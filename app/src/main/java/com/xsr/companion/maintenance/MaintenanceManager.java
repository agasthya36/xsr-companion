package com.xsr.companion.maintenance;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public class MaintenanceManager {

    private static final String PREF_NAME = "xsr_maintenance_prefs";
    private static final String KEY_CURRENT_ODO = "current_odometer_km";

    public static class ServiceItem {
        public final String id;
        public final String title;
        public final String description;
        public final int intervalKm;
        public double lastServiceOdoKm;

        public ServiceItem(String id, String title, String description, int intervalKm, double lastServiceOdoKm) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.intervalKm = intervalKm;
            this.lastServiceOdoKm = lastServiceOdoKm;
        }

        public double getElapsedKm(double currentOdo) {
            return Math.max(0.0, currentOdo - lastServiceOdoKm);
        }

        public double getRemainingKm(double currentOdo) {
            return intervalKm - getElapsedKm(currentOdo);
        }

        public int getProgressPercent(double currentOdo) {
            double elapsed = getElapsedKm(currentOdo);
            return (int) Math.min(100, Math.max(0, (elapsed * 100.0) / intervalKm));
        }

        public boolean isOverdue(double currentOdo) {
            return getRemainingKm(currentOdo) <= 0;
        }

        public boolean isDueSoon(double currentOdo) {
            double rem = getRemainingKm(currentOdo);
            return rem > 0 && rem <= (intervalKm * 0.1); // within 10% or 100km
        }
    }

    private final SharedPreferences prefs;
    private final List<ServiceItem> items = new ArrayList<>();
    private double currentOdometerKm = 0.0;

    public MaintenanceManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        currentOdometerKm = prefs.getFloat(KEY_CURRENT_ODO, 0.0f);
        initDefaultItems();
    }

    private void initDefaultItems() {
        items.clear();
        items.add(new ServiceItem("chain_lube", "Drive Chain Clean & Lube", "Clean slack and apply chain lubricant", 500, getSavedServiceOdo("chain_lube", 0.0)));
        items.add(new ServiceItem("engine_oil", "Engine Oil (Yamalube 10W-40)", "Replace oil (850ml) for engine longevity", 3000, getSavedServiceOdo("engine_oil", 0.0)));
        items.add(new ServiceItem("oil_filter", "Oil Filter Element", "Replace paper oil filter insert", 6000, getSavedServiceOdo("oil_filter", 0.0)));
        items.add(new ServiceItem("brake_pads", "Brake Pads & Fluid", "Check front and rear pad wear groove", 5000, getSavedServiceOdo("brake_pads", 0.0)));
        items.add(new ServiceItem("spark_plug", "Spark Plug (NGK MR8E9)", "Replace spark plug and set gap 0.8-0.9mm", 10000, getSavedServiceOdo("spark_plug", 0.0)));
        items.add(new ServiceItem("air_filter", "Air Cleaner Element", "Replace viscous paper filter element", 12000, getSavedServiceOdo("air_filter", 0.0)));
        items.add(new ServiceItem("coolant", "Coolant Replacement", "Flush and refill radiator & reservoir", 20000, getSavedServiceOdo("coolant", 0.0)));
    }

    private double getSavedServiceOdo(String id, double defaultVal) {
        return prefs.getFloat("last_service_" + id, (float) defaultVal);
    }

    public synchronized void updateOdometer(double odoKm) {
        if (odoKm > 0) {
            currentOdometerKm = odoKm;
            prefs.edit().putFloat(KEY_CURRENT_ODO, (float) currentOdometerKm).apply();
        }
    }

    public synchronized double getCurrentOdometer() {
        return currentOdometerKm;
    }

    public synchronized List<ServiceItem> getItems() {
        return new ArrayList<>(items);
    }

    public synchronized void markServiced(String id) {
        for (ServiceItem item : items) {
            if (item.id.equals(id)) {
                item.lastServiceOdoKm = currentOdometerKm;
                prefs.edit().putFloat("last_service_" + id, (float) currentOdometerKm).apply();
                break;
            }
        }
    }

    public synchronized int getDueItemCount() {
        int count = 0;
        for (ServiceItem item : items) {
            if (item.isOverdue(currentOdometerKm)) {
                count++;
            }
        }
        return count;
    }
}
