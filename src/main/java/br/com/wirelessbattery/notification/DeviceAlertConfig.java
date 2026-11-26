package br.com.wirelessbattery.notification;

import br.com.wirelessbattery.config.AppSettings;

/**
 * Configuração efetiva de alertas de bateria baixa para um dispositivo específico.
 */
public record DeviceAlertConfig(
        boolean enabled,
        int lowThreshold,
        int criticalThreshold,
        boolean suppressWhenCharging
) {
    public DeviceAlertConfig {
        lowThreshold = Math.max(1, Math.min(100, lowThreshold));
        criticalThreshold = Math.max(1, Math.min(lowThreshold, criticalThreshold));
    }

    /**
     * Constrói a configuração de alerta por dispositivo a partir de {@link AppSettings}.
     */
    public static DeviceAlertConfig fromSettings(AppSettings settings, String deviceId) {
        if (settings == null) {
            return new DeviceAlertConfig(true, 20, 10, true);
        }
        return new DeviceAlertConfig(
                settings.isDeviceAlertEnabled(deviceId),
                settings.getDeviceLowThreshold(deviceId),
                settings.getDeviceCriticalThreshold(deviceId),
                settings.isSuppressAlertWhenCharging(deviceId)
        );
    }
}
