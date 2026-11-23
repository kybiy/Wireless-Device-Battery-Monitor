package br.com.wirelessbattery.device;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.ConnectionStatus;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DeviceDriverRegistryTest {

    @Test
    void registersThreeTargetDevicesInPriorityOrderWithoutConflicts() {
        DeviceDriverRegistry registry = new DeviceDriverRegistry();

        registry.register(new DeviceDriverRegistry.DriverRegistration(
                DeluxM800Pro.DEVICE_ID, DeluxM800Pro.DISPLAY_NAME, 1, false, true, 1,
                (vid, pid) -> vid == 0x248A && (pid == 0x5B2F || pid == 0x5B2E),
                () -> dummyDriver(DeluxM800Pro.DEVICE_ID, DeluxM800Pro.DISPLAY_NAME, DeviceType.MOUSE)
        ));
        registry.register(new DeviceDriverRegistry.DriverRegistration(
                AkkoVergeS9.DEVICE_ID, AkkoVergeS9.DISPLAY_NAME, 2, false, false, -1,
                (vid, pid) -> false,
                () -> dummyDriver(AkkoVergeS9.DEVICE_ID, AkkoVergeS9.DISPLAY_NAME, DeviceType.HEADSET)
        ));
        registry.register(new DeviceDriverRegistry.DriverRegistration(
                GameSirCyclone2.DEVICE_ID, GameSirCyclone2.DISPLAY_NAME, 3, false, true, -1,
                (vid, pid) -> vid == 0x3537 && (pid == 0x100B || pid == 0x0575),
                () -> dummyDriver(GameSirCyclone2.DEVICE_ID, GameSirCyclone2.DISPLAY_NAME, DeviceType.GAMEPAD)
        ));

        List<DeviceDriverRegistry.ActiveDriverBind> active = registry.resolveActiveDrivers(List.of(), Set.of());
        assertEquals(3, active.size());
        assertEquals(DeluxM800Pro.DEVICE_ID, active.get(0).driver().getId());
        assertEquals(AkkoVergeS9.DEVICE_ID, active.get(1).driver().getId());
        assertEquals(GameSirCyclone2.DEVICE_ID, active.get(2).driver().getId());
    }

    @Test
    void managesThreeDevicesIncludingCyclone2ConnectSleepAndDisconnect() {
        AppLogger logger = new AppLogger();
        AtomicBoolean cyclonePresent = new AtomicBoolean(false);
        AtomicBoolean cycloneAwake = new AtomicBoolean(true);

        try (DeviceManager manager = new DeviceManager(logger)) {
            manager.registerDevice(new BatteryDevice() {
                @Override public String getId() { return DeluxM800Pro.DEVICE_ID; }
                @Override public String getDisplayName() { return DeluxM800Pro.DISPLAY_NAME; }
                @Override public DeviceType getDeviceType() { return DeviceType.MOUSE; }
                @Override public String getConnectionDescription() { return "2.4 GHz"; }
                @Override public DataSourceType getSourceType() { return DataSourceType.REAL; }
                @Override public boolean isHardwarePresent(List<org.hid4java.HidDevice> attached) { return true; }
                @Override public DeviceReading readBattery() {
                    return DeviceReading.realConnected(getId(), getDisplayName(), DeviceType.MOUSE, 85, false, "2.4 GHz", "OK");
                }
            });

            manager.registerDevice(new BatteryDevice() {
                @Override public String getId() { return AkkoVergeS9.DEVICE_ID; }
                @Override public String getDisplayName() { return AkkoVergeS9.DISPLAY_NAME; }
                @Override public DeviceType getDeviceType() { return DeviceType.HEADSET; }
                @Override public String getConnectionDescription() { return "2.4 GHz"; }
                @Override public DataSourceType getSourceType() { return DataSourceType.REAL; }
                @Override public boolean supportsBatteryQuery() { return false; }
                @Override public DeviceReading readBattery() {
                    return DeviceReading.realPendingProtocol(getId(), getDisplayName(), DeviceType.HEADSET, "2.4 GHz", "Pendente");
                }
            });

            manager.registerDevice(new BatteryDevice() {
                @Override public String getId() { return GameSirCyclone2.DEVICE_ID; }
                @Override public String getDisplayName() { return GameSirCyclone2.DISPLAY_NAME; }
                @Override public DeviceType getDeviceType() { return DeviceType.GAMEPAD; }
                @Override public String getConnectionDescription() { return "2.4 GHz / USB"; }
                @Override public DataSourceType getSourceType() { return DataSourceType.REAL; }
                @Override public boolean isHardwarePresent(List<org.hid4java.HidDevice> attached) {
                    return cyclonePresent.get();
                }
                @Override public DeviceReading readBattery() {
                    if (!cyclonePresent.get()) {
                        return DeviceReading.realDisconnected(getId(), getDisplayName(), DeviceType.GAMEPAD, "2.4 GHz / USB", "Desconectado");
                    }
                    if (!cycloneAwake.get()) {
                        return DeviceReading.realAsleep(getId(), getDisplayName(), DeviceType.GAMEPAD, "2.4 GHz", "Repouso");
                    }
                    return DeviceReading.realConnected(getId(), getDisplayName(), DeviceType.GAMEPAD, 90, false, "2.4 GHz / USB", "OK");
                }
            });

            // Ciclo 1: Delux conectado, Akko pendente, Cyclone 2 desconectado
            List<DeviceReading> poll1 = manager.pollOnce();
            assertEquals(3, poll1.size());
            assertTrue(manager.isDeviceDetected(DeluxM800Pro.DEVICE_ID));
            assertFalse(manager.isDeviceDetected(AkkoVergeS9.DEVICE_ID));
            assertFalse(manager.isDeviceDetected(GameSirCyclone2.DEVICE_ID));

            // Ciclo 2: Cyclone 2 conectado -> 90%
            cyclonePresent.set(true);
            List<DeviceReading> poll2 = manager.pollOnce();
            DeviceReading pad2 = poll2.get(2);
            assertEquals(90, pad2.batteryPercent());
            assertEquals(ConnectionStatus.CONNECTED, pad2.status());
            assertTrue(manager.isDeviceDetected(GameSirCyclone2.DEVICE_ID));

            // Ciclo 3: Cyclone 2 entra em repouso -> ASLEEP com última leitura 90%
            cycloneAwake.set(false);
            List<DeviceReading> poll3 = manager.pollOnce();
            DeviceReading pad3 = poll3.get(2);
            assertEquals(ConnectionStatus.ASLEEP, pad3.status());
            assertNull(pad3.batteryPercent());
            assertEquals(90, pad3.lastKnownPercent());

            // Ciclo 4: Receptor Cyclone 2 desconectado -> DISCONNECTED preservando última leitura 90%
            cyclonePresent.set(false);
            List<DeviceReading> poll4 = manager.pollOnce();
            DeviceReading pad4 = poll4.get(2);
            assertEquals(ConnectionStatus.DISCONNECTED, pad4.status());
            assertEquals(90, pad4.lastKnownPercent());
            assertFalse(manager.isDeviceDetected(GameSirCyclone2.DEVICE_ID));
        }
    }

    private static BatteryDevice dummyDriver(String id, String name, DeviceType type) {
        return new BatteryDevice() {
            @Override public String getId() { return id; }
            @Override public String getDisplayName() { return name; }
            @Override public DeviceType getDeviceType() { return type; }
            @Override public String getConnectionDescription() { return "2.4 GHz"; }
            @Override public DataSourceType getSourceType() { return DataSourceType.REAL; }
            @Override public DeviceReading readBattery() {
                return DeviceReading.realDisconnected(id, name, type, "2.4 GHz", "");
            }
        };
    }
}
