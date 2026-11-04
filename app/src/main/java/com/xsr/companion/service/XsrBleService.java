package com.xsr.companion.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.xsr.companion.BuildConfig;
import com.xsr.companion.R;
import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.diagnostics.DtcManager;
import com.xsr.companion.maintenance.FuelManager;
import com.xsr.companion.maintenance.MaintenanceManager;
import com.xsr.companion.protocol.BikeTelemetry;
import com.xsr.companion.protocol.YamahaProtocol;
import com.xsr.companion.settings.SettingsManager;
import com.xsr.companion.settings.Units;
import com.xsr.companion.ui.MainActivity;
import com.xsr.companion.util.AppLogManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressLint("MissingPermission")
public class XsrBleService extends Service {

    private static final String TAG = "XsrBleService";

    public static final String CHANNEL_SERVICE = "xsr_service";
    public static final String CHANNEL_RIDE = "xsr_ride";
    public static final String CHANNEL_ALERTS = "xsr_alerts";
    public static final int NOTIFICATION_ID = 155;
    public static final int RIDE_NOTIFICATION_ID = 156;
    public static final int ALERT_NOTIFICATION_ID = 157;

    public static final String ACTION_START_SCAN = "com.xsr.companion.ACTION_START_SCAN";
    public static final String ACTION_STOP_SCAN = "com.xsr.companion.ACTION_STOP_SCAN";
    public static final String ACTION_DISCONNECT = "com.xsr.companion.ACTION_DISCONNECT";
    public static final String ACTION_STOP_SERVICE = "com.xsr.companion.ACTION_STOP_SERVICE";
    public static final String ACTION_RECONNECT = "com.xsr.companion.ACTION_RECONNECT";
    public static final String ACTION_REFRESH_PHONE = "com.xsr.companion.ACTION_REFRESH_PHONE";

    public static final String BROADCAST_STATUS = "com.xsr.companion.BROADCAST_STATUS";
    public static final String BROADCAST_RIDE_FINISHED = "com.xsr.companion.BROADCAST_RIDE_FINISHED";
    public static final String BROADCAST_DTC = "com.xsr.companion.BROADCAST_DTC";

    public static final String EXTRA_STATUS_TEXT = "extra_status_text";
    public static final String EXTRA_IS_CONNECTED = "extra_is_connected";
    public static final String EXTRA_BATTERY_LEVEL = "extra_battery_level";
    public static final String EXTRA_PACKET_COUNT = "extra_packet_count";
    public static final String EXTRA_DEVICE_NAME = "extra_device_name";
    public static final String EXTRA_DTC_CODE = "extra_dtc_code";
    public static final String EXTRA_RIDE_ID = "extra_ride_id";

    public static final String PREFS_NAME = "xsr_companion_prefs";
    public static final String PREF_LAST_MAC = "last_connected_mac";
    public static final String PREF_LAST_NAME = "last_connected_name";
    public static final String PREF_PARKED_LAT = "parked_latitude";
    public static final String PREF_PARKED_LON = "parked_longitude";
    public static final String PREF_PARKED_TIME = "parked_timestamp";
    public static final String PREF_LATEST_BIKE_ODO = "pref_latest_bike_odo";
    public static final String PREF_PASSKEY = "pref_passkey";
    public static final String PREF_PHONE_UUID = "pref_phone_uuid";

    // ---------------------------------------------------------------------
    // State the UI can read at its own (2 s / 5 s) cadence. No telemetry
    // broadcasts are sent per packet.
    // ---------------------------------------------------------------------
    public static volatile boolean sIsConnected = false;
    public static volatile boolean sSessionActive = false;
    public static volatile String sStatusText = "Disconnected";
    public static volatile String sDeviceName = "No CCU paired";
    public static volatile int sPacketCount = 0;
    public static volatile int sBatteryLevel = 100;
    public static volatile double sInstantMileage = 0.0;
    public static volatile double sTripAvgMileage = 0.0;
    public static volatile double sFuelRemainingL = 10.0;
    public static volatile double sEstimatedRangeKm = 0.0;
    public static volatile double sLatestBikeOdo = 0.0;
    public static volatile long sLastUpdateMs = 0L;
    public static volatile long sRideStartMs = 0L;
    public static volatile double sRideDistanceKm = 0.0;
    public static volatile double sRideFuelL = 0.0;
    public static volatile boolean sRideActive = false;
    public static final BikeTelemetry sLatestTelemetry = new BikeTelemetry();

