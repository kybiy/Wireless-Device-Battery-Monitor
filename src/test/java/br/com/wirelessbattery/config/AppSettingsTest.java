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

    @Test
    void singleInstanceGuardPreventsDuplicateLockAndWritesActivateSignal(@TempDir Path tempDir) throws Exception {
        Path lockFile = tempDir.resolve("instance.lock");
        Path signalFile = tempDir.resolve("show.signal");

        try (SingleInstanceGuard primary = new SingleInstanceGuard(lockFile, signalFile)) {
            assertTrue(primary.tryAcquireOrSignalExisting());

            try (SingleInstanceGuard duplicate = new SingleInstanceGuard(lockFile, signalFile)) {
                assertFalse(duplicate.tryAcquireOrSignalExisting());
            }
            assertTrue(java.nio.file.Files.isRegularFile(signalFile));
        }

        // Após liberar a primeira instância, uma nova instância pode adquirir o lock
        try (SingleInstanceGuard reopened = new SingleInstanceGuard(lockFile, signalFile)) {
            assertTrue(reopened.tryAcquireOrSignalExisting());
        }
    }

    @Test
    void startupManagerUsesJpackageNativeExecutableWhenConfigured(@TempDir Path tempDir) throws Exception {
        Path fakeExe = tempDir.resolve("WirelessBattery.exe");
        java.nio.file.Files.writeString(fakeExe, "stub");
        String prev = System.getProperty("jpackage.app-path");
        try {
            System.setProperty("jpackage.app-path", fakeExe.toString());
            String cmd = WindowsStartupManager.buildLaunchCommand(java.util.List.of());
            assertEquals("\"" + fakeExe.toAbsolutePath().normalize() + "\" --minimized", cmd);
        } finally {
            if (prev == null) {
                System.clearProperty("jpackage.app-path");
            } else {
                System.setProperty("jpackage.app-path", prev);
            }
        }
    }

    @Test
    void startupManagerPrefersInstalledExeOverTemporaryAppImageExe(@TempDir Path tempDir) throws Exception {
        Path tempBuildDir = tempDir.resolve("dist").resolve("app-image").resolve("WirelessBattery");
        java.nio.file.Files.createDirectories(tempBuildDir);
        Path appImageExe = tempBuildDir.resolve("WirelessBattery.exe");
        java.nio.file.Files.writeString(appImageExe, "temp-stub");

        Path installedDir = tempDir.resolve("Installed").resolve("WirelessBattery");
        java.nio.file.Files.createDirectories(installedDir);
        Path installedExe = installedDir.resolve("WirelessBattery.exe");
        java.nio.file.Files.writeString(installedExe, "installed-stub");

        assertTrue(WindowsStartupManager.isTemporaryBuildPath(appImageExe.toString()));
        assertFalse(WindowsStartupManager.isTemporaryBuildPath(installedExe.toString()));

        String prev = System.getProperty("jpackage.app-path");
        try {
            System.setProperty("jpackage.app-path", appImageExe.toString());
            String cmd = WindowsStartupManager.buildLaunchCommand(java.util.List.of(installedExe));
            assertEquals("\"" + installedExe.toAbsolutePath().normalize() + "\" --minimized", cmd);
        } finally {
            if (prev == null) {
                System.clearProperty("jpackage.app-path");
            } else {
                System.setProperty("jpackage.app-path", prev);
            }
        }
    }

    @Test
    void singleInstanceGuardDoesNotBlockOnStaleLockFileFromPreviousSession(@TempDir Path tempDir) throws Exception {
        Path lockFile = tempDir.resolve("instance.lock");
        Path signalFile = tempDir.resolve("show.signal");
        // Simula um arquivo instance.lock deixado por uma sessão anterior antes de reiniciar o Windows
        java.nio.file.Files.writeString(lockFile, "999999");
        java.nio.file.Files.writeString(signalFile, "123456789");

        try (SingleInstanceGuard guard = new SingleInstanceGuard(lockFile, signalFile)) {
            assertTrue(guard.tryAcquireOrSignalExisting());
            assertFalse(java.nio.file.Files.exists(signalFile), "Sinal antigo deve ser limpo ao adquirir a trava");
            assertTrue(guard.getLastDiagnosticMessage().contains("999999"));

            // Segunda instância com --minimized (signalRestoreIfLocked=false) não deve criar show.signal
            try (SingleInstanceGuard minimizedDuplicate = new SingleInstanceGuard(lockFile, signalFile)) {
                assertFalse(minimizedDuplicate.tryAcquireOrSignalExisting(false));
            }
            assertFalse(java.nio.file.Files.exists(signalFile));
        }
    }

    @Test
    void appLoggerPersistsStartupEntriesToLogFile(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("wireless-battery.log");
        br.com.wirelessbattery.diagnostics.AppLogger logger =
                new br.com.wirelessbattery.diagnostics.AppLogger(logFile);
        logger.info("[STARTUP] Preferência 'Iniciar com o Windows': ATIVADA");
        logger.info("[STARTUP] Entrada criada e confirmada no registro");

        assertTrue(java.nio.file.Files.isRegularFile(logFile));
        String content = java.nio.file.Files.readString(logFile);
        assertTrue(content.contains("[STARTUP] Preferência 'Iniciar com o Windows': ATIVADA"));
        assertTrue(content.contains("[STARTUP] Entrada criada e confirmada no registro"));
    }
}
