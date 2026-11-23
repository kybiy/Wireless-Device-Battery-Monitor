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
        if (connected && !asleep) {
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
        }
    }

    public synchronized boolean isConnected() {
        return connected;
    }

    public synchronized void setConnected(boolean connected) {
        this.connected = connected;
        if (!connected) {
            this.asleep = false;
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
            if (lastKnownPercent == null) {
                lastKnownPercent = batteryPercent;
                lastKnownAt = LocalTime.now().withNano(0);
            }
        }
    }

    @Override
    public synchronized DeviceReading readBattery() {
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
