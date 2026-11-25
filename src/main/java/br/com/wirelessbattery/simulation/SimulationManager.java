package br.com.wirelessbattery.simulation;

import br.com.wirelessbattery.audio.AudioEndpointInfo;
import br.com.wirelessbattery.audio.DefaultAudioOutputProvider;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Gerenciador isolado para o modo de simulação.
 * Funciona sem dispositivos físicos conectados e mantém os dados simulados
 * completamente separados do {@link br.com.wirelessbattery.device.DeviceManager}.
 *
 * Simula os dispositivos suportados (Delux M800 Pro, Akko Verge S9 Ultra,
 * QCY T13 e GameSir Cyclone 2) além de cenários de áudio e Bluetooth.
 */
public final class SimulationManager {
    public static final String SIM_DELUX_ID = "sim-delux-m800-pro";
    public static final String SIM_AKKO_ID = "sim-akko-verge-s9";
    public static final String SIM_QCY_T13_ID = "sim-qcy-t13";
    public static final String SIM_CYCLONE2_ID = "sim-gamesir-cyclone-2";
    public static final String SIM_SYSTEM_SPEAKERS_ID = "system-speakers";

    private final Map<String, SimulatedBatteryDevice> simulatedDevices = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Consumer<List<DeviceReading>>> listeners = new CopyOnWriteArrayList<>();
    private final AppLogger logger;
    private boolean enabled;
    private boolean bluetoothAdapterAvailable = true;
    private String defaultAudioDeviceId = SIM_AKKO_ID;
    private SimulationScenario activeScenario;

    public SimulationManager(AppLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger não pode ser nulo");
        registerDefaultDevices();
    }

    private void registerDefaultDevices() {
        addSimulatedDevice(new SimulatedBatteryDevice(
                SIM_DELUX_ID,
                "Delux M800 Pro",
                DeviceType.MOUSE,
                "2.4 GHz",
                85,
                false,
                true,
                false
        ));
        addSimulatedDevice(new SimulatedBatteryDevice(
                SIM_AKKO_ID,
                "Akko Verge S9 Ultra",
                DeviceType.HEADSET,
                "2.4 GHz",
                62,
                false,
                true,
                false
        ));
        addSimulatedDevice(new SimulatedBatteryDevice(
                SIM_QCY_T13_ID,
                "QCY T13",
                DeviceType.HEADSET,
                "Bluetooth",
                74,
                false,
                false,
                true
        ));
        addSimulatedDevice(new SimulatedBatteryDevice(
                SIM_CYCLONE2_ID,
                "GameSir Cyclone 2",
                DeviceType.GAMEPAD,
                "2.4 GHz / USB",
                90,
                false,
                true,
                false
        ));
    }

    private void addSimulatedDevice(SimulatedBatteryDevice device) {
        simulatedDevices.put(device.getId(), device);
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        logger.info(enabled
                ? "Modo de simulação ATIVADO (dados simulados exibidos separadamente das leituras reais)."
                : "Modo de simulação DESATIVADO.");
        notifyListeners();
    }

    public synchronized boolean isBluetoothAdapterAvailable() {
        return bluetoothAdapterAvailable;
    }

    public synchronized void setBluetoothAdapterAvailable(boolean available) {
        this.bluetoothAdapterAvailable = available;
        logger.info(available
                ? "[SIMULAÇÃO] Adaptador Bluetooth simulado ativado."
                : "[SIMULAÇÃO] Adaptador Bluetooth simulado ausente ou desativado.");
        notifyListeners();
    }

    public synchronized String getDefaultAudioDeviceId() {
        return defaultAudioDeviceId;
    }

    public synchronized void setDefaultAudioDeviceId(String deviceId) {
        if (SIM_AKKO_ID.equals(deviceId)
                || SIM_QCY_T13_ID.equals(deviceId)
                || SIM_SYSTEM_SPEAKERS_ID.equals(deviceId)) {
            this.defaultAudioDeviceId = deviceId;
        } else if (deviceId == null || deviceId.isBlank()) {
            this.defaultAudioDeviceId = SIM_SYSTEM_SPEAKERS_ID;
        } else {
            throw new IllegalArgumentException("Dispositivo de áudio simulado inválido: " + deviceId);
        }
        logger.info("[SIMULAÇÃO] Saída de áudio padrão simulada definida para: " + this.defaultAudioDeviceId);
        notifyListeners();
    }

