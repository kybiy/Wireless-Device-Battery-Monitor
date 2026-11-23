package br.com.wirelessbattery.model;

/**
 * Estado detalhado de conexão e leitura de um periférico sem fio.
 */
public enum ConnectionStatus {
    CONNECTED("Conectado"),
    CHARGING("Carregando"),
    ASLEEP("Em repouso"),
    DISCONNECTED("Desconectado"),
    BATTERY_UNAVAILABLE("Bateria indisponível"),
    PENDING_PROTOCOL("Protocolo não implementado"),
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
}
