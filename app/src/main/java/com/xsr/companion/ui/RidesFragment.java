package com.xsr.companion.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.xsr.companion.R;
import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.settings.SettingsManager;
import com.xsr.companion.settings.Units;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RidesFragment extends BaseFragment {

    private RideDatabaseHelper db;
    private SettingsManager settings;
    private RideAdapter adapter;
    private RecyclerView recycler;
    private View tvEmpty;
    private TextView tvTotalDistance, tvRideCount, tvTopSpeed, tvTotalTime, tvAvgMileage;

    private long lastLoadMs = 0L;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_rides, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = RideDatabaseHelper.getInstance(requireContext());
        settings = SettingsManager.get(requireContext());

        tvEmpty = view.findViewById(R.id.tvEmpty);
        tvTotalDistance = view.findViewById(R.id.tvTotalDistance);
        tvRideCount = view.findViewById(R.id.tvRideCount);
        tvTopSpeed = view.findViewById(R.id.tvTopSpeed);
        tvTotalTime = view.findViewById(R.id.tvTotalTime);
        tvAvgMileage = view.findViewById(R.id.tvAvgMileage);

        recycler = view.findViewById(R.id.recyclerRides);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new RideAdapter(settings, ride ->
                RideDetailDialogFragment.newInstance(ride.id)
                        .show(getParentFragmentManager(), "ride_detail"));
        recycler.setAdapter(adapter);

        view.findViewById(R.id.btnExportAll).setOnClickListener(v -> exportEverything());

        getParentFragmentManager().setFragmentResultListener(
                RideDetailDialogFragment.RESULT_KEY, getViewLifecycleOwner(), (key, bundle) -> refresh());

        refresh();
    }

    @Override
    protected void onTick() {
        long now = System.currentTimeMillis();
        if (now - lastLoadMs >= 10_000L) refresh();
    }

    public void openRide(long rideId) {
        if (!isAdded()) return;
        RideDetailDialogFragment.newInstance(rideId)
                .show(getParentFragmentManager(), "ride_detail");
    }

    private void refresh() {
        if (!isAdded()) return;
        lastLoadMs = System.currentTimeMillis();

        List<RideDatabaseHelper.RideSummary> rides = db.getRecentRides(300);
        adapter.submit(rides);
        tvEmpty.setVisibility(rides.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(rides.isEmpty() ? View.GONE : View.VISIBLE);

        RideDatabaseHelper.AggregateStats stats = db.getAggregateStats();
        tvTotalDistance.setText(Units.distance(settings, stats.totalDistanceKm));
        tvRideCount.setText(String.valueOf(stats.totalRidesCount));
        tvTopSpeed.setText(stats.lifetimeTopSpeedKmh > 0
                ? Units.speed(settings, stats.lifetimeTopSpeedKmh) : "--");
        tvTotalTime.setText(formatDuration(stats.totalMovingSec > 0
                ? stats.totalMovingSec : stats.totalDurationSec));
        tvAvgMileage.setText(stats.overallAvgMileageKmL > 0.5
                ? Units.mileage(settings, stats.overallAvgMileageKmL) : "--");
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

    private static String formatDuration(int seconds) {
        if (seconds <= 0) return "--";
        int mins = seconds / 60;
        if (mins >= 60) {
            return String.format(Locale.getDefault(), "%dh %02dm", mins / 60, mins % 60);
        }
        return String.format(Locale.getDefault(), "%dm", mins);
    }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
