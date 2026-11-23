package br.com.wirelessbattery;

/** Port do algoritmo de protocolo em src/drivers/areson.cpp (projeto original GPL-3.0). */
public final class AresonProtocol {
    private AresonProtocol() {}
    public static boolean supports(int vid, int pid) {
        return vid == 0x25A7 && (pid == 0xFA07 || pid == 0xFA08 || pid == 0xFA7B || pid == 0xFA7C);
    }
    public static byte[] request() {
        byte[] frame = new byte[17];
        frame[0] = 0x08;
        frame[1] = 0x04;
        int sum = 0;
        for (int i=0; i<16; i++) sum += frame[i] & 0xff;
        frame[16] = (byte)((0x55 - sum) & 0xff);
        return frame;
    }
    /** Retorna null se o pacote nao corresponde a uma resposta valida. */
    public static BatteryStatus parse(int pid, byte[] data, int len) {
        if (len < 17 || data == null) return null;
        int sum = 0;
        for (int i=0; i<17; i++) sum += data[i] & 0xff;
        boolean direct = pid == 0xFA7B || pid == 0xFA7C;
        int raw = data[6] & 0xff;
        if ((sum & 0xff) != 0x55 || (data[0] & 0xff) != 0x09 ||
            (data[1] & 0xff) != 0x04 || data[2] != 0 || raw > (direct ? 100 : 10)) return null;
        return new BatteryStatus(pid == 0xFA7C ? "Fallen Pantera Pro Wireless" : "Mouse Areson/Redragon",
            direct ? raw : raw * 10, data[7] != 0, "Receptor USB 2.4 GHz");
    }
}
