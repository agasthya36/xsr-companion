package com.xsr.companion.protocol;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class YamahaProtocol {

    // Nordic UART Service (NUS)
    public static final UUID NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static final UUID NUS_RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"); // Phone writes to CCU
    public static final UUID NUS_TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"); // CCU notifies Phone
    public static final UUID CLIENT_CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    public static final String PREFIX_YCCU = "YCCU_";
    public static final String PREFIX_YSCCU = "YSCCU_";

    public static final byte START_BYTE_AUTH = (byte) 0xAA; // 170 (-86)
    public static final byte START_BYTE_A6 = (byte) 0xA6;   // 166 (-90)
    public static final byte START_BYTE_5A = (byte) 0x5A;   // 90
    public static final byte START_BYTE_55 = (byte) 0x55;   // 85 (Local Telemetry)
    public static final byte START_BYTE_56 = (byte) 0x56;   // 86 (CAN Telemetry)
    public static final byte START_BYTE_5B = (byte) 0x5B;   // 91 (Common Records)
    public static final byte GROUP_COMMON = 0x01;

    private YamahaProtocol() {}

    /**
     * Yamaha standard 2's complement 8-bit checksum over buffer up to length.
     */
    public static byte calculateChecksum(byte[] data, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (data[i] & 0xFF);
        }
        return (byte) ((256 - (sum & 0xFF)) & 0xFF);
    }

    public static boolean verifyChecksum(byte[] data) {
        if (data == null || data.length < 3) return false;
        byte expected = calculateChecksum(data, data.length - 1);
        return data[data.length - 1] == expected;
    }

    /**
     * Builds 60-byte Packet 0x7F00 (SCCU1AuthEnt):
     * Phone initiates session authentication with CCU.
     * CCU validates CCUID, passkey, and updateBondingFlag, then responds with Packet 0x7F01.
     */
    public static byte[] buildAuthPacket(String ccuId, String passKey, String phoneUuid, boolean isFirstConnection, int counter) {
        byte[] packet = new byte[60];
        packet[0] = START_BYTE_AUTH; // 0xAA (-86)
        packet[1] = GROUP_COMMON;    // 0x01
        packet[2] = 0x7F;            // ID High
        packet[3] = 0x00;            // ID Low (0x7F00)
        packet[4] = 0x35;            // Length: 53 bytes (0x35)

        // CCU ID: 14 ASCII bytes (offset 5..18)
        String cleanCcu = cleanCcuId(ccuId);
        byte[] ccuBytes = cleanCcu.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 14; i++) {
            packet[5 + i] = (i < ccuBytes.length) ? ccuBytes[i] : (byte) '0';
        }

        // PassKey: 6 ASCII bytes (offset 19..24)
        String cleanPass = (passKey != null && !passKey.trim().isEmpty()) ? passKey.trim() : "000000";
        if (cleanPass.length() > 6) {
            cleanPass = cleanPass.substring(cleanPass.length() - 6);
        }
        byte[] passBytes = cleanPass.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 6; i++) {
            packet[19 + i] = (i < passBytes.length) ? passBytes[i] : (byte) '0';
        }

        // Phone UUID: 32 ASCII bytes (offset 25..56)
        String cleanUuid = (phoneUuid != null && !phoneUuid.trim().isEmpty()) ? phoneUuid.trim().replace("-", "") : "";
        byte[] uuidBytes = cleanUuid.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 32; i++) {
            packet[25 + i] = (i < uuidBytes.length) ? uuidBytes[i] : (byte) '0';
        }

        // updateBondingFlag: 1 byte (offset 57) - 1 for initial pairing, 0 for reconnecting to bonded CCU
        packet[57] = (byte) (isFirstConnection ? 0x01 : 0x00);

        // Counter: 1 byte (offset 58)
        packet[58] = (byte) (counter & 0xFF);

        // Checksum: 1 byte (offset 59) over offsets 0..58
        packet[59] = calculateChecksum(packet, 59);

        return packet;
    }

    public static String cleanCcuId(String ccuId) {
        if (ccuId == null) return "00000000000000";
        String s = ccuId.trim();
        if (s.startsWith(PREFIX_YSCCU)) {
            s = s.substring(PREFIX_YSCCU.length());
        } else if (s.startsWith(PREFIX_YCCU)) {
            s = s.substring(PREFIX_YCCU.length());
        }
        if (s.length() < 14) {
            StringBuilder sb = new StringBuilder();
            while (sb.length() + s.length() < 14) {
                sb.append('0');
            }
            sb.append(s);
            s = sb.toString();
        } else if (s.length() > 14) {
            s = s.substring(0, 14);
        }
        return s;
    }

    /**
     * Checks if incoming packet is 0x7F01 (SCCU1StartProcEnt) from CCU.
     * Returns:
     *   1 if CCU confirmed start processing (auth accepted),
     *   0 if CCU rejected start processing (auth error),
     *  -1 if packet is not 0x7F01.
     */
    public static int parseStartProcessingAck(byte[] data) {
        if (data == null || data.length < 8) return -1;
        if (data[0] == START_BYTE_5A && data[1] == GROUP_COMMON
                && (data[2] & 0xFF) == 0x7F && (data[3] & 0xFF) == 0x01) {
            if (!verifyChecksum(data)) {
                return -1;
            }
            int flag = data[5] & 0xFF;
            return (flag == 1) ? 1 : 0;
        }
        return -1;
    }

    /**
     * Builds 8-byte Packet 0x7F01 (SCCU1StartProcEnt):
     * Legacy streaming trigger packet.
     */
    public static byte[] buildStartStreamingPacket(int counter) {
        byte[] packet = new byte[8];
        packet[0] = START_BYTE_5A; // 0x5A
        packet[1] = GROUP_COMMON;   // 0x01
        packet[2] = 0x7F;          // ID High
        packet[3] = 0x01;          // ID Low (0x7F01)
        packet[4] = 0x01;          // Length: 1 byte
        packet[5] = 0x01;          // startProcessingFlag = 1 (start)
        packet[6] = (byte) (counter & 0xFF);
        packet[7] = calculateChecksum(packet, 7);
        return packet;
    }

    /**
     * Builds 15-byte Packet 0x058A: Time & Phone Battery Sync
     * Transmitted periodically every 1000ms.
     */
    public static byte[] buildBatteryAndClockPacket(int year, int month, int day, int hour, int minute, int second, int batteryPercent, int counter) {
        byte[] packet = new byte[15];
        packet[0] = START_BYTE_A6;
        packet[1] = GROUP_COMMON;
        packet[2] = 0x05;
        packet[3] = (byte) 0x8A;
        packet[4] = 0x08; // 8 bytes payload
        packet[5] = (byte) ((year >> 8) & 0xFF);
        packet[6] = (byte) (year & 0xFF);
        packet[7] = (byte) Math.max(1, Math.min(12, month));
        packet[8] = (byte) Math.max(1, Math.min(31, day));
        packet[9] = (byte) Math.max(0, Math.min(23, hour));
        packet[10] = (byte) Math.max(0, Math.min(59, minute));
        packet[11] = (byte) Math.max(0, Math.min(59, second));

        // Battery level (0-100) shifted left by 1
        int clampedBattery = Math.max(0, Math.min(100, batteryPercent));
        packet[12] = (byte) ((clampedBattery & 0xFF) << 1);

        packet[13] = (byte) (counter & 0xFF);
        packet[14] = calculateChecksum(packet, 14);
        return packet;
    }

    /**
     * Builds 9-byte Packet 0x058B: Call & Notification Indicator Flags
     * Transmitted periodically every 1000ms or immediately when notification state changes.
     */
    public static byte[] buildNotificationPacket(boolean incomingCall, boolean missedCall, boolean unreadMessage, boolean appAlert, int counter) {
        byte[] packet = new byte[9];
        packet[0] = START_BYTE_A6;
        packet[1] = GROUP_COMMON;
        packet[2] = 0x05;
        packet[3] = (byte) 0x8B;
        packet[4] = 0x02; // 2 bytes payload

        // notificationUpper:
        // bits 7-6: incoming call
        // bits 5-4: missed call
        // bits 3-2: active call
        // bits 1-0: message flag
        int upper = 0;
        if (incomingCall) upper |= (0x01 << 6);
        if (missedCall)   upper |= (0x01 << 4);
        if (unreadMessage) upper |= (0x01);
        packet[5] = (byte) upper;

        // notificationLower:
        // bits 7-6: SNS / App alert
        // bits 5-4: Email alert
        int lower = 0;
        if (appAlert) lower |= (0x01 << 6);
        packet[6] = (byte) lower;

        packet[7] = (byte) (counter & 0xFF);
        packet[8] = calculateChecksum(packet, 8);
        return packet;
    }

    /**
     * Parses incoming BLE notifications from Yamaha CCU TX Characteristic.
     * Updates target BikeTelemetry instance with decoded CAN / LOCAL values.
     * Returns true if packet was recognized and parsed.
     */
    public static boolean parseIncomingPacket(byte[] data, BikeTelemetry target) {
        if (data == null || data.length < 5 || target == null) return false;

        target.timestamp = System.currentTimeMillis();

        byte startByte = data[0];

        // 1. Local Real-Time Telemetry Frame (0x55)
        // CCU sends 0x55 (85) containing TLV records:
        // TLV 0x0001 (len=33): ECU sensors (RPM, speed, throttle, coolant, air temp)
        // TLV 0x0048 (len=4): Meter distance / Odometer in 0.1km (Big Endian)
        // Only 0x55 is a local telemetry frame; matching arbitrary bytes at
        // offsets 2..3 would misroute CAN frames whose payload happens to
        // contain 0x0200.
        if (startByte == START_BYTE_55) {
            int i = 2; // skip start byte and group byte
            while (i + 3 <= data.length - 2) { // reserve 2 bytes for counter + checksum
                int tlvId = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
                int tlvLen = data[i + 2] & 0xFF;
                int valOffset = i + 3;
                if (valOffset + tlvLen > data.length - 2) {
                    break;
                }

                if (tlvId == 0x0001 && tlvLen >= 5) {
                    // Engine RPM: 2 bytes (UShort)
                    target.engineRpm = ((data[valOffset] & 0xFF) << 8) | (data[valOffset + 1] & 0xFF);
                    // Speed: 1 byte (km/h)
                    target.speedKmh = data[valOffset + 2] & 0xFF;
                    // Throttle: 1 byte (0-100% or 0-255 scaled)
                    int rawThrottle = data[valOffset + 3] & 0xFF;
                    target.throttlePct = Math.min(100.0, rawThrottle > 100 ? (rawThrottle * 100.0 / 255.0) : rawThrottle);
                    // Coolant temp: 1 byte (°C)
                    int rawTemp = data[valOffset + 4] & 0xFF;
                    target.coolantTemp = (rawTemp > 160) ? (rawTemp - 40) : rawTemp;
                    // Intake air temp: byte 7 (°C)
                    if (tlvLen > 7) {
                        target.airTemp = data[valOffset + 7] & 0xFF;
                    }
                } else if (tlvId == 0x0048 && tlvLen >= 4) {
                    // Meter Odometer: 4 bytes in 0.1km (e.g. 0x00000A43 = 2627 -> 262.7 km)
                    long rawDist = ((long)(data[valOffset] & 0xFF) << 24)
                                 | ((long)(data[valOffset + 1] & 0xFF) << 16)
                                 | ((long)(data[valOffset + 2] & 0xFF) << 8)
                                 | (long)(data[valOffset + 3] & 0xFF);
                    if (rawDist > 0 && rawDist < 10000000L) {
                        target.odometerKm = rawDist / 10.0;
                    }
                }
                i += 3 + tlvLen;
            }
            return true;
        }

        // 2. CAN Frame (0x56): warning lamp, fuel injection, DTCs
        if (startByte == START_BYTE_56) {
            // Short (2-byte) CAN id layout, used by the warning/injection records.
            for (int i = 0; i < data.length - 2; i++) {
                if (data[i] == 0x03 && data[i + 1] == 0x0D) { // Warning lamp (0x0300000D)
                    target.warningLamp = (data[i + 2] & 0xFF) == 1;
                } else if (data[i] == 0x03 && data[i + 1] == 0x0E && i + 3 < data.length) {
                    int rawInj = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
                    target.fuelInjection = rawInj / 100.0;
                }
            }

            // Full 32-bit CAN ids (0x02000008 = DTC count, 0x02000009 = active code).
            Integer dtcCount = findCanValue(data, 0x02000008, 1);
            if (dtcCount != null) target.dtcCount = dtcCount;
            Integer dtcCode = findCanValue(data, 0x02000009, 2);
            if (dtcCode != null) target.dtcCode = dtcCode;
            Integer warning = findCanValue(data, 0x0300000D, 1);
            if (warning != null) target.warningLamp = warning == 1;
            Integer injection = findCanValue(data, 0x0300000E, 2);
            if (injection != null) target.fuelInjection = injection / 100.0;

            return true;
        }

        // 3. Start Processing Ack (0x5A) or Common Frame (0x5B)
        if (startByte == START_BYTE_5A || startByte == START_BYTE_5B) {
            return true;
        }

        return false;
    }

    /**
     * Calculates estimated fuel flow rate (litres / hour) for the Yamaha 155cc VVA engine.
     * Uses speed-density airflow estimation tuned to the 155.1cc displacement,
     * stoichiometric air-fuel ratio (14.7:1), gasoline density (740 g/L), and
     * throttle-derived volumetric efficiency (VE).
     */
    public static double calculateFuelRateLh(int rpm, double throttlePct, int airTempC, double pressureKpa) {
        if (rpm <= 0) return 0.0;
        // Displacement: 155.1 cc = 0.1551 L
        // 4-stroke single cylinder: 1 intake event every 2 revolutions -> rpm / 120 intake strokes / sec
        double throttleNorm = Math.max(0.0, Math.min(100.0, throttlePct)) / 100.0;
        // Volumetric Efficiency (VE) curve for Yamaha 155cc 4V SOHC VVA:
        // ~0.28 at idle (closed butterfly with bypass air), up to ~0.90 at wide-open throttle (VVA cam lobe)
        double ve = 0.28 + (0.62 * Math.pow(throttleNorm, 0.85));

        // Air density (kg/m3 == g/L) adjusted for intake temperature and barometric pressure
        double tempK = (airTempC > -20 && airTempC < 80) ? (airTempC + 273.15) : 298.15;
        double pressKpa = (pressureKpa > 50 && pressureKpa < 120) ? pressureKpa : 101.325;
        // Gas law: rho = P / (R_specific * T), R_specific = 287.058 J/(kg*K)
        double airDensityGL = (pressKpa * 1000.0) / (287.058 * tempK);

        // Airflow in grams/second:
        double airFlowGs = ((rpm / 120.0) * 0.1551 * ve) * airDensityGL;

        // Stoichiometric ratio for unleaded gasoline: 14.7 grams air per 1 gram fuel
        // Density of gasoline: ~740 grams / Litre
        // Fuel rate (L/h) = (airFlowGs / 14.7 / 740) * 3600
        double fuelRateLh = ((airFlowGs / 14.7) / 740.0) * 3600.0;
        return Math.max(0.0, fuelRateLh);
    }

    /**
     * Calculates instantaneous mileage in km/L from vehicle speed (km/h) and fuel rate (L/h).
     * Accounts for overrun fuel cut-off when coasting / decelerating in gear.
     */
    public static double calculateInstantMileageKmL(int speedKmh, double fuelRateLh, double throttlePct) {
        if (speedKmh <= 1) return 0.0; // Stopped: 0 km/L (infinite L/100km)
        // Fuel overrun cut-off: during deceleration with closed throttle at speed > 15 km/h
        if (throttlePct < 1.0 && speedKmh > 15) {
            return 99.9; // Fuel cutoff active
        }
        if (fuelRateLh <= 0.05) return 99.9;
        double kmL = speedKmh / fuelRateLh;
        // Do not hide genuinely poor economy behind an artificial 5 km/L floor.
        return Math.min(99.9, Math.max(0.1, kmL));
    }

    /**
     * Scans a 0x56 CAN payload for a 32-bit big-endian CAN id followed by its
     * value. Returns null when the id is not present.
     */
    private static Integer findCanValue(byte[] data, int canId, int valueLength) {
        if (data == null) return null;
        for (int i = 0; i + 4 + valueLength <= data.length; i++) {
            int id = ((data[i] & 0xFF) << 24)
                    | ((data[i + 1] & 0xFF) << 16)
                    | ((data[i + 2] & 0xFF) << 8)
                    | (data[i + 3] & 0xFF);
            if (id == canId) {
                int v = 0;
                for (int b = 0; b < valueLength; b++) {
                    v = (v << 8) | (data[i + 4 + b] & 0xFF);
                }
                return v;
            }
        }
        return null;
    }

    // The CCU QR sticker encodes a scrambled 24-character payload. Position i
    // of the raw payload belongs at this index of the decoded string. The
    // decoded string is: 14-char CCU ID + 6-char passkey + 4-char suffix.
    private static final int[] CCU_QR_ORDER = {
            3, 11, 8, 19, 22, 17, 6, 0, 14, 20, 10, 23,
            4, 9, 1, 18, 12, 5, 21, 13, 2, 15, 7, 16
    };

    /**
     * De-scrambles the 24-character payload printed as the CCU pairing QR
     * code and returns its 6-character passkey. Returns null when the payload
     * or the embedded CCU ID does not match.
     */
    public static String decodeCcuQrPasskey(String qrPayload, String expectedCcuId) {
        if (qrPayload == null) return null;
        String qr = qrPayload.trim();
        if (qr.length() != CCU_QR_ORDER.length) return null;
        char[] decoded = new char[CCU_QR_ORDER.length];
        for (int i = 0; i < CCU_QR_ORDER.length; i++) {
            decoded[CCU_QR_ORDER[i]] = qr.charAt(i);
        }
        String result = new String(decoded);
        String expected = cleanCcuId(expectedCcuId);
        if (expected != null && !expected.isEmpty() && !result.startsWith(expected)) {
            return null;
        }
        return result.substring(14, 20);
    }
}
