package com.xsr.companion.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.xsr.companion.service.XsrBleService;

/**
 * Base for the tab screens. Refreshes on the same 2 s cadence the service uses
 * for its foreground processing, so telemetry is never rendered more often
 * than it is produced.
 */
public abstract class BaseFragment extends Fragment {

    protected MainActivity host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (isResumed() && isAdded()) {
                onTick();
                handler.postDelayed(this, 2000L);
            }
        }
    };

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof MainActivity) host = (MainActivity) context;
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        onTick();
        handler.postDelayed(ticker, 2000L);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(ticker);
        super.onDestroyView();
    }

    @Nullable
    protected XsrBleService service() {
        return host != null ? host.getBleService() : null;
    }

    protected abstract void onTick();
}
