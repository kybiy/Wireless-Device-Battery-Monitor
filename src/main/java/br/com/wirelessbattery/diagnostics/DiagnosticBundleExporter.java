package br.com.wirelessbattery.diagnostics;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.config.AppVersion;
import br.com.wirelessbattery.device.AkkoVergeS9Config;
import br.com.wirelessbattery.device.DeluxM800ProConfig;
import br.com.wirelessbattery.device.GameSirCyclone2Config;
import br.com.wirelessbattery.device.QcyT13Config;
import br.com.wirelessbattery.model.DeviceReading;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Constrói e exporta localmente um pacote ZIP de diagnóstico sanitizado para inspeção
 * e compartilhamento seguro pelo usuário (sem envio automático pela rede).
 */
public final class DiagnosticBundleExporter {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HidDiagnosticsService diagnosticsService;
    private final AppSettings settings;
    private final AppLogger logger;
    private final List<DeviceReading> driverStates;
    private final String widgetBackendSummary;
    private final DiagnosticSanitizer sanitizer;
    private final List<HidDeviceInfo> preEnumeratedDevices;

    public DiagnosticBundleExporter(
            HidDiagnosticsService diagnosticsService,
            AppSettings settings,
            AppLogger logger,
            List<DeviceReading> driverStates,
            String widgetBackendSummary,
            DiagnosticSanitizer sanitizer
    ) {
        this(diagnosticsService, settings, logger, driverStates, widgetBackendSummary, sanitizer, null);
    }

    public DiagnosticBundleExporter(
            HidDiagnosticsService diagnosticsService,
            AppSettings settings,
            AppLogger logger,
            List<DeviceReading> driverStates,
            String widgetBackendSummary,
            DiagnosticSanitizer sanitizer,
            List<HidDeviceInfo> preEnumeratedDevices
    ) {
        this.diagnosticsService = diagnosticsService;
        this.settings = settings;
        this.logger = logger;
        this.driverStates = driverStates != null ? List.copyOf(driverStates) : List.of();
        this.widgetBackendSummary = widgetBackendSummary;
        this.sanitizer = sanitizer != null ? sanitizer : new DiagnosticSanitizer();
        this.preEnumeratedDevices = preEnumeratedDevices != null ? List.copyOf(preEnumeratedDevices) : null;
    }

    public static Map<String, String> buildBundle(
            HidDiagnosticsService diagnosticsService,
            AppSettings settings,
            AppLogger logger,
            List<DeviceReading> driverStates,
            String widgetBackendSummary,
            DiagnosticSanitizer sanitizer
    ) {
        return new DiagnosticBundleExporter(
                diagnosticsService,
                settings,
                logger,
                driverStates,
                widgetBackendSummary,
                sanitizer
        ).buildEntries();
    }

