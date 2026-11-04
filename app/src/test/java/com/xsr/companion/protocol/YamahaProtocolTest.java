package com.xsr.companion.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class YamahaProtocolTest {

    @Test
    public void checksumIsTwosComplement() {
        byte[] data = {0x01, 0x02, 0x03};
        assertEquals((byte) 0xFA, YamahaProtocol.calculateChecksum(data, data.length));
    }

    @Test
    public void verifyChecksumAcceptsValidPacketAndRejectsCorruption() {
        byte[] packet = YamahaProtocol.buildNotificationPacket(true, false, false, false, 7);
        assertTrue(YamahaProtocol.verifyChecksum(packet));

        packet[5] ^= 0x01;
        assertFalse(YamahaProtocol.verifyChecksum(packet));
    }

    @Test
    public void authPacketUsesFixedFieldLayout() {
        byte[] packet = YamahaProtocol.buildAuthPacket("YSCCU_12345678901234",
                "654321", "abc123", true, 9);

        assertEquals(60, packet.length);
        assertEquals(YamahaProtocol.START_BYTE_AUTH, packet[0]);
        assertEquals(0x35, packet[4] & 0xFF);
        assertEquals('1', packet[5]);
        assertEquals('6', packet[19]);
        assertEquals(0x01, packet[57]);
        assertEquals(9, packet[58] & 0xFF);
        assertTrue(YamahaProtocol.verifyChecksum(packet));
    }

    @Test
    public void startProcessingAckRecognizesAcceptAndReject() {
        byte[] accepted = YamahaProtocol.buildStartStreamingPacket(4);
        assertEquals(1, YamahaProtocol.parseStartProcessingAck(accepted));

        accepted[5] = 0x00;
        accepted[7] = YamahaProtocol.calculateChecksum(accepted, 7);
        assertEquals(0, YamahaProtocol.parseStartProcessingAck(accepted));
    }

    @Test
    public void parsesLocalTelemetryTlvFrame() {
        byte[] packet = new byte[2 + 3 + 33 + 2];
        packet[0] = YamahaProtocol.START_BYTE_55;
        packet[1] = 0x01;
        packet[2] = 0x00;
        packet[3] = 0x01;
        packet[4] = 33;

        int v = 5;
        packet[v] = 0x1F;      // RPM 8000
        packet[v + 1] = 0x40;
        packet[v + 2] = 62;    // 62 km/h
        packet[v + 3] = 40;    // 40% throttle
        packet[v + 4] = 84;    // 84 C coolant
        packet[v + 7] = 31;    // 31 C air
        // Remaining TLV bytes stay zero.

        BikeTelemetry telemetry = new BikeTelemetry();
        assertTrue(YamahaProtocol.parseIncomingPacket(packet, telemetry));
        assertEquals(8000, telemetry.engineRpm);
        assertEquals(62, telemetry.speedKmh);
        assertEquals(40.0, telemetry.throttlePct, 1e-9);
        assertEquals(84, telemetry.coolantTemp);
        assertEquals(31, telemetry.airTemp);
    }

    @Test
    public void parsesDtcCountAndCodeFromCanFrame() {
        byte[] packet = {
                YamahaProtocol.START_BYTE_56, 0x01,
                0x02, 0x00, 0x00, 0x08, 0x02,             // DTC count = 2
                0x02, 0x00, 0x00, 0x09, 0x00, 0x1E,       // DTC 30
                0x03, 0x00, 0x00, 0x0D, 0x01,             // warning lamp on
                0x00
        };

        BikeTelemetry telemetry = new BikeTelemetry();
        assertTrue(YamahaProtocol.parseIncomingPacket(packet, telemetry));
        assertEquals(2, telemetry.dtcCount);
        assertEquals(30, telemetry.dtcCode);
        assertTrue(telemetry.warningLamp);
    }

    @Test
    public void fuelRateScalesWithRpmAndThrottle() {
        double idle = YamahaProtocol.calculateFuelRateLh(1500, 0, 30, 101.3);
        double cruise = YamahaProtocol.calculateFuelRateLh(6000, 30, 30, 101.3);
        double wideOpen = YamahaProtocol.calculateFuelRateLh(9000, 100, 30, 101.3);

        assertEquals(0.0, YamahaProtocol.calculateFuelRateLh(0, 50, 30, 101.3), 1e-9);
        assertTrue(idle > 0);
        assertTrue(cruise > idle);
        assertTrue(wideOpen > cruise);
    }

    @Test
    public void instantMileageHandlesStopCutoffAndDivision() {
        assertEquals(0.0, YamahaProtocol.calculateInstantMileageKmL(0, 1.0, 50), 1e-9);
        assertEquals(99.9, YamahaProtocol.calculateInstantMileageKmL(40, 2.0, 0.0), 1e-9);
        assertEquals(40.0, YamahaProtocol.calculateInstantMileageKmL(60, 1.5, 30), 0.001);
    }

    @Test
    public void decodesCcuQrPayloadToPasskey() {
        // Raw scrambled payload, and the decoded layout it represents:
        // 00000005826927 (CCU ID) + 123456 (passkey) + WXYZ (suffix).
        String raw = "0986Y4001W6Z020520X70253";
        assertEquals("123456", YamahaProtocol.decodeCcuQrPasskey(raw, "00000005826927"));
        assertEquals("123456", YamahaProtocol.decodeCcuQrPasskey(raw, "YSCCU_00000005826927"));
    }

    @Test
    public void rejectsCcuQrPayloadForAnotherCcu() {
        String raw = "0986Y4001W6Z020520X70253";
        assertEquals(null, YamahaProtocol.decodeCcuQrPasskey(raw, "00000005999999"));
        assertEquals(null, YamahaProtocol.decodeCcuQrPasskey("too-short", "00000005826927"));
        assertEquals(null, YamahaProtocol.decodeCcuQrPasskey(null, "00000005826927"));
    }
}
