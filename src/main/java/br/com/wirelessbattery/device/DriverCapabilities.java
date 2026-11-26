package br.com.wirelessbattery.device;

import java.util.Objects;

/**
 * Capacidades e restrições operacionais declaradas por cada driver modular.
 */
public record DriverCapabilities(
        TransportType transportType,
        boolean supportsChargingDetection,
        boolean supportsSleepDetection,
        int minimumSafePollSeconds,
        ProtocolSafetyInfo safetyInfo
) {
    public DriverCapabilities {
        Objects.requireNonNull(transportType, "transportType não pode ser nulo");
        Objects.requireNonNull(safetyInfo, "safetyInfo não pode ser nulo");
        minimumSafePollSeconds = Math.max(5, minimumSafePollSeconds);
    }

    public static DriverCapabilities defaultPassive() {
        return new DriverCapabilities(
                TransportType.USB_HID,
                false,
                false,
                5,
                ProtocolSafetyInfo.passiveOs("Leitura padrão.", "")
        );
    }
}
