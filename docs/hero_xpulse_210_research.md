# Hero XPulse 210 — Connectivity Research & Multi-Bike Extension Plan

> Status: research draft. Sources: official Hero App `com.customerapp.hero` v10.1.07 (versionCode 91106) split APKs, JADX + smali decompilation, Hero XPulse 210 owner's manual / parts catalogue / spec sheet, and public documentation. Anything that requires a physical bike is called out explicitly.

---

## 1. Executive summary

Hero motorcycles do **not** use a single Yamaha-style CCU. The Hero App talks to two independent connectivity systems:

| System | What it is | Transport | What it gives you | Fitted to XPulse 210? |
| :--- | :--- | :--- | :--- | :--- |
| **RideGuide cluster** | TFT/LCD instrument console | BLE UART (Telit/Stollmann Terminal I/O) | Turn-by-turn navigation, call/SMS alerts, music control, phone battery/network display | **Yes** — this is the connectivity advertised in the XPulse 210 spec sheet |
| **Hero Connect TCU** | Telematics Control Unit (separate box) | BLE, OTC Engineering `IceSDK` / `VarEx` register protocol | Speed, RPM, throttle, coolant temp, odometer, fuel, battery, GPS, lean angle, drive mode, DTC/faults → uploaded to Hero cloud | **Hardware present in parts catalogue** (`RAAAA7M001000G`), user-pairing not documented |

The important consequence for a companion app:

* The **RideGuide cluster is a display/control device, not a telemetry source.** The Hero App parses no vehicle telemetry from it. If XPulse 210 only exposes RideGuide, a third-party app can drive the cluster's navigation/call UI but cannot read speed/RPM/fuel.
* **Live telemetry only exists on the Hero Connect TCU path**, and even the official app mostly *uploads* it to Hero's backend rather than showing it. Fuel level, odometer and immobilisation status in the app UI come from a **cloud HTTP endpoint** (`DashboardAndStatus.KnowYourBike`), not from BLE.
* The Hero App itself chooses the path by `tcuid`: empty `tcuid` ⇒ RideGuide vehicle; non-empty ⇒ telematics/TCU vehicle (`DashboardWrapper.initializeRideGuideVehicle()`).

So for the XPulse 210, the realistic options are:

1. **Cluster-only integration (high confidence, no telemetry):** implement the RideGuide BLE protocol to push navigation/call/SMS/music to the TFT. This is a well-defined, fully reverse-engineered ASCII protocol.
2. **TCU integration (low confidence for this model):** *if* the fitted TCU is user-pairing-enabled and advertises, implement the OTC `VarEx` protocol to read live telemetry. This must be validated on a real bike; the owner's manual documents only cluster pairing.

---

## 2. XPulse 210 hardware evidence

### 2.1 Official specification
The XPulse 210 spec sheet lists connectivity as:

* Bluetooth (Call & SMS Alerts)
* Turn-by-Turn Navigation

No "Hero Connect", no ride data, no app telemetry. The owner's manual's Bluetooth section describes pairing the **meter console** to get navigation, call/SMS alerts, music, phone battery and network strength. That is the RideGuide cluster feature set.

### 2.2 Parts catalogue
The XPulse 210 parts list nevertheless contains:

* `TELEMATICS CONTROL UNIT` — part `RAAAA7M001000G`
* `CELL ASSY TCU`
* `UNIT BCM COMP`
* separate TFT meter assembly

The same TCU part number is also listed for the Xtreme 250R. This strongly suggests Hero Connect 2.0 hardware is fitted to at least the top variant, even though it is not advertised or documented for the XPulse 210. Whether it ships activated/pairable is unknown without a bike.

### 2.3 What this means
Two plausible configurations exist in the field:

* **Cluster-only bike:** TFT meter with RideGuide BLE, TCU absent or inactive.
* **Connected bike:** TFT meter **plus** an active Hero Connect TCU, with the cluster still doing navigation/calls.

A companion app must **discover which is present at runtime** rather than assume.

---

## 3. RideGuide cluster protocol

