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
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.maintenance.FuelManager;
import com.xsr.companion.maintenance.MaintenanceManager;
import com.xsr.companion.protocol.BikeTelemetry;
import com.xsr.companion.protocol.YamahaProtocol;
import com.xsr.companion.ui.MainActivity;
import com.xsr.companion.util.AppLogManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint("MissingPermission")
public class XsrBleService extends Service {

    private static final String TAG = "XsrBleService";

    public static final String CHANNEL_ID = "xsr_companion_service";
    public static final int NOTIFICATION_ID = 155;

    public static final String ACTION_START_SCAN = "com.xsr.companion.ACTION_START_SCAN";
    public static final String ACTION_STOP_SCAN = "com.xsr.companion.ACTION_STOP_SCAN";
    public static final String ACTION_DISCONNECT = "com.xsr.companion.ACTION_DISCONNECT";
    public static final String ACTION_CONNECT_MAC = "com.xsr.companion.ACTION_CONNECT_MAC";
    public static final String EXTRA_MAC_ADDRESS = "extra_mac_address";

    public static final String BROADCAST_STATUS = "com.xsr.companion.BROADCAST_STATUS";
    public static final String EXTRA_STATUS_TEXT = "extra_status_text";
    public static final String EXTRA_IS_CONNECTED = "extra_is_connected";
    public static final String EXTRA_BATTERY_LEVEL = "extra_battery_level";
    public static final String EXTRA_PACKET_COUNT = "extra_packet_count";
    public static final String EXTRA_DEVICE_NAME = "extra_device_name";

    public static final String BROADCAST_TELEMETRY = "com.xsr.companion.BROADCAST_TELEMETRY";
    public static final String EXTRA_SPEED = "extra_speed";
    public static final String EXTRA_RPM = "extra_rpm";
    public static final String EXTRA_THROTTLE = "extra_throttle";
    public static final String EXTRA_COOLANT = "extra_coolant";
    public static final String EXTRA_ODO = "extra_odo";
    public static final String EXTRA_WARNING = "extra_warning";
    public static final String EXTRA_DTC_CODE = "extra_dtc_code";
    public static final String EXTRA_INSTANT_MILEAGE = "extra_instant_mileage";
    public static final String EXTRA_TRIP_AVG_MILEAGE = "extra_trip_avg_mileage";
    public static final String EXTRA_FUEL_RATE = "extra_fuel_rate";
    public static final String EXTRA_TRIP_FUEL_L = "extra_trip_fuel_l";
    public static final String EXTRA_REMAINING_FUEL_L = "extra_remaining_fuel_l";
    public static final String EXTRA_ESTIMATED_RANGE_KM = "extra_estimated_range_km";

    public static final String BROADCAST_RIDE_FINISHED = "com.xsr.companion.BROADCAST_RIDE_FINISHED";

    public static final String PREFS_NAME = "xsr_companion_prefs";
    public static final String PREF_LAST_MAC = "last_connected_mac";
    public static final String PREF_LAST_NAME = "last_connected_name";
    public static final String PREF_PARKED_LAT = "parked_latitude";
    public static final String PREF_PARKED_LON = "parked_longitude";
    public static final String PREF_PARKED_TIME = "parked_timestamp";
    public static final String PREF_LATEST_BIKE_ODO = "pref_latest_bike_odo";
    public static final String PREF_PASSKEY = "pref_passkey";
    public static final String PREF_PHONE_UUID = "pref_phone_uuid";

    // Public static state for instant Activity UI synchronization
    public static volatile boolean sIsConnected = false;
    public static volatile String sStatusText = "Disconnected";
    public static volatile String sDeviceName = "No CCU Paired";
    public static volatile int sPacketCount = 0;
    public static volatile int sBatteryLevel = 100;
    public static volatile double sInstantMileage = 0.0;
    public static volatile double sTripAvgMileage = 48.5;
    public static volatile double sFuelRemainingL = 10.0;
    public static volatile double sEstimatedRangeKm = 485.0;
    public static volatile double sLatestBikeOdo = 0.0;
    public static final BikeTelemetry sLatestTelemetry = new BikeTelemetry();

