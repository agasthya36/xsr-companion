# Yamaha XSR 155 Companion

A privacy-first Android companion for the **Yamaha XSR 155** (and compatible **MT-15**, **YZF-R15 V3/V4**, and other Yamaha models using the `SCCU1_ICE` / Y-Connect CCU).

Version 2.1 is a full Material 3 rewrite with five focused screens, automatic ride recording, fuel and service tracking, DTC diagnostics, GPX export, and an optional OpenStreetMap ride-route viewer. There is no account, no analytics, and no telemetry upload; network access is used only for map tiles.

---

## What's new in 2.1

### Ride route map
- **View route on map** on any recorded ride, drawn from the locally stored GPS points.
- OpenStreetMap vector tiles (MapLibre GL inside a WebView), start/finish markers, automatic camera fit, and a dark basemap.
- **No API key required and none is bundled.** Tiles are served by [OpenFreeMap](https://openfreemap.org/), which is free, keyless, and built from OpenStreetMap data.
- Rides without GPS points disable the button instead of opening an empty map.

### Link stability
- Clock/battery sync (`0x058A`) and notification flags (`0x058B`) are now sent **once per second**, matching the official app. The CCU drops the link about 15 s after authentication if `0x058A` stops arriving.

---

## What's new in 2.0

### Material 3 UI
- **Dashboard** — live speed, RPM, coolant, throttle, odometer, ride stats, fuel gauge, range, and the next service item.
- **Rides** — lifetime totals plus ride history, speed charts, per-ride stats, GPX sharing, and full ZIP export.
- **Maintenance** — seven odometer-driven service items with configurable intervals and service history.
- **Diagnostics** — live link status, plain-English DTC explanations, DTC history, and the rolling live log.
- **Settings** — auto-connect, CCU passkey, units, tank/reserve size, alert thresholds, and data management.

### Battery and CPU
- UI refreshes every **2 s** while a screen is open; the foreground service processes at **5 s** in the background.
- Telemetry frames are parsed at most **5 Hz**, even though the CCU streams at 10 Hz.
- Fuel is integrated using **real elapsed time** and flushed to prefs only every ~30 s.
- GPS is adaptive: 3 s / 8 m while moving, stopped after 30 s stationary, resumed when wheel speed exceeds 2 km/h.
- BLE uses `CONNECTION_PRIORITY_BALANCED`, low-power scanning, a 12 s scan timeout, and stops the service when idle and unbound.
- No per-packet broadcasts or database writes. Ride points are written only while moving, once per GPS interval.
- The route map only runs while it is open; tiles stop loading when you leave it.

### Privacy / release hardening
- Network access is limited to OpenStreetMap vector tiles in the ride route viewer.
- `android:allowBackup="false"` and no cleartext traffic.
- File provider is non-exported with canonical-path validation.
- Release builds use R8 + resource shrinking and optional signing via `keystore.properties`.

---

## Features

### Riding
- Zero-click ride recording starts when the bike moves, not merely when the ignition is on.
- Records wheel speed, RPM, throttle, coolant, and GPS every 3 s.
- Discards stationary sessions under 50 m / 5 km/h so the garage never fills with empty rides.
- Standard GPX 1.1 export with Garmin TrackPoint extensions, plus CSV export of rides, fuel, and service history.
- Ride route on OpenStreetMap with start/finish markers, drawn from the recorded GPS points.
- Ride-recap notification and per-ride charts (no charting library).

### Fuel
- Remaining fuel in litres/gallons, percentage, and estimated range.
- Full and partial refuels; full fills anchor real tank-to-tank mileage.
- Low-fuel reserve warning.
- Fuel log persisted in SQLite.

### Maintenance
- Chain clean & lube, engine oil, oil filter, brake pads/fluid, spark plug, air filter, coolant.
- Intervals and last-service odometer are stored locally.
- New (used) bikes are automatically baselined at the current odometer on first sync.
- Service history with notes and cost.

### Diagnostics
- Parses DTC count/code and warning lamp from CAN frames.
- 20+ offline Yamaha 155cc DTC descriptions with severity, symptoms, and suggested action.
- DTC history stored locally.
- Exportable rolling BLE/application log.

### Cluster sync
- Phone battery and clock sync (`0x058A`).
- Incoming/missed call indication (`0x058B`).
- Last parked location saved at disconnect with one-tap navigation.

---

## Privacy guarantee

```
No analytics.
No trackers.
No cloud accounts.
No telemetry uploads.
Network access only for OpenStreetMap tiles.
```

All rides, GPS points, fuel entries, service records, and diagnostic logs stay in the app's private storage. The only network traffic the app can generate is OpenStreetMap vector-tile requests while a ride route is open. Data leaves the phone otherwise only when you explicitly share or export it.

---

## Requirements

- Android **17** (API 37) or newer. This build targets and requires API 37.
- JDK 17.
- Android SDK with API 37 and Build-Tools 37.

The project uses AGP **9.5.0**, Gradle **9.8.2**, Material Components **1.14.0**, and Java 17 source/target.

---

## Build

```bash
cd xsr-companion
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Run unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

### Map tiles (no API key)

The ride-route viewer renders locally stored GPS points with
[MapLibre GL JS](https://maplibre.org/) inside a WebView, on top of the
[OpenFreeMap](https://openfreemap.org/) dark style built from
[OpenStreetMap](https://www.openstreetmap.org/copyright) data.

- **No Google Maps SDK, no API key, and no key is compiled into the APK.**
- Only vector tiles, glyphs and sprites are requested; the route itself never
  leaves the phone.
- The HTML lives in `app/src/main/assets/map.html`; MapLibre GL JS 5.9.0 is
  bundled in `app/src/main/assets/maplibre/`, so only map data needs the
  network. To use a different provider, change the `style` URL in `map.html`
  and keep the attribution.
- If the phone is offline, the route still draws over an empty dark canvas.
- OpenFreeMap requires no account or key. The map shows the required
  OpenStreetMap/OpenFreeMap attribution in the bottom-right corner.

### Signed release

Create a keystore (once) and a `keystore.properties` file in the project root:

```properties
storeFile=release/xsr-release.jks
storePassword=...
keyAlias=xsr
keyPassword=...
```

Then:

```bash
./gradlew :app:assembleRelease
```

If `keystore.properties` is absent, release builds fall back to debug signing so the build still works for local testing. Keep the keystore and properties file backed up and out of version control.

---

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you are replacing a build signed with a different key (for example the
original debug-signed v1.0), Android refuses an in-place update. Uninstall the
old package first; its private app data is removed with it.

The first launch asks for Bluetooth, location, notification, and phone-state permissions. Bluetooth and location are required for the CCU link and ride GPS; notifications and phone-state are optional (alerts and call relay).

---

## Protocol

The CCU exposes the Nordic UART Service (NUS):

- Service: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- RX (phone writes): `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- TX (phone notifies): `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`

See [the full protocol notes](docs/yamaha_xsr_ble_spec.md) for framing, checksums, TLV/CAN identifiers, authentication, and fuel calculations.

---

## Compatibility

- Yamaha XSR 155 (all years with CCU)
- Yamaha MT-15 V2
- Yamaha YZF-R15 V3 / V4 / R15M
- Yamaha Aerox 155 / NMAX 155 (SCCU1 profile)

---

## License

Apache License 2.0. See [LICENSE](LICENSE).

Yamaha, XSR 155, MT-15, and Y-Connect are trademarks of Yamaha Motor Co., Ltd. This is an independent reverse-engineering project, not affiliated with or endorsed by Yamaha.
