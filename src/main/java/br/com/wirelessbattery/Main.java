package br.com.wirelessbattery;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.device.AkkoVergeS9;
import br.com.wirelessbattery.device.AkkoVergeS9Config;
import br.com.wirelessbattery.device.DeluxM800Pro;
import br.com.wirelessbattery.device.DeluxM800ProConfig;
import br.com.wirelessbattery.device.DeviceDriverRegistry;
import br.com.wirelessbattery.device.DeviceManager;
import br.com.wirelessbattery.device.GameSirCyclone2;
import br.com.wirelessbattery.device.GameSirCyclone2Config;
import br.com.wirelessbattery.device.HidServiceProvider;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.diagnostics.HidDiagnosticsService;
import br.com.wirelessbattery.simulation.SimulationManager;
import br.com.wirelessbattery.ui.MainWindow;
import br.com.wirelessbattery.ui.ThemeManager;
import java.util.Arrays;
import javax.swing.SwingUtilities;

/**
 * Ponto de entrada principal do Wireless Battery Monitor (Java 17 + Swing + FlatLaf).
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        boolean argMinimized = Arrays.stream(args).anyMatch(a -> "--minimized".equalsIgnoreCase(a));

        AppSettings settings = new AppSettings();
        settings.load();

        ThemeManager themeManager = new ThemeManager();
        themeManager.installInitialTheme(
                settings.isDarkTheme() ? ThemeManager.Theme.DARK : ThemeManager.Theme.LIGHT
        );

        SwingUtilities.invokeLater(() -> {
            AppLogger logger = new AppLogger();
            HidServiceProvider hidProvider = new HidServiceProvider(logger);
            DeviceManager deviceManager = new DeviceManager(hidProvider, logger);
            SimulationManager simulationManager = new SimulationManager(logger);

            DeluxM800ProConfig m800Config = DeluxM800ProConfig.loadFromEnvironment();
            AkkoVergeS9Config akkoConfig = AkkoVergeS9Config.loadFromEnvironment();
            GameSirCyclone2Config cyclone2Config = GameSirCyclone2Config.loadFromEnvironment();
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

            // 2. Headset: Akko Verge S9
            deviceManager.registerDriver(new DeviceDriverRegistry.DriverRegistration(
                    AkkoVergeS9.DEVICE_ID,
                    AkkoVergeS9.DISPLAY_NAME,
                    2,
                    false,
                    false,
                    -1,
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

            logger.info("Drivers registrados: Delux M800 Pro, Akko Verge S9 e GameSir Cyclone 2.");

            MainWindow window = new MainWindow(
                    settings,
                    themeManager,
                    deviceManager,
                    simulationManager,
                    diagnosticsService,
                    logger,
                    () -> {
                        deviceManager.close();
                        hidProvider.close();
                        System.exit(0);
                    }
            );

            boolean startMinimized = argMinimized || settings.isStartMinimized();
            if (!startMinimized) {
                window.setVisible(true);
            } else {
                logger.info("Iniciado minimizado conforme preferência do usuário.");
            }
            deviceManager.startPolling(settings.getPollIntervalSeconds());
        });
    }
}
