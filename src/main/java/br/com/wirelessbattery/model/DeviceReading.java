package br.com.wirelessbattery.model;

import java.time.LocalTime;
import java.util.Objects;

/**
 * Representa o resultado de uma consulta de bateria para um dispositivo.
 *
 * Quando uma leitura atual não está disponível (ex.: dispositivo desconectado ou em repouso),
 * {@code batteryPercent} permanece {@code null}. Opcionalmente, {@code lastKnownPercent}
 * preserva o último valor real conhecido para exibição diferenciada, sem nunca ser tratado
 * como leitura atual.
 */
public record DeviceReading(
        String deviceId,
        String displayName,
        DeviceType deviceType,
        Integer batteryPercent,
        boolean charging,
        ConnectionStatus status,
        String connectionLabel,
        DataSourceType sourceType,
        String detailMessage,
        LocalTime updatedAt,
        Integer lastKnownPercent,
        LocalTime lastKnownAt
) {
    public DeviceReading {
        Objects.requireNonNull(deviceId, "deviceId não pode ser nulo");
        Objects.requireNonNull(displayName, "displayName não pode ser nulo");
        Objects.requireNonNull(deviceType, "deviceType não pode ser nulo");
        Objects.requireNonNull(status, "status não pode ser nulo");
        Objects.requireNonNull(sourceType, "sourceType não pode ser nulo");
        if (batteryPercent != null && (batteryPercent < 0 || batteryPercent > 100)) {
            throw new IllegalArgumentException("Percentual de bateria inválido: " + batteryPercent);
        }
        if (lastKnownPercent != null && (lastKnownPercent < 0 || lastKnownPercent > 100)) {
            throw new IllegalArgumentException("Último percentual conhecido inválido: " + lastKnownPercent);
        }
        if (connectionLabel == null || connectionLabel.isBlank()) {
            connectionLabel = "2.4 GHz";
        }
        if (detailMessage == null) {
            detailMessage = "";
        }
        if (updatedAt == null) {
            updatedAt = LocalTime.now().withNano(0);
        }
    }

    public boolean isSimulated() {
        return sourceType == DataSourceType.SIMULATED;
    }

    public boolean hasBatteryPercent() {
        return batteryPercent != null;
    }

    public boolean hasLastKnownPercent() {
        return lastKnownPercent != null;
    }

    public DeviceReading withLastKnown(Integer percent, LocalTime at) {
        return new DeviceReading(
                deviceId,
                displayName,
                deviceType,
                batteryPercent,
                charging,
                status,
                connectionLabel,
                sourceType,
                detailMessage,
                updatedAt,
                percent,
                at
        );
    }

    public DeviceReading withStatus(ConnectionStatus newStatus, String newDetail) {
        return new DeviceReading(
                deviceId,
                displayName,
                deviceType,
                batteryPercent,
                charging,
                newStatus,
                connectionLabel,
                sourceType,
                newDetail != null ? newDetail : detailMessage,
                updatedAt,
                lastKnownPercent,
                lastKnownAt
        );
    }

    public static DeviceReading realConnected(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            int percent,
            boolean charging,
            String connectionLabel,
            String detailMessage
    ) {
        LocalTime now = LocalTime.now().withNano(0);
        return new DeviceReading(
                deviceId,
                displayName,
                deviceType,
                percent,
                charging,
                charging ? ConnectionStatus.CHARGING : ConnectionStatus.CONNECTED,
                connectionLabel,
                DataSourceType.REAL,
                detailMessage,
                now,
                percent,
                now
        );
    }

    public static DeviceReading realAsleep(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return new DeviceReading(
                deviceId,
                displayName,
                deviceType,
                null,
                false,
                ConnectionStatus.ASLEEP,
                connectionLabel,
                DataSourceType.REAL,
                detailMessage,
                LocalTime.now().withNano(0),
                null,
                null
        );
    }

    public static DeviceReading realDisconnected(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return new DeviceReading(
                deviceId,
                displayName,
                deviceType,
                null,
                false,
                ConnectionStatus.DISCONNECTED,
                connectionLabel,
                DataSourceType.REAL,
                detailMessage,
                LocalTime.now().withNano(0),
                null,
                null
        );
    }

    public static DeviceReading realBatteryUnavailable(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return new DeviceReading(
                deviceId,
                displayName,
                deviceType,
                null,
                false,
                ConnectionStatus.BATTERY_UNAVAILABLE,
                connectionLabel,
                DataSourceType.REAL,
                detailMessage,
                LocalTime.now().withNano(0),
                null,
                null
        );
    }

    public static DeviceReading realPendingProtocol(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return new DeviceReading(
                deviceId,
                displayName,
                deviceType,
                null,
                false,
                ConnectionStatus.PENDING_PROTOCOL,
                connectionLabel,
                DataSourceType.REAL,
                detailMessage,
                LocalTime.now().withNano(0),
                null,
                null
        );
    }

    public static DeviceReading realError(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return new DeviceReading(
                deviceId,
                displayName,
                deviceType,
                null,
                false,
                ConnectionStatus.ERROR,
                connectionLabel,
                DataSourceType.REAL,
                detailMessage,
                LocalTime.now().withNano(0),
                null,
                null
        );
    }

    public static DeviceReading simulated(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            Integer percent,
            boolean charging,
            boolean connected,
            String connectionLabel,
            String detailMessage,
            Integer lastKnownPercent,
            LocalTime lastKnownAt
    ) {
        LocalTime now = LocalTime.now().withNano(0);
        ConnectionStatus connStatus;
        if (!connected) {
            connStatus = ConnectionStatus.DISCONNECTED;
        } else if (charging) {
            connStatus = ConnectionStatus.CHARGING;
        } else {
            connStatus = ConnectionStatus.CONNECTED;
        }
        return new DeviceReading(
                deviceId,
                displayName,
                deviceType,
                connected ? percent : null,
                connected && charging,
                connStatus,
                connectionLabel,
                DataSourceType.SIMULATED,
                detailMessage,
                now,
                connected ? percent : lastKnownPercent,
                connected ? now : lastKnownAt
        );
    }
}
