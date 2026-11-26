package br.com.wirelessbattery.device;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.bluetooth.BluetoothDeviceInfo;
import br.com.wirelessbattery.bluetooth.BluetoothDeviceScanner;
import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.ConnectionStatus;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hid4java.HidDevice;
import org.hid4java.HidServicesSpecification;
import org.hid4java.jna.HidDeviceInfoStructure;
import org.junit.jupiter.api.Test;

class DriverSafetyAndResilienceTest {

    @Test
    void allFourDriversExposeAccurateCapabilitiesAndSafetyMetadata() {
        AppLogger logger = new AppLogger(null);
        try (HidServiceProvider hidProvider = new HidServiceProvider(logger)) {
            QcyT13Bluetooth qcy = new QcyT13Bluetooth(
                    () -> new BluetoothDeviceScanner.ScanResult(true, List.of(), "OK"),
                    QcyT13Config.defaultReference(),
                    logger
            );
            DriverCapabilities qcyCaps = qcy.getCapabilities();
            assertEquals(TransportType.BLUETOOTH_PNP, qcyCaps.transportType());
            assertFalse(qcyCaps.supportsChargingDetection());
            assertFalse(qcyCaps.supportsSleepDetection());
            assertEquals(10, qcyCaps.minimumSafePollSeconds());
            assertEquals(QueryMechanism.OS_PASSIVE_PROPERTY, qcyCaps.safetyInfo().mechanism());
            assertTrue(qcyCaps.safetyInfo().strictlyPassive());
            assertFalse(qcyCaps.safetyInfo().requiresHidOutputWrite());
            assertTrue(qcyCaps.safetyInfo().allowedInReadOnlyMode());
            assertFalse(qcyCaps.safetyInfo().requiresFirstUseNotice());
            assertTrue(qcyCaps.safetyInfo().referenceUrl().contains("Wireless-Device-Battery-Monitor-Fallen"));

            DeluxM800Pro delux = new DeluxM800Pro(hidProvider, DeluxM800ProConfig.defaultReference(), logger);
            DriverCapabilities deluxCaps = delux.getCapabilities();
            assertEquals(TransportType.USB_HID, deluxCaps.transportType());
            assertTrue(deluxCaps.supportsChargingDetection());
            assertFalse(deluxCaps.supportsSleepDetection());
            assertEquals(10, deluxCaps.minimumSafePollSeconds());
            assertEquals(QueryMechanism.HID_FEATURE_REPORT_QUERY, deluxCaps.safetyInfo().mechanism());
            assertFalse(deluxCaps.safetyInfo().strictlyPassive());
            assertFalse(deluxCaps.safetyInfo().requiresHidOutputWrite());
            assertTrue(deluxCaps.safetyInfo().allowedInReadOnlyMode());
            assertFalse(deluxCaps.safetyInfo().requiresFirstUseNotice());
            assertTrue(deluxCaps.safetyInfo().protocolSummary().contains("0x0C"));
            assertTrue(deluxCaps.safetyInfo().referenceUrl().contains("xb-bx/m800-pro-driver"));

            AkkoVergeS9 akko = new AkkoVergeS9(hidProvider, AkkoVergeS9Config.defaultReference(), logger);
            DriverCapabilities akkoCaps = akko.getCapabilities();
            assertEquals(TransportType.USB_HID, akkoCaps.transportType());
            assertFalse(akkoCaps.supportsChargingDetection());
            assertTrue(akkoCaps.supportsSleepDetection());
            assertEquals(10, akkoCaps.minimumSafePollSeconds());
            assertEquals(QueryMechanism.HID_OUTPUT_REPORT_VALIDATED, akkoCaps.safetyInfo().mechanism());
            assertFalse(akkoCaps.safetyInfo().strictlyPassive());
            assertTrue(akkoCaps.safetyInfo().requiresHidOutputWrite());
            assertTrue(akkoCaps.safetyInfo().allowedInReadOnlyMode());
            assertFalse(akkoCaps.safetyInfo().requiresFirstUseNotice());

            GameSirCyclone2 cyclone = new GameSirCyclone2(hidProvider, GameSirCyclone2Config.defaultReference(), logger);
            DriverCapabilities cycloneCaps = cyclone.getCapabilities();
            assertEquals(TransportType.USB_HID, cycloneCaps.transportType());
            assertTrue(cycloneCaps.supportsChargingDetection());
            assertTrue(cycloneCaps.supportsSleepDetection());
            assertEquals(10, cycloneCaps.minimumSafePollSeconds());
            assertEquals(QueryMechanism.HID_OUTPUT_REPORT_EXPERIMENTAL, cycloneCaps.safetyInfo().mechanism());
            assertFalse(cycloneCaps.safetyInfo().strictlyPassive());
            assertTrue(cycloneCaps.safetyInfo().requiresHidOutputWrite());
            assertFalse(cycloneCaps.safetyInfo().allowedInReadOnlyMode());
            assertTrue(cycloneCaps.safetyInfo().requiresFirstUseNotice());
            assertTrue(cycloneCaps.safetyInfo().protocolSummary().contains("0x0F 0x03"));
            assertTrue(cycloneCaps.safetyInfo().protocolSummary().contains("EPIPE"));
            assertTrue(cycloneCaps.safetyInfo().protocolSummary().contains("0x20"));
            assertTrue(cycloneCaps.safetyInfo().referenceUrl().contains("vdemonchy/cyclone2-linux"));
        }
    }

