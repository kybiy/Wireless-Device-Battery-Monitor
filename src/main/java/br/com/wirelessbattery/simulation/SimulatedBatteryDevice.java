package br.com.wirelessbattery.simulation;

import br.com.wirelessbattery.device.BatteryDevice;
import br.com.wirelessbattery.model.ConnectionStatus;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Dispositivo exclusivamente simulado para testes de interface e demonstração
 * sem hardware conectado. Sempre produz leituras com {@link DataSourceType#SIMULATED}.
 */
public final class SimulatedBatteryDevice implements BatteryDevice {
    private final String id;
    private final String displayName;
    private final DeviceType deviceType;
    private final String connectionDescription;
    private final boolean optional;
    private int batteryPercent;
    private boolean charging;
    private boolean connected;
    private boolean asleep;
    private boolean receiverPresentOff;
    private boolean batteryUnavailable;
    private boolean transientFailure;
    private Integer lastKnownPercent;
    private LocalTime lastKnownAt;

    public SimulatedBatteryDevice(
            String id,
            String displayName,
            DeviceType deviceType,
            String connectionDescription,
            int initialPercent,
            boolean initialCharging,
            boolean initialConnected
    ) {
        this(id, displayName, deviceType, connectionDescription, initialPercent, initialCharging, initialConnected, false);
    }

    public SimulatedBatteryDevice(
            String id,
            String displayName,
            DeviceType deviceType,
            String connectionDescription,
            int initialPercent,
            boolean initialCharging,
            boolean initialConnected,
            boolean optional
    ) {
        this.id = Objects.requireNonNull(id, "id não pode ser nulo");
        this.displayName = Objects.requireNonNull(displayName, "displayName não pode ser nulo");
        this.deviceType = Objects.requireNonNull(deviceType, "deviceType não pode ser nulo");
        this.connectionDescription = Objects.requireNonNull(connectionDescription, "connectionDescription não pode ser nulo");
        this.optional = optional;
        this.batteryPercent = clampPercent(initialPercent);
        this.charging = initialCharging;
        this.connected = initialConnected;
        this.asleep = false;
        if (initialConnected) {
            this.lastKnownPercent = this.batteryPercent;
            this.lastKnownAt = LocalTime.now().withNano(0);
        }
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public DeviceType getDeviceType() {
        return deviceType;
    }

    @Override
    public String getConnectionDescription() {
        return connectionDescription;
    }

    @Override
    public DataSourceType getSourceType() {
        return DataSourceType.SIMULATED;
    }

    @Override
    public boolean isOptional() {
        return optional;
    }

    public synchronized int getBatteryPercent() {
        return batteryPercent;
    }

    public synchronized void setBatteryPercent(int percent) {
        this.batteryPercent = clampPercent(percent);
        if (connected && !asleep && !batteryUnavailable && !transientFailure && !receiverPresentOff) {
            this.lastKnownPercent = this.batteryPercent;
            this.lastKnownAt = LocalTime.now().withNano(0);
        }
    }

    public synchronized boolean isCharging() {
        return charging;
    }

    public synchronized void setCharging(boolean charging) {
        this.charging = charging;
        if (charging) {
            this.asleep = false;
            clearSimulatedFaults();
        }
    }

    public synchronized boolean isConnected() {
        return connected;
    }

    public synchronized void setConnected(boolean connected) {
        clearSimulatedFaults();
        this.connected = connected;
        if (!connected) {
            this.asleep = false;
            this.charging = false;
        } else if (!asleep) {
            this.lastKnownPercent = this.batteryPercent;
            this.lastKnownAt = LocalTime.now().withNano(0);
        }
    }

    public synchronized boolean isAsleep() {
        return asleep;
    }

    public synchronized void setAsleep(boolean asleep) {
        this.asleep = asleep;
        if (asleep) {
            this.charging = false;
            clearSimulatedFaults();
            if (lastKnownPercent == null) {
                lastKnownPercent = batteryPercent;
                lastKnownAt = LocalTime.now().withNano(0);
            }
        }
    }

    public synchronized boolean isReceiverPresentOff() {
        return receiverPresentOff;
    }

    public synchronized void setReceiverPresentOff(boolean receiverPresentOff) {
        this.receiverPresentOff = receiverPresentOff;
        if (receiverPresentOff) {
            this.connected = false;
            this.asleep = false;
            this.charging = false;
            this.batteryUnavailable = false;
            this.transientFailure = false;
        }
    }

    public synchronized boolean isBatteryUnavailable() {
        return batteryUnavailable;
    }

    public synchronized void setBatteryUnavailable(boolean batteryUnavailable) {
        this.batteryUnavailable = batteryUnavailable;
        if (batteryUnavailable) {
            this.connected = true;
            this.asleep = false;
            this.charging = false;
            this.receiverPresentOff = false;
            this.transientFailure = false;
            this.lastKnownPercent = null;
            this.lastKnownAt = null;
        }
    }

    public synchronized boolean isTransientFailure() {
        return transientFailure;
    }

    public synchronized void setTransientFailure(boolean transientFailure) {
        this.transientFailure = transientFailure;
        if (transientFailure) {
            this.asleep = false;
            this.charging = false;
            this.receiverPresentOff = false;
            this.batteryUnavailable = false;
        }
    }

    public synchronized void clearSimulatedFaults() {
        this.receiverPresentOff = false;
        this.batteryUnavailable = false;
        this.transientFailure = false;
    }

    public synchronized void clearLastKnown() {
        this.lastKnownPercent = null;
        this.lastKnownAt = null;
    }

    @Override
    public synchronized DeviceReading readBattery() {
        return readBattery(true);
    }

    public synchronized DeviceReading readBattery(boolean bluetoothAdapterAvailable) {
        if (!bluetoothAdapterAvailable && "Bluetooth".equalsIgnoreCase(connectionDescription)) {
            return DeviceReading.simulated(
                    id,
                    displayName,
                    deviceType,
                    null,
                    false,
                    false,
                    connectionDescription,
                    "Simulação: adaptador Bluetooth ausente ou desativado.",
                    null,
                    null
            );
        }
        if (transientFailure) {
            return new DeviceReading(
                    id,
                    displayName,
                    deviceType,
                    null,
                    false,
                    ConnectionStatus.TIMEOUT,
                    connectionDescription,
                    DataSourceType.SIMULATED,
                    "Simulação: falha temporária de leitura (última leitura preservada).",
                    LocalTime.now().withNano(0),
                    lastKnownPercent,
                    lastKnownAt
            );
        }
        if (batteryUnavailable) {
            return new DeviceReading(
                    id,
                    displayName,
                    deviceType,
                    null,
                    false,
                    ConnectionStatus.BATTERY_UNAVAILABLE,
                    connectionDescription,
                    DataSourceType.SIMULATED,
                    "Simulação: conectado com leitura de bateria indisponível.",
                    LocalTime.now().withNano(0),
                    null,
                    null
            );
        }
        if (receiverPresentOff) {
            return new DeviceReading(
                    id,
                    displayName,
                    deviceType,
                    null,
                    false,
                    ConnectionStatus.ASLEEP,
                    connectionDescription,
                    DataSourceType.SIMULATED,
                    "Simulação: receptor presente, dispositivo desligado.",
                    LocalTime.now().withNano(0),
                    lastKnownPercent,
                    lastKnownAt
            );
        }
        if (connected && asleep) {
            return new DeviceReading(
                    id,
                    displayName,
                    deviceType,
                    null,
                    false,
                    ConnectionStatus.ASLEEP,
                    connectionDescription,
                    DataSourceType.SIMULATED,
                    "Simulação: periférico em repouso (última leitura preservada).",
                    LocalTime.now().withNano(0),
                    lastKnownPercent != null ? lastKnownPercent : batteryPercent,
                    lastKnownAt != null ? lastKnownAt : LocalTime.now().withNano(0)
            );
        }
        String detail = connected
                ? (charging
                        ? "Simulação: carregando (dados fictícios)."
                        : "Simulação ativa (valores fictícios).")
                : "Simulação: dispositivo desconectado.";
        return DeviceReading.simulated(
                id,
                displayName,
                deviceType,
                batteryPercent,
                charging,
                connected,
                connectionDescription,
                detail,
                lastKnownPercent,
                lastKnownAt
        );
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
