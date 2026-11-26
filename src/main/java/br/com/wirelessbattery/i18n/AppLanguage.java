package br.com.wirelessbattery.i18n;

import java.util.Locale;

/**
 * Idiomas suportados pela interface e mensagens do aplicativo.
 */
public enum AppLanguage {
    SYSTEM("system"),
    PT_BR("pt-BR"),
    EN_US("en-US");

    private final String code;

    AppLanguage(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * Resolve o código efetivo para carregamento do {@code ResourceBundle}.
     */
    public Locale resolveLocale() {
        if (this == PT_BR) {
            return Locale.forLanguageTag("pt-BR");
        }
        if (this == EN_US) {
            return Locale.US;
        }
        Locale sys = Locale.getDefault();
        if (sys != null && "pt".equalsIgnoreCase(sys.getLanguage())) {
            return Locale.forLanguageTag("pt-BR");
        }
        return Locale.US;
    }

    public static AppLanguage fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return SYSTEM;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("pt-br") || normalized.equals("pt_br") || normalized.equals("pt")) {
            return PT_BR;
        }
        if (normalized.equals("en-us") || normalized.equals("en_us") || normalized.equals("en")) {
            return EN_US;
        }
        return SYSTEM;
    }
}
