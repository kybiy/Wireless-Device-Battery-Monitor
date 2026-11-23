package br.com.wirelessbattery.model;

/**
 * Categoria do periférico sem fio para seleção de ícones e agrupamento visual.
 */
public enum DeviceType {
    MOUSE("Mouse"),
    HEADSET("Headset"),
    GAMEPAD("Controle"),
    OTHER("Periférico");

    private final String label;

    DeviceType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
