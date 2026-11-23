package br.com.wirelessbattery.diagnostics;

import br.com.wirelessbattery.config.AppVersion;
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
            WindowsHidCapsInspector.HidReportCaps caps = WindowsHidCapsInspector.inspect(d.getPath());
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
                    status,
                    caps.inputBytes(),
                    caps.outputBytes(),
                    caps.featureBytes()
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

        String joined = ((manufacturer != null ? manufacturer : "") + " " + (product != null ? product : ""))
                .toLowerCase(Locale.ROOT);
        boolean looksLikeKeyboard = AkkoVergeS9Config.isKnownAkkoKeyboard(v, p)
                || v == AkkoVergeS9Config.KNOWN_KEYBOARD_VENDOR_ID
                || joined.contains("keyboard")
                || joined.contains("teclado")
                || (page == 0x0001 && u == 0x0006);

        String pageHint = "";
        if (cyclone2Config.matchesCollection(v, p, page, u) || cyclone2Config.matchesIdleCollection(v, p, page, u)) {
            pageHint = " [Coleção FFF0:0040 Cyclone 2]";
        } else if (deluxConfig.matches(v, p) && page == DeluxM800ProConfig.DEFAULT_USAGE_PAGE && u == DeluxM800ProConfig.DEFAULT_USAGE) {
            pageHint = " [Coleção alvo M800 Pro — Feature 0x0C]";
        } else if (akkoConfig.matches(v, p) && page == AkkoVergeS9Config.PROPRIETARY_CANDIDATE_USAGE_PAGE) {
            pageHint = " [Coleção alvo 0x008C — Report 0x02 / cmd 0x12 CRC16]";
        } else if (akkoConfig.matches(v, p) && page == AkkoVergeS9Config.CONSUMER_USAGE_PAGE) {
            pageHint = " [Consumer Control — sem bateria HID]";
        } else if (page == 0x84 || page == 0x85 || (page == 0x06 && u == 0x20)) {
            pageHint = " [Coleção HID Bateria Padrão]";
        } else if (page >= 0xFF00) {
            pageHint = " [Coleção Vendor HID]";
        }

        // 1. Correspondência exata por VID/PID confirmados
        if (deluxConfig.matches(v, p)) {
            if (p == deluxConfig.getWirelessProductId()) {
                return "Delux M800 Pro (2.4 GHz — Ref. configurada)" + pageHint;
            }
            if (p == deluxConfig.getWiredProductId()) {
                return "Delux M800 Pro (USB Cabo — Ref. configurada)" + pageHint;
            }
            return "Delux M800 Pro (ID configurado)" + pageHint;
        }
        if (akkoConfig.matchesHeadsetInterface(v, p, page, u, product)) {
            return "Akko S9 Ultra / Verge S9 (Receptor 2.4 GHz — Ref. configurada)" + pageHint;
        }
        if (cyclone2Config.matchesActive(v, p)) {
            String mode = cyclone2Config.isWiredPid(p) ? "USB Cabo" : "2.4 GHz";
            return "GameSir Cyclone 2 (Ativo — " + mode + ")" + pageHint;
        }
        if (cyclone2Config.matchesIdleReceiver(v, p)) {
            return "GameSir Cyclone 2 (Receptor 2.4 GHz ocioso / modo HID — aguarda XInput 0x100B)" + pageHint;
        }

        // 2. Exclusão explícita de teclados (evita falso positivo do teclado Akko 0x3151:0x502D como headset)
        if (AkkoVergeS9Config.isKnownAkkoKeyboard(v, p)
                || (v == AkkoVergeS9Config.KNOWN_KEYBOARD_VENDOR_ID && (joined.contains("akko") || looksLikeKeyboard))) {
            return "Teclado Akko (não monitorado — ignorado pelo driver do headset)";
        }
        if (looksLikeKeyboard) {
            return "Teclado HID (não monitorado)";
        }

        // 3. Heurística conservadora por modelo específico (nunca apenas pela marca "Akko", "Delux" ou "GameSir")
        if (joined.contains("m800")) {
            return "Possível Delux M800 Pro (por modelo)" + pageHint;
        }
        if (joined.contains("verge") || joined.contains("s9 ultra") || joined.contains("s9 master")) {
            return "Possível Akko S9 Ultra / Verge S9 (por modelo — protocolo não implementado)" + pageHint;
        }
        if (joined.contains("cyclone 2") || joined.contains("cyclone2")) {
            return "Possível GameSir Cyclone 2 (por modelo)" + pageHint;
        }
        if (!pageHint.isEmpty()) {
            return "Não identificado" + pageHint;
        }
        return "Não identificado";
    }

    public String buildTextReport(List<HidDeviceInfo> devices) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Relatório de Diagnóstico HID (somente leitura) ===\n");
        sb.append("Aplicação: ").append(AppVersion.formatStartupInfo()).append("\n");
        sb.append("Gerado em: ").append(LocalDateTime.now().format(TS_FMT)).append("\n");
        sb.append("Total de interfaces HID encontradas: ").append(devices.size()).append("\n\n");
        boolean akkoDongleDetected = false;
        String akkoProprietaryCaps = null;
        for (int i = 0; i < devices.size(); i++) {
            HidDeviceInfo d = devices.get(i);
            int vid = d.vendorId() & 0xFFFF;
            int pid = d.productId() & 0xFFFF;
            int page = d.usagePage() & 0xFFFF;
            if (vid == AkkoVergeS9Config.DEFAULT_VENDOR_ID && pid == AkkoVergeS9Config.DEFAULT_PRODUCT_ID) {
                akkoDongleDetected = true;
                if (page == AkkoVergeS9Config.PROPRIETARY_CANDIDATE_USAGE_PAGE) {
                    akkoProprietaryCaps = d.formatReportCaps();
                }
            }
            sb.append(String.format(
                    "[%02d] VID:PID=%s | IF=%d | UsagePage=%s | Usage=%s | Caps=%s | Status=%s%n"
                            + "     Fabricante=%s | Produto=%s | Serial=%s%n"
                            + "     Path=%s%n",
                    i + 1,
                    d.formatVidPid(),
                    d.interfaceNumber(),
                    d.formatUsagePage(),
                    d.formatUsage(),
                    d.formatReportCaps(),
                    d.safeIdentificationStatus(),
                    d.safeManufacturer(),
                    d.safeProduct(),
                    d.safeSerial(),
                    d.safePath()
            ));
        }
        if (akkoDongleDetected) {
            sb.append("\n=== Protocolo HID Confirmado (Akko S9 Ultra) ===\n");
            sb.append("- Dongle 0x36EB:0x1087 detectado na IF=3.\n");
            sb.append("- Col01 (UsagePage 0x000C, Usage 0x0001): Consumer Control padrão (controles de áudio/mídia).\n");
            sb.append("- Col02 (UsagePage 0x008C, Usage 0x0001")
                    .append(akkoProprietaryCaps != null ? ", Caps " + akkoProprietaryCaps : "")
                    .append("): canal de bateria via Report ID 0x02 (Output 02 12 01 ... 71 12 -> Input 02 12 01 00 <pct> ... <CRC16>).\n");
        }
        return sb.toString();
    }
}