    // Binder and client tracking for background battery optimization
    public class LocalBinder extends android.os.Binder {
        public XsrBleService getService() {
            return XsrBleService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private int boundClients = 0;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic rxCharacteristic;

    private boolean isScanning = false;
    private boolean isConnected = false;
    private boolean isHandshakeComplete = false;
    private final ConcurrentLinkedQueue<byte[]> writeQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean isWriting = new AtomicBoolean(false);
    private int authCandidateIndex = 0;
    private final List<AuthCandidate> authCandidates = new ArrayList<>();

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
    private String currentDeviceName = "No CCU Paired";

    private boolean isIncomingCall = false;
    private boolean isMissedCall = false;
    private boolean hasUnreadMessage = false;

    private int syncCounter = 0;
    private Handler mainHandler;
    private Runnable syncRunnable;

    private SharedPreferences prefs;
    private RideDatabaseHelper rideDb;
    private MaintenanceManager maintenanceManager;
    private FuelManager fuelManager;
    private final BikeTelemetry latestTelemetry = new BikeTelemetry();

    private LocationManager locationManager;
    private Location currentLocation = null;
    private long currentRideId = -1;
    private long lastPointRecordTime = 0;
    private double rideStartOdo = 0.0;

    // Battery level receiver
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    currentBatteryLevel = (level * 100) / scale;
                }
            }
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            currentLocation = location;
            checkAndRecordRidePoint();
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.contains(PREF_PHONE_UUID)) {
            String uuid = UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString(PREF_PHONE_UUID, uuid).apply();
        }
        rideDb = new RideDatabaseHelper(this);
        maintenanceManager = new MaintenanceManager(this);
        fuelManager = new FuelManager(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());

        currentDeviceName = prefs.getString(PREF_LAST_NAME, "No CCU Paired");
        sDeviceName = currentDeviceName;
        sIsConnected = isConnected;
        sLatestBikeOdo = prefs.getFloat(PREF_LATEST_BIKE_ODO, 0.0f);
        sFuelRemainingL = fuelManager.getRemainingLitres();
        sEstimatedRangeKm = fuelManager.getEstimatedRangeKm(FuelManager.DEFAULT_ESTIMATED_MILEAGE_KML);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            bluetoothAdapter = bm.getAdapter();
            if (bluetoothAdapter != null) {
                bleScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        }

        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        setupCallStateListener();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "XSR Companion Active",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintains connection with Yamaha motorcycle meter");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildForegroundNotification(String status) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("Yamaha XSR Companion")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            }
            startForeground(NOTIFICATION_ID, buildForegroundNotification("Active"), type);
        } else {
            startForeground(NOTIFICATION_ID, buildForegroundNotification("Active"));
        }

        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_START_SCAN.equals(action)) {
                startScan();
            } else if (ACTION_STOP_SCAN.equals(action)) {
                stopScan();
            } else if (ACTION_DISCONNECT.equals(action)) {
                disconnectGatt();
            } else if (ACTION_CONNECT_MAC.equals(action)) {
                String mac = intent.getStringExtra(EXTRA_MAC_ADDRESS);
                if (mac != null && !mac.isEmpty()) {
                    connectToMac(mac);
                }
            }
        }

        return START_STICKY;
    }

    private void updateNotification(String status) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildForegroundNotification(status));
        }
    }

    private void broadcastStatus(String statusText) {
        sIsConnected = isConnected;
        sStatusText = statusText;
        sDeviceName = currentDeviceName;
        sPacketCount = packetCount;
        sBatteryLevel = currentBatteryLevel;

        AppLogManager.getInstance().log(TAG, "Status: " + statusText);
        Intent intent = new Intent(BROADCAST_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS_TEXT, statusText);
        intent.putExtra(EXTRA_IS_CONNECTED, isConnected);
        intent.putExtra(EXTRA_BATTERY_LEVEL, currentBatteryLevel);
        intent.putExtra(EXTRA_PACKET_COUNT, packetCount);
        intent.putExtra(EXTRA_DEVICE_NAME, currentDeviceName);
        sendBroadcast(intent);
        updateNotification(statusText);
    }

    private void broadcastTelemetry() {
        Intent intent = new Intent(BROADCAST_TELEMETRY);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_SPEED, latestTelemetry.speedKmh);
        intent.putExtra(EXTRA_RPM, latestTelemetry.engineRpm);
        intent.putExtra(EXTRA_THROTTLE, latestTelemetry.throttlePct);
        intent.putExtra(EXTRA_COOLANT, latestTelemetry.coolantTemp);
        intent.putExtra(EXTRA_ODO, latestTelemetry.odometerKm);
        intent.putExtra(EXTRA_WARNING, latestTelemetry.warningLamp);
        intent.putExtra(EXTRA_DTC_CODE, latestTelemetry.dtcCode);
        intent.putExtra(EXTRA_INSTANT_MILEAGE, latestTelemetry.instantMileageKmL);
        intent.putExtra(EXTRA_TRIP_AVG_MILEAGE, latestTelemetry.tripAvgMileageKmL);
        intent.putExtra(EXTRA_FUEL_RATE, latestTelemetry.fuelConsumptionRateLh);
        intent.putExtra(EXTRA_TRIP_FUEL_L, latestTelemetry.tripFuelConsumedL);
        intent.putExtra(EXTRA_REMAINING_FUEL_L, latestTelemetry.remainingFuelL);
        intent.putExtra(EXTRA_ESTIMATED_RANGE_KM, sEstimatedRangeKm);
        sendBroadcast(intent);
    }

    /**
     * Checks if the Yamaha CCU is already paired/bonded in Android OS.
     * If found, establishes a direct LE GATT connection immediately.
     */
    public boolean connectToBondedIfPresent() {
        if (bluetoothAdapter == null) return false;
        try {
            Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
            if (bondedDevices != null && !bondedDevices.isEmpty()) {
                String savedMac = prefs.getString(PREF_LAST_MAC, "");
                for (BluetoothDevice device : bondedDevices) {
                    String name = device.getName();
                    String addr = device.getAddress();

                    boolean isYamaha = (name != null && (name.startsWith(YamahaProtocol.PREFIX_YSCCU) || name.startsWith(YamahaProtocol.PREFIX_YCCU)))
                            || (addr != null && addr.equalsIgnoreCase("CA:D7:9C:D9:40:4A"))
                            || (savedMac != null && !savedMac.isEmpty() && addr != null && addr.equalsIgnoreCase(savedMac));

                    if (isYamaha) {
                        String displayName = name != null ? name : "YSCCU (" + addr + ")";
                        currentDeviceName = displayName;
                        broadcastStatus("Found bonded CCU: " + displayName + ", connecting directly...");
                        connectToDevice(device);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking bonded devices: " + e.getMessage());
        }
        return false;
    }

    public void startScan() {
        if (isConnected) {
            broadcastStatus("Already connected to CCU");
            return;
        }

        // 1. Prioritize connecting directly to already-bonded CCU
        if (connectToBondedIfPresent()) {
            return;
        }

        if (bleScanner == null) {
            if (bluetoothAdapter != null) {
                bleScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
            if (bleScanner == null) {
                broadcastStatus("Bluetooth LE Scanner not available");
                return;
            }
        }

        if (isScanning) return;
        isScanning = true;
        broadcastStatus("Scanning for Yamaha CCU...");

        try {
            List<ScanFilter> filters = new ArrayList<>();
            // Match advertised name prefix or CCU ID
            filters.add(new ScanFilter.Builder().setDeviceName("YSCCU_00000005826927").build());
            // Match Nordic UART Service UUID
            filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(YamahaProtocol.NUS_SERVICE_UUID)).build());

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            bleScanner.startScan(filters, settings, scanCallback);
        } catch (Exception e) {
            // Fallback to open scan
            try {
                bleScanner.startScan(scanCallback);
            } catch (Exception e2) {
                isScanning = false;
                broadcastStatus("Scan error: " + e2.getMessage());
            }
        }
    }

    public void stopScan() {
        if (bleScanner != null && isScanning) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (Exception ignored) {}
            isScanning = false;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null) return;
            BluetoothDevice device = result.getDevice();

            // Check advertisement record first (where name resides before pairing)
            String name = null;
            if (result.getScanRecord() != null && result.getScanRecord().getDeviceName() != null) {
                name = result.getScanRecord().getDeviceName();
            } else if (device != null && device.getName() != null) {
                name = device.getName();
            }

            String addr = device != null ? device.getAddress() : "";
            String savedMac = prefs.getString(PREF_LAST_MAC, "");

            boolean isMatch = (name != null && (name.startsWith(YamahaProtocol.PREFIX_YSCCU) || name.startsWith(YamahaProtocol.PREFIX_YCCU)))
                    || addr.equalsIgnoreCase("CA:D7:9C:D9:40:4A")
                    || (!savedMac.isEmpty() && addr.equalsIgnoreCase(savedMac));

            if (isMatch && device != null) {
                stopScan();
                String displayName = name != null ? name : "YSCCU (" + addr + ")";
                currentDeviceName = displayName;
                broadcastStatus("Discovered " + displayName + " (" + result.getRssi() + " dBm), connecting...");
                connectToDevice(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            isScanning = false;
            broadcastStatus("BLE Scan failed (code " + errorCode + ")");
        }
    };

    private void connectToMac(String mac) {
        if (bluetoothAdapter == null) return;
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(mac);
        if (device != null) {
            connectToDevice(device);
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        disconnectGatt();

        String name = device.getName() != null ? device.getName() : currentDeviceName;
        currentDeviceName = name;
        broadcastStatus("Initiating LE connection to " + name + "...");
        AppLogManager.getInstance().log(TAG, "Initiating LE GATT connection to %s (%s)", name, device.getAddress());

        prefs.edit()
                .putString(PREF_LAST_MAC, device.getAddress())
                .putString(PREF_LAST_NAME, name)
                .apply();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    private void disconnectGatt() {
        stopPeriodicSync();
        stopRideTracking();
        writeQueue.clear();
        isWriting.set(false);
        isHandshakeComplete = false;
        authCandidates.clear();
        authCandidateIndex = 0;
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        rxCharacteristic = null;
        isConnected = false;
        packetCount = 0;
    }

    /**
     * Sequential BLE GATT Callback State Machine:
     * 1. STATE_CONNECTED -> requestConnectionPriority(HIGH) -> 300ms delay -> discoverServices()
     * 2. onServicesDiscovered -> setCharacteristicNotification(6E400003) -> writeDescriptor(0x2902)
     * 3. onDescriptorWrite -> requestMtu(512)
     * 4. onMtuChanged -> sendAuthPacket(0x7F00)
     * 5. onCharacteristicChanged -> receive 0x7F01 (startProcessingFlag=1) -> startRideTracking() -> startPeriodicSync()
     * 6. onCharacteristicChanged -> parseIncomingPacket(0x55, 0x56) -> broadcastTelemetry()
     */
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                isHandshakeComplete = false;
                writeQueue.clear();
                isWriting.set(false);
                packetCount = 0;
                broadcastStatus("Connected to CCU. Discovering services...");

                // Set high connection priority for fast telemetry streaming
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);

                // Delay service discovery slightly to avoid GATT concurrency race condition on Android
                mainHandler.postDelayed(() -> {
                    if (bluetoothGatt != null) {
                        broadcastStatus("Querying CCU GATT services...");
                        boolean discoveryStarted = bluetoothGatt.discoverServices();
                        if (!discoveryStarted) {
                            broadcastStatus("Service discovery initiation failed");
                        }
                    }
                }, 300);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                isHandshakeComplete = false;
                writeQueue.clear();
                isWriting.set(false);
                stopPeriodicSync();
                rxCharacteristic = null;

                // Stop and save ride automatically!
                stopRideTracking();

                // Save last parked location!
                saveParkedLocation();

                broadcastStatus("Disconnected (Motorcycle turned OFF or status " + status + ")");
                AppLogManager.getInstance().log(TAG, "Motorcycle disconnected (GATT status=%d)", status);

                // Battery Saver: If disconnected and no UI bound, stop background service
                mainHandler.postDelayed(() -> checkStopIfDisconnected(), 1500);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(YamahaProtocol.NUS_SERVICE_UUID);
                if (service != null) {
                    rxCharacteristic = service.getCharacteristic(YamahaProtocol.NUS_RX_CHAR_UUID);
                    BluetoothGattCharacteristic txCharacteristic = service.getCharacteristic(YamahaProtocol.NUS_TX_CHAR_UUID);

                    if (rxCharacteristic != null) {
                        rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    }

                    if (txCharacteristic != null) {
                        gatt.setCharacteristicNotification(txCharacteristic, true);
                        BluetoothGattDescriptor descriptor = txCharacteristic.getDescriptor(YamahaProtocol.CLIENT_CONFIG_DESCRIPTOR);
                        if (descriptor != null) {
                            broadcastStatus("Enabling CCU notifications...");
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            } else {
                                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                gatt.writeDescriptor(descriptor);
                            }
                            return;
                        }
                    }
                }
                broadcastStatus("CCU NUS Service not found on device");
                AppLogManager.getInstance().log(TAG, "CCU NUS service missing on target device");
            } else {
                broadcastStatus("Service discovery failed: status " + status);
                AppLogManager.getInstance().log(TAG, "Service discovery failed with status: %d", status);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastStatus("Notifications enabled. Requesting MTU 512...");
                AppLogManager.getInstance().log(TAG, "NUS TX Notification descriptor enabled successfully. Requesting MTU 512...");
                // Request MTU 512 after descriptor write has successfully completed
                gatt.requestMtu(512);
            } else {
                broadcastStatus("Descriptor write error: " + status);
                AppLogManager.getInstance().log(TAG, "Descriptor write failed with status: %d", status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            BluetoothDevice dev = gatt.getDevice();
            int bondState = (dev != null) ? dev.getBondState() : BluetoothDevice.BOND_NONE;
            String devName = (dev != null && dev.getName() != null) ? dev.getName() : currentDeviceName;
            String ccuId = YamahaProtocol.cleanCcuId(devName);

            AppLogManager.getInstance().log(TAG, "onMtuChanged: mtu=%d, status=%d, bondState=%d, ccuId=%s", mtu, status, bondState, ccuId);

            // If the motorcycle is already bonded in Android OS, the CCU already trusts this phone!
            // Do NOT cycle through rejected 0x7F00 candidate PINs which triggers CCU lockout (status 147)
            if (bondState == BluetoothDevice.BOND_BONDED) {
                AppLogManager.getInstance().log(TAG, "CCU is BONDED in Android OS. Telemetry streams directly without 0x7F00 PIN handshake.");
                isHandshakeComplete = true;
                authCandidates.clear();
                authCandidateIndex = 0;
                broadcastStatus("Connected & Syncing with Meter (Bonded)");
                startRideTracking();
                startPeriodicSync();
                return;
            }

            // Unbonded device: prepare candidates
            isHandshakeComplete = false;
            broadcastStatus("MTU " + mtu + " negotiated. Authenticating with CCU...");

            prepareAuthCandidates(ccuId);
            trySendNextAuth();

            // Watchdog fallback: if CCU sends telemetry directly or misses 0x7F01, start sync after 4.5s
            mainHandler.postDelayed(() -> {
                if (isConnected && !isHandshakeComplete) {
                    AppLogManager.getInstance().log(TAG, "Auth response watchdog timeout (4.5s). Starting periodic sync fallback.");
                    isHandshakeComplete = true;
                    authCandidates.clear();
                    authCandidateIndex = 0;
                    broadcastStatus("Connected & Syncing with Meter");
                    startRideTracking();
                    startPeriodicSync();
                }
            }, 4500);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "onCharacteristicWrite status=" + status);
            if (isWriting.compareAndSet(true, false)) {
                processNextWrite();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) {
                Log.d(TAG, "RX Packet (legacy): " + bytesToHex(value));
            }
            handleCharacteristicUpdate(value);
        }

        // Android 13+ (API 33+)
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (value != null) {
                Log.d(TAG, "RX Packet (API33+): " + bytesToHex(value));
            }
            handleCharacteristicUpdate(value);
        }
    };

    private void prepareAuthCandidates(String ccuId) {
        authCandidates.clear();
        authCandidateIndex = 0;

        String userPass = prefs.getString(PREF_PASSKEY, "").trim();
        String last6Ccu = ccuId.length() >= 6 ? ccuId.substring(ccuId.length() - 6) : "826927";

        // 1. If user set custom passkey / vehicle VIN
        if (!userPass.isEmpty()) {
            if (userPass.length() > 6) {
                // VIN / Chassis number entered (e.g. 17 chars):
                // Candidate A: Last 6 digits/characters (the serial number)
                String last6 = userPass.substring(userPass.length() - 6);
                authCandidates.add(new AuthCandidate(last6, true));
                authCandidates.add(new AuthCandidate(last6, false));
                // Candidate B: First 6 characters
                String first6 = userPass.substring(0, 6);
                authCandidates.add(new AuthCandidate(first6, true));
            } else {
                authCandidates.add(new AuthCandidate(userPass, true));
                authCandidates.add(new AuthCandidate(userPass, false));
            }
        }

        // 2. Last 6 digits of CCUID (826927) with updateBondingFlag=1 (registers new phone UUID)
        authCandidates.add(new AuthCandidate(last6Ccu, true));

        // 3. Last 6 digits of CCUID (826927) with updateBondingFlag=0
        authCandidates.add(new AuthCandidate(last6Ccu, false));

        // 4. Default 000000 with updateBondingFlag=1
        authCandidates.add(new AuthCandidate("000000", true));

        // 5. Standard 123456 with updateBondingFlag=1
        authCandidates.add(new AuthCandidate("123456", true));
    }

    private void trySendNextAuth() {
        if (bluetoothGatt == null || !isConnected || isHandshakeComplete) return;
        if (authCandidateIndex >= authCandidates.size()) {
            broadcastStatus("All PIN candidates rejected. Tap CCU Name to enter PIN.");
            AppLogManager.getInstance().log(TAG, "All auth candidates exhausted without positive ACK.");
            return;
        }

        AuthCandidate candidate = authCandidates.get(authCandidateIndex++);
        String devName = bluetoothGatt.getDevice().getName();
        String ccuId = YamahaProtocol.cleanCcuId(devName != null ? devName : currentDeviceName);
        String phoneUuid = prefs.getString(PREF_PHONE_UUID, "");

        broadcastStatus("Authenticating CCU (PIN " + candidate.passkey + ", bond=" + (candidate.updateBondingFlag ? "1" : "0") + ")...");
        AppLogManager.getInstance().log(TAG, "Trying Auth Candidate [%d/%d]: PIN=%s, bondFlag=%b",
                authCandidateIndex, authCandidates.size(), candidate.passkey, candidate.updateBondingFlag);

        byte[] authPacket = YamahaProtocol.buildAuthPacket(ccuId, candidate.passkey, phoneUuid, candidate.updateBondingFlag, syncCounter++);
        enqueueWrite(authPacket);
    }

    private void handleCharacteristicUpdate(byte[] data) {
        if (data == null || data.length == 0) return;

        // 1. Check if packet is CCU Auth response 0x7F01 (SCCU1StartProcEnt)
        int startProcAck = YamahaProtocol.parseStartProcessingAck(data);
        if (startProcAck != -1) {
            if (startProcAck == 1) {
                isHandshakeComplete = true;
                AuthCandidate successfulCandidate = authCandidateIndex > 0 ? authCandidates.get(authCandidateIndex - 1) : null;
                if (successfulCandidate != null) {
                    prefs.edit().putString(PREF_PASSKEY, successfulCandidate.passkey).apply();
                    AppLogManager.getInstance().log(TAG, "CCU Authentication ACCEPTED! Saved working passkey: %s", successfulCandidate.passkey);
                }
                broadcastStatus("CCU Authenticated! Live streaming active.");
                startRideTracking();
                startPeriodicSync();
            } else {
                AppLogManager.getInstance().log(TAG, "CCU Auth candidate rejected (ack=0). Retrying next candidate...");
                mainHandler.postDelayed(this::trySendNextAuth, 120);
            }
            return;
        }

        // 2. If telemetry packet arrives, mark handshake complete IMMEDIATELY and CANCEL any remaining auth candidates!
        if (!isHandshakeComplete && (data[0] == YamahaProtocol.START_BYTE_55 || data[0] == YamahaProtocol.START_BYTE_56)) {
            AppLogManager.getInstance().log(TAG, "Telemetry streaming verified (0x%02X). Marking handshake complete and clearing pending auth.", data[0]);
            isHandshakeComplete = true;
            authCandidates.clear();
            authCandidateIndex = 0;
            broadcastStatus("Connected & Syncing with Meter");
            startRideTracking();
            startPeriodicSync();
        }

        boolean updated = YamahaProtocol.parseIncomingPacket(data, latestTelemetry);
        if (updated) {
            packetCount++;
            sPacketCount = packetCount;

            // 1. Calculate live fuel flow rate and instantaneous mileage
            double fuelRate = YamahaProtocol.calculateFuelRateLh(
                    latestTelemetry.engineRpm,
                    latestTelemetry.throttlePct,
                    latestTelemetry.airTemp,
                    latestTelemetry.atmosphericPressure
            );
            double instantMileage = YamahaProtocol.calculateInstantMileageKmL(
                    latestTelemetry.speedKmh,
                    fuelRate,
                    latestTelemetry.throttlePct
            );
            latestTelemetry.fuelConsumptionRateLh = fuelRate;
            latestTelemetry.instantMileageKmL = instantMileage;

            // 2. Fuel consumption integration (every ~1s while engine running)
            if (latestTelemetry.engineRpm > 0 && fuelRate > 0) {
                double fuelThisSecL = fuelRate / 3600.0;
                latestTelemetry.tripFuelConsumedL += fuelThisSecL;
                if (fuelManager != null) {
                    fuelManager.consumeFuel(fuelThisSecL);
                }
            }
            if (fuelManager != null) {
                latestTelemetry.remainingFuelL = fuelManager.getRemainingLitres();
            }

            // 3. Trip average mileage
            double tripDistanceKm = (currentRideId > 0 && rideStartOdo > 0 && latestTelemetry.odometerKm > rideStartOdo)
                    ? (latestTelemetry.odometerKm - rideStartOdo)
                    : 0.0;
            if (tripDistanceKm > 0.05 && latestTelemetry.tripFuelConsumedL > 0.002) {
                latestTelemetry.tripAvgMileageKmL = tripDistanceKm / latestTelemetry.tripFuelConsumedL;
            } else if (instantMileage > 0) {
                latestTelemetry.tripAvgMileageKmL = instantMileage;
            } else {
                latestTelemetry.tripAvgMileageKmL = FuelManager.DEFAULT_ESTIMATED_MILEAGE_KML;
            }

            // Update static volatiles for instant UI reflection
            sInstantMileage = instantMileage;
            sTripAvgMileage = latestTelemetry.tripAvgMileageKmL;
            sFuelRemainingL = latestTelemetry.remainingFuelL;
            if (fuelManager != null) {
                sEstimatedRangeKm = fuelManager.getEstimatedRangeKm(latestTelemetry.tripAvgMileageKmL);
            }

            if (latestTelemetry.odometerKm > 0) {
                sLatestBikeOdo = latestTelemetry.odometerKm;
                prefs.edit().putFloat(PREF_LATEST_BIKE_ODO, (float) latestTelemetry.odometerKm).apply();
                maintenanceManager.updateOdometer(latestTelemetry.odometerKm);
            }

            synchronized (sLatestTelemetry) {
                sLatestTelemetry.copyFrom(latestTelemetry);
            }

            checkAndRecordRidePoint();
            broadcastTelemetry();
        }
    }

    private void enqueueWrite(byte[] packet) {
        if (packet == null) return;
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
        try {
            rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            int res = -1;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                res = bluetoothGatt.writeCharacteristic(rxCharacteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                rxCharacteristic.setValue(packet);
                boolean ok = bluetoothGatt.writeCharacteristic(rxCharacteristic);
                res = ok ? 0 : -1;
            }
            Log.d(TAG, "TX Packet (" + packet.length + " bytes, res=" + res + "): " + bytesToHex(packet));
        } catch (Exception e) {
            Log.e(TAG, "Write error: " + e.getMessage());
            isWriting.set(false);
            mainHandler.postDelayed(this::processNextWrite, 50);
            return;
        }

        // Fallback watchdog: unlock after 50ms if onCharacteristicWrite is not invoked by OS
        mainHandler.postDelayed(() -> {
            if (isWriting.compareAndSet(true, false)) {
                processNextWrite();
            }
        }, 50);
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private void sendStartStreamingCommand() {
        byte[] packet = YamahaProtocol.buildStartStreamingPacket(syncCounter++);
        enqueueWrite(packet);
    }

    private void startRideTracking() {
        rideStartOdo = latestTelemetry.odometerKm > 0 ? latestTelemetry.odometerKm : sLatestBikeOdo;
        latestTelemetry.tripFuelConsumedL = 0.0;
        currentRideId = rideDb.startNewRide(System.currentTimeMillis());
        AppLogManager.getInstance().log(TAG, "Started ride session #%d (start odo=%.1f km)", currentRideId, rideStartOdo);
        try {
            if (locationManager != null) {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, locationListener);
                } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1500L, 2.0f, locationListener);
                }
            }
        } catch (Exception ignored) {}
    }

    private void checkAndRecordRidePoint() {
        if (currentRideId <= 0) return;
        long now = System.currentTimeMillis();
        // Record at most once every 1000ms
        if (now - lastPointRecordTime < 950) return;
        lastPointRecordTime = now;

        double lat = currentLocation != null ? currentLocation.getLatitude() : 0.0;
        double lon = currentLocation != null ? currentLocation.getLongitude() : 0.0;

        rideDb.insertPoint(
                currentRideId,
                lat,
                lon,
                latestTelemetry.speedKmh,
                latestTelemetry.engineRpm,
                latestTelemetry.throttlePct,
                latestTelemetry.coolantTemp
        );
    }

    private void stopRideTracking() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (Exception ignored) {}
        }

        if (currentRideId > 0) {
            long rideId = currentRideId;
            boolean saved = rideDb.finalizeRide(rideId, System.currentTimeMillis());
            currentRideId = -1;
            AppLogManager.getInstance().log(TAG, "Finalized ride session #%d (saved=%b)", rideId, saved);
            if (saved) {
                Intent finishIntent = new Intent(BROADCAST_RIDE_FINISHED);
                finishIntent.setPackage(getPackageName());
                sendBroadcast(finishIntent);
            }
        }
    }

    private void startPeriodicSync() {
        stopPeriodicSync();
        syncRunnable = new Runnable() {
            @Override
            public void run() {
                if (isConnected && bluetoothGatt != null && rxCharacteristic != null) {
                    sendClockAndBatterySync();

                    // Send notification flags after 100ms
                    mainHandler.postDelayed(() -> sendNotificationSync(), 100);

                    mainHandler.postDelayed(this, 1000);
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
        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int second = cal.get(Calendar.SECOND);

        byte[] packet = YamahaProtocol.buildBatteryAndClockPacket(
                year, month, day, hour, minute, second,
                currentBatteryLevel,
                syncCounter++
        );

        enqueueWrite(packet);
    }

    private void sendNotificationSync() {
        byte[] packet = YamahaProtocol.buildNotificationPacket(
                isIncomingCall,
                isMissedCall,
                hasUnreadMessage,
                false,
                syncCounter++
        );

        enqueueWrite(packet);
    }

    private void saveParkedLocation() {
        try {
            if (locationManager != null) {
                Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (location == null) {
                    location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
                if (location != null) {
                    prefs.edit()
                            .putFloat(PREF_PARKED_LAT, (float) location.getLatitude())
                            .putFloat(PREF_PARKED_LON, (float) location.getLongitude())
                            .putLong(PREF_PARKED_TIME, System.currentTimeMillis())
                            .apply();
                }
            }
        } catch (Exception ignored) {}
    }

    private void setupCallStateListener() {
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Executor executor = getMainExecutor();
            tm.registerTelephonyCallback(executor, new CallStateCallback());
        }
    }

    private class CallStateCallback extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            isIncomingCall = (state == TelephonyManager.CALL_STATE_RINGING);
            if (isConnected) {
                sendNotificationSync();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppLogManager.getInstance().log(TAG, "XsrBleService onDestroy");
        stopScan();
        disconnectGatt();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        boundClients++;
        AppLogManager.getInstance().log(TAG, "Client bound (active clients: %d)", boundClients);
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        boundClients = Math.max(0, boundClients - 1);
        AppLogManager.getInstance().log(TAG, "Client unbound (remaining clients: %d)", boundClients);
        checkStopIfDisconnected();
        return super.onUnbind(intent);
    }

    /**
     * Battery Saver:
     * If motorcycle is NOT connected and no Activity/UI is bound, shut down service completely.
     */
    public void checkStopIfDisconnected() {
        if (!isConnected && boundClients <= 0) {
            AppLogManager.getInstance().log(TAG, "Battery Saver: Bike disconnected and no active UI. Shutting down service.");
            stopScan();
            disconnectGatt();
            stopForeground(true);
            stopSelf();
        }
    }
}
