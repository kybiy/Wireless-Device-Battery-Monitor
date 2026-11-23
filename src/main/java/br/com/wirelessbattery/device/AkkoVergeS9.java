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
 * Driver para o headset sem fio Akko S9 Ultra / Verge S9 via receptor USB 2.4 GHz
 * ({@code VID 0x36EB / PID 0x1087}, Interface 3, Coleção {@code Col02}: UsagePage {@code 0x008C}, Usage {@code 0x0001}).
 *
 * Opera em modo estritamente somente-leitura utilizando o protocolo oficial {@link AkkoS9UltraProtocol}:
 * envia apenas o Output Report {@code 0x02 0x12 0x01 ... <CRC16>} (64 bytes) para consultar a porcentagem
 * real de bateria ({@code 1..100%}) e o estado de conexão/repouso no Input Report {@code 0x02}.
 */
public final class AkkoVergeS9 implements BatteryDevice {
    public static final String DEVICE_ID = "akko-verge-s9";
    public static final String DISPLAY_NAME = "Akko S9 Ultra";
    public static final String CONNECTION = "2.4 GHz";

    private static final int SINGLE_READ_TIMEOUT_MS = 200;

    private final HidServiceProvider hidProvider;
    private final AkkoVergeS9Config config;
    private final AppLogger logger;
    private String lastCandidateLogSignature;

    public AkkoVergeS9(HidServiceProvider hidProvider, AkkoVergeS9Config config, AppLogger logger) {
        this.hidProvider = Objects.requireNonNull(hidProvider, "hidProvider não pode ser nulo");
        this.config = Objects.requireNonNull(config, "config não pode ser nulo");
        this.logger = Objects.requireNonNull(logger, "logger não pode ser nulo");
    }

