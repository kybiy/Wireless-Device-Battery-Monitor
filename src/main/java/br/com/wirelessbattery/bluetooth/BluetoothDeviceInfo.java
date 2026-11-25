package br.com.wirelessbattery.bluetooth;

/**
 * Metadados e estado de bateria lidos passivamente do gerenciador de dispositivos Bluetooth do Windows.
 */
public record BluetoothDeviceInfo(
        String containerId,
        String friendlyName,
        boolean connected,
        Integer batteryPercent,
        boolean bluetoothLe,
        String instanceId
) {}
