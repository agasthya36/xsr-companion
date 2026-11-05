# Yamaha XSR 155 Companion

A privacy-first Android companion for the **Yamaha XSR 155** and other Yamaha
motorcycles that use the `SCCU1_ICE` / Y-Connect CCU. It connects over Bluetooth
LE, reads live data from the bike, records rides, tracks fuel and service
intervals, and explains diagnostic codes.

Everything stays on the phone. There is no account, no analytics, and no
telemetry upload. Network access is used only for map data when you open a
recorded ride.

## Features

### Dashboard
- Live speed, RPM, coolant temperature, throttle position, and odometer.
- Fuel level, range, and current-ride distance, time, and mileage.
- Connection status, battery level, and the next service item.
- A photo of the bike with the connection controls on top.

### Rides
- Recording starts automatically when the bike moves.
- Speed, RPM, throttle, coolant, and GPS are logged during the ride.
- Ride history with distance, duration, and top speed.
- A speed chart for each ride.
- A route map for rides that have GPS points.
- GPX 1.1 export for one ride, plus CSV export for rides, fuel, and service history.
- A recap notification when a ride ends.
- Last parked location with one-tap navigation.

### Maintenance
- Seven service items: chain, engine oil, oil filter, brake pads and fluid,
  spark plug, air filter, and coolant.
- Odometer-based intervals that can be changed.
- Service history with notes, cost, and the odometer at the time.
- Logging a service requires a note, so a stray tap cannot mark an item as done.
- Counters can be reset for a new bike.

### Diagnostics
- Reads the diagnostic trouble code count and warning lamp from the bike.
- Offline explanations for common Yamaha 155cc codes, including severity and
  suggested action.
- DTC history.
- A rolling app and BLE log that can be exported.

### Settings
- Metric or imperial units.
- Tank capacity and reserve warning.
- GPS mode: detailed (3 s / 8 m) or battery saver (10 s / 25 m).
- Optional background connection.
- CCU passkey and alert thresholds.

## Privacy

No accounts. No analytics. No trackers. No telemetry uploads.

Rides, GPS points, fuel entries, service records, and diagnostic logs are stored
in the app's private storage. The only network traffic is map data while a ride
route is open. Data leaves the phone only when you share or export it.

## Requirements

- Android 17 (API 37) or newer.
- JDK 17.
- Android SDK with API 37 and Build-Tools 37.

Built with AGP 9.5.0, Gradle 9.8.2, Material Components 1.14.0, and Java 17.

## Build

```bash
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Run the unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

### Release build

Create a keystore and a `keystore.properties` file in the project root:

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

If `keystore.properties` is missing, the release build is signed with the debug
key so it can still be tested locally. Keep the keystore and passwords out of
version control.

### Map data

The route map is drawn from the GPS points stored on the phone. MapLibre GL JS
and the OpenFreeMap dark style run inside a WebView, and OpenFreeMap serves
OpenStreetMap data. The map only loads while a route is open. MapLibre and its
licence are bundled in `app/src/main/assets/maplibre/`.

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch the app asks for Bluetooth, location, notification, and
phone-state permissions. Bluetooth and location are required for the CCU link
and ride GPS. Notifications and phone state are optional and are used for alerts
and call relay.

## Protocol

The CCU uses the Nordic UART Service (NUS):

- Service: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- RX (phone writes): `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
- TX (phone notifies): `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`

See [the protocol notes](docs/yamaha_xsr_ble_spec.md) for framing, checksums,
authentication, and fuel calculations.

## Compatibility

- Yamaha XSR 155
- Yamaha MT-15 V2
- Yamaha YZF-R15 V3 / V4 / R15M
- Yamaha Aerox 155 / NMAX 155 (SCCU1 profile)

## License

Apache License 2.0. See [LICENSE](LICENSE).

Yamaha, XSR 155, MT-15, and Y-Connect are trademarks of Yamaha Motor Co., Ltd.
This is an independent reverse-engineering project, not affiliated with or
endorsed by Yamaha.