The cluster is a Telit/Stollmann-style **Terminal I/O (TIO)** BLE UART device. The app connects, subscribes to notifications, and exchanges **ASCII, newline-delimited** commands padded with `~`.

### 3.1 GATT profile

The app supports two equivalent UUID sets — standard Telit TIO and a custom vendor set — and treats them identically.

| Item | Telit UUID | Custom vendor UUID | Role |
| :--- | :--- | :--- | :--- |
| Primary service | `0000FEFB-0000-1000-8000-00805F9B34FB` | `E837D9A2-9C49-4493-9547-8E6918A59CA8` | RideGuide TIO service |
| RX data | `00000001-0000-1000-8000-008025000000` | `64AECB40-849A-44F1-934F-ADDC4B316423` | phone → cluster (write) |
| TX data | `00000002-0000-1000-8000-008025000000` | `B792A4BB-DB87-436A-9066-DB63C5FB3F00` | cluster → phone (notify) |
| RX credits | `00000003-0000-1000-8000-008025000000` | `F535DD6E-7975-4ABD-9719-491E38A81179` | phone writes credits |
| TX credits | `00000004-0000-1000-8000-008025000000` | `36E8614B-2DED-45C1-9AD3-C9F59A01F21E` | cluster indicates credits |
| CCCD | `00002902-0000-1000-8000-00805F9B34FB` | — | descriptor |

Connection sequence:

1. Connect, then `discoverServices()`; accept either service UUID.
2. `requestMtu(65)`.
3. Enable **indications** on TX credits, then **notifications** on TX data.
4. Write initial RX credits: `sendIntData(RX_CREDITS, 10, FORMAT_UINT8)`.
5. After a 200 ms delay set `mNotificationCounter = 1`.
6. Cluster sends `bytes[3] == 53` to confirm the TIO link is up.

The credit mechanism is largely *unused* by the Hero App: it grants a static 10 credits and never decrements them, and ignores the TX credit value. A strict implementation may need real accounting if the cluster enforces it.

### 3.2 Framing
* Every outbound message starts with `\n` and ends with `\n`.
* Variable-length text fields are right-padded with `~` to a fixed width.
* Numeric fields are fixed-width, zero-padded (`%02d`, `%05d`, `%05.1f`).
* No checksum or length prefix — the newline is the delimiter.
* Incoming payloads are wrapped as `"\n" + payload + "\n"` before parsing, so raw byte 3 corresponds to `charAt(2)`.

### 3.3 Outbound messages (phone → cluster)

Every packet is `"\n" + <digits> + <fields> + "\n"`. Fields with a fixed width are padded with `~`.

| Prefix | Purpose | Exact concatenation after `\n` |
| :--- | :--- | :--- |
| `1` | Navigation | `1` + id + dir + distance + unit + etaHour + meridian + etaMinute + demeridian + roundabout + noSignal + remainDistance + remainUnit + remainTime + nextDir + secondRoundabout + roadName |
| `2` | Incoming call (while not playing music) | `2` + number |
| `3` | Battery / network status (music active) | `3` + batteryCode + network + pad(user,17) |
| `3` | Battery / network status (navigation active) | `3` + batteryCode |
| `3` | Battery / network status (otherwise) | `3` + batteryCode + network + user |
| `5` | Missed call | `5` + number, or `5` + 2-digit count |
| `6` | SMS | `60` + count |
| `7` | Ringing call (music active) | `7` + pad(number,17) + `1` + pad("~",17) + `0` + duration + `d` |
| `7` | Active call | `7` + pad(number,17) + `2` + pad(name,17) + `0` + duration + volume |
| `7` | Outgoing call | `7` + pad(number,17) + `4` + pad(name,17) + `0` + duration + `d` |
| `7` | Call cleared / idle | `7` + pad("~",17) + `0` + pad("~",17) + `0` + pad("~",5) + `d` |
| `9` | Music | `9` + pad(song,20) + pad(album,20) + (`01` if stopped else `00`) + playbackState + duration + volume |
| `33` | Navigation status ack | (no payload) |

