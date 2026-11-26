package br.com.wirelessbattery.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.config.DisplayMode;
import br.com.wirelessbattery.device.AkkoVergeS9Config;
import br.com.wirelessbattery.device.DeluxM800ProConfig;
import br.com.wirelessbattery.device.GameSirCyclone2Config;
import br.com.wirelessbattery.device.HidServiceProvider;
import br.com.wirelessbattery.i18n.AppLanguage;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticExportAndSanitizerTest {

    @Test
    void rotatesLogUpToThreeBackupsRespectingSizeLimit(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("wireless-battery.log");
        long maxBytes = 512L;
        AppLogger logger = new AppLogger(logFile, maxBytes, 3);

        for (int i = 0; i < 40; i++) {
            logger.info("Evento de teste de rotação número " + i + " com texto suficiente para atingir o limite.");
        }

        Path backup1 = tempDir.resolve("wireless-battery.log.1");
        Path backup2 = tempDir.resolve("wireless-battery.log.2");
        Path backup3 = tempDir.resolve("wireless-battery.log.3");
        Path backup4 = tempDir.resolve("wireless-battery.log.4");

        assertTrue(Files.isRegularFile(logFile), "Arquivo principal de log deve existir");
        assertTrue(Files.isRegularFile(backup1), "Backup .1 deve existir");
        assertTrue(Files.isRegularFile(backup2), "Backup .2 deve existir");
        assertTrue(Files.isRegularFile(backup3), "Backup .3 deve existir");
        assertFalse(Files.exists(backup4), "Backup .4 nunca deve ser criado");

        assertTrue(Files.size(logFile) <= maxBytes, "Log ativo deve respeitar o limite de tamanho");
        assertTrue(Files.size(backup1) <= maxBytes, "Backup .1 deve respeitar o limite de tamanho");
        assertTrue(Files.size(backup2) <= maxBytes, "Backup .2 deve respeitar o limite de tamanho");
        assertTrue(Files.size(backup3) <= maxBytes, "Backup .3 deve respeitar o limite de tamanho");
    }

    @Test
    void hidesRawSixtyFourByteHidPayloadUnlessDebugPropertyEnabled(@TempDir Path tempDir) {
        String prev = System.getProperty(AppLogger.RAW_HID_DEBUG_PROPERTY);
        try {
            System.clearProperty(AppLogger.RAW_HID_DEBUG_PROPERTY);
            AppLogger logger = new AppLogger(tempDir.resolve("hid.log"), 4096L, 3);

            String raw64 = "02 12 01 00 55 00 00 00 00 00 00 00 00 00 00 00 "
                    + "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00";
            logger.hidOperation(
                    "Akko S9 Ultra",
                    0x36EB,
                    0x1087,
                    3,
                    0x008C,
                    0x0001,
                    0x02,
                    "read",
                    "OK",
                    "Payload bruto: " + raw64,
                    false
            );
            byte[] payload = new byte[64];
            payload[0] = 0x12;
            payload[1] = 0x55;
            logger.hidRawPayload("GameSir Cyclone 2", 0x3537, 0x100B, "IN", payload, 64);

            List<String> history = logger.getHistory();
            assertEquals(1, history.size());
            assertTrue(history.get(0).contains("<RAW_HID_PAYLOAD_REDACTED>"));
            assertFalse(history.get(0).contains("02 12 01 00 55 00 00 00 00 00 00 00 00 00 00 00"));

            System.setProperty(AppLogger.RAW_HID_DEBUG_PROPERTY, "true");
            logger.hidRawPayload("GameSir Cyclone 2", 0x3537, 0x100B, "IN", payload, 64);
            assertEquals(2, logger.getHistory().size());
            assertTrue(logger.getHistory().get(1).contains("[HID-RAW]"));
        } finally {
            if (prev == null) {
                System.clearProperty(AppLogger.RAW_HID_DEBUG_PROPERTY);
            } else {
                System.setProperty(AppLogger.RAW_HID_DEBUG_PROPERTY, prev);
            }
        }
    }

    @Test
    void sanitizesPersonalPathsHostnamesEmailsMacsSerialsAndTokens() {
        DiagnosticSanitizer sanitizer = new DiagnosticSanitizer();
        String raw = String.join("\n",
                "UserPath1=C:\\Users\\Alice\\Documents\\WirelessBattery\\log.txt",
                "UserPath2=c:/users/alice/AppData/Local/settings.properties",
                "Logged in as Alice on machine WORKSTATION-XYZ99",
                "Contact: alice.dev+test@example.org",
                "BT_MAC_COLON=AA:BB:CC:DD:EE:FF",
                "BT_MAC_HYPHEN=11-22-33-44-55-66",
                "PnP_ID=BTHENUM\\DEV_AABBCCDDEEFF\\8&112233445566_C00000000",
                "HidPath=\\\\?\\hid#vid_248a&pid_5b2f&mi_01#7&SN99887766&0&0000#{4d1e55b2-f16f-11cf-88cb-001111000030}",
                "Fabricante=GameSir | Produto=Cyclone 2 | Serial=USB-SERIAL-998877",
                "serialNumber=SECRET_SN_42",
                "gh_classic=ghp_1234567890abcdefGHIJ",
                "gh_fine=github_pat_11ABC_998877665544332211",
                "auth=Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig",
                "cfg token=mySecretTok123 password: SuperSecretPassword!"
        );

        String sanitized = sanitizer.sanitizeText(raw, "Alice", "WORKSTATION-XYZ99");

        assertFalse(sanitized.contains("Alice"));
        assertFalse(sanitized.contains("alice"));
        assertFalse(sanitized.contains("WORKSTATION-XYZ99"));
        assertFalse(sanitized.contains("alice.dev+test@example.org"));
        assertFalse(sanitized.contains("AA:BB:CC:DD:EE:FF"));
        assertFalse(sanitized.contains("11-22-33-44-55-66"));
        assertFalse(sanitized.contains("AABBCCDDEEFF"));
        assertFalse(sanitized.contains("112233445566"));
        assertFalse(sanitized.contains("SN99887766"));
        assertFalse(sanitized.contains("USB-SERIAL-998877"));
        assertFalse(sanitized.contains("SECRET_SN_42"));
        assertFalse(sanitized.contains("ghp_1234567890abcdefGHIJ"));
        assertFalse(sanitized.contains("github_pat_11ABC_998877665544332211"));
        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertFalse(sanitized.contains("mySecretTok123"));
        assertFalse(sanitized.contains("SuperSecretPassword"));

        assertTrue(sanitized.contains("C:\\Users\\<USER>\\Documents\\WirelessBattery\\log.txt"));
        assertTrue(sanitized.contains("c:/users/<USER>/AppData/Local/settings.properties"));
        assertTrue(sanitized.contains("Logged in as <USER> on machine <HOST>"));
        assertTrue(sanitized.contains("<EMAIL_REDACTED>"));
        assertTrue(sanitized.contains("DEV_<BT_MAC_REDACTED>"));
        assertTrue(sanitized.contains("&<BT_MAC_REDACTED>_"));
        assertTrue(sanitized.contains("<SERIAL_REDACTED>"));
        assertTrue(sanitized.contains("<TOKEN_REDACTED>"));
        assertTrue(sanitized.contains("vid_248a&pid_5b2f"));
        assertTrue(sanitized.contains("{4d1e55b2-f16f-11cf-88cb-001111000030}"));
    }

    @Test
    void exportsSanitizedDiagnosticZipWithoutLeakingSensitiveStrings(@TempDir Path tempDir) throws Exception {
        AppLogger logger = new AppLogger(tempDir.resolve("app.log"), 8192L, 3);
        logger.info("Falha ao ler arquivo C:\\Users\\Alice\\secret.txt no host WORKSTATION-XYZ99");
        logger.warn("Token ghp_SecretTokenValue999 e contato alice@example.com MAC AA:BB:CC:DD:EE:FF");

        AppSettings settings = new AppSettings(tempDir.resolve("Users/Alice/settings.properties"));
        settings.setLanguage(AppLanguage.EN_US);
        settings.setPollIntervalSeconds(30);
        settings.setEcoModeEnabled(true);
        settings.setReadOnlyHidMode(true);
        settings.setGameSirMonitoringEnabled(false);
        settings.setDisplayMode(DisplayMode.BOTH);
        settings.setDarkTheme(false);
        settings.setBatteryThresholds(15, 55);
        settings.setHistoryRetentionDays(90);
        settings.setAutoCheckUpdates(true);
        settings.setWindowPosition(420, 260);

        String propsText = DiagnosticSanitizer.sanitizeSettings(settings);
        assertFalse(propsText.contains("windowX"));
        assertFalse(propsText.contains("windowY"));
        assertFalse(propsText.contains("Alice"));
        assertTrue(propsText.contains("language=en-US"));
        assertTrue(propsText.contains("pollIntervalSeconds=30"));
        assertTrue(propsText.contains("readOnlyHidMode=true"));
        assertTrue(propsText.contains("historyRetentionDays=90"));
        assertTrue(propsText.contains("autoCheckUpdates=true"));

        List<DeviceReading> readings = List.of(
                DeviceReading.realConnected(
                        "delux-m800-pro",
                        "Delux M800 Pro",
                        DeviceType.MOUSE,
                        78,
                        false,
                        "2.4 GHz",
                        "Lido em C:\\Users\\Alice\\cache.dat por alice@example.com"
                )
        );
        List<HidDeviceInfo> fakeDevices = List.of(
                new HidDeviceInfo(
                        0x3537,
                        0x100B,
                        1,
                        0xFFF0,
                        0x0040,
                        "GameSir",
                        "Cyclone 2",
                        "SERIAL-SECRET-007",
                        "\\\\?\\hid#vid_3537&pid_100b#7&SECRETCONTAINER&0&0000#{4d1e55b2-f16f-11cf-88cb-001111000030}",
                        "GameSir Cyclone 2 (Ativo — 2.4 GHz)"
                )
        );

        DiagnosticSanitizer sanitizer = new DiagnosticSanitizer("Alice", "WORKSTATION-XYZ99");
        Path zipPath = tempDir.resolve("diagnostico-sanitizado.zip");

        try (HidServiceProvider provider = new HidServiceProvider(logger)) {
            HidDiagnosticsService diagService = new HidDiagnosticsService(
                    provider,
                    DeluxM800ProConfig.defaultReference(),
                    AkkoVergeS9Config.defaultReference(),
                    GameSirCyclone2Config.defaultReference(),
                    logger
            );
            DiagnosticBundleExporter exporter = new DiagnosticBundleExporter(
                    diagService,
                    settings,
                    logger,
                    readings,
                    "Native Win32 Taskbar Widget (hwnd=ok) em WORKSTATION-XYZ99",
                    sanitizer,
                    fakeDevices
            );

            Map<String, String> entries = exporter.buildEntries();
            assertEquals(
                    List.of(
                            "diagnostic-summary.txt",
                            "driver-states.txt",
                            "hid-interfaces.txt",
                            "settings-sanitized.properties",
                            "logs-sanitized.txt"
                    ),
                    List.copyOf(entries.keySet())
            );

            String preview = DiagnosticBundleExporter.buildPreviewText(entries);
            assertTrue(preview.contains("ARQUIVO: diagnostic-summary.txt"));
            assertTrue(preview.contains("ARQUIVO: hid-interfaces.txt"));
            assertFalse(preview.contains("Alice"));
            assertFalse(preview.contains("WORKSTATION-XYZ99"));

            DiagnosticBundleExporter.exportZip(entries, zipPath);
        }

        assertTrue(Files.isRegularFile(zipPath));

        Map<String, String> unzipped = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry entry = en.nextElement();
                try (InputStream is = zip.getInputStream(entry)) {
                    unzipped.put(entry.getName(), new String(is.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }

        assertEquals(5, unzipped.size());
        String allContent = String.join("\n", unzipped.values());
        assertFalse(allContent.contains("Alice"));
        assertFalse(allContent.contains("WORKSTATION-XYZ99"));
        assertFalse(allContent.contains("alice@example.com"));
        assertFalse(allContent.contains("ghp_SecretTokenValue999"));
        assertFalse(allContent.contains("AA:BB:CC:DD:EE:FF"));
        assertFalse(allContent.contains("SERIAL-SECRET-007"));
        assertFalse(allContent.contains("SECRETCONTAINER"));
        assertFalse(allContent.contains("windowX"));
        assertTrue(allContent.contains("<USER>"));
        assertTrue(allContent.contains("<HOST>"));
        assertTrue(allContent.contains("<SERIAL_REDACTED>"));
        assertTrue(allContent.contains("<TOKEN_REDACTED>"));
    }
}
