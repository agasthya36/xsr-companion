package com.xsr.companion.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.xsr.companion.R;
import com.xsr.companion.sync.AuthManager;
import com.xsr.companion.sync.SyncManager;

/**
 * DialogFragment guiding the user through email OTP sign-in / signup,
 * followed by the automatic first-login merge and sync.
 */
public class CloudSyncDialogFragment extends DialogFragment {

    public interface OnSyncCompletedListener {
        void onSyncCompleted();
    }

    private LinearLayout groupEmail;
    private LinearLayout groupCode;
    private LinearLayout groupProgress;
    private LinearLayout groupResult;

    private TextView tvSyncTitle;
    private TextView tvSyncError;
    private TextView tvCodePrompt;
    private TextView tvProgressMsg;
    private TextView tvResultMsg;
    private TextView tvChangeEmail;

    private TextInputEditText inputEmail;
    private TextInputEditText inputCode;

    private MaterialButton btnEmailCancel;
    private MaterialButton btnSendCode;
    private MaterialButton btnResendCode;
    private MaterialButton btnVerifyCode;
    private MaterialButton btnResultDone;

    private AuthManager auth;
    private SyncManager sync;
    private OnSyncCompletedListener listener;

    private String currentEmail = "";
    private CountDownTimer resendTimer;
    private boolean isCountingDown = false;

    public static CloudSyncDialogFragment newInstance() {
        return new CloudSyncDialogFragment();
    }

    public void setOnSyncCompletedListener(OnSyncCompletedListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_cloud_sync, null);
        initViews(view);

        auth = AuthManager.getInstance(requireContext());
        sync = SyncManager.getInstance(requireContext());

        setupListeners();
        showEmailState();

        return new MaterialAlertDialogBuilder(requireContext(), R.style.Widget_Xsr_Card)
                .setView(view)
                .setCancelable(false)
                .create();
    }

    private void initViews(View view) {
        groupEmail = view.findViewById(R.id.groupEmail);
        groupCode = view.findViewById(R.id.groupCode);
        groupProgress = view.findViewById(R.id.groupProgress);
        groupResult = view.findViewById(R.id.groupResult);

        tvSyncTitle = view.findViewById(R.id.tvSyncTitle);
        tvSyncError = view.findViewById(R.id.tvSyncError);
        tvCodePrompt = view.findViewById(R.id.tvCodePrompt);
        tvProgressMsg = view.findViewById(R.id.tvProgressMsg);
        tvResultMsg = view.findViewById(R.id.tvResultMsg);
        tvChangeEmail = view.findViewById(R.id.tvChangeEmail);

        inputEmail = view.findViewById(R.id.inputEmail);
        inputCode = view.findViewById(R.id.inputCode);

        btnEmailCancel = view.findViewById(R.id.btnEmailCancel);
        btnSendCode = view.findViewById(R.id.btnSendCode);
        btnResendCode = view.findViewById(R.id.btnResendCode);
        btnVerifyCode = view.findViewById(R.id.btnVerifyCode);
        btnResultDone = view.findViewById(R.id.btnResultDone);
    }

    private void setupListeners() {
        btnEmailCancel.setOnClickListener(v -> dismiss());

        btnSendCode.setOnClickListener(v -> {
            String email = inputEmail.getText() != null ? inputEmail.getText().toString().trim() : "";
            if (!AuthManager.isValidEmail(email)) {
                showError("Please enter a valid email address.");
                return;
            }
            sendOtp(email);
        });

        btnVerifyCode.setOnClickListener(v -> {
            String code = inputCode.getText() != null ? inputCode.getText().toString().trim() : "";
            if (code.length() < 6 || code.length() > 10) {
                showError("Please enter a valid verification code.");
                return;
            }
            verifyOtp(code);
        });

        btnResendCode.setOnClickListener(v -> {
            if (!isCountingDown && !currentEmail.isEmpty()) {
                sendOtp(currentEmail);
            }
        });

        tvChangeEmail.setOnClickListener(v -> {
            stopResendTimer();
            showEmailState();
        });

        btnResultDone.setOnClickListener(v -> {
            if (listener != null) listener.onSyncCompleted();
            dismiss();
        });
    }

    private void sendOtp(String email) {
        currentEmail = email;
        clearError();
        showProgressState("Sending code...");

        auth.sendOtp(email, new AuthManager.AuthCallback() {
            @Override
            public void onSuccess(String message) {
                showCodeState(email);
                startResendCooldown();
            }

            @Override
            public void onError(String error) {
                showEmailState();
                showError(error);
            }
        });
    }

    private void verifyOtp(String code) {
        clearError();
        showProgressState("Verifying code...");

        auth.verifyOtp(currentEmail, code, new AuthManager.AuthCallback() {
            @Override
            public void onSuccess(String message) {
                runFirstSync();
            }

            @Override
            public void onError(String error) {
                showCodeState(currentEmail);
                showError(error);
            }
        });
    }

    private void runFirstSync() {
        showProgressState("Merging and syncing cloud data...");

        sync.firstSync(result -> {
            if (result.success) {
                showResultState(result.getSummaryMessage());
            } else {
                showResultState("Authentication succeeded, but initial sync had issues:\n" + result.errorMessage);
            }
        });
    }

    private void showEmailState() {
        clearError();
        groupEmail.setVisibility(View.VISIBLE);
        groupCode.setVisibility(View.GONE);
        groupProgress.setVisibility(View.GONE);
        groupResult.setVisibility(View.GONE);
        setCancelable(true);
    }

    private void showCodeState(String email) {
        clearError();
        groupEmail.setVisibility(View.GONE);
        groupCode.setVisibility(View.VISIBLE);
        groupProgress.setVisibility(View.GONE);
        groupResult.setVisibility(View.GONE);
        tvCodePrompt.setText("Enter the verification code sent to " + email);
        inputCode.setText("");
        inputCode.requestFocus();
        setCancelable(true);
    }

    private void showProgressState(String message) {
        clearError();
        groupEmail.setVisibility(View.GONE);
        groupCode.setVisibility(View.GONE);
        groupProgress.setVisibility(View.VISIBLE);
        groupResult.setVisibility(View.GONE);
        tvProgressMsg.setText(message);
        setCancelable(false);
    }

    private void showResultState(String message) {
        clearError();
        groupEmail.setVisibility(View.GONE);
        groupCode.setVisibility(View.GONE);
        groupProgress.setVisibility(View.GONE);
        groupResult.setVisibility(View.VISIBLE);
        tvResultMsg.setText(message);
        setCancelable(false);
    }

    private void showError(String error) {
        tvSyncError.setVisibility(View.VISIBLE);
        tvSyncError.setText(error);
    }

    private void clearError() {
        tvSyncError.setVisibility(View.GONE);
        tvSyncError.setText("");
    }

    private void startResendCooldown() {
        stopResendTimer();
        isCountingDown = true;
        btnResendCode.setEnabled(false);

        resendTimer = new CountDownTimer(60_000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                btnResendCode.setText(String.format("Resend (%ds)", millisUntilFinished / 1000));
            }

            @Override
            public void onFinish() {
                isCountingDown = false;
                btnResendCode.setEnabled(true);
                btnResendCode.setText("Resend");
            }
        }.start();
    }

    private void stopResendTimer() {
        if (resendTimer != null) {
            resendTimer.cancel();
            resendTimer = null;
        }
        isCountingDown = false;
        if (btnResendCode != null) {
            btnResendCode.setEnabled(true);
            btnResendCode.setText("Resend");
        }
    }

    @Override
    public void onDestroyView() {
        stopResendTimer();
        super.onDestroyView();
    }
}
