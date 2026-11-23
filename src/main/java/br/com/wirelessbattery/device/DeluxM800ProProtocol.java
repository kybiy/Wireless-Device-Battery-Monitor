package br.com.wirelessbattery.device;

import java.util.Arrays;

/**
 * Protocolo somente-leitura de consulta de bateria do Delux M800 Pro PAW3395.
 *
 * Baseado exclusivamente na função {@code initial_query} de
 * {@code https://github.com/xb-bx/m800-pro-driver/blob/HEAD/main.c}:
 * - SET_REPORT (Feature Report ID 0x0C, 33 bytes): {0x0c, 0x01, 0x20, 0x00, packetId, 0x00...}
 * - GET_REPORT (Feature Report ID 0x0C, 33 bytes): buf[18] = carga, buf[19] == 1 indica carregando.
 *
 * Nenhum comando de configuração (DPI, polling rate, remapeamento ou firmware) é implementado aqui.
 */
public final class DeluxM800ProProtocol {
    public static final byte FEATURE_REPORT_ID = 0x0C;
    public static final int FULL_REPORT_LENGTH = 33;
    public static final int PAYLOAD_LENGTH = 32;
    public static final int BATTERY_CHARGING_SENTINEL = 0x65;

    private DeluxM800ProProtocol() {}

    public record ParsedBattery(int percent, boolean charging) {
        public ParsedBattery {
            if (percent < 0 || percent > 100) {
                throw new IllegalArgumentException("Percentual fora da faixa 0..100: " + percent);
            }
        }
    }

    /**
     * Monta o frame completo de 33 bytes (incluindo Report ID 0x0C no índice 0).
     */
    public static byte[] buildFullBatteryQuery(int packetId) {
        byte[] frame = new byte[FULL_REPORT_LENGTH];
        frame[0] = FEATURE_REPORT_ID;
        frame[1] = 0x01;
        frame[2] = 0x20;
        frame[3] = 0x00;
        frame[4] = (byte) (packetId & 0xFF);
        return frame;
    }

    /**
     * Monta apenas o payload de 32 bytes (sem o Report ID), pronto para
     * {@code HidDevice.sendFeatureReport(payload, FEATURE_REPORT_ID)} do hid4java.
     */
    public static byte[] buildFeaturePayload(int packetId) {
        byte[] full = buildFullBatteryQuery(packetId);
        return Arrays.copyOfRange(full, 1, full.length);
    }

    /**
     * Faz o parse do payload de 32 bytes devolvido por {@code HidDevice.getFeatureReport(buf, 0x0C)}
     * do {@code hid4java} no Windows/HIDAPI.
     *
     * No {@code hid4java} 0.8.0, {@code HidApi.getFeatureReport} sempre remove o byte 0 (Report ID 0x0C)
     * ao copiar {@code report.buffer[1..]} para {@code buf[0..]} e devolve {@code res} (33 ou 32 bytes).
     * Assim, no buffer recebido pelo Java:
     * - {@code payload[17]} corresponde a {@code buf[18]} (carga de bateria em {@code main.c})
     * - {@code payload[18]} corresponde a {@code buf[19]} (flag de carregamento {@code == 1})
     */
    public static ParsedBattery parseHid4JavaFeaturePayload(byte[] payload, int reportedLength) {
        if (payload == null || reportedLength <= 0) {
            return null;
        }
        int available = Math.min(reportedLength, payload.length);
        if (available < 19) {
            return null;
        }
        return decodeBatteryBytes(payload[17] & 0xFF, payload[18] & 0xFF);
    }

    /**
     * Faz o parse da resposta de {@code getFeatureReport}.
     * Aceita tanto o buffer completo de 33 bytes com Report ID 0x0C no índice 0 (como no libusb)
     * quanto o buffer de 32 bytes sem o Report ID (quando o hid4java omite o byte 0).
     *
     * @return {@link ParsedBattery} se a resposta contiver leitura válida, ou {@code null} caso contrário.
     */
    public static ParsedBattery parseFeatureResponse(byte[] buffer, int length) {
        if (buffer == null || length <= 0) {
            return null;
        }
        int available = Math.min(length, buffer.length);
        if (available < 19) {
            return null;
        }
        boolean hasReportIdPrefix = (buffer[0] & 0xFF) == (FEATURE_REPORT_ID & 0xFF) && available >= 20;
        int batteryIndex = hasReportIdPrefix ? 18 : 17;
        int chargingIndex = hasReportIdPrefix ? 19 : 18;

        if (available <= chargingIndex) {
            return null;
        }

        return decodeBatteryBytes(buffer[batteryIndex] & 0xFF, buffer[chargingIndex] & 0xFF);
    }

    private static ParsedBattery decodeBatteryBytes(int rawBattery, int rawCharging) {
        if (rawBattery == 0) {
            return null;
        }
        boolean charging = (rawCharging == 1) || (rawBattery == BATTERY_CHARGING_SENTINEL);
        int percent = rawBattery == BATTERY_CHARGING_SENTINEL ? 100 : rawBattery;
        if (percent < 0 || percent > 100) {
            return null;
        }
        return new ParsedBattery(percent, charging);
    }
}
