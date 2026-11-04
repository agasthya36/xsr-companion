package com.xsr.companion.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.xsr.companion.R;
import com.xsr.companion.maintenance.MaintenanceManager;
import com.xsr.companion.protocol.BikeTelemetry;
import com.xsr.companion.service.XsrBleService;
import com.xsr.companion.settings.SettingsManager;
import com.xsr.companion.settings.Units;

import java.util.List;
import java.util.Locale;

public class DashboardFragment extends BaseFragment implements MainActivity.PermissionAware {

    private TextView tvStatus, tvDeviceName, tvBattery, tvSpeed, tvSpeedUnit, tvLiveBadge;
    private TextView tvRpm, tvCoolant, tvThrottle, tvOdometer;
    private TextView tvRideDistance, tvRideDuration, tvRideMileage, tvRideFuel;
    private TextView tvFuelLitres, tvFuelPercent, tvRange, tvTankCap;
    private TextView tvNextService, tvNextServiceRemaining, tvDueCount, tvRideHint;
    private View statusDot;
    private LinearProgressIndicator progressFuel, progressService;
    private MaterialCardView cardPermissions;
    private MaterialButton btnConnect, btnGrant;

    private SettingsManager settings;
    private MaintenanceManager maintenance;
    private int lastFuelPercent = -1;
    private int lastServicePercent = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        settings = SettingsManager.get(requireContext());
        maintenance = new MaintenanceManager(requireContext());

        tvStatus = view.findViewById(R.id.tvStatus);
        tvDeviceName = view.findViewById(R.id.tvDeviceName);
        tvBattery = view.findViewById(R.id.tvBattery);
        statusDot = view.findViewById(R.id.statusDot);
        tvSpeed = view.findViewById(R.id.tvSpeed);
        tvSpeedUnit = view.findViewById(R.id.tvSpeedUnit);
        tvLiveBadge = view.findViewById(R.id.tvLiveBadge);
        tvRideHint = view.findViewById(R.id.tvRideHint);

        tvRpm = view.findViewById(R.id.tvRpm);
        tvCoolant = view.findViewById(R.id.tvCoolant);
        tvThrottle = view.findViewById(R.id.tvThrottle);
        tvOdometer = view.findViewById(R.id.tvOdometer);

        tvRideDistance = view.findViewById(R.id.tvRideDistance);
        tvRideDuration = view.findViewById(R.id.tvRideDuration);
        tvRideMileage = view.findViewById(R.id.tvRideMileage);
        tvRideFuel = view.findViewById(R.id.tvRideFuel);

        tvFuelLitres = view.findViewById(R.id.tvFuelLitres);
        tvFuelPercent = view.findViewById(R.id.tvFuelPercent);
        tvRange = view.findViewById(R.id.tvRange);
        tvTankCap = view.findViewById(R.id.tvTankCap);
        progressFuel = view.findViewById(R.id.progressFuel);

        tvNextService = view.findViewById(R.id.tvNextService);
        tvNextServiceRemaining = view.findViewById(R.id.tvNextServiceRemaining);
        tvDueCount = view.findViewById(R.id.tvDueCount);
        progressService = view.findViewById(R.id.progressService);

        cardPermissions = view.findViewById(R.id.cardPermissions);
        btnGrant = view.findViewById(R.id.btnGrant);
        btnConnect = view.findViewById(R.id.btnConnect);

        btnConnect.setOnClickListener(v -> {
            if (XsrBleService.sIsConnected || XsrBleService.sSessionActive) {
                if (host != null) host.disconnect();
            } else if (host != null) {
                host.connect();
            }
        });
        btnGrant.setOnClickListener(v -> {
            if (host != null) host.requestAppPermissions();
        });

        View cardService = view.findViewById(R.id.cardService);
        cardService.setOnClickListener(v -> {
            if (host != null) host.selectTab(MainActivity.TAB_MAINTENANCE);
        });

        View btnRefuel = view.findViewById(R.id.btnRefuel);
        btnRefuel.setOnClickListener(v ->
                new RefuelBottomSheet().show(getChildFragmentManager(), "refuel"));

