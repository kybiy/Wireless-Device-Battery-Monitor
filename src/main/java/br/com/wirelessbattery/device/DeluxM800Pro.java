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
 * Implementação Java somente-leitura via HID Feature Reports (Report ID 0x0C).
 * Os parâmetros técnicos de interoperabilidade HID (VID/PID, relatório 0x0C e offsets de
 * bateria/carregamento) foram consultados a partir da referência pública
 * {@code xb-bx/m800-pro-driver}. A leitura percentual (ex.: 78%) foi confirmada em
 * hardware real no Windows; o estado de carregamento e a leitura em 100% ainda
 * precisam ser validados no hardware real.
 */
public final class DeluxM800Pro implements BatteryDevice {
    public static final String DEVICE_ID = "delux-m800-pro";
    public static final String DISPLAY_NAME = "Delux M800 Pro";
    public static final String CONNECTION = "2.4 GHz";

    private static final DriverCapabilities CAPABILITIES = new DriverCapabilities(
            TransportType.USB_HID,
            true,
            false,
            10,
            new ProtocolSafetyInfo(
                    QueryMechanism.HID_FEATURE_REPORT_QUERY,
                    false,
                    false,
                    true,
                    false,
                    "Consulta somente-bateria via HID Feature Report 0x0C (sendFeatureReport + getFeatureReport), sem comandos de configuração.",
                    "https://github.com/xb-bx/m800-pro-driver"
            )
    );

    private final HidServiceProvider hidProvider;
    private final DeluxM800ProConfig config;
    private final AppLogger logger;
    private String lastCandidateLogSignature;

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
    public DriverCapabilities getCapabilities() {
        return CAPABILITIES;
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

    private static final long QUERY_SETTLE_DELAY_MS = 20L;

    private enum QueryOutcomeType {
        SUCCESS,
        OPEN_FAILED,
        SEND_FAILED,
        GET_FAILED,
        NO_RESPONSE,
        INVALID_DATA
    }

    private record QueryOutcome(
            QueryOutcomeType type,
            DeluxM800ProProtocol.ParsedBattery battery,
            boolean wired,
            String detail
    ) {}

    @Override
    public DeviceReading readBattery(List<HidDevice> attachedDevices) {
        try {
            boolean hardwareSeen = isHardwarePresent(attachedDevices);
            if (!hardwareSeen) {
                lastCandidateLogSignature = null;
                return DeviceReading.realDisconnected(
                        DEVICE_ID,
                        DISPLAY_NAME,
                        DeviceType.MOUSE,
                        CONNECTION,
                        "Receptor 2.4 GHz (0x248A:0x5B2F) não detectado."
                );
            }

            List<HidDevice> candidates = findCandidateInterfaces(attachedDevices);
            if (candidates.isEmpty()) {
                lastCandidateLogSignature = null;
                logger.hidOperation(
                        DISPLAY_NAME,
                        config.getVendorId(),
                        config.getWirelessProductId(),
                        config.getInterfaceNumber(),
                        config.getUsagePage() != null ? config.getUsagePage() : 0,
                        config.getUsage() != null ? config.getUsage() : 0,
                        DeluxM800ProProtocol.FEATURE_REPORT_ID & 0xFF,
                        "selectCollection",
                        "INDISPONIVEL",
                        "Nenhuma coleção Vendor (0xFF00:0x0001) encontrada na interface " + config.getInterfaceNumber(),
                        true
                );
                return DeviceReading.realBatteryUnavailable(
                        DEVICE_ID,
                        DISPLAY_NAME,
                        DeviceType.MOUSE,
                        CONNECTION,
                        "Receptor detectado, mas sem coleção HID Vendor 0xFF00:0x0001 na interface " + config.getInterfaceNumber() + "."
                );
            }

            logCandidateSelectionIfChanged(candidates);

            QueryOutcome bestFailure = null;
            for (HidDevice candidate : candidates) {
                QueryOutcome outcome = queryDevice(candidate);
                if (outcome.type() == QueryOutcomeType.SUCCESS && outcome.battery() != null) {
                    String connLabel = outcome.wired() ? "USB Cabo" : CONNECTION;
                    return DeviceReading.realConnected(
                            DEVICE_ID,
                            DISPLAY_NAME,
                            DeviceType.MOUSE,
                            outcome.battery().percent(),
                            outcome.battery().charging(),
                            connLabel,
                            outcome.detail()
                    );
                }
                if (bestFailure == null || failurePriority(outcome.type()) > failurePriority(bestFailure.type())) {
                    bestFailure = outcome;
                }
            }

            if (bestFailure == null) {
                return DeviceReading.realNoResponse(
                        DEVICE_ID,
                        DISPLAY_NAME,
                        DeviceType.MOUSE,
                        CONNECTION,
                        "Receptor detectado, sem resposta de bateria."
                );
            }

            String connLabel = bestFailure.wired() ? "USB Cabo" : CONNECTION;
            return switch (bestFailure.type()) {
                case OPEN_FAILED -> DeviceReading.realOpenFailed(
                        DEVICE_ID, DISPLAY_NAME, DeviceType.MOUSE, connLabel, bestFailure.detail()
                );
                case SEND_FAILED, GET_FAILED, INVALID_DATA -> DeviceReading.realReadFailed(
                        DEVICE_ID, DISPLAY_NAME, DeviceType.MOUSE, connLabel, bestFailure.detail()
                );
                case NO_RESPONSE -> DeviceReading.realNoResponse(
                        DEVICE_ID, DISPLAY_NAME, DeviceType.MOUSE, connLabel, bestFailure.detail()
                );
                default -> DeviceReading.realError(
                        DEVICE_ID, DISPLAY_NAME, DeviceType.MOUSE, connLabel, bestFailure.detail()
                );
            };
        } catch (Exception e) {
            logger.error("Falha inesperada ao consultar Delux M800 Pro", e);
            return DeviceReading.realReadFailed(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.MOUSE,
                    CONNECTION,
                    "Falha HID ao consultar M800 Pro: " + e.getMessage()
            );
        }
    }

    private void logCandidateSelectionIfChanged(List<HidDevice> candidates) {
        StringBuilder desc = new StringBuilder();
        desc.append("count=").append(candidates.size()).append(" [");
        for (int i = 0; i < candidates.size(); i++) {
            HidDevice c = candidates.get(i);
            if (i > 0) {
                desc.append(", ");
            }
            desc.append(String.format(
                    "0x%04X:0x%04X IF=%d 0x%04X:0x%04X",
                    c.getVendorId() & 0xFFFF,
                    c.getProductId() & 0xFFFF,
                    c.getInterfaceNumber(),
                    c.getUsagePage() & 0xFFFF,
                    c.getUsage() & 0xFFFF
            ));
        }
        desc.append("]");
        String signature = desc.toString();
        if (!signature.equals(lastCandidateLogSignature)) {
            lastCandidateLogSignature = signature;
            HidDevice primary = candidates.get(0);
            logger.hidOperation(
                    DISPLAY_NAME,
                    primary.getVendorId() & 0xFFFF,
                    primary.getProductId() & 0xFFFF,
                    primary.getInterfaceNumber(),
                    primary.getUsagePage() & 0xFFFF,
                    primary.getUsage() & 0xFFFF,
                    DeluxM800ProProtocol.FEATURE_REPORT_ID & 0xFF,
                    "selectCollection",
                    "CANDIDATAS_SELECIONADAS",
                    signature,
                    false
            );
        }
    }

    private static int failurePriority(QueryOutcomeType type) {
        return switch (type) {
            case NO_RESPONSE -> 4;
            case INVALID_DATA -> 3;
            case GET_FAILED, SEND_FAILED -> 2;
            case OPEN_FAILED -> 1;
            default -> 0;
        };
    }

    List<HidDevice> findCandidateInterfaces(List<HidDevice> all) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<HidDevice> primary = new ArrayList<>();
        List<HidDevice> vendorSameInterface = new ArrayList<>();
        for (HidDevice device : all) {
            int vid = device.getVendorId() & 0xFFFF;
            int pid = device.getProductId() & 0xFFFF;
            int iface = device.getInterfaceNumber();
            int page = device.getUsagePage() & 0xFFFF;
            int usage = device.getUsage() & 0xFFFF;
            if (config.matchesTargetCollection(vid, pid, iface, page, usage)) {
                primary.add(device);
            } else if (config.matchesVendorFallbackOnExpectedInterface(vid, pid, iface, page)) {
                vendorSameInterface.add(device);
            }
        }
        return !primary.isEmpty() ? primary : vendorSameInterface;
    }

