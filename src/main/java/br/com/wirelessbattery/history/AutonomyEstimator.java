package br.com.wirelessbattery.history;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Calcula estimativa local de autonomia restante a partir de amostras reais de descarga.
 */
public final class AutonomyEstimator {

    public static final int MIN_SAMPLES = 3;
    public static final long MIN_SEGMENT_DURATION_MILLIS = 30L * 60L * 1000L;
    public static final int MIN_NET_DISCHARGE_PERCENT = 3;
    public static final int MAX_UPWARD_JUMP_PERCENT = 3;
    public static final long MAX_SAMPLE_GAP_MILLIS = 2L * 60L * 60L * 1000L;
    public static final double MIN_DRAIN_RATE_PER_HOUR = 0.1;
    public static final double MAX_DRAIN_RATE_PER_HOUR = 100.0;

    private AutonomyEstimator() {
    }

    /**
     * Estima autonomia restante considerando apenas amostras reais do segmento contíguo
     * de descarga mais recente terminando na última amostra.
     */
    public static AutonomyEstimate estimate(List<BatteryHistorySample> samples) {
        if (samples == null || samples.isEmpty()) {
            return AutonomyEstimate.insufficientData();
        }

        List<BatteryHistorySample> sorted = new ArrayList<>();
        for (BatteryHistorySample sample : samples) {
            if (sample != null && !sample.simulated()) {
                sorted.add(sample);
            }
        }
        if (sorted.isEmpty()) {
            return AutonomyEstimate.insufficientData();
        }

        sorted.sort(Comparator.comparingLong(BatteryHistorySample::epochMillis));

        String targetDeviceId = sorted.get(sorted.size() - 1).deviceId();
        List<BatteryHistorySample> deviceSamples = new ArrayList<>();
        for (BatteryHistorySample sample : sorted) {
            if (targetDeviceId.equals(sample.deviceId())) {
                deviceSamples.add(sample);
            }
        }

        int n = deviceSamples.size();
        BatteryHistorySample latest = deviceSamples.get(n - 1);
        if (latest.charging()) {
            return AutonomyEstimate.insufficientData("Dispositivo está carregando na leitura mais recente.");
        }

        int segmentStartIdx = n - 1;
        for (int i = n - 2; i >= 0; i--) {
            BatteryHistorySample prev = deviceSamples.get(i);
            BatteryHistorySample curr = deviceSamples.get(i + 1);

            if (prev.charging()) {
                break;
            }
            if (curr.percent() - prev.percent() > MAX_UPWARD_JUMP_PERCENT) {
                break;
            }
            if (curr.epochMillis() - prev.epochMillis() > MAX_SAMPLE_GAP_MILLIS) {
                break;
            }
            segmentStartIdx = i;
        }

        List<BatteryHistorySample> segment = deviceSamples.subList(segmentStartIdx, n);
        int count = segment.size();
        if (count < MIN_SAMPLES) {
            return AutonomyEstimate.insufficientData("Menos de " + MIN_SAMPLES + " amostras no segmento de descarga.");
        }

        BatteryHistorySample start = segment.get(0);
        BatteryHistorySample end = segment.get(count - 1);
        long durationMillis = end.epochMillis() - start.epochMillis();
        if (durationMillis < MIN_SEGMENT_DURATION_MILLIS) {
            return AutonomyEstimate.insufficientData("Segmento de descarga menor que 30 minutos.");
        }

        int netDischarge = start.percent() - end.percent();
        if (netDischarge < MIN_NET_DISCHARGE_PERCENT) {
            return AutonomyEstimate.insufficientData("Descarga líquida inferior a 3%.");
        }

        double hours = durationMillis / 3_600_000.0;
        double rawRate = netDischarge / hours;
        double drainRate = Math.max(MIN_DRAIN_RATE_PER_HOUR, Math.min(MAX_DRAIN_RATE_PER_HOUR, rawRate));
        int currentPercent = end.percent();
        long remainingMinutes = Math.round((currentPercent / drainRate) * 60.0);

        String explanation = String.format(
                Locale.US,
                "%.1f%%/h (%d%% -> %d%% in %d min, %d samples)",
                drainRate,
                start.percent(),
                end.percent(),
                durationMillis / 60_000L,
                count
        );

        return new AutonomyEstimate(true, drainRate, remainingMinutes, currentPercent, count, explanation);
    }
}
