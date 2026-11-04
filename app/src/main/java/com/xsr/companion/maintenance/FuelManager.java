package com.xsr.companion.maintenance;

import android.content.Context;
import android.content.SharedPreferences;

import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.settings.SettingsManager;

/**
 * Tracks estimated remaining fuel and real tank-to-tank fuel economy.
 *
 * Fuel is integrated by the BLE service every processing tick using elapsed
 * time (never per packet) and passed here in batches, so SharedPreferences is
 * only written a few times per minute.
 */
public class FuelManager {

    public static final String PREF_FUEL_TANK_CAPACITY = "fuel_tank_capacity_l";
    public static final String PREF_FUEL_REMAINING = "fuel_remaining_l";
    public static final String PREF_FUEL_CONSUMED_SINCE_REFUEL = "fuel_consumed_since_refuel_l";
    public static final String PREF_FUEL_LIFETIME_CONSUMED = "fuel_lifetime_consumed_l";
    public static final String PREF_LAST_REFUEL_TIME = "fuel_last_refuel_time";
    public static final String PREF_LAST_REFUEL_ODO = "fuel_last_refuel_odo";
    /** Bumped whenever the rider changes the estimate (refuel / cap change). */
    public static final String PREF_FUEL_REVISION = "fuel_revision";

    public static final float DEFAULT_TANK_CAPACITY_L = 10.0f;
    public static final float RESERVE_CAPACITY_L = 2.0f;
    public static final double DEFAULT_ESTIMATED_MILEAGE_KML = 48.5;

    private final Context context;
    private final SharedPreferences prefs;
    private final SettingsManager settings;
    private final RideDatabaseHelper db;
    private double cachedActualMileage = -1.0;

    public FuelManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("xsr_fuel_prefs", Context.MODE_PRIVATE);
        this.settings = SettingsManager.get(this.context);
        this.db = RideDatabaseHelper.getInstance(this.context);
    }

    public float getTankCapacityL() {
        return settings.getTankCapacityL();
    }

    public float getRemainingLitres() {
        return Math.max(0.0f, Math.min(getTankCapacityL(),
                prefs.getFloat(PREF_FUEL_REMAINING, getTankCapacityL())));
    }

    public void setRemainingLitres(double litres) {
        prefs.edit()
                .putFloat(PREF_FUEL_REMAINING,
                        (float) Math.max(0.0, Math.min(getTankCapacityL(), litres)))
                .putInt(PREF_FUEL_REVISION, getRevision() + 1)
                .apply();
    }

    /** Monotonic counter so the BLE service can detect a rider-side change. */
    public int getRevision() {
        return prefs.getInt(PREF_FUEL_REVISION, 0);
    }

    public float getRemainingPercent() {
        float cap = getTankCapacityL();
        if (cap <= 0f) return 100f;
        return Math.max(0f, Math.min(100f, (getRemainingLitres() / cap) * 100f));
    }

    public double getEstimatedRangeKm(double avgMileageKmL) {
        double mileage = (avgMileageKmL > 5.0 && avgMileageKmL < 120.0)
                ? avgMileageKmL : DEFAULT_ESTIMATED_MILEAGE_KML;
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
     * Batch-integrated fuel consumption. Called by the service at its flush
     * interval (typically every 30 seconds), never per BLE packet.
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
     * Logs a refuel. A full fill resets the tank to capacity; a partial fill
     * adds the litres to the current estimate.
     */
    public synchronized void logRefuel(double litresAdded, double pricePerLitre,
                                       boolean isFull, double currentOdo, String notes) {
        float cap = getTankCapacityL();
        float current = getRemainingLitres();
        double litresForLog = litresAdded;
        if (litresForLog <= 0) {
            litresForLog = isFull ? Math.max(0, cap - current) : 0;
        }
        float newRemaining;
        if (isFull) {
            newRemaining = cap;
        } else {
            newRemaining = Math.min(cap, (float) (current + Math.max(0, litresAdded)));
        }

        SharedPreferences.Editor editor = prefs.edit()
                .putFloat(PREF_FUEL_REMAINING, newRemaining)
                .putInt(PREF_FUEL_REVISION, getRevision() + 1)
                .putLong(PREF_LAST_REFUEL_TIME, System.currentTimeMillis())
                .putFloat(PREF_LAST_REFUEL_ODO, (float) currentOdo);
        if (isFull) {
            editor.putFloat(PREF_FUEL_CONSUMED_SINCE_REFUEL, 0.0f);
        }
        editor.apply();

        db.insertFuelLog(System.currentTimeMillis(), currentOdo, litresForLog,
                pricePerLitre, isFull, notes);
        cachedActualMileage = -1.0;
    }

    public synchronized void resetToFull(double currentOdo) {
        logRefuel(getTankCapacityL(), 0.0, true, currentOdo, null);
    }

    /** Real tank-to-tank mileage, or 0 if there are fewer than two full fills. */
    public synchronized double getActualMileageKmL() {
        if (cachedActualMileage < 0) {
            cachedActualMileage = db.calculateActualMileageKmL();
        }
        return cachedActualMileage;
    }

    public synchronized void invalidateMileageCache() {
        cachedActualMileage = -1.0;
    }

    /** Prefer real fills, fall back to the model/default. */
    public double getBestMileageKmL(double tripAverage) {
        double actual = getActualMileageKmL();
        if (actual > 5.0 && actual < 120.0) return actual;
        if (tripAverage > 5.0 && tripAverage < 120.0) return tripAverage;
        return DEFAULT_ESTIMATED_MILEAGE_KML;
    }

    public double getLifetimeCost() {
        double cost = 0;
        for (RideDatabaseHelper.FuelLogEntry e : db.getFuelLog(0)) cost += e.totalCost;
        return cost;
    }
}
