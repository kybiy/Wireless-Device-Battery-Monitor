package br.com.wirelessbattery.device;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.ConnectionStatus;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.hid4java.HidDevice;
import org.hid4java.HidServices;
import org.hid4java.HidServicesListener;
import org.hid4java.event.HidServicesEvent;

/**
 * Gerenciador inteligente de dispositivos REAIS.
 *
 * Funcionalidades:
 * - Integra-se ao {@link DeviceDriverRegistry} para detecção automática e isolamento de VID:PID.
 * - Instancia e exibe drivers opcionais somente quando o hardware
 *   correspondente é detectado.
 * - Reage a eventos de conexão/desconexão USB (via {@link HidServicesListener}) com debounce.
 * - Detecta saída de suspensão do Windows (salto de relógio) e agenda atualização imediata.
 * - Preserva a última leitura conhecida durante repouso ou desconexão, diferenciando-a de leituras atuais.
 */
public final class DeviceManager implements AutoCloseable {
    private record LastKnown(int percent, LocalTime timestamp, Instant validInstant, long epochMillis) {}

    private static final class OpenFailureBackoff {
        int consecutiveFailures;
        boolean skipNextCycle;
        boolean backoffLogged;
    }

    public static final long DEFAULT_PER_DEVICE_TIMEOUT_MS = 3_500L;
    private static final int OPEN_FAILED_BACKOFF_THRESHOLD = 3;
    private static final int QUERY_QUEUE_CAPACITY = 16;
    private static final long ASLEEP_GRACE_MILLIS = 30L * 60L * 1000L; // 30 minutos
    private static final long SUSPEND_JUMP_THRESHOLD_MILLIS = 15_000L;
    private static final long USB_EVENT_DEBOUNCE_MILLIS = 1_800L;

    private final DeviceDriverRegistry registry = new DeviceDriverRegistry();
    private final List<BatteryDevice> directDevices = new ArrayList<>();
    private final Map<String, DeviceReading> readings = new LinkedHashMap<>();
    private final Map<String, LastKnown> lastKnownMap = new HashMap<>();
    private final Map<String, OpenFailureBackoff> openFailureBackoffs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> inFlightDevices = new ConcurrentHashMap<>();
    private final Set<String> seenDriverIds = new LinkedHashSet<>();
    private final Set<String> detectedDeviceIds = new LinkedHashSet<>();
    private final CopyOnWriteArrayList<Consumer<List<DeviceReading>>> listeners = new CopyOnWriteArrayList<>();

    private final HidServiceProvider hidProvider;
    private final AppLogger logger;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService queryExecutor;
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final AtomicLong cycleGeneration = new AtomicLong(0L);

    private volatile AppSettings settings;
    private volatile Runnable prePollHook;
    private volatile long perDeviceTimeoutMs;
    private ScheduledFuture<?> periodicTask;
    private ScheduledFuture<?> debounceTask;
    private ScheduledFuture<?> suspendWatchTask;
    private HidServicesListener hidListener;
    private volatile long baseIntervalSeconds = 20;
    private volatile long currentIntervalSeconds = 20;
    private volatile boolean ecoModeWindowHidden = false;
    private volatile long lastWatchdogWallMillis = System.currentTimeMillis();
    private volatile LocalTime lastPollTime;
    private volatile boolean closed;
    private String lastCycleSummarySignature;

    public DeviceManager(AppLogger logger) {
        this(null, null, logger, DEFAULT_PER_DEVICE_TIMEOUT_MS);
    }

    public DeviceManager(HidServiceProvider hidProvider, AppLogger logger) {
        this(hidProvider, null, logger, DEFAULT_PER_DEVICE_TIMEOUT_MS);
    }

    public DeviceManager(HidServiceProvider hidProvider, AppSettings settings, AppLogger logger) {
        this(hidProvider, settings, logger, DEFAULT_PER_DEVICE_TIMEOUT_MS);
    }

