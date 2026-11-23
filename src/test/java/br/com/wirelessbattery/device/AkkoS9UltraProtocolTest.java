package br.com.wirelessbattery.device;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.diagnostics.AkkoPcapngAnalyzer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class AkkoS9UltraProtocolTest {

    @Test
    void buildsOfficialConnectionAndBatteryQueriesWithExactCrc16() {
        byte[] connQuery = AkkoS9UltraProtocol.buildFullConnectionQuery();
        assertEquals(64, connQuery.length);
        assertEquals(0x02, connQuery[0] & 0xFF);
        assertEquals(0x10, connQuery[1] & 0xFF);
        assertEquals(0x01, connQuery[2] & 0xFF);
        assertEquals(0x00, connQuery[3] & 0xFF);
        assertEquals(0x4B, connQuery[62] & 0xFF);
        assertEquals(0x43, connQuery[63] & 0xFF);
        assertTrue(AkkoS9UltraProtocol.verifyCrc16(connQuery, connQuery.length));

        byte[] battQuery = AkkoS9UltraProtocol.buildFullBatteryQuery();
        assertEquals(64, battQuery.length);
        assertEquals(0x02, battQuery[0] & 0xFF);
        assertEquals(0x12, battQuery[1] & 0xFF);
        assertEquals(0x01, battQuery[2] & 0xFF);
        assertEquals(0x00, battQuery[3] & 0xFF);
        assertEquals(0x71, battQuery[62] & 0xFF);
        assertEquals(0x12, battQuery[63] & 0xFF);
        assertTrue(AkkoS9UltraProtocol.verifyCrc16(battQuery, battQuery.length));

        byte[] writePayload = AkkoS9UltraProtocol.buildBatteryWritePayload();
        assertEquals(63, writePayload.length);
        assertEquals(0x12, writePayload[0] & 0xFF);
        assertEquals(0x01, writePayload[1] & 0xFF);
        assertEquals(0x71, writePayload[61] & 0xFF);
        assertEquals(0x12, writePayload[62] & 0xFF);
    }

    @Test
    void parsesRealHardware47PercentReplyAndConnectionHeartbeat() {
        // Pacote real de heartbeat de conexão observado no dongle: 02 10 01 00 01 00...00 71 E1
        byte[] heartbeatAlive = new byte[64];
        heartbeatAlive[0] = 0x02;
        heartbeatAlive[1] = 0x10;
        heartbeatAlive[2] = 0x01;
        heartbeatAlive[3] = 0x00;
        heartbeatAlive[4] = 0x01;
        heartbeatAlive[62] = 0x71;
        heartbeatAlive[63] = (byte) 0xE1;

        assertTrue(AkkoS9UltraProtocol.verifyCrc16(heartbeatAlive, 64));
        AkkoS9UltraProtocol.ParsedReply connReply = AkkoS9UltraProtocol.inspectInputReport(heartbeatAlive, 64);
        assertEquals(AkkoS9UltraProtocol.ReplyType.CONNECTION_ALIVE, connReply.type());
        assertNull(AkkoS9UltraProtocol.parseInputReport(heartbeatAlive, 64));

        // Pacote real de bateria 47% (0x2F) observado no dongle: 02 12 01 00 2F 00...00 55 16
        byte[] battery47 = new byte[64];
        battery47[0] = 0x02;
        battery47[1] = 0x12;
        battery47[2] = 0x01;
        battery47[3] = 0x00;
        battery47[4] = 0x2F; // 47%
        battery47[62] = 0x55;
        battery47[63] = 0x16;

        assertTrue(AkkoS9UltraProtocol.verifyCrc16(battery47, 64));
        AkkoS9UltraProtocol.ParsedBattery parsed = AkkoS9UltraProtocol.parseInputReport(battery47, 64);
        assertNotNull(parsed);
        assertEquals(47, parsed.percent());
        assertFalse(parsed.charging());
    }

    @Test
    void handlesChargingSentinelAsleepAndInvalidCrc() {
        // Sentinela de carregamento 0xBC (188) comprovado em DeviceModel / BaseDeviceViewModel
        byte[] chargingReport = buildSignedReply(0x12, 0x00, AkkoS9UltraProtocol.BATTERY_CHARGING_SENTINEL);
        AkkoS9UltraProtocol.ParsedBattery charging = AkkoS9UltraProtocol.parseInputReport(chargingReport, 64);
        assertNotNull(charging);
        assertEquals(100, charging.percent());
        assertTrue(charging.charging());

        // Headset desligado / em repouso (status=0x01 ou valor=0x00)
        byte[] asleepReport = buildSignedReply(0x12, 0x01, 0x00);
        AkkoS9UltraProtocol.ParsedReply asleepReply = AkkoS9UltraProtocol.inspectInputReport(asleepReport, 64);
        assertEquals(AkkoS9UltraProtocol.ReplyType.HEADSET_ASLEEP, asleepReply.type());
        assertNull(AkkoS9UltraProtocol.parseInputReport(asleepReport, 64));

        // CRC inválido deve ser rejeitado
        byte[] corrupted = battery47WithBadCrc();
        AkkoS9UltraProtocol.ParsedReply badCrc = AkkoS9UltraProtocol.inspectInputReport(corrupted, 64);
        assertEquals(AkkoS9UltraProtocol.ReplyType.CRC_MISMATCH, badCrc.type());
        assertNull(AkkoS9UltraProtocol.parseInputReport(corrupted, 64));
    }

    @Test
    void configMatchesTargetCollection008cAndExcludes000cAndKeyboard() {
        AkkoVergeS9Config cfg = AkkoVergeS9Config.defaultReference();
        assertTrue(cfg.matchesTargetCollection(0x36EB, 0x1087, 3, 0x008C, 0x0001, "Akko S9 Ultra_Dongle"));
        assertFalse(cfg.matchesTargetCollection(0x36EB, 0x1087, 3, 0x000C, 0x0001, "Akko S9 Ultra_Dongle"));
        assertFalse(cfg.matchesTargetCollection(0x3151, 0x502D, 1, 0x008C, 0x0001, "Akko Keyboard"));
    }

    @Test
    void pcapngAnalyzerExtractsAkkoTransfersAndComparesBatteryPercentages() {
        byte[] cap47 = buildSyntheticPcapng(19, 47);
        byte[] cap82 = buildSyntheticPcapng(25, 82);

        AkkoPcapngAnalyzer.CaptureAnalysis a47 = AkkoPcapngAnalyzer.analyzeBytes("akko-47.pcapng", cap47, null);
        AkkoPcapngAnalyzer.CaptureAnalysis a82 = AkkoPcapngAnalyzer.analyzeBytes("akko-82.pcapng", cap82, null);

        assertTrue(a47.matchedUsbAddresses().contains(19));
        assertEquals(java.util.List.of(47), a47.observedBatteryPercentages());
        assertTrue(a82.matchedUsbAddresses().contains(25));
        assertEquals(java.util.List.of(82), a82.observedBatteryPercentages());

        String diffReport = AkkoPcapngAnalyzer.compareCaptures(a47, a82);
        assertTrue(diffReport.contains("byte[4]"));
        assertTrue(diffReport.contains("47%"));
        assertTrue(diffReport.contains("82%"));
    }

    private static byte[] buildSignedReply(int cmd, int status, int val) {
        byte[] frame = new byte[64];
        frame[0] = 0x02;
        frame[1] = (byte) cmd;
        frame[2] = 0x01;
        frame[3] = (byte) status;
        frame[4] = (byte) val;
        int crc = AkkoS9UltraProtocol.computeCrc16(frame, 0, 62);
        frame[62] = (byte) ((crc >>> 8) & 0xFF);
        frame[63] = (byte) (crc & 0xFF);
        return frame;
    }

    private static byte[] battery47WithBadCrc() {
        byte[] frame = buildSignedReply(0x12, 0x00, 47);
        frame[63] ^= 0xFF;
        return frame;
    }

    private static byte[] buildSyntheticPcapng(int usbAddr, int batteryPct) {
        byte[] req = AkkoS9UltraProtocol.buildFullBatteryQuery();
        byte[] resp = buildSignedReply(0x12, 0x00, batteryPct);

        ByteBuffer buf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        // SHB (28 bytes)
        buf.putInt(0x0A0D0D0A);
        buf.putInt(28);
        buf.putInt(0x1A2B3C4D);
        buf.putShort((short) 1);
        buf.putShort((short) 0);
        buf.putLong(-1L);
        buf.putInt(28);

        // EPB 1: Host -> Dongle (Interrupt OUT)
        writeUsbPcapEpb(buf, usbAddr, 0x03, false, req);
        // EPB 2: Dongle -> Host (Interrupt IN)
        writeUsbPcapEpb(buf, usbAddr, 0x83, true, resp);

        byte[] out = new byte[buf.position()];
        buf.flip();
        buf.get(out);
        return out;
    }

    private static void writeUsbPcapEpb(ByteBuffer buf, int usbAddr, int endpoint, boolean deviceToHost, byte[] hidPayload) {
        int usbHeaderLen = 27;
        int capLen = usbHeaderLen + hidPayload.length;
        int paddedCapLen = (capLen + 3) & ~3;
        int blockLen = 32 + paddedCapLen;

        buf.putInt(0x00000006); // EPB
        buf.putInt(blockLen);
        buf.putInt(0); // interface id
        buf.putInt(0); // ts high
        buf.putInt(0); // ts low
        buf.putInt(capLen);
        buf.putInt(capLen);

        int pktStart = buf.position();
        buf.putShort((short) usbHeaderLen);
        buf.putLong(1L); // irpId
        buf.putInt(0);   // status
        buf.putShort((short) 0); // function
        buf.put((byte) (deviceToHost ? 0x01 : 0x00)); // info
        buf.putShort((short) 1); // bus
        buf.putShort((short) usbAddr);
        buf.put((byte) endpoint);
        buf.put((byte) 0x01); // URB_INTERRUPT
        buf.putInt(hidPayload.length);
        buf.put(hidPayload);

        int written = buf.position() - pktStart;
        while (written < paddedCapLen) {
            buf.put((byte) 0);
            written++;
        }
        buf.putInt(blockLen);
    }
}
