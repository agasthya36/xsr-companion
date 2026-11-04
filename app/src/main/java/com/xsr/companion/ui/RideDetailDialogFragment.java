package com.xsr.companion.ui;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.xsr.companion.R;
import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.settings.SettingsManager;
import com.xsr.companion.settings.Units;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RideDetailDialogFragment extends DialogFragment {

    public static final String RESULT_KEY = "ride_changed";
    private static final String ARG_ID = "ride_id";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SettingsManager settings;
    private RideDatabaseHelper db;
    private RideDatabaseHelper.RideSummary ride;
    private boolean hasGpsPoints;

    public static RideDetailDialogFragment newInstance(long rideId) {
        RideDetailDialogFragment f = new RideDetailDialogFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_ID, rideId);
        f.setArguments(args);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_ride_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        settings = SettingsManager.get(requireContext());
        db = RideDatabaseHelper.getInstance(requireContext());

        long rideId = getArguments() != null ? getArguments().getLong(ARG_ID, -1) : -1;

        View share = view.findViewById(R.id.btnShareRide);
        share.setOnClickListener(v -> shareRide());
        view.findViewById(R.id.btnViewRoute).setOnClickListener(v -> openRouteMap());
        view.findViewById(R.id.btnDeleteRide).setOnClickListener(v -> confirmDelete());

        io.execute(() -> {
            RideDatabaseHelper.RideSummary summary = db.getRide(rideId);
            List<RideDatabaseHelper.RidePoint> points = db.getPointsForRide(rideId);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> render(view, summary, points));
        });
    }

    private void render(View view, RideDatabaseHelper.RideSummary summary,
                        List<RideDatabaseHelper.RidePoint> points) {
        if (!isAdded() || summary == null) return;
        ride = summary;

        hasGpsPoints = false;
        if (points != null) {
            for (RideDatabaseHelper.RidePoint p : points) {
                if (p.hasGps) {
                    hasGpsPoints = true;
                    break;
                }
            }
        }
        View routeButton = view.findViewById(R.id.btnViewRoute);
        routeButton.setEnabled(hasGpsPoints);
        routeButton.setAlpha(hasGpsPoints ? 1f : 0.5f);

        ((TextView) view.findViewById(R.id.tvDetailDate)).setText(summary.getFormattedDate());
        ((TextView) view.findViewById(R.id.tvDetailSubtitle)).setText(
                summary.getFormattedDuration() + "  •  "
                        + Units.distance(settings, summary.distanceKm));

        RideChartView chart = view.findViewById(R.id.chartSpeed);
        chart.setPoints(downsample(points, 600));

        LinearLayout container = view.findViewById(R.id.statsContainer);
        container.removeAllViews();

        addStatGrid(container, new String[][]{
                {"Distance", Units.distance(settings, summary.distanceKm)},
                {"Moving / total time", summary.getFormattedMovingTime()
                        + " / " + summary.getFormattedDuration()},
                {"Avg / top speed", Units.speedValue(settings, summary.avgSpeedKmh)
                        + " / " + Units.speed(settings, summary.topSpeedKmh)},
                {"Avg / max RPM", String.format(Locale.getDefault(), "%,d", summary.avgRpm)
                        + " / " + String.format(Locale.getDefault(), "%,d", summary.maxRpm)},
                {"Max coolant", Units.temp(settings, summary.maxCoolant)},
                {"Fuel used", Units.fuel(settings, summary.fuelUsedL)},
                {"Mileage", Units.mileage(settings, summary.avgMileageKmL)},
                {"Odometer", formatOdometerRange(summary.startOdoKm, summary.endOdoKm)},
        });
        if (summary.notes != null && !summary.notes.trim().isEmpty()) {
            addStatWide(container, "Notes", summary.notes, true);
        }
    }

    private void addStatGrid(LinearLayout container, String[][] stats) {
        LinearLayout row = null;
        for (int i = 0; i < stats.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(container.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBaselineAligned(false);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.topMargin = i == 0 ? 0 : dp(8);
                container.addView(row, rowParams);
            }
            LinearLayout tile = inflateStat(container, stats[i][0], stats[i][1], false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 2 == 0) {
                lp.setMarginEnd(dp(5));
            } else {
                lp.setMarginStart(dp(5));
            }
            row.addView(tile, lp);
        }
        // Keep a lone final tile at half width so the columns stay aligned.
        if (row != null && stats.length % 2 == 1) {
            View spacer = new View(container.getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(dp(5));
            row.addView(spacer, lp);
        }
    }

    private void addStatWide(LinearLayout container, String label, String value, boolean multiLine) {
        LinearLayout tile = inflateStat(container, label, value, multiLine);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        container.addView(tile, lp);
    }

    private LinearLayout inflateStat(LinearLayout container, String label, String value,
                                     boolean multiLine) {
        LinearLayout tile = (LinearLayout) LayoutInflater.from(container.getContext())
                .inflate(R.layout.item_ride_stat, container, false);
        TextView valueView = tile.findViewById(R.id.tvStatValue);
        TextView labelView = tile.findViewById(R.id.tvStatLabel);
        valueView.setText(value);
        valueView.setMaxLines(multiLine ? 4 : 1);
        labelView.setText(label);
        return tile;
    }

    private String formatOdometerRange(double startKm, double endKm) {
        if (settings.isImperial()) {
            return String.format(Locale.getDefault(), "%,.0f → %,.0f mi",
                    startKm * Units.KM_TO_MI, endKm * Units.KM_TO_MI);
        }
        return String.format(Locale.getDefault(), "%,.0f → %,.0f km", startKm, endKm);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private List<RideDatabaseHelper.RidePoint> downsample(List<RideDatabaseHelper.RidePoint> points,
                                                          int max) {
        if (points == null || points.size() <= max) return points == null ? new ArrayList<>() : points;
        List<RideDatabaseHelper.RidePoint> out = new ArrayList<>(max);
        int step = (int) Math.ceil(points.size() / (double) max);
        for (int i = 0; i < points.size(); i += step) out.add(points.get(i));
        RideDatabaseHelper.RidePoint last = points.get(points.size() - 1);
        if (out.isEmpty() || out.get(out.size() - 1) != last) out.add(last);
        return out;
    }

    private void shareRide() {
        if (ride == null) return;
        if (ride.gpxPath == null) {
            Toast.makeText(requireContext(), "No GPX file for this ride", Toast.LENGTH_SHORT).show();
            return;
        }
        File file = new File(ride.gpxPath);
        if (!file.exists()) {
            Toast.makeText(requireContext(), "GPX file missing", Toast.LENGTH_SHORT).show();
            return;
        }
        ExportUtils.shareFile(requireContext(), file, "application/gpx+xml",
                "Yamaha XSR 155 — " + ride.getFormattedDate());
    }

    private void openRouteMap() {
        if (ride == null) return;
        if (!hasGpsPoints) {
            Toast.makeText(requireContext(), R.string.map_no_route, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(requireContext(), RideMapActivity.class);
        intent.putExtra(RideMapActivity.EXTRA_RIDE_ID, ride.id);
        startActivity(intent);
    }

    private void confirmDelete() {
        if (ride == null) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete ride?")
                .setMessage("This removes the ride, its GPS points and the GPX file. It cannot be undone.")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    io.execute(() -> {
                        db.deleteRide(ride.id);
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(() -> {
                            Bundle result = new Bundle();
                            result.putBoolean("changed", true);
                            getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
                            dismissAllowingStateLoss();
                        });
                    });
                })
                .show();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null || dialog.getWindow() == null) return;
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        int width = (int) (getResources().getDisplayMetrics().widthPixels
                - 32 * getResources().getDisplayMetrics().density);
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
