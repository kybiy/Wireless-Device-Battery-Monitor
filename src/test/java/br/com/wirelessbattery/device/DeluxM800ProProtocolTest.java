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
    void parses32ByteHid4JavaPayloadEvenWhenReportedLengthIs33AndFirstByteIs0x0C() {
        byte[] payload = new byte[32];
        payload[0] = DeluxM800ProProtocol.FEATURE_REPORT_ID; // não deve deslocar índice no payload já sem Report ID
        payload[17] = 88;
        payload[18] = 0;

        DeluxM800ProProtocol.ParsedBattery parsed =
                DeluxM800ProProtocol.parseHid4JavaFeaturePayload(payload, 33);
        assertNotNull(parsed);
        assertEquals(88, parsed.percent());
        assertFalse(parsed.charging());

        payload[17] = 45;
        payload[18] = 1;
        DeluxM800ProProtocol.ParsedBattery charging =
                DeluxM800ProProtocol.parseHid4JavaFeaturePayload(payload, 33);
        assertNotNull(charging);
        assertEquals(45, charging.percent());
        assertTrue(charging.charging());
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
    void defaultConfigMatchesConfirmedCol04AndIgnoresInterface2AndStandardCollections() {
        DeluxM800ProConfig cfg = DeluxM800ProConfig.defaultReference();
        assertTrue(cfg.matches(0x248A, 0x5B2F));
        assertTrue(cfg.matches(0x248A, 0x5B2E));
        assertFalse(cfg.matches(0x25A7, 0xFA7C));
        assertEquals(1, cfg.getInterfaceNumber());
        assertEquals(0xFF00, cfg.getUsagePage());
        assertEquals(0x0001, cfg.getUsage());

        // Coleção alvo confirmada: IF 1, UsagePage 0xFF00, Usage 0x0001 (Col04)
        assertTrue(cfg.matchesTargetCollection(0x248A, 0x5B2F, 1, 0xFF00, 0x0001));
        // Não abrir IF 2 (0xFFEF:0x0000) nem coleções padrão de mouse/teclado/consumer
        assertFalse(cfg.matchesTargetCollection(0x248A, 0x5B2F, 2, 0xFFEF, 0x0000));
        assertFalse(cfg.matchesVendorFallbackOnExpectedInterface(0x248A, 0x5B2F, 2, 0xFFEF));
        assertFalse(cfg.matchesTargetCollection(0x248A, 0x5B2F, 1, 0x0001, 0x0002));
        assertFalse(cfg.matchesVendorFallbackOnExpectedInterface(0x248A, 0x5B2F, 1, 0x0001));
        assertFalse(cfg.matchesVendorFallbackOnExpectedInterface(0x248A, 0x5B2F, 1, 0x000C));
    }
}
