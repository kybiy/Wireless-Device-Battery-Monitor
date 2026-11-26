package br.com.wirelessbattery.notification;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.i18n.AppLanguage;
import br.com.wirelessbattery.i18n.I18n;
import br.com.wirelessbattery.model.ConnectionStatus;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatteryAlertManagerTest {

    private record RecordedAlert(String deviceId, String title, String message, boolean critical) {
    }

    @Test
    void ignoresSimulatedOfflineAndReadingsWithoutBatteryPercent(@TempDir Path tempDir) {
        AppSettings settings = new AppSettings(tempDir.resolve("settings.properties"));
        BatteryAlertManager manager = new BatteryAlertManager();
        I18n i18n = new I18n(AppLanguage.EN_US);
        List<RecordedAlert> alerts = new ArrayList<>();

        DeviceReading simLow = DeviceReading.simulated(
                "delux-m800-pro", "Delux M800 Pro", DeviceType.MOUSE,
                5, false, true, "2.4 GHz", "Sim", 5, LocalTime.now()
        );
        DeviceReading offlineWithLastKnown = new DeviceReading(
                "akko-verge-s9", "Akko Verge S9", DeviceType.MOUSE,
                null, false, ConnectionStatus.ASLEEP, "2.4 GHz", DataSourceType.REAL,
                "Asleep", LocalTime.now(), 8, LocalTime.now()
        );
        DeviceReading onlineWithoutPercent = new DeviceReading(
                "qcy-t13", "QCY T13", DeviceType.HEADSET,
                null, false, ConnectionStatus.BATTERY_UNAVAILABLE, "Bluetooth", DataSourceType.REAL,
                "Unavailable", LocalTime.now(), null, null
        );

        manager.evaluate(List.of(simLow, offlineWithLastKnown, onlineWithoutPercent), settings, i18n,
                (id, t, m, c) -> alerts.add(new RecordedAlert(id, t, m, c)));

        assertTrue(alerts.isEmpty());
    }

    @Test
    void firesLowOnceAndEscalatesToCriticalWithoutRetriggeringLow(@TempDir Path tempDir) {
        AppSettings settings = new AppSettings(tempDir.resolve("settings.properties"));
        BatteryAlertManager manager = new BatteryAlertManager();
        I18n i18n = new I18n(AppLanguage.EN_US);
        List<RecordedAlert> alerts = new ArrayList<>();
        BatteryAlertManager.NotificationSink sink = (id, t, m, c) -> alerts.add(new RecordedAlert(id, t, m, c));

        // 25% -> sem alerta
        manager.evaluate(List.of(realMouse(25, false)), settings, i18n, sink);
        assertTrue(alerts.isEmpty());

        // 20% -> dispara LOW uma vez
        manager.evaluate(List.of(realMouse(20, false)), settings, i18n, sink);
        assertEquals(1, alerts.size());
        assertFalse(alerts.get(0).critical());
        assertTrue(alerts.get(0).title().contains("Low battery"));
        assertTrue(alerts.get(0).message().contains("20%"));

        // Continua baixo (18%, 12%) -> não re-dispara
        manager.evaluate(List.of(realMouse(18, false)), settings, i18n, sink);
        manager.evaluate(List.of(realMouse(12, false)), settings, i18n, sink);
        assertEquals(1, alerts.size());

        // Cai para 10% -> escala para CRITICAL uma vez
        manager.evaluate(List.of(realMouse(10, false)), settings, i18n, sink);
        assertEquals(2, alerts.size());
        assertTrue(alerts.get(1).critical());
        assertTrue(alerts.get(1).title().contains("Critical battery"));
        assertTrue(alerts.get(1).message().contains("10%"));

        // Oscila para 8% e sobe para 15% (< 25%) -> NÃO re-dispara CRITICAL nem LOW
        manager.evaluate(List.of(realMouse(8, false)), settings, i18n, sink);
        manager.evaluate(List.of(realMouse(15, false)), settings, i18n, sink);
        assertEquals(2, alerts.size());
    }

    @Test
    void rearmsOnlyAfterFivePercentRecoveryOrEnteringCharging(@TempDir Path tempDir) {
        AppSettings settings = new AppSettings(tempDir.resolve("settings.properties"));
        BatteryAlertManager manager = new BatteryAlertManager();
        I18n i18n = new I18n(AppLanguage.EN_US);
        List<RecordedAlert> alerts = new ArrayList<>();
        BatteryAlertManager.NotificationSink sink = (id, t, m, c) -> alerts.add(new RecordedAlert(id, t, m, c));

        // Dispara LOW em 20%
        manager.evaluate(List.of(realMouse(20, false)), settings, i18n, sink);
        assertEquals(1, alerts.size());

        // Sobe apenas para 24% (< 20 + 5) e cai para 19% -> NÃO re-arma
        manager.evaluate(List.of(realMouse(24, false)), settings, i18n, sink);
        manager.evaluate(List.of(realMouse(19, false)), settings, i18n, sink);
        assertEquals(1, alerts.size());

        // Recupera para 25% (>= 20 + 5) -> re-arma; ao cair para 18%, dispara novamente
        manager.evaluate(List.of(realMouse(25, false)), settings, i18n, sink);
        manager.evaluate(List.of(realMouse(18, false)), settings, i18n, sink);
        assertEquals(2, alerts.size());

        // Entra em CHARGING -> suprime e re-arma; ao desconectar cabo em 18%, dispara novamente
        manager.evaluate(List.of(realMouse(18, true)), settings, i18n, sink);
        assertEquals(2, alerts.size());
        manager.evaluate(List.of(realMouse(18, false)), settings, i18n, sink);
        assertEquals(3, alerts.size());
    }

    @Test
    void respectsGlobalAndPerDeviceSettingsAndSuppressWhenChargingOption(@TempDir Path tempDir) {
        AppSettings settings = new AppSettings(tempDir.resolve("settings.properties"));
        settings.setNotificationsEnabled(false);
        BatteryAlertManager manager = new BatteryAlertManager();
        I18n i18n = new I18n(AppLanguage.PT_BR);
        List<RecordedAlert> alerts = new ArrayList<>();
        BatteryAlertManager.NotificationSink sink = (id, t, m, c) -> alerts.add(new RecordedAlert(id, t, m, c));

        manager.evaluate(List.of(realMouse(5, false)), settings, i18n, sink);
        assertTrue(alerts.isEmpty());

        settings.setNotificationsEnabled(true);
        settings.setDeviceAlertEnabled("delux-m800-pro", false);
        manager.evaluate(List.of(realMouse(5, false)), settings, i18n, sink);
        assertTrue(alerts.isEmpty());

        // Outro dispositivo continua habilitado e usa histerese independente
        DeviceReading akko = DeviceReading.realConnected(
                "akko-verge-s9", "Akko Verge S9", DeviceType.MOUSE, 18, false, "2.4 GHz", "OK"
        );
        manager.evaluate(List.of(akko), settings, i18n, sink);
        assertEquals(1, alerts.size());
        assertEquals("akko-verge-s9", alerts.get(0).deviceId());
        assertTrue(alerts.get(0).title().contains("Bateria baixa"));

        // Desabilitar silenciamento durante recarga dispara uma vez sem spammar nos ciclos seguintes
        settings.setDeviceAlertEnabled("delux-m800-pro", true);
        settings.setSuppressAlertWhenCharging("delux-m800-pro", false);
        manager.evaluate(List.of(realMouse(15, true)), settings, i18n, sink);
        manager.evaluate(List.of(realMouse(15, true)), settings, i18n, sink);
        assertEquals(2, alerts.size());
        assertEquals("delux-m800-pro", alerts.get(1).deviceId());
    }

    @Test
    void testNotificationBypassesHysteresisAndSimulationAndClampsConfig() {
        DeviceAlertConfig clamped = new DeviceAlertConfig(true, 120, 150, true);
        assertEquals(100, clamped.lowThreshold());
        assertEquals(100, clamped.criticalThreshold());

        BatteryAlertManager manager = new BatteryAlertManager();
        I18n i18n = new I18n(AppLanguage.EN_US);
        List<RecordedAlert> alerts = new ArrayList<>();

        manager.triggerTestNotification("qcy-t13", "QCY T13", 15, i18n,
                (id, t, m, c) -> alerts.add(new RecordedAlert(id, t, m, c)));
        manager.triggerTestNotification("qcy-t13", "QCY T13", 15, i18n,
                (id, t, m, c) -> alerts.add(new RecordedAlert(id, t, m, c)));

        assertEquals(2, alerts.size());
        assertFalse(alerts.get(0).critical());
        assertTrue(alerts.get(0).message().contains("15%"));
        assertTrue(alerts.get(0).title().contains("QCY T13"));
    }

    private static DeviceReading realMouse(int percent, boolean charging) {
        return DeviceReading.realConnected(
                "delux-m800-pro",
                "Delux M800 Pro",
                DeviceType.MOUSE,
                percent,
                charging,
                charging ? "Cabo USB" : "2.4 GHz",
                "OK"
        );
    }
}
