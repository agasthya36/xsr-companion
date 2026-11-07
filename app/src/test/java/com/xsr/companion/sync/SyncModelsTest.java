package com.xsr.companion.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.xsr.companion.data.RideDatabaseHelper;

import org.json.JSONObject;
import org.junit.Test;

public class SyncModelsTest {

    @Test
    public void rideSerializationDoesNotLeakGpxOrPoints() throws Exception {
        RideDatabaseHelper.RideSummary r = new RideDatabaseHelper.RideSummary();
        r.id = 42;
        r.uuid = "550e8400-e29b-41d4-a716-446655440000";
        r.startTime = 1700000000000L;
        r.endTime = 1700003600000L;
        r.durationSec = 3600;
        r.movingSec = 3200;
        r.distanceKm = 45.2;
        r.topSpeedKmh = 110;
        r.avgSpeedKmh = 50.85;
        r.maxRpm = 9500;
        r.avgRpm = 6200;
        r.maxCoolant = 88;
        r.startOdoKm = 1000.0;
        r.endOdoKm = 1045.2;
        r.fuelUsedL = 0.95;
        r.avgMileageKmL = 47.58;
        r.gpxPath = "/storage/emulated/0/Android/data/com.xsr.companion/files/rides/xsr_ride_20261107.gpx";
        r.notes = "Morning twisties ride";
        r.updatedAt = 1700003600000L;
        r.deleted = false;
        r.dirty = true;

        JSONObject json = SyncModels.rideToJson(r);

        // Required fields
        assertEquals(r.uuid, json.getString("id"));
        assertEquals(r.startTime, json.getLong("start_time"));
        assertEquals(r.endTime, json.getLong("end_time"));
        assertEquals(45.2, json.getDouble("distance_km"), 1e-6);
        assertEquals("Morning twisties ride", json.getString("notes"));
        assertFalse(json.getBoolean("deleted"));

        // Privacy guarantee: MUST NOT contain local database ID, GPX path or GPS points
        assertFalse("JSON must not contain local database ID", json.has("_id"));
        assertFalse("JSON must not leak gpxPath", json.has("gpx_path"));
        assertFalse("JSON must not contain gpxPath", json.has("gpxPath"));
        assertFalse("JSON must not contain latitude", json.has("latitude"));
        assertFalse("JSON must not contain longitude", json.has("longitude"));
        assertFalse("JSON must not contain points", json.has("points"));
        assertFalse("JSON must not contain user_id", json.has("user_id"));
    }

    @Test
    public void rideDeserializationHandlesNullGpxAndDefaults() throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", "test-uuid-1234");
        json.put("start_time", 1000L);
        json.put("end_time", 2000L);
        json.put("duration_sec", 1000);
        json.put("distance_km", 12.5);
        json.put("notes", JSONObject.NULL);
        json.put("updated_at", 2000L);
        json.put("deleted", false);

        RideDatabaseHelper.RideSummary r = SyncModels.rideFromJson(json);