    @Test
    void readOnlyModeBlocksGameSirWithZeroHidWritesWhileAllowingDeluxAkkoAndQcy() {
        AppLogger logger = new AppLogger(null);
        AppSettings settings = new AppSettings();
        settings.setReadOnlyHidMode(false);
        settings.setGameSirMonitoringEnabled(true);

        StubHidDevice deluxHid = createDeluxFeatureStub(78, false);
        StubHidDevice akkoHid = createAkkoBatteryStub(47);
        StubHidDevice cycloneHid = createCyclone2BatteryStub(88, false);
        List<HidDevice> attached = List.of(deluxHid, akkoHid, cycloneHid);

        try (HidServiceProvider hidProvider = new HidServiceProvider(logger);
             DeviceManager manager = new DeviceManager(hidProvider, settings, logger)) {

            DeluxM800Pro realDelux = new DeluxM800Pro(hidProvider, DeluxM800ProConfig.defaultReference(), logger);
            AkkoVergeS9 realAkko = new AkkoVergeS9(hidProvider, AkkoVergeS9Config.defaultReference(), logger);
            GameSirCyclone2 realCyclone = new GameSirCyclone2(hidProvider, GameSirCyclone2Config.defaultReference(), logger);
            QcyT13Bluetooth qcy = new QcyT13Bluetooth(
                    () -> new BluetoothDeviceScanner.ScanResult(
                            true,
                            List.of(new BluetoothDeviceInfo(
                                    "{11111111-2222-3333-4444-555555555555}",
                                    "QCY T13",
                                    true,
                                    76,
                                    false,
                                    "BTHENUM\\DEV_1\\7&1"
                            )),
                            "OK"
                    ),
                    QcyT13Config.defaultReference(),
                    logger
            );

            manager.registerDevice(wrapWithAttachedHid(realDelux, attached));
            manager.registerDevice(wrapWithAttachedHid(realAkko, attached));
            manager.registerDevice(wrapWithAttachedHid(realCyclone, attached));
            manager.registerDevice(qcy);

            // 1. Modo normal: GameSir é consultado e registra validReadingInstant
            List<DeviceReading> normalReadings = manager.pollOnce();
            DeviceReading cycloneNormal = findById(normalReadings, GameSirCyclone2.DEVICE_ID);
            assertEquals(ConnectionStatus.CONNECTED, cycloneNormal.status());
            assertEquals(88, cycloneNormal.batteryPercent());
            Instant validInstant = cycloneNormal.validReadingInstant();
            assertNotNull(validInstant);
            assertEquals(1, cycloneHid.openCount.get());
            assertEquals(1, cycloneHid.writeCount.get());

            // 2. Ativa modo somente leitura: GameSir deve ser bloqueado sem abrir ou escrever no HID
            settings.setReadOnlyHidMode(true);
            List<DeviceReading> readOnlyReadings = manager.pollOnce();

            DeviceReading cycloneBlocked = findById(readOnlyReadings, GameSirCyclone2.DEVICE_ID);
            assertEquals(ConnectionStatus.READ_ONLY_BLOCKED, cycloneBlocked.status());
            assertNull(cycloneBlocked.batteryPercent());
            assertEquals(88, cycloneBlocked.lastKnownPercent());
            assertEquals(validInstant, cycloneBlocked.lastKnownInstant());
            assertTrue(cycloneBlocked.detailMessage().contains("0x0F 0x03"));
            assertEquals(1, cycloneHid.openCount.get(), "GameSir não deve abrir HID em modo somente leitura");
            assertEquals(1, cycloneHid.writeCount.get(), "GameSir não deve enviar Output Report em modo somente leitura");

            DeviceReading deluxReading = findById(readOnlyReadings, DeluxM800Pro.DEVICE_ID);
            assertEquals(ConnectionStatus.CONNECTED, deluxReading.status());
            assertEquals(78, deluxReading.batteryPercent());

            DeviceReading akkoReading = findById(readOnlyReadings, AkkoVergeS9.DEVICE_ID);
            assertEquals(ConnectionStatus.CONNECTED, akkoReading.status());
            assertEquals(47, akkoReading.batteryPercent());

            DeviceReading qcyReading = findById(readOnlyReadings, QcyT13Bluetooth.DEVICE_ID);
            assertEquals(ConnectionStatus.CONNECTED, qcyReading.status());
            assertEquals(76, qcyReading.batteryPercent());
        }
    }

