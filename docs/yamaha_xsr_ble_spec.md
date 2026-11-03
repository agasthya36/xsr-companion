# Yamaha XSR 155 (SCCU1) Reverse-Engineered BLE Protocol & Lightweight App Architecture

## 1. Overview
This specification details the reverse-engineered Bluetooth Low Energy (BLE) protocol used by Yamaha motorcycles equipped with the **SCCU1** (Simple Communication Control Unit - Internal Combustion Engine), specifically the **Yamaha XSR 155**, MT-15, and R15 V3/V4 series.

The official Yamaha Motor On / Y-Connect app (`jp.co.yamaha_motor.yamahamotoron`) is ~90.7 MB and heavily loaded with tracking telemetry (Sentry APM, Firebase Analytics, Google Measurement, SAP Gigya, AWS/CloudFront sync). This architecture document provides the exact byte-level protocol implemented in our **50 KB**, 100% offline, privacy-first companion application.

---

## 2. Bluetooth LE Transport Layer

### 2.1 GATT Service & Characteristics
Yamaha CCU utilizes the standard **Nordic UART Service (NUS)**:

| Item | UUID | Description |
| :--- | :--- | :--- |
| **Primary Service** | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | Nordic UART Service |
| **RX Characteristic** | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | Write / Write Without Response (Phone → Bike) |
| **TX Characteristic** | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | Notify (Bike → Phone) |
| **CCCD** | `00002902-0000-1000-8000-00805F9B34FB` | Client Characteristic Configuration Descriptor |

### 2.2 Device Discovery & Identification
* **Advertised BLE Device Name**: `YCCU_<CCUID>` or `YSCCU_<CCUID>`
* **CCU ID Format for SCCU1 (ICE)**: Starts with prefix `"0000"` (e.g. `0000XXXXXXXX`).
* **MTU**: Negotiated default up to `512` bytes, standard packet payload fits comfortably within standard BLE MTU (8–27 bytes).

---

## 3. Packet Framing & Checksum Algorithm

All outbound messages to the CCU follow Yamaha's CAN-over-BLE framing:

$$\text{Frame} = [\text{StartByte}, \text{Group}, \text{LocalID (2B)}, \text{Length (1B)}, \text{Payload (N B)}, \text{Counter (1B)}, \text{Checksum (1B)}]$$

### 3.1 Checksum Calculation
The checksum is calculated across all preceding bytes using two's complement of the 8-bit sum:

```java
public static byte calculateChecksum(byte[] data, int length) {
    int sum = 0;
    for (int i = 0; i < length; i++) {
        sum += (data[i] & 0xFF);
    }
    return (byte) ((256 - (sum & 0xFF)) & 0xFF);
}
```

---

## 4. Inbound & Outbound Packets

### 4.1 Telemetry Streaming Trigger (`0x7F01` - `SCCU1StartProcEnt`)
* **Trigger**: Transmitted immediately upon BLE GATT connection and service discovery.
* **Length**: 8 bytes.
* **Effect**: Commands the CCU to start streaming continuous `LOCAL` and `CAN` frames over the TX Characteristic at 100–1000 ms intervals.

| Offset | Size | Field | Value |
| :---: | :---: | :--- | :--- |
| `0` | 1 | `startByte` | `0x5A` |
| `1` | 1 | `group` | `0x01` |
| `2-3` | 2 | `localId7F01` | `0x7F, 0x01` (ID `0x7F01`) |
| `4` | 1 | `length` | `0x01` |
| `5` | 1 | `startProcessingFlag` | `0x01` (1 = Start streaming) |
| `6` | 1 | `counter` | Rolling counter (`0..255`) |
| `7` | 1 | `checksum` | Two's complement checksum |

---

### 4.2 Periodic Time & Phone Battery Packet (`0x058A` - `SCCU1CanMsg058AEnt`)
* **Interval**: Transmitted every **1000 ms** (1.0 second).
* **Length**: 15 bytes.

| Offset | Size | Field | Description / Value |
| :---: | :---: | :--- | :--- |
| `0` | 1 | `startByte` | `0xA6` (`-90` signed byte) |
| `1` | 1 | `group` | `0x01` |
| `2-3` | 2 | `localId058A` | `0x05, 0x8A` (Big-Endian ID `0x058A`) |
| `4` | 1 | `length` | `0x08` (Payload length = 8 bytes) |
| `5-6` | 2 | `year` | Current Year (e.g. 2026 = `0x07, 0xEA`) |
| `7` | 1 | `month` | Current Month (`1` to `12`) |
| `8` | 1 | `day` | Current Day (`1` to `31`) |
| `9` | 1 | `hour` | Current Hour (`0` to `23`) |
| `10` | 1 | `minute` | Current Minute (`0` to `59`) |
| `11` | 1 | `second` | Current Second (`0` to `59`) |
| `12` | 1 | `battery` | **Phone battery percentage shifted left by 1**: `(batteryPercent << 1)` (e.g., 85% = `170` = `0xAA`) |
| `13` | 1 | `counter` | Incremental rolling counter (`0` to `255`) |
| `14` | 1 | `checksum` | Two's complement checksum of bytes 0..13 |

