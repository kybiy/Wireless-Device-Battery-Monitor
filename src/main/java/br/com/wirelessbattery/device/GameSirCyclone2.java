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
 * Driver experimental para o controle GameSir Cyclone 2 via hid4java no Windows.
 *
 * Baseado diretamente em {@code PollGameSirCyclone} ({@code src/drivers/gamesir.cpp} do
 * repositório de referência). Opera em modo XInput via receptor 2.4 GHz ou cabo USB
 * na coleção HID {@code FFF0:0040} usando o relatório de saída {@code 0x0F 0x03}.
 *
 * Permanece marcado como experimental até validação com o controle físico no Windows.
 */
public final class GameSirCyclone2 implements BatteryDevice {
    public static final String DEVICE_ID = "gamesir-cyclone-2";
    public static final String DISPLAY_NAME = "GameSir Cyclone 2";
    public static final String CONNECTION = "2.4 GHz / USB";

    private static final int SINGLE_READ_TIMEOUT_MS = 200;

    private final HidServiceProvider hidProvider;
    private final GameSirCyclone2Config config;
    private final AppLogger logger;

    public GameSirCyclone2(HidServiceProvider hidProvider, GameSirCyclone2Config config, AppLogger logger) {
        this.hidProvider = Objects.requireNonNull(hidProvider, "hidProvider não pode ser nulo");
        this.config = Objects.requireNonNull(config, "config não pode ser nulo");
        this.logger = Objects.requireNonNull(logger, "logger não pode ser nulo");
    }

