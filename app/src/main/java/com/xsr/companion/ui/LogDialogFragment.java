package com.xsr.companion.ui;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.xsr.companion.R;
import com.xsr.companion.util.AppLogManager;

import java.io.File;
import java.util.List;

public class LogDialogFragment extends DialogFragment {

    private AppLogManager.OnLogListener listener;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_live_logs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TextView content = view.findViewById(R.id.tvLogContent);
        TextView counter = view.findViewById(R.id.tvLogCounter);
        ScrollView scroll = view.findViewById(R.id.scrollLogContainer);
        AppLogManager logs = AppLogManager.getInstance();

        List<String> current = logs.getAllLogs();
        counter.setText(current.size() + " log entries recorded");
        content.setText(current.isEmpty()
                ? "No log events recorded yet.\nConnect to the motorcycle or start a scan."
                : logs.getLogsAsString());
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));

        listener = line -> {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (logs.getAllLogs().size() <= 1) content.setText(line + "\n");
                else content.append(line + "\n");
                counter.setText(logs.getAllLogs().size() + " log entries recorded");
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            });
        };
        logs.addListener(listener);

        view.findViewById(R.id.btnCloseLogs).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.btnClearLogs).setOnClickListener(v -> {
            logs.clear();
            content.setText("Log buffer cleared.");
            counter.setText("0 log entries");
        });
        view.findViewById(R.id.btnCopyLogs).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("XSR logs", logs.getLogsAsString()));
                Toast.makeText(requireContext(), "Logs copied", Toast.LENGTH_SHORT).show();
            }
        });
        view.findViewById(R.id.btnExportLogs).setOnClickListener(v -> {
            try {
                File file = logs.exportToFile(requireContext());
                ExportUtils.shareFile(requireContext(), file, "text/plain",
                        "XSR Companion logs");
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Export failed: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null || dialog.getWindow() == null) return;
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
    }

    @Override
    public void onDestroy() {
        if (listener != null) AppLogManager.getInstance().removeListener(listener);
        super.onDestroy();
    }
}
