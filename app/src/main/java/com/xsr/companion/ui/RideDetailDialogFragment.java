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

        addStat(container, "Distance", Units.distance(settings, summary.distanceKm));
        addStat(container, "Moving time", summary.getFormattedMovingTime());
        addStat(container, "Total time", summary.getFormattedDuration());
        addStat(container, "Average speed", Units.speed(settings, summary.avgSpeedKmh));
        addStat(container, "Top speed", Units.speed(settings, summary.topSpeedKmh));
        addStat(container, "Max RPM", String.format(Locale.getDefault(), "%,d", summary.maxRpm));
        addStat(container, "Average RPM", String.format(Locale.getDefault(), "%,d", summary.avgRpm));
        addStat(container, "Max coolant", Units.temp(settings, summary.maxCoolant));
        addStat(container, "Fuel used", Units.fuel(settings, summary.fuelUsedL));
        addStat(container, "Mileage", Units.mileage(settings, summary.avgMileageKmL));
        addStat(container, "Odometer", Units.distance(settings, summary.startOdoKm)
                + " → " + Units.distance(settings, summary.endOdoKm));
        if (summary.notes != null && !summary.notes.trim().isEmpty()) {
            addStat(container, "Notes", summary.notes);
        }
    }

    private void addStat(LinearLayout container, String label, String value) {
        View row = LayoutInflater.from(container.getContext())
                .inflate(android.R.layout.simple_list_item_2, container, false);
        TextView first = row.findViewById(android.R.id.text1);
        TextView second = row.findViewById(android.R.id.text2);
        first.setText(label);
        second.setText(value);
        first.setTextColor(0xFFF5F8FF);
        second.setTextColor(0xFFA7B3C7);
        first.setTextSize(14f);
        second.setTextSize(12f);
        row.setPadding(0, 10, 0, 10);
        container.addView(row);
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
