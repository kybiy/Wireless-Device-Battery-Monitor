package br.com.wirelessbattery.ui;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.config.AppSettings;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatteryColorPolicyTest {

    @Test
    void respectsDefaultAndCustomBatteryThresholds(@TempDir Path tempDir) {
        AppSettings settings = new AppSettings(tempDir.resolve("c.properties"));

        // Padrão: > 50% verde, 21-50% amarelo, <= 20% vermelho, carregando azul
        assertEquals(DeviceIconFactory.COLOR_HIGH, DeviceIconFactory.batteryColor(85, false, settings));
        assertEquals(DeviceIconFactory.COLOR_HIGH, DeviceIconFactory.batteryColor(51, false, settings));
        assertEquals(DeviceIconFactory.COLOR_MEDIUM, DeviceIconFactory.batteryColor(50, false, settings));
        assertEquals(DeviceIconFactory.COLOR_MEDIUM, DeviceIconFactory.batteryColor(21, false, settings));
        assertEquals(DeviceIconFactory.COLOR_LOW, DeviceIconFactory.batteryColor(20, false, settings));
        assertEquals(DeviceIconFactory.COLOR_CHARGING, DeviceIconFactory.batteryColor(15, true, settings));
        assertEquals(DeviceIconFactory.COLOR_NEUTRAL, DeviceIconFactory.batteryColor(null, false, settings));

        // Limites customizados
        settings.setBatteryThresholds(15, 60);
        assertEquals(DeviceIconFactory.COLOR_MEDIUM, DeviceIconFactory.batteryColor(20, false, settings));
        assertEquals(DeviceIconFactory.COLOR_LOW, DeviceIconFactory.batteryColor(15, false, settings));
        assertEquals(DeviceIconFactory.COLOR_MEDIUM, DeviceIconFactory.batteryColor(60, false, settings));
        assertEquals(DeviceIconFactory.COLOR_HIGH, DeviceIconFactory.batteryColor(61, false, settings));
    }
}