        assertEquals("test-uuid-1234", r.uuid);
        assertEquals(1000L, r.startTime);
        assertEquals(2000L, r.endTime);
        assertEquals(12.5, r.distanceKm, 1e-6);
        assertNull(r.notes);
        assertNull(r.gpxPath);
        assertFalse(r.deleted);
        assertFalse(r.dirty);
    }

    @Test
    public void fuelLogRoundTrip() throws Exception {
        RideDatabaseHelper.FuelLogEntry entry = new RideDatabaseHelper.FuelLogEntry();
        entry.uuid = "fuel-uuid-1";
        entry.timestamp = 1700000000000L;
        entry.odometerKm = 5230.5;
        entry.litres = 8.4;
        entry.pricePerLitre = 104.5;
        entry.totalCost = 877.8;
        entry.isFull = true;
        entry.notes = "HP Petrol bunk";
        entry.updatedAt = 1700000000000L;
        entry.deleted = false;

        JSONObject json = SyncModels.fuelToJson(entry);
        assertEquals("fuel-uuid-1", json.getString("id"));
        assertTrue(json.getBoolean("is_full"));

        RideDatabaseHelper.FuelLogEntry parsed = SyncModels.fuelFromJson(json);
        assertEquals(entry.uuid, parsed.uuid);
        assertEquals(entry.timestamp, parsed.timestamp);
        assertEquals(entry.odometerKm, parsed.odometerKm, 1e-6);
        assertEquals(entry.litres, parsed.litres, 1e-6);
        assertEquals(entry.pricePerLitre, parsed.pricePerLitre, 1e-6);
        assertEquals(entry.totalCost, parsed.totalCost, 1e-6);
        assertTrue(parsed.isFull);
        assertEquals("HP Petrol bunk", parsed.notes);
        assertFalse(parsed.deleted);
    }

    @Test
    public void serviceLogRoundTrip() throws Exception {
        RideDatabaseHelper.ServiceLogEntry entry = new RideDatabaseHelper.ServiceLogEntry();
        entry.uuid = "svc-uuid-1";
        entry.itemId = "chain_lube";
        entry.timestamp = 1700001000000L;
        entry.odometerKm = 1500.0;
        entry.notes = "Cleaned with Motul chain cleaner";
        entry.cost = 250.0;
        entry.updatedAt = 1700001000000L;
        entry.deleted = false;

        JSONObject json = SyncModels.serviceToJson(entry);
        assertEquals("chain_lube", json.getString("item_id"));

        RideDatabaseHelper.ServiceLogEntry parsed = SyncModels.serviceFromJson(json);
        assertEquals(entry.uuid, parsed.uuid);
        assertEquals(entry.itemId, parsed.itemId);
        assertEquals(entry.timestamp, parsed.timestamp);
        assertEquals(entry.odometerKm, parsed.odometerKm, 1e-6);
        assertEquals(entry.cost, parsed.cost, 1e-6);
        assertEquals("Cleaned with Motul chain cleaner", parsed.notes);
    }

    @Test
    public void dtcLogRoundTrip() throws Exception {
        RideDatabaseHelper.DtcLogEntry entry = new RideDatabaseHelper.DtcLogEntry();
        entry.uuid = "dtc-uuid-1";
        entry.timestamp = 1700002000000L;
        entry.code = 12;
        entry.title = "Crankshaft position sensor";
        entry.severity = "HIGH";
        entry.odometerKm = 2300.0;
        entry.updatedAt = 1700002000000L;
        entry.deleted = false;

        JSONObject json = SyncModels.dtcToJson(entry);
        assertEquals(12, json.getInt("code"));

        RideDatabaseHelper.DtcLogEntry parsed = SyncModels.dtcFromJson(json);
        assertEquals(entry.uuid, parsed.uuid);
        assertEquals(12, parsed.code);
        assertEquals("Crankshaft position sensor", parsed.title);
        assertEquals("HIGH", parsed.severity);
    }

    @Test
    public void bikeStateRoundTrip() throws Exception {
        SyncModels.BikeState b = new SyncModels.BikeState();
        b.currentOdometerKm = 5432.1;
        b.fuelRemainingL = 7.5;
        b.fuelConsumedSinceRefuelL = 2.5;
        b.fuelLifetimeConsumedL = 120.0;
        b.fuelLastRefuelTime = 1700000000000L;
        b.fuelLastRefuelOdo = 5300.0;
        b.maintenanceBaselined = true;
        b.updatedAt = 1700005000000L;

        JSONObject json = SyncModels.bikeStateToJson(b);
        assertEquals(5432.1, json.getDouble("current_odometer_km"), 1e-6);

        SyncModels.BikeState parsed = SyncModels.bikeStateFromJson(json);
        assertEquals(b.currentOdometerKm, parsed.currentOdometerKm, 1e-6);
        assertEquals(b.fuelRemainingL, parsed.fuelRemainingL, 1e-6);
        assertEquals(b.fuelConsumedSinceRefuelL, parsed.fuelConsumedSinceRefuelL, 1e-6);
        assertEquals(b.fuelLifetimeConsumedL, parsed.fuelLifetimeConsumedL, 1e-6);
        assertEquals(b.fuelLastRefuelTime, parsed.fuelLastRefuelTime);
        assertEquals(b.fuelLastRefuelOdo, parsed.fuelLastRefuelOdo, 1e-6);
        assertTrue(parsed.maintenanceBaselined);
        assertEquals(b.updatedAt, parsed.updatedAt);
    }
}