    @Test
    void disablingGameSirMonitoringReportsMonitoringDisabledWithoutTouchingHardware() {
        AppLogger logger = new AppLogger(null);
        AppSettings settings = new AppSettings();
        settings.setGameSirMonitoringEnabled(false);

        StubHidDevice cycloneHid = createCyclone2BatteryStub(65, false);
        try (HidServiceProvider hidProvider = new HidServiceProvider(logger);
             DeviceManager manager = new DeviceManager(hidProvider, settings, logger)) {

            GameSirCyclone2 realCyclone = new GameSirCyclone2(hidProvider, GameSirCyclone2Config.defaultReference(), logger);
            DeluxM800Pro realDelux = new DeluxM800Pro(hidProvider, DeluxM800ProConfig.defaultReference(), logger);
            StubHidDevice deluxHid = createDeluxFeatureStub(81, false);
            List<HidDevice> attached = List.of(cycloneHid, deluxHid);

            manager.registerDevice(wrapWithAttachedHid(realDelux, attached));
            manager.registerDevice(wrapWithAttachedHid(realCyclone, attached));

            List<DeviceReading> readings = manager.pollOnce();
            DeviceReading cyclone = findById(readings, GameSirCyclone2.DEVICE_ID);
            assertEquals(ConnectionStatus.MONITORING_DISABLED, cyclone.status());
            assertNull(cyclone.batteryPercent());
            assertEquals(0, cycloneHid.openCount.get());
            assertEquals(0, cycloneHid.writeCount.get());

            DeviceReading delux = findById(readings, DeluxM800Pro.DEVICE_ID);
            assertEquals(ConnectionStatus.CONNECTED, delux.status());
            assertEquals(81, delux.batteryPercent());
        }
    }