---

### 4.3 Call & Notification Indicator Packet (`0x058B` - `SCCU1CanMsg058BEnt`)
* **Interval**: Transmitted every **1000 ms** (offset 100 ms after `0x058A`) or immediately upon state change.
* **Length**: 9 bytes.

| Offset | Size | Field | Description / Value |
| :---: | :---: | :--- | :--- |
| `0` | 1 | `startByte` | `0xA6` |
| `1` | 1 | `group` | `0x01` |
| `2-3` | 2 | `localId058B` | `0x05, 0x8B` (Big-Endian ID `0x058B`) |
| `4` | 1 | `length` | `0x02` (Payload length = 2 bytes) |
| `5` | 1 | `notificationUpper` | Packed 2-bit flags: <br>• Bits 7-6: **Incoming Call Flag** (`0`=None, `1`=Ringing)<br>• Bits 5-4: **Missed Call Flag** (`0`=None, `1`=Missed)<br>• Bits 3-2: **Active Call Flag**<br>• Bits 1-0: **Message Flag 1** |
| `6` | 1 | `notificationLower` | Packed 2-bit flags: <br>• Bits 7-6: **SNS / App Notification Flag**<br>• Bits 5-4: **Email Flag**<br>• Bits 3-0: Reserved |
| `7` | 1 | `counter` | Incremental rolling counter (`0` to `255`) |
| `8` | 1 | `checksum` | Two's complement checksum of bytes 0..7 |

---

## 5. Bike-Emitted Telemetry & Slot Keys

When streaming is activated, the bike broadcasts `LOCAL` and `CAN` frames containing vehicle data:

| Slot Key | Metric | Type | Scale / Units | Description |
| :---: | :--- | :---: | :---: | :--- |
| `0x02000001` | Engine RPM | UShort | 1 RPM | Real-time crankshaft engine speed (0–15,000 RPM) |
| `0x02000002` | Wheel Speed | UByte | 1 km/h | Rear/front wheel speed sensor reading (0–255 km/h) |
| `0x02000004` | Throttle Angle | Double | 0.0–100.0% | Wrist throttle position |
| `0x02000005` | Coolant Temp | UByte | 1 °C | Cylinder head coolant temperature |
| `0x02000006` | Air Temp | UByte | 1 °C | Intake airbox temperature |
| `0x02000007` | Barometric Pressure | Double | kPa | Ambient atmospheric pressure |
| `0x02000008` | DTC Fault Count | UByte | Integer | Number of active diagnostic trouble codes |
| `0x02000009` | Active DTC Code | UShort | Code | Active Diagnostic Trouble Code (e.g. 12, 21, 46) |
| `0x0200000A` | Total Odometer | Double | km | Cumulative odometer distance |
| `0x0300000D` | Warning Lamp | UByte | 0 or 1 | Check Engine warning light state |
| `0x0300000E` | Fuel Injection | Double | Flow rate | Instantaneous injector pulse width |
| `0x01000101` | ECU Run Time | UInt | Seconds | Total cumulative ECU power-on operating time |
| `0x01000102` | Ignition Count | UShort | Cycles | Total engine start / ignition cycles |

---

## 6. Offline Diagnostic Trouble Code (DTC) Dictionary

The app contains an offline dictionary for Yamaha 155cc VVA engines (XSR 155, MT-15, YZF-R15 V3/V4):

