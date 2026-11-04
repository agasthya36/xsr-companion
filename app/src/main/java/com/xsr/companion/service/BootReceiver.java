package com.xsr.companion.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.xsr.companion.settings.SettingsManager;

/**
 * Re-arms the low-power background connection after a reboot. Only runs when
 * the rider has enabled "Background connection"; otherwise it does nothing.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        SettingsManager settings = SettingsManager.get(context);
        if (!settings.isBackgroundConnect() || !settings.isAutoConnect()) return;

        Intent service = new Intent(context, XsrBleService.class)
                .setAction(XsrBleService.ACTION_BACKGROUND_CONNECT);
        try {
            ContextCompat.startForegroundService(context, service);
        } catch (Exception ignored) {
            // The OS may refuse an FGS start from BOOT_COMPLETED; the rider
            // can still launch the app once.
        }
    }
}
