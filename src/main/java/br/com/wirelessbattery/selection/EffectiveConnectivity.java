package br.com.wirelessbattery.selection;

/**
 * Estado efetivo de conectividade de um dispositivo para seleção e estabilização por categoria.
 */
public enum EffectiveConnectivity {
    CONNECTED_WITH_BATTERY,
    CONNECTED_BATTERY_UNAVAILABLE,
    TRANSIENT_FAILURE_HOLD,
    RECEIVER_ONLY_OFF,
    DISCONNECTED;

    public boolean isEffectivelyConnected() {
        return this == CONNECTED_WITH_BATTERY
                || this == CONNECTED_BATTERY_UNAVAILABLE
                || this == TRANSIENT_FAILURE_HOLD;
    }
}