| DTC | Title | Symptom | Action / Troubleshooting | Severity |
| :---: | :--- | :--- | :--- | :---: |
| **12** | Crankshaft Position Sensor | Engine cranks but will not fire. | Inspect pickup coil connector, wiring, and air gap. | Critical |
| **13** | Intake Air Pressure Sensor (Open/Short) | Sluggish throttle; runs in fail-safe. | Inspect MAP sensor harness and connector. | Warning |
| **14** | Intake Air Pressure Sensor Pipe Detached | Erratic idle; stalls under acceleration. | Inspect vacuum hose to intake pressure sensor. | Warning |
| **15** | Throttle Position Sensor (TPS) Circuit | Rough acceleration, hesitation. | Check TPS harness and sensor alignment. | Warning |
| **16** | Throttle Position Sensor (TPS) Stuck | Unstable idle; RPM restricted. | Check throttle cable tension; replace TPS if stuck. | Warning |
| **19** | Sidestand Switch Circuit Broken | Engine cuts out immediately when shifted into gear. | Check sidestand switch plunger, pivot spring, and plug. | Critical |
| **21** | Coolant Temperature Sensor | Hard starting; radiator fan runs continuously. | Check coolant temp sensor plug near cylinder head. | Warning |
| **22** | Intake Air Temperature Sensor | Slight drop in fuel economy. | Inspect airbox temp sensor plug. | Info |
| **24** | O2 Sensor Abnormal Signal | Increased emissions; black plug fouling. | Check O2 sensor wiring near exhaust downpipe. | Warning |
| **28** | Coolant Over-Temperature | Severe engine overheating (>115°C). | Immediately pull over, key off, check coolant & fan. | Critical |
| **30** | Lean Angle Sensor Activated | Motorcycle fell over (>65° tilt); fuel cut. | Upright bike, cycle ignition key OFF then ON. | Critical |
| **33** | Ignition Coil Primary Circuit | Engine misfires or fails to start. | Check ignition coil terminal, plug boot, and ground. | Critical |
| **37** | Idle Speed Control (ISC) Valve | Engine stalls at stops or idles >2,000 RPM. | Clean throttle body bypass passage; check ISC plug. | Warning |
| **39** | Fuel Injector Circuit | Engine will not run; no fuel delivery. | Measure injector resistance (~12 ohms) and wiring. | Critical |
| **42** | Wheel Speed Sensor / Neutral Switch | Speedo reads 0 km/h; gear indicator lost. | Check front wheel speed sensor and neutral switch. | Warning |
| **44** | ECU EEPROM Memory Error | Intermittent erratic operation. | Disconnect battery for 10 min; replace ECU if code persists. | Critical |
| **46** | Charging System / Voltage Abnormal | Battery voltage <10V or >16V. | Test stator AC voltage and regulator/rectifier DC output. | Critical |
| **50** | ECU Internal Fault | Engine stalls or fails to boot. | Check ECU ground; dealer ECU replacement required. | Critical |
| **84** | VVA Solenoid Valve Malfunction | VVA high-cam profile disabled above 7,400 RPM. | Inspect VVA solenoid connector on cylinder head cover. | Warning |
| **96** | Emergency Stop / Killswitch Active | Engine will crank but starter is inhibited. | Verify handlebar red killswitch is set to RUN. | Info |

---

## 7. Odometer-Driven Maintenance Tracker

The app automatically syncs the motorcycle's cumulative odometer (`0x0200000A`) on connection and tracks maintenance intervals:

* **Drive Chain Clean & Lube**: Every 500 km
* **Engine Oil (Yamalube 10W-40)**: Every 3,000 km (First at 1,000 km)
* **Oil Filter Element**: Every 6,000 km
* **Brake Pads & Fluid**: Every 5,000 km
* **Spark Plug (NGK MR8E9)**: Every 10,000 km
* **Air Cleaner Element**: Every 12,000 km
* **Coolant Replacement**: Every 20,000 km

Each item calculates `remainingKm = interval - (currentOdo - lastServiceOdo)`. Badges turn amber within 10% of interval and red when overdue.

---

## 8. Zero-Click Ride Tracker & GPX 1.1 Exporter

### 8.1 Hands-Free Operation
1. **Auto-Start**: Initiates on BLE connect (`currentRideId = rideDb.startNewRide()`). Subscribes to GPS `LocationManager`.
2. **Telemetry Pairing**: Samples at 1000 ms intervals:
   * GPS Coordinates `(lat, lon)`
   * Bike Wheel Speed (km/h)
   * Engine RPM
   * Wrist Throttle %
   * Engine Coolant Temp (°C)
3. **Auto-Finalize & Discard Filter**:
   * On ignition key-off (BLE disconnect), computes duration, distance, top speed, average speed, max RPM.
   * If total movement < 50 meters and max speed < 5 km/h, the trip is discarded (prevents clutter when testing key in garage).
   * Generates standard GPX 1.1 file in `getExternalFilesDir("rides")` with Garmin `TrackPointExtension` (`<gpxtpx:cad>` for RPM, `<gpxtpx:temp>` for Coolant Temp).
   * Automatically records exact last parked GPS coordinate.

---

## 9. APK Footprint Comparison

| Metric | Official Yamaha Motor On App | XSR Companion App |
| :--- | :---: | :---: |
| **APK Size** | **90.7 MB** | **50 KB** (0.05 MB) |
| **Internet Permission** | Required (`android.permission.INTERNET`) | **None** (100% Offline) |
| **Cloud Analytics / Trackers** | Sentry, Firebase, Gigya, Google Tag | **Zero** |
| **GPX / CSV Ride Export** | None (Locked in Yamaha Cloud) | **Instant One-Click Export** |
| **DTC Diagnostics** | Vague warning, visit dealer | **Plain-English Explanations** |
| **Maintenance Tracker** | Cloud/dealer based | **Real Odometer-Driven** |
| **Memory Footprint** | ~180 MB RAM | **~18 MB RAM** |
