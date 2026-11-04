package com.xsr.companion.ui;

import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.xsr.companion.R;
import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.diagnostics.DtcManager;
import com.xsr.companion.protocol.BikeTelemetry;
import com.xsr.companion.service.XsrBleService;
import com.xsr.companion.settings.SettingsManager;
import com.xsr.companion.settings.Units;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DiagnosticsFragment extends BaseFragment {

    private RideDatabaseHelper db;
    private SettingsManager settings;
    private MaterialCardView cardDtc;
    private ImageView imgDtc;
    private TextView tvDtcTitle, tvDtcDesc, tvDtcAction, tvNoDtcHistory;
    private LinearLayout infoContainer, dtcHistoryList;

    private TextView[] infoValues;
    private long lastHistoryLoad = 0L;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private static final String[] INFO_LABELS = {
            "Connection", "CCU", "Packets", "Phone battery", "Bike odometer", "Updated"
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_diagnostics, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = RideDatabaseHelper.getInstance(requireContext());
        settings = SettingsManager.get(requireContext());

        cardDtc = view.findViewById(R.id.cardDtc);
        imgDtc = view.findViewById(R.id.imgDtc);
        tvDtcTitle = view.findViewById(R.id.tvDtcTitle);
        tvDtcDesc = view.findViewById(R.id.tvDtcDesc);
        tvDtcAction = view.findViewById(R.id.tvDtcAction);
        infoContainer = view.findViewById(R.id.infoContainer);
        tvNoDtcHistory = view.findViewById(R.id.tvNoDtcHistory);
        dtcHistoryList = view.findViewById(R.id.dtcHistoryList);

        buildInfoRows();

        view.findViewById(R.id.btnLiveLogs).setOnClickListener(v ->
                new LogDialogFragment().show(getParentFragmentManager(), "logs"));
        view.findViewById(R.id.btnExportDiagnostics).setOnClickListener(v -> exportAll());
        view.findViewById(R.id.btnClearDtc).setOnClickListener(v -> confirmClear());

        refreshHistory();
        updateLive();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshHistory();
        updateLive();
    }

    @Override
    protected void onTick() {
        updateLive();
        long now = System.currentTimeMillis();
        if (now - lastHistoryLoad > 30_000L) refreshHistory();
    }

    private void buildInfoRows() {
        infoContainer.removeAllViews();
        infoValues = new TextView[INFO_LABELS.length];
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < INFO_LABELS.length; i++) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, (int) (5 * density), 0, (int) (5 * density));

            TextView label = new TextView(requireContext());
            label.setText(INFO_LABELS[i]);
            label.setTextColor(0xFFA7B3C7);
            label.setTextSize(13f);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView value = new TextView(requireContext());
            value.setText("--");
            value.setTextColor(0xFFF5F8FF);
            value.setTextSize(13f);
            value.setGravity(Gravity.END);

            row.addView(label);
            row.addView(value);
            infoContainer.addView(row);
            infoValues[i] = value;
        }
    }

    private void updateLive() {
        if (!isAdded() || infoValues == null) return;

        int speed = 0, rpm = 0, coolant = 0;
        double odo = 0;
        boolean warning = false;
        int dtcCode = 0;
        synchronized (XsrBleService.sLatestTelemetry) {
            BikeTelemetry t = XsrBleService.sLatestTelemetry;
            speed = t.speedKmh;
            rpm = t.engineRpm;
            coolant = t.coolantTemp;
            odo = t.odometerKm;
            warning = t.warningLamp;
            dtcCode = t.dtcCode;
        }

        infoValues[0].setText(XsrBleService.sStatusText);
        infoValues[0].setTextColor(XsrBleService.sIsConnected ? 0xFF34D399 : 0xFFA7B3C7);
        infoValues[1].setText(XsrBleService.sDeviceName);
        infoValues[2].setText(String.valueOf(XsrBleService.sPacketCount));
        infoValues[3].setText(XsrBleService.sBatteryLevel + "%");
        infoValues[4].setText(odo > 0 ? Units.distance(settings, odo) : "--");
        infoValues[5].setText(XsrBleService.sLastUpdateMs > 0
                ? DateFormat.getTimeFormat(requireContext())
                .format(new Date(XsrBleService.sLastUpdateMs)) : "--");

        if (warning || dtcCode > 0) {
            DtcManager.DtcInfo info = DtcManager.lookup(dtcCode);
            imgDtc.setImageResource(R.drawable.ic_warning);
            tvDtcTitle.setText(dtcCode > 0 ? "DTC " + dtcCode + " — " + info.title
                    : "Check engine light active");
            tvDtcTitle.setTextColor(0xFFF87171);
            tvDtcDesc.setText(info.description);
            tvDtcAction.setText("Action: " + info.action);
            cardDtc.setStrokeColor(0xFFF87171);
        } else {
            imgDtc.setImageResource(R.drawable.ic_check);
            tvDtcTitle.setText("All systems healthy");
            tvDtcTitle.setTextColor(0xFF34D399);
            tvDtcDesc.setText("No active trouble codes. ECU operating within normal parameters.");
            tvDtcAction.setText(speed > 0
                    ? String.format(Locale.getDefault(), "%d RPM • %s", rpm, Units.temp(settings, coolant))
                    : "Waiting for live data");
            cardDtc.setStrokeColor(0xFF2C3748);
        }
    }

    private void refreshHistory() {
        if (!isAdded()) return;
        lastHistoryLoad = System.currentTimeMillis();
        List<RideDatabaseHelper.DtcLogEntry> entries = db.getDtcLog(50);
        dtcHistoryList.removeAllViews();

        if (entries.isEmpty()) {
            tvNoDtcHistory.setVisibility(View.VISIBLE);
            return;
        }
        tvNoDtcHistory.setVisibility(View.GONE);

        SimpleDateFormat fmt = new SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault());
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (RideDatabaseHelper.DtcLogEntry entry : entries) {
            View row = inflater.inflate(android.R.layout.simple_list_item_2, dtcHistoryList, false);
            TextView title = row.findViewById(android.R.id.text1);
            TextView subtitle = row.findViewById(android.R.id.text2);

            title.setText("DTC " + entry.code + " • "
                    + (entry.title != null ? entry.title : "Unknown"));
            title.setTextColor("CRITICAL".equalsIgnoreCase(entry.severity) ? 0xFFF87171
                    : "WARNING".equalsIgnoreCase(entry.severity) ? 0xFFFBBF24 : 0xFFF5F8FF);
            title.setTextSize(14f);

            subtitle.setText(fmt.format(new Date(entry.timestamp))
                    + " • " + Units.distance(settings, entry.odometerKm)
                    + " • " + entry.severity);
            subtitle.setTextColor(0xFFA7B3C7);
            subtitle.setTextSize(12f);
            row.setPadding(0, 10, 0, 10);
            dtcHistoryList.addView(row);
        }
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear DTC history?")
                .setMessage("Only the history on this phone is removed. Active ECU codes are not cleared.")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    db.clearDtcLog();
                    refreshHistory();
                })
                .show();
    }

    private void exportAll() {
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

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
