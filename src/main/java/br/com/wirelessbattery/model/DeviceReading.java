package br.com.wirelessbattery.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Representa o resultado de uma consulta de bateria para um dispositivo.
 *
 * Quando uma leitura atual não está disponível (ex.: dispositivo desconectado ou em repouso),
 * {@code batteryPercent} permanece {@code null}. Opcionalmente, {@code lastKnownPercent}
 * preserva o último valor real conhecido e {@code lastKnownInstant} o timestamp real dessa
 * leitura válida (nunca o horário de uma tentativa de comunicação com falha).
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
        LocalTime lastKnownAt,
        Instant validReadingInstant,
        Instant lastKnownInstant
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
        if (validReadingInstant == null && batteryPercent != null) {
            validReadingInstant = inferInstantFromLocalTime(updatedAt);
        }
        if (lastKnownInstant == null && lastKnownPercent != null) {
            lastKnownInstant = inferInstantFromLocalTime(lastKnownAt != null ? lastKnownAt : updatedAt);
        }
    }

    /**
     * Construtor de compatibilidade com 12 argumentos utilizado por testes e chamadores existentes.
     */
    public DeviceReading(
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
        this(
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
                lastKnownPercent,
                lastKnownAt,
                batteryPercent != null ? inferInstantFromLocalTime(updatedAt) : null,
                lastKnownPercent != null ? inferInstantFromLocalTime(lastKnownAt != null ? lastKnownAt : updatedAt) : null
        );
    }

    private static Instant inferInstantFromLocalTime(LocalTime time) {
        if (time == null) {
            return Instant.now();
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDateTime candidate = LocalDateTime.of(today, time);
        Instant instant = candidate.atZone(zone).toInstant();
        if (instant.isAfter(Instant.now().plusSeconds(5))) {
            instant = candidate.minusDays(1).atZone(zone).toInstant();
        }
        return instant;
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

    public Instant effectiveReadingTimestamp() {
        return hasBatteryPercent() ? validReadingInstant : lastKnownInstant;
    }

    public String formattedBatteryText() {
        if (hasBatteryPercent()) {
            return batteryPercent + "%";
        }
        if (hasLastKnownPercent()) {
            return "--% (última " + lastKnownPercent + "%)";
        }
        return "--%";
    }

    public DeviceReading withLastKnown(Integer percent, LocalTime at) {
        return withLastKnown(percent, at, percent != null ? inferInstantFromLocalTime(at) : null);
    }

    public DeviceReading withLastKnown(Integer percent, LocalTime at, Instant instant) {
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
                at,
                validReadingInstant,
                percent != null ? (instant != null ? instant : inferInstantFromLocalTime(at)) : null
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
                lastKnownAt,
                validReadingInstant,
                lastKnownInstant
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
        Instant instantNow = Instant.now();
        LocalTime now = LocalTime.ofInstant(instantNow, ZoneId.systemDefault()).withNano(0);
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
                now,
                instantNow,
                instantNow
        );
    }

    public static DeviceReading realAsleep(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return realStatusOnly(deviceId, displayName, deviceType, ConnectionStatus.ASLEEP, connectionLabel, detailMessage);
    }

    public static DeviceReading realNoResponse(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return realStatusOnly(deviceId, displayName, deviceType, ConnectionStatus.NO_RESPONSE, connectionLabel, detailMessage);
    }

    public static DeviceReading realOpenFailed(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return realStatusOnly(deviceId, displayName, deviceType, ConnectionStatus.OPEN_FAILED, connectionLabel, detailMessage);
    }

    public static DeviceReading realReadFailed(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return realStatusOnly(deviceId, displayName, deviceType, ConnectionStatus.READ_FAILED, connectionLabel, detailMessage);
    }

    public static DeviceReading realTimeout(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return realStatusOnly(deviceId, displayName, deviceType, ConnectionStatus.TIMEOUT, connectionLabel, detailMessage);
    }

    public static DeviceReading realDisconnected(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return realStatusOnly(deviceId, displayName, deviceType, ConnectionStatus.DISCONNECTED, connectionLabel, detailMessage);
    }

    public static DeviceReading realBatteryUnavailable(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return realStatusOnly(deviceId, displayName, deviceType, ConnectionStatus.BATTERY_UNAVAILABLE, connectionLabel, detailMessage);
    }

    public static DeviceReading realPendingProtocol(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return realStatusOnly(deviceId, displayName, deviceType, ConnectionStatus.PENDING_PROTOCOL, connectionLabel, detailMessage);
    }

    public static DeviceReading realReadOnlyBlocked(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return realStatusOnly(deviceId, displayName, deviceType, ConnectionStatus.READ_ONLY_BLOCKED, connectionLabel, detailMessage);
    }

    public static DeviceReading realMonitoringDisabled(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return realStatusOnly(deviceId, displayName, deviceType, ConnectionStatus.MONITORING_DISABLED, connectionLabel, detailMessage);
    }

    public static DeviceReading realError(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            String connectionLabel,
            String detailMessage
    ) {
        return realStatusOnly(deviceId, displayName, deviceType, ConnectionStatus.ERROR, connectionLabel, detailMessage);
    }

    private static DeviceReading realStatusOnly(
            String deviceId,
            String displayName,
            DeviceType deviceType,
            ConnectionStatus status,
            String connectionLabel,
            String detailMessage
    ) {
        return new DeviceReading(
                deviceId,
                displayName,
                deviceType,
                null,
                false,
                status,
                connectionLabel,
                DataSourceType.REAL,
                detailMessage,
                LocalTime.now().withNano(0),
                null,
                null,
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
        Instant instantNow = Instant.now();
        LocalTime now = LocalTime.ofInstant(instantNow, ZoneId.systemDefault()).withNano(0);
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
                connected ? now : lastKnownAt,
                connected && percent != null ? instantNow : null,
                connected && percent != null
                        ? instantNow
                        : (lastKnownPercent != null ? inferInstantFromLocalTime(lastKnownAt) : null)
        );
    }
}
