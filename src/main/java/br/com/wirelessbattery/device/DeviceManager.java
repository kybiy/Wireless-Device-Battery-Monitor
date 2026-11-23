package br.com.wirelessbattery.device;

import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.ConnectionStatus;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private record LastKnown(int percent, LocalTime timestamp, long epochMillis) {}

    private static final long ASLEEP_GRACE_MILLIS = 30L * 60L * 1000L; // 30 minutos
    private static final long SUSPEND_JUMP_THRESHOLD_MILLIS = 15_000L;
    private static final long USB_EVENT_DEBOUNCE_MILLIS = 1_800L;

    private final DeviceDriverRegistry registry = new DeviceDriverRegistry();
    private final List<BatteryDevice> directDevices = new ArrayList<>();
    private final Map<String, DeviceReading> readings = new LinkedHashMap<>();
    private final Map<String, LastKnown> lastKnownMap = new HashMap<>();
    private final Set<String> seenDriverIds = new LinkedHashSet<>();
    private final Set<String> detectedDeviceIds = new LinkedHashSet<>();
    private final CopyOnWriteArrayList<Consumer<List<DeviceReading>>> listeners = new CopyOnWriteArrayList<>();

    private final HidServiceProvider hidProvider;
    private final AppLogger logger;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    private ScheduledFuture<?> periodicTask;
    private ScheduledFuture<?> debounceTask;
    private ScheduledFuture<?> suspendWatchTask;
    private HidServicesListener hidListener;
    private volatile long currentIntervalSeconds = 20;
    private volatile long lastWatchdogWallMillis = System.currentTimeMillis();
    private volatile LocalTime lastPollTime;
    private volatile boolean closed;

    public DeviceManager(AppLogger logger) {
        this(null, logger);
    }

    public DeviceManager(HidServiceProvider hidProvider, AppLogger logger) {
        this.hidProvider = hidProvider;
        this.logger = Objects.requireNonNull(logger, "logger não pode ser nulo");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wireless-battery-poller");
            t.setDaemon(true);
            return t;
        });
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
        this.currentIntervalSeconds = Math.max(5, intervalSeconds);
        if (periodicTask != null) {
            periodicTask.cancel(false);
        }
        logger.info("Monitoramento automático configurado para cada " + currentIntervalSeconds + " s.");
        periodicTask = scheduler.scheduleWithFixedDelay(
                this::pollAllInternal, 0, currentIntervalSeconds, TimeUnit.SECONDS
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
        try {
            List<HidDevice> attachedHid = enumerateHidOnce();
            List<BatteryDevice> toPoll = resolveDevicesToPoll(attachedHid);

            Map<String, DeviceReading> cycleReadings = new LinkedHashMap<>();
            Set<String> cycleDetected = new LinkedHashSet<>();
            for (BatteryDevice device : toPoll) {
                DeviceReading previous;
                synchronized (this) {
                    previous = readings.get(device.getId());
                }

                DeviceReading reading = querySingleDevice(device, attachedHid);
                boolean hardwareHere = device.isHardwarePresent(attachedHid)
                        || reading.hasBatteryPercent()
                        || reading.status() == ConnectionStatus.ASLEEP;
                if (hardwareHere) {
                    cycleDetected.add(device.getId());
                }
                synchronized (this) {
                    reading = enrichWithLastKnownAndSleepState(device, reading, attachedHid);
                    logTransitionIfChanged(previous, reading);
                    cycleReadings.put(device.getId(), reading);
                }
            }

            List<DeviceReading> immutable;
            synchronized (this) {
                readings.clear();
                readings.putAll(cycleReadings);
                detectedDeviceIds.clear();
                detectedDeviceIds.addAll(cycleDetected);
                lastPollTime = LocalTime.now().withNano(0);
                immutable = Collections.unmodifiableList(new ArrayList<>(readings.values()));
            }

            for (Consumer<List<DeviceReading>> listener : listeners) {
                listener.accept(immutable);
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

    private DeviceReading querySingleDevice(BatteryDevice device, List<HidDevice> attachedHid) {
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
            lastKnownMap.put(device.getId(), new LastKnown(reading.batteryPercent(), reading.updatedAt(), nowMs));
            seenDriverIds.add(device.getId());
            return reading;
        }

        LastKnown known = lastKnownMap.get(device.getId());
        if (known != null) {
            reading = reading.withLastKnown(known.percent(), known.timestamp());
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
                logger.info(current.displayName() + " entrou em repouso.");
            } else if (prevStatus.isOnline() || prevStatus == ConnectionStatus.ASLEEP) {
                logger.info(current.displayName() + " desconectado.");
            }
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
    }
}
