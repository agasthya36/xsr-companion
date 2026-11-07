package com.xsr.companion.sync;

import com.xsr.companion.data.RideDatabaseHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Data Transfer Objects and JSON converters for Supabase PostgREST sync.
 */
public final class SyncModels {

    private SyncModels() {}

    public static JSONObject rideToJson(RideDatabaseHelper.RideSummary r) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", r.uuid);
        obj.put("start_time", r.startTime);
        obj.put("end_time", r.endTime);
        obj.put("duration_sec", r.durationSec);
        obj.put("moving_sec", r.movingSec);
        obj.put("distance_km", r.distanceKm);
        obj.put("top_speed_kmh", r.topSpeedKmh);
        obj.put("avg_speed_kmh", r.avgSpeedKmh);
        obj.put("max_rpm", r.maxRpm);
        obj.put("avg_rpm", r.avgRpm);
        obj.put("max_coolant", r.maxCoolant);
        obj.put("start_odo_km", r.startOdoKm);
        obj.put("end_odo_km", r.endOdoKm);
        obj.put("fuel_used_l", r.fuelUsedL);
        obj.put("avg_mileage_kml", r.avgMileageKmL);
        if (r.notes != null) obj.put("notes", r.notes);
        else obj.put("notes", JSONObject.NULL);
        obj.put("updated_at", r.updatedAt);
        obj.put("deleted", r.deleted);
        return obj;
    }

    public static RideDatabaseHelper.RideSummary rideFromJson(JSONObject obj) {
        RideDatabaseHelper.RideSummary r = new RideDatabaseHelper.RideSummary();
        r.uuid = obj.optString("id", null);
        r.startTime = obj.optLong("start_time", 0L);
        r.endTime = obj.optLong("end_time", 0L);
        r.durationSec = obj.optInt("duration_sec", 0);
        r.movingSec = obj.optInt("moving_sec", 0);
        r.distanceKm = obj.optDouble("distance_km", 0.0);
        r.topSpeedKmh = obj.optInt("top_speed_kmh", 0);
        r.avgSpeedKmh = obj.optDouble("avg_speed_kmh", 0.0);
        r.maxRpm = obj.optInt("max_rpm", 0);
        r.avgRpm = obj.optInt("avg_rpm", 0);
        r.maxCoolant = obj.optInt("max_coolant", 0);
        r.startOdoKm = obj.optDouble("start_odo_km", 0.0);
        r.endOdoKm = obj.optDouble("end_odo_km", 0.0);
        r.fuelUsedL = obj.optDouble("fuel_used_l", 0.0);
        r.avgMileageKmL = obj.optDouble("avg_mileage_kml", 0.0);
        r.notes = obj.isNull("notes") ? null : obj.optString("notes", null);
        r.updatedAt = obj.optLong("updated_at", 0L);
        r.deleted = obj.optBoolean("deleted", false);
        r.dirty = false;
        return r;
    }

    public static JSONObject fuelToJson(RideDatabaseHelper.FuelLogEntry f) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", f.uuid);
        obj.put("timestamp", f.timestamp);
        obj.put("odometer_km", f.odometerKm);
        obj.put("litres", f.litres);
        obj.put("price_per_l", f.pricePerLitre);
        obj.put("total_cost", f.totalCost);
        obj.put("is_full", f.isFull);
        if (f.notes != null) obj.put("notes", f.notes);
        else obj.put("notes", JSONObject.NULL);
        obj.put("updated_at", f.updatedAt);
        obj.put("deleted", f.deleted);
        return obj;
    }

    public static RideDatabaseHelper.FuelLogEntry fuelFromJson(JSONObject obj) {
        RideDatabaseHelper.FuelLogEntry f = new RideDatabaseHelper.FuelLogEntry();
        f.uuid = obj.optString("id", null);
        f.timestamp = obj.optLong("timestamp", 0L);
        f.odometerKm = obj.optDouble("odometer_km", 0.0);
        f.litres = obj.optDouble("litres", 0.0);
        f.pricePerLitre = obj.optDouble("price_per_l", 0.0);
        f.totalCost = obj.optDouble("total_cost", 0.0);
        f.isFull = obj.optBoolean("is_full", false);
        f.notes = obj.isNull("notes") ? null : obj.optString("notes", null);
        f.updatedAt = obj.optLong("updated_at", 0L);
        f.deleted = obj.optBoolean("deleted", false);
        f.dirty = false;
        return f;
    }

    public static JSONObject serviceToJson(RideDatabaseHelper.ServiceLogEntry s) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", s.uuid);
        obj.put("item_id", s.itemId);
        obj.put("timestamp", s.timestamp);
        obj.put("odometer_km", s.odometerKm);
        if (s.notes != null) obj.put("notes", s.notes);
        else obj.put("notes", JSONObject.NULL);
        obj.put("cost", s.cost);
        obj.put("updated_at", s.updatedAt);
        obj.put("deleted", s.deleted);
        return obj;
    }

    public static RideDatabaseHelper.ServiceLogEntry serviceFromJson(JSONObject obj) {
        RideDatabaseHelper.ServiceLogEntry s = new RideDatabaseHelper.ServiceLogEntry();
        s.uuid = obj.optString("id", null);
        s.itemId = obj.optString("item_id", "");
        s.timestamp = obj.optLong("timestamp", 0L);
        s.odometerKm = obj.optDouble("odometer_km", 0.0);
        s.notes = obj.isNull("notes") ? null : obj.optString("notes", null);
        s.cost = obj.optDouble("cost", 0.0);
        s.updatedAt = obj.optLong("updated_at", 0L);
        s.deleted = obj.optBoolean("deleted", false);
        s.dirty = false;
        return s;
    }

    public static JSONObject dtcToJson(RideDatabaseHelper.DtcLogEntry d) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", d.uuid);
        obj.put("timestamp", d.timestamp);
        obj.put("code", d.code);
        if (d.title != null) obj.put("title", d.title);
        else obj.put("title", JSONObject.NULL);
        if (d.severity != null) obj.put("severity", d.severity);
        else obj.put("severity", JSONObject.NULL);
        obj.put("odometer_km", d.odometerKm);
        obj.put("updated_at", d.updatedAt);
        obj.put("deleted", d.deleted);
        return obj;
    }

    public static RideDatabaseHelper.DtcLogEntry dtcFromJson(JSONObject obj) {
        RideDatabaseHelper.DtcLogEntry d = new RideDatabaseHelper.DtcLogEntry();
        d.uuid = obj.optString("id", null);
        d.timestamp = obj.optLong("timestamp", 0L);
        d.code = obj.optInt("code", 0);
        d.title = obj.isNull("title") ? null : obj.optString("title", null);
        d.severity = obj.isNull("severity") ? null : obj.optString("severity", null);
        d.odometerKm = obj.optDouble("odometer_km", 0.0);
        d.updatedAt = obj.optLong("updated_at", 0L);
        d.deleted = obj.optBoolean("deleted", false);
        d.dirty = false;
        return d;
    }

    public static class BikeState {
        public double currentOdometerKm;
        public Double fuelRemainingL;
        public Double fuelConsumedSinceRefuelL;
        public Double fuelLifetimeConsumedL;
        public Long fuelLastRefuelTime;
        public Double fuelLastRefuelOdo;
        public boolean maintenanceBaselined;
        public long updatedAt;
    }

    public static JSONObject bikeStateToJson(BikeState b) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("current_odometer_km", b.currentOdometerKm);
        if (b.fuelRemainingL != null) obj.put("fuel_remaining_l", b.fuelRemainingL);
        if (b.fuelConsumedSinceRefuelL != null) obj.put("fuel_consumed_since_refuel_l", b.fuelConsumedSinceRefuelL);
        if (b.fuelLifetimeConsumedL != null) obj.put("fuel_lifetime_consumed_l", b.fuelLifetimeConsumedL);
        if (b.fuelLastRefuelTime != null) obj.put("fuel_last_refuel_time", b.fuelLastRefuelTime);
        if (b.fuelLastRefuelOdo != null) obj.put("fuel_last_refuel_odo", b.fuelLastRefuelOdo);
        obj.put("maintenance_baselined", b.maintenanceBaselined);
        obj.put("updated_at", b.updatedAt);
        return obj;
    }

    public static BikeState bikeStateFromJson(JSONObject obj) {
        BikeState b = new BikeState();
        b.currentOdometerKm = obj.optDouble("current_odometer_km", 0.0);
        if (!obj.isNull("fuel_remaining_l")) b.fuelRemainingL = obj.optDouble("fuel_remaining_l");
        if (!obj.isNull("fuel_consumed_since_refuel_l")) b.fuelConsumedSinceRefuelL = obj.optDouble("fuel_consumed_since_refuel_l");
        if (!obj.isNull("fuel_lifetime_consumed_l")) b.fuelLifetimeConsumedL = obj.optDouble("fuel_lifetime_consumed_l");
        if (!obj.isNull("fuel_last_refuel_time")) b.fuelLastRefuelTime = obj.optLong("fuel_last_refuel_time");
        if (!obj.isNull("fuel_last_refuel_odo")) b.fuelLastRefuelOdo = obj.optDouble("fuel_last_refuel_odo");
        b.maintenanceBaselined = obj.optBoolean("maintenance_baselined", false);
        b.updatedAt = obj.optLong("updated_at", 0L);
        return b;
    }

    public static class MaintenanceItemState {
        public String itemId;
        public double lastServiceOdoKm;
        public long lastServiceTime;
        public int intervalKm;
        public long updatedAt;

        public MaintenanceItemState(String itemId, double lastServiceOdoKm, long lastServiceTime,
                                    int intervalKm, long updatedAt) {
            this.itemId = itemId;
            this.lastServiceOdoKm = lastServiceOdoKm;
            this.lastServiceTime = lastServiceTime;
            this.intervalKm = intervalKm;
            this.updatedAt = updatedAt;
        }
    }

    public static JSONObject maintenanceItemToJson(MaintenanceItemState m) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("item_id", m.itemId);
        obj.put("last_service_odo_km", m.lastServiceOdoKm);
        obj.put("last_service_time", m.lastServiceTime);
        obj.put("interval_km", m.intervalKm);
        obj.put("updated_at", m.updatedAt);
        return obj;
    }

    public static MaintenanceItemState maintenanceItemFromJson(JSONObject obj) {
        String itemId = obj.optString("item_id", "");
        double lastOdo = obj.optDouble("last_service_odo_km", 0.0);
        long lastTime = obj.optLong("last_service_time", 0L);
        int interval = obj.optInt("interval_km", 500);
        long updatedAt = obj.optLong("updated_at", 0L);
        return new MaintenanceItemState(itemId, lastOdo, lastTime, interval, updatedAt);
    }
}
