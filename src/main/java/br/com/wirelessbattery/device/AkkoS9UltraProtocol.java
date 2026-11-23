package br.com.wirelessbattery.device;

import java.util.Arrays;

/**
 * Protocolo somente-leitura para consulta de bateria e estado de conexão do headset
 * Akko S9 Ultra (VID {@code 0x36EB}, PID {@code 0x1087}) via receptor USB 2.4 GHz.
 *
 * Especificação comprovada pela análise estática de {@code HeadsetDataService::getBatteryInfoInternal}
 * e {@code HeadsetController} ({@code controller_id = 2}) do software oficial Akko Sound Studio
 * (V1.6 / release 2026-03-17) e validada no hardware real:
 * <ul>
 *   <li>Interface USB 3, Coleção {@code Col02}: Usage Page {@code 0x008C}, Usage {@code 0x0001}</li>
 *   <li>Tamanho dos relatórios HID: Input = 64 bytes, Output = 64 bytes, Feature = 0 bytes</li>
 *   <li>Report ID: {@code 0x02} (1 byte de Report ID + 63 bytes de payload = 64 bytes)</li>
 *   <li>Checksum nos bytes {@code [62..63]} (big-endian): CRC-16 refletido ({@code init = 0xFFFF},
 *       {@code poly = 0x8005}, {@code xorOut = 0x0000}) calculado sobre os bytes {@code [0..61]}</li>
 *   <li>Consulta de estado de conexão ({@code 0x10}):
 *       Request {@code 02 10 01 00 ... 00 4B 43} → Response {@code 02 10 01 <status> <connected> ... <CRC>}
 *       onde {@code status == 0x00} e {@code connected == 0x01} indicam headset ligado e pareado
 *       (ex.: {@code 02 10 01 00 01 00 ... 00 71 E1})</li>
 *   <li>Consulta de nível de bateria ({@code 0x12}):
 *       Request {@code 02 12 01 00 ... 00 71 12} → Response {@code 02 12 01 <status> <battery> ... <CRC>}
 *       onde {@code status == 0x00} e {@code battery} em {@code 1..100} representam a porcentagem real
 *       (ex.: {@code 0x2F = 47%}, frame {@code 02 12 01 00 2F 00 ... 00 55 16}), ou {@code 0xBC (188)}
 *       como sentinela de carregamento ({@code "Charging"})</li>
 * </ul>
 *
 * Segurança: esta classe implementa exclusivamente os frames de leitura de conexão ({@code 0x10}) e
 * bateria ({@code 0x12}). Nenhum comando de escrita de áudio, equalizador, 7.1, tempo de suspensão ou
 * firmware é construído ou enviado.
 */
public final class AkkoS9UltraProtocol {
    public static final byte REPORT_ID = 0x02;
    public static final byte CMD_CONNECTION_STATE = 0x10;
    public static final byte CMD_BATTERY_LEVEL = 0x12;
    public static final byte PAYLOAD_LENGTH_1 = 0x01;

    public static final int FULL_REPORT_LENGTH = 64;
    public static final int WRITE_PAYLOAD_LENGTH = 63;
    public static final int CRC_DATA_LENGTH = 62;
    public static final int CRC_HIGH_INDEX = 62;
    public static final int CRC_LOW_INDEX = 63;

    public static final int CRC_INIT = 0xFFFF;
    public static final int CRC_POLY_REFLECTED = 0x8005;

    public static final int STATUS_OK = 0x00;
    public static final int STATUS_OFFLINE = 0x01;
    public static final int STATUS_BUSY = 0x02;

    public static final int BATTERY_CHARGING_SENTINEL = 0xBC;

    private AkkoS9UltraProtocol() {}

    public enum ReplyType {
        BATTERY_OK,
        HEADSET_ASLEEP,
        CONNECTION_ALIVE,
        DEVICE_BUSY,
        CRC_MISMATCH,
        UNRELATED_REPORT
    }

    public record ParsedBattery(int percent, boolean charging) {
        public ParsedBattery {
            if (percent < 0 || percent > 100) {
                throw new IllegalArgumentException("Percentual fora da faixa 0..100: " + percent);
            }
        }
    }

    public record ParsedReply(
            ReplyType type,
            ParsedBattery battery,
            int command,
            int status,
            int rawValue,
            boolean crcValid
    ) {}