Call-state digit values: `0` idle/cleared, `1` ringing, `2` active, `4` outgoing. Battery codes are `0`–`4` (quintiles of charge). `d` is the "duration unknown/placeholder" marker.

> ⚠️ Decompiler caveat: JADX substitutes some string literals with Android SDK constants — e.g. it prints `ExifInterface.GPS_MEASUREMENT_2D` where the bytecode actually holds `"2"`. The table above uses the raw smali literals, verified against `SendDataToCluster.smali`, not the JADX rendering.

The direction field is **one ASCII letter**, not an integer. `setDirBinary(n)` maps a Mappls manoeuvre code `n` to a letter; the inverse table (verified against `NavigateFragment.smali`) is:

| Letter | Manoeuvre codes | Meaning |
| :--- | :--- | :--- |
| `G` | 6, 7, 8, 21, 32, 33, 50, 68 | straight / unknown |
| `I` | 0, 9, 13 | keep left / slight left |
| `J` | 3, 10, 14 | keep right / slight right |
| `E` | 1, 17 | left |
| `F` | 4, 18 | right |
| `C` | 2 | sharp left |
| `D` | 5 | sharp right |
| `Z` | 15, 19 | u-turn |
| `X` | 16, 20 | u-turn variant |
| `V` | 26–31 | roundabout / ramp |
| `Y` | 36 | ferry |
| `P` | 41 | merge |
| `K` | 42, 44, 73, 74 | highway entry/exit |
| `L` | 43, 75 | highway fork |
| `B` | 51–57 | destination / arrival |
| `U` | 65, 66, 67, 69, 70, 71, 72 | ferry / special |

Special cases: if the next step is flagged as a final destination the letter is forced to `B`; for distances below 200 m the letter is sent **lowercase**, otherwise uppercase. Some codes (`11, 12, 22–25, 34, 35, 37–40, 45–49, 58–64`) are handled through a shared default branch and are not individually distinguishable from the smali layout.

Field formats (verified in `NavigateFragment.sendDataToSpeedoMeter()` and `NavigationPacket`):

| Field | Width | Format / default |
| :--- | :---: | :--- |
| `id` | 1 | fixed `1` |
| `binaryDir` | 1 | manoeuvre letter; lowercase when distance < 200 m |
| `distance` | 5 | zero-padded; `%05d` metres, or `%05.0f`/`%05.1f` km |
| `unit` | 1 | `1` = metres, `k` = kilometres |
| `etaHour` | 2 | `%02d`, 12-hour clock |
| `meridian` | 1 | `A` (AM) / `P` (PM) |
| `etaMinute` | 2 | `%02d` |
| `demeridian` | 1 | `M` by default |
| `roundabout` | 2 | `%02d`, `01`–`09`, `00` if none |
| `noSignalPacket` | 1 | `1` if GPS lost, else `0` |
| `remainDistance` | 5 | `%05d` / `%05.0f` / `%05.1f` |
| `remainUnit` | 1 | `1` or `k` |
| `remainTime` | 4 | `~~~~` placeholder, or `%02d`+`H`/`M`/`S` |
| `nextBinaryDir` | 1 | `~` default |
| `secondRoundabout` | 2 | `04` default |
| `roadName` | 18–19 | pad `~` to 18; truncate to 18 + `~` |

String limits elsewhere: caller/contact name 17 chars, song title 20, album/artist 20, user/profile name 15.

* Navigation packets are resent on an **800 ms** cadence; route progress is polled every 5 s; BLE reconnect retries start after 10 s, then every 500 ms.
* For cluster variants without the `CommonFeatureMusic` feature (`app_type != 191`), fields 11–16 are omitted and the packet is sent in a shorter "basic" form.

### 3.4 Inbound messages (cluster → phone)

Parsed in the notification callback by byte index or character position:

