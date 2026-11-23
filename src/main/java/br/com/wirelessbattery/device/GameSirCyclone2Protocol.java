package br.com.wirelessbattery.device;

import java.util.Arrays;

/**
 * Protocolo estritamente somente-leitura para consulta de bateria do GameSir Cyclone 2
 * (modo XInput via receptor 2.4 GHz ou cabo USB).
 *
 * Confirmado pelas especificações de protocolo de {@code pedrosegato/gamesir-reverse-engineering}
 * (engenharia reversa do GameSir Connect 1.16.7) e {@code vdemonchy/cyclone2-linux}:
 * - Coleção HID proprietária: Usage Page {@code 0xFFF0}, Usage {@code 0x0040}
 * - Output Report {@code 0x0F 0xF2 0x00} (64 bytes): Heartbeat oficial somente-leitura do GameSir Connect,
 *   que instrui o controle a transmitir relatórios de estado {@code 0x12} por ~1 segundo.
 *   (Não utiliza o comando {@code 0x03}, que corresponde a escrita de perfil {@code WriteProfile}.)
 * - Input Report {@code 0x12} (>= 37 bytes):
 *   - {@code data[35] != 0}: cabo conectado / carregando
 *   - {@code data[36]}: porcentagem real da bateria ({@code 0..100})
 * - Frames de resposta/evento {@code 0x10} são ignorados durante a leitura.
 *
 * Nenhum comando de perfil, firmware, vibração, iluminação ou remapeamento é enviado.
 */
public final class GameSirCyclone2Protocol {
    public static final byte OUT_REPORT_ID = 0x0F;
    public static final byte CMD_STREAM_STATE = 0x03;
    public static final int IN_REPORT_ID = 0x12;
    public static final int EVENT_REPORT_ID = 0x10;
    public static final int FULL_REPORT_LENGTH = 64;
    public static final int WRITE_PAYLOAD_LENGTH = 63;
    public static final int MIN_REPLY_LENGTH = 37;
    public static final int CHARGING_INDEX = 35;
    public static final int BATTERY_INDEX = 36;

    private GameSirCyclone2Protocol() {}

    public record ParsedBattery(int percent, boolean charging) {
        public ParsedBattery {
            if (percent < 0 || percent > 100) {
                throw new IllegalArgumentException("Percentual fora da faixa 0..100: " + percent);
            }
        }
    }

    /**
     * Monta o frame completo de 64 bytes ({@code 0x0F 0x03 ...}).
     */
    public static byte[] buildFullStateQuery() {
        byte[] frame = new byte[FULL_REPORT_LENGTH];
        frame[0] = OUT_REPORT_ID;
        frame[1] = CMD_STREAM_STATE;
        return frame;
    }

    /**
     * Monta o payload de 63 bytes (sem o Report ID {@code 0x0F} no índice 0) para
     * {@code HidDevice.write(payload, 63, OUT_REPORT_ID)} do hid4java.
     */
    public static byte[] buildWritePayload() {
        byte[] full = buildFullStateQuery();
        return Arrays.copyOfRange(full, 1, full.length);
    }

    /**
     * Verifica se o pacote {@code 0x12} recebido é o frame ocioso do receptor 2.4 GHz
     * ({@code 12 00 00 ... 00 CC}), enviado pelo dongle {@code 0x3537:0x0575} quando o controle
     * está desligado ou em repouso (bytes 1..36 todos zerados, sem analógicos em 0x80 nem D-pad em 0x0F).
     */
    public static boolean isIdleDongleReport(byte[] data, int length) {
        if (data == null || length <= 0) {
            return false;
        }
        int available = Math.min(length, data.length);
        if (available < MIN_REPLY_LENGTH) {
            return false;
        }
        if ((data[0] & 0xFF) != IN_REPORT_ID) {
            return false;
        }
        for (int i = 1; i <= BATTERY_INDEX; i++) {
            if (data[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Interpreta um Input Report recebido da coleção {@code FFF0:0040}.
     *
     * @return {@link ParsedBattery} se for um relatório {@code 0x12} ativo válido com percentual 1..100;
     *         {@code null} para relatórios de evento ({@code 0x10}), frames ociosos do dongle
     *         ({@code bytes[1..36] == 0}), curtos ou fora da faixa.
     */
    public static ParsedBattery parseInputReport(byte[] data, int length) {
        if (data == null || length <= 0) {
            return null;
        }
        int available = Math.min(length, data.length);
        if (available < MIN_REPLY_LENGTH) {
            return null;
        }
        int reportId = data[0] & 0xFF;
        if (reportId != IN_REPORT_ID) {
            return null;
        }
        if (isIdleDongleReport(data, length)) {
            return null;
        }
        int rawBattery = data[BATTERY_INDEX] & 0xFF;
        if (rawBattery <= 0 || rawBattery > 100) {
            return null;
        }
        boolean charging = (data[CHARGING_INDEX] & 0xFF) == 1;
        return new ParsedBattery(rawBattery, charging);
    }
}