    public AkkoVergeS9Config getConfig() {
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
        return true;
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
    public boolean isHardwarePresent(List<HidDevice> attachedDevices) {
        if (attachedDevices == null || !config.isConfigured()) {
            return false;
        }
        for (HidDevice d : attachedDevices) {
            if (config.matchesHeadsetInterface(
                    d.getVendorId(), d.getProductId(), d.getUsagePage(), d.getUsage(), d.getProduct())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public DeviceReading readBattery() {
        if (!config.isConfigured()) {
            return readBattery(List.of());
        }
        try {
            return readBattery(hidProvider.getServices().getAttachedHidDevices());
        } catch (Exception e) {
            logger.error("Erro ao consultar bateria do Akko S9 Ultra / Verge S9", e);
            return DeviceReading.realDisconnected(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.HEADSET,
                    CONNECTION,
                    "Não foi possível enumerar dispositivos HID."
            );
        }
    }

    private enum QueryOutcomeType {
        SUCCESS,
        CONFIRMED_ASLEEP,
        OPEN_FAILED,
        WRITE_FAILED,
        READ_FAILED,
        TIMEOUT
    }

    private record QueryOutcome(
            QueryOutcomeType type,
            AkkoS9UltraProtocol.ParsedBattery battery,
            String detail
    ) {}

    @Override
    public DeviceReading readBattery(List<HidDevice> attachedDevices) {
        if (!config.isConfigured()) {
            return DeviceReading.realPendingProtocol(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.HEADSET,
                    CONNECTION,
                    "Configure VID/PID do receptor 2.4 GHz."
            );
        }

        if (!isHardwarePresent(attachedDevices)) {
            lastCandidateLogSignature = null;
            return DeviceReading.realDisconnected(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.HEADSET,
                    CONNECTION,
                    String.format("Receptor 2.4 GHz (0x%04X:0x%04X) desconectado.",
                            config.getVendorId(), config.getProductId())
            );
        }

        List<HidDevice> candidates = findCandidateCollections(attachedDevices);
        if (candidates.isEmpty()) {
            lastCandidateLogSignature = null;
            logger.hidOperation(
                    DISPLAY_NAME,
                    config.getVendorId(),
                    config.getProductId(),
                    config.getInterfaceNumber(),
                    AkkoVergeS9Config.DEFAULT_USAGE_PAGE,
                    AkkoVergeS9Config.DEFAULT_USAGE,
                    AkkoS9UltraProtocol.REPORT_ID & 0xFF,
                    "selectCollection",
                    "INDISPONIVEL",
                    "Receptor detectado, mas sem coleção HID 0x008C:0x0001 na interface " + config.getInterfaceNumber(),
                    true
            );
            return DeviceReading.realBatteryUnavailable(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.HEADSET,
                    CONNECTION,
                    String.format(
                            "Receptor 2.4 GHz detectado (0x%04X:0x%04X) sem coleção 0x008C:0x0001.",
                            config.getVendorId(),
                            config.getProductId()
                    )
            );
        }

        logCandidateSelectionIfChanged(candidates);

        QueryOutcome bestFailure = null;
        for (HidDevice candidate : candidates) {
            QueryOutcome outcome = queryCollection(candidate);
            if (outcome.type() == QueryOutcomeType.SUCCESS && outcome.battery() != null) {
                return DeviceReading.realConnected(
                        DEVICE_ID,
                        DISPLAY_NAME,
                        DeviceType.HEADSET,
                        outcome.battery().percent(),
                        outcome.battery().charging(),
                        CONNECTION,
                        outcome.detail()
                );
            }
            if (bestFailure == null || failurePriority(outcome.type()) > failurePriority(bestFailure.type())) {
                bestFailure = outcome;
            }
        }

        if (bestFailure == null) {
            return DeviceReading.realNoResponse(
                    DEVICE_ID, DISPLAY_NAME, DeviceType.HEADSET, CONNECTION, "Sem resposta do receptor 2.4 GHz."
            );
        }

        return switch (bestFailure.type()) {
            case CONFIRMED_ASLEEP -> DeviceReading.realAsleep(
                    DEVICE_ID, DISPLAY_NAME, DeviceType.HEADSET, CONNECTION, bestFailure.detail()
            );
            case OPEN_FAILED -> DeviceReading.realOpenFailed(
                    DEVICE_ID, DISPLAY_NAME, DeviceType.HEADSET, CONNECTION, bestFailure.detail()
            );
            case WRITE_FAILED, READ_FAILED -> DeviceReading.realReadFailed(
                    DEVICE_ID, DISPLAY_NAME, DeviceType.HEADSET, CONNECTION, bestFailure.detail()
            );
            case TIMEOUT -> DeviceReading.realTimeout(
                    DEVICE_ID, DISPLAY_NAME, DeviceType.HEADSET, CONNECTION, bestFailure.detail()
            );
            default -> DeviceReading.realError(
                    DEVICE_ID, DISPLAY_NAME, DeviceType.HEADSET, CONNECTION, bestFailure.detail()
            );
        };
    }

    List<HidDevice> findCandidateCollections(List<HidDevice> attachedDevices) {
        if (attachedDevices == null || attachedDevices.isEmpty()) {
            return List.of();
        }
        List<HidDevice> primary = new ArrayList<>();
        List<HidDevice> fallback008c = new ArrayList<>();
        for (HidDevice d : attachedDevices) {
            int vid = d.getVendorId() & 0xFFFF;
            int pid = d.getProductId() & 0xFFFF;
            int iface = d.getInterfaceNumber();
            int page = d.getUsagePage() & 0xFFFF;
            int usage = d.getUsage() & 0xFFFF;
            if (config.matchesTargetCollection(vid, pid, iface, page, usage, d.getProduct())) {
                primary.add(d);
            } else if (config.matchesHeadsetInterface(vid, pid, page, usage, d.getProduct())
                    && page == AkkoVergeS9Config.DEFAULT_USAGE_PAGE) {
                fallback008c.add(d);
            }
        }
        return !primary.isEmpty() ? primary : fallback008c;
    }

    private QueryOutcome queryCollection(HidDevice device) {
        int vid = device.getVendorId() & 0xFFFF;
        int pid = device.getProductId() & 0xFFFF;
        int iface = device.getInterfaceNumber();
        int page = device.getUsagePage() & 0xFFFF;
        int usage = device.getUsage() & 0xFFFF;
        int reportId = AkkoS9UltraProtocol.REPORT_ID & 0xFF;

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
                        "Falha ao abrir coleção HID 0x008C:0x0001 (IF " + iface + "): " + err
                );
            }

            logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, reportId,
                    "open", "ABERTA_COM_SUCESSO", null, false);

            // Não realizar read(..., 0) antes de write(...) no Windows (signal11/hidapi embutido no hid4java 0.8.0)
            // para evitar ReadFile Overlapped pendente no mesmo device_handle do WriteFile.
            byte[] payload = AkkoS9UltraProtocol.buildBatteryWritePayload();
            int written = device.write(
                    payload,
                    AkkoS9UltraProtocol.WRITE_PAYLOAD_LENGTH,
                    AkkoS9UltraProtocol.REPORT_ID
            );
            if (written < 0) {
                String err = safeLastError(device, "write retornou " + written);
                logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, reportId,
                        "write(0x02 0x12 0x01, 64B)", "FALHA_ESCRITA (" + written + ")", err, true);
                return new QueryOutcome(
                        QueryOutcomeType.WRITE_FAILED,
                        null,
                        "Falha ao enviar consulta 0x02 0x12 0x01 (IF " + iface + "): " + err
                );
            }

            long deadline = System.currentTimeMillis() + AkkoVergeS9Config.DEFAULT_READ_TIMEOUT_MS;
            byte[] buffer = new byte[AkkoS9UltraProtocol.FULL_REPORT_LENGTH];
            boolean sawAsleepStatus = false;
            int packetsSeen = 0;

            while (System.currentTimeMillis() < deadline) {
                int remaining = (int) Math.max(1L, Math.min(SINGLE_READ_TIMEOUT_MS, deadline - System.currentTimeMillis()));
                int read = device.read(buffer, remaining);
                if (read < 0) {
                    String err = safeLastError(device, "read retornou " + read);
                    logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, reportId,
                            "read(64B)", "FALHA_LEITURA (" + read + ")", err, true);
                    return new QueryOutcome(
                            QueryOutcomeType.READ_FAILED,
                            null,
                            "Falha de leitura HID na coleção 0x008C:0x0001 (IF " + iface + "): " + err
                    );
                }
                if (read > 0) {
                    packetsSeen++;
                    AkkoS9UltraProtocol.ParsedReply reply = AkkoS9UltraProtocol.inspectInputReport(buffer, read);
                    if (reply.type() == AkkoS9UltraProtocol.ReplyType.BATTERY_OK && reply.battery() != null) {
                        logger.hidOperation(
                                DISPLAY_NAME,
                                vid,
                                pid,
                                iface,
                                page,
                                usage,
                                reportId,
                                "read(Report 0x02 cmd 0x12)",
                                String.format("OK (bytes=%d, bateria=%d%%, carregando=%s, hdr=[%s], crc=[%02X %02X])",
                                        read,
                                        reply.battery().percent(),
                                        reply.battery().charging(),
                                        formatHexSlice(buffer, 0, Math.min(6, read)),
                                        buffer[62] & 0xFF,
                                        buffer[63] & 0xFF),
                                null,
                                false
                        );
                        String detail = String.format(
                                "Leitura via HID 0x008C:0x0001 (IF %d, Report 0x02 cmd 0x12, %d bytes).",
                                iface, read
                        );
                        return new QueryOutcome(QueryOutcomeType.SUCCESS, reply.battery(), detail);
                    }
                    if (reply.type() == AkkoS9UltraProtocol.ReplyType.HEADSET_ASLEEP) {
                        sawAsleepStatus = true;
                        if (reply.command() == (AkkoS9UltraProtocol.CMD_BATTERY_LEVEL & 0xFF)) {
                            break;
                        }
                    }
                }
            }

