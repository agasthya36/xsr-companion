package com.xsr.companion.settings;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Central place for every user-tunable value. All values are stored in metric
 * units; the UI converts them for display.
 */
public class SettingsManager {

    private static final String PREFS = "xsr_settings";

    public static final String KEY_UNITS = "units";                       // metric | imperial
    public static final String KEY_TANK_CAPACITY = "tank_capacity_l";    // litres
    public static final String KEY_RESERVE = "reserve_l";                // litres
    public static final String KEY_AUTO_CONNECT = "auto_connect";
    public static final String KEY_BACKGROUND_CONNECT = "background_connect";
    public static final String KEY_GPS_SAVER = "gps_battery_saver";
    public static final String KEY_ALERT_OVERSPEED = "alert_overspeed";
    public static final String KEY_OVERSPEED_KMH = "overspeed_kmh";
    public static final String KEY_ALERT_COOLANT = "alert_coolant";
    public static final String KEY_COOLANT_C = "coolant_c";
    public static final String KEY_ALERT_LOW_FUEL = "alert_low_fuel";
    public static final String KEY_ALERT_SERVICE = "alert_service";
    public static final String KEY_ALERT_COLD = "alert_cold";
    public static final String KEY_FOREGROUND_MS = "foreground_ms";
    public static final String KEY_BACKGROUND_MS = "background_ms";
    public static final String KEY_KEEP_LOGS = "keep_logs";
    public static final String KEY_FIRST_RUN = "first_run_done";
    public static final String KEY_BASELINED = "maintenance_baselined";
    public static final String KEY_MAINTENANCE_INTERVAL_PREFIX = "interval_";
    public static final String KEY_SETTINGS_UPDATED_AT = "settings_updated_at";

    public static final String UNITS_METRIC = "metric";
    public static final String UNITS_IMPERIAL = "imperial";

    private static volatile SettingsManager instance;
    private final SharedPreferences prefs;

    private SettingsManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static SettingsManager get(Context context) {
        if (instance == null) {
            synchronized (SettingsManager.class) {
                if (instance == null) instance = new SettingsManager(context);
            }
        }
        return instance;
    }

    public SharedPreferences raw() {
        return prefs;
    }

    private SharedPreferences.Editor editWithTimestamp() {
        return prefs.edit().putLong(KEY_SETTINGS_UPDATED_AT, System.currentTimeMillis());
    }

    public boolean isImperial() {
        return UNITS_IMPERIAL.equals(prefs.getString(KEY_UNITS, UNITS_METRIC));
    }

    public boolean isMetric() {
        return !isImperial();
    }

    public void setImperial(boolean imperial) {
        editWithTimestamp().putString(KEY_UNITS, imperial ? UNITS_IMPERIAL : UNITS_METRIC).apply();
    }

    public float getTankCapacityL() {
        return prefs.getFloat(KEY_TANK_CAPACITY, 10.0f);
    }

    public void setTankCapacityL(float litres) {
        editWithTimestamp().putFloat(KEY_TANK_CAPACITY, Math.max(1f, litres)).apply();
    }

    public float getReserveL() {
        return prefs.getFloat(KEY_RESERVE, 2.0f);
    }

    public void setReserveL(float litres) {
        editWithTimestamp().putFloat(KEY_RESERVE, Math.max(0f, litres)).apply();
    }

    public boolean isAutoConnect() {
        return prefs.getBoolean(KEY_AUTO_CONNECT, true);
    }

    public void setAutoConnect(boolean enabled) {
        editWithTimestamp().putBoolean(KEY_AUTO_CONNECT, enabled).apply();
    }

    /** Keep a low-power BLE connection request open even when the app is closed. */
    public boolean isBackgroundConnect() {
        return prefs.getBoolean(KEY_BACKGROUND_CONNECT, false);
    }

    public void setBackgroundConnect(boolean enabled) {
        prefs.edit().putBoolean(KEY_BACKGROUND_CONNECT, enabled).apply();
    }

    /** Coarser GPS sampling (10 s / 25 m) for long rides. */
    public boolean isGpsBatterySaver() {
        return prefs.getBoolean(KEY_GPS_SAVER, false);
    }

    public void setGpsBatterySaver(boolean enabled) {
        editWithTimestamp().putBoolean(KEY_GPS_SAVER, enabled).apply();
    }

