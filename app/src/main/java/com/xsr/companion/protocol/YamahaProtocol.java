package com.xsr.companion.protocol;

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
     * Builds 8-byte Packet 0x7F01 (SCCU1StartProcEnt):
     * Commands the CCU to start continuous periodic telemetry transmission (Local + CAN frames).
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
        int frameId = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);

        // 1. Local Real-Time Telemetry Frame (0x55, 0x5A, 0x0148, 0x0200)
        // CCU sends 0x55 (85) with 0x0148 or 0x0200 records for ICE bikes
        if (startByte == START_BYTE_55 || startByte == START_BYTE_5A || frameId == 0x0148 || frameId == 0x0200) {
            int offset = 5;
            if (data.length >= 16) {
                // RPM: 2 bytes (UShort)
                target.engineRpm = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                // Speed: 1 byte (UByte, km/h)
                target.speedKmh = data[offset + 2] & 0xFF;
                // Throttle: 1 byte (0-100 or 0-255 scaled)
                int rawThrottle = data[offset + 3] & 0xFF;
                target.throttlePct = Math.min(100.0, rawThrottle > 100 ? (rawThrottle * 100.0 / 255.0) : rawThrottle);
                // Coolant temp: 1 byte (°C, offset -40 if raw or direct °C)
                int rawTemp = data[offset + 4] & 0xFF;
                target.coolantTemp = (rawTemp > 160) ? (rawTemp - 40) : rawTemp;
                // Air temp: 1 byte (°C)
                if (offset + 5 < data.length) {
                    target.airTemp = data[offset + 5] & 0xFF;
                }
                // DTC count & code
                if (offset + 8 < data.length) {
                    target.dtcCount = data[offset + 6] & 0xFF;
                    target.dtcCode = ((data[offset + 7] & 0xFF) << 8) | (data[offset + 8] & 0xFF);
                }
                // Odometer (4 bytes in 0.1km or 1km)
                if (offset + 12 < data.length) {
                    long rawOdo = ((long)(data[offset + 9] & 0xFF) << 24)
                                | ((long)(data[offset + 10] & 0xFF) << 16)
                                | ((long)(data[offset + 11] & 0xFF) << 8)
                                | (long)(data[offset + 12] & 0xFF);
                    if (rawOdo > 0 && rawOdo < 10000000L) {
                        target.odometerKm = rawOdo > 999999 ? (rawOdo / 10.0) : rawOdo;
                    }
                }
                return true;
            }
        }

        // 2. CAN Frame (0x56 or 0x0300): Warning lamp, fuel injection, malfunction
        if (startByte == START_BYTE_56 || frameId == 0x0300 || data.length >= 8) {
            for (int i = 0; i < data.length - 2; i++) {
                if (data[i] == 0x03 && data[i + 1] == 0x0D) { // Warning lamp key (0x0300000D)
                    target.warningLamp = (data[i + 2] & 0xFF) == 1;
                } else if (data[i] == 0x03 && data[i + 1] == 0x0E && i + 3 < data.length) { // Injection amount (0x0300000E)
                    int rawInj = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
                    target.fuelInjection = rawInj / 100.0;
                }
            }
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
        return Math.min(99.9, Math.max(5.0, kmL));
    }
}
