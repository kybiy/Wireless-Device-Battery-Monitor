package br.com.wirelessbattery.device;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GameSirCyclone2ProtocolTest {

    @Test
    void builds64ByteStateQueryAnd63ByteWritePayload() {
        byte[] full = GameSirCyclone2Protocol.buildFullStateQuery();
        assertEquals(64, full.length);
        assertEquals(0x0F, full[0] & 0xFF);
        assertEquals(0x03, full[1] & 0xFF);
        for (int i = 2; i < full.length; i++) {
            assertEquals(0, full[i]);
        }

        byte[] payload = GameSirCyclone2Protocol.buildWritePayload();
        assertEquals(63, payload.length);
        assertEquals(0x03, payload[0] & 0xFF);
        for (int i = 1; i < payload.length; i++) {
            assertEquals(0, payload[i]);
        }
    }

    @Test
    void detectsIdleDongle0x12ReportAsAsleepAndNotZeroPercentBattery() {
        byte[] idleReport = new byte[64];
        idleReport[0] = 0x12;
        idleReport[63] = (byte) 0xCC;

        assertTrue(GameSirCyclone2Protocol.isIdleDongleReport(idleReport, 64));
        assertNull(GameSirCyclone2Protocol.parseInputReport(idleReport, 64));
    }

    @Test
    void parsesValidInputReport12WithBatteryAndChargingFlag() {
        byte[] report = new byte[64];
        report[0] = 0x12;
        report[35] = 0x00; // descarregando (2.4 GHz)
        report[36] = 90;   // 90%

        GameSirCyclone2Protocol.ParsedBattery parsed =
                GameSirCyclone2Protocol.parseInputReport(report, report.length);
        assertNotNull(parsed);
        assertEquals(90, parsed.percent());
        assertFalse(parsed.charging());

        report[35] = 0x01; // carregando / cabo conectado
        report[36] = 64;
        GameSirCyclone2Protocol.ParsedBattery charging =
                GameSirCyclone2Protocol.parseInputReport(report, 37);
        assertNotNull(charging);
        assertEquals(64, charging.percent());
        assertTrue(charging.charging());
    }

    @Test
    void rejectsEventReport10ShortFramesAndOutOfRangeBattery() {
        byte[] event10 = new byte[64];
        event10[0] = 0x10;
        event10[36] = 50;
        assertNull(GameSirCyclone2Protocol.parseInputReport(event10, 64));

        byte[] shortFrame = new byte[36];
        shortFrame[0] = 0x12;
        shortFrame[35] = 1;
        assertNull(GameSirCyclone2Protocol.parseInputReport(shortFrame, 36));

        byte[] over100 = new byte[64];
        over100[0] = 0x12;
        over100[36] = (byte) 101;
        assertNull(GameSirCyclone2Protocol.parseInputReport(over100, 64));
    }

    @Test
    void configMatchesOnlyCyclone2IdsAndNotNovaLine() {
        GameSirCyclone2Config cfg = GameSirCyclone2Config.defaultReference();
        assertTrue(cfg.matchesActive(0x3537, 0x100B));
        assertTrue(cfg.matchesActive(0x3537, 0x102A));
        assertTrue(cfg.matchesActive(0x3537, 0x1053));
        assertTrue(cfg.matchesActive(0x3537, 0x101D));
        assertTrue(cfg.isWiredPid(0x1053));
        assertTrue(cfg.isWiredPid(0x101D));
        assertFalse(cfg.isWiredPid(0x100B));

        assertTrue(cfg.matchesIdleReceiver(0x3537, 0x0575));
        assertTrue(cfg.matchesAny(0x3537, 0x100B));
        assertTrue(cfg.matchesAny(0x3537, 0x0575));
        assertTrue(cfg.matchesCollection(0x3537, 0x100B, 0xFFF0, 0x0040));
        assertTrue(cfg.matchesCollection(0x3537, 0x1053, 0xFFF0, 0x0040));
        assertTrue(cfg.matchesIdleCollection(0x3537, 0x0575, 0xFFF0, 0x0040));
        assertFalse(cfg.matchesCollection(0x3537, 0x100B, 0xFF7A, 0x0001));

        // Não confundir com GameSir Nova / Nova 2 / Nova 2 Lite
        assertFalse(cfg.matchesAny(0x3537, 0x1098));
        assertFalse(cfg.matchesAny(0x3537, 0x100F));
        assertFalse(cfg.matchesAny(0x3537, 0x1040));
    }
}
