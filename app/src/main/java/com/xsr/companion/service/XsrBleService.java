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
import android.bluetooth.le.ScanResult;
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
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.maintenance.MaintenanceManager;
import com.xsr.companion.protocol.BikeTelemetry;
import com.xsr.companion.protocol.YamahaProtocol;
import com.xsr.companion.ui.MainActivity;

import java.util.Calendar;
import java.util.concurrent.Executor;

@SuppressLint("MissingPermission")
public class XsrBleService extends Service {

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

    public static final String BROADCAST_TELEMETRY = "com.xsr.companion.BROADCAST_TELEMETRY";
    public static final String EXTRA_SPEED = "extra_speed";
    public static final String EXTRA_RPM = "extra_rpm";
    public static final String EXTRA_THROTTLE = "extra_throttle";
    public static final String EXTRA_COOLANT = "extra_coolant";
    public static final String EXTRA_ODO = "extra_odo";
    public static final String EXTRA_WARNING = "extra_warning";
    public static final String EXTRA_DTC_CODE = "extra_dtc_code";

    public static final String BROADCAST_RIDE_FINISHED = "com.xsr.companion.BROADCAST_RIDE_FINISHED";

    public static final String PREFS_NAME = "xsr_companion_prefs";
    public static final String PREF_LAST_MAC = "last_connected_mac";
    public static final String PREF_LAST_NAME = "last_connected_name";
    public static final String PREF_PARKED_LAT = "parked_latitude";
    public static final String PREF_PARKED_LON = "parked_longitude";
    public static final String PREF_PARKED_TIME = "parked_timestamp";

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic rxCharacteristic;

    private boolean isScanning = false;
    private boolean isConnected = false;
    private int currentBatteryLevel = 100;
    private boolean isIncomingCall = false;
    private boolean isMissedCall = false;
    private boolean hasUnreadMessage = false;

    private int syncCounter = 0;
    private Handler syncHandler;
    private Runnable syncRunnable;

    private SharedPreferences prefs;
    private RideDatabaseHelper rideDb;
    private MaintenanceManager maintenanceManager;
    private final BikeTelemetry latestTelemetry = new BikeTelemetry();

    private LocationManager locationManager;
    private Location currentLocation = null;
    private long currentRideId = -1;
    private long lastPointRecordTime = 0;

