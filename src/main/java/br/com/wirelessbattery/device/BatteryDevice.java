package br.com.wirelessbattery.device;

import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.util.List;
import org.hid4java.HidDevice;

/**
 * Interface padrão para todos os drivers de dispositivos de bateria.
 */
public interface BatteryDevice {

    /**
     * Identificador único interno do dispositivo/driver.
     */
    String getId();

    /**
     * Nome amigável exibido na interface.
     */
    String getDisplayName();

    /**
     * Tipo do periférico (Mouse, Headset, Outro).
     */
    DeviceType getDeviceType();

    /**
     * Descrição curta da conexão (ex.: "2.4 GHz").
     */
    String getConnectionDescription();

    /**
     * Origem das leituras fornecidas por este dispositivo (REAL ou SIMULATED).
     */
    DataSourceType getSourceType();

    /**
     * Indica se o driver é opcional (exibido na tela principal somente quando o hardware
     * correspondente for detectado).
     */
    default boolean isOptional() {
        return false;
    }

    /**
     * Indica se o protocolo de leitura de bateria já está implementado no driver.
     */
    default boolean supportsBatteryQuery() {
        return true;
    }

    /**
     * Interface USB/HID esperada pelo protocolo, ou -1 se não aplicável.
     */
    default int getExpectedInterface() {
        return -1;
    }

    /**
     * Verifica de forma segura se um par VID/PID pertence a este driver.
     */
    default boolean matchesVidPid(int vid, int pid) {
        return false;
    }

    /**
     * Verifica passivamente se o receptor ou dispositivo está presente na lista HID atual.
     */
    default boolean isHardwarePresent(List<HidDevice> attachedDevices) {
        if (attachedDevices == null) {
            return false;
        }
        for (HidDevice d : attachedDevices) {
            if (matchesVidPid(d.getVendorId() & 0xFFFF, d.getProductId() & 0xFFFF)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Consulta o estado atual da bateria sem bloquear a EDT.
     * Nunca deve retornar null; quando indisponível, retornar status ASLEEP, DISCONNECTED ou PENDING_PROTOCOL.
     */
    DeviceReading readBattery();

    /**
     * Consulta o estado da bateria reutilizando a enumeração HID atual do ciclo.
     */
    default DeviceReading readBattery(List<HidDevice> attachedDevices) {
        return readBattery();
    }
}
