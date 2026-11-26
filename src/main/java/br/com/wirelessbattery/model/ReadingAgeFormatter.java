package br.com.wirelessbattery.model;

import br.com.wirelessbattery.i18n.I18n;
import java.time.Duration;
import java.time.Instant;

/**
 * Formata a idade real de leituras válidas de bateria (nunca o horário de tentativas com erro).
 */
public final class ReadingAgeFormatter {

    private ReadingAgeFormatter() {
    }

    public static String formatRelativeAge(Instant readingInstant, Instant now, I18n i18n, boolean lastKnownPrefix) {
        if (readingInstant == null) {
            return "";
        }
        Instant refNow = now != null ? now : Instant.now();
        long seconds = Math.max(0L, Duration.between(readingInstant, refNow).getSeconds());
        boolean pt = i18n == null || i18n.isPortuguese();

        if (seconds < 15L) {
            if (lastKnownPrefix) {
                return pt ? "última leitura agora" : "last reading just now";
            }
            return i18n != null ? i18n.t("age.updatedNow") : "Atualizado agora";
        }
        if (seconds < 60L) {
            if (lastKnownPrefix) {
                return i18n != null
                        ? i18n.t("age.lastReadingSeconds", seconds)
                        : "última leitura há " + seconds + " s";
            }
            return i18n != null
                    ? i18n.t("age.updatedSeconds", seconds)
                    : "Atualizado há " + seconds + " segundos";
        }
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            if (lastKnownPrefix) {
                return i18n != null
                        ? i18n.t("age.lastReadingMinutes", minutes)
                        : "última leitura há " + minutes + " min";
            }
            return i18n != null
                    ? i18n.t("age.updatedMinutes", minutes)
                    : "Atualizado há " + minutes + " min";
        }
        long hours = minutes / 60L;
        if (hours < 48L) {
            if (lastKnownPrefix) {
                return i18n != null
                        ? i18n.t("age.lastReadingHours", hours)
                        : "última leitura há " + hours + " h";
            }
            return i18n != null
                    ? i18n.t("age.updatedHours", hours)
                    : "Atualizado há " + hours + " h";
        }
        long days = hours / 24L;
        if (lastKnownPrefix) {
            return i18n != null
                    ? i18n.t("age.lastReadingDays", days)
                    : "última leitura há " + days + " d";
        }
        return i18n != null
                ? i18n.t("age.updatedDays", days)
                : "Atualizado há " + days + " d";
    }
}
