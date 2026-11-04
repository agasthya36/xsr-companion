package com.xsr.companion.ui;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.xsr.companion.R;
import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.maintenance.MaintenanceManager;
import com.xsr.companion.settings.SettingsManager;
import com.xsr.companion.settings.Units;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ServiceHistoryDialogFragment extends DialogFragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_service_history, container, false);
        ViewGroup list = view.findViewById(R.id.historyList);
        TextView empty = view.findViewById(R.id.tvHistoryEmpty);

        RideDatabaseHelper db = RideDatabaseHelper.getInstance(requireContext());
        SettingsManager settings = SettingsManager.get(requireContext());
        List<RideDatabaseHelper.ServiceLogEntry> entries = db.getServiceLog(200);

        if (entries.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            return view;
        }
        empty.setVisibility(View.GONE);

        SimpleDateFormat date = new SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault());
        LayoutInflater inflater2 = LayoutInflater.from(requireContext());
        for (RideDatabaseHelper.ServiceLogEntry entry : entries) {
            View row = inflater2.inflate(android.R.layout.simple_list_item_2, list, false);
            TextView title = row.findViewById(android.R.id.text1);
            TextView subtitle = row.findViewById(android.R.id.text2);
            title.setText(MaintenanceManager.displayName(entry.itemId)
                    + " • " + Units.distance(settings, entry.odometerKm));
            title.setTextColor(0xFFF5F8FF);
            title.setTextSize(14f);
            StringBuilder sub = new StringBuilder(date.format(new Date(entry.timestamp)));
            if (entry.cost > 0) sub.append(" • ").append(String.format(Locale.getDefault(),
                    "%.2f", entry.cost));
            if (entry.notes != null && !entry.notes.trim().isEmpty()) {
                sub.append("\n").append(entry.notes);
            }
            subtitle.setText(sub.toString());
            subtitle.setTextColor(0xFFA7B3C7);
            subtitle.setTextSize(12f);
            row.setPadding(0, 10, 0, 10);
            list.addView(row);
        }
        return view;
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
