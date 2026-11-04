package com.xsr.companion.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.xsr.companion.R;
import com.xsr.companion.service.XsrBleService;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_TAB = "extra_tab";
    public static final int TAB_DASHBOARD = 0;
    public static final int TAB_RIDES = 1;
    public static final int TAB_MAINTENANCE = 2;
    public static final int TAB_DIAGNOSTICS = 3;
    public static final int TAB_SETTINGS = 4;

    private static final String[] TAGS = {
            "tab_dashboard", "tab_rides", "tab_maintenance", "tab_diagnostics", "tab_settings"
    };

    private static final int PERMISSION_REQ_CODE = 155;
    private static final String STATE_TAB = "state_selected_tab";

    private static final String[] CORE_PERMISSIONS = {
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    };

    private static final String[] OPTIONAL_PERMISSIONS = {
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_PHONE_STATE
    };

    private BottomNavigationView bottomNav;
    private XsrBleService bleService;
    private boolean isBound = false;
    private int selectedTab = TAB_DASHBOARD;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service instanceof XsrBleService.LocalBinder) {
                bleService = ((XsrBleService.LocalBinder) service).getService();
                isBound = true;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bleService = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        applyInsets();
        bottomNav = findViewById(R.id.bottomNav);

        // Decide the tab before wiring the listener so restoring state does not
        // briefly select (and create) the dashboard.
        if (savedInstanceState == null) {
            selectedTab = clampTab(getIntent().getIntExtra(EXTRA_TAB, TAB_DASHBOARD));
        } else {
            selectedTab = clampTab(savedInstanceState.getInt(STATE_TAB, TAB_DASHBOARD));
        }
        bottomNav.setSelectedItemId(menuIdFor(selectedTab));

        bottomNav.setOnItemSelectedListener(item -> {
            showTab(indexFor(item.getItemId()));
            return true;
        });
        showTab(selectedTab);

        if (hasBluetoothPermission()) startBleService();
        if (!hasCorePermissions()) {
            requestAppPermissions();
        } else {
            connectIfAuto();
        }

        handleRideExtra(getIntent());
    }

    private void applyInsets() {
        View root = findViewById(R.id.root);
        View nav = findViewById(R.id.bottomNav);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            nav.setPadding(0, 0, 0, bars.bottom);
            return windowInsets;
        });
    }

    private void handleRideExtra(Intent intent) {
        if (intent == null) return;
        long rideId = intent.getLongExtra(XsrBleService.EXTRA_RIDE_ID, -1);
        if (rideId > 0) {
            selectTab(TAB_RIDES);
            // The tab transaction is committed asynchronously; resolve it
            // before looking the fragment up.
            getSupportFragmentManager().executePendingTransactions();
            Fragment f = getSupportFragmentManager().findFragmentByTag(TAGS[TAB_RIDES]);
            if (f instanceof RidesFragment) {
                ((RidesFragment) f).openRide(rideId);
            }
            intent.removeExtra(XsrBleService.EXTRA_RIDE_ID);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int tab = intent.getIntExtra(EXTRA_TAB, -1);
        if (tab >= 0) selectTab(tab);
        handleRideExtra(intent);
    }

    private int clampTab(int tab) {
        return Math.max(TAB_DASHBOARD, Math.min(TAB_SETTINGS, tab));
    }

    private int menuIdFor(int tab) {
        switch (tab) {
            case TAB_RIDES: return R.id.nav_rides;
            case TAB_MAINTENANCE: return R.id.nav_maintenance;
            case TAB_DIAGNOSTICS: return R.id.nav_diagnostics;
            case TAB_SETTINGS: return R.id.nav_settings;
            default: return R.id.nav_dashboard;
        }
    }

    private int indexFor(int menuId) {
        if (menuId == R.id.nav_rides) return TAB_RIDES;
        if (menuId == R.id.nav_maintenance) return TAB_MAINTENANCE;
        if (menuId == R.id.nav_diagnostics) return TAB_DIAGNOSTICS;
        if (menuId == R.id.nav_settings) return TAB_SETTINGS;
        return TAB_DASHBOARD;
    }

    public void selectTab(int tab) {
        if (bottomNav == null) return;
        bottomNav.setSelectedItemId(menuIdFor(clampTab(tab)));
    }

    private void showTab(int index) {
        selectedTab = clampTab(index);
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();

        // Hidden screens must not stay RESUMED: otherwise their 2 s ticker
        // keeps running in the background and burns battery. Drop them to
        // STARTED so onPause stops the handler, and restore only the visible
        // screen to RESUMED.
        for (int i = 0; i < TAGS.length; i++) {
            Fragment existing = fm.findFragmentByTag(TAGS[i]);
            if (existing == null) continue;
            if (i == selectedTab) {
                tx.setMaxLifecycle(existing, Lifecycle.State.RESUMED);
                tx.show(existing);
            } else {
                tx.setMaxLifecycle(existing, Lifecycle.State.STARTED);
                tx.hide(existing);
            }
        }

        String tag = TAGS[selectedTab];
        Fragment target = fm.findFragmentByTag(tag);
        if (target == null) {
            target = createFragment(selectedTab);
            tx.add(R.id.fragmentContainer, target, tag);
            tx.setMaxLifecycle(target, Lifecycle.State.RESUMED);
        }
        tx.commitAllowingStateLoss();
    }

    private Fragment createFragment(int index) {
        switch (index) {
            case TAB_RIDES: return new RidesFragment();
            case TAB_MAINTENANCE: return new MaintenanceFragment();
            case TAB_DIAGNOSTICS: return new DiagnosticsFragment();
            case TAB_SETTINGS: return new SettingsFragment();
            default: return new DashboardFragment();
        }
    }

    // -----------------------------------------------------------------
    // Service lifecycle
    // -----------------------------------------------------------------

    private void startBleService() {
        if (!hasBluetoothPermission()) return;
        try {
            ContextCompat.startForegroundService(this, new Intent(this, XsrBleService.class));
        } catch (Exception ignored) {
            // The system may refuse FGS start in restricted situations; the
            // dashboard surface will show a permission/connection hint.
        }
    }

    private void connectIfAuto() {
        if (!com.xsr.companion.settings.SettingsManager.get(this).isAutoConnect()) return;
        Intent intent = new Intent(this, XsrBleService.class);
        intent.setAction(XsrBleService.ACTION_START_SCAN);
        intent.putExtra(XsrBleService.EXTRA_AUTO_CONNECT, true);
        try {
            ContextCompat.startForegroundService(this, intent);
        } catch (Exception ignored) {
        }
    }

    public void connect() {
        if (!hasCorePermissions()) {
            requestAppPermissions();
            return;
        }
        startBleService();
        Intent intent = new Intent(this, XsrBleService.class);
        intent.setAction(XsrBleService.ACTION_START_SCAN);
        try {
            ContextCompat.startForegroundService(this, intent);
        } catch (Exception ignored) {
        }
    }

    public void disconnect() {
        Intent intent = new Intent(this, XsrBleService.class);
        intent.setAction(XsrBleService.ACTION_DISCONNECT);
        try {
            startService(intent);
        } catch (Exception ignored) {
        }
    }

    public XsrBleService getBleService() {
        return bleService;
    }

    public boolean isServiceBound() {
        return isBound;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (hasBluetoothPermission()) {
            bindService(new Intent(this, XsrBleService.class), serviceConnection,
                    Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, selectedTab);
    }

    @Override
    protected void onStop() {
        if (isBound) {
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
        }
        isBound = false;
        bleService = null;
        super.onStop();
    }

    // -----------------------------------------------------------------
    // Permissions
    // -----------------------------------------------------------------

    public boolean hasBluetoothPermission() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasCorePermissions() {
        for (String permission : CORE_PERMISSIONS) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    public void requestAppPermissions() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String permission : CORE_PERMISSIONS) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        }
        for (String permission : OPTIONAL_PERMISSIONS) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        }
        if (missing.isEmpty()) return;
        requestPermissions(missing.toArray(new String[0]), PERMISSION_REQ_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQ_CODE) return;

        if (hasBluetoothPermission()) {
            startBleService();
            if (hasCorePermissions()) {
                Intent intent = new Intent(this, XsrBleService.class);
                intent.setAction(XsrBleService.ACTION_START_SCAN);
                intent.putExtra(XsrBleService.EXTRA_AUTO_CONNECT, true);
                try {
                    ContextCompat.startForegroundService(this, intent);
                } catch (Exception ignored) {
                }
            }
        }
        // Let visible fragments react (permission banner, phone relay).
        for (String tag : TAGS) {
            Fragment f = getSupportFragmentManager().findFragmentByTag(tag);
            if (f instanceof PermissionAware && f.isVisible()) {
                ((PermissionAware) f).onPermissionsChanged();
            }
        }
        if (hasCorePermissions() && checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
            Intent intent = new Intent(this, XsrBleService.class);
            intent.setAction(XsrBleService.ACTION_REFRESH_PHONE);
            try { startService(intent); } catch (Exception ignored) {}
        }
    }

    public interface PermissionAware {
        void onPermissionsChanged();
    }
}
