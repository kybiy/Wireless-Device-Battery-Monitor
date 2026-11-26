package br.com.wirelessbattery.history;

import java.time.Instant;
import java.util.List;

/**
 * Estatísticas agregadas sobre o histórico armazenado de um dispositivo.
 */
public record BatteryHistoryStats(
        int sampleCount,
        int minPercent,
        int maxPercent,
        long lastFullChargeEpochMillis,
        List<Instant> chargingStarts
) {
    public BatteryHistoryStats {
        sampleCount = Math.max(0, sampleCount);
        chargingStarts = chargingStarts == null ? List.of() : List.copyOf(chargingStarts);
    }

    public static BatteryHistoryStats empty() {
        return new BatteryHistoryStats(0, 0, 0, 0L, List.of());
    }

    public boolean hasSamples() {
        return sampleCount > 0;
    }

    public boolean hasLastFullCharge() {
        return lastFullChargeEpochMillis > 0L;
    }
}
