package com.xsr.companion.maintenance;

import android.content.Context;
import android.content.SharedPreferences;

import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.settings.SettingsManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MaintenanceManager {

    private static final String PREF_NAME = "xsr_maintenance_prefs";
    private static final String KEY_CURRENT_ODO = "current_odometer_km";
    private static final String KEY_LAST_PREFIX = "last_service_";

    public static class ServiceItem {
        public final String id;
        public final String title;
        public final String description;
        public final int defaultIntervalKm;
        public int intervalKm;
        public double lastServiceOdoKm;
        public long lastServiceTime;

        public ServiceItem(String id, String title, String description,
                           int defaultIntervalKm, double lastServiceOdoKm) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.defaultIntervalKm = defaultIntervalKm;
            this.intervalKm = defaultIntervalKm;
            this.lastServiceOdoKm = lastServiceOdoKm;
        }

        public double getElapsedKm(double currentOdo) {
            return Math.max(0.0, currentOdo - lastServiceOdoKm);
        }

        public double getRemainingKm(double currentOdo) {
            return intervalKm - getElapsedKm(currentOdo);
        }

        public int getProgressPercent(double currentOdo) {
            if (intervalKm <= 0) return 0;
            double elapsed = getElapsedKm(currentOdo);
            return (int) Math.min(100, Math.max(0, (elapsed * 100.0) / intervalKm));
        }

        public boolean isOverdue(double currentOdo) {
            return getRemainingKm(currentOdo) <= 0;
        }

        public boolean isDueSoon(double currentOdo) {
            double rem = getRemainingKm(currentOdo);
            return rem > 0 && rem <= Math.max(50, intervalKm * 0.1);
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final SettingsManager settings;
    private final RideDatabaseHelper db;
    private final List<ServiceItem> items = new ArrayList<>();
    private double currentOdometerKm = 0.0;

    public MaintenanceManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.settings = SettingsManager.get(this.context);
        this.db = RideDatabaseHelper.getInstance(this.context);
        this.currentOdometerKm = prefs.getFloat(KEY_CURRENT_ODO, 0.0f);
        initDefaultItems();
        loadIntervals();
    }

    private void initDefaultItems() {
        items.clear();
        items.add(new ServiceItem("chain_lube", "Drive chain clean & lube",
                "Clean, check slack and lubricate the chain.", 500,
                getSavedServiceOdo("chain_lube")));
        items.add(new ServiceItem("engine_oil", "Engine oil",
                "Yamalube 10W-40, 850 ml. Replace with filter service.", 3000,
                getSavedServiceOdo("engine_oil")));
        items.add(new ServiceItem("oil_filter", "Oil filter element",
                "Replace the paper filter insert.", 6000,
                getSavedServiceOdo("oil_filter")));
        items.add(new ServiceItem("brake_pads", "Brake pads & fluid",
                "Check pad wear grooves and fluid level.", 5000,
                getSavedServiceOdo("brake_pads")));
        items.add(new ServiceItem("spark_plug", "Spark plug",
                "NGK MR8E9, gap 0.8–0.9 mm.", 10000,
                getSavedServiceOdo("spark_plug")));
        items.add(new ServiceItem("air_filter", "Air cleaner element",
                "Replace the viscous paper filter element.", 12000,
                getSavedServiceOdo("air_filter")));
        items.add(new ServiceItem("coolant", "Coolant replacement",
                "Flush and refill radiator and reservoir.", 20000,
                getSavedServiceOdo("coolant")));
    }

    private double getSavedServiceOdo(String id) {
        return prefs.getFloat(KEY_LAST_PREFIX + id, 0.0f);
    }

    private void loadIntervals() {
        for (ServiceItem item : items) {
            item.intervalKm = settings.getMaintenanceIntervalKm(item.id, item.defaultIntervalKm);
            item.lastServiceTime = prefs.getLong(KEY_LAST_PREFIX + item.id + "_time", 0L);
        }
    }

    public synchronized void updateOdometer(double odoKm) {
        if (odoKm <= 0) return;
        if (Math.abs(odoKm - currentOdometerKm) < 0.05) return;
        currentOdometerKm = odoKm;
        prefs.edit().putFloat(KEY_CURRENT_ODO, (float) currentOdometerKm).apply();
        baselineIfNeeded(odoKm);
    }

    /**
     * On a used motorcycle every item would otherwise look overdue because the
     * last service odometer is unknown. On first odometer sync we baseline all
     * items to the current odometer; the rider can then adjust intervals or log
     * real services as they go.
     */
    private void baselineIfNeeded(double odoKm) {
        if (settings.isMaintenanceBaselined() || odoKm <= 0) return;
        // Do NOT baseline automatically. On a new bike that falsely marks
        // everything as done at 263 km. Never-serviced items stay at
        // lastServiceOdo = 0 / time = 0 and show "never serviced" until
        // the rider logs a real service or taps Reset counters to 0.
        settings.setMaintenanceBaselined();
    }

    public synchronized double getCurrentOdometer() {
        // The BLE service owns odometer updates and writes them to prefs; this
        // instance may have been created before that write, so prefer the
        // freshest persisted value.
        float saved = prefs.getFloat(KEY_CURRENT_ODO, 0.0f);
        if (saved > currentOdometerKm) currentOdometerKm = saved;
        return currentOdometerKm;
    }

    public synchronized List<ServiceItem> getItems() {
        loadIntervals();
        updateTimes();
        return new ArrayList<>(items);
    }

    public synchronized ServiceItem getItem(String id) {
        loadIntervals();
        updateTimes();
        for (ServiceItem item : items) {
            if (item.id.equals(id)) return item;
        }
        return null;
    }

    private void updateTimes() {
        for (ServiceItem item : items) {
            item.lastServiceTime = prefs.getLong(KEY_LAST_PREFIX + item.id + "_time", 0L);
        }
    }

    public synchronized Map<String, ServiceItem> getItemsById() {
        Map<String, ServiceItem> map = new LinkedHashMap<>();
        for (ServiceItem item : getItems()) map.put(item.id, item);
        return map;
    }

    /**
     * Sets every item to never-serviced (last = 0/0) and clears the
     * odometer, for a new motorcycle. The next BLE odometer sync will
     * repopulate the display, but all items stay "never serviced".
     */
    public synchronized void clearAllToNever() {
        SharedPreferences.Editor editor = prefs.edit();
        for (ServiceItem item : items) {
            item.lastServiceOdoKm = 0.0;
            item.lastServiceTime = 0L;
            editor.putFloat(KEY_LAST_PREFIX + item.id, 0.0f);
            editor.putLong(KEY_LAST_PREFIX + item.id + "_time", 0L);
        }
        editor.putFloat(KEY_CURRENT_ODO, 0.0f);
        editor.apply();
        currentOdometerKm = 0.0;
        settings.clearMaintenanceBaselined();
        db.insertServiceLog("reset", System.currentTimeMillis(), 0.0,
                "All services cleared to never serviced", 0.0);
    }

    /**
     * Treats the bike's current odometer as the origin for every item. Used
     * when the rider confirms this is a new motorcycle (or a corrected
     * odometer): each interval then counts down from zero service history.
     */
    public synchronized void resetAllToZero() {
        double origin = Math.max(0.0, getCurrentOdometer());
        SharedPreferences.Editor editor = prefs.edit();
        for (ServiceItem item : items) {
            item.lastServiceOdoKm = origin;
            item.lastServiceTime = 0L;
            editor.putFloat(KEY_LAST_PREFIX + item.id, (float) origin);
            editor.putLong(KEY_LAST_PREFIX + item.id + "_time", 0L);
        }
        editor.apply();
        settings.setMaintenanceBaselined();
        if (origin > 0) {
            db.insertServiceLog("reset", System.currentTimeMillis(), origin,
                    "Maintenance counters reset to current odometer", 0.0);
        }
    }

    /** Clears the "baselined" flag so a future {@link #updateOdometer} can re-seed. */
    public synchronized void unbaseline() {
        settings.clearMaintenanceBaselined();
    }

    public synchronized void setLastServiceFromHistory(String id, double odoKm, long when) {
        for (ServiceItem item : items) {
            if (item.id.equals(id)) {
                item.lastServiceOdoKm = Math.max(0.0, odoKm);
                item.lastServiceTime = when;
                prefs.edit()
                        .putFloat(KEY_LAST_PREFIX + id, (float) Math.max(0.0, odoKm))
                        .putLong(KEY_LAST_PREFIX + id + "_time", when)
                        .apply();
                return;
            }
        }
    }

    public synchronized void markServiced(String id, String notes, double cost) {
        markServiced(id, getCurrentOdometer(), notes, cost);
    }

    /**
     * Logs a service at an explicit odometer, which keeps the stored value in
     * sync with the number the rider saw in the dialog even if the service is
     * still flushing a fresher reading.
     */
    public synchronized void markServiced(String id, double odoKm, String notes, double cost) {
        ServiceItem item = getItem(id);
        if (item == null) return;
        double serviceOdo = odoKm > 0 ? odoKm : getCurrentOdometer();
        item.lastServiceOdoKm = serviceOdo;
        long now = System.currentTimeMillis();
        prefs.edit()
                .putFloat(KEY_LAST_PREFIX + id, (float) serviceOdo)
                .putLong(KEY_LAST_PREFIX + id + "_time", now)
                .apply();
        db.insertServiceLog(id, now, serviceOdo, notes, cost);
    }

    public synchronized void markServiced(String id) {
        markServiced(id, null, 0.0);
    }

    public synchronized void setInterval(String id, int km) {
        settings.setMaintenanceIntervalKm(id, km);
        for (ServiceItem item : items) {
            if (item.id.equals(id)) item.intervalKm = Math.max(50, km);
        }
    }

    public synchronized int getDueItemCount() {
        int count = 0;
        for (ServiceItem item : items) {
            if (item.isOverdue(currentOdometerKm)) count++;
        }
        return count;
    }

    public synchronized int getDueSoonItemCount() {
        int count = 0;
        for (ServiceItem item : items) {
            if (item.isDueSoon(currentOdometerKm)) count++;
        }
        return count;
    }

    public synchronized ServiceItem getNextDue() {
        ServiceItem next = null;
        double best = Double.MAX_VALUE;
        for (ServiceItem item : items) {
            double rem = item.getRemainingKm(currentOdometerKm);
            if (rem < best) {
                best = rem;
                next = item;
            }
        }
        return next;
    }

    public List<RideDatabaseHelper.ServiceLogEntry> getHistory(int limit) {
        return db.getServiceLog(limit);
    }

    public static String displayName(String id) {
        switch (id) {
            case "chain_lube": return "Chain lube";
            case "engine_oil": return "Engine oil";
            case "oil_filter": return "Oil filter";
            case "brake_pads": return "Brake pads";
            case "spark_plug": return "Spark plug";
            case "air_filter": return "Air filter";
            case "coolant": return "Coolant";
            default: return id;
        }
    }
}
