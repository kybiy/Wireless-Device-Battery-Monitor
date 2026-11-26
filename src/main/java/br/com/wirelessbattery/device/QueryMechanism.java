package br.com.wirelessbattery.device;

/**
 * Mecanismo técnico empregado para consultar a bateria do periférico.
 */
public enum QueryMechanism {
    /** Leitura exclusivamente via propriedades do sistema operacional (ex.: SetupAPI Bluetooth), zero tráfego de escrita. */
    OS_PASSIVE_PROPERTY,
    /** Consulta via HID Feature Report (Get/Set Feature) já validada para leitura somente-bateria. */
    HID_FEATURE_REPORT_QUERY,
    /** Leitura via HID Output Report de consulta de bateria comprovado por engenharia reversa do software oficial. */
    HID_OUTPUT_REPORT_VALIDATED,
    /** Leitura que exige envio de HID Output Report cujo comportamento completo de firmware ainda requer validação física. */
    HID_OUTPUT_REPORT_EXPERIMENTAL
}
