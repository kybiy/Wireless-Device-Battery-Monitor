package br.com.wirelessbattery.device;

import br.com.wirelessbattery.bluetooth.BluetoothDeviceInfo;
import br.com.wirelessbattery.bluetooth.BluetoothDeviceScanner;
import br.com.wirelessbattery.bluetooth.WindowsBluetoothScanner;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.util.List;
import java.util.Objects;

/**
 * Driver Bluetooth somente-leitura para o headset QCY T13 no Windows.
 *
 * <p>Consulta passivamente as propriedades de bateria e conexão expostas pelo gerenciador de
 * dispositivos do Windows via {@link BluetoothDeviceScanner}, preservando áudio A2DP/HFP intacto
 * e permitindo correlação de saída de áudio via {@link #getLastMatchedContainerId()}.
 */
public final class QcyT13Bluetooth implements BatteryDevice {

    public static final String DEVICE_ID = "qcy-t13";
    public static final String DISPLAY_NAME = "QCY T13";
    public static final String CONNECTION = "Bluetooth";

    private static final DriverCapabilities CAPABILITIES = new DriverCapabilities(
            TransportType.BLUETOOTH_PNP,
            false,
            false,
            10,
            ProtocolSafetyInfo.passiveOs(
                    "Leitura passiva via propriedades SetupAPI/PnP do Windows (DEVPKEY_Bluetooth_Battery / PKEY_Devices_BatteryLife), sem conexões RFCOMM/GATT.",
                    "https://github.com/joaoarebalo/Wireless-Device-Battery-Monitor-Fallen"
            )
    );

    private final BluetoothDeviceScanner scanner;
    private final QcyT13Config config;
    private final AppLogger logger;

    private volatile String lastMatchedContainerId;
    private volatile String lastLogStateKey;

    public QcyT13Bluetooth(QcyT13Config config, AppLogger logger) {
        this(new WindowsBluetoothScanner(), config, logger);
    }

    public QcyT13Bluetooth(BluetoothDeviceScanner scanner, QcyT13Config config, AppLogger logger) {
        this.scanner = Objects.requireNonNull(scanner, "scanner não pode ser nulo");
        this.config = Objects.requireNonNull(config, "config não pode ser nulo");
        this.logger = Objects.requireNonNull(logger, "logger não pode ser nulo");
    }

    public QcyT13Config getConfig() {
        return config;
    }

    public String getLastMatchedContainerId() {
        return lastMatchedContainerId;
    }

    @Override
    public String getId() {
        return DEVICE_ID;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public DeviceType getDeviceType() {
        return DeviceType.HEADSET;
    }

    @Override
    public String getConnectionDescription() {
        return CONNECTION;
    }

    @Override
    public DataSourceType getSourceType() {
        return DataSourceType.REAL;
    }

    @Override
    public boolean isOptional() {
        return false;
    }

    @Override
    public boolean supportsBatteryQuery() {
        return true;
    }

    @Override
    public DriverCapabilities getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public DeviceReading readBattery() {
        BluetoothDeviceScanner.ScanResult scanResult;
        try {
            scanResult = scanner.scan();
        } catch (Exception e) {
            lastMatchedContainerId = null;
            if (!"SCAN_ERROR".equals(lastLogStateKey)) {
                lastLogStateKey = "SCAN_ERROR";
                logger.error("Erro ao consultar dispositivos Bluetooth para QCY T13", e);
            }
            return DeviceReading.realDisconnected(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.HEADSET,
                    CONNECTION,
                    "Falha na varredura Bluetooth do Windows."
            );
        }

        if (scanResult == null || !scanResult.adapterAvailable()) {
            lastMatchedContainerId = null;
            logTransitionIfChanged(
                    "ADAPTER_UNAVAILABLE",
                    "[BLUETOOTH] QCY T13: Adaptador Bluetooth ausente ou desativado."
            );
            return DeviceReading.realDisconnected(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.HEADSET,
                    CONNECTION,
                    "Adaptador Bluetooth ausente ou desativado."
            );
        }

        BluetoothDeviceInfo matched = findBestMatch(scanResult.devices());
        if (matched == null || !matched.connected()) {
            lastMatchedContainerId = null;
            logTransitionIfChanged(
                    "DISCONNECTED",
                    "[BLUETOOTH] QCY T13 desconectado no Bluetooth."
            );
            return DeviceReading.realDisconnected(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.HEADSET,
                    CONNECTION,
                    "QCY T13 desconectado no Bluetooth."
            );
        }

        lastMatchedContainerId = matched.containerId();
        Integer battery = matched.batteryPercent();
        if (battery == null || battery < 0 || battery > 100) {
            logTransitionIfChanged(
                    "BATTERY_UNAVAILABLE:" + matched.containerId(),
                    "[BLUETOOTH] QCY T13 conectado via Bluetooth (" + matched.friendlyName()
                            + "), sem nível de bateria exposto pelo Windows."
            );
            return DeviceReading.realBatteryUnavailable(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.HEADSET,
                    CONNECTION,
                    "QCY T13 conectado via Bluetooth (" + matched.friendlyName()
                            + "), mas o Windows não expôs nível de bateria."
            );
        }

        logTransitionIfChanged(
                "CONNECTED:" + matched.containerId(),
                "[BLUETOOTH] QCY T13 conectado via Bluetooth (" + matched.friendlyName()
                        + ", " + battery + "%)."
        );
        return DeviceReading.realConnected(
                DEVICE_ID,
                DISPLAY_NAME,
                DeviceType.HEADSET,
                battery,
                false,
                CONNECTION,
                "Leitura via Windows Bluetooth (" + matched.friendlyName() + ")."
        );
    }

    private BluetoothDeviceInfo findBestMatch(List<BluetoothDeviceInfo> devices) {
        if (devices == null || devices.isEmpty()) {
            return null;
        }
        BluetoothDeviceInfo bestMatch = null;
        for (BluetoothDeviceInfo info : devices) {
            if (info == null || !config.matchesDeviceName(info.friendlyName())) {
                continue;
            }
            if (info.connected()) {
                if (info.batteryPercent() != null) {
                    return info;
                }
                if (bestMatch == null || !bestMatch.connected()) {
                    bestMatch = info;
                }
            } else if (bestMatch == null) {
                bestMatch = info;
            }
        }
        return bestMatch;
    }

    private void logTransitionIfChanged(String stateKey, String message) {
        if (!stateKey.equals(lastLogStateKey)) {
            lastLogStateKey = stateKey;
            logger.info(message);
        }
    }
}
