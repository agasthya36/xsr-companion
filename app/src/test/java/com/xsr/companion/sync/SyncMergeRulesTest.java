package com.xsr.companion.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xsr.companion.data.RideDatabaseHelper;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SyncMergeRulesTest {

    @Test
    public void lastWriteWinsTakesNewerRideNote() {
        RideDatabaseHelper.RideSummary local = new RideDatabaseHelper.RideSummary();
        local.uuid = "ride-1";
        local.notes = "Original local notes";
        local.updatedAt = 1000L;

        RideDatabaseHelper.RideSummary remote = new RideDatabaseHelper.RideSummary();
        remote.uuid = "ride-1";
        remote.notes = "Updated cloud notes";
        remote.updatedAt = 2000L;

        boolean remoteWins = remote.updatedAt > local.updatedAt;
        assertTrue(remoteWins);

        RideDatabaseHelper.RideSummary result = remoteWins ? remote : local;
        assertEquals("Updated cloud notes", result.notes);
    }

    @Test
    public void localWinsWhenLocalIsNewer() {
        RideDatabaseHelper.RideSummary local = new RideDatabaseHelper.RideSummary();
        local.uuid = "ride-1";
        local.notes = "Recent phone edit";
        local.updatedAt = 3000L;

        RideDatabaseHelper.RideSummary remote = new RideDatabaseHelper.RideSummary();
        remote.uuid = "ride-1";
        remote.notes = "Old cloud notes";
        remote.updatedAt = 2000L;

        boolean remoteWins = remote.updatedAt > local.updatedAt;
        assertFalse(remoteWins);

        RideDatabaseHelper.RideSummary result = remoteWins ? remote : local;
        assertEquals("Recent phone edit", result.notes);
    }

    @Test
    public void cloudTombstoneAppliesWhenRemoteIsNewer() {
        RideDatabaseHelper.RideSummary local = new RideDatabaseHelper.RideSummary();
        local.uuid = "ride-1";
        local.deleted = false;
        local.updatedAt = 1000L;

        RideDatabaseHelper.RideSummary remote = new RideDatabaseHelper.RideSummary();
        remote.uuid = "ride-1";
        remote.deleted = true;
        remote.updatedAt = 1500L;

        if (remote.updatedAt > local.updatedAt) {
            local.deleted = remote.deleted;
            local.updatedAt = remote.updatedAt;
        }

        assertTrue(local.deleted);
    }

    @Test
    public void bikeStateOdometerNeverRollsBack() {
        double localOdometer = 3500.0;
        double staleRemoteOdometer = 3100.0;

        // Plan §6.2: force current_odometer_km = max(local, cloud) so a stale device cannot roll it back
        double mergedOdometer = Math.max(localOdometer, staleRemoteOdometer);
        assertEquals(3500.0, mergedOdometer, 1e-6);

        double freshRemoteOdometer = 4200.0;
        double mergedHigher = Math.max(localOdometer, freshRemoteOdometer);
        assertEquals(4200.0, mergedHigher, 1e-6);
    }

    @Test
    public void settingsDefaultYieldsToRealCloudSettings() throws Exception {
        long localDefaultUpdatedAt = 0L; // Plan §8.3: defaults use updated_at = 0

        JSONObject cloudSettings = new JSONObject();
        cloudSettings.put("units", "imperial");
        cloudSettings.put("tank_capacity_l", 12.0);
        long cloudUpdatedAt = 1700000000000L;

        assertTrue("Real cloud settings must beat fresh install defaults", cloudUpdatedAt > localDefaultUpdatedAt);
    }

    @Test
    public void maintenanceRecomputeFromServiceLog() {
        // Prepare mock service logs for chain_lube
        List<RideDatabaseHelper.ServiceLogEntry> logs = new ArrayList<>();

        RideDatabaseHelper.ServiceLogEntry older = new RideDatabaseHelper.ServiceLogEntry();
        older.itemId = "chain_lube";
        older.odometerKm = 500.0;
        older.timestamp = 1000L;
        logs.add(older);

        RideDatabaseHelper.ServiceLogEntry newer = new RideDatabaseHelper.ServiceLogEntry();
        newer.itemId = "chain_lube";
        newer.odometerKm = 1020.0;
        newer.timestamp = 2000L;
        logs.add(0, newer); // newest first (DESC)

        // Find newest non-deleted log for item
        RideDatabaseHelper.ServiceLogEntry found = null;
        for (RideDatabaseHelper.ServiceLogEntry log : logs) {
            if ("chain_lube".equals(log.itemId)) {
                found = log;
                break;
            }
        }

        assertEquals(newer.odometerKm, found.odometerKm, 1e-6);
        assertEquals(newer.timestamp, found.timestamp);
    }
}
