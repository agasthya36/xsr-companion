package com.xsr.companion.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.xsr.companion.R;
import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.maintenance.FuelManager;
import com.xsr.companion.service.XsrBleService;
import com.xsr.companion.settings.SettingsManager;
import com.xsr.companion.settings.Units;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsFragment extends BaseFragment {

    private SettingsManager settings;
    private RideDatabaseHelper db;
    private FuelManager fuelManager;
    private SharedPreferences servicePrefs;

    private MaterialSwitch swAutoConnect, swBackgroundConnect, swOverspeed, swCoolant, swLowFuel, swService, swCold;
    private TextView tvPasskey, tvBackgroundLocation;
    private MaterialButtonToggleGroup gpsModeGroup;
    private MaterialButton btnToggleConnection, btnTankCapacity, btnReserve,
            btnOverspeedLimit, btnCoolantLimit;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        settings = SettingsManager.get(requireContext());
        db = RideDatabaseHelper.getInstance(requireContext());
        fuelManager = new FuelManager(requireContext());
        servicePrefs = requireContext().getSharedPreferences(
                XsrBleService.PREFS_NAME, Context.MODE_PRIVATE);

        swAutoConnect = view.findViewById(R.id.swAutoConnect);
        swBackgroundConnect = view.findViewById(R.id.swBackgroundConnect);
        swOverspeed = view.findViewById(R.id.swOverspeed);
        swCoolant = view.findViewById(R.id.swCoolant);
        swLowFuel = view.findViewById(R.id.swLowFuel);
        swService = view.findViewById(R.id.swService);
        swCold = view.findViewById(R.id.swCold);

        tvPasskey = view.findViewById(R.id.tvPasskey);
        tvBackgroundLocation = view.findViewById(R.id.tvBackgroundLocation);
        gpsModeGroup = view.findViewById(R.id.gpsModeGroup);
        btnToggleConnection = view.findViewById(R.id.btnToggleConnection);
        btnTankCapacity = view.findViewById(R.id.btnTankCapacity);
        btnReserve = view.findViewById(R.id.btnReserve);
        btnOverspeedLimit = view.findViewById(R.id.btnOverspeedLimit);
        btnCoolantLimit = view.findViewById(R.id.btnCoolantLimit);

        bindToggles();
        bindConnection(view);
        bindRiding(view);
        bindUnits(view);
        bindFuel();
        bindAlerts();
        bindDataActions(view);

        refreshValues();
    }

    @Override
    protected void onTick() {
        updateConnectionState();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshValues();
        updateConnectionState();
    }

    private void bindToggles() {
        swAutoConnect.setChecked(settings.isAutoConnect());
        swBackgroundConnect.setChecked(settings.isBackgroundConnect());
        swOverspeed.setChecked(settings.alertOverspeed());
        swCoolant.setChecked(settings.alertCoolant());
        swLowFuel.setChecked(settings.alertLowFuel());
        swService.setChecked(settings.alertService());
        swCold.setChecked(settings.alertColdEngine());

        swAutoConnect.setOnCheckedChangeListener((v, checked) -> settings.setAutoConnect(checked));
        swBackgroundConnect.setOnCheckedChangeListener((v, checked) -> {
            settings.setBackgroundConnect(checked);
            Intent intent = new Intent(requireContext(), XsrBleService.class);
            if (checked) {
                intent.setAction(XsrBleService.ACTION_BACKGROUND_CONNECT);
                try {
                    ContextCompat.startForegroundService(requireContext(), intent);
                } catch (Exception e) {
                    Toast.makeText(requireContext(),
                            "Could not start background connection", Toast.LENGTH_SHORT).show();
                }
                Toast.makeText(requireContext(),
                        "Background connection on — Android will show an ongoing notification",
                        Toast.LENGTH_LONG).show();
            } else {
                intent.setAction(XsrBleService.ACTION_DISABLE_BACKGROUND);
                try {
                    requireContext().startService(intent);
                } catch (Exception ignored) {
                }
            }
        });
        swOverspeed.setOnCheckedChangeListener((v, checked) -> settings.setAlertOverspeed(checked));
        swCoolant.setOnCheckedChangeListener((v, checked) -> settings.setAlertCoolant(checked));
        swLowFuel.setOnCheckedChangeListener((v, checked) -> settings.setAlertLowFuel(checked));
        swService.setOnCheckedChangeListener((v, checked) -> settings.setAlertService(checked));
        swCold.setOnCheckedChangeListener((v, checked) -> settings.setAlertColdEngine(checked));
    }

    private void bindConnection(View view) {
        btnToggleConnection.setOnClickListener(v -> {
            if (host == null) return;
            if (XsrBleService.sIsConnected || XsrBleService.sSessionActive) host.disconnect();
            else host.connect();
            v.postDelayed(this::updateConnectionState, 300L);
        });

        view.findViewById(R.id.btnEditPasskey).setOnClickListener(v -> editPasskey());
    }

    private void bindRiding(View view) {
        gpsModeGroup.check(settings.isGpsBatterySaver() ? R.id.btnGpsSaver : R.id.btnGpsDetailed);
        gpsModeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean saver = checkedId == R.id.btnGpsSaver;
            if (saver == settings.isGpsBatterySaver()) return;
            settings.setGpsBatterySaver(saver);
            try {
                requireContext().startService(new Intent(requireContext(), XsrBleService.class)
                        .setAction(XsrBleService.ACTION_REFRESH_GPS));
            } catch (Exception ignored) {
            }
        });

        view.findViewById(R.id.btnBackgroundLocation).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", requireContext().getPackageName(), null)));
            } catch (Exception ignored) {
            }
        });
    }

    private void bindUnits(View view) {
        MaterialButtonToggleGroup group = view.findViewById(R.id.unitsGroup);
        group.check(settings.isImperial() ? R.id.btnImperial : R.id.btnMetric);
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean imperial = checkedId == R.id.btnImperial;
            if (imperial == settings.isImperial()) return;
            settings.setImperial(imperial);
            refreshValues();
        });
    }

    private void bindFuel() {
        btnTankCapacity.setOnClickListener(v -> promptDecimal("Tank capacity",
                settings.isImperial() ? "Tank capacity in US gallons" : "Tank capacity in litres",
                settings.isImperial()
                        ? settings.getTankCapacityL() * Units.L_TO_US_GAL
                        : settings.getTankCapacityL(),
                value -> {
                    float litres = settings.isImperial()
                            ? (float) (value / Units.L_TO_US_GAL) : (float) value;
                    settings.setTankCapacityL(litres);
                    fuelManager.setRemainingLitres(fuelManager.getRemainingLitres());
                    XsrBleService.sFuelRemainingL = fuelManager.getRemainingLitres();
                    XsrBleService.sEstimatedRangeKm = fuelManager.getEstimatedRangeKm(
                            fuelManager.getBestMileageKmL(XsrBleService.sTripAvgMileage));
                    refreshValues();
                }));

        btnReserve.setOnClickListener(v -> promptDecimal("Reserve warning",
                settings.isImperial() ? "Reserve in US gallons" : "Reserve in litres",
                settings.isImperial()
                        ? settings.getReserveL() * Units.L_TO_US_GAL
                        : settings.getReserveL(),
                value -> {
                    float litres = settings.isImperial()
                            ? (float) (value / Units.L_TO_US_GAL) : (float) value;
                    settings.setReserveL(litres);
                    refreshValues();
                }));
    }

    private void bindAlerts() {
        btnOverspeedLimit.setOnClickListener(v -> promptInteger("Overspeed limit",
                settings.isImperial() ? "mph" : "km/h",
                settings.isImperial()
                        ? (int) Math.round(settings.overspeedKmh() * Units.KMH_TO_MPH)
                        : settings.overspeedKmh(),
                value -> {
                    int kmh = settings.isImperial()
                            ? (int) Math.round(value / Units.KMH_TO_MPH) : value;
                    settings.setOverspeedKmh(kmh);
                    refreshValues();
                }));

        btnCoolantLimit.setOnClickListener(v -> promptInteger("Coolant limit",
                settings.isImperial() ? "°F" : "°C",
                settings.isImperial()
                        ? (int) Math.round(settings.coolantLimitC() * 9.0 / 5.0 + 32.0)
                        : settings.coolantLimitC(),
                value -> {
                    int celsius = settings.isImperial()
                            ? (int) Math.round((value - 32) * 5.0 / 9.0) : value;
                    settings.setCoolantLimitC(celsius);
                    refreshValues();
                }));
    }

    private void bindDataActions(View view) {
        view.findViewById(R.id.btnExportData).setOnClickListener(v -> exportEverything());

        view.findViewById(R.id.btnClearRides).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Delete all rides?")
                        .setMessage("Every ride, GPS point and GPX file on this phone is removed. "
                                + "Fuel and service history are kept.")
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.action_delete, (d, w) -> {
                            io.execute(() -> {
                                db.clearAllRideData();
                                if (!isAdded()) return;
                                requireActivity().runOnUiThread(() ->
                                        Toast.makeText(requireContext(), "Ride history cleared",
                                                Toast.LENGTH_SHORT).show());
                            });
                        })
                        .show());

        view.findViewById(R.id.btnClearDtc).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Clear DTC history?")
                        .setMessage("Only the history stored on this phone is removed. "
                                + "Active ECU codes are not cleared.")
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton("Clear", (d, w) -> {
                            db.clearDtcLog();
                            Toast.makeText(requireContext(), "DTC history cleared",
                                    Toast.LENGTH_SHORT).show();
                        })
                        .show());

        String version = "Version 2.0";
        try {
            version = "Version " + requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        ((TextView) view.findViewById(R.id.tvVersion)).setText(version);
    }

    private void refreshValues() {
        if (!isAdded()) return;

        btnTankCapacity.setText(settings.isImperial()
                ? String.format(Locale.getDefault(), "%.1f gal",
                settings.getTankCapacityL() * Units.L_TO_US_GAL)
                : String.format(Locale.getDefault(), "%.1f L", settings.getTankCapacityL()));

        btnReserve.setText(settings.isImperial()
                ? String.format(Locale.getDefault(), "%.1f gal",
                settings.getReserveL() * Units.L_TO_US_GAL)
                : String.format(Locale.getDefault(), "%.1f L", settings.getReserveL()));

        btnOverspeedLimit.setText(settings.isImperial()
                ? String.format(Locale.getDefault(), "%d mph",
                Math.round(settings.overspeedKmh() * Units.KMH_TO_MPH))
                : String.format(Locale.getDefault(), "%d km/h", settings.overspeedKmh()));

        btnCoolantLimit.setText(settings.isImperial()
                ? String.format(Locale.getDefault(), "%d °F",
                Math.round(settings.coolantLimitC() * 9.0 / 5.0 + 32.0))
                : String.format(Locale.getDefault(), "%d °C", settings.coolantLimitC()));

        boolean backgroundLocation = ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        tvBackgroundLocation.setText(backgroundLocation
                ? "Allowed — background rides record GPS"
                : "Allow all the time to record GPS on background rides");

        updateConnectionState();
    }

    private void updateConnectionState() {
        if (!isAdded() || getView() == null) return;
        boolean linked = XsrBleService.sIsConnected || XsrBleService.sSessionActive;
        btnToggleConnection.setText(linked
                ? R.string.action_disconnect : R.string.action_connect);

        String passkey = servicePrefs.getString(XsrBleService.PREF_PASSKEY, "");
        tvPasskey.setText(passkey == null || passkey.trim().isEmpty()
                ? "Not set — learned automatically on first pairing"
                : "••••••••");
    }

    private void editPasskey() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("6-digit passkey, VIN, or CCU QR payload");
        String current = servicePrefs.getString(XsrBleService.PREF_PASSKEY, "");
        input.setText(current);
        input.setSelectAllOnFocus(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("CCU passkey")
                .setMessage("Enter the 6-character passkey from the CCU QR sticker, "
                        + "the full chassis/VIN number, or the 24-character QR payload. "
                        + "Leave empty to try the CCU ID automatically.")
                .setView(input)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    servicePrefs.edit()
                            .putString(XsrBleService.PREF_PASSKEY,
                                    input.getText().toString().trim())
                            .apply();
                    XsrBleService service = service();
                    if (service != null) service.retryAuthentication();
                    updateConnectionState();
                })
                .show();
    }

    private void exportEverything() {
        Toast.makeText(requireContext(), "Preparing export…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            File zip = db.exportEverything();
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (zip == null) {
                    Toast.makeText(requireContext(), "Export failed", Toast.LENGTH_SHORT).show();
                } else {
                    ExportUtils.shareFile(requireContext(), zip, "application/zip",
                            "XSR Companion export");
                }
            });
        });
    }

    private interface DecimalCallback {
        void onValue(double value);
    }

    private interface IntegerCallback {
        void onValue(int value);
    }

    private void promptDecimal(String title, String hint, double current,
                               DecimalCallback callback) {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(hint);
        input.setText(String.format(Locale.US, "%.2f", current));
        input.setSelectAllOnFocus(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setView(input)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    double value;
                    try {
                        value = Double.parseDouble(input.getText().toString().trim());
                    } catch (Exception e) {
                        return;
                    }
                    if (value <= 0) return;
                    callback.onValue(value);
                })
                .show();
    }

    private void promptInteger(String title, String unit, int current,
                               IntegerCallback callback) {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(unit);
        input.setText(String.valueOf(current));
        input.setSelectAllOnFocus(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title + " (" + unit + ")")
                .setView(input)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    int value;
                    try {
                        value = Integer.parseInt(input.getText().toString().trim());
                    } catch (Exception e) {
                        return;
                    }
                    if (value <= 0) return;
                    callback.onValue(value);
                })
                .show();
    }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
