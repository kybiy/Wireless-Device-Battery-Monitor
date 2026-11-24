package br.com.wirelessbattery.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.config.AppVersion;
import br.com.wirelessbattery.device.AkkoVergeS9;
import br.com.wirelessbattery.device.AkkoVergeS9Config;
import br.com.wirelessbattery.device.DeluxM800ProConfig;
import br.com.wirelessbattery.device.GameSirCyclone2Config;
import br.com.wirelessbattery.device.HidServiceProvider;
import br.com.wirelessbattery.model.ConnectionStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class HidDiagnosticsServiceTest {

    @Test
    void classifiesDeluxAkkoCyclone2AndExcludesAkkoKeyboardFalsePositive() {
        AppLogger logger = new AppLogger();
        try (HidServiceProvider provider = new HidServiceProvider(logger)) {
            HidDiagnosticsService service = new HidDiagnosticsService(
                    provider,
                    DeluxM800ProConfig.defaultReference(),
                    AkkoVergeS9Config.defaultReference(),
                    GameSirCyclone2Config.defaultReference(),
                    logger
            );

            String deluxCol04 = service.classifyDevice(0x248A, 0x5B2F, 0xFF00, 0x0001, "Delux", "M800 Pro");
            assertTrue(deluxCol04.contains("Delux M800 Pro"));
            assertTrue(deluxCol04.contains("Feature 0x0C"));

            String akkoHeadset008c = service.classifyDevice(0x36EB, 0x1087, 0x008C, 0x0001, "GTAI", "Akko S9 Ultra_Dongle");
            assertTrue(akkoHeadset008c.contains("Akko S9 Ultra / Verge S9"));
            assertTrue(akkoHeadset008c.contains("0x008C"));

            String akkoHeadset000c = service.classifyDevice(0x36EB, 0x1087, 0x000C, 0x0001, "GTAI", "Akko S9 Ultra_Dongle");
            assertTrue(akkoHeadset000c.contains("Akko S9 Ultra / Verge S9"));
            assertTrue(akkoHeadset000c.contains("Consumer Control"));

            // Falso positivo corrigido: Akko Keyboard (0x3151:0x502D) NUNCA deve ser classificado como headset
            String akkoKeyboard = service.classifyDevice(0x3151, 0x502D, 0x0001, 0x0006, "Akko", "Akko Keyboard");
            assertFalse(akkoKeyboard.contains("Verge S9"));
            assertFalse(akkoKeyboard.contains("S9 Ultra"));
            assertTrue(akkoKeyboard.contains("Teclado Akko"));
            assertFalse(AkkoVergeS9Config.defaultReference().matches(0x3151, 0x502D));

            String cycloneActive = service.classifyDevice(0x3537, 0x100B, 0xFFF0, 0x0040, "GameSir", "Cyclone 2");
            assertTrue(cycloneActive.contains("GameSir Cyclone 2"));
            assertTrue(cycloneActive.contains("FFF0:0040"));

            String cycloneWired = service.classifyDevice(0x3537, 0x1053, 0xFFF0, 0x0040, "GameSir", "Cyclone 2");
            assertTrue(cycloneWired.contains("USB Cabo"));
            assertTrue(cycloneWired.contains("FFF0:0040"));

            String cycloneIdle = service.classifyDevice(0x3537, 0x0575, 0xFFF0, 0x0040, "GameSir", "Receiver");
            assertTrue(cycloneIdle.contains("ocioso"));
            assertTrue(cycloneIdle.contains("FFF0:0040"));

            assertEquals("Não identificado", service.classifyDevice(0x046D, 0xC52B, "Logitech", "Receiver"));

            String report = service.buildTextReport(List.of(
                    new HidDeviceInfo(0x3537, 0x100B, 1, 0xFFF0, 0x0040, "GameSir", "Cyclone 2", "SN1", "\\\\?\\hid#test",
                            cycloneActive)
            ));
            assertTrue(report.contains("0x3537:0x100B"));
            assertTrue(report.contains("GameSir Cyclone 2"));
        }
    }

    @Test
    void logsStructuredHidOperationWithoutSensitiveFields() {
        AppLogger logger = new AppLogger();
        logger.hidOperation(
                "Delux M800 Pro",
                0x248A,
                0x5B2F,
                1,
                0xFF00,
                0x0001,
                0x0C,
                "getFeatureReport",
                "FALHA_LEITURA (-1)",
                "The parameter is incorrect",
                true
        );
        List<String> history = logger.getHistory();
        assertEquals(1, history.size());
        String entry = history.get(0);
        assertTrue(entry.contains("Delux M800 Pro"));
        assertTrue(entry.contains("0x248A:0x5B2F"));
        assertTrue(entry.contains("IF=1"));
        assertTrue(entry.contains("0xFF00:0x0001"));
        assertTrue(entry.contains("ReportID=0x0C"));
        assertTrue(entry.contains("getFeatureReport"));
        assertTrue(entry.contains("The parameter is incorrect"));
    }

    @Test
    void exposesBuildVersionAndAkkoUnavailableStatusMetadata() {
        assertEquals("1.1.0", AppVersion.getVersion());
        assertEquals("kybiy", AppVersion.APP_VENDOR);
        assertEquals("7c2f4b5e-3d9a-4e8c-91f2-8a6b4d2e1c90", AppVersion.WINDOWS_UPGRADE_UUID);
        assertFalse(AppVersion.getBuildTimestamp().isBlank());
        assertTrue(AppVersion.formatShort().contains("1.1.0"));
        assertTrue(AppVersion.formatStartupInfo().contains("Origem das classes/JAR:"));

        assertEquals("Akko S9 Ultra", AkkoVergeS9.DISPLAY_NAME);
        assertEquals("Leitura indisponível", ConnectionStatus.BATTERY_UNAVAILABLE.getLabel());
        assertTrue(ConnectionStatus.BATTERY_UNAVAILABLE.isReceiverDetectedState());
        assertEquals("Leitura indisponível", ConnectionStatus.PENDING_PROTOCOL.getLabel());
        assertFalse(ConnectionStatus.PENDING_PROTOCOL.isReceiverDetectedState());

        AkkoVergeS9Config akkoConfig = AkkoVergeS9Config.defaultReference();
        assertTrue(akkoConfig.matchesHeadsetInterface(0x36EB, 0x1087, 0x008C, 0x0001, "Akko S9 Ultra_Dongle"));
        assertTrue(akkoConfig.matchesHeadsetInterface(0x36EB, 0x1087, 0x000C, 0x0001, "Akko S9 Ultra_Dongle"));
        assertFalse(akkoConfig.matchesHeadsetInterface(0x3151, 0x502D, 0x0001, 0x0006, "Akko Keyboard"));
    }
}