    @Test
    void cyclone2ProtocolAllowsActiveZeroPercentWhileRejectingIdleDongleAndInvalidFrames() {
        // 0% ativo com analógicos não zerados (não é frame ocioso 12 00..00 CC)
        byte[] activeZeroDischarging = new byte[64];
        activeZeroDischarging[0] = 0x12;
        activeZeroDischarging[10] = (byte) 0x80;
        activeZeroDischarging[35] = 0x00;
        activeZeroDischarging[36] = 0x00;
        GameSirCyclone2Protocol.ParsedBattery zeroDischarging =
                GameSirCyclone2Protocol.parseInputReport(activeZeroDischarging, 64);
        assertNotNull(zeroDischarging);
        assertEquals(0, zeroDischarging.percent());
        assertFalse(zeroDischarging.charging());

        // 0% ativo carregando (data[35] == 1)
        byte[] activeZeroCharging = new byte[64];
        activeZeroCharging[0] = 0x12;
        activeZeroCharging[35] = 0x01;
        activeZeroCharging[36] = 0x00;
        GameSirCyclone2Protocol.ParsedBattery zeroCharging =
                GameSirCyclone2Protocol.parseInputReport(activeZeroCharging, 64);
        assertNotNull(zeroCharging);
        assertEquals(0, zeroCharging.percent());
        assertTrue(zeroCharging.charging());

        // Frame ocioso 12 00..00 CC deve continuar retornando null
        byte[] idleDongle = new byte[64];
        idleDongle[0] = 0x12;
        idleDongle[63] = (byte) 0xCC;
        assertTrue(GameSirCyclone2Protocol.isIdleDongleReport(idleDongle, 64));
        assertNull(GameSirCyclone2Protocol.parseInputReport(idleDongle, 64));

        // 100% válido
        byte[] full = new byte[64];
        full[0] = 0x12;
        full[35] = 0x01;
        full[36] = 100;
        GameSirCyclone2Protocol.ParsedBattery fullParsed =
                GameSirCyclone2Protocol.parseInputReport(full, 64);
        assertNotNull(fullParsed);
        assertEquals(100, fullParsed.percent());
        assertTrue(fullParsed.charging());

        // Frames truncados e inválidos
        assertNull(GameSirCyclone2Protocol.parseInputReport(null, 64));
        assertNull(GameSirCyclone2Protocol.parseInputReport(activeZeroCharging, 0));
        assertNull(GameSirCyclone2Protocol.parseInputReport(new byte[36], 36));
        byte[] invalidOver100 = new byte[64];
        invalidOver100[0] = 0x12;
        invalidOver100[36] = (byte) 101;
        assertNull(GameSirCyclone2Protocol.parseInputReport(invalidOver100, 64));
    }

    @Test
    void cyclone2HandlesBusyInterfaceAndMidReadDisconnectWithoutReturningZeroPercent() {
        AppLogger logger = new AppLogger(null);
        try (HidServiceProvider hidProvider = new HidServiceProvider(logger)) {
            GameSirCyclone2 driver = new GameSirCyclone2(hidProvider, GameSirCyclone2Config.defaultReference(), logger);

            // 1. Falha de abertura por interface ocupada / sharing violation
            StubHidDevice busyStub = new StubHidDevice(0x3537, 0x100B, 1, 0xFFF0, 0x0040, "Cyclone 2") {
                @Override
                public boolean open() {
                    openCount.incrementAndGet();
                    return false;
                }

                @Override
                public String getLastErrorMessage() {
                    return "The process cannot access the file because it is being used by another process (sharing violation)";
                }
            };
            DeviceReading openFailed = driver.readBattery(List.of(busyStub));
            assertEquals(ConnectionStatus.OPEN_FAILED, openFailed.status());
            assertNull(openFailed.batteryPercent());
            assertTrue(openFailed.detailMessage().contains("GameSir Connect"));

            // 2. Desconexão no meio da leitura (open/write ok, read retorna -1)
            StubHidDevice disconnectMidReadStub = new StubHidDevice(0x3537, 0x100B, 1, 0xFFF0, 0x0040, "Cyclone 2") {
                @Override
                public boolean open() {
                    openCount.incrementAndGet();
                    return true;
                }

                @Override
                public int write(byte[] message, int packetLength, byte reportId) {
                    writeCount.incrementAndGet();
                    return packetLength + 1;
                }

                @Override
                public int read(byte[] data, int timeoutMillis) {
                    return -1;
                }

                @Override
                public String getLastErrorMessage() {
                    return "Device disconnected";
                }
            };
            DeviceReading readFailed = driver.readBattery(List.of(disconnectMidReadStub));
            assertEquals(ConnectionStatus.READ_FAILED, readFailed.status());
            assertNull(readFailed.batteryPercent());
            assertEquals(1, disconnectMidReadStub.closeCount.get());
        }
    }