        updateUi();
    }

    @Override
    protected void onTick() {
        updateUi();
    }

    @Override
    public void onPermissionsChanged() {
        updateUi();
    }

    private void updateUi() {
        if (!isAdded() || getView() == null) return;

        cardPermissions.setVisibility(host != null && host.hasCorePermissions()
                ? View.GONE : View.VISIBLE);

        boolean connected = XsrBleService.sIsConnected;
        boolean active = XsrBleService.sSessionActive;

        tvStatus.setText(XsrBleService.sStatusText);
        tvDeviceName.setText(XsrBleService.sDeviceName);
        tvBattery.setText(XsrBleService.sBatteryLevel + "%");
        btnConnect.setText(connected || active ? R.string.action_disconnect : R.string.action_connect);

        int statusColor = connected ? 0xFF34D399
                : (XsrBleService.sStatusText != null
                && (XsrBleService.sStatusText.contains("Scan") || XsrBleService.sStatusText.contains("Connect")
                || XsrBleService.sStatusText.contains("Pair") || XsrBleService.sStatusText.contains("Negotiat")
                || XsrBleService.sStatusText.contains("Enabling")))
                ? 0xFFFBBF24 : 0xFFF87171;
        statusDot.setBackgroundTintList(ColorStateList.valueOf(statusColor));
        tvStatus.setTextColor(statusColor);

        int speed;
        int rpm;
        int coolant;
        double throttle;
        double odo;
        double instantMileage;
        boolean riding;
        synchronized (XsrBleService.sLatestTelemetry) {
            BikeTelemetry t = XsrBleService.sLatestTelemetry;
            speed = t.speedKmh;
            rpm = t.engineRpm;
            coolant = t.coolantTemp;
            throttle = t.throttlePct;
            odo = t.odometerKm;
            instantMileage = t.instantMileageKmL;
        }
        riding = XsrBleService.sRideActive;

        tvSpeed.setText(Units.speedValue(settings, speed));
        tvSpeedUnit.setText(Units.speedUnit(settings));
        tvLiveBadge.setText(active ? (riding ? "REC" : "LIVE") : "IDLE");
        tvLiveBadge.setTextColor(active ? 0xFF34D399 : 0xFF6C7A90);

        tvRpm.setText(String.format(Locale.getDefault(), "%,d", rpm));
        tvCoolant.setText(coolant > 0 ? Units.temp(settings, coolant) : "--");
        tvThrottle.setText(String.format(Locale.getDefault(), "%.0f%%", throttle));
        tvOdometer.setText(odo > 0 ? Units.distance(settings, odo) : "--");

        if (riding && XsrBleService.sRideStartMs > 0) {
            long elapsed = Math.max(0, System.currentTimeMillis() - XsrBleService.sRideStartMs);
            tvRideDistance.setText(Units.distance(settings, XsrBleService.sRideDistanceKm));
            tvRideDuration.setText(formatDuration(elapsed));
            tvRideMileage.setText(Units.mileage(settings, XsrBleService.sTripAvgMileage));
            tvRideFuel.setText(Units.fuel(settings, XsrBleService.sRideFuelL) + " used this ride");
        } else {
            tvRideDistance.setText(Units.distance(settings, 0));
            tvRideDuration.setText("0:00");
            tvRideMileage.setText(Units.mileage(settings, instantMileage));
            tvRideFuel.setText("Recording starts automatically when you ride");
        }

        double fuel = XsrBleService.sFuelRemainingL;
        double cap = settings.getTankCapacityL();
        int percent = cap > 0 ? (int) Math.round(Math.max(0, Math.min(100, fuel / cap * 100.0))) : 0;
        tvFuelLitres.setText(Units.fuel(settings, fuel));
        tvFuelPercent.setText(percent + "%");
        int fuelColor = fuel <= settings.getReserveL() ? 0xFFF87171
                : fuel <= settings.getReserveL() * 2 ? 0xFFFBBF24 : 0xFF22D3EE;
        if (percent != lastFuelPercent) {
            lastFuelPercent = percent;
            progressFuel.setProgressCompat(percent, true);
            progressFuel.setIndicatorColor(fuelColor);
        }
        tvRange.setText("Range " + Units.shortDistance(settings, XsrBleService.sEstimatedRangeKm));
        tvTankCap.setText("Tank " + Units.fuel(settings, cap));

        updateMaintenanceCard(odo);
    }

    private void updateMaintenanceCard(double reportedOdo) {
        double odo = Math.max(maintenance.getCurrentOdometer(), reportedOdo);
        List<MaintenanceManager.ServiceItem> items = maintenance.getItems();

        MaintenanceManager.ServiceItem next = null;
        double best = Double.MAX_VALUE;
        int due = 0;
        int soon = 0;
        for (MaintenanceManager.ServiceItem item : items) {
            double remaining = item.getRemainingKm(odo);
            if (remaining < best) {
                best = remaining;
                next = item;
            }
            if (item.isOverdue(odo)) due++;
            else if (item.isDueSoon(odo)) soon++;
        }

        if (next == null) {
            tvNextService.setText("All caught up");
            tvNextServiceRemaining.setText("No service items configured");
            if (lastServicePercent != 0) {
                lastServicePercent = 0;
                progressService.setProgressCompat(0, true);
            }
        } else {
            tvNextService.setText(next.title);
            if (best <= 0) {
                tvNextServiceRemaining.setText("Overdue by " + Units.shortDistance(settings, -best));
                tvNextServiceRemaining.setTextColor(0xFFF87171);
            } else if (best < 1) {
                tvNextServiceRemaining.setText("Due now");
                tvNextServiceRemaining.setTextColor(0xFFFBBF24);
            } else {
                tvNextServiceRemaining.setText(Units.shortDistance(settings, best) + " remaining");
                tvNextServiceRemaining.setTextColor(0xFFA7B3C7);
            }
            int servicePercent = next.getProgressPercent(odo);
            if (servicePercent != lastServicePercent) {
                lastServicePercent = servicePercent;
                progressService.setProgressCompat(servicePercent, true);
                progressService.setIndicatorColor(best <= 0 ? 0xFFF87171
                        : best <= Math.max(50, next.intervalKm * 0.1) ? 0xFFFBBF24 : 0xFF4D8DFF);
            }
        }

        if (due > 0) {
            tvDueCount.setText(due + " overdue");
            tvDueCount.setTextColor(0xFFF87171);
        } else if (soon > 0) {
            tvDueCount.setText(soon + " due soon");
            tvDueCount.setTextColor(0xFFFBBF24);
        } else {
            tvDueCount.setText("All good");
            tvDueCount.setTextColor(0xFF34D399);
        }
    }

    private static String formatDuration(long millis) {
        long secs = millis / 1000;
        long h = secs / 3600;
        long m = (secs % 3600) / 60;
        long s = secs % 60;
        if (h > 0) return String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s);
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }
}
