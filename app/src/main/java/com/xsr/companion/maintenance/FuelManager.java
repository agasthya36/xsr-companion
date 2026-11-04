package com.xsr.companion.maintenance;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Manages fuel tank capacity, fuel consumption integration, refuel logging,
 * and estimated riding range for the Yamaha XSR 155 (10.0 Litre tank capacity).
 */
public class FuelManager {

    public static final String PREF_FUEL_TANK_CAPACITY = "fuel_tank_capacity_l";
    public static final String PREF_FUEL_REMAINING = "fuel_remaining_l";
    public static final String PREF_FUEL_CONSUMED_SINCE_REFUEL = "fuel_consumed_since_refuel_l";
    public static final String PREF_FUEL_LIFETIME_CONSUMED = "fuel_lifetime_consumed_l";
    public static final String PREF_LAST_REFUEL_TIME = "fuel_last_refuel_time";
    public static final String PREF_LAST_REFUEL_ODO = "fuel_last_refuel_odo";

    // Yamaha XSR 155 Factory Tank Specs
    public static final float DEFAULT_TANK_CAPACITY_L = 10.0f;
    public static final float RESERVE_CAPACITY_L = 2.0f;
    public static final double DEFAULT_ESTIMATED_MILEAGE_KML = 48.5; // Real-world 155cc avg

    private final SharedPreferences prefs;

    public FuelManager(Context context) {
        this.prefs = context.getSharedPreferences("xsr_fuel_prefs", Context.MODE_PRIVATE);
    }

    public float getTankCapacityL() {
        return prefs.getFloat(PREF_FUEL_TANK_CAPACITY, DEFAULT_TANK_CAPACITY_L);
    }

    public float getRemainingLitres() {
        return Math.max(0.0f, Math.min(getTankCapacityL(), prefs.getFloat(PREF_FUEL_REMAINING, DEFAULT_TANK_CAPACITY_L)));
    }

    public float getRemainingPercent() {
        float cap = getTankCapacityL();
        if (cap <= 0f) return 100f;
        return Math.max(0f, Math.min(100f, (getRemainingLitres() / cap) * 100f));
    }

    public double getEstimatedRangeKm(double avgMileageKmL) {
        double mileage = (avgMileageKmL > 10.0 && avgMileageKmL < 90.0) ? avgMileageKmL : DEFAULT_ESTIMATED_MILEAGE_KML;
        return getRemainingLitres() * mileage;
    }

    public float getConsumedSinceRefuelL() {
        return prefs.getFloat(PREF_FUEL_CONSUMED_SINCE_REFUEL, 0.0f);
    }

    public float getLifetimeConsumedL() {
        return prefs.getFloat(PREF_FUEL_LIFETIME_CONSUMED, 0.0f);
    }

    public long getLastRefuelTime() {
        return prefs.getLong(PREF_LAST_REFUEL_TIME, 0L);
    }

    public double getLastRefuelOdo() {
        return prefs.getFloat(PREF_LAST_REFUEL_ODO, 0.0f);
    }

    /**
     * Integrates fuel consumed (e.g. from 1-second interval calculated fuel flow).
     */
    public synchronized void consumeFuel(double litresConsumed) {
        if (litresConsumed <= 0.0) return;
        float currentRemaining = getRemainingLitres();
        float newRemaining = Math.max(0.0f, (float) (currentRemaining - litresConsumed));
        float currentSinceRefuel = getConsumedSinceRefuelL();
        float currentLifetime = getLifetimeConsumedL();

        prefs.edit()
                .putFloat(PREF_FUEL_REMAINING, newRemaining)
                .putFloat(PREF_FUEL_CONSUMED_SINCE_REFUEL, (float) (currentSinceRefuel + litresConsumed))
                .putFloat(PREF_FUEL_LIFETIME_CONSUMED, (float) (currentLifetime + litresConsumed))
                .apply();
    }

    /**
     * Logs a refuel event, resetting the tank to full (10.0 L) or adding litres.
     */
    public synchronized void logRefuel(float litresAdded, double currentOdo) {
        float cap = getTankCapacityL();
        float newRemaining = (litresAdded <= 0f) ? cap : Math.min(cap, getRemainingLitres() + litresAdded);

        prefs.edit()
                .putFloat(PREF_FUEL_REMAINING, newRemaining)
                .putFloat(PREF_FUEL_CONSUMED_SINCE_REFUEL, 0.0f)
                .putLong(PREF_LAST_REFUEL_TIME, System.currentTimeMillis())
                .putFloat(PREF_LAST_REFUEL_ODO, (float) currentOdo)
                .apply();
    }

    /**
     * Resets fuel tank to 100% full capacity.
     */
    public synchronized void resetToFull(double currentOdo) {
        logRefuel(getTankCapacityL(), currentOdo);
    }
}