    @Test
    void openFailedAppliesTwoXBackoffAfterThreeFailuresAndResetsOnRecovery() {
        AppLogger logger = new AppLogger(null);
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicBoolean failOpen = new AtomicBoolean(true);

        try (DeviceManager manager = new DeviceManager(logger)) {
            manager.registerDevice(new BatteryDevice() {
                @Override public String getId() { return "busy-device"; }
                @Override public String getDisplayName() { return "Busy HID Device"; }
                @Override public DeviceType getDeviceType() { return DeviceType.MOUSE; }
                @Override public String getConnectionDescription() { return "2.4 GHz"; }
                @Override public DataSourceType getSourceType() { return DataSourceType.REAL; }
                @Override public DeviceReading readBattery() {
                    callCount.incrementAndGet();
                    if (failOpen.get()) {
                        return DeviceReading.realOpenFailed(getId(), getDisplayName(), DeviceType.MOUSE, "2.4 GHz", "Sharing violation");
                    }
                    return DeviceReading.realConnected(getId(), getDisplayName(), DeviceType.MOUSE, 82, false, "2.4 GHz", "OK");
                }
            });

            // Ciclos 1, 2, 3: consulta normal -> OPEN_FAILED
            assertEquals(ConnectionStatus.OPEN_FAILED, manager.pollOnce().get(0).status());
            assertEquals(ConnectionStatus.OPEN_FAILED, manager.pollOnce().get(0).status());
            assertEquals(ConnectionStatus.OPEN_FAILED, manager.pollOnce().get(0).status());
            assertEquals(3, callCount.get());

            // Ciclo 4: pulado pelo backoff 2x
            assertEquals(ConnectionStatus.OPEN_FAILED, manager.pollOnce().get(0).status());
            assertEquals(3, callCount.get());

            // Ciclo 5: tenta novamente -> ainda OPEN_FAILED
            assertEquals(ConnectionStatus.OPEN_FAILED, manager.pollOnce().get(0).status());
            assertEquals(4, callCount.get());

            // Ciclo 6: pulado pelo backoff 2x
            assertEquals(ConnectionStatus.OPEN_FAILED, manager.pollOnce().get(0).status());
            assertEquals(4, callCount.get());

            long backoffWarnings = logger.getHistory().stream()
                    .filter(line -> line.contains("backoff 2x"))
                    .count();
            assertEquals(1, backoffWarnings, "Aviso de backoff deve ser registrado apenas uma vez");

            // Ciclo 7: recuperação -> reseta contador
            failOpen.set(false);
            DeviceReading recovered = manager.pollOnce().get(0);
            assertEquals(ConnectionStatus.CONNECTED, recovered.status());
            assertEquals(82, recovered.batteryPercent());
            assertEquals(5, callCount.get());

            // Ciclo 8: consulta imediata sem pular ciclo
            assertEquals(ConnectionStatus.CONNECTED, manager.pollOnce().get(0).status());
            assertEquals(6, callCount.get());
        }
    }

