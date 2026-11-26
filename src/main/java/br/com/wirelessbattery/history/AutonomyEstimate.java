package br.com.wirelessbattery.history;

/**
 * Resultado de estimativa local de autonomia baseada em segmento recente de descarga.
 */
public record AutonomyEstimate(
        boolean sufficientData,
        double drainPercentPerHour,
        long estimatedRemainingMinutes,
        int currentPercent,
        int sampleCountUsed,
        String explanation
) {
    public AutonomyEstimate {
        explanation = explanation == null ? "" : explanation;
    }

    public static AutonomyEstimate insufficientData() {
        return insufficientData("Dados insuficientes para estimar autonomia.");
    }

    public static AutonomyEstimate insufficientData(String explanation) {
        return new AutonomyEstimate(false, 0.0, -1L, -1, 0, explanation);
    }
}
