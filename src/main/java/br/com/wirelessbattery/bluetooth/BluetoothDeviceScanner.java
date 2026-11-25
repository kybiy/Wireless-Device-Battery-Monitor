package br.com.wirelessbattery.bluetooth;

import java.util.List;

/**
 * Contrato para varredura passiva de dispositivos Bluetooth e seus níveis de bateria expostos pelo SO.
 */
public interface BluetoothDeviceScanner {

    /**
     * Resultado consolidado de uma varredura Bluetooth.
     */
    record ScanResult(
            boolean adapterAvailable,
            List<BluetoothDeviceInfo> devices,
            String diagnosticMessage
    ) {}

    ScanResult scan();
}
