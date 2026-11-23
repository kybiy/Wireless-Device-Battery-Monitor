package br.com.wirelessbattery;

public record BatteryStatus(String name, int percent, boolean charging, String connection) {
    public BatteryStatus {
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("Percentual invalido");
    }
}