| Match | Action |
| :--- | :--- |
| `bytes[3] == 10` | Accept call |
| `bytes[3] == 15` | Reject / end call |
| `bytes[3] == 35` | Play / pause |
| `bytes[3] == 53` | TIO connected handshake |
| `charAt(2) == '('` | Next track |
| `charAt(2) == '-'` | Previous track |
| `charAt(2) == 'A'` | Media volume up |
| `charAt(2) == 'F'` | Media volume down |
| `charAt(2) == '<'` | Call volume down + resend active call |
| `charAt(2) == '7'` | Call volume up + resend active call |
| `charAt(2) == '2'` | Mute toggle (adjust stream volume ±100) |

Byte 3 of the raw notification maps to `stringValue.charAt(2)` because the service wraps each payload as `"\n" + payload + "\n"`. Values `10 0x0A`, `15 0x0F`, `35 0x23`, `53 0x35` are ASCII controls; `(`, `-`, `2`, `7`, `<`, `A`, `F` are ASCII printable.

**No vehicle telemetry is read from the cluster.** All notifications are UI/control events.

---

## 4. Hero Connect TCU protocol (OTC Engineering IceSDK)

Only relevant if the bike exposes a Hero Connect TCU. Treat every claim in this section as *protocol-as-implemented-in-the-app*, not as confirmed XPulse 210 behaviour.

### 4.1 GATT profile
* Base service UUID: `0010676e-6972-6565-6e69-676e4543544f`.
* Fixed suffix constant in `OtcBle`: `SUFFIX = "10676e-6972-6565-6e69-676e4543544f"`; every characteristic UUID is `<2-hex-prefix>` + `SUFFIX`.
* Channel roles as implemented:

| Prefix | UUID | Role | Direction |
| :--- | :--- | :--- | :--- |
| `01` | `0110676e-6972-6565-6e69-676e4543544f` | VarEx command / write data | App → TCU |
| `02` | `0210676e-6972-6565-6e69-676e4543544f` | Write ACK | TCU → App |
| `03` | `0310676e-6972-6565-6e69-676e4543544f` | VarEx read response chunks | TCU → App |
| `04` | `0410676e-6972-6565-6e69-676e4543544f` | Read ACK | App → TCU |
| `09` | `0910676e-…` | Live telemetry (10 parts) | TCU → App, notify |
| `0A` | `0A10676e-…` | Auxiliary raw buffer (not parsed) | TCU → App |
| `0B` | `0B10676e-…` | Configuration/settings (33 parts) | TCU → App, notify |
| `0C` | `0C10676e-…` | Control / heartbeat / remote-action codes | TCU → App |

* BLE packet size is 20 bytes (default ATT MTU 23 minus 3-byte header); max 16 payload bytes per packet.
* Prefixes `>= 0x08` (telemetry/config/control) are **not encrypted**; prefixes `01`–`04` are encrypted only when `OtcBle.crypt == true`.
* **Encryption:** AES-128 via `Crypt.encryptBle()/decryptBle()`, initialised with a static key in the `OtcBle` constructor: ASCII `"abcdefghijklmnop"`. There is no per-device key derivation in these classes.

### 4.2 TCU type selection
At connect the app reads one byte from address `0x00000000` (`TYPE_ID`); that byte selects the register map:

| Type | Constant | Register map | Notes |
| :--- | :--- | :--- | :--- |
| 0 | `TCU_TYPE_JNS` ("OTC TCU") | `OTC.csv` | OTC RAM base `0x200004xx` |
| 1 | `TCU_TYPE_MSSL` ("Hero TCU") | `Hero.csv` | Hero RAM base `0xFEDF80xx` |
| 2 | `TCU_TYPE_JNS2` ("OTC test TCU") | `OTC_v2.csv` | test/dongle profile |
| 3 | `TCU_TYPE_MSSL2` ("Hero v2 TCU") | `Hero.csv` | **requires BLE bonding** before requests |

