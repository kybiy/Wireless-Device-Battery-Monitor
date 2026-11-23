package br.com.wirelessbattery.device;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DeluxM800ProProtocolTest {

    @Test
    void buildsExpected33ByteQueryAnd32BytePayload() {
        byte[] full = DeluxM800ProProtocol.buildFullBatteryQuery(3);
        assertEquals(33, full.length);
        assertEquals(0x0C, full[0] & 0xFF);
        assertEquals(0x01, full[1] & 0xFF);
        assertEquals(0x20, full[2] & 0xFF);
        assertEquals(0x00, full[3] & 0xFF);
        assertEquals(0x03, full[4] & 0xFF);

        byte[] payload = DeluxM800ProProtocol.buildFeaturePayload(3);
        assertEquals(32, payload.length);
        assertEquals(0x01, payload[0] & 0xFF);
        assertEquals(0x20, payload[1] & 0xFF);
        assertEquals(0x03, payload[3] & 0xFF);
    }

    @Test
    void parses33ByteResponseWithReportIdPrefix() {
        byte[] buf = new byte[33];
        buf[0] = DeluxM800ProProtocol.FEATURE_REPORT_ID;
        buf[18] = 74;
        buf[19] = 0;

        DeluxM800ProProtocol.ParsedBattery parsed = DeluxM800ProProtocol.parseFeatureResponse(buf, buf.length);
        assertNotNull(parsed);
        assertEquals(74, parsed.percent());
        assertFalse(parsed.charging());
    }

    @Test
    void parses32ByteResponseWithoutReportIdPrefixAndChargingFlag() {
        byte[] buf = new byte[32];
        buf[0] = 0x01;
        buf[17] = 60;
        buf[18] = 1;

        DeluxM800ProProtocol.ParsedBattery parsed = DeluxM800ProProtocol.parseFeatureResponse(buf, buf.length);
        assertNotNull(parsed);
        assertEquals(60, parsed.percent());
        assertTrue(parsed.charging());
    }

    @Test
    void returnsNullWhenBatteryByteIsZeroOrOutOfRange() {
        byte[] empty = new byte[33];
        empty[0] = DeluxM800ProProtocol.FEATURE_REPORT_ID;
        assertNull(DeluxM800ProProtocol.parseFeatureResponse(empty, empty.length));

        byte[] invalid = new byte[33];
        invalid[0] = DeluxM800ProProtocol.FEATURE_REPORT_ID;
        invalid[18] = (byte) 200;
        assertNull(DeluxM800ProProtocol.parseFeatureResponse(invalid, invalid.length));
    }

    @Test
    void defaultConfigMatchesReferenceIds() {
        DeluxM800ProConfig cfg = DeluxM800ProConfig.defaultReference();
        assertTrue(cfg.matches(0x248A, 0x5B2F));
        assertTrue(cfg.matches(0x248A, 0x5B2E));
        assertFalse(cfg.matches(0x25A7, 0xFA7C));
        assertEquals(1, cfg.getInterfaceNumber());
    }
}
