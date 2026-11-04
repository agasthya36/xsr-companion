package com.xsr.companion.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.xsr.companion.R;
import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.maintenance.MaintenanceManager;
import com.xsr.companion.settings.SettingsManager;
import com.xsr.companion.settings.Units;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ServiceHistoryDialogFragment extends DialogFragment {

    private final List<RideDatabaseHelper.ServiceLogEntry> entries = new ArrayList<>();
    private ViewGroup listView;
    private TextView emptyView;
    private RideDatabaseHelper db;
    private SettingsManager settings;

    public static ServiceHistoryDialogFragment newInstance() {
        return new ServiceHistoryDialogFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_service_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        listView = view.findViewById(R.id.historyList);
        emptyView = view.findViewById(R.id.tvHistoryEmpty);
        db = RideDatabaseHelper.getInstance(requireContext());
        settings = SettingsManager.get(requireContext());
        loadEntries();
    }

    private void loadEntries() {
        if (!isAdded()) return;
        entries.clear();
        entries.addAll(db.getServiceLog(200));
        renderEntries();
    }

    private void renderEntries() {
        listView.removeAllViews();
        if (entries.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        emptyView.setVisibility(View.GONE);

        SimpleDateFormat date = new SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault());
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (RideDatabaseHelper.ServiceLogEntry entry : entries) {
            View row = inflater.inflate(R.layout.item_service_history, listView, false);
            TextView title = row.findViewById(R.id.tvHistoryTitle);
            TextView subtitle = row.findViewById(R.id.tvHistorySubtitle);
            title.setText(MaintenanceManager.displayName(entry.itemId)
                    + " • " + Units.distance(settings, entry.odometerKm));
            StringBuilder sub = new StringBuilder(date.format(new Date(entry.timestamp)));
            if (entry.cost > 0) sub.append(" • ").append(String.format(Locale.getDefault(),
                    "%.2f", entry.cost));
            if (entry.notes != null && !entry.notes.trim().isEmpty()) {
                sub.append("\n").append(entry.notes);
            }
            subtitle.setText(sub.toString());
            row.findViewById(R.id.btnDeleteHistory).setOnClickListener(v ->
                    confirmDelete(entry));
            listView.addView(row);
        }
    }

    private void confirmDelete(RideDatabaseHelper.ServiceLogEntry entry) {
        String name = MaintenanceManager.displayName(entry.itemId);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete entry?")
                .setMessage("Delete " + name + " at "
                        + Units.distance(settings, entry.odometerKm) + "? "
                        + "This cannot be undone. The item's last-service "
                        + "date will go back to never if this was the latest entry.")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    db.deleteServiceLog(entry.id);
                    if ("reset".equals(entry.itemId)) {
                        // Reset entries are informational only.
                    } else if (!"reset".equals(entry.itemId)) {
                        recomputeLastService(entry.itemId);
                    }
                    Toast.makeText(requireContext(), "Entry deleted", Toast.LENGTH_SHORT).show();
                    loadEntries();
                    // Tell MaintenanceFragment to refresh its counters.
                    getParentFragmentManager().setFragmentResult("service_changed", new Bundle());
                })
                .show();
    }

    private void recomputeLastService(String itemId) {
        // Find the latest remaining entry for this item and reset the
        // persisted last-service pointer to it (or to never).
        double latestOdo = 0.0;
        long latestTime = 0L;
        boolean found = false;
        for (RideDatabaseHelper.ServiceLogEntry e : db.getServiceLog(200)) {
            if (itemId.equals(e.itemId)) {
                if (!found || e.timestamp > latestTime) {
                    latestTime = e.timestamp;
                    latestOdo = e.odometerKm;
                    found = true;
                }
            }
        }
        MaintenanceManager manager = new MaintenanceManager(requireContext());
        manager.setLastServiceFromHistory(itemId, found ? latestOdo : 0.0,
                found ? latestTime : 0L);
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null || dialog.getWindow() == null) return;
        Window window = dialog.getWindow();
        int width = (int) (getResources().getDisplayMetrics().widthPixels
                - 32 * getResources().getDisplayMetrics().density);
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