    public void setAlertOverspeed(boolean enabled) {
        editWithTimestamp().putBoolean(KEY_ALERT_OVERSPEED, enabled).apply();
    }

    public void setOverspeedKmh(int kmh) {
        editWithTimestamp().putInt(KEY_OVERSPEED_KMH, Math.max(20, kmh)).apply();
    }

    public void setAlertCoolant(boolean enabled) {
        editWithTimestamp().putBoolean(KEY_ALERT_COOLANT, enabled).apply();
    }

    public void setCoolantLimitC(int celsius) {
        editWithTimestamp().putInt(KEY_COOLANT_C, Math.max(80, celsius)).apply();
    }

    public void setAlertLowFuel(boolean enabled) {
        editWithTimestamp().putBoolean(KEY_ALERT_LOW_FUEL, enabled).apply();
    }

    public void setAlertService(boolean enabled) {
        editWithTimestamp().putBoolean(KEY_ALERT_SERVICE, enabled).apply();
    }

    public void setAlertColdEngine(boolean enabled) {
        editWithTimestamp().putBoolean(KEY_ALERT_COLD, enabled).apply();
    }

    public boolean alertOverspeed() {
        return prefs.getBoolean(KEY_ALERT_OVERSPEED, false);
    }

    public int overspeedKmh() {
        return prefs.getInt(KEY_OVERSPEED_KMH, 80);
    }

    public boolean alertCoolant() {
        return prefs.getBoolean(KEY_ALERT_COOLANT, true);
    }

    public int coolantLimitC() {
        return prefs.getInt(KEY_COOLANT_C, 105);
    }

    public boolean alertLowFuel() {
        return prefs.getBoolean(KEY_ALERT_LOW_FUEL, true);
    }

    public boolean alertService() {
        return prefs.getBoolean(KEY_ALERT_SERVICE, true);
    }

    public boolean alertColdEngine() {
        return prefs.getBoolean(KEY_ALERT_COLD, false);
    }

    /** How often the UI/processing loop runs while an Activity is bound. */
    public long foregroundIntervalMs() {
        return Math.max(1000L, prefs.getLong(KEY_FOREGROUND_MS, 2000L));
    }

    /** How often the processing loop runs while the app is in the background. */
    public long backgroundIntervalMs() {
        return Math.max(2000L, prefs.getLong(KEY_BACKGROUND_MS, 5000L));
    }

    public int getMaintenanceIntervalKm(String id, int fallback) {
        return prefs.getInt(KEY_MAINTENANCE_INTERVAL_PREFIX + id, fallback);
    }

    public void setMaintenanceIntervalKm(String id, int km) {
        prefs.edit().putInt(KEY_MAINTENANCE_INTERVAL_PREFIX + id, Math.max(50, km)).apply();
    }

    public boolean isFirstRunDone() {
        return prefs.getBoolean(KEY_FIRST_RUN, false);
    }

    public void setFirstRunDone() {
        prefs.edit().putBoolean(KEY_FIRST_RUN, true).apply();
    }

    public boolean isMaintenanceBaselined() {
        return prefs.getBoolean(KEY_BASELINED, false);
    }

    public void setMaintenanceBaselined() {
        prefs.edit().putBoolean(KEY_BASELINED, true).apply();
    }

    public void clearMaintenanceBaselined() {
        prefs.edit().putBoolean(KEY_BASELINED, false).apply();
    }

    public long getSettingsUpdatedAt() {
        return prefs.getLong(KEY_SETTINGS_UPDATED_AT, 0L);
    }