    private QueryOutcome queryDevice(HidDevice device) {
        int vid = device.getVendorId() & 0xFFFF;
        int pid = device.getProductId() & 0xFFFF;
        int iface = device.getInterfaceNumber();
        int page = device.getUsagePage() & 0xFFFF;
        int usage = device.getUsage() & 0xFFFF;
        int reportId = DeluxM800ProProtocol.FEATURE_REPORT_ID & 0xFF;
        boolean wired = pid == config.getWiredProductId();

        boolean opened = false;
        try {
            opened = device.open();
            if (!opened) {
                String err = safeLastError(device, "HidDevice.open() retornou false");
                logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, reportId,
                        "open", "FALHA_ABRIR", err, true);
                return new QueryOutcome(
                        QueryOutcomeType.OPEN_FAILED,
                        null,
                        wired,
                        "Falha ao abrir coleção HID (IF " + iface + ", " + String.format("0x%04X:0x%04X", page, usage) + "): " + err
                );
            }

            logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, reportId,
                    "open", "ABERTA_COM_SUCESSO", null, false);

            int lastRawBattery = 0;
            int lastRawCharging = 0;
            int lastReadLen = 0;
            String lastHexPreview = "";

            for (int packetId = 1; packetId <= config.getMaxAttempts(); packetId++) {
                byte[] payload = DeluxM800ProProtocol.buildFeaturePayload(packetId);
                int sent = device.sendFeatureReport(payload, DeluxM800ProProtocol.FEATURE_REPORT_ID);
                if (sent < 0) {
                    String err = safeLastError(device, "sendFeatureReport retornou " + sent);
                    logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, reportId,
                            "sendFeatureReport(32+1=33B, pkt=" + packetId + ")", "FALHA_ESCRITA (" + sent + ")", err, true);
                    return new QueryOutcome(
                            QueryOutcomeType.SEND_FAILED,
                            null,
                            wired,
                            "Falha ao enviar Feature Report 0x0C (IF " + iface + "): " + err
                    );
                }