    @Test
    void slowDeviceTimeoutDoesNotBlockFastDeviceAndCloseRejectsInFlightCommit() throws Exception {
        AppLogger logger = new AppLogger(null);
        AtomicInteger prePollCalls = new AtomicInteger(0);

        try (DeviceManager manager = new DeviceManager(null, null, logger, 120L)) {
            manager.setPrePollHook(prePollCalls::incrementAndGet);

            manager.registerDevice(new BatteryDevice() {
                @Override public String getId() { return "slow-device"; }
                @Override public String getDisplayName() { return "Slow Stub"; }
                @Override public DeviceType getDeviceType() { return DeviceType.GAMEPAD; }
                @Override public String getConnectionDescription() { return "2.4 GHz"; }
                @Override public DataSourceType getSourceType() { return DataSourceType.REAL; }
                @Override public DeviceReading readBattery() {
                    try {
                        Thread.sleep(2_000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return DeviceReading.realConnected(getId(), getDisplayName(), DeviceType.GAMEPAD, 50, false, "2.4 GHz", "Late");
                }
            });

            manager.registerDevice(new BatteryDevice() {
                @Override public String getId() { return "fast-device"; }
                @Override public String getDisplayName() { return "Fast Stub"; }
                @Override public DeviceType getDeviceType() { return DeviceType.MOUSE; }
                @Override public String getConnectionDescription() { return "2.4 GHz"; }
                @Override public DataSourceType getSourceType() { return DataSourceType.REAL; }
                @Override public DeviceReading readBattery() {
                    return DeviceReading.realConnected(getId(), getDisplayName(), DeviceType.MOUSE, 91, false, "2.4 GHz", "OK");
                }
            });

            List<DeviceReading> readings = manager.pollOnce();
            assertEquals(1, prePollCalls.get());

            DeviceReading slow = findById(readings, "slow-device");
            assertEquals(ConnectionStatus.TIMEOUT, slow.status());
            assertNull(slow.batteryPercent());

            DeviceReading fast = findById(readings, "fast-device");
            assertEquals(ConnectionStatus.CONNECTED, fast.status());
            assertEquals(91, fast.batteryPercent());
        }

        // Verifica que fechar o DeviceManager no meio de uma leitura descarta o resultado sem notificar listeners
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        AtomicInteger listenerNotifications = new AtomicInteger(0);
        DeviceManager closingManager = new DeviceManager(null, null, new AppLogger(null), 2_000L);
        closingManager.addListener(r -> listenerNotifications.incrementAndGet());
        closingManager.registerDevice(new BatteryDevice() {
            @Override public String getId() { return "closing-device"; }
            @Override public String getDisplayName() { return "Closing Stub"; }
            @Override public DeviceType getDeviceType() { return DeviceType.MOUSE; }
            @Override public String getConnectionDescription() { return "2.4 GHz"; }
            @Override public DataSourceType getSourceType() { return DataSourceType.REAL; }
            @Override public DeviceReading readBattery() {
                started.countDown();
                try {
                    allowReturn.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return DeviceReading.realConnected(getId(), getDisplayName(), DeviceType.MOUSE, 77, false, "2.4 GHz", "OK");
            }
        });

        AtomicReference<List<DeviceReading>> pollResult = new AtomicReference<>();
        Thread t = new Thread(() -> pollResult.set(closingManager.pollOnce()));
        t.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        closingManager.close();
        allowReturn.countDown();
        t.join(2_000L);

        assertEquals(0, listenerNotifications.get());
        assertNull(closingManager.getCurrentReadings().get(0).batteryPercent());
    }

    @Test
    void ecoModeScalesIntervalWhenWindowHiddenAndRestoresWhenVisible() {
        AppLogger logger = new AppLogger(null);
        AppSettings settings = new AppSettings();
        settings.setEcoModeEnabled(true);

        try (DeviceManager manager = new DeviceManager(null, settings, logger)) {
            manager.startPolling(15);
            assertEquals(15L, manager.getEffectivePollIntervalSeconds());
            assertFalse(manager.isEcoModeWindowHidden());

            manager.setEcoModeWindowHidden(true);
            assertTrue(manager.isEcoModeWindowHidden());
            assertEquals(30L, manager.getEffectivePollIntervalSeconds());

            manager.updatePollInterval(25);
            assertEquals(50L, manager.getEffectivePollIntervalSeconds());

            manager.updatePollInterval(45);
            assertEquals(60L, manager.getEffectivePollIntervalSeconds());

            manager.setEcoModeWindowHidden(false);
            assertFalse(manager.isEcoModeWindowHidden());
            assertEquals(45L, manager.getEffectivePollIntervalSeconds());

            settings.setEcoModeEnabled(false);
            manager.setEcoModeWindowHidden(true);
            assertEquals(45L, manager.getEffectivePollIntervalSeconds());
        }
    }

    private static DeviceReading findById(List<DeviceReading> readings, String id) {
        return readings.stream()
                .filter(r -> id.equals(r.deviceId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Leitura ausente para " + id));
    }

    private static BatteryDevice wrapWithAttachedHid(BatteryDevice delegate, List<HidDevice> attached) {
        return new BatteryDevice() {
            @Override public String getId() { return delegate.getId(); }
            @Override public String getDisplayName() { return delegate.getDisplayName(); }
            @Override public DeviceType getDeviceType() { return delegate.getDeviceType(); }
            @Override public String getConnectionDescription() { return delegate.getConnectionDescription(); }
            @Override public DataSourceType getSourceType() { return delegate.getSourceType(); }
            @Override public DriverCapabilities getCapabilities() { return delegate.getCapabilities(); }
            @Override public boolean isHardwarePresent(List<HidDevice> ignored) { return delegate.isHardwarePresent(attached); }
            @Override public DeviceReading readBattery() { return delegate.readBattery(attached); }
            @Override public DeviceReading readBattery(List<HidDevice> ignored) { return delegate.readBattery(attached); }
        };
    }

    private static StubHidDevice createDeluxFeatureStub(int percent, boolean charging) {
        return new StubHidDevice(0x248A, 0x5B2F, 1, 0xFF00, 0x0001, "Delux M800 Pro") {
            @Override
            public boolean open() {
                openCount.incrementAndGet();
                return true;
            }

            @Override
            public int sendFeatureReport(byte[] data, byte reportId) {
                writeCount.incrementAndGet();
                return data.length + 1;
            }

            @Override
            public int getFeatureReport(byte[] data, byte reportId) {
                data[17] = (byte) (percent & 0xFF);
                data[18] = (byte) (charging ? 1 : 0);
                return DeluxM800ProProtocol.PAYLOAD_LENGTH;
            }
        };
    }

    private static StubHidDevice createAkkoBatteryStub(int percent) {
        return new StubHidDevice(0x36EB, 0x1087, 3, 0x008C, 0x0001, "Akko S9 Ultra_Dongle") {
            @Override
            public boolean open() {
                openCount.incrementAndGet();
                return true;
            }

            @Override
            public int write(byte[] message, int packetLength, byte reportId) {
                writeCount.incrementAndGet();
                return packetLength + 1;
            }

            @Override
            public int read(byte[] data, int timeoutMillis) {
                byte[] frame = new byte[AkkoS9UltraProtocol.FULL_REPORT_LENGTH];
                frame[0] = AkkoS9UltraProtocol.REPORT_ID;
                frame[1] = AkkoS9UltraProtocol.CMD_BATTERY_LEVEL;
                frame[2] = AkkoS9UltraProtocol.PAYLOAD_LENGTH_1;
                frame[3] = AkkoS9UltraProtocol.STATUS_OK;
                frame[4] = (byte) (percent & 0xFF);
                int crc = AkkoS9UltraProtocol.computeCrc16(frame, 0, AkkoS9UltraProtocol.CRC_DATA_LENGTH);
                frame[AkkoS9UltraProtocol.CRC_HIGH_INDEX] = (byte) ((crc >>> 8) & 0xFF);
                frame[AkkoS9UltraProtocol.CRC_LOW_INDEX] = (byte) (crc & 0xFF);
                System.arraycopy(frame, 0, data, 0, frame.length);
                return frame.length;
            }
        };
    }

    private static StubHidDevice createCyclone2BatteryStub(int percent, boolean charging) {
        return new StubHidDevice(0x3537, 0x100B, 1, 0xFFF0, 0x0040, "GameSir Cyclone 2") {
            @Override
            public boolean open() {
                openCount.incrementAndGet();
                return true;
            }

            @Override
            public int write(byte[] message, int packetLength, byte reportId) {
                writeCount.incrementAndGet();
                return packetLength + 1;
            }

            @Override
            public int read(byte[] data, int timeoutMillis) {
                data[0] = (byte) GameSirCyclone2Protocol.IN_REPORT_ID;
                data[GameSirCyclone2Protocol.CHARGING_INDEX] = (byte) (charging ? 1 : 0);
                data[GameSirCyclone2Protocol.BATTERY_INDEX] = (byte) (percent & 0xFF);
                return GameSirCyclone2Protocol.FULL_REPORT_LENGTH;
            }
        };
    }

    private static class StubHidDevice extends HidDevice {
        final AtomicInteger openCount = new AtomicInteger(0);
        final AtomicInteger writeCount = new AtomicInteger(0);
        final AtomicInteger closeCount = new AtomicInteger(0);

        StubHidDevice(int vid, int pid, int iface, int usagePage, int usage, String product) {
            super(createInfo(vid, pid, iface, usagePage, usage, product), null, new HidServicesSpecification());
        }

        private static HidDeviceInfoStructure createInfo(
                int vid,
                int pid,
                int iface,
                int usagePage,
                int usage,
                String product
        ) {
            HidDeviceInfoStructure info = new HidDeviceInfoStructure();
            info.path = "\\\\?\\hid#stub_" + Integer.toHexString(vid) + "_" + Integer.toHexString(pid);
            info.vendor_id = (short) vid;
            info.product_id = (short) pid;
            info.interface_number = iface;
            info.usage_page = (short) usagePage;
            info.usage = (short) usage;
            if (product != null) {
                info.product_string = new com.sun.jna.WString(product);
            }
            return info;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }
}
