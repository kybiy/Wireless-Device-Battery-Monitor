package br.com.wirelessbattery.model;

import br.com.wirelessbattery.i18n.I18n;

/**
 * Estado detalhado de conexão e leitura de um periférico sem fio.
 */
public enum ConnectionStatus {
    CONNECTED("Conectado", "status.connected"),
    CHARGING("Carregando", "status.charging"),
    ASLEEP("Em repouso confirmado", "status.asleep"),
    NO_RESPONSE("Receptor conectado, sem resposta", "status.noResponse"),
    OPEN_FAILED("Falha ao abrir interface HID", "status.openFailed"),
    READ_FAILED("Falha de leitura", "status.readFailed"),
    TIMEOUT("Timeout de leitura", "status.timeout"),
    DISCONNECTED("Desconectado", "status.disconnected"),
    BATTERY_UNAVAILABLE("Leitura indisponível", "status.batteryUnavailable"),
    PENDING_PROTOCOL("Leitura indisponível", "status.pendingProtocol"),
    READ_ONLY_BLOCKED("Bloqueado (modo somente leitura)", "status.readOnlyBlocked"),
    MONITORING_DISABLED("Monitoramento desativado", "status.monitoringDisabled"),
    ERROR("Erro de leitura", "status.error");

    private final String label;
    private final String i18nKey;

    ConnectionStatus(String label, String i18nKey) {
        this.label = label;
        this.i18nKey = i18nKey;
    }

    public String getLabel() {
        return label;
    }

    public String getI18nKey() {
        return i18nKey;
    }

    public String getLocalizedLabel(I18n i18n) {
        if (i18n == null) {
            return label;
        }
        String translated = i18n.t(i18nKey);
        return translated.startsWith("!") ? label : translated;
    }

    public boolean isOnline() {
        return this == CONNECTED || this == CHARGING;
    }

    /**
     * Indica estados em que o receptor/dispositivo físico está presente, mas sem leitura atual de bateria.
     */
    public boolean isReceiverDetectedState() {
        return isOnline()
                || this == ASLEEP
                || this == NO_RESPONSE
                || this == OPEN_FAILED
                || this == READ_FAILED
                || this == TIMEOUT
                || this == BATTERY_UNAVAILABLE
                || this == READ_ONLY_BLOCKED;
    }
}
