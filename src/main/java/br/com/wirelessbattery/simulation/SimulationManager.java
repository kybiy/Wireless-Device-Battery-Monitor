package br.com.wirelessbattery.simulation;

import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Gerenciador isolado para o modo de simulação.
 * Funciona sem dispositivos físicos conectados e mantém os dados simulados
 * completamente separados do {@link br.com.wirelessbattery.device.DeviceManager}.
 *
 * Simula exclusivamente os três dispositivos suportados:
 * Delux M800 Pro (Mouse), Akko Verge S9 (Headset) e GameSir Cyclone 2 (Controle).
 */
public final class SimulationManager {
    public static final String SIM_DELUX_ID = "sim-delux-m800-pro";
    public static final String SIM_AKKO_ID = "sim-akko-verge-s9";
    public static final String SIM_CYCLONE2_ID = "sim-gamesir-cyclone-2";

    private final Map<String, SimulatedBatteryDevice> simulatedDevices = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Consumer<List<DeviceReading>>> listeners = new CopyOnWriteArrayList<>();
    private final AppLogger logger;
    private boolean enabled;

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
                "Akko S9 Ultra",
                DeviceType.HEADSET,
                "2.4 GHz",
                62,
                false,
                true,
                false
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
        SimulatedBatteryDevice device = simulatedDevices.get(id);
        if (device == null) {
            throw new IllegalArgumentException("Dispositivo simulado não encontrado: " + id);
        }
        device.setConnected(connected);
        device.setAsleep(asleep);
        device.setCharging(charging);
        device.setBatteryPercent(percent);
        notifyListeners();
    }

    public synchronized List<DeviceReading> getCurrentReadings() {
        if (!enabled) {
            return List.of();
        }
        List<DeviceReading> list = new ArrayList<>(simulatedDevices.size());
        for (SimulatedBatteryDevice device : simulatedDevices.values()) {
            list.add(device.readBattery());
        }
        return Collections.unmodifiableList(list);
    }

    public void addListener(Consumer<List<DeviceReading>> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<List<DeviceReading>> listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        List<DeviceReading> snapshot = getCurrentReadings();
        for (Consumer<List<DeviceReading>> listener : listeners) {
            listener.accept(snapshot);
        }
    }
}