    public DeviceManager(
            HidServiceProvider hidProvider,
            AppSettings settings,
            AppLogger logger,
            long perDeviceTimeoutMs
    ) {
        this.hidProvider = hidProvider;
        this.settings = settings;
        this.logger = Objects.requireNonNull(logger, "logger não pode ser nulo");
        this.perDeviceTimeoutMs = Math.max(50L, perDeviceTimeoutMs);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wireless-battery-poller");
            t.setDaemon(true);
            return t;
        });
        this.queryExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUERY_QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "wireless-battery-device-query");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public synchronized void setSettings(AppSettings settings) {
        this.settings = settings;
        if (!closed && periodicTask != null && !periodicTask.isCancelled()) {
            long effective = computeEffectiveIntervalSeconds(this.baseIntervalSeconds);
            if (effective != this.currentIntervalSeconds) {
                reschedulePeriodicTask(effective, ecoModeWindowHidden ? effective : 0L);
            }
        }
    }

    public void setPrePollHook(Runnable prePollHook) {
        this.prePollHook = prePollHook;
    }

    public void setPerDeviceTimeoutMs(long perDeviceTimeoutMs) {
        this.perDeviceTimeoutMs = Math.max(50L, perDeviceTimeoutMs);
    }

    public long getPerDeviceTimeoutMs() {
        return perDeviceTimeoutMs;
    }

    public DeviceDriverRegistry getRegistry() {
        return registry;
    }

    /**
     * Registra um descritor de driver inteligente no {@link DeviceDriverRegistry}.
     */
    public synchronized void registerDriver(DeviceDriverRegistry.DriverRegistration registration) {
        Objects.requireNonNull(registration, "registration não pode ser nulo");
        registry.register(registration);
        if (!registration.optional()) {
            BatteryDevice device = registration.factory().get();
            validateRealDevice(device);
            readings.putIfAbsent(device.getId(), initialReadingFor(device));
        }
    }

    /**
     * Registra diretamente uma instância de {@link BatteryDevice} (mantido para compatibilidade
     * e testes unitários).
     */
    public synchronized void registerDevice(BatteryDevice device) {
        validateRealDevice(device);
        directDevices.removeIf(d -> d.getId().equals(device.getId()));
        directDevices.add(device);
        if (!device.isOptional()) {
            readings.put(device.getId(), initialReadingFor(device));
        }
    }

    private static void validateRealDevice(BatteryDevice device) {
        Objects.requireNonNull(device, "device não pode ser nulo");
        if (device.getSourceType() != DataSourceType.REAL) {
            throw new IllegalArgumentException(
                    "DeviceManager aceita apenas dispositivos REAIS. Use SimulationManager para dados simulados."
            );
        }
    }

    private static DeviceReading initialReadingFor(BatteryDevice device) {
        if (!device.supportsBatteryQuery()) {
            return DeviceReading.realPendingProtocol(
                    device.getId(),
                    device.getDisplayName(),
                    device.getDeviceType(),
                    device.getConnectionDescription(),
                    "Protocolo não implementado."
            );
        }
        return DeviceReading.realDisconnected(
                device.getId(),
                device.getDisplayName(),
                device.getDeviceType(),
                device.getConnectionDescription(),
                "Aguardando verificação..."
        );
    }

    public synchronized List<BatteryDevice> getRegisteredDevices() {
        Map<String, BatteryDevice> all = new LinkedHashMap<>();
        for (BatteryDevice d : registry.getKnownDriverInstances()) {
            all.put(d.getId(), d);
        }
        for (BatteryDevice d : directDevices) {
            all.put(d.getId(), d);
        }
        return Collections.unmodifiableList(new ArrayList<>(all.values()));
    }

    public LocalTime getLastPollTime() {
        return lastPollTime;
    }

    public void addListener(Consumer<List<DeviceReading>> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<List<DeviceReading>> listener) {
        listeners.remove(listener);
    }

    public synchronized List<DeviceReading> getCurrentReadings() {
        return Collections.unmodifiableList(new ArrayList<>(readings.values()));
    }

    public synchronized boolean isDeviceDetected(String deviceId) {
        return detectedDeviceIds.contains(deviceId);
    }

    public synchronized void startPolling(long intervalSeconds) {
        if (closed) {
            return;
        }
        attachHidHotplugListener();
        startSuspendWatchdog();
        updatePollInterval(intervalSeconds);
    }

    public synchronized void updatePollInterval(long intervalSeconds) {
        if (closed) {
            return;
        }
        this.baseIntervalSeconds = Math.max(5L, intervalSeconds);
        long effective = computeEffectiveIntervalSeconds(this.baseIntervalSeconds);
        if (periodicTask != null && !periodicTask.isCancelled() && effective == this.currentIntervalSeconds) {
            return;
        }
        reschedulePeriodicTask(effective, 0L);
    }

    public synchronized void setEcoModeWindowHidden(boolean hidden) {
        if (closed) {
            return;
        }
        this.ecoModeWindowHidden = hidden;
        long effective = computeEffectiveIntervalSeconds(this.baseIntervalSeconds);
        if (effective == this.currentIntervalSeconds) {
            return;
        }
        if (periodicTask != null && !periodicTask.isCancelled()) {
            reschedulePeriodicTask(effective, hidden ? effective : 0L);
        } else {
            this.currentIntervalSeconds = effective;
        }
    }

    public boolean isEcoModeWindowHidden() {
        return ecoModeWindowHidden;
    }

    public long getEffectivePollIntervalSeconds() {
        return currentIntervalSeconds;
    }

    private long computeEffectiveIntervalSeconds(long baseSeconds) {
        AppSettings currentSettings = this.settings;
        if (ecoModeWindowHidden && currentSettings != null && currentSettings.isEcoModeEnabled()) {
            return Math.max(Math.min(60L, baseSeconds * 2L), 30L);
        }
        return baseSeconds;
    }

    private void reschedulePeriodicTask(long effectiveSeconds, long initialDelaySeconds) {
        this.currentIntervalSeconds = effectiveSeconds;
        if (periodicTask != null) {
            periodicTask.cancel(false);
        }
        String ecoTag = (ecoModeWindowHidden && settings != null && settings.isEcoModeEnabled())
                ? " (modo eco)" : "";
        logger.info("Monitoramento automático configurado para cada " + effectiveSeconds + " s" + ecoTag + ".");
        periodicTask = scheduler.scheduleWithFixedDelay(
                this::pollAllInternal, initialDelaySeconds, effectiveSeconds, TimeUnit.SECONDS
        );
    }

    public void refreshNow() {
        if (closed) {
            return;
        }
        scheduler.execute(this::pollAllInternal);
    }

    /**
     * Agenda uma atualização com debounce (ex.: após conexão USB ou saída de suspensão),
     * evitando consultas duplicadas em sequência rápida.
     */
    public synchronized void scheduleDebouncedRefresh(long delayMillis, String reason) {
        if (closed) {
            return;
        }
        if (debounceTask != null && !debounceTask.isDone()) {
            debounceTask.cancel(false);
        }
        if (reason != null && !reason.isBlank()) {
            logger.info(reason);
        }
        debounceTask = scheduler.schedule(this::pollAllInternal, Math.max(200, delayMillis), TimeUnit.MILLISECONDS);
    }

    /**
     * Executa uma rodada síncrona de consulta (útil também para testes unitários).
     */
    public List<DeviceReading> pollOnce() {
        return pollAllInternal();
    }

    private void attachHidHotplugListener() {
        if (hidProvider == null || hidListener != null) {
            return;
        }
        try {
            HidServices services = hidProvider.getServices();
            hidListener = new HidServicesListener() {
                @Override
                public void hidDeviceAttached(HidServicesEvent event) {
                    scheduleDebouncedRefresh(USB_EVENT_DEBOUNCE_MILLIS,
                            "Dispositivo USB/HID conectado — atualizando leituras...");
                }

                @Override
                public void hidDeviceDetached(HidServicesEvent event) {
                    scheduleDebouncedRefresh(USB_EVENT_DEBOUNCE_MILLIS,
                            "Dispositivo USB/HID desconectado — atualizando estado...");
                }

                @Override
                public void hidFailure(HidServicesEvent event) {
                    // Ignorado silenciosamente
                }

                @Override
                public void hidDataReceived(HidServicesEvent event) {
                    // Ignorado silenciosamente
                }
            };
            services.addHidServicesListener(hidListener);
        } catch (Exception e) {
            logger.warn("Monitoramento de eventos USB (hotplug) indisponível: " + e.getMessage());
        }
    }

    private void startSuspendWatchdog() {
        if (suspendWatchTask != null) {
            return;
        }
        lastWatchdogWallMillis = System.currentTimeMillis();
        suspendWatchTask = scheduler.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            long elapsed = now - lastWatchdogWallMillis;
            lastWatchdogWallMillis = now;
            if (elapsed > 5_000L + SUSPEND_JUMP_THRESHOLD_MILLIS) {
                scheduleDebouncedRefresh(3_500L,
                        "Sistema retornou de suspensão — agendando atualização dos dispositivos...");
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private List<DeviceReading> pollAllInternal() {
        if (closed || !polling.compareAndSet(false, true)) {
            return getCurrentReadings();
        }
        final long myGeneration = cycleGeneration.incrementAndGet();
        try {
            Runnable hook = this.prePollHook;
            if (hook != null) {
                try {
                    hook.run();
                } catch (Exception e) {
                    logger.warn("Falha no hook de pré-atualização: " + e.getMessage());
                }
            }
            if (closed || cycleGeneration.get() != myGeneration) {
                return getCurrentReadings();
            }

            List<HidDevice> attachedHid = enumerateHidOnce();
            List<BatteryDevice> toPoll = resolveDevicesToPoll(attachedHid);

            Map<String, DeviceReading> cycleReadings = new LinkedHashMap<>();
            Set<String> cycleDetected = new LinkedHashSet<>();
            for (BatteryDevice device : toPoll) {
                if (closed || cycleGeneration.get() != myGeneration) {
                    return getCurrentReadings();
                }
                DeviceReading previous;
                synchronized (this) {
                    previous = readings.get(device.getId());
                }

                DeviceReading reading = querySingleDevice(device, attachedHid, previous);
                if (closed || cycleGeneration.get() != myGeneration) {
                    return getCurrentReadings();
                }

                boolean hardwareHere = device.isHardwarePresent(attachedHid)
                        || reading.hasBatteryPercent()
                        || reading.status().isReceiverDetectedState();
                if (hardwareHere) {
                    cycleDetected.add(device.getId());
                }
                synchronized (this) {
                    if (closed || cycleGeneration.get() != myGeneration) {
                        return getCurrentReadings();
                    }
                    reading = enrichWithLastKnownAndSleepState(device, reading, attachedHid);
                    logTransitionIfChanged(previous, reading);
                    cycleReadings.put(device.getId(), reading);
                }
            }

            List<DeviceReading> immutable;
            synchronized (this) {
                if (closed || cycleGeneration.get() != myGeneration) {
                    return getCurrentReadings();
                }
                readings.clear();
                readings.putAll(cycleReadings);
                detectedDeviceIds.clear();
                detectedDeviceIds.addAll(cycleDetected);
                lastPollTime = LocalTime.now().withNano(0);
                immutable = Collections.unmodifiableList(new ArrayList<>(readings.values()));
                logDetectionCycleSummaryIfChanged(attachedHid.size(), immutable, cycleDetected);
            }

            if (!closed && cycleGeneration.get() == myGeneration) {
                for (Consumer<List<DeviceReading>> listener : listeners) {
                    listener.accept(immutable);
                }
            }
            return immutable;
        } finally {
            polling.set(false);
        }
    }

    private List<HidDevice> enumerateHidOnce() {
        if (hidProvider == null) {
            return List.of();
        }
        try {
            return hidProvider.getServices().getAttachedHidDevices();
        } catch (Exception e) {
            logger.warn("Não foi possível enumerar dispositivos HID: " + e.getMessage());
            return List.of();
        }
    }

    private synchronized List<BatteryDevice> resolveDevicesToPoll(List<HidDevice> attachedHid) {
        Map<String, BatteryDevice> ordered = new LinkedHashMap<>();

        List<DeviceDriverRegistry.ActiveDriverBind> binds =
                registry.resolveActiveDrivers(attachedHid, seenDriverIds);
        for (DeviceDriverRegistry.ActiveDriverBind bind : binds) {
            if (bind.hardwareDetected()) {
                seenDriverIds.add(bind.registration().driverId());
            }
            ordered.put(bind.driver().getId(), bind.driver());
        }

        Set<String> seenCopy = new HashSet<>(seenDriverIds);
        for (BatteryDevice direct : directDevices) {
            boolean detected = direct.isHardwarePresent(attachedHid);
            if (detected) {
                seenDriverIds.add(direct.getId());
            }
            if (direct.isOptional() && !detected && !seenCopy.contains(direct.getId())) {
                continue;
            }
            ordered.putIfAbsent(direct.getId(), direct);
        }

        return new ArrayList<>(ordered.values());
    }

    private DeviceReading querySingleDevice(
            BatteryDevice device,
            List<HidDevice> attachedHid,
            DeviceReading previous
    ) {
        String deviceId = device.getId();
        AppSettings currentSettings = this.settings;

        if (currentSettings != null
                && !currentSettings.isGameSirMonitoringEnabled()
                && GameSirCyclone2.DEVICE_ID.equals(deviceId)) {
            openFailureBackoffs.remove(deviceId);
            return DeviceReading.realMonitoringDisabled(
                    deviceId,
                    device.getDisplayName(),
                    device.getDeviceType(),
                    device.getConnectionDescription(),
                    "Monitoramento do GameSir Cyclone 2 desativado nas configurações."
            );
        }

        if (currentSettings != null
                && currentSettings.isReadOnlyHidMode()
                && !device.getCapabilities().safetyInfo().allowedInReadOnlyMode()) {
            openFailureBackoffs.remove(deviceId);
            String detail = GameSirCyclone2.DEVICE_ID.equals(deviceId)
                    ? "Consulta bloqueada pelo modo somente leitura (envia comando HID 0x0F 0x03)."
                    : "Consulta bloqueada pelo modo somente leitura.";
            return DeviceReading.realReadOnlyBlocked(
                    deviceId,
                    device.getDisplayName(),
                    device.getDeviceType(),
                    device.getConnectionDescription(),
                    detail
            );
        }

        OpenFailureBackoff backoff = openFailureBackoffs.get(deviceId);
        if (backoff != null && backoff.skipNextCycle) {
            backoff.skipNextCycle = false;
            String conn = previous != null ? previous.connectionLabel() : device.getConnectionDescription();
            String detail = (previous != null && !previous.detailMessage().isBlank())
                    ? previous.detailMessage()
                    : "Interface HID ocupada (aguardando próximo ciclo).";
            return DeviceReading.realOpenFailed(
                    deviceId,
                    device.getDisplayName(),
                    device.getDeviceType(),
                    conn,
                    detail
            );
        }

        if (inFlightDevices.putIfAbsent(deviceId, Boolean.TRUE) != null) {
            return DeviceReading.realTimeout(
                    deviceId,
                    device.getDisplayName(),
                    device.getDeviceType(),
                    device.getConnectionDescription(),
                    "Consulta anterior ainda em andamento (timeout ativo)."
            );
        }

        Future<DeviceReading> future;
        try {
            future = queryExecutor.submit(() -> {
                try {
                    return executeDeviceRead(device, attachedHid);
                } finally {
                    inFlightDevices.remove(deviceId);
                }
            });
        } catch (RejectedExecutionException rejected) {
            inFlightDevices.remove(deviceId);
            if (closed) {
                return DeviceReading.realDisconnected(
                        deviceId,
                        device.getDisplayName(),
                        device.getDeviceType(),
                        device.getConnectionDescription(),
                        "Gerenciador encerrado."
                );
            }
            return DeviceReading.realTimeout(
                    deviceId,
                    device.getDisplayName(),
                    device.getDeviceType(),
                    device.getConnectionDescription(),
                    "Fila de consulta HID saturada."
            );
        }

        DeviceReading result;
        long timeoutMs = this.perDeviceTimeoutMs;
        try {
            result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            result = DeviceReading.realTimeout(
                    deviceId,
                    device.getDisplayName(),
                    device.getDeviceType(),
                    device.getConnectionDescription(),
                    "Timeout (" + timeoutMs + " ms) ao consultar dispositivo."
            );
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            result = DeviceReading.realTimeout(
                    deviceId,
                    device.getDisplayName(),
                    device.getDeviceType(),
                    device.getConnectionDescription(),
                    "Consulta interrompida."
            );
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            logger.error("Erro inesperado ao consultar " + device.getDisplayName(), cause);
            result = DeviceReading.realDisconnected(
                    deviceId,
                    device.getDisplayName(),
                    device.getDeviceType(),
                    device.getConnectionDescription(),
                    "Erro na consulta: " + cause.getMessage()
            );
        }

        if (!closed) {
            updateOpenFailureBackoff(device, result);
        }
        return result;
    }

    private void updateOpenFailureBackoff(BatteryDevice device, DeviceReading result) {
        String deviceId = device.getId();
        if (result.status() == ConnectionStatus.OPEN_FAILED) {
            OpenFailureBackoff backoff = openFailureBackoffs.computeIfAbsent(deviceId, k -> new OpenFailureBackoff());
            backoff.consecutiveFailures++;
            if (backoff.consecutiveFailures >= OPEN_FAILED_BACKOFF_THRESHOLD) {
                backoff.skipNextCycle = true;
                if (!backoff.backoffLogged) {
                    backoff.backoffLogged = true;
                    logger.warn(device.getDisplayName() + " — Interface HID ocupada por "
                            + backoff.consecutiveFailures
                            + " ciclos consecutivos; aplicando backoff 2x para evitar contenção.");
                }
            }
        } else {
            openFailureBackoffs.remove(deviceId);
        }
    }

    private DeviceReading executeDeviceRead(BatteryDevice device, List<HidDevice> attachedHid) {
        try {
            DeviceReading reading = attachedHid.isEmpty()
                    ? device.readBattery()
                    : device.readBattery(attachedHid);
            if (reading == null) {
                return DeviceReading.realDisconnected(
                        device.getId(),
                        device.getDisplayName(),
                        device.getDeviceType(),
                        device.getConnectionDescription(),
                        "Desconectado."
                );
            }
            if (reading.isSimulated()) {
                throw new IllegalStateException("Driver real retornou leitura simulada: " + device.getId());
            }
            return reading;
        } catch (Exception e) {
            logger.error("Erro inesperado ao consultar " + device.getDisplayName(), e);
            return DeviceReading.realDisconnected(
                    device.getId(),
                    device.getDisplayName(),
                    device.getDeviceType(),
                    device.getConnectionDescription(),
                    "Erro na consulta: " + e.getMessage()
            );
        }
    }

    private DeviceReading enrichWithLastKnownAndSleepState(
            BatteryDevice device,
            DeviceReading reading,
            List<HidDevice> attachedHid
    ) {
        long nowMs = System.currentTimeMillis();
        if (reading.hasBatteryPercent()) {
            Instant validInstant = reading.validReadingInstant() != null
                    ? reading.validReadingInstant()
                    : Instant.now();
            lastKnownMap.put(
                    device.getId(),
                    new LastKnown(reading.batteryPercent(), reading.updatedAt(), validInstant, nowMs)
            );
            seenDriverIds.add(device.getId());
            return reading;
        }

        LastKnown known = lastKnownMap.get(device.getId());
        if (known != null) {
            reading = reading.withLastKnown(known.percent(), known.timestamp(), known.validInstant());
            boolean withinGrace = (nowMs - known.epochMillis()) <= ASLEEP_GRACE_MILLIS;
            boolean receiverPresent = device.isHardwarePresent(attachedHid);
            if (reading.status() == ConnectionStatus.DISCONNECTED && withinGrace && receiverPresent) {
                reading = reading.withStatus(
                        ConnectionStatus.ASLEEP,
                        "Em repouso (última leitura preservada)."
                );
            }
        }
        return reading;
    }

    private void logTransitionIfChanged(DeviceReading previous, DeviceReading current) {
        if (previous == null) {
            if (current.status().isOnline()) {
                logger.info(current.displayName() + " detectado (" + current.batteryPercent() + "%).");
            }
            return;
        }
        ConnectionStatus prevStatus = previous.status();
        ConnectionStatus currStatus = current.status();
        if (prevStatus != currStatus) {
            if (currStatus.isOnline()) {
                logger.info(current.displayName() + " conectado (" + current.batteryPercent() + "%).");
            } else if (currStatus == ConnectionStatus.ASLEEP) {
                logger.info(current.displayName() + " em repouso confirmado.");
            } else if (currStatus == ConnectionStatus.NO_RESPONSE
                    || currStatus == ConnectionStatus.OPEN_FAILED
                    || currStatus == ConnectionStatus.READ_FAILED
                    || currStatus == ConnectionStatus.TIMEOUT) {
                logger.warn(current.displayName() + " — " + currStatus.getLabel()
                        + (current.detailMessage().isBlank() ? "" : ": " + current.detailMessage()));
            } else if (currStatus == ConnectionStatus.PENDING_PROTOCOL) {
                logger.info(current.displayName() + " — " + currStatus.getLabel() + ".");
            } else if (prevStatus.isOnline() || prevStatus.isReceiverDetectedState()) {
                logger.info(current.displayName() + " desconectado.");
            }
        }
    }

    private void logDetectionCycleSummaryIfChanged(
            int totalHidCount,
            List<DeviceReading> currentReadings,
            Set<String> cycleDetected
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("HID=").append(totalHidCount).append(" | ");
        if (currentReadings.isEmpty()) {
            sb.append("nenhum dispositivo ativo");
        } else {
            for (int i = 0; i < currentReadings.size(); i++) {
                DeviceReading r = currentReadings.get(i);
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(r.displayName())
                        .append("=")
                        .append(r.status().name())
                        .append(" (")
                        .append(r.formattedBatteryText())
                        .append(", ")
                        .append(r.status().getLabel())
                        .append(", detectado=")
                        .append(cycleDetected.contains(r.deviceId()))
                        .append(")");
            }
        }
        String signature = sb.toString();
        if (!signature.equals(lastCycleSummarySignature)) {
            lastCycleSummarySignature = signature;
            logger.info("[DETECÇÃO] " + signature);
        }
    }

    @Override
    public void close() {
        closed = true;
        if (periodicTask != null) {
            periodicTask.cancel(true);
        }
        if (debounceTask != null) {
            debounceTask.cancel(false);
        }
        if (suspendWatchTask != null) {
            suspendWatchTask.cancel(false);
        }
        scheduler.shutdownNow();
        queryExecutor.shutdownNow();
    }
}
