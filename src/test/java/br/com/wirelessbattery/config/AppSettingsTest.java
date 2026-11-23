package br.com.wirelessbattery.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppSettingsTest {

    @Test
    void savesAndRestoresAllPreferencesIncludingTaskbarLayoutAndWindowPosition(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("settings.properties");
        AppSettings original = new AppSettings(file);
        original.setStartWithWindows(true);
        original.setStartMinimized(true);
        original.setMinimizeToTray(false);
        original.setPollIntervalSeconds(45);
        original.setDarkTheme(false);
        original.setNotificationsEnabled(false);
        original.setBatteryThresholds(15, 55);
        original.setDisplayMode(DisplayMode.BOTH);
        original.setSimulationEnabled(true);
        original.setShowDisconnectedDevices(true);
        original.setTaskbarBackgroundPlate(true);
        original.setTaskbarIconSize(18);
        original.setTaskbarFontSize(13);
        original.setTaskbarSpacing(20);
        original.setTaskbarOffsetX(24);
        original.setWindowPosition(320, 180);
        original.setDeviceHidden("akko-verge-s9", true);
        original.save();

        AppSettings reloaded = new AppSettings(file);
        reloaded.load();

        assertTrue(reloaded.isStartWithWindows());
        assertTrue(reloaded.isStartMinimized());
        assertFalse(reloaded.isMinimizeToTray());
        assertEquals(45, reloaded.getPollIntervalSeconds());
        assertFalse(reloaded.isDarkTheme());
        assertFalse(reloaded.isNotificationsEnabled());
        assertEquals(15, reloaded.getLowBatteryThreshold());
        assertEquals(55, reloaded.getMediumBatteryThreshold());
        assertEquals(DisplayMode.BOTH, reloaded.getDisplayMode());
        assertTrue(reloaded.isSimulationEnabled());
        assertTrue(reloaded.isShowDisconnectedDevices());
        assertTrue(reloaded.isTaskbarBackgroundPlate());
        assertEquals(18, reloaded.getTaskbarIconSize());
        assertEquals(13, reloaded.getTaskbarFontSize());
        assertEquals(20, reloaded.getTaskbarSpacing());
        assertEquals(24, reloaded.getTaskbarOffsetX());
        assertEquals(320, reloaded.getWindowX());
        assertEquals(180, reloaded.getWindowY());
        assertTrue(reloaded.isDeviceHidden("akko-verge-s9"));
        assertFalse(reloaded.isDeviceHidden("delux-m800-pro"));
    }

    @Test
    void clampsPollIntervalThresholdsAndTaskbarSizesSafely(@TempDir Path tempDir) {
        AppSettings settings = new AppSettings(tempDir.resolve("s.properties"));
        settings.setPollIntervalSeconds(1);
        assertEquals(5, settings.getPollIntervalSeconds());

        settings.setBatteryThresholds(2, 10);
        assertEquals(5, settings.getLowBatteryThreshold());
        assertEquals(10, settings.getMediumBatteryThreshold());

        settings.setTaskbarIconSize(4);
        assertEquals(12, settings.getTaskbarIconSize());
        settings.setTaskbarFontSize(40);
        assertEquals(18, settings.getTaskbarFontSize());
        settings.setTaskbarSpacing(1);
        assertEquals(4, settings.getTaskbarSpacing());
    }
}
