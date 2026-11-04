package com.xsr.companion.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.xsr.companion.R;
import com.xsr.companion.maintenance.MaintenanceManager;
import com.xsr.companion.service.XsrBleService;
import com.xsr.companion.settings.SettingsManager;
import com.xsr.companion.settings.Units;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MaintenanceFragment extends BaseFragment {

    private MaintenanceManager maintenance;
    private SettingsManager settings;
    private TextView tvOdometer, tvDueSummary;
    private LinearLayout serviceList;

    private final List<Row> rows = new ArrayList<>();

    private static class Row {
        String id;
        TextView title, desc, status, remaining;
        com.google.android.material.button.MaterialButton interval, log;
        LinearProgressIndicator progress;
        int lastPercent = -1;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_maintenance, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        maintenance = new MaintenanceManager(requireContext());
        settings = SettingsManager.get(requireContext());

        tvOdometer = view.findViewById(R.id.tvOdometer);
        tvDueSummary = view.findViewById(R.id.tvDueSummary);
        serviceList = view.findViewById(R.id.serviceList);

        view.findViewById(R.id.btnServiceHistory).setOnClickListener(v ->
                new ServiceHistoryDialogFragment().show(getParentFragmentManager(), "service_history"));

        com.google.android.material.button.MaterialButton reset = view.findViewById(R.id.btnResetCounters);
        if (reset != null) reset.setOnClickListener(v -> showResetCountersDialog());

        buildRows();
        refresh();
    }

    @Override
    protected void onTick() {
        refresh();
    }

    private void buildRows() {
        serviceList.removeAllViews();
        rows.clear();
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        for (MaintenanceManager.ServiceItem item : maintenance.getItems()) {
            View rowView = inflater.inflate(R.layout.item_maintenance, serviceList, false);
            Row row = new Row();
            row.id = item.id;
            row.title = rowView.findViewById(R.id.tvItemTitle);
            row.desc = rowView.findViewById(R.id.tvItemDesc);
            row.status = rowView.findViewById(R.id.tvItemStatus);
            row.remaining = rowView.findViewById(R.id.tvItemRemaining);
            row.interval = rowView.findViewById(R.id.btnItemInterval);
            row.log = rowView.findViewById(R.id.btnLogService);
            row.progress = rowView.findViewById(R.id.progressItem);

            row.title.setText(item.title);
            row.desc.setText(item.description);
            row.log.setOnClickListener(v -> showServiceDialog(item.id));
            row.interval.setOnClickListener(v -> showIntervalDialog(item.id));
            rowView.setOnClickListener(v -> showInfo(item.id));

            serviceList.addView(rowView);
            rows.add(row);
        }
    }

    private double currentOdometer() {
        return Math.max(maintenance.getCurrentOdometer(), XsrBleService.sLatestBikeOdo);
    }

    private void refresh() {
        if (!isAdded()) return;
        double odo = currentOdometer();

        tvOdometer.setText(odo > 0 ? Units.distance(settings, odo) : "--");

        // Load the item set once per refresh; each getItems()/getItem() call
        // reloads preferences.
        java.util.Map<String, MaintenanceManager.ServiceItem> items =
                maintenance.getItemsById();

        int due = 0;
        int soon = 0;
        for (MaintenanceManager.ServiceItem item : items.values()) {
            if (item.isOverdue(odo)) due++;
            else if (item.isDueSoon(odo)) soon++;
        }
        tvDueSummary.setText(String.format(Locale.getDefault(), "%d / %d", due, soon));
        tvDueSummary.setTextColor(due > 0 ? 0xFFF87171 : soon > 0 ? 0xFFFBBF24 : 0xFF34D399);

        for (Row row : rows) {
            MaintenanceManager.ServiceItem item = items.get(row.id);
            if (item == null) continue;
            refreshRow(row, item, odo);
        }
    }

    private void refreshRow(Row row, MaintenanceManager.ServiceItem item, double odo) {
        double remaining = item.getRemainingKm(odo);
        int percent = item.getProgressPercent(odo);

        row.interval.setText(Units.interval(settings, item.intervalKm));
        if (percent != row.lastPercent) {
            row.lastPercent = percent;
            row.progress.setProgressCompat(percent, true);
        }

        if (item.lastServiceTime <= 0) {
            row.status.setText("NEVER");
            row.status.setTextColor(0xFF4D8DFF);
            row.progress.setIndicatorColor(0xFF4D8DFF);
            row.remaining.setText(Units.shortDistance(settings, item.intervalKm)
                    + " remaining • last done: never");
            return;
        }

        if (item.isOverdue(odo)) {
            row.status.setText("OVERDUE");
            row.status.setTextColor(0xFFF87171);
            row.progress.setIndicatorColor(0xFFF87171);
            row.remaining.setText("Overdue by " + Units.shortDistance(settings, Math.abs(remaining))
                    + " • last at " + Units.shortDistance(settings, item.lastServiceOdoKm));
        } else if (item.isDueSoon(odo)) {
            row.status.setText("DUE SOON");
            row.status.setTextColor(0xFFFBBF24);
            row.progress.setIndicatorColor(0xFFFBBF24);
            row.remaining.setText(Units.shortDistance(settings, remaining) + " remaining • last at "
                    + Units.shortDistance(settings, item.lastServiceOdoKm));
        } else {
            row.status.setText("OK");
            row.status.setTextColor(0xFF34D399);
            row.progress.setIndicatorColor(0xFF4D8DFF);
            row.remaining.setText(Units.shortDistance(settings, remaining) + " remaining • last at "
                    + Units.shortDistance(settings, item.lastServiceOdoKm));
        }
    }

    private void showServiceDialog(String id) {
        MaintenanceManager.ServiceItem item = maintenance.getItem(id);
        if (item == null) return;
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_service_log, null, false);
        ((TextView) content.findViewById(R.id.tvServiceTitle)).setText(item.title);
        double serviceOdo = currentOdometer();
        ((TextView) content.findViewById(R.id.tvServiceOdo)).setText(
                "at " + Units.distance(settings, serviceOdo));

        EditText notes = content.findViewById(R.id.inputServiceNotes);
        EditText cost = content.findViewById(R.id.inputServiceCost);

        final android.content.DialogInterface[] holder = new android.content.DialogInterface[1];
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Log this service?")
                .setMessage("This records that you actually did "
                        + item.title.toLowerCase(Locale.getDefault())
                        + " at " + Units.distance(settings, serviceOdo) + ". "
                        + "Add a note and justification.")
                .setView(content)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, null)
                .show();
        holder[0] = dialog;
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String noteText = notes.getText().toString().trim();
            if (noteText.isEmpty()) {
                Toast.makeText(requireContext(),
                        "Add a note explaining why this service was done — cannot save empty",
                        Toast.LENGTH_LONG).show();
                return;
            }
            double costValue = parseDouble(cost.getText().toString());
            maintenance.markServiced(id, serviceOdo, noteText, costValue);
            Toast.makeText(requireContext(), "Service logged", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            refresh();
        });
    }

    private void showResetCountersDialog() {
        double odo = currentOdometer();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear all services to never?")
                .setMessage("Sets every item to last done: never and clears all "
                        + "service history counters. This is for a new motorcycle "
                        + "(no services done yet). The odometer will refresh "
                        + "from the bike on next ride.")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton("Clear to never", (dialog, which) -> {
                    maintenance.clearAllToNever();
                    Toast.makeText(requireContext(), "All services set to never", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }

    private void showIntervalDialog(String id) {
        MaintenanceManager.ServiceItem item = maintenance.getItem(id);
        if (item == null) return;

        boolean imperial = settings.isImperial();
        int shown = imperial
                ? (int) Math.round(item.intervalKm * Units.KM_TO_MI)
                : item.intervalKm;

        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(shown));
        input.setSelectAllOnFocus(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(item.title + " interval")
                .setMessage("Distance between services in " + (imperial ? "miles" : "km") + ".")
                .setView(input)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    int value;
                    try {
                        value = Integer.parseInt(input.getText().toString().trim());
                    } catch (Exception e) {
                        return;
                    }
                    int km = imperial ? (int) Math.round(value / Units.KM_TO_MI) : value;
                    maintenance.setInterval(id, Math.max(50, km));
                    refresh();
                })
                .show();
    }

    private void showInfo(String id) {
        MaintenanceManager.ServiceItem item = maintenance.getItem(id);
        if (item == null) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(item.title)
                .setMessage(item.description + "\n\nDefault interval: "
                        + Units.interval(settings, item.defaultIntervalKm))
                .setPositiveButton(R.string.action_ok, null)
                .show();
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0.0;
        }
    }
}
