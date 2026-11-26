package br.com.wirelessbattery;

import br.com.wirelessbattery.audio.AudioEndpointInfo;
import br.com.wirelessbattery.audio.WindowsAudioOutputInspector;
import br.com.wirelessbattery.bluetooth.WindowsBluetoothScanner;
import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.config.AppVersion;
import br.com.wirelessbattery.config.SingleInstanceGuard;
import br.com.wirelessbattery.config.WindowsStartupManager;
import br.com.wirelessbattery.device.AkkoVergeS9;
import br.com.wirelessbattery.device.AkkoVergeS9Config;
import br.com.wirelessbattery.device.DeluxM800Pro;
import br.com.wirelessbattery.device.DeluxM800ProConfig;
import br.com.wirelessbattery.device.DeviceDriverRegistry;
import br.com.wirelessbattery.device.DeviceManager;
import br.com.wirelessbattery.device.GameSirCyclone2;
import br.com.wirelessbattery.device.GameSirCyclone2Config;
import br.com.wirelessbattery.device.HidServiceProvider;
import br.com.wirelessbattery.device.QcyT13Bluetooth;
import br.com.wirelessbattery.device.QcyT13Config;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.diagnostics.HidDiagnosticsService;
import br.com.wirelessbattery.history.BatteryHistoryStore;
import br.com.wirelessbattery.i18n.I18n;
import br.com.wirelessbattery.notification.BatteryAlertManager;
import br.com.wirelessbattery.selection.CategoryDeviceSelector;
import br.com.wirelessbattery.simulation.SimulationManager;
import br.com.wirelessbattery.ui.MainWindow;
import br.com.wirelessbattery.ui.ThemeManager;
import br.com.wirelessbattery.update.UpdateCheckerService;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/**
 * Ponto de entrada principal do Wireless Battery Monitor (Java 17 + Swing + FlatLaf).
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        boolean argShutdown = Arrays.stream(args).anyMatch(a -> "--shutdown".equalsIgnoreCase(a));
        boolean argMinimized = Arrays.stream(args).anyMatch(a -> "--minimized".equalsIgnoreCase(a));

        SingleInstanceGuard instanceGuard = new SingleInstanceGuard();
        if (argShutdown) {
            boolean closed = instanceGuard.requestRunningInstanceShutdown(6000L);
            AppLogger shutdownLogger = new AppLogger();
            shutdownLogger.info("[STARTUP] Solicitação --shutdown processada (encerrado=" + closed + ").");
            System.exit(closed ? 0 : 1);
            return;
        }

        if (!instanceGuard.tryAcquireOrSignalExisting(!argMinimized)) {
            AppLogger duplicateLogger = new AppLogger();
            duplicateLogger.info("[STARTUP] Inicialização ignorada: " + instanceGuard.getLastDiagnosticMessage());
            System.exit(0);
            return;
        }

        AppSettings settings = new AppSettings();
        settings.load();

        ThemeManager themeManager = new ThemeManager();
        themeManager.installInitialTheme(
                settings.isDarkTheme() ? ThemeManager.Theme.DARK : ThemeManager.Theme.LIGHT
        );

        SwingUtilities.invokeLater(() -> {
            AppLogger logger = new AppLogger();
            logger.info("Iniciando Wireless Device Battery Monitor | " + AppVersion.formatStartupInfo());
            logger.info("[STARTUP] " + instanceGuard.getLastDiagnosticMessage());
            WindowsStartupManager.reconcileStartupSetting(settings, logger);

            HidServiceProvider hidProvider = new HidServiceProvider(logger);
            DeviceManager deviceManager = new DeviceManager(hidProvider, settings, logger);
            SimulationManager simulationManager = new SimulationManager(logger);
            I18n i18n = new I18n(settings.getLanguage());
            BatteryHistoryStore historyStore = new BatteryHistoryStore(settings, logger);
            UpdateCheckerService updateService = new UpdateCheckerService();
            BatteryAlertManager alertManager = new BatteryAlertManager();

            DeluxM800ProConfig m800Config = DeluxM800ProConfig.loadFromEnvironment();
            AkkoVergeS9Config akkoConfig = AkkoVergeS9Config.loadFromEnvironment();
            GameSirCyclone2Config cyclone2Config = GameSirCyclone2Config.loadFromEnvironment();
            QcyT13Config qcyConfig = QcyT13Config.loadFromEnvironment();
            WindowsBluetoothScanner bluetoothScanner = new WindowsBluetoothScanner();
            WindowsAudioOutputInspector audioOutputInspector = new WindowsAudioOutputInspector();
            AtomicReference<Optional<AudioEndpointInfo>> cachedAudioOutput = new AtomicReference<>(Optional.empty());
            Runnable refreshAudioCache = () -> cachedAudioOutput.set(audioOutputInspector.getDefaultRenderEndpoint());
            deviceManager.setPrePollHook(refreshAudioCache);
            CategoryDeviceSelector categorySelector = new CategoryDeviceSelector(cachedAudioOutput::get, logger);

            HidDiagnosticsService diagnosticsService = new HidDiagnosticsService(
                    hidProvider, m800Config, akkoConfig, cyclone2Config, logger
            );

            // 1. Mouse: Delux M800 Pro PAW3395
            deviceManager.registerDriver(new DeviceDriverRegistry.DriverRegistration(
                    DeluxM800Pro.DEVICE_ID,
                    DeluxM800Pro.DISPLAY_NAME,
                    1,
                    false,
                    true,
                    m800Config.getInterfaceNumber(),
                    m800Config::matches,
                    () -> new DeluxM800Pro(hidProvider, m800Config, logger)
            ));

            // 2. Headset (2.4 GHz): Akko S9 Ultra / Verge S9
            deviceManager.registerDriver(new DeviceDriverRegistry.DriverRegistration(
                    AkkoVergeS9.DEVICE_ID,
                    AkkoVergeS9.DISPLAY_NAME,
                    2,
                    false,
                    true,
                    akkoConfig.getInterfaceNumber(),
                    akkoConfig::matches,
                    () -> new AkkoVergeS9(hidProvider, akkoConfig, logger)
            ));

            // 3. Controle: GameSir Cyclone 2
            deviceManager.registerDriver(new DeviceDriverRegistry.DriverRegistration(
                    GameSirCyclone2.DEVICE_ID,
                    GameSirCyclone2.DISPLAY_NAME,
                    3,
                    false,
                    true,
                    -1,
                    cyclone2Config::matchesAny,
                    () -> new GameSirCyclone2(hidProvider, cyclone2Config, logger)
            ));

            // 4. Headset (Bluetooth): QCY T13
            deviceManager.registerDriver(new DeviceDriverRegistry.DriverRegistration(
                    QcyT13Bluetooth.DEVICE_ID,
                    QcyT13Bluetooth.DISPLAY_NAME,
                    4,
                    false,
                    true,
                    -1,
                    (vid, pid) -> false,
                    () -> new QcyT13Bluetooth(bluetoothScanner, qcyConfig, logger)
            ));

            logger.info("Drivers registrados: Delux M800 Pro, Akko S9 Ultra, GameSir Cyclone 2 e QCY T13 (Bluetooth).");

            AtomicBoolean servicesClosed = new AtomicBoolean(false);
            AtomicReference<MainWindow> windowRef = new AtomicReference<>();
            Runnable closeServicesOnce = () -> {
                if (!servicesClosed.compareAndSet(false, true)) {
                    return;
                }
                MainWindow win = windowRef.get();
                if (win != null) {
                    try {
                        if (SwingUtilities.isEventDispatchThread()) {
                            win.releaseUiResourcesForShutdown();
                        } else {
                            SwingUtilities.invokeAndWait(win::releaseUiResourcesForShutdown);
                        }
                    } catch (Exception ignored) {
                        // Encerramento em melhor esforço durante hook da JVM
                    }
                }
                deviceManager.close();
                historyStore.close();
                hidProvider.close();
                instanceGuard.close();
            };

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("[SHUTDOWN] Encerrando recursos com segurança (hook do sistema/atualizador).");
                closeServicesOnce.run();
            }, "wireless-battery-shutdown-hook"));

            MainWindow window = new MainWindow(
                    settings,
                    themeManager,
                    deviceManager,
                    simulationManager,
                    diagnosticsService,
                    logger,
                    categorySelector,
                    i18n,
                    historyStore,
                    updateService,
                    alertManager,
                    refreshAudioCache,
                    () -> {
                        closeServicesOnce.run();
                        System.exit(0);
                    }
            );
            windowRef.set(window);
            instanceGuard.startLifecycleListeners(
                    window::bringToFront,
                    () -> window.requestGracefulExit("Encerramento seguro solicitado por atualizador/instalador.")
            );
            deviceManager.startPolling(settings.getPollIntervalSeconds());

            if (settings.isAutoCheckUpdates()) {
                CompletableFuture.runAsync(() -> updateService.checkIfDue(settings).ifPresent(res -> {
                    try {
                        settings.save();
                    } catch (Exception ignored) {
                        // Cache de atualização opcional
                    }
                }));
            }

            boolean startMinimized = argMinimized || settings.isStartMinimized();
            if (!startMinimized) {
                window.setVisible(true);
            } else {
                window.startMinimizedSession(argMinimized);
            }
        });
    }
}
