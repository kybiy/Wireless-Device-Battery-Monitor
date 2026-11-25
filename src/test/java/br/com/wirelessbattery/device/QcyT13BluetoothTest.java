package br.com.wirelessbattery.device;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.bluetooth.BluetoothDeviceInfo;
import br.com.wirelessbattery.bluetooth.BluetoothDeviceScanner;
import br.com.wirelessbattery.bluetooth.WindowsBluetoothScanner;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.ConnectionStatus;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import com.sun.jna.platform.win32.Ole32;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class QcyT13BluetoothTest {

    @Test
    void configMatchesQcyT13VariantsAndIgnoresUnrelatedDevices() {
        QcyT13Config config = QcyT13Config.defaultReference();

        assertTrue(config.matchesDeviceName("QCY T13"));
        assertTrue(config.matchesDeviceName("qcy t13"));
        assertTrue(config.matchesDeviceName("QCY-T13"));
        assertTrue(config.matchesDeviceName("QCY_T13"));
        assertTrue(config.matchesDeviceName("QCY T13 ANC"));
        assertTrue(config.matchesDeviceName("QCY-T13X"));

        assertFalse(config.matchesDeviceName("JBL Tune 510BT"));
        assertFalse(config.matchesDeviceName("QCY HT05"));
        assertFalse(config.matchesDeviceName("QCY T1"));
        assertFalse(config.matchesDeviceName("QCY T130"));
        assertFalse(config.matchesDeviceName(""));
        assertFalse(config.matchesDeviceName(null));
    }

    @Test
    void configSupportsSystemPropertyOverride() {
        String prev = System.getProperty(QcyT13Config.PROPERTY_NAME_PATTERN);
        try {
            System.setProperty(QcyT13Config.PROPERTY_NAME_PATTERN, "MeuFone-BT");
            QcyT13Config loaded = QcyT13Config.loadFromEnvironment();
            assertTrue(loaded.matchesDeviceName("MeuFone-BT Studio"));
            assertFalse(loaded.matchesDeviceName("QCY T13"));
        } finally {
            if (prev == null) {
                System.clearProperty(QcyT13Config.PROPERTY_NAME_PATTERN);
            } else {
                System.setProperty(QcyT13Config.PROPERTY_NAME_PATTERN, prev);
            }
        }
    }

    @Test
    void readsValidBatteryPercentageAndPopulatesContainerId() {
        String containerId = "{11111111-2222-3333-4444-555555555555}";
        BluetoothDeviceScanner scanner = () -> new BluetoothDeviceScanner.ScanResult(
                true,
                List.of(
                        new BluetoothDeviceInfo(
                                "{99999999-0000-0000-0000-000000000000}",
                                "JBL Tune 510BT",
                                true,
                                90,
                                false,
                                "BTHENUM\\DEV_AABBCCDDEE01\\7&1"
                        ),
                        new BluetoothDeviceInfo(
                                containerId,
                                "QCY-T13",
                                true,
                                76,
                                false,
                                "BTHENUM\\DEV_AABBCCDDEE02\\7&2"
                        )
                ),
                "OK"
        );

        AppLogger logger = new AppLogger(null);
        QcyT13Bluetooth driver = new QcyT13Bluetooth(scanner, QcyT13Config.defaultReference(), logger);

        assertEquals(QcyT13Bluetooth.DEVICE_ID, driver.getId());
        assertEquals(QcyT13Bluetooth.DISPLAY_NAME, driver.getDisplayName());
        assertEquals(DeviceType.HEADSET, driver.getDeviceType());
        assertEquals(QcyT13Bluetooth.CONNECTION, driver.getConnectionDescription());
        assertEquals(DataSourceType.REAL, driver.getSourceType());
        assertFalse(driver.isOptional());
        assertTrue(driver.supportsBatteryQuery());

        DeviceReading reading = driver.readBattery();
        assertEquals(ConnectionStatus.CONNECTED, reading.status());
        assertEquals(76, reading.batteryPercent());
        assertFalse(reading.charging());
        assertEquals("Bluetooth", reading.connectionLabel());
        assertEquals(DataSourceType.REAL, reading.sourceType());
        assertEquals(DeviceType.HEADSET, reading.deviceType());
        assertTrue(reading.detailMessage().contains("QCY-T13"));
        assertEquals(containerId, driver.getLastMatchedContainerId());
    }

    @Test
    void returnsBatteryUnavailableWhenConnectedWithoutBatteryProperty() {
        String containerId = "{AAAA1111-2222-3333-4444-555555555555}";
        BluetoothDeviceScanner scanner = () -> new BluetoothDeviceScanner.ScanResult(
                true,
                List.of(new BluetoothDeviceInfo(
                        containerId,
                        "QCY T13 ANC",
                        true,
                        null,
                        false,
                        "BTHENUM\\DEV_112233445566\\7&1"
                )),
                "OK"
        );

        QcyT13Bluetooth driver = new QcyT13Bluetooth(scanner, QcyT13Config.defaultReference(), new AppLogger(null));
        DeviceReading reading = driver.readBattery();

        assertEquals(ConnectionStatus.BATTERY_UNAVAILABLE, reading.status());
        assertTrue(reading.status().isReceiverDetectedState());
        assertNull(reading.batteryPercent());
        assertEquals(containerId, driver.getLastMatchedContainerId());
        assertEquals(
                "QCY T13 conectado via Bluetooth (QCY T13 ANC), mas o Windows não expôs nível de bateria.",
                reading.detailMessage()
        );
    }

    @Test
    void returnsDisconnectedWhenDeviceDisconnectedOrAdapterMissingAndClearsContainerId() {
        AtomicReference<BluetoothDeviceScanner.ScanResult> nextScan = new AtomicReference<>(
                new BluetoothDeviceScanner.ScanResult(
                        true,
                        List.of(new BluetoothDeviceInfo(
                                "{AAAA1111-2222-3333-4444-555555555555}",
                                "QCY T13",
                                false,
                                80, // Mesmo se vier cacheado no DTO, desconectado nunca reporta bateria
                                false,
                                "BTHENUM\\DEV_112233445566\\7&1"
                        )),
                        "Desconectado"
                )
        );
        AppLogger logger = new AppLogger(null);
        QcyT13Bluetooth driver = new QcyT13Bluetooth(nextScan::get, QcyT13Config.defaultReference(), logger);

        DeviceReading disconnected = driver.readBattery();
        assertEquals(ConnectionStatus.DISCONNECTED, disconnected.status());
        assertNull(disconnected.batteryPercent());
        assertNull(driver.getLastMatchedContainerId());
        assertEquals("QCY T13 desconectado no Bluetooth.", disconnected.detailMessage());

        // Sem adaptador Bluetooth (e duas leituras seguidas não devem duplicar log de transição)
        nextScan.set(new BluetoothDeviceScanner.ScanResult(false, List.of(), "Adaptador ausente"));
        int historyBefore = logger.getHistory().size();

        DeviceReading noAdapter1 = driver.readBattery();
        int historyAfterFirst = logger.getHistory().size();
        DeviceReading noAdapter2 = driver.readBattery();
        int historyAfterSecond = logger.getHistory().size();

        assertEquals(ConnectionStatus.DISCONNECTED, noAdapter1.status());
        assertEquals(ConnectionStatus.DISCONNECTED, noAdapter2.status());
        assertEquals("Adaptador Bluetooth ausente ou desativado.", noAdapter1.detailMessage());
        assertNull(driver.getLastMatchedContainerId());
        assertEquals(historyBefore + 1, historyAfterFirst);
        assertEquals(historyAfterFirst, historyAfterSecond);
    }

    @Test
    void handlesDisconnectThenReconnectAndPrefersConnectedMatch() {
        String containerId = "{BBBB1111-2222-3333-4444-555555555555}";
        AtomicReference<BluetoothDeviceScanner.ScanResult> nextScan = new AtomicReference<>(
                new BluetoothDeviceScanner.ScanResult(
                        true,
                        List.of(new BluetoothDeviceInfo(
                                containerId,
                                "QCY T13",
                                true,
                                76,
                                false,
                                "BTHENUM\\DEV_112233445566\\7&1"
                        )),
                        "OK"
                )
        );

        QcyT13Bluetooth driver = new QcyT13Bluetooth(nextScan::get, QcyT13Config.defaultReference(), new AppLogger(null));

        // 1. Conectado -> 76%
        DeviceReading r1 = driver.readBattery();
        assertEquals(ConnectionStatus.CONNECTED, r1.status());
        assertEquals(76, r1.batteryPercent());
        assertEquals(containerId, driver.getLastMatchedContainerId());

        // 2. Desconectado -> limpa containerId
        nextScan.set(new BluetoothDeviceScanner.ScanResult(
                true,
                List.of(new BluetoothDeviceInfo(
                        containerId,
                        "QCY T13",
                        false,
                        null,
                        false,
                        "BTHENUM\\DEV_112233445566\\7&1"
                )),
                "Desconectado"
        ));
        DeviceReading r2 = driver.readBattery();
        assertEquals(ConnectionStatus.DISCONNECTED, r2.status());
        assertNull(r2.batteryPercent());
        assertNull(driver.getLastMatchedContainerId());

        // 3. Reconectado (com entrada antiga desconectada + nova entrada conectada) -> 84%
        String newContainerId = "{CCCC1111-2222-3333-4444-555555555555}";
        nextScan.set(new BluetoothDeviceScanner.ScanResult(
                true,
                List.of(
                        new BluetoothDeviceInfo(
                                containerId,
                                "QCY T13",
                                false,
                                null,
                                false,
                                "BTHENUM\\DEV_000000000001\\7&1"
                        ),
                        new BluetoothDeviceInfo(
                                newContainerId,
                                "QCY T13",
                                true,
                                84,
                                false,
                                "BTHENUM\\DEV_112233445566\\7&2"
                        )
                ),
                "OK"
        ));
        DeviceReading r3 = driver.readBattery();
        assertEquals(ConnectionStatus.CONNECTED, r3.status());
        assertEquals(84, r3.batteryPercent());
        assertEquals(newContainerId, driver.getLastMatchedContainerId());
    }

    @Test
    void deviceManagerDetectsConnectedAndBatteryUnavailableStatesAndPreservesLastKnown() {
        String containerId = "{DDDD1111-2222-3333-4444-555555555555}";
        AtomicReference<BluetoothDeviceScanner.ScanResult> nextScan = new AtomicReference<>(
                new BluetoothDeviceScanner.ScanResult(
                        true,
                        List.of(new BluetoothDeviceInfo(
                                containerId, "QCY T13", true, 76, false, "BTHENUM\\DEV_1\\7&1"
                        )),
                        "OK"
                )
        );
        QcyT13Bluetooth driver = new QcyT13Bluetooth(nextScan::get, QcyT13Config.defaultReference(), new AppLogger(null));

        try (DeviceManager manager = new DeviceManager(new AppLogger(null))) {
            manager.registerDevice(driver);

            List<DeviceReading> poll1 = manager.pollOnce();
            assertEquals(1, poll1.size());
            assertEquals(76, poll1.get(0).batteryPercent());
            assertTrue(manager.isDeviceDetected(QcyT13Bluetooth.DEVICE_ID));

            // Conectado mas sem propriedade de bateria -> BATTERY_UNAVAILABLE ainda conta como detectado
            nextScan.set(new BluetoothDeviceScanner.ScanResult(
                    true,
                    List.of(new BluetoothDeviceInfo(
                            containerId, "QCY T13", true, null, false, "BTHENUM\\DEV_1\\7&1"
                    )),
                    "OK"
            ));
            List<DeviceReading> poll2 = manager.pollOnce();
            assertEquals(ConnectionStatus.BATTERY_UNAVAILABLE, poll2.get(0).status());
            assertEquals(76, poll2.get(0).lastKnownPercent());
            assertTrue(manager.isDeviceDetected(QcyT13Bluetooth.DEVICE_ID));

            // Desconectado -> não detectado e preserva lastKnownPercent
            nextScan.set(new BluetoothDeviceScanner.ScanResult(true, List.of(), "OK"));
            List<DeviceReading> poll3 = manager.pollOnce();
            assertEquals(ConnectionStatus.DISCONNECTED, poll3.get(0).status());
            assertEquals(76, poll3.get(0).lastKnownPercent());
            assertFalse(manager.isDeviceDetected(QcyT13Bluetooth.DEVICE_ID));
        }
    }

    @Test
    void stripsBluetoothServiceSuffixesAndValidatesJnaStructSizes() {
        assertEquals("QCY T13", WindowsBluetoothScanner.stripServiceSuffix("QCY T13 Hands-Free AG"));
        assertEquals("QCY T13", WindowsBluetoothScanner.stripServiceSuffix("QCY T13 Hands-Free"));
        assertEquals("QCY T13", WindowsBluetoothScanner.stripServiceSuffix("QCY T13 Avrcp Transport"));
        assertEquals("QCY T13", WindowsBluetoothScanner.stripServiceSuffix("QCY T13 Stereo"));
        assertEquals("QCY T13", WindowsBluetoothScanner.stripServiceSuffix("QCY T13 Audio Gateway"));
        assertEquals("QCY T13 ANC", WindowsBluetoothScanner.stripServiceSuffix("QCY T13 ANC Stereo"));
        assertNull(WindowsBluetoothScanner.stripServiceSuffix(null));

        WindowsBluetoothScanner.DEVPROPKEY key =
                new WindowsBluetoothScanner.DEVPROPKEY("{8C7ED206-3F8A-4827-B3AB-AE9E1FAEFC6C}", 2);
        assertEquals(20, key.size());
        assertEquals(4, new WindowsBluetoothScanner.BLUETOOTH_FIND_RADIO_PARAMS().size());

        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            char[] guidChars = new char[64];
            int copied = Ole32.INSTANCE.StringFromGUID2(key.fmtid, guidChars, guidChars.length);
            assertTrue(copied > 1);
            assertEquals("{8C7ED206-3F8A-4827-B3AB-AE9E1FAEFC6C}", new String(guidChars, 0, copied - 1));
        }
    }

    @Test
    void liveWindowsScannerSmokeTestDoesNotThrow() {
        WindowsBluetoothScanner scanner = new WindowsBluetoothScanner(250L);
        BluetoothDeviceScanner.ScanResult first = assertDoesNotThrow(scanner::scan);
        assertNotNull(first);
        assertNotNull(first.devices());
        assertNotNull(first.diagnosticMessage());
        assertThrows(UnsupportedOperationException.class, () -> first.devices().add(null));

        BluetoothDeviceScanner.ScanResult second = assertDoesNotThrow(scanner::scan);
        assertNotNull(second);
        assertEquals(first.adapterAvailable(), second.adapterAvailable());
    }
}
