package br.com.wirelessbattery.model;

/**
 * Identifica estritamente a origem de uma leitura de bateria.
 * Leituras simuladas nunca devem ser apresentadas como leituras reais.
 */
public enum DataSourceType {
    REAL("Leitura real"),
    SIMULATED("Simulação");

    private final String label;

    DataSourceType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
