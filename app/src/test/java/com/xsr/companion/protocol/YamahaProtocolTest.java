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
    public void fuelRateUsesInjectionMlPerSecond() {
        assertEquals(0.0, YamahaProtocol.calculateFuelRateLh(0.0), 1e-9);
        assertEquals(0.0, YamahaProtocol.calculateFuelRateLh(-2.0), 1e-9);
        assertEquals(1.8, YamahaProtocol.calculateFuelRateLh(0.5), 1e-9);
        assertEquals(7.2, YamahaProtocol.calculateFuelRateLh(2.0), 1e-9);
    }

    @Test
    public void fuelRateFallbackEstimatesFromSensorPayload() {
        // Engine off -> no fuel.
        assertEquals(0.0, YamahaProtocol.calculateFuelRateLh(0, 50.0, 25, 101.325), 1e-9);
        // Cruising at 5000 rpm / half throttle should give a plausible
        // non-zero rate for the 155cc engine (not zero, not absurd).
        double rate = YamahaProtocol.calculateFuelRateLh(5000, 50.0, 25, 101.325);
        assertTrue("expected positive fallback rate, got " + rate, rate > 0.5 && rate < 8.0);
    }

    @Test
    public void fuelUsedIntegratesInjectionRate() {
        // 0.5 mL/s for 3 s = 1.5 mL = 0.0015 L.
        assertEquals(0.0015, YamahaProtocol.calculateFuelUsedL(0.5, 3.0), 1e-12);
        assertEquals(0.0, YamahaProtocol.calculateFuelUsedL(0.0, 3.0), 1e-12);
        assertEquals(0.0, YamahaProtocol.calculateFuelUsedL(0.5, 0.0), 1e-12);
    }

    @Test
    public void instantMileageUsesInjectionFuelRate() {
        assertEquals(0.0, YamahaProtocol.calculateInstantMileageKmL(0, 1.0), 1e-9);
        assertEquals(999.9, YamahaProtocol.calculateInstantMileageKmL(40, 0.0), 1e-9);
        assertEquals(40.0, YamahaProtocol.calculateInstantMileageKmL(60, 1.5), 0.001);
        // Official app floors to one decimal and caps at 999.9.
        assertEquals(35.2, YamahaProtocol.calculateInstantMileageKmL(60, 1.7), 0.001);
        assertEquals(999.9, YamahaProtocol.calculateInstantMileageKmL(100, 0.0001), 1e-9);
    }

    @Test
    public void parsesInjectionFromFullAndShortCanIds() {
        byte[] full = {
                YamahaProtocol.START_BYTE_56, 0x01,
                0x03, 0x00, 0x00, 0x0E, 0x13, (byte) 0x88, // 0x0300000E = 0x1388 = 5000 -> 50 mL/s
                0x00
        };
        BikeTelemetry fullTelemetry = new BikeTelemetry();
        assertTrue(YamahaProtocol.parseIncomingPacket(full, fullTelemetry));
        assertEquals(50.0, fullTelemetry.fuelInjection, 1e-9);

        byte[] shortId = {
                YamahaProtocol.START_BYTE_56, 0x01,
                0x03, 0x0E, 0x13, (byte) 0x88,              // Short 0x0300000E layout
                0x00
        };
        BikeTelemetry shortTelemetry = new BikeTelemetry();
        assertTrue(YamahaProtocol.parseIncomingPacket(shortId, shortTelemetry));
        assertEquals(50.0, shortTelemetry.fuelInjection, 1e-9);
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
