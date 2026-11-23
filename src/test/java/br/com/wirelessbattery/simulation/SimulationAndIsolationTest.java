package br.com.wirelessbattery.simulation;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.device.AkkoVergeS9;
import br.com.wirelessbattery.device.AkkoVergeS9Config;
import br.com.wirelessbattery.device.BatteryDevice;
import br.com.wirelessbattery.device.DeviceManager;
import br.com.wirelessbattery.device.GameSirCyclone2;
import br.com.wirelessbattery.device.GameSirCyclone2Config;
import br.com.wirelessbattery.device.HidServiceProvider;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.ConnectionStatus;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SimulationAndIsolationTest {

    @Test
    void simulationManagerSupportsDeluxAkkoAndGameSirCyclone2() {
        AppLogger logger = new AppLogger();
        SimulationManager sim = new SimulationManager(logger);

        assertFalse(sim.isEnabled());
        assertTrue(sim.getCurrentReadings().isEmpty());

        sim.setEnabled(true);
        List<DeviceReading> readings = sim.getCurrentReadings();
        assertEquals(3, readings.size());
        assertTrue(readings.stream().allMatch(r -> r.sourceType() == DataSourceType.SIMULATED));

        // Simular carregamento no Cyclone 2 e repouso no Delux M800 Pro
        sim.updateDevice(SimulationManager.SIM_CYCLONE2_ID, 42, true, true, false);
        sim.updateDevice(SimulationManager.SIM_DELUX_ID, 85, false, true, true);
        sim.updateDevice(SimulationManager.SIM_AKKO_ID, 62, false, false, false);

        List<DeviceReading> updated = sim.getCurrentReadings();
        DeviceReading cyclone = updated.stream()
                .filter(r -> r.deviceId().equals(SimulationManager.SIM_CYCLONE2_ID))
                .findFirst()
                .orElseThrow();
        assertEquals(42, cyclone.batteryPercent());
        assertEquals(DeviceType.GAMEPAD, cyclone.deviceType());
        assertEquals(ConnectionStatus.CHARGING, cyclone.status());
        assertTrue(cyclone.charging());
        assertTrue(cyclone.isSimulated());

        DeviceReading deluxAsleep = updated.stream()
                .filter(r -> r.deviceId().equals(SimulationManager.SIM_DELUX_ID))
                .findFirst()
                .orElseThrow();
        assertEquals(ConnectionStatus.ASLEEP, deluxAsleep.status());
        assertNull(deluxAsleep.batteryPercent());
        assertEquals(85, deluxAsleep.lastKnownPercent());
        assertTrue(deluxAsleep.isSimulated());

        DeviceReading akkoDisconnected = updated.stream()
                .filter(r -> r.deviceId().equals(SimulationManager.SIM_AKKO_ID))
                .findFirst()
                .orElseThrow();
        assertEquals(ConnectionStatus.DISCONNECTED, akkoDisconnected.status());
        assertNull(akkoDisconnected.batteryPercent());
        assertEquals(62, akkoDisconnected.lastKnownPercent());
        assertTrue(akkoDisconnected.isSimulated());
    }

    @Test
    void deviceManagerRejectsSimulatedDevicesToGuaranteeIsolation() {
        AppLogger logger = new AppLogger();
        try (DeviceManager manager = new DeviceManager(logger)) {
            SimulatedBatteryDevice simDevice = new SimulatedBatteryDevice(
                    "sim-test", "Teste Simulado", DeviceType.GAMEPAD, "2.4 GHz", 50, false, true
            );
            assertThrows(IllegalArgumentException.class, () -> manager.registerDevice(simDevice));
        }
    }

    @Test
    void deviceManagerPreservesLastKnownPercentWithoutTreatingItAsCurrent() {
        AppLogger logger = new AppLogger();
        AtomicBoolean connected = new AtomicBoolean(true);

        try (DeviceManager manager = new DeviceManager(logger)) {
            manager.registerDevice(new BatteryDevice() {
                @Override public String getId() { return "real-mouse"; }
                @Override public String getDisplayName() { return "Mouse Teste"; }
                @Override public DeviceType getDeviceType() { return DeviceType.MOUSE; }
                @Override public String getConnectionDescription() { return "2.4 GHz"; }
                @Override public DataSourceType getSourceType() { return DataSourceType.REAL; }
                @Override public DeviceReading readBattery() {
                    if (connected.get()) {
                        return DeviceReading.realConnected(
                                getId(), getDisplayName(), DeviceType.MOUSE, 73, false, "2.4 GHz", "OK"
                        );
                    }
                    return DeviceReading.realDisconnected(
                            getId(), getDisplayName(), DeviceType.MOUSE, "2.4 GHz", "Desconectado"
                    );
                }
            });

            List<DeviceReading> first = manager.pollOnce();
            assertEquals(73, first.get(0).batteryPercent());
            assertEquals(ConnectionStatus.CONNECTED, first.get(0).status());

            connected.set(false);
            List<DeviceReading> second = manager.pollOnce();
            assertNull(second.get(0).batteryPercent());
            assertEquals(ConnectionStatus.DISCONNECTED, second.get(0).status());
            assertEquals(73, second.get(0).lastKnownPercent());
            assertNotNull(second.get(0).lastKnownAt());
        }
    }

    @Test
    void deviceManagerReportsDisconnectedWhenRealDriverFails() {
        AppLogger logger = new AppLogger();
        try (DeviceManager manager = new DeviceManager(logger)) {
            manager.registerDevice(new BatteryDevice() {
                @Override public String getId() { return "failing-real"; }
                @Override public String getDisplayName() { return "Periférico Real"; }
                @Override public DeviceType getDeviceType() { return DeviceType.HEADSET; }
                @Override public String getConnectionDescription() { return "2.4 GHz"; }
                @Override public DataSourceType getSourceType() { return DataSourceType.REAL; }
                @Override public DeviceReading readBattery() {
                    throw new RuntimeException("Sem acesso HID");
                }
            });

            List<DeviceReading> readings = manager.pollOnce();
            assertEquals(1, readings.size());
            assertEquals(ConnectionStatus.DISCONNECTED, readings.get(0).status());
            assertEquals(DataSourceType.REAL, readings.get(0).sourceType());
            assertNull(readings.get(0).batteryPercent());
        }
    }

    @Test
    void akkoVergeS9ReportsPendingProtocolWithoutSendingCommands() {
        AppLogger logger = new AppLogger();
        try (HidServiceProvider provider = new HidServiceProvider(logger)) {
            AkkoVergeS9 akko = new AkkoVergeS9(provider, AkkoVergeS9Config.unconfigured(), logger);
            DeviceReading reading = akko.readBattery();
            assertEquals(ConnectionStatus.PENDING_PROTOCOL, reading.status());
            assertEquals(DataSourceType.REAL, reading.sourceType());
            assertEquals(DeviceType.HEADSET, reading.deviceType());
            assertNull(reading.batteryPercent());
        }
    }

    @Test
    void gameSirCyclone2ReportsDisconnectedWhenNoHardwareAttached() {
        AppLogger logger = new AppLogger();
        try (HidServiceProvider provider = new HidServiceProvider(logger)) {
            GameSirCyclone2 pad = new GameSirCyclone2(provider, GameSirCyclone2Config.defaultReference(), logger);
            DeviceReading reading = pad.readBattery(List.of());
            assertEquals(ConnectionStatus.DISCONNECTED, reading.status());
            assertEquals(DataSourceType.REAL, reading.sourceType());
            assertEquals(DeviceType.GAMEPAD, reading.deviceType());
            assertNull(reading.batteryPercent());
        }
    }
}