    // Battery receiver
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
        rideDb = new RideDatabaseHelper(this);
        maintenanceManager = new MaintenanceManager(this);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        syncHandler = new Handler(Looper.getMainLooper());

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
        Intent intent = new Intent(BROADCAST_STATUS);
        intent.putExtra(EXTRA_STATUS_TEXT, statusText);
        intent.putExtra(EXTRA_IS_CONNECTED, isConnected);
        intent.putExtra(EXTRA_BATTERY_LEVEL, currentBatteryLevel);
        sendBroadcast(intent);
        updateNotification(statusText);
    }

    private void broadcastTelemetry() {
        Intent intent = new Intent(BROADCAST_TELEMETRY);
        intent.putExtra(EXTRA_SPEED, latestTelemetry.speedKmh);
        intent.putExtra(EXTRA_RPM, latestTelemetry.engineRpm);
        intent.putExtra(EXTRA_THROTTLE, latestTelemetry.throttlePct);
        intent.putExtra(EXTRA_COOLANT, latestTelemetry.coolantTemp);
        intent.putExtra(EXTRA_ODO, latestTelemetry.odometerKm);
        intent.putExtra(EXTRA_WARNING, latestTelemetry.warningLamp);
        intent.putExtra(EXTRA_DTC_CODE, latestTelemetry.dtcCode);
        sendBroadcast(intent);
    }

    private void startScan() {
        if (bleScanner == null || isScanning || isConnected) return;
        isScanning = true;
        broadcastStatus("Scanning for Yamaha CCU...");

        try {
            bleScanner.startScan(scanCallback);
        } catch (Exception e) {
            isScanning = false;
            broadcastStatus("Scan error: " + e.getMessage());
        }
    }

    private void stopScan() {
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
            BluetoothDevice device = result.getDevice();
            if (device != null && device.getName() != null) {
                String name = device.getName();
                if (name.startsWith(YamahaProtocol.PREFIX_YCCU) || name.startsWith(YamahaProtocol.PREFIX_YSCCU)) {
                    stopScan();
                    broadcastStatus("Found " + name + ", connecting...");
                    connectToDevice(device);
                }
            }
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
        broadcastStatus("Connecting to " + device.getName() + "...");
        prefs.edit()
                .putString(PREF_LAST_MAC, device.getAddress())
                .putString(PREF_LAST_NAME, device.getName())
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
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        rxCharacteristic = null;
        isConnected = false;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                broadcastStatus("Connected to Yamaha CCU");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                stopPeriodicSync();
                rxCharacteristic = null;

                // Stop and save ride automatically!
                stopRideTracking();

                // Save last parked location!
                saveParkedLocation();

                broadcastStatus("Disconnected (Motorcycle turned OFF)");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(YamahaProtocol.NUS_SERVICE_UUID);
                if (service != null) {
                    rxCharacteristic = service.getCharacteristic(YamahaProtocol.NUS_RX_CHAR_UUID);
                    BluetoothGattCharacteristic txCharacteristic = service.getCharacteristic(YamahaProtocol.NUS_TX_CHAR_UUID);

                    if (txCharacteristic != null) {
                        gatt.setCharacteristicNotification(txCharacteristic, true);
                        BluetoothGattDescriptor descriptor = txCharacteristic.getDescriptor(YamahaProtocol.CLIENT_CONFIG_DESCRIPTOR);
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(descriptor);
                        }
                    }

                    // Command CCU to stream telemetry
                    sendStartStreamingCommand();

                    // Start zero-click ride tracking!
                    startRideTracking();

                    // Start periodic meter telemetry sync
                    startPeriodicSync();
                    broadcastStatus("Connected & Syncing with Meter");
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            handleCharacteristicUpdate(characteristic.getValue());
        }

        // Android 13+ (API 33+)
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleCharacteristicUpdate(value);
        }
    };

    private void handleCharacteristicUpdate(byte[] data) {
        if (data == null || data.length == 0) return;
        boolean updated = YamahaProtocol.parseIncomingPacket(data, latestTelemetry);
        if (updated) {
            if (latestTelemetry.odometerKm > 0) {
                maintenanceManager.updateOdometer(latestTelemetry.odometerKm);
            }
            checkAndRecordRidePoint();
            broadcastTelemetry();
        }
    }

    private void sendStartStreamingCommand() {
        if (bluetoothGatt == null || rxCharacteristic == null) return;
        byte[] packet = YamahaProtocol.buildStartStreamingPacket(syncCounter++);
        rxCharacteristic.setValue(packet);
        rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        bluetoothGatt.writeCharacteristic(rxCharacteristic);
    }

    private void startRideTracking() {
        currentRideId = rideDb.startNewRide(System.currentTimeMillis());
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
            boolean saved = rideDb.finalizeRide(currentRideId, System.currentTimeMillis());
            currentRideId = -1;
            if (saved) {
                sendBroadcast(new Intent(BROADCAST_RIDE_FINISHED));
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
                    syncHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            sendNotificationSync();
                        }
                    }, 100);

                    syncHandler.postDelayed(this, 1000);
                }
            }
        };
        syncHandler.post(syncRunnable);
    }

    private void stopPeriodicSync() {
        if (syncRunnable != null) {
            syncHandler.removeCallbacks(syncRunnable);
            syncRunnable = null;
        }
    }

    private void sendClockAndBatterySync() {
        if (bluetoothGatt == null || rxCharacteristic == null) return;

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

        rxCharacteristic.setValue(packet);
        rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        bluetoothGatt.writeCharacteristic(rxCharacteristic);
    }

    private void sendNotificationSync() {
        if (bluetoothGatt == null || rxCharacteristic == null) return;

        byte[] packet = YamahaProtocol.buildNotificationPacket(
                isIncomingCall,
                isMissedCall,
                hasUnreadMessage,
                false,
                syncCounter++
        );

        rxCharacteristic.setValue(packet);
        rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        bluetoothGatt.writeCharacteristic(rxCharacteristic);
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
        stopScan();
        disconnectGatt();
        unregisterReceiver(batteryReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