    public synchronized SimulationScenario getActiveScenario() {
        return activeScenario;
    }

    public DefaultAudioOutputProvider getSimulatedAudioOutputProvider() {
        return () -> {
            synchronized (SimulationManager.this) {
                SimulatedBatteryDevice qcy = simulatedDevices.get(SIM_QCY_T13_ID);
                SimulatedBatteryDevice akko = simulatedDevices.get(SIM_AKKO_ID);
                if (SIM_QCY_T13_ID.equals(defaultAudioDeviceId)
                        && qcy != null
                        && qcy.isConnected()
                        && bluetoothAdapterAvailable) {
                    return Optional.of(new AudioEndpointInfo(
                            "sim-audio-qcy",
                            "Fones de ouvido (QCY T13)",
                            "QCY T13 Stereo",
                            "sim-bt-qcy-t13"
                    ));
                }
                if (SIM_AKKO_ID.equals(defaultAudioDeviceId)
                        && akko != null
                        && akko.isConnected()) {
                    return Optional.of(new AudioEndpointInfo(
                            "sim-audio-akko",
                            "Alto-falantes (Akko Verge S9 Ultra)",
                            "Akko S9 Ultra",
                            null
                    ));
                }
                return Optional.of(new AudioEndpointInfo(
                        "sim-audio-speakers",
                        "Alto-falantes (Realtek High Definition Audio)",
                        "Realtek Audio",
                        null
                ));
            }
        };
    }

    public synchronized List<SimulatedBatteryDevice> getSimulatedDevices() {
        return Collections.unmodifiableList(new ArrayList<>(simulatedDevices.values()));
    }

    public synchronized SimulatedBatteryDevice getDevice(String id) {
        return simulatedDevices.get(id);
    }

    public synchronized void updateDevice(String id, int percent, boolean charging, boolean connected) {
        updateDevice(id, percent, charging, connected, false);
    }

    public synchronized void updateDevice(
            String id,
            int percent,
            boolean charging,
            boolean connected,
            boolean asleep
    ) {
        SimulatedBatteryDevice device = requireDevice(id);
        device.setConnected(connected);
        device.setAsleep(asleep);
        device.setCharging(charging);
        device.setBatteryPercent(percent);
        notifyListeners();
    }

