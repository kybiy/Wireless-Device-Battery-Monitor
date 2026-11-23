package br.com.wirelessbattery.config;

/**
 * Modos de exibição dos indicadores de bateria no Windows.
 */
public enum DisplayMode {
    SYSTEM_TRAY("Bandeja do sistema (ícones individuais)"),
    TASKBAR_WIDGET("Barra de tarefas (widget próximo ao relógio)"),
    BOTH("Ambos (Bandeja + Widget na barra de tarefas)");

    private final String label;

    DisplayMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean usesSystemTray() {
        return this == SYSTEM_TRAY || this == BOTH;
    }

    public boolean usesTaskbarWidget() {
        return this == TASKBAR_WIDGET || this == BOTH;
    }

    @Override
    public String toString() {
        return label;
    }
}