                // Aguarda resposta RF do mouse no receptor 2.4 GHz antes do GET_FEATURE
                Thread.sleep(QUERY_SETTLE_DELAY_MS);

                // hid4java 0.8.0 chama hid_get_feature_report(..., data.length + 1) e copia a partir de buffer[1]:
                // passando 32 bytes (PAYLOAD_LENGTH), a requisição ao Windows tem exatamente 33 bytes (1 Report ID + 32 dados).
                byte[] response = new byte[DeluxM800ProProtocol.PAYLOAD_LENGTH];
                int read = device.getFeatureReport(response, DeluxM800ProProtocol.FEATURE_REPORT_ID);
                if (read < 0) {
                    String err = safeLastError(device, "getFeatureReport retornou " + read);
                    logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, reportId,
                            "getFeatureReport(32+1=33B, pkt=" + packetId + ")", "FALHA_LEITURA (" + read + ")", err, true);
                    return new QueryOutcome(
                            QueryOutcomeType.GET_FAILED,
                            null,
                            wired,
                            "Falha ao ler Feature Report 0x0C (IF " + iface + "): " + err
                    );
                }

                lastReadLen = read;
                lastHexPreview = formatHexSlice(response, 0, Math.min(20, response.length));
                if (response.length >= 19) {
                    lastRawBattery = response[17] & 0xFF;
                    lastRawCharging = response[18] & 0xFF;
                }

                DeluxM800ProProtocol.ParsedBattery parsed =
                        DeluxM800ProProtocol.parseHid4JavaFeaturePayload(response, read);
                if (parsed != null) {
                    logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, reportId,
                            "getFeatureReport(pkt=" + packetId + ")",
                            String.format("OK (sent=%d, bytes=%d, bateria=%d%%, carregando=%s, raw=%d/%d, payload=[%s])",
                                    sent, read, parsed.percent(), parsed.charging(), lastRawBattery, lastRawCharging, lastHexPreview),
                            null,
                            false);
                    String detail = String.format(
                            "Leitura via Feature Report 0x0C (IF %d, 0x%04X:0x%04X, %d bytes, tentativa %d).",
                            iface, page, usage, read, packetId
                    );
                    return new QueryOutcome(QueryOutcomeType.SUCCESS, parsed, wired, detail);
                }

                if (lastRawBattery > DeluxM800ProProtocol.MAX_VALID_BATTERY_PERCENT) {
                    String err = String.format("Valor de bateria fora da faixa 1..100 em payload[17]=0x%02X (%d), charging=%d, payload=[%s]",
                            lastRawBattery, lastRawBattery, lastRawCharging, lastHexPreview);
                    logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, reportId,
                            "parseFeaturePayload(pkt=" + packetId + ")", "DADOS_INVALIDOS", err, true);
                    return new QueryOutcome(QueryOutcomeType.INVALID_DATA, null, wired, err);
                }
            }

            String summary = String.format(
                    "Sem resposta de bateria após %d tentativa(s) (bytes=%d, buf[18]=%d, buf[19]=%d, payload=[%s]).",
                    config.getMaxAttempts(), lastReadLen, lastRawBattery, lastRawCharging, lastHexPreview
            );
            logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, reportId,
                    "batteryQuery", "SEM_RESPOSTA", summary, true);
            return new QueryOutcome(
                    QueryOutcomeType.NO_RESPONSE,
                    null,
                    wired,
                    "Receptor conectado, sem resposta do mouse (mova o mouse para acordar ou verifique se está ligado)."
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new QueryOutcome(QueryOutcomeType.NO_RESPONSE, null, wired, "Consulta interrompida.");
        } catch (Exception e) {
            logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, reportId,
                    "queryDevice", "EXCECAO", e.getMessage(), true);
            return new QueryOutcome(
                    QueryOutcomeType.GET_FAILED,
                    null,
                    wired,
                    "Exceção HID ao consultar M800 Pro: " + e.getMessage()
            );
        } finally {
            if (opened) {
                device.close();
            }
        }
    }

    private static String safeLastError(HidDevice device, String fallback) {
        try {
            String msg = device.getLastErrorMessage();
            if (msg != null && !msg.isBlank() && !"Success".equalsIgnoreCase(msg.trim())) {
                return msg.trim();
            }
        } catch (Exception ignored) {
            // fallback
        }
        return fallback;
    }

    private static String formatHexSlice(byte[] buf, int offset, int count) {
        StringBuilder sb = new StringBuilder();
        int end = Math.min(buf.length, offset + count);
        for (int i = offset; i < end; i++) {
            if (i > offset) sb.append(' ');
            sb.append(String.format("%02X", buf[i] & 0xFF));
        }
        return sb.toString();
    }
}