    public synchronized void applyScenario(SimulationScenario scenario) {
        Objects.requireNonNull(scenario, "scenario não pode ser nulo");
        SimulatedBatteryDevice akko = requireDevice(SIM_AKKO_ID);
        SimulatedBatteryDevice qcy = requireDevice(SIM_QCY_T13_ID);
        this.activeScenario = scenario;

        switch (scenario) {
            case ONLY_AKKO_CONNECTED -> {
                this.bluetoothAdapterAvailable = true;
                configureNormalState(akko, 62, true);
                configureNormalState(qcy, 74, false);
                this.defaultAudioDeviceId = SIM_AKKO_ID;
                logger.info("[SIMULAÇÃO] Cenário aplicado: " + scenario.getLabel());
                notifyListeners();
            }
            case ONLY_QCY_CONNECTED -> {
                this.bluetoothAdapterAvailable = true;
                configureNormalState(akko, 62, false);
                configureNormalState(qcy, 74, true);
                this.defaultAudioDeviceId = SIM_QCY_T13_ID;
                logger.info("[SIMULAÇÃO] Cenário aplicado: " + scenario.getLabel());
                notifyListeners();
            }
            case BOTH_AKKO_DEFAULT_AUDIO -> {
                this.bluetoothAdapterAvailable = true;
                configureNormalState(akko, 62, true);
                configureNormalState(qcy, 74, true);
                this.defaultAudioDeviceId = SIM_AKKO_ID;
                logger.info("[SIMULAÇÃO] Cenário aplicado: " + scenario.getLabel());
                notifyListeners();
            }
            case BOTH_QCY_DEFAULT_AUDIO -> {
                this.bluetoothAdapterAvailable = true;
                configureNormalState(akko, 62, true);
                configureNormalState(qcy, 74, true);
                this.defaultAudioDeviceId = SIM_QCY_T13_ID;
                logger.info("[SIMULAÇÃO] Cenário aplicado: " + scenario.getLabel());
                notifyListeners();
            }
            case NO_HEADSET_CONNECTED -> {
                this.bluetoothAdapterAvailable = true;
                configureNormalState(akko, 62, false);
                configureNormalState(qcy, 74, false);
                this.defaultAudioDeviceId = SIM_SYSTEM_SPEAKERS_ID;
                logger.info("[SIMULAÇÃO] Cenário aplicado: " + scenario.getLabel());
                notifyListeners();
            }
            case AKKO_DONGLE_PRESENT_HEADSET_OFF -> {
                this.bluetoothAdapterAvailable = true;
                akko.clearLastKnown();
                akko.setReceiverPresentOff(true);
                configureNormalState(qcy, 74, false);
                this.defaultAudioDeviceId = SIM_SYSTEM_SPEAKERS_ID;
                logger.info("[SIMULAÇÃO] Cenário aplicado: " + scenario.getLabel());
                notifyListeners();
            }
            case QCY_CONNECTED_BATTERY_UNAVAILABLE -> {
                this.bluetoothAdapterAvailable = true;
                configureNormalState(akko, 62, false);
                qcy.setBatteryUnavailable(true);
                this.defaultAudioDeviceId = SIM_QCY_T13_ID;
                logger.info("[SIMULAÇÃO] Cenário aplicado: " + scenario.getLabel());
                notifyListeners();
            }
            case SWITCH_AKKO_TO_QCY -> {
                this.bluetoothAdapterAvailable = true;
                configureNormalState(akko, 62, true);
                configureNormalState(qcy, 74, false);
                this.defaultAudioDeviceId = SIM_AKKO_ID;
                logger.info("[SIMULAÇÃO] Cenário 8 (1/2): Akko Verge S9 Ultra conectado como saída padrão (62%).");
                notifyListeners();

                configureNormalState(akko, 62, false);
                configureNormalState(qcy, 74, true);
                this.defaultAudioDeviceId = SIM_QCY_T13_ID;
                logger.info("[SIMULAÇÃO] Cenário 8 (2/2): troca automática para QCY T13 concluída (74%).");
                notifyListeners();
            }
            case SWITCH_QCY_TO_AKKO -> {
                this.bluetoothAdapterAvailable = true;
                configureNormalState(qcy, 74, true);
                configureNormalState(akko, 62, false);
                this.defaultAudioDeviceId = SIM_QCY_T13_ID;
                logger.info("[SIMULAÇÃO] Cenário 9 (1/2): QCY T13 conectado como saída padrão (74%).");
                notifyListeners();

                configureNormalState(qcy, 74, false);
                configureNormalState(akko, 62, true);
                this.defaultAudioDeviceId = SIM_AKKO_ID;
                logger.info("[SIMULAÇÃO] Cenário 9 (2/2): troca automática para Akko Verge S9 Ultra concluída (62%).");
                notifyListeners();
            }
            case TEMPORARY_READ_FAILURE -> {
                this.bluetoothAdapterAvailable = true;
                SimulatedBatteryDevice target = resolveTargetHeadsetForTransientFailure(akko, qcy);
                int targetPercent = resolveBaselinePercentForTransientFailure(target, qcy);
                configureNormalState(target, targetPercent, true);
                this.defaultAudioDeviceId = target.getId();
                logger.info("[SIMULAÇÃO] Cenário 10 (1/2): " + target.getDisplayName()
                        + " conectado com leitura inicial (" + targetPercent + "%).");
                notifyListeners();

                target.setTransientFailure(true);
                logger.info("[SIMULAÇÃO] Cenário 10 (2/2): falha temporária de leitura em "
                        + target.getDisplayName() + " (última leitura preservada).");
                notifyListeners();
            }
            case BLUETOOTH_DISCONNECT_AND_RECONNECT -> {
                this.bluetoothAdapterAvailable = true;
                configureNormalState(akko, 62, false);
                configureNormalState(qcy, 74, true);
                this.defaultAudioDeviceId = SIM_QCY_T13_ID;
                logger.info("[SIMULAÇÃO] Cenário 11 (1/3): QCY T13 conectado via Bluetooth (74%).");
                notifyListeners();

                qcy.setConnected(false);
                logger.info("[SIMULAÇÃO] Cenário 11 (2/3): QCY T13 desconectado via Bluetooth.");
                notifyListeners();

                qcy.setConnected(true);
                qcy.setBatteryPercent(74);
                logger.info("[SIMULAÇÃO] Cenário 11 (3/3): QCY T13 reconectado via Bluetooth (74%).");
                notifyListeners();
            }
            case BLUETOOTH_ADAPTER_MISSING -> {
                this.bluetoothAdapterAvailable = false;
                configureNormalState(akko, 62, false);
                qcy.clearLastKnown();
                configureNormalState(qcy, 74, false);
                this.defaultAudioDeviceId = SIM_SYSTEM_SPEAKERS_ID;
                logger.info("[SIMULAÇÃO] Cenário aplicado: " + scenario.getLabel());
                notifyListeners();
            }
        }
    }

