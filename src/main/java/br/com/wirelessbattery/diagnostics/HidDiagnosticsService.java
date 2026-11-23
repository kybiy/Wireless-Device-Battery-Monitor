package br.com.wirelessbattery.diagnostics;

import br.com.wirelessbattery.device.AkkoVergeS9Config;
import br.com.wirelessbattery.device.DeluxM800ProConfig;
import br.com.wirelessbattery.device.GameSirCyclone2Config;
import br.com.wirelessbattery.device.HidServiceProvider;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.hid4java.HidDevice;
import org.hid4java.HidServices;

/**
 * Ferramenta de diagnóstico HID estritamente passiva (somente enumeração).
 * Não abre dispositivos e não envia nenhum comando HID aos periféricos.
 */
public final class HidDiagnosticsService {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HidServiceProvider hidProvider;
    private final DeluxM800ProConfig deluxConfig;
    private final AkkoVergeS9Config akkoConfig;
    private final GameSirCyclone2Config cyclone2Config;
    private final AppLogger logger;

    public HidDiagnosticsService(
            HidServiceProvider hidProvider,
            DeluxM800ProConfig deluxConfig,
            AkkoVergeS9Config akkoConfig,
            GameSirCyclone2Config cyclone2Config,
            AppLogger logger
    ) {
        this.hidProvider = Objects.requireNonNull(hidProvider, "hidProvider não pode ser nulo");
        this.deluxConfig = Objects.requireNonNull(deluxConfig, "deluxConfig não pode ser nulo");
        this.akkoConfig = Objects.requireNonNull(akkoConfig, "akkoConfig não pode ser nulo");
        this.cyclone2Config = Objects.requireNonNull(cyclone2Config, "cyclone2Config não pode ser nulo");
        this.logger = Objects.requireNonNull(logger, "logger não pode ser nulo");
    }

    public List<HidDeviceInfo> listConnectedHidDevices() {
        HidServices services = hidProvider.getServices();
        List<HidDevice> attached = services.getAttachedHidDevices();
        List<HidDeviceInfo> result = new ArrayList<>(attached.size());
        for (HidDevice d : attached) {
            String status = classifyDevice(
                    d.getVendorId(),
                    d.getProductId(),
                    d.getUsagePage(),
                    d.getUsage(),
                    d.getManufacturer(),
                    d.getProduct()
            );
            result.add(new HidDeviceInfo(
                    d.getVendorId(),
                    d.getProductId(),
                    d.getInterfaceNumber(),
                    d.getUsagePage(),
                    d.getUsage(),
                    d.getManufacturer(),
                    d.getProduct(),
                    d.getSerialNumber(),
                    d.getPath(),
                    status
            ));
        }
        result.sort(Comparator
                .comparingInt((HidDeviceInfo i) -> i.vendorId() & 0xFFFF)
                .thenComparingInt(i -> i.productId() & 0xFFFF)
                .thenComparingInt(HidDeviceInfo::interfaceNumber)
                .thenComparingInt(i -> i.usagePage() & 0xFFFF));
        logger.info("Diagnóstico HID enumerou " + result.size() + " interface(s) HID (modo somente leitura).");
        return result;
    }

    public String classifyDevice(int vid, int pid, String manufacturer, String product) {
        return classifyDevice(vid, pid, 0, 0, manufacturer, product);
    }

    public String classifyDevice(int vid, int pid, int usagePage, int usage, String manufacturer, String product) {
        int v = vid & 0xFFFF;
        int p = pid & 0xFFFF;
        int page = usagePage & 0xFFFF;
        int u = usage & 0xFFFF;

        String pageHint = "";
        if (cyclone2Config.matchesCollection(v, p, page, u)) {
            pageHint = " [Coleção FFF0:0040 Cyclone 2]";
        } else if (page == 0x84 || page == 0x85 || (page == 0x06 && u == 0x20)) {
            pageHint = " [Coleção HID Bateria Padrão]";
        } else if (page >= 0xFF00) {
            pageHint = " [Coleção Vendor HID]";
        }

        if (deluxConfig.matches(v, p)) {
            if (p == deluxConfig.getWirelessProductId()) {
                return "Delux M800 Pro (2.4 GHz — Ref. configurada)" + pageHint;
            }
            if (p == deluxConfig.getWiredProductId()) {
                return "Delux M800 Pro (USB Cabo — Ref. configurada)" + pageHint;
            }
            return "Delux M800 Pro (ID configurado)" + pageHint;
        }
        if (akkoConfig.matches(v, p)) {
            return "Akko Verge S9 (Receptor configurado — protocolo pendente)" + pageHint;
        }
        if (cyclone2Config.matchesActive(v, p)) {
            return "GameSir Cyclone 2 (Ativo — 2.4 GHz / USB)" + pageHint;
        }
        if (cyclone2Config.matchesIdleReceiver(v, p)) {
            return "GameSir Cyclone 2 (Receptor 2.4 GHz ocioso — controle desligado)" + pageHint;
        }

        String joined = ((manufacturer != null ? manufacturer : "") + " " + (product != null ? product : ""))
                .toLowerCase(Locale.ROOT);
        if (joined.contains("delux") || joined.contains("m800")) {
            return "Possível Delux M800 Pro (por nome)" + pageHint;
        }
        if (joined.contains("akko") || joined.contains("verge")) {
            return "Possível Akko Verge S9 (por nome — protocolo pendente)" + pageHint;
        }
        if (joined.contains("gamesir") || joined.contains("cyclone")) {
            return "Possível GameSir Cyclone 2 (por nome)" + pageHint;
        }
        if (!pageHint.isEmpty()) {
            return "Não identificado" + pageHint;
        }
        return "Não identificado";
    }

    public String buildTextReport(List<HidDeviceInfo> devices) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Relatório de Diagnóstico HID (somente leitura) ===\n");
        sb.append("Gerado em: ").append(LocalDateTime.now().format(TS_FMT)).append("\n");
        sb.append("Total de interfaces HID encontradas: ").append(devices.size()).append("\n\n");
        for (int i = 0; i < devices.size(); i++) {
            HidDeviceInfo d = devices.get(i);
            sb.append(String.format(
                    "[%02d] VID:PID=%s | IF=%d | UsagePage=%s | Usage=%s | Status=%s%n"
                            + "     Fabricante=%s | Produto=%s | Serial=%s%n"
                            + "     Path=%s%n",
                    i + 1,
                    d.formatVidPid(),
                    d.interfaceNumber(),
                    d.formatUsagePage(),
                    d.formatUsage(),
                    d.safeIdentificationStatus(),
                    d.safeManufacturer(),
                    d.safeProduct(),
                    d.safeSerial(),
                    d.safePath()
            ));
        }
        return sb.toString();
    }
}