            if (sawAsleepStatus) {
                String asleepMsg = String.format(
                        "Receptor 0x%04X:0x%04X (IF %d) confirmou headset desligado ou em repouso.",
                        vid, pid, iface
                );
                logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, reportId,
                        "read(Report 0x02)", "REPOUSO_CONFIRMADO", asleepMsg, false);
                return new QueryOutcome(QueryOutcomeType.CONFIRMED_ASLEEP, null, asleepMsg);
            }

            String timeoutMsg = "Timeout (" + AkkoVergeS9Config.DEFAULT_READ_TIMEOUT_MS
                    + " ms) aguardando resposta 0x02 0x12 0x01"
                    + (packetsSeen > 0 ? " (recebidos " + packetsSeen + " pacotes de status)." : " (0 bytes recebidos).");
            logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, reportId,
                    "read(timeout=" + AkkoVergeS9Config.DEFAULT_READ_TIMEOUT_MS + "ms)", "TIMEOUT", timeoutMsg, true);
            return new QueryOutcome(QueryOutcomeType.TIMEOUT, null, timeoutMsg);
        } catch (Exception e) {
            logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, reportId,
                    "queryCollection", "EXCECAO", e.getMessage(), true);
            return new QueryOutcome(
                    QueryOutcomeType.READ_FAILED,
                    null,
                    "Exceção HID no Akko S9 Ultra: " + e.getMessage()
            );
        } finally {
            if (opened) {
                device.close();
            }
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
                    AkkoS9UltraProtocol.REPORT_ID & 0xFF,
                    "selectCollection",
                    "CANDIDATAS_SELECIONADAS",
                    signature,
                    false
            );
        }
    }

    private static int failurePriority(QueryOutcomeType type) {
        return switch (type) {
            case CONFIRMED_ASLEEP -> 4;
            case TIMEOUT -> 3;
            case READ_FAILED, WRITE_FAILED -> 2;
            case OPEN_FAILED -> 1;
            default -> 0;
        };
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
}