    /**
     * Calcula o CRC-16 refletido ({@code init = 0xFFFF}, {@code poly = 0x8005}) utilizado pelo
     * receptor {@code 0x36EB:0x1087} sobre os primeiros 62 bytes do relatório ({@code [0..61]}).
     */
    public static int computeCrc16(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("Intervalo inválido para cálculo de CRC-16");
        }
        int crc = CRC_INIT;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF);
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ CRC_POLY_REFLECTED;
                } else {
                    crc = (crc >>> 1);
                }
            }
        }
        return crc & 0xFFFF;
    }

    /**
     * Verifica se um relatório completo de 64 bytes possui CRC-16 válido nos bytes {@code [62..63]}.
     */
    public static boolean verifyCrc16(byte[] report64, int length) {
        if (report64 == null || length < FULL_REPORT_LENGTH || report64.length < FULL_REPORT_LENGTH) {
            return false;
        }
        int expected = computeCrc16(report64, 0, CRC_DATA_LENGTH);
        int actual = ((report64[CRC_HIGH_INDEX] & 0xFF) << 8) | (report64[CRC_LOW_INDEX] & 0xFF);
        return expected == actual;
    }

    /**
     * Constrói um frame completo de 64 bytes (com Report ID {@code 0x02} no byte 0 e CRC-16 nos bytes 62..63).
     */
    public static byte[] buildFullCommandFrame(int command) {
        byte[] frame = new byte[FULL_REPORT_LENGTH];
        frame[0] = REPORT_ID;
        frame[1] = (byte) (command & 0xFF);
        frame[2] = PAYLOAD_LENGTH_1;
        frame[3] = 0x00;
        int crc = computeCrc16(frame, 0, CRC_DATA_LENGTH);
        frame[CRC_HIGH_INDEX] = (byte) ((crc >>> 8) & 0xFF);
        frame[CRC_LOW_INDEX] = (byte) (crc & 0xFF);
        return frame;
    }

    /**
     * Constrói o frame completo de 64 bytes para consulta somente-leitura de bateria ({@code 0x12}):
     * {@code 02 12 01 00 ... 00 71 12}.
     */
    public static byte[] buildFullBatteryQuery() {
        return buildFullCommandFrame(CMD_BATTERY_LEVEL & 0xFF);
    }

    /**
     * Constrói o payload de 63 bytes (sem o Report ID {@code 0x02} no índice 0) pronto para
     * {@code HidDevice.write(payload, 63, REPORT_ID)} do {@code hid4java}.
     */
    public static byte[] buildBatteryWritePayload() {
        byte[] full = buildFullBatteryQuery();
        return Arrays.copyOfRange(full, 1, full.length);
    }

    /**
     * Constrói o frame completo de 64 bytes para consulta somente-leitura de conexão ({@code 0x10}):
     * {@code 02 10 01 00 ... 00 4B 43}.
     */
    public static byte[] buildFullConnectionQuery() {
        return buildFullCommandFrame(CMD_CONNECTION_STATE & 0xFF);
    }

    /**
     * Constrói o payload de 63 bytes de consulta de conexão para {@code HidDevice.write(payload, 63, REPORT_ID)}.
     */
    public static byte[] buildConnectionWritePayload() {
        byte[] full = buildFullConnectionQuery();
        return Arrays.copyOfRange(full, 1, full.length);
    }

    /**
     * Analisa detalhadamente um Input Report de 64 bytes recebido da coleção {@code 0x008C:0x0001}.
     */
    public static ParsedReply inspectInputReport(byte[] data, int length) {
        if (data == null || length < 5 || data.length < 5) {
            return new ParsedReply(ReplyType.UNRELATED_REPORT, null, -1, -1, -1, false);
        }
        int reportId = data[0] & 0xFF;
        int cmd = data[1] & 0xFF;
        int payloadLen = data[2] & 0xFF;
        int status = data[3] & 0xFF;
        int rawValue = data[4] & 0xFF;

        if (reportId != (REPORT_ID & 0xFF) || payloadLen != (PAYLOAD_LENGTH_1 & 0xFF)) {
            return new ParsedReply(ReplyType.UNRELATED_REPORT, null, cmd, status, rawValue, false);
        }

        int available = Math.min(length, data.length);
        boolean crcValid = available >= FULL_REPORT_LENGTH && verifyCrc16(data, available);
        if (available >= FULL_REPORT_LENGTH && !crcValid) {
            return new ParsedReply(ReplyType.CRC_MISMATCH, null, cmd, status, rawValue, false);
        }

        if (cmd == (CMD_CONNECTION_STATE & 0xFF)) {
            if (status == STATUS_OFFLINE || (status == STATUS_OK && rawValue == 0)) {
                return new ParsedReply(ReplyType.HEADSET_ASLEEP, null, cmd, status, rawValue, crcValid);
            }
            if (status == STATUS_BUSY) {
                return new ParsedReply(ReplyType.DEVICE_BUSY, null, cmd, status, rawValue, crcValid);
            }
            if (status == STATUS_OK && rawValue == 1) {
                return new ParsedReply(ReplyType.CONNECTION_ALIVE, null, cmd, status, rawValue, crcValid);
            }
            return new ParsedReply(ReplyType.UNRELATED_REPORT, null, cmd, status, rawValue, crcValid);
        }

        if (cmd == (CMD_BATTERY_LEVEL & 0xFF)) {
            if (status == STATUS_OFFLINE || (status == STATUS_OK && rawValue == 0)) {
                return new ParsedReply(ReplyType.HEADSET_ASLEEP, null, cmd, status, rawValue, crcValid);
            }
            if (status == STATUS_BUSY) {
                return new ParsedReply(ReplyType.DEVICE_BUSY, null, cmd, status, rawValue, crcValid);
            }
            if (status == STATUS_OK) {
                if (rawValue == BATTERY_CHARGING_SENTINEL) {
                    ParsedBattery pb = new ParsedBattery(100, true);
                    return new ParsedReply(ReplyType.BATTERY_OK, pb, cmd, status, rawValue, crcValid);
                }
                if (rawValue >= 1 && rawValue <= 100) {
                    ParsedBattery pb = new ParsedBattery(rawValue, false);
                    return new ParsedReply(ReplyType.BATTERY_OK, pb, cmd, status, rawValue, crcValid);
                }
            }
            return new ParsedReply(ReplyType.UNRELATED_REPORT, null, cmd, status, rawValue, crcValid);
        }

        return new ParsedReply(ReplyType.UNRELATED_REPORT, null, cmd, status, rawValue, crcValid);
    }

    /**
     * Faz o parse direto de um Input Report de bateria ({@code 0x12}).
     *
     * @return {@link ParsedBattery} se for uma resposta {@code 02 12 01 00 <battery>} válida com CRC correto,
     *         ou {@code null} caso contrário.
     */
    public static ParsedBattery parseInputReport(byte[] data, int length) {
        ParsedReply reply = inspectInputReport(data, length);
        return reply.type() == ReplyType.BATTERY_OK ? reply.battery() : null;
    }
}