    public class LocalBinder extends android.os.Binder {
        public XsrBleService getService() {
            return XsrBleService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final AtomicInteger boundClients = new AtomicInteger(0);

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic rxCharacteristic;

    private volatile boolean isScanning = false;
    private volatile boolean isConnected = false;
    private volatile boolean isSuspended = false;
    private volatile boolean userDisconnect = false;
    private volatile boolean sessionActive = false;
    private volatile boolean handshakeComplete = false;

    private final ConcurrentLinkedQueue<byte[]> writeQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean isWriting = new AtomicBoolean(false);
    private int authCandidateIndex = 0;
    private final List<AuthCandidate> authCandidates = new ArrayList<>();
    private int connectRetries = 0;
    private boolean connectInFlight = false;
    private String connectInFlightAddress = null;
    private boolean skipBondedOnNextScan = false;

    private static class AuthCandidate {
        final String passkey;
        final boolean updateBondingFlag;
        AuthCandidate(String passkey, boolean updateBondingFlag) {
            this.passkey = passkey;
            this.updateBondingFlag = updateBondingFlag;
        }
    }

    private int currentBatteryLevel = 100;
    private int packetCount = 0;
    private String currentDeviceName = "No CCU paired";

    private boolean isIncomingCall = false;
    private boolean isMissedCall = false;
    private boolean hasUnreadMessage = false;
    private boolean wasRinging = false;
    private boolean notificationDirty = false;

    private int syncCounter = 0;
    private Handler mainHandler;
    private Runnable processorRunnable;
    private Runnable syncRunnable;
    private Runnable scanTimeoutRunnable;

    private SharedPreferences prefs;
    private RideDatabaseHelper rideDb;
    private MaintenanceManager maintenanceManager;
    private FuelManager fuelManager;
    private SettingsManager settings;
    private final BikeTelemetry pendingTelemetry = new BikeTelemetry();
    private final BikeTelemetry latestTelemetry = new BikeTelemetry();

    private LocationManager locationManager;
    private volatile Location currentLocation = null;
    private volatile long currentLocationMs = 0L;
    private boolean locationUpdatesActive = false;
    private long stationarySinceMs = 0L;

    private volatile long currentRideId = -1;
    private long rideStartMs = 0L;
    private double rideStartOdo = 0.0;
    private volatile double rideDistanceKm = 0.0;
    private volatile double rideFuelL = 0.0;
    private int rideMovingSec = 0;
    private int rideMaxCoolant = 0;
    private long lastPointRecordMs = 0L;

    private long lastPacketMs = 0L;
    private long lastProcessMs = 0L;
    private long lastPersistMs = 0L;
    private long lastClockSyncMs = 0L;
    private long lastNotificationSyncMs = 0L;
    private long lastServiceNotificationMs = 0L;

    private double unsavedFuelL = 0.0;
    private double inMemoryFuelL = 10.0;
    private int lastFuelRevision = -1;
    private double inMemoryOdo = 0.0;
    private boolean odoDirty = false;
    private int lastDtcCode = 0;
    private final Map<Long, Long> dtcCooldowns = new HashMap<>();
    private long missedCallAtMs = 0L;
    private CallStateCallback callStateCallback;
    private boolean telephonyRegistered = false;

    private final Map<String, Long> alertCooldowns = new HashMap<>();
    private long lastAlertServiceCheckMs = 0L;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    currentBatteryLevel = (level * 100) / scale;
                    sBatteryLevel = currentBatteryLevel;
                }
            }
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            currentLocation = location;
            currentLocationMs = System.currentTimeMillis();
            if (location.hasSpeed() && location.getSpeed() * 3.6 > 3) {
                stationarySinceMs = 0L;
            }
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        settings = SettingsManager.get(this);
        rideDb = RideDatabaseHelper.getInstance(this);
        maintenanceManager = new MaintenanceManager(this);
        fuelManager = new FuelManager(this);
        // Baseline maintenance to the last known odometer even before the
        // first successful BLE sync, so the service screen is useful offline.
        maintenanceManager.updateOdometer(prefs.getFloat(PREF_LATEST_BIKE_ODO, 0.0f));
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());

        if (!prefs.contains(PREF_PHONE_UUID)) {
            String uuid = UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString(PREF_PHONE_UUID, uuid).apply();
        }

        currentDeviceName = prefs.getString(PREF_LAST_NAME, "No CCU paired");
        sDeviceName = currentDeviceName;
        inMemoryOdo = prefs.getFloat(PREF_LATEST_BIKE_ODO, 0.0f);
        sLatestBikeOdo = inMemoryOdo;
        inMemoryFuelL = fuelManager.getRemainingLitres();
        sFuelRemainingL = inMemoryFuelL;
        sEstimatedRangeKm = fuelManager.getEstimatedRangeKm(FuelManager.DEFAULT_ESTIMATED_MILEAGE_KML);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            bluetoothAdapter = bm.getAdapter();
            if (bluetoothAdapter != null) bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        }

        ContextCompat.registerReceiver(this, batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
        createNotificationChannels();
        registerTelephonyIfAllowed();

        // Recover any ride left open by a process kill / crash.
        new Thread(() -> {
            try { rideDb.recoverOpenRides(); } catch (Exception ignored) {}
        }, "xsr-ride-recovery").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForegroundCompat();
        } catch (Exception e) {
            Log.e(TAG, "Unable to start foreground service: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START_SCAN.equals(action)) {
            startScan();
        } else if (ACTION_STOP_SCAN.equals(action)) {
            stopScan();
        } else if (ACTION_DISCONNECT.equals(action)) {
            userDisconnect = true;
            stopScan();
            disconnectGatt(true);
            broadcastStatus("Disconnected");
            mainHandler.postDelayed(this::checkStopIfDisconnected, 500);
        } else if (ACTION_RECONNECT.equals(action)) {
            userDisconnect = false;
            startScan();
        } else if (ACTION_STOP_SERVICE.equals(action)) {
            userDisconnect = true;
            stopScan();
            disconnectGatt(true);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } else if (ACTION_REFRESH_PHONE.equals(action)) {
            // READ_PHONE_STATE may have been granted after the service
            // started; re-register the call-state listener exactly once.
            unregisterTelephony();
            registerTelephonyIfAllowed();
        } else if (intent == null) {
            // Real process restart (START_STICKY). Try the known MAC directly;
            // scanning in the background is deliberately avoided to save
            // battery. A no-action start (used only to spin up the foreground
            // service) must NOT connect here: the activity follows it with an
            // explicit ACTION_START_SCAN, and connecting twice in a row
            // cancels the first attempt and makes the second time out.
            String savedMac = prefs.getString(PREF_LAST_MAC, "");
            if (settings.isAutoConnect() && hasBluetoothPermission() && savedMac != null && !savedMac.isEmpty()) {
                connectToMac(savedMac);
            }
        }
        return START_STICKY;
    }

    private void startForegroundCompat() {
        Notification notification = buildServiceNotification(sStatusText);
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (hasLocationPermission()) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        try {
            startForeground(NOTIFICATION_ID, notification, type);
        } catch (Exception e) {
            // Some OEM builds reject a typed FGS start when a runtime
            // permission was just revoked; retry with the base contract.
            Log.w(TAG, "Typed foreground start failed, retrying: " + e.getMessage());
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        boundClients.incrementAndGet();
        rescheduleProcessor();
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        boundClients.decrementAndGet();
        rescheduleProcessor();
        checkStopIfDisconnected();
        return super.onUnbind(intent);
    }

    @Override
    public void onRebind(Intent intent) {
        boundClients.incrementAndGet();
        rescheduleProcessor();
        super.onRebind(intent);
    }

    @Override
    public void onDestroy() {
        AppLogManager.getInstance().log(TAG, "Service destroyed");
        stopScan();
        stopProcessor();
        stopPeriodicSync();
        stopLocationUpdates();
        unregisterTelephony();
        flushState();
        closeGatt();
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ---------------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------------

    private void createNotificationChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel service = new NotificationChannel(
                CHANNEL_SERVICE, "Bike connection", NotificationManager.IMPORTANCE_LOW);
        service.setDescription("Maintains the Bluetooth connection to the motorcycle.");
        nm.createNotificationChannel(service);

        NotificationChannel ride = new NotificationChannel(
                CHANNEL_RIDE, "Ride recaps", NotificationManager.IMPORTANCE_DEFAULT);
        ride.setDescription("Summary shown after a ride ends.");
        nm.createNotificationChannel(ride);

        NotificationChannel alerts = new NotificationChannel(
                CHANNEL_ALERTS, "Riding alerts", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Overspeed, coolant, low fuel and service reminders.");
        alerts.enableVibration(true);
        nm.createNotificationChannel(alerts);
    }

    private Notification buildServiceNotification(String status) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent disconnect = new Intent(this, XsrBleService.class).setAction(ACTION_DISCONNECT);
        PendingIntent disconnectIntent = PendingIntent.getService(this, 1, disconnect,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_SERVICE)
                .setContentTitle("XSR Companion")
                .setContentText(status)
                .setSmallIcon(R.drawable.ic_stat_moto)
                .setContentIntent(openIntent)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectIntent).build())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateServiceNotification(String status) {
        safeNotify(NOTIFICATION_ID, buildServiceNotification(status));
    }

    private void safeNotify(int id, Notification notification) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            nm.notify(id, notification);
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS can be revoked while the service runs.
        }
    }

    private void postRideRecap(RideDatabaseHelper.RideSummary summary) {
        if (summary == null) return;
        String text = String.format(Locale.getDefault(),
                "%s • %s • top %s",
                Units.distance(settings, summary.distanceKm),
                summary.getFormattedMovingTime(),
                Units.speed(settings, summary.topSpeedKmh));

        Intent open = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_RIDES)
                .putExtra(EXTRA_RIDE_ID, summary.id);
        PendingIntent openIntent = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_RIDE)
                .setContentTitle("Ride saved")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text
                        + "\n" + summary.getFormattedDate()))
                .setSmallIcon(R.drawable.ic_stat_moto)
                .setContentIntent(openIntent)
                .setAutoCancel(true);

        PendingIntent navigate = buildNavigatePendingIntent();
        if (navigate != null) {
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_mylocation, "Navigate to bike", navigate).build());
        }

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            try {
                nm.notify(RIDE_NOTIFICATION_ID, builder.build());
            } catch (SecurityException ignored) {
            }
        }
    }

    private PendingIntent buildNavigatePendingIntent() {
        float lat = prefs.getFloat(PREF_PARKED_LAT, 0f);
        float lon = prefs.getFloat(PREF_PARKED_LON, 0f);
        if (lat == 0f && lon == 0f) return null;
        Intent nav = new Intent(Intent.ACTION_VIEW,
                Uri.parse("google.navigation:q=" + lat + "," + lon + "&mode=w"));
        nav.setPackage("com.google.android.apps.maps");
        return PendingIntent.getActivity(this, 11, nav,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void postAlert(String key, String title, String text) {
        long now = System.currentTimeMillis();
        Long last = alertCooldowns.get(key);
        long cooldown = 10 * 60 * 1000L;
        if (last != null && now - last < cooldown) return;
        alertCooldowns.put(key, now);

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 20, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, CHANNEL_ALERTS)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_moto)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .build();

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            try {
                nm.notify(key.hashCode() & 0xFFFF, n);
            } catch (SecurityException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------------
    // Status broadcasting (low frequency only)
    // ---------------------------------------------------------------------

    private String lastBroadcastStatus = "";

    private void broadcastStatus(String statusText) {
        sIsConnected = isConnected;
        sSessionActive = sessionActive;
        sStatusText = statusText;
        sDeviceName = currentDeviceName;
        sPacketCount = packetCount;
        sBatteryLevel = currentBatteryLevel;

        AppLogManager.getInstance().log(TAG, "Status: " + statusText);
        if (!statusText.equals(lastBroadcastStatus)) {
            lastBroadcastStatus = statusText;
            Intent intent = new Intent(BROADCAST_STATUS);
            intent.setPackage(getPackageName());
            intent.putExtra(EXTRA_STATUS_TEXT, statusText);
            intent.putExtra(EXTRA_IS_CONNECTED, isConnected);
            intent.putExtra(EXTRA_BATTERY_LEVEL, currentBatteryLevel);
            intent.putExtra(EXTRA_PACKET_COUNT, packetCount);
            intent.putExtra(EXTRA_DEVICE_NAME, currentDeviceName);
            sendBroadcast(intent);
            updateServiceNotification(statusText);
        }
    }

    private void broadcastDtc(int code) {
        Intent intent = new Intent(BROADCAST_DTC);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_DTC_CODE, code);
        sendBroadcast(intent);
    }

    // ---------------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------------

    public void startScan() {
        if (!hasBluetoothPermission()) {
            broadcastStatus("Bluetooth permission required");
            return;
        }
        if (isConnected) {
            broadcastStatus("Already connected");
            return;
        }
        if (isScanning) return;

        // Prefer an already-bonded CCU: zero scanning cost. Skip it after a
        // direct-connect timeout, so a rotated CCU address can be rediscovered
        // by an actual scan instead of retrying a stale MAC forever.
        if (!skipBondedOnNextScan && connectToBondedIfPresent()) return;
        skipBondedOnNextScan = false;

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            broadcastStatus("Bluetooth is off");
            return;
        }
        if (bleScanner == null) bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            broadcastStatus("BLE scanner unavailable");
            return;
        }

        userDisconnect = false;
        isScanning = true;
        broadcastStatus("Scanning for your motorcycle…");

        try {
            // The CCU does not reliably advertise the NUS service UUID in its
            // scan response, so scan unfiltered for the short 12 s window and
            // match on name / saved MAC in the callback.
            ScanSettings scanSettings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .build();
            bleScanner.startScan(null, scanSettings, scanCallback);
        } catch (Exception e) {
            try {
                bleScanner.startScan(scanCallback);
            } catch (Exception e2) {
                isScanning = false;
                broadcastStatus("Scan failed: " + e2.getMessage());
                return;
            }
        }

        scanTimeoutRunnable = () -> {
            if (isScanning) {
                stopScan();
                broadcastStatus("Motorcycle not found. Turn the ignition on and retry.");
                checkStopIfDisconnected();
            }
        };
        mainHandler.postDelayed(scanTimeoutRunnable, 12_000);
    }

    public void stopScan() {
        if (scanTimeoutRunnable != null) {
            mainHandler.removeCallbacks(scanTimeoutRunnable);
            scanTimeoutRunnable = null;
        }
        if (bleScanner != null && isScanning) {
            try { bleScanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        isScanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;
            BluetoothDevice device = result.getDevice();
            String name = null;
            if (result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
            if (name == null) {
                try { name = device.getName(); } catch (Exception ignored) {}
            }
            String addr = device.getAddress();
            String savedMac = prefs.getString(PREF_LAST_MAC, "");

            boolean nameMatch = name != null
                    && (name.startsWith(YamahaProtocol.PREFIX_YSCCU)
                    || name.startsWith(YamahaProtocol.PREFIX_YCCU));
            boolean macMatch = savedMac != null && !savedMac.isEmpty() && addr.equalsIgnoreCase(savedMac);
            boolean advertisesNus = result.getScanRecord() != null
                    && result.getScanRecord().getServiceUuids() != null
                    && result.getScanRecord().getServiceUuids()
                    .contains(new ParcelUuid(YamahaProtocol.NUS_SERVICE_UUID));

            if (!(nameMatch || macMatch || advertisesNus)) return;

            stopScan();
            String displayName = name != null ? name : "CCU " + addr;
            currentDeviceName = displayName;
            broadcastStatus("Found " + displayName + ", connecting…");
            connectToDevice(device);
        }

        @Override
        public void onScanFailed(int errorCode) {
            isScanning = false;
            broadcastStatus("BLE scan failed (code " + errorCode + ")");
        }
    };

    private boolean connectToBondedIfPresent() {
        if (bluetoothAdapter == null) return false;
        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded == null) return false;
            String savedMac = prefs.getString(PREF_LAST_MAC, "");
            for (BluetoothDevice device : bonded) {
                String name = device.getName();
                String addr = device.getAddress();
                boolean isYamaha = (name != null && (name.startsWith(YamahaProtocol.PREFIX_YSCCU)
                        || name.startsWith(YamahaProtocol.PREFIX_YCCU)))
                        || (savedMac != null && !savedMac.isEmpty() && addr != null
                        && addr.equalsIgnoreCase(savedMac));
                if (isYamaha) {
                    currentDeviceName = name != null ? name : "CCU " + addr;
                    broadcastStatus("Connecting to paired CCU…");
                    connectToDevice(device);
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Bonded device scan failed: " + e.getMessage());
        }
        return false;
    }

    private void connectToMac(String mac) {
        if (bluetoothAdapter == null || mac == null || mac.isEmpty()) return;
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(mac);
            if (device != null) connectToDevice(device);
        } catch (Exception e) {
            broadcastStatus("Saved bike not available. Tap Connect.");
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        if (device == null) return;
        String address = device.getAddress();

        // Never start a second connection while one is still opening. The
        // previous code closed the first GATT and immediately reconnected,
        // which the controller often answers with a connection timeout.
        if (connectInFlight && address != null
                && address.equalsIgnoreCase(connectInFlightAddress)) {
            AppLogManager.getInstance().log(TAG,
                    "Connect already in progress; ignoring duplicate request");
            return;
        }

        closeGatt();
        connectInFlight = true;
        connectInFlightAddress = address;

        String name;
        try { name = device.getName(); } catch (Exception e) { name = null; }
        if (name == null) name = currentDeviceName;
        currentDeviceName = name;
        broadcastStatus("Connecting to " + name + "…");

        prefs.edit()
                .putString(PREF_LAST_MAC, device.getAddress())
                .putString(PREF_LAST_NAME, name)
                .apply();

        try {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            bluetoothGatt = null;
        }
        if (bluetoothGatt == null) {
            connectInFlight = false;
            connectInFlightAddress = null;
            broadcastStatus("Could not start the Bluetooth connection");
        }
    }

    // ---------------------------------------------------------------------
    // GATT state machine
    // ---------------------------------------------------------------------

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            // Ignore callbacks from a GATT instance we already abandoned.
            // Otherwise a late DISCONNECTED from the old attempt calls
            // closeGatt() and tears down the new, healthy connection.
            if (gatt != bluetoothGatt) {
                try { gatt.close(); } catch (Exception ignored) {}
                return;
            }
            connectInFlight = false;
            connectInFlightAddress = null;

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                sIsConnected = true;
                handshakeComplete = false;
                sessionActive = false;
                authCandidates.clear();
                authCandidateIndex = 0;
                connectRetries = 0;
                packetCount = 0;
                sPacketCount = 0;
                broadcastStatus("Connected. Reading services…");
                try { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Exception ignored) {}
                mainHandler.postDelayed(() -> {
                    if (bluetoothGatt != null) {
                        try {
                            if (!bluetoothGatt.discoverServices()) {
                                broadcastStatus("Service discovery failed");
                            }
                        } catch (Exception e) {
                            broadcastStatus("Service discovery error");
                        }
                    }
                }, 300);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                sIsConnected = false;
                handshakeComplete = false;
                sessionActive = false;
                sSessionActive = false;
                writeQueue.clear();
                isWriting.set(false);
                rxCharacteristic = null;
                stopPeriodicSync();
                finishRide();
                saveParkedLocation();
                closeGatt();

                // 147 = GATT_CONNECTION_TIMEOUT: the CCU did not answer the
                // direct connect. Rescan instead of blindly retrying the saved
                // MAC, because the CCU can advertise from a rotated address.
                if (status == 147 && !userDisconnect && connectRetries < 1) {
                    connectRetries++;
                    broadcastStatus("Connection timed out. Scanning again…");
                    skipBondedOnNextScan = true;
                    mainHandler.postDelayed(() -> {
                        if (!isConnected && !isScanning) startScan();
                    }, 1500);
                    return;
                }

                if (status == 133 && !userDisconnect && connectRetries < 2) {
                    connectRetries++;
                    broadcastStatus("Connection dropped. Retrying…");
                    mainHandler.postDelayed(() -> {
                        String mac = prefs.getString(PREF_LAST_MAC, "");
                        if (!mac.isEmpty()) connectToMac(mac);
                    }, 1200L * connectRetries);
                    return;
                }

                broadcastStatus(userDisconnect ? "Disconnected" : "Motorcycle off (CCU disconnected)");
                mainHandler.postDelayed(XsrBleService.this::checkStopIfDisconnected, 1500);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                broadcastStatus("Service discovery failed (" + status + ")");
                return;
            }
            BluetoothGattService service = gatt.getService(YamahaProtocol.NUS_SERVICE_UUID);
            if (service == null) {
                broadcastStatus("Yamaha NUS service not found");
                return;
            }

            rxCharacteristic = service.getCharacteristic(YamahaProtocol.NUS_RX_CHAR_UUID);
            BluetoothGattCharacteristic tx = service.getCharacteristic(YamahaProtocol.NUS_TX_CHAR_UUID);
            if (rxCharacteristic != null) {
                rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            }
            if (tx == null) {
                broadcastStatus("CCU notify characteristic missing");
                return;
            }

            boolean notificationSet = gatt.setCharacteristicNotification(tx, true);
            BluetoothGattDescriptor descriptor = tx.getDescriptor(YamahaProtocol.CLIENT_CONFIG_DESCRIPTOR);
            if (descriptor == null || !notificationSet) {
                broadcastStatus("Could not enable CCU notifications");
                return;
            }
            broadcastStatus("Enabling live data…");
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastStatus("Negotiating connection…");
                boolean requested = false;
                try { requested = gatt.requestMtu(247); } catch (Exception ignored) {}
                if (!requested) {
                    mainHandler.post(() -> beginAuthentication(gatt));
                } else {
                    // If the CCU never answers the MTU request, authenticate
                    // anyway rather than leaving the session unauthenticated.
                    mainHandler.postDelayed(() -> {
                        if (bluetoothGatt == gatt && isConnected && !handshakeComplete
                                && !sessionActive && authCandidateIndex == 0) {
                            AppLogManager.getInstance().log(TAG,
                                    "MTU callback missing; authenticating anyway");
                            beginAuthentication(gatt);
                        }
                    }, 4000);
                }
            } else {
                broadcastStatus("Notification setup failed (" + status + ")");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            AppLogManager.getInstance().log(TAG,
                    "MTU " + mtu + " (status " + status + "); starting authentication");
            beginAuthentication(gatt);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (isWriting.compareAndSet(true, false)) processNextWrite();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleIncoming(value);
        }
    };

    private String cleanCcuIdForDevice(BluetoothDevice dev) {
        String name = null;
        try { name = dev != null ? dev.getName() : null; } catch (Exception ignored) {}
        return YamahaProtocol.cleanCcuId(name != null ? name : currentDeviceName);
    }

    private void beginAuthentication(BluetoothGatt gatt) {
        if (gatt == null || gatt != bluetoothGatt || !isConnected) return;
        if (handshakeComplete || sessionActive) return;

        BluetoothDevice dev = gatt.getDevice();
        int bondState = dev != null ? dev.getBondState() : BluetoothDevice.BOND_NONE;
        boolean bonded = bondState == BluetoothDevice.BOND_BONDED;

        if (authCandidateIndex > 0) {
            AppLogManager.getInstance().log(TAG,
                    "Authentication already in progress; ignoring repeated MTU update");
            return;
        }

        prepareAuthCandidates(cleanCcuIdForDevice(dev), bonded);
        AppLogManager.getInstance().log(TAG,
                "Authenticating with CCU (bonded=" + bonded + ", attempts=" + authCandidates.size() + ")");
        broadcastStatus(bonded ? "Authenticating with CCU…" : "Pairing with CCU…");
        trySendNextAuth();

        mainHandler.postDelayed(() -> {
            if (isConnected && !handshakeComplete && !sessionActive) {
                broadcastStatus("CCU PIN required. Tap the bike name to enter it.");
            }
        }, 5000);
    }

    /**
     * Re-sends 0x7F00 after the user edits the passkey, without dropping the
     * BLE link. No-op once a session is already authenticated.
     */
    public void retryAuthentication() {
        if (bluetoothGatt == null || !isConnected || sessionActive) return;
        handshakeComplete = false;
        authCandidates.clear();
        authCandidateIndex = 0;
        AppLogManager.getInstance().log(TAG,
                "Retrying authentication with the updated passkey");
        beginAuthentication(bluetoothGatt);
    }

    private void prepareAuthCandidates(String ccuId, boolean bonded) {
        authCandidates.clear();
        authCandidateIndex = 0;

        String userPass = prefs.getString(PREF_PASSKEY, "").trim();
        String last6Ccu = ccuId.length() >= 6
                ? ccuId.substring(ccuId.length() - 6) : "826927";

        String candidate;
        if (!userPass.isEmpty()) {
            if (userPass.length() == 24) {
                String decoded = YamahaProtocol.decodeCcuQrPasskey(userPass, ccuId);
                if (decoded != null) {
                    candidate = decoded;
                    // Replace the raw QR payload with the actual passkey so
                    // later reconnects do not need to re-decode it.
                    prefs.edit().putString(PREF_PASSKEY, decoded).apply();
                } else {
                    AppLogManager.getInstance().log(TAG,
                            "CCU QR payload did not match this CCU ID; falling back");
                    candidate = last6Ccu;
                }
            } else {
                candidate = userPass.length() > 6
                        ? userPass.substring(userPass.length() - 6) : userPass;
            }
        } else {
            candidate = last6Ccu;
        }

        // 0x7F00 is required on every session. When the phone is already
        // bonded, the CCU expects updateBondingFlag=0; on first pairing it
        // expects 1. Try the matching flag first, then the alternate once.
        // Never brute-force a list of PINs: the CCU can lock out.
        authCandidates.add(new AuthCandidate(candidate, !bonded));
        authCandidates.add(new AuthCandidate(candidate, bonded));
    }

    private void trySendNextAuth() {
        if (bluetoothGatt == null || !isConnected || handshakeComplete) return;
        if (authCandidateIndex >= authCandidates.size()) {
            broadcastStatus("CCU PIN required. Tap the bike name to enter it.");
            return;
        }
        AuthCandidate candidate = authCandidates.get(authCandidateIndex++);
        String ccuId = cleanCcuIdForDevice(bluetoothGatt.getDevice());
        String phoneUuid = prefs.getString(PREF_PHONE_UUID, "");
        byte[] packet = YamahaProtocol.buildAuthPacket(ccuId, candidate.passkey,
                phoneUuid, candidate.updateBondingFlag, syncCounter++);
        enqueueWrite(packet);
    }

    // ---------------------------------------------------------------------
    // Incoming telemetry + processing loop
    // ---------------------------------------------------------------------

    private void handleIncoming(byte[] data) {
        if (data == null || data.length == 0) return;

        int startProcAck = YamahaProtocol.parseStartProcessingAck(data);
        if (startProcAck != -1) {
            if (startProcAck == 1) {
                handshakeComplete = true;
                if (authCandidateIndex > 0) {
                    AuthCandidate accepted = authCandidates.get(authCandidateIndex - 1);
                    prefs.edit().putString(PREF_PASSKEY, accepted.passkey).apply();
                    AppLogManager.getInstance().log(TAG, "CCU accepted the pairing PIN");
                }
                startSession();
            } else if (authCandidateIndex < authCandidates.size()) {
                AppLogManager.getInstance().log(TAG,
                        "CCU rejected auth attempt; trying the alternate bonding flag");
                mainHandler.postDelayed(this::trySendNextAuth, 250);
            } else {
                AppLogManager.getInstance().log(TAG,
                        "CCU rejected all auth candidates; a PIN/VIN is required");
                broadcastStatus("CCU PIN required. Tap the bike name to enter it.");
            }
            return;
        }

        long now = System.currentTimeMillis();
        // The CCU streams at 10 Hz. Parsing at 5 Hz is plenty for every metric
        // and halves the CPU cost; integration uses real elapsed time.
        if (now - lastPacketMs < 180) return;
        long dtMs = lastPacketMs == 0 ? 0 : now - lastPacketMs;
        lastPacketMs = now;

        boolean updated;
        synchronized (pendingTelemetry) {
            updated = YamahaProtocol.parseIncomingPacket(data, pendingTelemetry);
        }
        if (!updated) return;

        packetCount++;
        sPacketCount = packetCount;

        if (!handshakeComplete && (data[0] == YamahaProtocol.START_BYTE_55
                || data[0] == YamahaProtocol.START_BYTE_56)) {
            startSession();
        }

        double dtSec = Math.min(5.0, Math.max(0.0, dtMs / 1000.0));
        if (dtSec > 0.05) {
            int rpm;
            double throttle;
            int airTemp;
            double pressure;
            int speed;
            int coolant;
            synchronized (pendingTelemetry) {
                rpm = pendingTelemetry.engineRpm;
                throttle = pendingTelemetry.throttlePct;
                airTemp = pendingTelemetry.airTemp;
                pressure = pendingTelemetry.atmosphericPressure;
                speed = pendingTelemetry.speedKmh;
                coolant = pendingTelemetry.coolantTemp;
            }

            double fuelRate = YamahaProtocol.calculateFuelRateLh(rpm, throttle, airTemp, pressure);
            double mileage = YamahaProtocol.calculateInstantMileageKmL(speed, fuelRate, throttle);

            synchronized (pendingTelemetry) {
                pendingTelemetry.fuelConsumptionRateLh = fuelRate;
                pendingTelemetry.instantMileageKmL = mileage;
                if (rpm > 0 && fuelRate > 0) {
                    double litres = fuelRate * dtSec / 3600.0;
                    unsavedFuelL += litres;
                    if (currentRideId > 0) rideFuelL += litres;
                }
                if (currentRideId > 0 && speed > 0) {
                    rideDistanceKm += speed * dtSec / 3600.0;
                }
                if (coolant > rideMaxCoolant) rideMaxCoolant = coolant;
            }
        }

        handleDtcIfNeeded();
    }

    private void handleDtcIfNeeded() {
        int code;
        int count;
        boolean warning;
        synchronized (pendingTelemetry) {
            code = pendingTelemetry.dtcCode;
            count = pendingTelemetry.dtcCount;
            warning = pendingTelemetry.warningLamp;
        }
        if (code > 0 && code != lastDtcCode) {
            lastDtcCode = code;
            DtcManager.DtcInfo info = DtcManager.lookup(code);
            rideDb.insertDtcLog(System.currentTimeMillis(), code, info.title,
                    info.severity.name(), inMemoryOdo);
            broadcastDtc(code);
            postAlert("dtc_" + code, "Check engine: DTC " + code, info.title);
        } else if (code == 0 && lastDtcCode != 0) {
            lastDtcCode = 0;
            broadcastDtc(0);
        } else if (warning && lastDtcCode == 0 && count > 0) {
            // Warning lamp on but no decoded code yet.
            long now = System.currentTimeMillis();
            Long last = dtcCooldowns.get(0L);
            if (last == null || now - last > 10 * 60 * 1000L) {
                dtcCooldowns.put(0L, now);
                postAlert("dtc_warning", "Check engine light", "The ECU reports an active fault.");
            }
        }
    }

    private void rescheduleProcessor() {
        if (mainHandler == null) return;
        if (processorRunnable != null) {
            mainHandler.removeCallbacks(processorRunnable);
            processorRunnable = null;
        }
        // Fully idle (bound but not linked, no ride, no GPS): do not wake the
        // CPU every couple of seconds. startSession()/onBind() reschedule when
        // there is real work again.
        if (!isConnected && !sessionActive && currentRideId <= 0 && !locationUpdatesActive) {
            return;
        }
        processorRunnable = this::processTick;
        long interval = boundClients.get() > 0
                ? settings.foregroundIntervalMs() : settings.backgroundIntervalMs();
        mainHandler.postDelayed(processorRunnable, interval);
    }

    private void stopProcessor() {
        if (processorRunnable != null) {
            mainHandler.removeCallbacks(processorRunnable);
            processorRunnable = null;
        }
    }

    private void processTick() {
        long now = System.currentTimeMillis();
        long intervalMs = lastProcessMs == 0L ? 5_000L
                : Math.min(15_000L, Math.max(500L, now - lastProcessMs));
        lastProcessMs = now;

        int speed;
        int rpm;
        int coolant;
        double throttle;
        double odo;
        double instantMileage;
        double fuelRate;
        boolean warning;
        int dtcCode;
        int dtcCount;
        synchronized (pendingTelemetry) {
            latestTelemetry.copyFrom(pendingTelemetry);
            speed = latestTelemetry.speedKmh;
            rpm = latestTelemetry.engineRpm;
            coolant = latestTelemetry.coolantTemp;
            throttle = latestTelemetry.throttlePct;
            odo = latestTelemetry.odometerKm;
            instantMileage = latestTelemetry.instantMileageKmL;
            fuelRate = latestTelemetry.fuelConsumptionRateLh;
            warning = latestTelemetry.warningLamp;
            dtcCode = latestTelemetry.dtcCode;
            dtcCount = latestTelemetry.dtcCount;
        }

        // A refuel or tank-size change happens in the UI while this service is
        // running. Apply any pending consumption first, then rebase the
        // in-memory estimate on the new persistent value so the next tick does
        // not overwrite the fill-up with a stale number.
        int fuelRevision = fuelManager.getRevision();
        if (fuelRevision != lastFuelRevision) {
            if (lastFuelRevision != -1 && unsavedFuelL > 0.0) {
                fuelManager.consumeFuel(unsavedFuelL);
                unsavedFuelL = 0.0;
            }
            inMemoryFuelL = fuelManager.getRemainingLitres();
            lastFuelRevision = fuelRevision;
        }

        double displayFuel = Math.max(0.0, inMemoryFuelL - unsavedFuelL);
        double tripAvg = 0.0;
        if (rideDistanceKm > 0.05 && rideFuelL > 0.002) {
            tripAvg = rideDistanceKm / rideFuelL;
        } else if (instantMileage > 0.5) {
            tripAvg = instantMileage;
        }

        double bestMileage = fuelManager.getBestMileageKmL(tripAvg);
        double range = fuelManager.getEstimatedRangeKm(bestMileage);

        latestTelemetry.tripFuelConsumedL = rideFuelL;
        latestTelemetry.tripAvgMileageKmL = tripAvg;
        latestTelemetry.remainingFuelL = displayFuel;
        latestTelemetry.speedKmh = speed;
        latestTelemetry.engineRpm = rpm;
        latestTelemetry.coolantTemp = coolant;
        latestTelemetry.throttlePct = throttle;
        latestTelemetry.odometerKm = odo;
        latestTelemetry.warningLamp = warning;
        latestTelemetry.dtcCode = dtcCode;
        latestTelemetry.dtcCount = dtcCount;

        sInstantMileage = instantMileage;
        sTripAvgMileage = tripAvg;
        sFuelRemainingL = displayFuel;
        sEstimatedRangeKm = range;
        sLastUpdateMs = lastPacketMs;
        sRideActive = currentRideId > 0;
        sRideDistanceKm = rideDistanceKm;
        sRideFuelL = rideFuelL;
        sRideStartMs = rideStartMs;

        if (odo > 0 && Math.abs(odo - inMemoryOdo) >= 0.1) {
            inMemoryOdo = odo;
            odoDirty = true;
            sLatestBikeOdo = odo;
        }

        synchronized (sLatestTelemetry) {
            sLatestTelemetry.copyFrom(latestTelemetry);
        }

        // A ride only exists once the bike actually moves; sitting with the
        // ignition on should not create empty records or drain GPS.
        if (sessionActive && currentRideId <= 0 && speed > 2) {
            startRideTracking();
        }

        if (currentRideId > 0) {
            if (speed > 1) {
                rideMovingSec += (int) Math.max(1, intervalMs / 1000);
                stationarySinceMs = 0L;
            } else if (stationarySinceMs == 0L) {
                stationarySinceMs = now;
            }
            recordPointIfDue(now);
        }

        if (isMissedCall && missedCallAtMs > 0 && now - missedCallAtMs > 60_000L) {
            isMissedCall = false;
            missedCallAtMs = 0L;
            notificationDirty = true;
        }

        if (now - lastPersistMs >= 30_000L) flushState();
        if (isConnected && now - lastClockSyncMs >= 60_000L) {
            sendClockAndBatterySync();
            lastClockSyncMs = now;
        }
        if (isConnected && (isIncomingCall || notificationDirty)
                && now - lastNotificationSyncMs >= 2_000L) {
            sendNotificationSync();
            lastNotificationSyncMs = now;
            notificationDirty = false;
        }
        updateServiceNotificationIfUseful(now);
        // Only evaluate alerts and GPS motion against a live packet. After the
        // link drops the last snapshot can be minutes old; acting on it would
        // fire phantom overspeed/coolant alerts and keep GPS awake.
        boolean fresh = lastPacketMs > 0 && now - lastPacketMs < 10_000L;
        if (isConnected && fresh) {
            checkAlerts(now, speed, coolant, displayFuel);
            manageGpsForMotion(now, speed);
        } else if (currentRideId > 0) {
            manageGpsForMotion(now, 0);
        }
        rescheduleProcessor();
    }

    private void updateServiceNotificationIfUseful(long now) {
        if (!isConnected) return;
        /* Keep the persistent notification informative without churn. */
        if (now - lastServiceNotificationMs < 30_000L) return;
        lastServiceNotificationMs = now;
        String text = String.format(Locale.getDefault(), "%s • %s",
                Units.speed(settings, latestTelemetry.speedKmh),
                Units.mileage(settings, latestTelemetry.instantMileageKmL));
        updateServiceNotification(text);
    }

    private void checkAlerts(long now, int speed, int coolant, double fuelL) {
        if (settings.alertOverspeed() && speed > settings.overspeedKmh()) {
            postAlert("overspeed", "Slow down", "You are above "
                    + Units.speed(settings, settings.overspeedKmh()) + ".");
        }
        if (settings.alertCoolant() && coolant >= settings.coolantLimitC()) {
            postAlert("coolant", "Coolant temperature high",
                    Units.temp(settings, coolant) + " — ease off and let the engine cool.");
        }
        if (settings.alertLowFuel() && fuelL > 0 && fuelL <= settings.getReserveL()) {
            postAlert("low_fuel", "Low fuel",
                    Units.fuel(settings, fuelL) + " left (~" + Units.shortDistance(settings,
                            fuelManager.getEstimatedRangeKm(fuelManager.getBestMileageKmL(sTripAvgMileage))) + ").");
        }
        if (settings.alertColdEngine() && coolant > 0 && coolant < 55 && latestTelemetry.engineRpm > 4500) {
            postAlert("cold_engine", "Engine is still cold",
                    "Keep the revs lower until " + Units.temp(settings, 55) + ".");
        }
        if (settings.alertService() && currentRideId > 0 && now - lastAlertServiceCheckMs > 6 * 60 * 60 * 1000L) {
            lastAlertServiceCheckMs = now;
            int due = maintenanceManager.getDueItemCount();
            if (due > 0) {
                postAlert("service", "Maintenance due",
                        due + (due == 1 ? " item needs attention." : " items need attention."));
            }
        }
    }

    private void manageGpsForMotion(long now, int speed) {
        if (currentRideId <= 0) {
            stopLocationUpdates();
            return;
        }
        if (speed <= 1) {
            if (stationarySinceMs == 0L) stationarySinceMs = now;
            if (locationUpdatesActive && now - stationarySinceMs > 30_000L) {
                stopLocationUpdates();
            }
        } else if (!locationUpdatesActive) {
            startLocationUpdates();
        }
    }

    private void recordPointIfDue(long now) {
        if (currentRideId <= 0) return;
        // No GPS or database writes while stationary — moving again records
        // immediately because lastPointRecordMs is only advanced on a write.
        if (latestTelemetry.speedKmh <= 1) return;
        if (now - lastPointRecordMs < 2000L) return;
        lastPointRecordMs = now;

        boolean hasGps = currentLocation != null
                && now - currentLocationMs < 15_000L
                && currentLocation.getAccuracy() <= 100f;
        double lat = hasGps ? currentLocation.getLatitude() : 0.0;
        double lon = hasGps ? currentLocation.getLongitude() : 0.0;
        float accuracy = hasGps ? currentLocation.getAccuracy() : 0f;

        rideDb.insertPoint(currentRideId, now, lat, lon, hasGps, accuracy,
                latestTelemetry.speedKmh, latestTelemetry.engineRpm,
                latestTelemetry.throttlePct, latestTelemetry.coolantTemp);
    }

    // ---------------------------------------------------------------------
    // Session / ride lifecycle
    // ---------------------------------------------------------------------

    private void startSession() {
        if (sessionActive) return;
        sessionActive = true;
        sSessionActive = true;
        handshakeComplete = true;
        broadcastStatus("Connected & syncing");
        sRideActive = false;
        if (bluetoothGatt != null) {
            try { bluetoothGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED); } catch (Exception ignored) {}
        }
        startPeriodicSync();
        lastProcessMs = 0L;
        rescheduleProcessor();
        sendClockAndBatterySync();
        lastClockSyncMs = System.currentTimeMillis();
    }

    private void startRideTracking() {
        if (currentRideId > 0) return;
        rideStartOdo = latestTelemetry.odometerKm > 0 ? latestTelemetry.odometerKm : inMemoryOdo;
        rideDistanceKm = 0.0;
        rideFuelL = 0.0;
        rideMovingSec = 0;
        rideMaxCoolant = 0;
        rideStartMs = System.currentTimeMillis();
        lastPointRecordMs = 0L;
        stationarySinceMs = 0L;
        currentRideId = rideDb.startNewRide(rideStartMs, rideStartOdo);
        sRideActive = true;
        sRideStartMs = rideStartMs;
        sRideDistanceKm = 0.0;
        sRideFuelL = 0.0;
        AppLogManager.getInstance().log(TAG, "Ride started (odo=%.1f km)", rideStartOdo);
        startLocationUpdates();
    }

    private void finishRide() {
        if (currentRideId <= 0) {
            stopLocationUpdates();
            return;
        }
        long id = currentRideId;
        currentRideId = -1;
        flushState();
        stopLocationUpdates();

        boolean saved = rideDb.finalizeRide(id, System.currentTimeMillis(), rideDistanceKm,
                rideMovingSec, rideMaxCoolant, inMemoryOdo, rideFuelL);
        AppLogManager.getInstance().log(TAG, "Ride finished (saved=%b, %.2f km)", saved, rideDistanceKm);

        sRideActive = false;
        sRideDistanceKm = 0.0;
        sRideFuelL = 0.0;
        sRideStartMs = 0L;
        rideDistanceKm = 0.0;
        rideFuelL = 0.0;
        rideMovingSec = 0;
        rideMaxCoolant = 0;
        rideStartMs = 0L;

        if (saved) {
            RideDatabaseHelper.RideSummary summary = rideDb.getRide(id);
            Intent intent = new Intent(BROADCAST_RIDE_FINISHED);
            intent.setPackage(getPackageName());
            intent.putExtra(EXTRA_RIDE_ID, id);
            sendBroadcast(intent);
            postRideRecap(summary);
        }
    }

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    private void flushState() {
        if (unsavedFuelL > 0.0) {
            fuelManager.consumeFuel(unsavedFuelL);
            inMemoryFuelL = Math.max(0.0, inMemoryFuelL - unsavedFuelL);
            unsavedFuelL = 0.0;
        }
        if (odoDirty && inMemoryOdo > 0) {
            prefs.edit().putFloat(PREF_LATEST_BIKE_ODO, (float) inMemoryOdo).apply();
            maintenanceManager.updateOdometer(inMemoryOdo);
            odoDirty = false;
        }
        lastPersistMs = System.currentTimeMillis();
    }

    // ---------------------------------------------------------------------
    // Periodic CCU sync
    // ---------------------------------------------------------------------

    private void startPeriodicSync() {
        stopPeriodicSync();
        syncRunnable = new Runnable() {
            @Override
            public void run() {
                if (isConnected && bluetoothGatt != null && rxCharacteristic != null) {
                    long now = System.currentTimeMillis();
                    // The CCU drops the link roughly 15 s after authentication if
                    // 0x058A stops arriving. The official app sends 0x058A and
                    // 0x058B once per second, so mirror that cadence here.
                    sendClockAndBatterySync();
                    lastClockSyncMs = now;
                    sendNotificationSync();
                    lastNotificationSyncMs = now;
                    mainHandler.postDelayed(this, 1_000L);
                }
            }
        };
        mainHandler.post(syncRunnable);
    }

    private void stopPeriodicSync() {
        if (syncRunnable != null) {
            mainHandler.removeCallbacks(syncRunnable);
            syncRunnable = null;
        }
    }

    private void sendClockAndBatterySync() {
        if (!isConnected || rxCharacteristic == null) return;
        Calendar cal = Calendar.getInstance();
        byte[] packet = YamahaProtocol.buildBatteryAndClockPacket(
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND),
                currentBatteryLevel, syncCounter++);
        enqueueWrite(packet);
    }

    private void sendNotificationSync() {
        if (!isConnected || rxCharacteristic == null) return;
        byte[] packet = YamahaProtocol.buildNotificationPacket(
                isIncomingCall, isMissedCall, hasUnreadMessage, false, syncCounter++);
        enqueueWrite(packet);
    }

    // ---------------------------------------------------------------------
    // GPS
    // ---------------------------------------------------------------------

    private void startLocationUpdates() {
        if (locationUpdatesActive || locationManager == null) return;
        if (!hasLocationPermission()) return;
        try {
            boolean gps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean network = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (gps) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        2000L, 5f, locationListener, Looper.getMainLooper());
                locationUpdatesActive = true;
            } else if (network) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        3000L, 10f, locationListener, Looper.getMainLooper());
                locationUpdatesActive = true;
            }
            Location last = locationManager.getLastKnownLocation(
                    gps ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER);
            if (last != null && System.currentTimeMillis() - last.getTime() < 60_000L) {
                currentLocation = last;
                currentLocationMs = last.getTime();
            }
        } catch (Exception ignored) {}
    }

    private void stopLocationUpdates() {
        if (locationManager != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        }
        locationUpdatesActive = false;
    }

    private void saveParkedLocation() {
        try {
            long now = System.currentTimeMillis();
            Location location = null;
            if (currentLocation != null && now - currentLocationMs < 10 * 60_000L) {
                location = currentLocation;
            }
            if (location == null && locationManager != null && hasLocationPermission()) {
                try { location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
                if (location == null) {
                    try { location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
                }
            }
            if (location != null) {
                prefs.edit()
                        .putFloat(PREF_PARKED_LAT, (float) location.getLatitude())
                        .putFloat(PREF_PARKED_LON, (float) location.getLongitude())
                        .putLong(PREF_PARKED_TIME, now)
                        .apply();
            }
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------------
    // GATT writes
    // ---------------------------------------------------------------------

    private void enqueueWrite(byte[] packet) {
        if (packet == null) return;
        if (writeQueue.size() > 16) writeQueue.poll();
        writeQueue.add(packet);
        processNextWrite();
    }

    private synchronized void processNextWrite() {
        if (isWriting.get()) return;
        byte[] packet = writeQueue.poll();
        if (packet == null) return;
        if (bluetoothGatt == null || rxCharacteristic == null) {
            writeQueue.clear();
            return;
        }

        isWriting.set(true);
        int result;
        try {
            result = bluetoothGatt.writeCharacteristic(rxCharacteristic, packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } catch (Exception e) {
            isWriting.set(false);
            mainHandler.postDelayed(this::processNextWrite, 60);
            return;
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "TX " + packet.length + " bytes (res=" + result + ")");
        }
        mainHandler.postDelayed(() -> {
            if (isWriting.compareAndSet(true, false)) processNextWrite();
        }, 60);
    }

    private void closeGatt() {
        writeQueue.clear();
        isWriting.set(false);
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        rxCharacteristic = null;
        isConnected = false;
        sIsConnected = false;
        connectInFlight = false;
        connectInFlightAddress = null;
        authCandidates.clear();
        authCandidateIndex = 0;
    }

    private void disconnectGatt(boolean finalizeRide) {
        stopScan();
        stopPeriodicSync();
        stopProcessor();
        if (finalizeRide) finishRide();
        else stopLocationUpdates();
        writeQueue.clear();
        isWriting.set(false);
        handshakeComplete = false;
        sessionActive = false;
        sSessionActive = false;
        authCandidates.clear();
        authCandidateIndex = 0;
        connectRetries = 0;
        skipBondedOnNextScan = false;
        closeGatt();
        packetCount = 0;
        sPacketCount = 0;
    }

    // ---------------------------------------------------------------------
    // Phone call relay
    // ---------------------------------------------------------------------

    private void registerTelephonyIfAllowed() {
        if (!hasPhonePermission()) return;
        if (callStateCallback != null) return;
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return;
        try {
            callStateCallback = new CallStateCallback();
            tm.registerTelephonyCallback(getMainExecutor(), callStateCallback);
        } catch (Exception e) {
            callStateCallback = null;
            Log.w(TAG, "Telephony callback unavailable: " + e.getMessage());
        }
    }

    private void unregisterTelephony() {
        if (callStateCallback == null) return;
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) tm.unregisterTelephonyCallback(callStateCallback);
        } catch (Exception ignored) {
        }
        callStateCallback = null;
    }

    private class CallStateCallback extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            boolean ringing = state == TelephonyManager.CALL_STATE_RINGING;
            if (ringing) {
                isIncomingCall = true;
                isMissedCall = false;
                wasRinging = true;
            } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                isIncomingCall = false;
                if (wasRinging) isMissedCall = true;
                wasRinging = false;
            } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                isIncomingCall = false;
                isMissedCall = false;
                wasRinging = false;
            }
            notificationDirty = true;
            if (isConnected) sendNotificationSync();
        }
    }

    // ---------------------------------------------------------------------
    // Permissions / cleanup
    // ---------------------------------------------------------------------

    private boolean hasBluetoothPermission() {
        return checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasPhonePermission() {
        return checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void checkStopIfDisconnected() {
        if (!isConnected && !sessionActive && !isScanning && boundClients.get() <= 0) {
            AppLogManager.getInstance().log(TAG, "Battery saver: stopping idle service");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }
}