    public JSONObject snapshotWhitelisted() {
        JSONObject obj = new JSONObject();
        try {
            obj.put(KEY_UNITS, prefs.getString(KEY_UNITS, UNITS_METRIC));
            obj.put(KEY_TANK_CAPACITY, (double) prefs.getFloat(KEY_TANK_CAPACITY, 10.0f));
            obj.put(KEY_RESERVE, (double) prefs.getFloat(KEY_RESERVE, 2.0f));
            obj.put(KEY_AUTO_CONNECT, prefs.getBoolean(KEY_AUTO_CONNECT, true));
            obj.put(KEY_GPS_SAVER, prefs.getBoolean(KEY_GPS_SAVER, false));
            obj.put(KEY_ALERT_OVERSPEED, prefs.getBoolean(KEY_ALERT_OVERSPEED, false));
            obj.put(KEY_OVERSPEED_KMH, prefs.getInt(KEY_OVERSPEED_KMH, 80));
            obj.put(KEY_ALERT_COOLANT, prefs.getBoolean(KEY_ALERT_COOLANT, true));
            obj.put(KEY_COOLANT_C, prefs.getInt(KEY_COOLANT_C, 105));
            obj.put(KEY_ALERT_LOW_FUEL, prefs.getBoolean(KEY_ALERT_LOW_FUEL, true));
            obj.put(KEY_ALERT_SERVICE, prefs.getBoolean(KEY_ALERT_SERVICE, true));
            obj.put(KEY_ALERT_COLD, prefs.getBoolean(KEY_ALERT_COLD, false));
            obj.put(KEY_FOREGROUND_MS, prefs.getLong(KEY_FOREGROUND_MS, 2000L));
            obj.put(KEY_BACKGROUND_MS, prefs.getLong(KEY_BACKGROUND_MS, 5000L));
            obj.put(KEY_KEEP_LOGS, prefs.getBoolean(KEY_KEEP_LOGS, false));
        } catch (JSONException ignored) {}
        return obj;
    }

    public synchronized void applySnapshot(JSONObject data, long remoteUpdatedAt) {
        if (data == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (data.has(KEY_UNITS)) editor.putString(KEY_UNITS, data.optString(KEY_UNITS, UNITS_METRIC));
        if (data.has(KEY_TANK_CAPACITY)) editor.putFloat(KEY_TANK_CAPACITY, (float) data.optDouble(KEY_TANK_CAPACITY, 10.0));
        if (data.has(KEY_RESERVE)) editor.putFloat(KEY_RESERVE, (float) data.optDouble(KEY_RESERVE, 2.0));
        if (data.has(KEY_AUTO_CONNECT)) editor.putBoolean(KEY_AUTO_CONNECT, data.optBoolean(KEY_AUTO_CONNECT, true));
        if (data.has(KEY_GPS_SAVER)) editor.putBoolean(KEY_GPS_SAVER, data.optBoolean(KEY_GPS_SAVER, false));
        if (data.has(KEY_ALERT_OVERSPEED)) editor.putBoolean(KEY_ALERT_OVERSPEED, data.optBoolean(KEY_ALERT_OVERSPEED, false));
        if (data.has(KEY_OVERSPEED_KMH)) editor.putInt(KEY_OVERSPEED_KMH, data.optInt(KEY_OVERSPEED_KMH, 80));
        if (data.has(KEY_ALERT_COOLANT)) editor.putBoolean(KEY_ALERT_COOLANT, data.optBoolean(KEY_ALERT_COOLANT, true));
        if (data.has(KEY_COOLANT_C)) editor.putInt(KEY_COOLANT_C, data.optInt(KEY_COOLANT_C, 105));
        if (data.has(KEY_ALERT_LOW_FUEL)) editor.putBoolean(KEY_ALERT_LOW_FUEL, data.optBoolean(KEY_ALERT_LOW_FUEL, true));
        if (data.has(KEY_ALERT_SERVICE)) editor.putBoolean(KEY_ALERT_SERVICE, data.optBoolean(KEY_ALERT_SERVICE, true));
        if (data.has(KEY_ALERT_COLD)) editor.putBoolean(KEY_ALERT_COLD, data.optBoolean(KEY_ALERT_COLD, false));
        if (data.has(KEY_FOREGROUND_MS)) editor.putLong(KEY_FOREGROUND_MS, data.optLong(KEY_FOREGROUND_MS, 2000L));
        if (data.has(KEY_BACKGROUND_MS)) editor.putLong(KEY_BACKGROUND_MS, data.optLong(KEY_BACKGROUND_MS, 5000L));
        if (data.has(KEY_KEEP_LOGS)) editor.putBoolean(KEY_KEEP_LOGS, data.optBoolean(KEY_KEEP_LOGS, false));
        editor.putLong(KEY_SETTINGS_UPDATED_AT, remoteUpdatedAt);
        editor.apply();
    }
}
