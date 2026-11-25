package br.com.wirelessbattery.simulation;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.audio.AudioEndpointInfo;
import br.com.wirelessbattery.audio.DefaultAudioOutputProvider;
import br.com.wirelessbattery.bluetooth.BluetoothDeviceInfo;
import br.com.wirelessbattery.bluetooth.BluetoothDeviceScanner;
import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.device.AkkoVergeS9;
import br.com.wirelessbattery.device.AkkoVergeS9Config;
import br.com.wirelessbattery.device.BatteryDevice;
import br.com.wirelessbattery.device.DeluxM800Pro;
import br.com.wirelessbattery.device.DeluxM800ProConfig;
import br.com.wirelessbattery.device.DeviceManager;
import br.com.wirelessbattery.device.GameSirCyclone2;
import br.com.wirelessbattery.device.GameSirCyclone2Config;
import br.com.wirelessbattery.device.HidServiceProvider;
import br.com.wirelessbattery.device.QcyT13Bluetooth;
import br.com.wirelessbattery.device.QcyT13Config;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.diagnostics.HidDiagnosticsService;
import br.com.wirelessbattery.model.ConnectionStatus;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import br.com.wirelessbattery.selection.CategoryDeviceSelector;
import br.com.wirelessbattery.ui.MainWindow;
import br.com.wirelessbattery.ui.SimulationControlPanel;
import br.com.wirelessbattery.ui.TaskbarWidgetWindow;
import br.com.wirelessbattery.ui.ThemeManager;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadsetAutoSelectionAndScenariosTest {

    @Test
    void allTwelveSimulationScenariosSynchronizeMainWindowAndWidget(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requer ambiente gráfico");

        AppLogger logger = new AppLogger();
        AppSettings settings = new AppSettings(tempDir.resolve("settings.properties"));
        settings.setSimulationEnabled(true);
        settings.setShowDisconnectedDevices(false);

        ThemeManager theme = new ThemeManager();
        theme.installInitialTheme(ThemeManager.Theme.DARK);

        SimulationManager simulationManager = new SimulationManager(logger);
        AtomicReference<AudioEndpointInfo> realAudioRef = new AtomicReference<>();
        DefaultAudioOutputProvider realAudio = () -> Optional.ofNullable(realAudioRef.get());
        CategoryDeviceSelector selector = new CategoryDeviceSelector(realAudio, logger);

        try (HidServiceProvider hid = new HidServiceProvider(logger);
             DeviceManager deviceManager = new DeviceManager(hid, logger)) {

            deviceManager.registerDevice(realDisconnectedStub(DeluxM800Pro.DEVICE_ID, "Delux M800 Pro", DeviceType.MOUSE, "2.4 GHz"));
            deviceManager.registerDevice(realDisconnectedStub(AkkoVergeS9.DEVICE_ID, "Akko S9 Ultra", DeviceType.HEADSET, "2.4 GHz"));
            deviceManager.registerDevice(realDisconnectedStub(GameSirCyclone2.DEVICE_ID, "GameSir Cyclone 2", DeviceType.GAMEPAD, "2.4 GHz / USB"));
            deviceManager.registerDevice(new QcyT13Bluetooth(
                    () -> new BluetoothDeviceScanner.ScanResult(false, List.of(), "Sem adaptador"),
                    QcyT13Config.defaultReference(),
                    logger
            ));

            HidDiagnosticsService diag = new HidDiagnosticsService(
                    hid,
                    DeluxM800ProConfig.defaultReference(),
                    AkkoVergeS9Config.defaultReference(),
                    GameSirCyclone2Config.defaultReference(),
                    logger
            );
            AtomicReference<MainWindow> winRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> winRef.set(new MainWindow(
                    settings, theme, deviceManager, simulationManager, diag, logger, selector, () -> {}
            )));
            MainWindow window = winRef.get();
            try {
                // 1. Somente Akko conectado
                applyAndWaitEdt(simulationManager, SimulationScenario.ONLY_AKKO_CONNECTED);
                assertSyncedThreeSlots(window, "Akko Verge S9 Ultra", 62, "2.4 GHz", ConnectionStatus.CONNECTED);

                // 2. Somente QCY conectado
                applyAndWaitEdt(simulationManager, SimulationScenario.ONLY_QCY_CONNECTED);
                assertSyncedThreeSlots(window, "QCY T13", 74, "Bluetooth", ConnectionStatus.CONNECTED);

                // 3. Ambos conectados, Akko saída padrão
                applyAndWaitEdt(simulationManager, SimulationScenario.BOTH_AKKO_DEFAULT_AUDIO);
                assertSyncedThreeSlots(window, "Akko Verge S9 Ultra", 62, "2.4 GHz", ConnectionStatus.CONNECTED);

                // 4. Ambos conectados, QCY saída padrão
                applyAndWaitEdt(simulationManager, SimulationScenario.BOTH_QCY_DEFAULT_AUDIO);
                assertSyncedThreeSlots(window, "QCY T13", 74, "Bluetooth", ConnectionStatus.CONNECTED);

                // 5. Nenhum fone conectado
                applyAndWaitEdt(simulationManager, SimulationScenario.NO_HEADSET_CONNECTED);
                DeviceReading none = assertSyncedThreeSlots(window, "Fone", null, "Sem conexão", ConnectionStatus.DISCONNECTED);
                assertNull(none.lastKnownPercent(), "Não deve reutilizar percentual anterior no slot genérico Fone");

                // 6. Dongle Akko presente, headset desligado
                applyAndWaitEdt(simulationManager, SimulationScenario.AKKO_DONGLE_PRESENT_HEADSET_OFF);
                DeviceReading dongleOnly = assertSyncedThreeSlots(window, "Fone", null, "Sem conexão", ConnectionStatus.DISCONNECTED);
                assertNull(dongleOnly.lastKnownPercent());
                assertTrue(dongleOnly.detailMessage().contains("Akko"));

                // 7. QCY conectado com bateria indisponível
                applyAndWaitEdt(simulationManager, SimulationScenario.QCY_CONNECTED_BATTERY_UNAVAILABLE);
                DeviceReading qcyNoBatt = assertSyncedThreeSlots(window, "QCY T13", null, "Bluetooth", ConnectionStatus.BATTERY_UNAVAILABLE);
                assertNull(qcyNoBatt.lastKnownPercent());

                // 8. Troca automática Akko -> QCY
                applyAndWaitEdt(simulationManager, SimulationScenario.SWITCH_AKKO_TO_QCY);
                DeviceReading swQcy = assertSyncedThreeSlots(window, "QCY T13", 74, "Bluetooth", ConnectionStatus.CONNECTED);
                assertEquals(74, swQcy.lastKnownPercent());

                // 9. Troca automática QCY -> Akko
                applyAndWaitEdt(simulationManager, SimulationScenario.SWITCH_QCY_TO_AKKO);
                DeviceReading swAkko = assertSyncedThreeSlots(window, "Akko Verge S9 Ultra", 62, "2.4 GHz", ConnectionStatus.CONNECTED);
                assertEquals(62, swAkko.lastKnownPercent());

                // 10. Falha temporária de leitura (estabilização mantém o fone selecionado)
                applyAndWaitEdt(simulationManager, SimulationScenario.TEMPORARY_READ_FAILURE);
                DeviceReading held = assertSyncedThreeSlots(window, "Akko Verge S9 Ultra", null, "2.4 GHz", ConnectionStatus.TIMEOUT);
                assertEquals(62, held.lastKnownPercent(), "Falha temporária deve preservar apenas a última leitura do próprio Akko");

                // 11. Desconexão e reconexão Bluetooth
                applyAndWaitEdt(simulationManager, SimulationScenario.BLUETOOTH_DISCONNECT_AND_RECONNECT);
                assertSyncedThreeSlots(window, "QCY T13", 74, "Bluetooth", ConnectionStatus.CONNECTED);

                // 12. Adaptador Bluetooth ausente ou desativado
                applyAndWaitEdt(simulationManager, SimulationScenario.BLUETOOTH_ADAPTER_MISSING);
                DeviceReading noBt = assertSyncedThreeSlots(window, "Fone", null, "Sem conexão", ConnectionStatus.DISCONNECTED);
                assertNull(noBt.lastKnownPercent());
                assertTrue(noBt.detailMessage().contains("Bluetooth"));

                // Verifica que DeviceManager real permaneceu completamente isolado
                assertTrue(deviceManager.getCurrentReadings().stream().noneMatch(DeviceReading::isSimulated));

                // Verifica instanciação segura do SimulationControlPanel com os 12 cenários
                SwingUtilities.invokeAndWait(() -> {
                    SimulationControlPanel panel = new SimulationControlPanel(simulationManager);
                    assertNotNull(panel);
                });
            } finally {
                SwingUtilities.invokeAndWait(window::releaseUiResourcesForShutdown);
            }
        }
    }

    @Test
    void realModeAndSimulatedModeShareIdenticalSelectionAndRemainIsolated(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requer ambiente gráfico");

        AppLogger logger = new AppLogger();
        AppSettings settings = new AppSettings(tempDir.resolve("settings-real.properties"));
        settings.setSimulationEnabled(false);

        ThemeManager theme = new ThemeManager();
        theme.installInitialTheme(ThemeManager.Theme.DARK);

        AtomicReference<BluetoothDeviceScanner.ScanResult> btScanRef = new AtomicReference<>(
                new BluetoothDeviceScanner.ScanResult(true, List.of(
                        new BluetoothDeviceInfo("{QCY-CONT-1}", "QCY T13", true, 81, false, "BTHENUM\\DEV_1")
                ), "OK")
        );
        AtomicReference<AudioEndpointInfo> audioRef = new AtomicReference<>(
                new AudioEndpointInfo("ep-qcy", "Fones de ouvido (QCY T13)", "QCY T13 Stereo", "{QCY-CONT-1}")
        );
        AtomicReference<Boolean> akkoOnlineRef = new AtomicReference<>(true);

        CategoryDeviceSelector selector = new CategoryDeviceSelector(() -> Optional.ofNullable(audioRef.get()), logger);
        SimulationManager simulationManager = new SimulationManager(logger);

        try (HidServiceProvider hid = new HidServiceProvider(logger);
             DeviceManager deviceManager = new DeviceManager(hid, logger)) {

            deviceManager.registerDevice(realConnectedStub(DeluxM800Pro.DEVICE_ID, "Delux M800 Pro", DeviceType.MOUSE, 88, "2.4 GHz"));
            deviceManager.registerDevice(new BatteryDevice() {
                @Override public String getId() { return AkkoVergeS9.DEVICE_ID; }
                @Override public String getDisplayName() { return AkkoVergeS9.DISPLAY_NAME; }
                @Override public DeviceType getDeviceType() { return DeviceType.HEADSET; }
                @Override public String getConnectionDescription() { return "2.4 GHz"; }
                @Override public DataSourceType getSourceType() { return DataSourceType.REAL; }
                @Override public DeviceReading readBattery() {
                    return akkoOnlineRef.get()
                            ? DeviceReading.realConnected(getId(), getDisplayName(), DeviceType.HEADSET, 55, false, "2.4 GHz", "OK")
                            : DeviceReading.realAsleep(getId(), getDisplayName(), DeviceType.HEADSET, "2.4 GHz", "Dongle presente");
                }
            });
            deviceManager.registerDevice(realConnectedStub(GameSirCyclone2.DEVICE_ID, "GameSir Cyclone 2", DeviceType.GAMEPAD, 92, "2.4 GHz / USB"));
            deviceManager.registerDevice(new QcyT13Bluetooth(btScanRef::get, QcyT13Config.defaultReference(), logger));

            HidDiagnosticsService diag = new HidDiagnosticsService(
                    hid,
                    DeluxM800ProConfig.defaultReference(),
                    AkkoVergeS9Config.defaultReference(),
                    GameSirCyclone2Config.defaultReference(),
                    logger
            );
            AtomicReference<MainWindow> winRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> winRef.set(new MainWindow(
                    settings, theme, deviceManager, simulationManager, diag, logger, selector, () -> {}
            )));
            MainWindow window = winRef.get();
            try {
                deviceManager.pollOnce();
                SwingUtilities.invokeAndWait(() -> {});
                // Ambos conectados em modo REAL com QCY como saída padrão -> seleciona QCY T13 (81%)
                DeviceReading realQcy = assertSyncedThreeSlots(window, "QCY T13", 81, "Bluetooth", ConnectionStatus.CONNECTED);
                assertFalse(realQcy.isSimulated());

                // Troca saída padrão de áudio do Windows para Akko -> seleciona Akko Verge S9 Ultra (55%)
                audioRef.set(new AudioEndpointInfo("ep-akko", "Alto-falantes (Akko Verge S9 Ultra)", "Akko S9 Ultra", null));
                deviceManager.pollOnce();
                SwingUtilities.invokeAndWait(() -> {});
                DeviceReading realAkko = assertSyncedThreeSlots(window, "Akko Verge S9 Ultra", 55, "2.4 GHz", ConnectionStatus.CONNECTED);
                assertFalse(realAkko.isSimulated());

                // Akko entra em repouso (dongle presente, fone desligado) e QCY permanece no Bluetooth -> volta para QCY T13 (81%)
                akkoOnlineRef.set(false);
                deviceManager.pollOnce();
                SwingUtilities.invokeAndWait(() -> {});
                assertSyncedThreeSlots(window, "QCY T13", 81, "Bluetooth", ConnectionStatus.CONNECTED);

                // QCY desconecta no Bluetooth com dongle Akko ainda presente -> card genérico "Fone" sem vazar 81% nem 55%
                btScanRef.set(new BluetoothDeviceScanner.ScanResult(true, List.of(), "Desconectado"));
                deviceManager.pollOnce();
                SwingUtilities.invokeAndWait(() -> {});
                DeviceReading realNone = assertSyncedThreeSlots(window, "Fone", null, "Sem conexão", ConnectionStatus.DISCONNECTED);
                assertNull(realNone.lastKnownPercent());
            } finally {
                SwingUtilities.invokeAndWait(window::releaseUiResourcesForShutdown);
            }
        }
    }

    private static void applyAndWaitEdt(SimulationManager sim, SimulationScenario scenario) throws Exception {
        SwingUtilities.invokeAndWait(() -> sim.applyScenario(scenario));
        SwingUtilities.invokeAndWait(() -> {});
    }

    private static DeviceReading assertSyncedThreeSlots(
            MainWindow window,
            String expectedHeadsetName,
            Integer expectedPercent,
            String expectedConnection,
            ConnectionStatus expectedStatus
    ) {
        List<DeviceReading> windowReadings = window.getLatestSelectedReadings();
        List<DeviceReading> widgetReadings = window.getTaskbarWidgetReadingsSnapshot();

        assertEquals(3, windowReadings.size(), "Janela principal deve manter exatamente 3 cards (Mouse, Fone, Controle)");
        assertEquals(windowReadings, widgetReadings, "Widget da barra de tarefas deve exibir exatamente a mesma seleção da janela principal");

        assertEquals(DeviceType.MOUSE, windowReadings.get(0).deviceType());
        assertEquals(DeviceType.HEADSET, windowReadings.get(1).deviceType());
        assertEquals(DeviceType.GAMEPAD, windowReadings.get(2).deviceType());

        DeviceReading headset = windowReadings.get(1);
        assertEquals(expectedHeadsetName, headset.displayName());
        assertEquals(expectedPercent, headset.batteryPercent());
        assertEquals(expectedConnection, headset.connectionLabel());
        assertEquals(expectedStatus, headset.status());
        return headset;
    }

    private static BatteryDevice realDisconnectedStub(String id, String name, DeviceType type, String via) {
        return new BatteryDevice() {
            @Override public String getId() { return id; }
            @Override public String getDisplayName() { return name; }
            @Override public DeviceType getDeviceType() { return type; }
            @Override public String getConnectionDescription() { return via; }
            @Override public DataSourceType getSourceType() { return DataSourceType.REAL; }
            @Override public DeviceReading readBattery() {
                return DeviceReading.realDisconnected(id, name, type, via, "Desconectado");
            }
        };
    }

    private static BatteryDevice realConnectedStub(String id, String name, DeviceType type, int pct, String via) {
        return new BatteryDevice() {
            @Override public String getId() { return id; }
            @Override public String getDisplayName() { return name; }
            @Override public DeviceType getDeviceType() { return type; }
            @Override public String getConnectionDescription() { return via; }
            @Override public DataSourceType getSourceType() { return DataSourceType.REAL; }
            @Override public DeviceReading readBattery() {
                return DeviceReading.realConnected(id, name, type, pct, false, via, "OK");
            }
        };
    }
}
