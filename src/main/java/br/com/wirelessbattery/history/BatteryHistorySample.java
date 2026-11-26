package br.com.wirelessbattery.history;

import java.time.Instant;
import java.util.Objects;

/**
 * Amostra válida de histórico de bateria persistida localmente.
 * Nunca armazena valores sentinelas negativos ou leituras de erro.
 */
public record BatteryHistorySample(
        long epochMillis,
        String deviceId,
        String displayName,
        int percent,
        boolean charging,
        String connectionLabel,
        boolean simulated
) {
    public BatteryHistorySample {
        if (epochMillis < 0) {
            throw new IllegalArgumentException("epochMillis inválido: " + epochMillis);
        }
        Objects.requireNonNull(deviceId, "deviceId não pode ser nulo");
        if (deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId não pode ser vazio");
        }
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percent deve estar entre 0 e 100: " + percent);
        }
        displayName = (displayName == null || displayName.isBlank()) ? deviceId : displayName;
        connectionLabel = connectionLabel == null ? "" : connectionLabel;
    }

    public Instant timestamp() {
        return Instant.ofEpochMilli(epochMillis);
    }
}