    public Map<String, String> buildEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("diagnostic-summary.txt", buildSummarySection());
        entries.put("driver-states.txt", buildDriverStatesSection());
        entries.put("hid-interfaces.txt", buildHidInterfacesSection());
        entries.put("settings-sanitized.properties", sanitizer.sanitizeText(DiagnosticSanitizer.sanitizeSettings(settings)));
        entries.put("logs-sanitized.txt", buildSanitizedLogsSection());
        return entries;
    }

    private String buildSummarySection() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Resumo Técnico de Diagnóstico ===\n");
        sb.append("Versão da aplicação: ").append(AppVersion.getVersion()).append('\n');
        sb.append("Build timestamp: ").append(AppVersion.getBuildTimestamp()).append('\n');
        sb.append("Gerado em: ").append(LocalDateTime.now().format(TS_FMT)).append('\n');
        sb.append("SO: ").append(System.getProperty("os.name", "-"))
                .append(" | Versão: ").append(System.getProperty("os.version", "-"))
                .append(" | Arquitetura: ").append(System.getProperty("os.arch", "-")).append('\n');
        sb.append("Java: ").append(System.getProperty("java.version", "-"))
                .append(" | Vendor: ").append(System.getProperty("java.vendor", "-")).append('\n');
        sb.append("Backend do widget: ")
                .append(widgetBackendSummary != null && !widgetBackendSummary.isBlank()
                        ? widgetBackendSummary
                        : "N/A")
                .append('\n');
        sb.append("\n=== Dispositivos e VID/PID Suportados ===\n");
        sb.append(String.format("- Delux M800 Pro: VID=0x%04X, PID=0x%04X (2.4 GHz) / 0x%04X (USB), IF=%d, Usage=0x%04X:0x%04X%n",
                DeluxM800ProConfig.DEFAULT_VENDOR_ID,
                DeluxM800ProConfig.DEFAULT_WIRELESS_PID,
                DeluxM800ProConfig.DEFAULT_WIRED_PID,
                DeluxM800ProConfig.DEFAULT_INTERFACE_NUMBER,
                DeluxM800ProConfig.DEFAULT_USAGE_PAGE,
                DeluxM800ProConfig.DEFAULT_USAGE));
        sb.append(String.format("- Akko S9 Ultra / Verge S9: VID=0x%04X, PID=0x%04X (2.4 GHz) / 0x%04X (Alt), IF=%d, Usage=0x%04X:0x%04X%n",
                AkkoVergeS9Config.DEFAULT_VENDOR_ID,
                AkkoVergeS9Config.DEFAULT_PRODUCT_ID,
                AkkoVergeS9Config.WIRED_OR_ALT_PRODUCT_ID,
                AkkoVergeS9Config.DEFAULT_INTERFACE_NUMBER,
                AkkoVergeS9Config.DEFAULT_USAGE_PAGE,
                AkkoVergeS9Config.DEFAULT_USAGE));
        sb.append(String.format("- GameSir Cyclone 2: VID=0x%04X, PID=0x%04X/0x%04X (2.4 GHz) / 0x%04X/0x%04X (USB) / 0x%04X (Idle), Usage=0x%04X:0x%04X%n",
                GameSirCyclone2Config.DEFAULT_VENDOR_ID,
                GameSirCyclone2Config.DEFAULT_ACTIVE_PID,
                GameSirCyclone2Config.DEFAULT_WIRELESS_ALT_PID,
                GameSirCyclone2Config.DEFAULT_WIRED_PID,
                GameSirCyclone2Config.DEFAULT_WIRED_LEGACY_PID,
                GameSirCyclone2Config.DEFAULT_IDLE_RECEIVER_PID,
                GameSirCyclone2Config.DEFAULT_USAGE_PAGE,
                GameSirCyclone2Config.DEFAULT_USAGE));
        sb.append("- QCY T13: Bluetooth (varredura passiva Windows PnP, padrão: ")
                .append(QcyT13Config.DEFAULT_NAME_PATTERN).append(")\n");
        return sanitizer.sanitizeText(sb.toString());
    }

    private String buildDriverStatesSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Estado Atual dos Drivers ===\n");
        if (driverStates.isEmpty()) {
            sb.append("Nenhum estado de driver disponível.\n");
            return sb.toString();
        }
        for (DeviceReading r : driverStates) {
            if (r == null) {
                continue;
            }
            String pct = r.batteryPercent() != null ? r.batteryPercent() + "%" : "--%";
            String detail = sanitizer.sanitizeText(r.detailMessage());
            sb.append(String.format("- %s (%s) | Status=%s | Bateria=%s | Carregando=%s | Conexão=%s | Origem=%s | Detalhe=%s%n",
                    r.deviceId(),
                    r.displayName(),
                    r.status().name(),
                    pct,
                    r.charging(),
                    r.connectionLabel(),
                    r.sourceType().name(),
                    detail.isEmpty() ? "-" : detail));
        }
        return sanitizer.sanitizeText(sb.toString());
    }

    private String buildHidInterfacesSection() {
        if (diagnosticsService == null) {
            return "Serviço de diagnóstico HID indisponível.\n";
        }
        try {
            List<HidDeviceInfo> devices = preEnumeratedDevices != null
                    ? preEnumeratedDevices
                    : diagnosticsService.listConnectedHidDevices();
            String rawReport = diagnosticsService.buildTextReport(devices);
            return sanitizer.sanitizeText(rawReport);
        } catch (Exception e) {
            return "Erro ao enumerar interfaces HID: " + sanitizer.sanitizeText(e.getMessage()) + "\n";
        }
    }

    private String buildSanitizedLogsSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Logs Recentes (Sanitizados) ===\n");
        List<String> lines = logger != null ? logger.getHistory() : List.of();
        if (lines.isEmpty()) {
            sb.append("# Nenhum log recente disponível.\n");
            return sb.toString();
        }
        for (String line : lines) {
            sb.append(sanitizer.sanitizeText(line)).append('\n');
        }
        return sb.toString();
    }

    public static String buildPreviewText(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("================================================================================\n");
            sb.append("ARQUIVO: ").append(entry.getKey()).append('\n');
            sb.append("================================================================================\n");
            String value = entry.getValue() != null ? entry.getValue() : "";
            sb.append(value);
            if (!value.endsWith("\n")) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public static void exportZip(Map<String, String> entries, Path targetZip) throws IOException {
        Objects.requireNonNull(entries, "entries não pode ser nulo");
        Objects.requireNonNull(targetZip, "targetZip não pode ser nulo");
        Path parent = targetZip.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetZip), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String fileName = entry.getKey();
                if (fileName == null || fileName.isBlank()) {
                    continue;
                }
                ZipEntry zipEntry = new ZipEntry(fileName);
                zos.putNextEntry(zipEntry);
                byte[] bytes = (entry.getValue() != null ? entry.getValue() : "").getBytes(StandardCharsets.UTF_8);
                zos.write(bytes);
                zos.closeEntry();
            }
        }
    }
}
