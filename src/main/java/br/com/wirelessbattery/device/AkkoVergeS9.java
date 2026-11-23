package br.com.wirelessbattery.device;

import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.util.List;
import java.util.Objects;
import org.hid4java.HidDevice;

/**
 * Estrutura preparada para o headset Akko Verge S9 via receptor USB 2.4 GHz.
 *
 * Segurança: esta classe NUNCA envia comandos HID desconhecidos ao headset.
 * Enquanto o protocolo de bateria não for identificado, realiza apenas verificação
 * passiva de presença e inspeção de coleções HID padrão do Windows (Usage Page 0x84/0x85/0x06)
 * quando VID/PID estiverem configurados.
 */
public final class AkkoVergeS9 implements BatteryDevice {
    public static final String DEVICE_ID = "akko-verge-s9";
    public static final String DISPLAY_NAME = "Akko Verge S9";
    public static final String CONNECTION = "2.4 GHz";

    private final HidServiceProvider hidProvider;
    private final AkkoVergeS9Config config;
    private final AppLogger logger;

    public AkkoVergeS9(HidServiceProvider hidProvider, AkkoVergeS9Config config, AppLogger logger) {
        this.hidProvider = Objects.requireNonNull(hidProvider, "hidProvider não pode ser nulo");
        this.config = Objects.requireNonNull(config, "config não pode ser nulo");
        this.logger = Objects.requireNonNull(logger, "logger não pode ser nulo");
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
    public boolean supportsBatteryQuery() {
        return false;
    }

    @Override
    public boolean matchesVidPid(int vid, int pid) {
        return config.matches(vid & 0xFFFF, pid & 0xFFFF);
    }

    @Override
    public DeviceReading readBattery() {
        if (!config.isConfigured()) {
            return readBattery(List.of());
        }
        try {
            return readBattery(hidProvider.getServices().getAttachedHidDevices());
        } catch (Exception e) {
            logger.error("Erro ao verificar presença passiva do Akko Verge S9", e);
            return DeviceReading.realDisconnected(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.HEADSET,
                    CONNECTION,
                    "Não foi possível enumerar dispositivos HID."
            );
        }
    }

    @Override
    public DeviceReading readBattery(List<HidDevice> attachedDevices) {
        if (!config.isConfigured()) {
            return DeviceReading.realPendingProtocol(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.HEADSET,
                    CONNECTION,
                    "Protocolo real pendente. Use o Diagnóstico HID para identificar o receptor 2.4 GHz."
            );
        }

        boolean receiverPresent = false;
        boolean hasStandardBatteryPage = false;
        boolean hasVendorPage = false;

        if (attachedDevices != null) {
            for (HidDevice device : attachedDevices) {
                int vid = device.getVendorId() & 0xFFFF;
                int pid = device.getProductId() & 0xFFFF;
                if (config.matches(vid, pid)) {
                    receiverPresent = true;
                    int page = device.getUsagePage() & 0xFFFF;
                    int usage = device.getUsage() & 0xFFFF;
                    if (page == 0x84 || page == 0x85 || (page == 0x06 && usage == 0x20)) {
                        hasStandardBatteryPage = true;
                    } else if (page >= 0xFF00) {
                        hasVendorPage = true;
                    }
                }
            }
        }

        if (receiverPresent) {
            String extra = hasStandardBatteryPage
                    ? " (expõe Usage Page padrão de bateria HID)"
                    : (hasVendorPage ? " (expõe coleção HID proprietária)" : "");
            return DeviceReading.realPendingProtocol(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.HEADSET,
                    CONNECTION,
                    String.format(
                            "Receptor 2.4 GHz detectado (0x%04X:0x%04X)%s — protocolo de leitura pendente.",
                            config.getVendorId(),
                            config.getProductId(),
                            extra
                    )
            );
        }

        return DeviceReading.realDisconnected(
                DEVICE_ID,
                DISPLAY_NAME,
                DeviceType.HEADSET,
                CONNECTION,
                "Receptor 2.4 GHz desconectado."
        );
    }
}
