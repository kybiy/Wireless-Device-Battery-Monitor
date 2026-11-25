package br.com.wirelessbattery.simulation;

/**
 * Cenários pré-configurados de simulação para validar seleção de headsets,
 * saída padrão de áudio, estados de falha e Bluetooth sem hardware conectado.
 */
public enum SimulationScenario {
    ONLY_AKKO_CONNECTED("1. Somente Akko conectado"),
    ONLY_QCY_CONNECTED("2. Somente QCY conectado"),
    BOTH_AKKO_DEFAULT_AUDIO("3. Ambos conectados (Akko saída padrão)"),
    BOTH_QCY_DEFAULT_AUDIO("4. Ambos conectados (QCY saída padrão)"),
    NO_HEADSET_CONNECTED("5. Nenhum fone conectado"),
    AKKO_DONGLE_PRESENT_HEADSET_OFF("6. Dongle Akko presente, headset desligado"),
    QCY_CONNECTED_BATTERY_UNAVAILABLE("7. QCY conectado com bateria indisponível"),
    SWITCH_AKKO_TO_QCY("8. Troca automática do Akko para o QCY"),
    SWITCH_QCY_TO_AKKO("9. Troca automática do QCY para o Akko"),
    TEMPORARY_READ_FAILURE("10. Falha temporária de leitura"),
    BLUETOOTH_DISCONNECT_AND_RECONNECT("11. Desconexão e reconexão Bluetooth"),
    BLUETOOTH_ADAPTER_MISSING("12. Adaptador Bluetooth ausente ou desativado");

    private final String label;

    SimulationScenario(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