    public synchronized List<DeviceReading> getCurrentReadings() {
        if (!enabled) {
            return List.of();
        }
        List<DeviceReading> list = new ArrayList<>(simulatedDevices.size());
        for (SimulatedBatteryDevice device : simulatedDevices.values()) {
            if (shouldIncludeInCurrentReadings(device)) {
                list.add(device.readBattery(bluetoothAdapterAvailable));
            }
        }
        return Collections.unmodifiableList(list);
    }

    public synchronized List<DeviceReading> getCandidateReadings() {
        if (!enabled) {
            return List.of();
        }
        List<DeviceReading> list = new ArrayList<>(simulatedDevices.size());
        for (SimulatedBatteryDevice device : simulatedDevices.values()) {
            list.add(device.readBattery(bluetoothAdapterAvailable));
        }
        return Collections.unmodifiableList(list);
    }

    public void addListener(Consumer<List<DeviceReading>> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<List<DeviceReading>> listener) {
        listeners.remove(listener);
    }

    private SimulatedBatteryDevice requireDevice(String id) {
        SimulatedBatteryDevice device = simulatedDevices.get(id);
        if (device == null) {
            throw new IllegalArgumentException("Dispositivo simulado não encontrado: " + id);
        }
        return device;
    }

    private static void configureNormalState(SimulatedBatteryDevice device, int percent, boolean connected) {
        device.clearSimulatedFaults();
        device.setAsleep(false);
        device.setCharging(false);
        device.setConnected(connected);
        device.setBatteryPercent(percent);
    }

    private SimulatedBatteryDevice resolveTargetHeadsetForTransientFailure(
            SimulatedBatteryDevice akko,
            SimulatedBatteryDevice qcy
    ) {
        if (qcy.isConnected() && SIM_QCY_T13_ID.equals(defaultAudioDeviceId)) {
            return qcy;
        }
        if (akko.isConnected() && SIM_AKKO_ID.equals(defaultAudioDeviceId)) {
            return akko;
        }
        if (qcy.isConnected()) {
            return qcy;
        }
        if (akko.isConnected()) {
            return akko;
        }
        return qcy;
    }

    private static int resolveBaselinePercentForTransientFailure(
            SimulatedBatteryDevice target,
            SimulatedBatteryDevice qcy
    ) {
        if (target.isConnected() && !target.isBatteryUnavailable()) {
            return target.getBatteryPercent();
        }
        return target == qcy ? 74 : 62;
    }

    private static boolean shouldIncludeInCurrentReadings(SimulatedBatteryDevice device) {
        if (!device.isOptional()) {
            return true;
        }
        return device.isConnected()
                || device.isBatteryUnavailable()
                || device.isTransientFailure()
                || device.isReceiverPresentOff();
    }

    private void notifyListeners() {
        List<DeviceReading> snapshot = getCurrentReadings();
        for (Consumer<List<DeviceReading>> listener : listeners) {
            listener.accept(snapshot);
        }
    }
}
