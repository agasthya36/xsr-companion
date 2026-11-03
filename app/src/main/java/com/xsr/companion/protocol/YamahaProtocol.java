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

    public static final byte START_BYTE_A6 = (byte) 0xA6; // 166 or -90
    public static final byte START_BYTE_5A = (byte) 0x5A; // 90
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

        // Check if packet matches 0x0148 / 0x0200 ICE Local data frame:
        // Byte 0: Start (0x5A or 0xA6 or 0xA7)
        // Byte 2-3: Frame ID
        int frameId = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);

        // Standard LOCAL Frame: Engine RPM, Speed, Throttle, Temp, ODO, DTC
        if (frameId == 0x0148 || frameId == 0x0200 || data[0] == 0x5A || data[0] == (byte) 0xA7) {
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
                if (offset + 7 < data.length) {
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

        // Standard CAN Frame (0x0300): Warning lamp & injection
        if (frameId == 0x0300 || data.length >= 8) {
            for (int i = 0; i < data.length - 2; i++) {
                if (data[i] == 0x03 && data[i + 1] == 0x0D) { // Warning lamp key
                    target.warningLamp = (data[i + 2] & 0xFF) == 1;
                }
            }
            return true;
        }

        return false;
    }
}