    public GameSirCyclone2Config getConfig() {
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
        return DeviceType.GAMEPAD;
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
    public boolean matchesVidPid(int vid, int pid) {
        return config.matchesAny(vid & 0xFFFF, pid & 0xFFFF);
    }

    @Override
    public DeviceReading readBattery() {
        try {
            return readBattery(hidProvider.getServices().getAttachedHidDevices());
        } catch (Exception e) {
            logger.error("Falha ao enumerar HID para GameSir Cyclone 2", e);
            return DeviceReading.realDisconnected(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.GAMEPAD,
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
            GameSirCyclone2Protocol.ParsedBattery battery,
            int pid,
            String detail
    ) {}

    @Override
    public DeviceReading readBattery(List<HidDevice> attachedDevices) {
        if (attachedDevices == null || attachedDevices.isEmpty()) {
            return DeviceReading.realDisconnected(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.GAMEPAD,
                    CONNECTION,
                    "Controle / receptor não detectado."
            );
        }

        List<HidDevice> activeCollections = new ArrayList<>();
        List<HidDevice> idleCollections = new ArrayList<>();
        boolean activePidSeen = false;
        boolean idleReceiverSeen = false;

        for (HidDevice d : attachedDevices) {
            int vid = d.getVendorId() & 0xFFFF;
            int pid = d.getProductId() & 0xFFFF;
            if (config.matchesActive(vid, pid)) {
                activePidSeen = true;
                if (config.matchesCollection(vid, pid, d.getUsagePage(), d.getUsage())) {
                    activeCollections.add(d);
                }
            } else if (config.matchesIdleReceiver(vid, pid)) {
                idleReceiverSeen = true;
                if (config.matchesIdleCollection(vid, pid, d.getUsagePage(), d.getUsage())) {
                    idleCollections.add(d);
                }
            }
        }

        if (!activeCollections.isEmpty()) {
            return executeQueries(activeCollections, config.getReadTimeoutMs());
        }

        if (activePidSeen) {
            logger.hidOperation(
                    DISPLAY_NAME,
                    config.getVendorId(),
                    config.getActiveProductId(),
                    -1,
                    config.getUsagePage(),
                    config.getUsage(),
                    GameSirCyclone2Protocol.OUT_REPORT_ID & 0xFF,
                    "selectCollection",
                    "INDISPONIVEL",
                    "PID ativo detectado sem coleção HID 0xFFF0:0x0040 (verifique modo XInput)",
                    true
            );
            return DeviceReading.realBatteryUnavailable(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.GAMEPAD,
                    CONNECTION,
                    "Controle detectado sem coleção HID FFF0:0040 (verifique se está em modo XInput)."
            );
        }

        if (!idleCollections.isEmpty()) {
            return executeQueries(idleCollections, GameSirCyclone2Config.IDLE_PROBE_TIMEOUT_MS);
        }

        if (idleReceiverSeen) {
            logger.hidOperation(
                    DISPLAY_NAME,
                    config.getVendorId(),
                    config.getIdleReceiverProductId(),
                    0,
                    0,
                    0,
                    null,
                    "idleReceiverDetected",
                    "REPOUSO_CONFIRMADO",
                    "Receptor 0x3537:0x0575 sem coleção ativa (controle desligado)",
                    false
            );
            return DeviceReading.realAsleep(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.GAMEPAD,
                    "2.4 GHz",
                    "Receptor 2.4 GHz ocioso (0x3537:0x0575) — controle desligado ou em repouso."
            );
        }

        return DeviceReading.realDisconnected(
                DEVICE_ID,
                DISPLAY_NAME,
                DeviceType.GAMEPAD,
                CONNECTION,
                "Controle / receptor não detectado."
        );
    }

    private DeviceReading executeQueries(List<HidDevice> candidates, int timeoutMs) {
        QueryOutcome bestFailure = null;
        for (HidDevice candidate : candidates) {
            QueryOutcome outcome = queryCollection(candidate, timeoutMs);
            if (outcome.type() == QueryOutcomeType.SUCCESS && outcome.battery() != null) {
                boolean wired = config.isWiredPid(outcome.pid());
                String connLabel = wired ? "USB Cabo" : "2.4 GHz";
                boolean charging = outcome.battery().charging();
                return DeviceReading.realConnected(
                        DEVICE_ID,
                        DISPLAY_NAME,
                        DeviceType.GAMEPAD,
                        outcome.battery().percent(),
                        charging,
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
                    DEVICE_ID, DISPLAY_NAME, DeviceType.GAMEPAD, CONNECTION, "Sem resposta do controle."
            );
        }

        String connLabel = config.isWiredPid(bestFailure.pid()) ? "USB Cabo" : "2.4 GHz";
        return switch (bestFailure.type()) {
            case CONFIRMED_ASLEEP -> DeviceReading.realAsleep(
                    DEVICE_ID, DISPLAY_NAME, DeviceType.GAMEPAD, "2.4 GHz", bestFailure.detail()
            );
            case OPEN_FAILED -> DeviceReading.realOpenFailed(
                    DEVICE_ID, DISPLAY_NAME, DeviceType.GAMEPAD, connLabel, bestFailure.detail()
            );
            case WRITE_FAILED, READ_FAILED -> DeviceReading.realReadFailed(
                    DEVICE_ID, DISPLAY_NAME, DeviceType.GAMEPAD, connLabel, bestFailure.detail()
            );
            case TIMEOUT -> DeviceReading.realTimeout(
                    DEVICE_ID, DISPLAY_NAME, DeviceType.GAMEPAD, connLabel, bestFailure.detail()
            );
            default -> DeviceReading.realError(
                    DEVICE_ID, DISPLAY_NAME, DeviceType.GAMEPAD, connLabel, bestFailure.detail()
            );
        };
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

    private QueryOutcome queryCollection(HidDevice device, int timeoutMs) {
        int vid = device.getVendorId() & 0xFFFF;
        int pid = device.getProductId() & 0xFFFF;
        int iface = device.getInterfaceNumber();
        int page = device.getUsagePage() & 0xFFFF;
        int usage = device.getUsage() & 0xFFFF;
        int outReportId = GameSirCyclone2Protocol.OUT_REPORT_ID & 0xFF;

        boolean opened = false;
        try {
            opened = device.open();
            if (!opened) {
                String err = safeLastError(device, "HidDevice.open() retornou false");
                logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, outReportId,
                        "open", "FALHA_ABRIR", err, true);
                return new QueryOutcome(
                        QueryOutcomeType.OPEN_FAILED,
                        null,
                        pid,
                        "Falha ao abrir coleção HID FFF0:0040 (IF " + iface + "): " + err
                );
            }

            logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, outReportId,
                    "open", "ABERTA_COM_SUCESSO", null, false);

            // Importante no Windows (signal11/hidapi embutido no hid4java 0.8.0):
            // NÃO chamar device.read(..., 0) antes de device.write(...), pois hid_read_timeout(0)
            // inicia um ReadFile Overlapped pendente no mesmo device_handle usado pelo WriteFile sem hEvent.
            byte[] payload = GameSirCyclone2Protocol.buildWritePayload();
            int written = device.write(
                    payload,
                    GameSirCyclone2Protocol.WRITE_PAYLOAD_LENGTH,
                    GameSirCyclone2Protocol.OUT_REPORT_ID
            );
            if (written < 0) {
                String err = safeLastError(device, "write retornou " + written);
                logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, outReportId,
                        "write(0x0F 0x03, 64B)", "FALHA_ESCRITA (" + written + ")", err, true);
                return new QueryOutcome(
                        QueryOutcomeType.WRITE_FAILED,
                        null,
                        pid,
                        "Falha ao enviar comando 0x0F 0x03 (IF " + iface + "): " + err
                );
            }

            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buffer = new byte[GameSirCyclone2Protocol.FULL_REPORT_LENGTH];
            int nonStatePackets = 0;
            boolean sawIdleDongleFrame = false;

            while (System.currentTimeMillis() < deadline) {
                int remaining = (int) Math.max(1L, Math.min(SINGLE_READ_TIMEOUT_MS, deadline - System.currentTimeMillis()));
                int read = device.read(buffer, remaining);
                if (read < 0) {
                    String err = safeLastError(device, "read retornou " + read);
                    logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, GameSirCyclone2Protocol.IN_REPORT_ID,
                            "read(64B)", "FALHA_LEITURA (" + read + ")", err, true);
                    return new QueryOutcome(
                            QueryOutcomeType.READ_FAILED,
                            null,
                            pid,
                            "Falha de leitura HID na coleção FFF0:0040 (IF " + iface + "): " + err
                    );
                }
                if (read > 0) {
                    GameSirCyclone2Protocol.ParsedBattery parsed =
                            GameSirCyclone2Protocol.parseInputReport(buffer, read);
                    if (parsed != null) {
                        logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, GameSirCyclone2Protocol.IN_REPORT_ID,
                                "read(Report 0x12)",
                                String.format("OK (bytes=%d, bateria=%d%%, carregando=%s, hdr=%s, b35_36=%02X_%02X)",
                                        read,
                                        parsed.percent(),
                                        parsed.charging(),
                                        formatHexSlice(buffer, 0, Math.min(8, read)),
                                        buffer[35] & 0xFF,
                                        buffer[36] & 0xFF),
                                null,
                                false);
                        String detail = String.format(
                                "Leitura via HID FFF0:0040 (PID 0x%04X, IF %d, Report 0x12, %d bytes).",
                                pid, iface, read
                        );
                        return new QueryOutcome(QueryOutcomeType.SUCCESS, parsed, pid, detail);
                    }
                    if (GameSirCyclone2Protocol.isIdleDongleReport(buffer, read)) {
                        sawIdleDongleFrame = true;
                        break;
                    }
                    nonStatePackets++;
                }
            }

            if (sawIdleDongleFrame) {
                String idleMsg = String.format(
                        "Receptor 0x%04X:0x%04X respondeu frame 0x12 ocioso (%d bytes, bytes[1..36]=0x00, tail=0x%02X) — controle desligado ou em repouso.",
                        vid, pid, buffer.length, buffer[buffer.length - 1] & 0xFF
                );
                logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, GameSirCyclone2Protocol.IN_REPORT_ID,
                        "read(Report 0x12)",
                        "REPOUSO_CONFIRMADO (bytes=64, frame ocioso 12 00..00 CC)",
                        idleMsg,
                        false);
                return new QueryOutcome(QueryOutcomeType.CONFIRMED_ASLEEP, null, pid, idleMsg);
            }

            String timeoutSummary = "Timeout (" + timeoutMs + " ms) aguardando Input Report 0x12 após comando 0x0F 0x03"
                    + (nonStatePackets > 0 ? " (recebidos " + nonStatePackets + " pacotes não-0x12)." : " (0 bytes recebidos).");
            logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, GameSirCyclone2Protocol.IN_REPORT_ID,
                    "read(timeout=" + timeoutMs + "ms)",
                    "TIMEOUT",
                    timeoutSummary,
                    true);
            return new QueryOutcome(QueryOutcomeType.TIMEOUT, null, pid, timeoutSummary);
        } catch (Exception e) {
            logger.hidOperation(DISPLAY_NAME, vid, pid, iface, page, usage, outReportId,
                    "queryCollection", "EXCECAO", e.getMessage(), true);
            return new QueryOutcome(
                    QueryOutcomeType.READ_FAILED,
                    null,
                    pid,
                    "Exceção HID no GameSir Cyclone 2: " + e.getMessage()
            );
        } finally {
            if (opened) {
                device.close();
            }
        }
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
