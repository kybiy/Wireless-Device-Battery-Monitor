package br.com.wirelessbattery.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.device.AkkoVergeS9Config;
import br.com.wirelessbattery.device.DeluxM800ProConfig;
import br.com.wirelessbattery.device.GameSirCyclone2Config;
import br.com.wirelessbattery.device.HidServiceProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class HidDiagnosticsServiceTest {

    @Test
    void classifiesDeluxAkkoCyclone2AndUnknownDevicesPassively() {
        AppLogger logger = new AppLogger();
        try (HidServiceProvider provider = new HidServiceProvider(logger)) {
            HidDiagnosticsService service = new HidDiagnosticsService(
                    provider,
                    DeluxM800ProConfig.defaultReference(),
                    new AkkoVergeS9Config(0x1234, 0x5678),
                    GameSirCyclone2Config.defaultReference(),
                    logger
            );

            assertTrue(service.classifyDevice(0x248A, 0x5B2F, "Delux", "M800 Pro").contains("Delux M800 Pro"));
            assertTrue(service.classifyDevice(0x1234, 0x5678, "Akko", "Verge S9").contains("Akko Verge S9"));

            String cycloneActive = service.classifyDevice(0x3537, 0x100B, 0xFFF0, 0x0040, "GameSir", "Cyclone 2");
            assertTrue(cycloneActive.contains("GameSir Cyclone 2"));
            assertTrue(cycloneActive.contains("FFF0:0040"));

            String cycloneIdle = service.classifyDevice(0x3537, 0x0575, 0xFF00, 0x0001, "GameSir", "Receiver");
            assertTrue(cycloneIdle.contains("ocioso"));

            assertEquals("Não identificado", service.classifyDevice(0x046D, 0xC52B, "Logitech", "Receiver"));

            String report = service.buildTextReport(List.of(
                    new HidDeviceInfo(0x3537, 0x100B, 1, 0xFFF0, 0x0040, "GameSir", "Cyclone 2", "SN1", "\\\\?\\hid#test",
                            cycloneActive)
            ));
            assertTrue(report.contains("0x3537:0x100B"));
            assertTrue(report.contains("GameSir Cyclone 2"));
        }
    }
}
