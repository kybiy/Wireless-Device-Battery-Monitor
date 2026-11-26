package br.com.wirelessbattery.notification;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.i18n.I18n;
import br.com.wirelessbattery.model.DeviceReading;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gerencia alertas de bateria baixa e crítica com histerese por dispositivo.
 * <p>
 * Leituras simuladas, offline ou sem percentual real nunca disparam notificações automáticas.
 */
public final class BatteryAlertManager {

    @FunctionalInterface
    public interface NotificationSink {
        void notify(String deviceId, String title, String message, boolean critical);
    }

    private enum AlertState {
        ARMED,
        LOW_NOTIFIED,
        CRITICAL_NOTIFIED
    }

    private static final class DeviceTracker {
        AlertState state = AlertState.ARMED;
        boolean wasCharging = false;
    }

    private final Map<String, DeviceTracker> trackers = new HashMap<>();

    /**
     * Avalia a lista de leituras atual e despacha alertas conforme configuração e histerese.
     */
    public synchronized void evaluate(
            List<DeviceReading> readings,
            AppSettings settings,
            I18n i18n,
            NotificationSink sink
    ) {
        if (readings == null || readings.isEmpty() || settings == null || sink == null) {
            return;
        }
        I18n resolvedI18n = i18n != null ? i18n : new I18n();

        for (DeviceReading reading : readings) {
            if (reading == null || reading.isSimulated() || !reading.hasBatteryPercent()) {
                continue;
            }
            if (!reading.status().isOnline()) {
                continue;
            }

            String deviceId = reading.deviceId();
            DeviceTracker tracker = trackers.computeIfAbsent(deviceId, id -> new DeviceTracker());
            boolean charging = reading.charging();
            boolean enteredCharging = charging && !tracker.wasCharging;
            tracker.wasCharging = charging;

            DeviceAlertConfig config = DeviceAlertConfig.fromSettings(settings, deviceId);

            if (charging && config.suppressWhenCharging()) {
                tracker.state = AlertState.ARMED;
                continue;
            }
            if (enteredCharging) {
                tracker.state = AlertState.ARMED;
            }

            int percent = reading.batteryPercent();
            int rearmThreshold = Math.min(100, config.lowThreshold() + 5);
            if (percent >= rearmThreshold) {
                tracker.state = AlertState.ARMED;
                continue;
            }

            if (!settings.isNotificationsEnabled() || !config.enabled()) {
                continue;
            }

            if (percent <= config.criticalThreshold()) {
                if (tracker.state != AlertState.CRITICAL_NOTIFIED) {
                    tracker.state = AlertState.CRITICAL_NOTIFIED;
                    String title = resolvedI18n.t("tray.lowBatteryCriticalTitle", reading.displayName());
                    String body = resolvedI18n.t("tray.lowBatteryBody", percent);
                    sink.notify(deviceId, title, body, true);
                }
            } else if (percent <= config.lowThreshold()) {
                if (tracker.state == AlertState.ARMED) {
                    tracker.state = AlertState.LOW_NOTIFIED;
                    String title = resolvedI18n.t("tray.lowBatteryTitle", reading.displayName());
                    String body = resolvedI18n.t("tray.lowBatteryBody", percent);
                    sink.notify(deviceId, title, body, false);
                }
            }
        }
    }

    /**
     * Dispara uma notificação de teste explicitamente solicitada pelo usuário,
     * ignorando estado de simulação e histerese.
     */
    public void triggerTestNotification(
            String deviceId,
            String displayName,
            int samplePercent,
            I18n i18n,
            NotificationSink sink
    ) {
        if (sink == null) {
            return;
        }
        I18n resolvedI18n = i18n != null ? i18n : new I18n();
        String safeId = (deviceId == null || deviceId.isBlank()) ? "test-device" : deviceId;
        String safeName = (displayName == null || displayName.isBlank()) ? safeId : displayName;
        int clampedPercent = Math.max(0, Math.min(100, samplePercent));

        String title = resolvedI18n.t("tray.lowBatteryTitle", safeName);
        String body = resolvedI18n.t("tray.testNotificationBody", clampedPercent);
        sink.notify(safeId, title, body, false);
    }

    public synchronized void reset() {
        trackers.clear();
    }

    public synchronized void resetDevice(String deviceId) {
        if (deviceId != null) {
            trackers.remove(deviceId);
        }
    }
}
