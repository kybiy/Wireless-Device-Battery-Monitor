package br.com.wirelessbattery.device;

import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hid4java.HidDevice;

/**
 * Driver para o mouse Delux M800 Pro PAW3395 via hid4java no Windows.
 *
 * Implementação adaptada da consulta de bateria de {@code xb-bx/m800-pro-driver}
 * usando apenas HID Feature Reports (somente leitura). Ainda requer validação em
 * hardware real no Windows antes de ser considerada confirmada.
 */
public final class DeluxM800Pro implements BatteryDevice {
    public static final String DEVICE_ID = "delux-m800-pro";
    public static final String DISPLAY_NAME = "Delux M800 Pro";
    public static final String CONNECTION = "2.4 GHz";

    private final HidServiceProvider hidProvider;
    private final DeluxM800ProConfig config;
    private final AppLogger logger;

    public DeluxM800Pro(HidServiceProvider hidProvider, DeluxM800ProConfig config, AppLogger logger) {
        this.hidProvider = Objects.requireNonNull(hidProvider, "hidProvider não pode ser nulo");
        this.config = Objects.requireNonNull(config, "config não pode ser nulo");
        this.logger = Objects.requireNonNull(logger, "logger não pode ser nulo");
    }

    public DeluxM800ProConfig getConfig() {
        return config;
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
        return DeviceType.MOUSE;
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
    public int getExpectedInterface() {
        return config.getInterfaceNumber();
    }

    @Override
    public boolean matchesVidPid(int vid, int pid) {
        return config.matches(vid & 0xFFFF, pid & 0xFFFF);
    }

    @Override
    public DeviceReading readBattery() {
        try {
            return readBattery(hidProvider.getServices().getAttachedHidDevices());
        } catch (Exception e) {
            logger.error("Falha ao enumerar HID para Delux M800 Pro", e);
            return DeviceReading.realDisconnected(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.MOUSE,
                    CONNECTION,
                    "Não foi possível consultar o receptor USB."
            );
        }
    }

    @Override
    public DeviceReading readBattery(List<HidDevice> attachedDevices) {
        try {
            List<HidDevice> candidates = findCandidateInterfaces(attachedDevices);
            if (candidates.isEmpty()) {
                return DeviceReading.realDisconnected(
                        DEVICE_ID,
                        DISPLAY_NAME,
                        DeviceType.MOUSE,
                        CONNECTION,
                        "Receptor 2.4 GHz não detectado."
                );
            }

            for (HidDevice candidate : candidates) {
                DeluxM800ProProtocol.ParsedBattery parsed = queryDevice(candidate);
                if (parsed != null) {
                    boolean wired = (candidate.getProductId() & 0xFFFF) == config.getWiredProductId();
                    String connLabel = wired ? "USB Cabo" : CONNECTION;
                    String detail = "Leitura via Feature Report 0x0C (aguardando confirmação em hardware real).";
                    return DeviceReading.realConnected(
                            DEVICE_ID,
                            DISPLAY_NAME,
                            DeviceType.MOUSE,
                            parsed.percent(),
                            parsed.charging(),
                            connLabel,
                            detail
                    );
                }
            }

            return DeviceReading.realAsleep(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.MOUSE,
                    CONNECTION,
                    "Receptor detectado; mouse em repouso ou interface HID reservada pelo Windows."
            );
        } catch (Exception e) {
            logger.error("Falha ao consultar Delux M800 Pro", e);
            return DeviceReading.realDisconnected(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.MOUSE,
                    CONNECTION,
                    "Falha HID ao consultar M800 Pro: " + e.getMessage()
            );
        }
    }

    private List<HidDevice> findCandidateInterfaces(List<HidDevice> all) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<HidDevice> exact = new ArrayList<>();
        List<HidDevice> fallback = new ArrayList<>();
        for (HidDevice device : all) {
            int vid = device.getVendorId() & 0xFFFF;
            int pid = device.getProductId() & 0xFFFF;
            if (!config.matches(vid, pid)) {
                continue;
            }
            boolean ifaceMatch = device.getInterfaceNumber() == config.getInterfaceNumber()
                    || device.getInterfaceNumber() < 0;
            boolean usageMatch = config.getUsagePage() == null
                    || (device.getUsagePage() & 0xFFFF) == (config.getUsagePage() & 0xFFFF);
            if (ifaceMatch && usageMatch) {
                exact.add(device);
            } else {
                fallback.add(device);
            }
        }
        return !exact.isEmpty() ? exact : fallback;
    }

    private DeluxM800ProProtocol.ParsedBattery queryDevice(HidDevice device) {
        boolean opened = false;
        try {
            opened = device.open();
            if (!opened) {
                return null;
            }
            for (int packetId = 1; packetId <= config.getMaxAttempts(); packetId++) {
                byte[] payload = DeluxM800ProProtocol.buildFeaturePayload(packetId);
                int sent = device.sendFeatureReport(payload, DeluxM800ProProtocol.FEATURE_REPORT_ID);
                if (sent < 0) {
                    return null;
                }
                byte[] response = new byte[DeluxM800ProProtocol.FULL_REPORT_LENGTH];
                int read = device.getFeatureReport(response, DeluxM800ProProtocol.FEATURE_REPORT_ID);
                if (read < 0) {
                    return null;
                }
                DeluxM800ProProtocol.ParsedBattery parsed =
                        DeluxM800ProProtocol.parseFeatureResponse(response, read);
                if (parsed != null) {
                    return parsed;
                }
                Thread.sleep(25);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Tentativa HID no M800 Pro (" + device.getPath() + ") falhou: " + e.getMessage());
        } finally {
            if (opened) {
                device.close();
            }
        }
        return null;
    }
}
