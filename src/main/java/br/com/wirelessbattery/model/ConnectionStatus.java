package br.com.wirelessbattery.model;

/**
 * Estado detalhado de conexão e leitura de um periférico sem fio.
 */
public enum ConnectionStatus {
    CONNECTED("Conectado"),
    CHARGING("Carregando"),
    ASLEEP("Em repouso confirmado"),
    NO_RESPONSE("Receptor conectado, sem resposta"),
    OPEN_FAILED("Falha ao abrir interface HID"),
    READ_FAILED("Falha de leitura"),
    TIMEOUT("Timeout de leitura"),
    DISCONNECTED("Desconectado"),
    BATTERY_UNAVAILABLE("Leitura indisponível"),
    PENDING_PROTOCOL("Leitura indisponível"),
    ERROR("Erro de leitura");

    private final String label;

    ConnectionStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
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
                || this == BATTERY_UNAVAILABLE;
    }
}
