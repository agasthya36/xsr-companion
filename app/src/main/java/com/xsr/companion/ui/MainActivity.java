package com.xsr.companion.ui;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.data.RideFileProvider;
import com.xsr.companion.diagnostics.DtcManager;
import com.xsr.companion.maintenance.MaintenanceManager;
import com.xsr.companion.service.XsrBleService;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQ_CODE = 155;

    private TextView tvConnectionStatus;
    private TextView tvDeviceName;
    private Button btnScanConnect;
    private Button btnDisconnect;

    private TextView tvLiveSpeed;
    private TextView tvLiveRpm;
    private TextView tvLiveCoolant;
    private TextView tvLiveThrottle;
    private TextView tvLiveOdometer;

    private TextView tvDtcStatus;
    private TextView tvDtcDetails;

    private TextView tvChainLubeStatus;
    private Button btnResetChainLube;
    private TextView tvEngineOilStatus;
    private Button btnResetEngineOil;

    private TextView tvLastRideStats;
    private Button btnExportLastGpx;

    private TextView tvParkedCoordinates;
    private TextView tvParkedTime;
    private Button btnNavigateToBike;

    private TextView tvBatteryPercent;
    private View layoutPermissions;
    private Button btnGrantPermissions;

    private SharedPreferences prefs;
    private MaintenanceManager maintenanceManager;
    private RideDatabaseHelper rideDb;
    private RideDatabaseHelper.RideSummary latestRide = null;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (XsrBleService.BROADCAST_STATUS.equals(intent.getAction())) {
                String status = intent.getStringExtra(XsrBleService.EXTRA_STATUS_TEXT);
                boolean isConnected = intent.getBooleanExtra(XsrBleService.EXTRA_IS_CONNECTED, false);
                int battery = intent.getIntExtra(XsrBleService.EXTRA_BATTERY_LEVEL, -1);

                updateConnectionUi(status, isConnected);
                if (battery >= 0) {
                    tvBatteryPercent.setText(battery + "%");
                }
            } else if (XsrBleService.BROADCAST_TELEMETRY.equals(intent.getAction())) {
                int speed = intent.getIntExtra(XsrBleService.EXTRA_SPEED, 0);
                int rpm = intent.getIntExtra(XsrBleService.EXTRA_RPM, 0);
                double throttle = intent.getDoubleExtra(XsrBleService.EXTRA_THROTTLE, 0.0);
                int coolant = intent.getIntExtra(XsrBleService.EXTRA_COOLANT, 0);
                double odo = intent.getDoubleExtra(XsrBleService.EXTRA_ODO, 0.0);
                boolean warning = intent.getBooleanExtra(XsrBleService.EXTRA_WARNING, false);
                int dtcCode = intent.getIntExtra(XsrBleService.EXTRA_DTC_CODE, 0);

                updateTelemetryUi(speed, rpm, throttle, coolant, odo, warning, dtcCode);
            } else if (XsrBleService.BROADCAST_RIDE_FINISHED.equals(intent.getAction())) {
                updateRecentRidesUi();
                updateParkedLocationUi();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.xsr.companion.R.layout.activity_main);

        prefs = getSharedPreferences(XsrBleService.PREFS_NAME, MODE_PRIVATE);
        maintenanceManager = new MaintenanceManager(this);
        rideDb = new RideDatabaseHelper(this);

        initViews();
        setupListeners();
        updateParkedLocationUi();
        updateMaintenanceUi();
        updateRecentRidesUi();

        // Start background service
        Intent serviceIntent = new Intent(this, XsrBleService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void initViews() {
        tvConnectionStatus = findViewById(com.xsr.companion.R.id.tvConnectionStatus);
        tvDeviceName = findViewById(com.xsr.companion.R.id.tvDeviceName);
        btnScanConnect = findViewById(com.xsr.companion.R.id.btnScanConnect);
        btnDisconnect = findViewById(com.xsr.companion.R.id.btnDisconnect);

        tvLiveSpeed = findViewById(com.xsr.companion.R.id.tvLiveSpeed);
        tvLiveRpm = findViewById(com.xsr.companion.R.id.tvLiveRpm);
        tvLiveCoolant = findViewById(com.xsr.companion.R.id.tvLiveCoolant);
        tvLiveThrottle = findViewById(com.xsr.companion.R.id.tvLiveThrottle);
        tvLiveOdometer = findViewById(com.xsr.companion.R.id.tvLiveOdometer);

        tvDtcStatus = findViewById(com.xsr.companion.R.id.tvDtcStatus);
        tvDtcDetails = findViewById(com.xsr.companion.R.id.tvDtcDetails);

        tvChainLubeStatus = findViewById(com.xsr.companion.R.id.tvChainLubeStatus);
        btnResetChainLube = findViewById(com.xsr.companion.R.id.btnResetChainLube);
        tvEngineOilStatus = findViewById(com.xsr.companion.R.id.tvEngineOilStatus);
        btnResetEngineOil = findViewById(com.xsr.companion.R.id.btnResetEngineOil);

        tvLastRideStats = findViewById(com.xsr.companion.R.id.tvLastRideStats);
        btnExportLastGpx = findViewById(com.xsr.companion.R.id.btnExportLastGpx);

        tvParkedCoordinates = findViewById(com.xsr.companion.R.id.tvParkedCoordinates);
        tvParkedTime = findViewById(com.xsr.companion.R.id.tvParkedTime);
        btnNavigateToBike = findViewById(com.xsr.companion.R.id.btnNavigateToBike);

        tvBatteryPercent = findViewById(com.xsr.companion.R.id.tvBatteryPercent);
        layoutPermissions = findViewById(com.xsr.companion.R.id.layoutPermissions);
        btnGrantPermissions = findViewById(com.xsr.companion.R.id.btnGrantPermissions);
    }

    private void setupListeners() {
        btnScanConnect.setOnClickListener(v -> {
            Intent intent = new Intent(this, XsrBleService.class);
            intent.setAction(XsrBleService.ACTION_START_SCAN);
            startService(intent);
        });

        btnDisconnect.setOnClickListener(v -> {
            Intent intent = new Intent(this, XsrBleService.class);
            intent.setAction(XsrBleService.ACTION_DISCONNECT);
            startService(intent);
        });

        btnResetChainLube.setOnClickListener(v -> {
            maintenanceManager.markServiced("chain_lube");
            updateMaintenanceUi();
            Toast.makeText(this, "Chain maintenance logged!", Toast.LENGTH_SHORT).show();
        });

        btnResetEngineOil.setOnClickListener(v -> {
            maintenanceManager.markServiced("engine_oil");
            updateMaintenanceUi();
            Toast.makeText(this, "Engine oil replacement logged!", Toast.LENGTH_SHORT).show();
        });

        btnExportLastGpx.setOnClickListener(v -> {
            exportLastRideGpx();
        });

        btnNavigateToBike.setOnClickListener(v -> {
            float lat = prefs.getFloat(XsrBleService.PREF_PARKED_LAT, 0f);
            float lon = prefs.getFloat(XsrBleService.PREF_PARKED_LON, 0f);
            if (lat != 0f && lon != 0f) {
                Uri gmmIntentUri = Uri.parse("google.navigation:q=" + lat + "," + lon + "&mode=w");
                Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
                mapIntent.setPackage("com.google.android.apps.maps");
                if (mapIntent.resolveActivity(getPackageManager()) != null) {
                    startActivity(mapIntent);
                } else {
                    Intent genericMap = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon + "(Yamaha XSR 155)"));
                    startActivity(genericMap);
                }
            } else {
                Toast.makeText(this, "No parked coordinates recorded yet", Toast.LENGTH_SHORT).show();
            }
        });

        btnGrantPermissions.setOnClickListener(v -> checkAndRequestPermissions());
    }

    private void updateConnectionUi(String status, boolean isConnected) {
        tvConnectionStatus.setText(status != null ? status : (isConnected ? "Connected" : "Disconnected"));
        int color = isConnected ? 0xFF00E676 : 0xFFFF5252;
        tvConnectionStatus.setTextColor(color);

        String savedName = prefs.getString(XsrBleService.PREF_LAST_NAME, "No CCU Paired");
        tvDeviceName.setText(savedName);
    }

    private void updateTelemetryUi(int speed, int rpm, double throttle, int coolant, double odo, boolean warning, int dtcCode) {
        tvLiveSpeed.setText(speed + " km/h");
        tvLiveRpm.setText(rpm + " RPM");
        tvLiveCoolant.setText(coolant > 0 ? (coolant + " °C") : "-- °C");
        tvLiveThrottle.setText(String.format(Locale.getDefault(), "%.0f%%", throttle));
        if (odo > 0) {
            tvLiveOdometer.setText(String.format(Locale.getDefault(), "%.1f km", odo));
            updateMaintenanceUi();
        }

        // DTC Engine Diagnostics
        if (warning || dtcCode > 0) {
            DtcManager.DtcInfo info = DtcManager.lookup(dtcCode);
            tvDtcStatus.setText("⚠ Check Engine Active: DTC " + info.code + " (" + info.title + ")");
            tvDtcStatus.setTextColor(0xFFFF5252);
            tvDtcDetails.setText("Symptom: " + info.symptom + "\nAction: " + info.action);
        } else {
            tvDtcStatus.setText("Engine Diagnostics: All Systems Normal");
            tvDtcStatus.setTextColor(0xFF00E676);
            tvDtcDetails.setText("No active fault codes. ECU operating within normal parameters.");
        }
    }

    private void updateMaintenanceUi() {
        double currentOdo = maintenanceManager.getCurrentOdometer();
        List<MaintenanceManager.ServiceItem> items = maintenanceManager.getItems();

        for (MaintenanceManager.ServiceItem item : items) {
            if ("chain_lube".equals(item.id)) {
                double rem = item.getRemainingKm(currentOdo);
                if (rem <= 0) {
                    tvChainLubeStatus.setText(String.format(Locale.getDefault(), "OVERDUE by %.0f km!", Math.abs(rem)));
                    tvChainLubeStatus.setTextColor(0xFFFF5252);
                } else {
                    tvChainLubeStatus.setText(String.format(Locale.getDefault(), "%.0f km remaining (%.0f km interval)", rem, (double) item.intervalKm));
                    tvChainLubeStatus.setTextColor(rem <= 50 ? 0xFFFFD600 : 0xFF00E5FF);
                }
            } else if ("engine_oil".equals(item.id)) {
                double rem = item.getRemainingKm(currentOdo);
                if (rem <= 0) {
                    tvEngineOilStatus.setText(String.format(Locale.getDefault(), "OVERDUE by %.0f km!", Math.abs(rem)));
                    tvEngineOilStatus.setTextColor(0xFFFF5252);
                } else {
                    tvEngineOilStatus.setText(String.format(Locale.getDefault(), "%.0f km remaining (%.0f km interval)", rem, (double) item.intervalKm));
                    tvEngineOilStatus.setTextColor(rem <= 200 ? 0xFFFFD600 : 0xFF00E676);
                }
            }
        }
    }

    private void updateRecentRidesUi() {
        List<RideDatabaseHelper.RideSummary> rides = rideDb.getRecentRides(1);
        if (!rides.isEmpty()) {
            latestRide = rides.get(0);
            String summary = String.format(Locale.getDefault(),
                    "%s\nDistance: %.2f km • Duration: %s\nTop Speed: %d km/h • Avg: %.1f km/h • Max Revs: %d RPM",
                    latestRide.getFormattedDate(),
                    latestRide.distanceKm,
                    latestRide.getFormattedDuration(),
                    latestRide.topSpeedKmh,
                    latestRide.avgSpeedKmh,
                    latestRide.maxRpm
            );
            tvLastRideStats.setText(summary);
            btnExportLastGpx.setVisibility(latestRide.gpxPath != null ? View.VISIBLE : View.GONE);
        } else {
            latestRide = null;
            tvLastRideStats.setText("No rides logged yet. Turn on motorcycle and ride to start auto-recording!");
            btnExportLastGpx.setVisibility(View.GONE);
        }
    }

    private void exportLastRideGpx() {
        if (latestRide == null || latestRide.gpxPath == null) {
            Toast.makeText(this, "No GPX file available for this ride", Toast.LENGTH_SHORT).show();
            return;
        }

        File gpxFile = new File(latestRide.gpxPath);
        if (!gpxFile.exists()) {
            Toast.makeText(this, "GPX file missing: " + gpxFile.getName(), Toast.LENGTH_SHORT).show();
            return;
        }

        Uri contentUri = RideFileProvider.getUriForFile(gpxFile);
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/gpx+xml");
        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Yamaha XSR 155 Ride - " + latestRide.getFormattedDate());
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivity(Intent.createChooser(shareIntent, "Export GPX to Strava, Drive, or Files"));
    }

    private void updateParkedLocationUi() {
        float lat = prefs.getFloat(XsrBleService.PREF_PARKED_LAT, 0f);
        float lon = prefs.getFloat(XsrBleService.PREF_PARKED_LON, 0f);
        long time = prefs.getLong(XsrBleService.PREF_PARKED_TIME, 0L);

        if (lat != 0f && lon != 0f) {
            tvParkedCoordinates.setText(String.format(Locale.getDefault(), "Lat: %.6f, Lon: %.6f", lat, lon));
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy • HH:mm:ss", Locale.getDefault());
            tvParkedTime.setText("Recorded on key-off: " + sdf.format(new Date(time)));
            btnNavigateToBike.setEnabled(true);
        } else {
            tvParkedCoordinates.setText("No recorded parking spot");
            tvParkedTime.setText("GPS saved strictly on disconnect");
            btnNavigateToBike.setEnabled(false);
        }
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] perms = {
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.POST_NOTIFICATIONS
            };
            requestPermissions(perms, PERMISSION_REQ_CODE);
        } else {
            String[] perms = {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_PHONE_STATE
            };
            requestPermissions(perms, PERMISSION_REQ_CODE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(XsrBleService.BROADCAST_STATUS);
        filter.addAction(XsrBleService.BROADCAST_TELEMETRY);
        filter.addAction(XsrBleService.BROADCAST_RIDE_FINISHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }

        updateMaintenanceUi();
        updateRecentRidesUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(statusReceiver);
    }
}