### 4.3 VarEx register framing
* **Read (8 bytes):** `[opcode 1B][address 4B][length 2B][checksum 1B]`
* **Write (8+N bytes):** `[opcode 1B][address 4B][length 2B][payload NB][checksum 1B]`
* **Opcodes:** `1` = read internal (`extern=false`), `2` = read external (`extern=true`), `3` = write internal, `4` = write external.
* **Address/length byte order:** **big-endian** — `intToDword()` emits `(i>>24),(i>>16),(i>>8),i` and `intToWord()` emits `(i>>8),i`.
* **Checksum:** 8-bit sum of every preceding byte, `sum & 0xFF` (two's-complement *not* used here, unlike Yamaha).
* **Worked read** of `TYPE_ID` (`0x00000000`, length 1, internal): `01 00 00 00 00 00 01 02`.
* **Worked write** of `0x01` to `NOTIF_ENABLE` (`0x20001803`, internal): `03 20 00 18 03 00 01 01 40`.

> Note the deliberate split: **VarEx headers are big-endian, but the telemetry payload moved over chars 09/0B is little-endian** (see 4.4). Do not use one decoder for both.

### 4.4 Live telemetry notifications
* Every notification is 20 bytes: **16 data bytes (0–15) + checksum (byte 16) + part index (byte 17)**; bytes 18–19 are padding.
* **Char 09** (live telemetry): 10 parts, 16 bytes each → a 160-byte record (`ch9Full[part]`). Parts are also decoded immediately by `DataStatusProcessor.processCh9Part1..10`.
* **Char 0B** (configuration/identity): 33 parts — panic/theft/sleep timers, geofence, buzzer tones, BLE MAC, comms keys, immobiliser slots, APN/SMS server config, VIN, IMEI, ICCID, ECU fingerprints, speed limit, ECO/RIDE/SPORT profiles.
* **Char 0A**: cached raw buffer, not parsed.
* **Char 0C**: heartbeat + remote-action codes; `data[10] == 1` triggers a TCU "resend from start".
* **Payload endianness:** little-endian. `DataEntry.getInt()` accumulates `i = (i << 8) + data[len-1 … 0]`, so byte 0 is the LSB. Floats are `Float.intBitsToFloat(getInt())` (IEEE-754 float32, little-endian).
* **No scaling is applied in the app** — scale 1.0, offset 0. Engineering units are implicit (km/h, km, rpm, °C, degrees) and any decimal scaling happens on Hero's backend or in UI adapters.

Key char-09 fields (part / offset within the 16-byte part):

| Metric | Part | Offset | Width | Type | Notes |
| :--- | :---: | :---: | :---: | :--- | :--- |
| `VehicleEvent` | 1 | 0 | 2 | u16 LE | event id |
| `FAULTS` | 1 | 2 | 9 | bytes | DTC bitfield |
| `FLAGS` | 1 | 11 | 2 | u16 LE | alert flags |
| `BMS` | 1–5 | 13… | 57 | bytes | split across parts 1–5 |
| `GPSLAT` | 5 | 6 | 4 | float LE | decimal degrees |
| `GPSLONG` | 5 | 10 | 4 | float LE | decimal degrees |
| `MSLALTITUDE` | 5+6 | P5:14, P6:0 | 4 | float LE | metres |
| `SPEEDOVERGROUND` | 6 | 2 | 4 | float LE | GPS speed |
| `COURSEOVERGROUND` | 6 | 6 | 4 | float LE | GPS heading |
| `BATTERYFUEL` | 6 | 10 | 2 | u16 LE | fuel gauge / EV battery |
| `LEANANGLE` | 6 | 12 | 4 | float LE | degrees |
| `ENGINETEMPERATURE` | 7 | 0 | 1 | u8 | °C |
| `CURRENTDATE` | 7 | 1 | 6 | bytes | date/time |
| `VehicleSpeed` | 7 | 7 | 1 | u8 | km/h |
| `Throttle` | 7 | 8 | 2 | bit-packed | `((d[8]&0xE0)>>5) | (d[9]<<5)` |
| `RemainingRangeInKM` | 7 | 11 | 1 | nibbles | `((d[11]&0xF0)>>4) | ((d[12]&0x0F)<<4)` |
| `DriveModesignal` | 7 | 11 | 4 bits | u8 | `d[11] & 0x0F` |
| `MileageTrip` | 7 | 12 | 2 | bit-packed | `((d[12]&0xF0)>>4) | (d[13]<<4)` |
| `EngineSpeedRPM` | 9 | 8 | 3 | bit-packed | `(d[8]>>3) + (d[9]<<5) + ((d[10]&7)<<13)` |
| `GearShiftPattern` | 9 | 8 | 3 bits | u8 | `d[8] & 0x07` |
| `CoolantTemeprature` | 9 | 12 | 1 | u8 | °C |
| `ODOSignal` | 9+10 | P9:15, P10:0 | 3 | u24 LE | odometer |
| `ICVehicleSpeed` | 10 | 2 | 1 | u8 | cluster speed |
| `ABSFrontWheelPressure` | 10 | 5 | 2 | bit-packed | wheel speed |
| `StatusABS` | 10 | 7 | 2 bits | u8 | ABS state |
| CAN/bike flags | 10 | 10 | 1 | bits | `BIKECANOFF/BUSOFF/HEAVY/OK`, `BIKEONROUTE/PARKED`, `DONGLESTATUS` |
| operating-state flags | 10 | 11 | 1 | bits | `OPERATIONAL`, `STANDBY`, sleep variants |
| BLE-state flags | 10 | 12 | 1 | bits | `BLEOFF/ADV/PAIRING/CONNECTED/DISCONNECTED` |
| `Heartbeat`, `RemoteActionCode` | char 0C | 0–2 | — | — | control channel |

* `realTimeData9.csv` lists 237 entries: char 9 entries 1–83, char 0B entries 84–235, char 0C entries 236–237.
* The app uploads only when **all 10 char-9 parts are present** (`ch9Full.keySet().size() >= 10`) and at most **once every 5 s**, as `LocationAndSecurity.DeviceLiveData.rawData` (160 bytes) to `dongleLiveData`.
* The UI never displays these decoded fields — they are parsed, stored in `Status.mData`, and shipped to the backend.

### 4.5 Cloud dependency
`IceSDK.IceDashboardResponse` is populated from the HTTP endpoint `DashboardAndStatus.KnowYourBike` via `NetworkSDK`. It carries odometer, fuel indicator (High/Med/Low), timestamp, cloud-connected flag, and BLE-connected flag. **Fuel and odometer shown in the app are cloud values, not BLE values.** A third-party app cannot reproduce this without Hero account credentials and API access.

> ⚠️ Legacy note: `assets/bleSpec.csv` (16-bit registers, e.g. `CAR_DATA 0x287C`) and `assets/bleCharacteristics.csv` (char 9/0A/0B bitfields) look like an **older OTC SDK generation**. The shipping code path uses `realTimeData9.csv` + `Hero.csv`/`OTC.csv`/`OTC_v2.csv`. Do not treat the bitfield CSVs as authoritative without a caveat.

---

## 5. What metrics are actually obtainable

| Metric | RideGuide cluster | Hero Connect TCU | Source / note |
| :--- | :---: | :---: | :--- |
| Speed / RPM | ✗ | ✓ (if TCU pairable) | `VehicleSpeed`, `ICVehicleSpeed`, `EngineSpeedRPM` |
| Throttle / coolant temp | ✗ | ✓ | `Throttle`, `ENGINETEMPERATURE`, `CoolantTemeprature` |
| Odometer | ✗ | ✓ | `ODOSignal` (u24, split P9/P10) |
| Fuel level | ✗ | ✓ (raw) | `BATTERYFUEL`; app UI shows cloud High/Med/Low |
| Battery voltage | ✗ | partial | `DCLinkVoltageMCU` is EV traction; ICE aux is flag-only |
| GPS position/speed/heading | ✗ | ✓ | `GPSLAT`, `GPSLONG`, `SPEEDOVERGROUND`, `COURSEOVERGROUND` |
| Lean angle | ✗ | ✓ | `LEANANGLE` |
| Drive mode / gear | ✗ | ✓ | `DriveModesignal`, `GearShiftPattern` |
| DTC / fault codes | ✗ | ✓ | `FAULTS`, `FLAGS` |
| Turn-by-turn nav control | ✓ | ✗ | cluster |
| Call / SMS / music control | ✓ | ✗ | cluster |
| Phone battery / network on cluster | ✓ | ✗ | cluster |

**Bottom line for XPulse 210:** if only the RideGuide cluster is active, a companion app can control the cluster but gets **zero vehicle telemetry**. Live telemetry requires a pairable Hero Connect TCU, which is not documented for this model.

---

## 6. Confidence & open questions

| Claim | Confidence | Evidence |
| :--- | :--- | :--- |
| XPulse 210 ships a RideGuide TFT cluster with nav/call/SMS | High | spec sheet, owner's manual, APK |
| RideGuide protocol details above | High | decompiled `SendDataToCluster`, `NavigationPacket`, `NavigateFragment` + smali |
| XPulse 210 parts list includes a TCU | High | parts catalogue |
| TCU is user-pairable / Hero Connect active on XPulse 210 | **Low** | not documented; requires bike |
| Advertised BLE names for either system | **Unknown** | not derivable from APK |
| Real GATT layout / MTU on the bike | **Unknown** | requires scan |
| TCU supplier & internal CAN IDs | Partial | OTC SDK strongly implied; CAN IDs not proven |

---

## 7. Real-bike validation procedure

1. **Scan** with nRF Connect / BetterScan; record advertised names, service UUIDs, RSSI, and manufacturer data with the ignition on.
2. **Classify** each device: service `0000FEFB-…` ⇒ RideGuide cluster; service starting `0010676e-…` ⇒ OTC TCU.
3. **Cluster:** subscribe to char `00000002`, write `\n33\n` to char `00000001`, confirm the `53` handshake, then send a synthetic navigation packet and observe the TFT.
4. **TCU:** attempt bonding/pairing; if the device accepts and exposes chars `01/02/09/0A/0B/0C`, perform a VarEx read of a known register from `Hero.csv` and verify the checksum echo.
5. **Record** exact MTU, credit behaviour, notification sizes, and whether the cluster and TCU can be connected simultaneously.
6. **Capture** a 10-minute ride log of char 9 notifications to validate the `DataStatusProcessor` offsets against real motion (speed vs GPS, RPM vs sound).

---

## 8. Multi-bike extension plan for `xsr-companion`

### 8.1 Current coupling
`XsrBleService` is Yamaha-specific: it hardcodes the NUS UUIDs, the `0xAA`/`0x5A` framing, SCCU1 authentication, and packet IDs. `BikeTelemetry` is the only reusable piece.

### 8.2 Target architecture

```
app/
  core/
    BikeTelemetry            (canonical metrics model — keep, extend fields)
    BikeProfile              (interface: identity, capabilities, connect(), disconnect())
    BikeProfileRegistry      (detect profile from scan result)
  protocol/
    yamaha/  YamahaSccu1Profile   (existing logic, moved behind interface)
    hero/
      HeroRideGuideProfile        (cluster: nav/call/SMS/music, no telemetry)
      HeroConnectTcuProfile       (OTC VarEx; telemetry if pairable)
  service/
    BikeConnectionService    (generic; delegates to active profile)
```

### 8.3 `BikeProfile` interface sketch

```java
public interface BikeProfile {
    String id();                                   // "yamaha-sccu1", "hero-rideguide", "hero-connect-tcu"
    String displayName();
    Set<Capability> capabilities();                // TELEMETRY, NAV_PUSH, CALL_UI, MUSIC_UI, FAULTS
    boolean matches(ScanResult result);            // service UUID / name match
    void connect(BluetoothGatt gatt, Listener l);
    void disconnect();
    // optional feature surfaces
    interface NavigationSink { void pushNavigation(NavUpdate u); }
    interface TelemetrySource { void subscribe(TelemetryListener l); }
}
```

### 8.4 Capability matrix drives the UI

| Capability | Yamaha SCCU1 | Hero RideGuide | Hero Connect TCU |
| :--- | :---: | :---: | :---: |
| Live telemetry | ✓ | ✗ | ✓ (conditional) |
| Push navigation | ✓ | ✓ | ✗ |
| Call/SMS UI on bike | ✗ | ✓ | ✗ |
| Music control on bike | ✗ | ✓ | ✗ |
| Fault codes | ✓ | ✗ | ✓ |

The UI binds widgets to capabilities instead of assuming a Yamaha bike. A Hero RideGuide connection shows nav/call/music panels and hides the speed/RPM gauge; a Hero TCU connection shows gauges and hides cluster controls.

### 8.5 Migration steps

1. Extract `BikeTelemetry` to `core/` and add fields the Hero TCU can supply (GPS, lean, gear, DTC).
2. Introduce `BikeProfile` + `BikeProfileRegistry`; wrap the existing `YamahaProtocol`/`XsrBleService` as `YamahaSccu1Profile` with no behaviour change.
3. Add `HeroRideGuideProfile` (pure protocol port; highest confidence, testable with the app's own packet builders).
4. Add `HeroConnectTcuProfile` behind a feature flag until real-bike validation passes.
5. Make `BikeConnectionService` profile-agnostic; keep Yamaha as the default.
6. Add a "Scan & detect" screen that lists bikes by advertised service and lets the user pick the profile if ambiguous.

### 8.6 Risks

* **No telemetry on XPulse 210** if the TCU is not pairable — the app must not promise gauges it cannot deliver.
* **Cloud lock-in** — Hero fuel/odometer live in Hero's backend; do not design around scraping it.
* **Encryption** — OTC VarEx encryption/keys are inside `OtcBle`/`Crypt`; verify licensing/legality before shipping.
* **Protocol drift** — Hero App updates may change packet layouts; pin the researched version (10.1.07) in the adapter.

---

## 9. Appendix — source map

| Topic | File |
| :--- | :--- |
| RideGuide outbound packets | `com/customerapp/hero/views/fragments/rideGuide/SendDataToCluster.java` |
| Navigation packet fields | `com/customerapp/hero/thorwrapper/rideguidewrapper/NavigationPacket.java` |
| Navigation values/timing/direction | `com/customerapp/hero/views/fragments/quickAction/NavigateFragment.java` (+ smali `setDirBinary`) |
| RideGuide GATT + inbound parsing | `com/customerapp/hero/ride_guide/BluetoothConnectionService.java` |
| OTC transport/registers | `com/otcengineering/otcble/OtcBle.java`, `VarEx.java` |
| OTC telemetry decoding | `com/otcengineering/otcble/DataStatusProcessor.java`, `Status.java` |
| OTC field catalogue | `assets/realTimeData9.csv` |
| Register maps | `assets/Hero.csv`, `assets/OTC.csv`, `assets/OTC_v2.csv` |
| Cloud dashboard | `com/otcengineering/otcble/IceSDK.java`, `NetworkSDK` |
| Profile selection | `DashboardWrapper.initializeRideGuideVehicle()` |
| Cloud dashboard API | `DashboardAndStatus.KnowYourBike` via `NetworkSDK` |

### 9.1 Provenance

Extraction workflow:

1. Split APKs unzipped; `base.apk` decompiled with JADX 1.5.6 (`-j 8`, `--no-debug-info`).
2. Because JADX failed on some methods (notably `setDirBinary`), `apktool` smali was generated in parallel and used as ground truth.
3. Protocol tables were produced by delegated extraction runs (`agy`, Gemini 3.8 Flash) against narrow, read-only file sets, then **independently re-verified** against source:
   * VarEx header byte order — corrected to **big-endian** (agy assumed little-endian).
   * Char 0B role — corrected to **configuration/identity**, not telemetry.
   * RideGuide `-` key — corrected to **previous track**, not mute.
   * JADX string-constant substitutions (`"2D"`/`"3D"`) — corrected to raw smali literals `"2"`/`"3"`.
4. Everything marked *Unknown* requires a physical XPulse 210 or Hero Connect–equipped bike.

Decompiled artefacts used during this research live outside the repo in `/tmp/opencode/` and are not required to build the companion app.
