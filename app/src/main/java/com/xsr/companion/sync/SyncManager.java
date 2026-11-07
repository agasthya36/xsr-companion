package com.xsr.companion.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.maintenance.FuelManager;
import com.xsr.companion.maintenance.MaintenanceManager;
import com.xsr.companion.settings.SettingsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates cloud sync: pull-merge-push on first login / restore,
 * and push-only on routine daily runs.
 */
public class SyncManager {

    private static final int BATCH_SIZE = 100;
    private static final int PAGE_LIMIT = 1000;

    public static class SyncResult {
        public final boolean success;
        public final int restoredRides;
        public final int restoredFuel;
        public final int restoredService;
        public final int restoredDtc;
        public final int uploadedRides;
        public final int uploadedFuel;
        public final int uploadedService;
        public final int uploadedDtc;
        public final String errorMessage;

        public SyncResult(boolean success, int restoredRides, int restoredFuel,
                          int restoredService, int restoredDtc, int uploadedRides,
                          int uploadedFuel, int uploadedService, int uploadedDtc,
                          String errorMessage) {
            this.success = success;
            this.restoredRides = restoredRides;
            this.restoredFuel = restoredFuel;
            this.restoredService = restoredService;
            this.restoredDtc = restoredDtc;
            this.uploadedRides = uploadedRides;
            this.uploadedFuel = uploadedFuel;
            this.uploadedService = uploadedService;
            this.uploadedDtc = uploadedDtc;
            this.errorMessage = errorMessage;
        }

        public String getSummaryMessage() {
            if (!success) return errorMessage != null ? errorMessage : "Sync failed";
            return String.format("Restored: %d rides, %d fuel logs. Uploaded: %d rides, %d fuel logs.",
                    restoredRides, restoredFuel, uploadedRides, uploadedFuel);
        }
    }

    public interface SyncCallback {
        void onComplete(SyncResult result);
    }

    private static volatile SyncManager instance;

    private final Context context;
    private final AuthManager auth;
    private final RideDatabaseHelper db;
    private final MaintenanceManager maintenance;
    private final FuelManager fuel;
    private final SettingsManager settings;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SyncManager(Context context) {
        this.context = context.getApplicationContext();
        this.auth = AuthManager.getInstance(this.context);
        this.db = RideDatabaseHelper.getInstance(this.context);
        this.maintenance = new MaintenanceManager(this.context);
        this.fuel = new FuelManager(this.context);
        this.settings = SettingsManager.get(this.context);
    }

    public static SyncManager getInstance(Context context) {
        if (instance == null) {
            synchronized (SyncManager.class) {
                if (instance == null) {
                    instance = new SyncManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * First login or manual "Restore from cloud": Pull -> Merge -> Push.
     */
    public void firstSync(SyncCallback callback) {
        executor.execute(() -> {
            SyncResult result = executeFirstSync();
            if (callback != null) {
                mainHandler.post(() -> callback.onComplete(result));
            }
        });
    }

    /**
     * Daily background job or manual "Sync now": Push-only dirty rows and state.
     */
    public void syncNow(SyncCallback callback) {
        executor.execute(() -> {
            SyncResult result = executeRoutinePushSync();
            if (callback != null) {
                mainHandler.post(() -> callback.onComplete(result));
            }
        });
    }

    public synchronized SyncResult executeFirstSync() {
        if (!auth.isSignedIn()) {
            return new SyncResult(false, 0, 0, 0, 0, 0, 0, 0, 0, "Not signed in");
        }

        db.ensureUuids();
        SupabaseClient client = auth.getClient();

        try {
            // 1. Pull all tables
            List<RideDatabaseHelper.RideSummary> remoteRides = pullAll(client, "/rest/v1/rides?select=*",
                    SyncModels::rideFromJson);
            List<RideDatabaseHelper.FuelLogEntry> remoteFuel = pullAll(client, "/rest/v1/fuel_log?select=*",
                    SyncModels::fuelFromJson);
            List<RideDatabaseHelper.ServiceLogEntry> remoteService = pullAll(client, "/rest/v1/service_log?select=*",
                    SyncModels::serviceFromJson);
            List<RideDatabaseHelper.DtcLogEntry> remoteDtc = pullAll(client, "/rest/v1/dtc_log?select=*",
                    SyncModels::dtcFromJson);

            JSONObject remoteBikeState = pullSingle(client, "/rest/v1/bike_state?select=*");
            List<SyncModels.MaintenanceItemState> remoteMaintenance = pullAll(client, "/rest/v1/maintenance_state?select=*",
                    SyncModels::maintenanceItemFromJson);
            JSONObject remoteSettings = pullSingle(client, "/rest/v1/user_settings?select=*");

            // 2. Apply remote items to local database
            int restoredRides = 0;
            for (RideDatabaseHelper.RideSummary r : remoteRides) {
                if (db.applyRemoteRide(r)) restoredRides++;
            }

            int restoredFuel = 0;
            for (RideDatabaseHelper.FuelLogEntry f : remoteFuel) {
                if (db.applyRemoteFuel(f)) restoredFuel++;
            }

            int restoredService = 0;
            for (RideDatabaseHelper.ServiceLogEntry s : remoteService) {
                if (db.applyRemoteService(s)) restoredService++;
            }

            int restoredDtc = 0;
            for (RideDatabaseHelper.DtcLogEntry d : remoteDtc) {
                if (db.applyRemoteDtc(d)) restoredDtc++;
            }

            // 3. Maintenance recompute (§6.3)
            recomputeMaintenanceFromServiceLogs(remoteMaintenance);

            // 4. Merge bike state (§6.2)
            mergeBikeState(remoteBikeState);

            // 5. Merge user settings (§6.2)
            mergeUserSettings(remoteSettings);

            // 6. Push local dirty rows and state rows
            int uploadedRides = pushDirtyRides(client);
            int uploadedFuel = pushDirtyFuel(client);
            int uploadedService = pushDirtyService(client);
            int uploadedDtc = pushDirtyDtc(client);

            pushStateRows(client);

            long now = System.currentTimeMillis();
            auth.setLastSyncAt(now);
            auth.setFirstSyncDone(true);
            auth.setLastSyncResult("SUCCESS", null);

            return new SyncResult(true, restoredRides, restoredFuel, restoredService, restoredDtc,
                    uploadedRides, uploadedFuel, uploadedService, uploadedDtc, null);
        } catch (Exception e) {
            String err = e.getMessage() != null ? e.getMessage() : "Sync failed";
            auth.setLastSyncResult("FAILED", err);
            return new SyncResult(false, 0, 0, 0, 0, 0, 0, 0, 0, err);
        }
    }

    public synchronized SyncResult executeRoutinePushSync() {
        if (!auth.isSignedIn()) {
            return new SyncResult(true, 0, 0, 0, 0, 0, 0, 0, 0, null);
        }

        SupabaseClient client = auth.getClient();
        try {
            int uploadedRides = pushDirtyRides(client);
            int uploadedFuel = pushDirtyFuel(client);
            int uploadedService = pushDirtyService(client);
            int uploadedDtc = pushDirtyDtc(client);

            pushStateRows(client);

            long now = System.currentTimeMillis();
            auth.setLastSyncAt(now);
            auth.setLastSyncResult("SUCCESS", null);

            return new SyncResult(true, 0, 0, 0, 0, uploadedRides, uploadedFuel, uploadedService, uploadedDtc, null);
        } catch (Exception e) {
            String err = e.getMessage() != null ? e.getMessage() : "Sync failed";
            auth.setLastSyncResult("FAILED", err);
            return new SyncResult(false, 0, 0, 0, 0, 0, 0, 0, 0, err);
        }
    }

    private void recomputeMaintenanceFromServiceLogs(List<SyncModels.MaintenanceItemState> remoteMaintenance) {
        Map<String, SyncModels.MaintenanceItemState> remoteMap = new HashMap<>();
        if (remoteMaintenance != null) {
            for (SyncModels.MaintenanceItemState item : remoteMaintenance) {
                remoteMap.put(item.itemId, item);
            }
        }

        List<RideDatabaseHelper.ServiceLogEntry> allLogs = db.getServiceLog(0); // non-deleted, DESC
        List<MaintenanceManager.ServiceItem> items = maintenance.getItems();

        for (MaintenanceManager.ServiceItem item : items) {
            RideDatabaseHelper.ServiceLogEntry newest = null;
            for (RideDatabaseHelper.ServiceLogEntry log : allLogs) {
                if (item.id.equals(log.itemId) || "reset".equals(log.itemId)) {
                    newest = log;
                    break;
                }
            }

            SyncModels.MaintenanceItemState remoteState = remoteMap.get(item.id);
            int interval = remoteState != null && remoteState.intervalKm > 0
                    ? remoteState.intervalKm : item.intervalKm;

            if (newest != null) {
                maintenance.applyMaintenanceState(item.id, newest.odometerKm, newest.timestamp,
                        interval, newest.timestamp);
            } else if (remoteState != null && remoteState.updatedAt > maintenance.getItemUpdatedAt(item.id)) {
                maintenance.applyMaintenanceState(item.id, remoteState.lastServiceOdoKm,
                        remoteState.lastServiceTime, remoteState.intervalKm, remoteState.updatedAt);
            }
        }
    }

    private void mergeBikeState(JSONObject remoteBikeJson) {
        if (remoteBikeJson == null) return;
        SyncModels.BikeState remote = SyncModels.bikeStateFromJson(remoteBikeJson);

        long localUpdatedAt = Math.max(fuel.getFuelUpdatedAt(), maintenance.getOdometerUpdatedAt());
        if (remote.updatedAt > localUpdatedAt) {
            fuel.applyFuelState(remote.fuelRemainingL, remote.fuelConsumedSinceRefuelL,
                    remote.fuelLifetimeConsumedL, remote.fuelLastRefuelTime,
                    remote.fuelLastRefuelOdo, remote.updatedAt);

            if (remote.maintenanceBaselined) {
                settings.setMaintenanceBaselined();
            } else {
                settings.clearMaintenanceBaselined();
            }
        }

        // Always protect odometer from rolling back
        maintenance.applyOdometer(remote.currentOdometerKm, Math.max(localUpdatedAt, remote.updatedAt));
    }

    private void mergeUserSettings(JSONObject remoteSettingsJson) {
        if (remoteSettingsJson == null) return;
        long remoteUpdatedAt = remoteSettingsJson.optLong("updated_at", 0L);
        JSONObject data = remoteSettingsJson.optJSONObject("data");
        if (data != null && remoteUpdatedAt > settings.getSettingsUpdatedAt()) {
            settings.applySnapshot(data, remoteUpdatedAt);
        }
    }

    // ---------------------------------------------------------------------
    // Push Helpers
    // ---------------------------------------------------------------------

    private int pushDirtyRides(SupabaseClient client) throws Exception {
        List<RideDatabaseHelper.RideSummary> dirty = db.getDirtyRides();
        if (dirty.isEmpty()) return 0;
        int pushed = 0;
        for (int i = 0; i < dirty.size(); i += BATCH_SIZE) {
            List<RideDatabaseHelper.RideSummary> batch = dirty.subList(i, Math.min(i + BATCH_SIZE, dirty.size()));
            JSONArray arr = new JSONArray();
            for (RideDatabaseHelper.RideSummary r : batch) {
                arr.put(SyncModels.rideToJson(r));
            }
            Map<String, String> headers = defaultPushHeaders();
            SupabaseClient.HttpResponse resp = client.post("/rest/v1/rides?on_conflict=id", arr.toString(), headers);
            if (resp.isSuccess()) {
                for (RideDatabaseHelper.RideSummary r : batch) {
                    db.markClean(RideDatabaseHelper.TABLE_RIDES, r.uuid);
                    pushed++;
                }
            } else if (resp.statusCode >= 400 && resp.statusCode < 500) {
                // Retry row by row to isolate bad rows (§7.5)
                for (RideDatabaseHelper.RideSummary r : batch) {
                    JSONArray single = new JSONArray().put(SyncModels.rideToJson(r));
                    SupabaseClient.HttpResponse singleResp = client.post(
                            "/rest/v1/rides?on_conflict=id", single.toString(), headers);
                    if (singleResp.isSuccess()) {
                        db.markClean(RideDatabaseHelper.TABLE_RIDES, r.uuid);
                        pushed++;
                    }
                }
            } else {
                throw new Exception("Push rides failed: " + resp.getErrorMessage());
            }
        }
        return pushed;
    }

    private int pushDirtyFuel(SupabaseClient client) throws Exception {
        List<RideDatabaseHelper.FuelLogEntry> dirty = db.getDirtyFuel();
        if (dirty.isEmpty()) return 0;
        int pushed = 0;
        for (int i = 0; i < dirty.size(); i += BATCH_SIZE) {
            List<RideDatabaseHelper.FuelLogEntry> batch = dirty.subList(i, Math.min(i + BATCH_SIZE, dirty.size()));
            JSONArray arr = new JSONArray();
            for (RideDatabaseHelper.FuelLogEntry f : batch) {
                arr.put(SyncModels.fuelToJson(f));
            }
            Map<String, String> headers = defaultPushHeaders();
            SupabaseClient.HttpResponse resp = client.post("/rest/v1/fuel_log?on_conflict=id", arr.toString(), headers);
            if (resp.isSuccess()) {
                for (RideDatabaseHelper.FuelLogEntry f : batch) {
                    db.markClean(RideDatabaseHelper.TABLE_FUEL, f.uuid);
                    pushed++;
                }
            } else if (resp.statusCode >= 400 && resp.statusCode < 500) {
                for (RideDatabaseHelper.FuelLogEntry f : batch) {
                    JSONArray single = new JSONArray().put(SyncModels.fuelToJson(f));
                    SupabaseClient.HttpResponse singleResp = client.post(
                            "/rest/v1/fuel_log?on_conflict=id", single.toString(), headers);
                    if (singleResp.isSuccess()) {
                        db.markClean(RideDatabaseHelper.TABLE_FUEL, f.uuid);
                        pushed++;
                    }
                }
            } else {
                throw new Exception("Push fuel failed: " + resp.getErrorMessage());
            }
        }
        return pushed;
    }

    private int pushDirtyService(SupabaseClient client) throws Exception {
        List<RideDatabaseHelper.ServiceLogEntry> dirty = db.getDirtyService();
        if (dirty.isEmpty()) return 0;
        int pushed = 0;
        for (int i = 0; i < dirty.size(); i += BATCH_SIZE) {
            List<RideDatabaseHelper.ServiceLogEntry> batch = dirty.subList(i, Math.min(i + BATCH_SIZE, dirty.size()));
            JSONArray arr = new JSONArray();
            for (RideDatabaseHelper.ServiceLogEntry s : batch) {
                arr.put(SyncModels.serviceToJson(s));
            }
            Map<String, String> headers = defaultPushHeaders();
            SupabaseClient.HttpResponse resp = client.post("/rest/v1/service_log?on_conflict=id", arr.toString(), headers);
            if (resp.isSuccess()) {
                for (RideDatabaseHelper.ServiceLogEntry s : batch) {
                    db.markClean(RideDatabaseHelper.TABLE_SERVICE, s.uuid);
                    pushed++;
                }
            } else if (resp.statusCode >= 400 && resp.statusCode < 500) {
                for (RideDatabaseHelper.ServiceLogEntry s : batch) {
                    JSONArray single = new JSONArray().put(SyncModels.serviceToJson(s));
                    SupabaseClient.HttpResponse singleResp = client.post(
                            "/rest/v1/service_log?on_conflict=id", single.toString(), headers);
                    if (singleResp.isSuccess()) {
                        db.markClean(RideDatabaseHelper.TABLE_SERVICE, s.uuid);
                        pushed++;
                    }
                }
            } else {
                throw new Exception("Push service failed: " + resp.getErrorMessage());
            }
        }
        return pushed;
    }

    private int pushDirtyDtc(SupabaseClient client) throws Exception {
        List<RideDatabaseHelper.DtcLogEntry> dirty = db.getDirtyDtc();
        if (dirty.isEmpty()) return 0;
        int pushed = 0;
        for (int i = 0; i < dirty.size(); i += BATCH_SIZE) {
            List<RideDatabaseHelper.DtcLogEntry> batch = dirty.subList(i, Math.min(i + BATCH_SIZE, dirty.size()));
            JSONArray arr = new JSONArray();
            for (RideDatabaseHelper.DtcLogEntry d : batch) {
                arr.put(SyncModels.dtcToJson(d));
            }
            Map<String, String> headers = defaultPushHeaders();
            SupabaseClient.HttpResponse resp = client.post("/rest/v1/dtc_log?on_conflict=id", arr.toString(), headers);
            if (resp.isSuccess()) {
                for (RideDatabaseHelper.DtcLogEntry d : batch) {
                    db.markClean(RideDatabaseHelper.TABLE_DTC, d.uuid);
                    pushed++;
                }
            } else if (resp.statusCode >= 400 && resp.statusCode < 500) {
                for (RideDatabaseHelper.DtcLogEntry d : batch) {
                    JSONArray single = new JSONArray().put(SyncModels.dtcToJson(d));
                    SupabaseClient.HttpResponse singleResp = client.post(
                            "/rest/v1/dtc_log?on_conflict=id", single.toString(), headers);
                    if (singleResp.isSuccess()) {
                        db.markClean(RideDatabaseHelper.TABLE_DTC, d.uuid);
                        pushed++;
                    }
                }
            } else {
                throw new Exception("Push DTC failed: " + resp.getErrorMessage());
            }
        }
        return pushed;
    }

    private void pushStateRows(SupabaseClient client) throws Exception {
        Map<String, String> headers = defaultPushHeaders();

        // 1. bike_state
        SyncModels.BikeState bikeState = new SyncModels.BikeState();
        bikeState.currentOdometerKm = maintenance.getCurrentOdometer();
        bikeState.fuelRemainingL = (double) fuel.getRemainingLitres();
        bikeState.fuelConsumedSinceRefuelL = (double) fuel.getConsumedSinceRefuelL();
        bikeState.fuelLifetimeConsumedL = (double) fuel.getLifetimeConsumedL();
        bikeState.fuelLastRefuelTime = fuel.getLastRefuelTime() > 0 ? fuel.getLastRefuelTime() : null;
        bikeState.fuelLastRefuelOdo = fuel.getLastRefuelOdo() > 0 ? fuel.getLastRefuelOdo() : null;
        bikeState.maintenanceBaselined = settings.isMaintenanceBaselined();
        long bikeUpdatedAt = Math.max(fuel.getFuelUpdatedAt(), maintenance.getOdometerUpdatedAt());
        bikeState.updatedAt = bikeUpdatedAt > 0 ? bikeUpdatedAt : System.currentTimeMillis();

        client.post("/rest/v1/bike_state?on_conflict=user_id",
                SyncModels.bikeStateToJson(bikeState).toString(), headers);

        // 2. maintenance_state (7 items)
        List<SyncModels.MaintenanceItemState> maintenanceItems = maintenance.snapshotMaintenanceState();
        JSONArray mArr = new JSONArray();
        for (SyncModels.MaintenanceItemState item : maintenanceItems) {
            mArr.put(SyncModels.maintenanceItemToJson(item));
        }
        client.post("/rest/v1/maintenance_state?on_conflict=user_id,item_id", mArr.toString(), headers);

        // 3. user_settings
        JSONObject settingsObj = new JSONObject();
        settingsObj.put("data", settings.snapshotWhitelisted());
        settingsObj.put("updated_at", settings.getSettingsUpdatedAt());

        client.post("/rest/v1/user_settings?on_conflict=user_id", settingsObj.toString(), headers);
    }

    private Map<String, String> defaultPushHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Prefer", "resolution=merge-duplicates,return=minimal");
        return headers;
    }

    // ---------------------------------------------------------------------
    // Pull Helpers
    // ---------------------------------------------------------------------

    public interface JsonParser<T> {
        T parse(JSONObject json);
    }

    private <T> List<T> pullAll(SupabaseClient client, String path, JsonParser<T> parser) throws Exception {
        List<T> results = new ArrayList<>();
        int offset = 0;
        while (true) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Range", offset + "-" + (offset + PAGE_LIMIT - 1));
            headers.put("Range-Unit", "items");

            SupabaseClient.HttpResponse resp = client.get(path, headers);
            if (!resp.isSuccess()) {
                throw new Exception("Pull failed on " + path + ": " + resp.getErrorMessage());
            }

            JSONArray arr = new JSONArray(resp.body != null ? resp.body : "[]");
            for (int i = 0; i < arr.length(); i++) {
                results.add(parser.parse(arr.getJSONObject(i)));
            }

            if (arr.length() < PAGE_LIMIT) {
                break;
            }
            offset += arr.length();
        }
        return results;
    }

    private JSONObject pullSingle(SupabaseClient client, String path) throws Exception {
        SupabaseClient.HttpResponse resp = client.get(path, Collections.emptyMap());
        if (!resp.isSuccess()) return null;
        JSONArray arr = new JSONArray(resp.body != null ? resp.body : "[]");
        if (arr.length() > 0) return arr.getJSONObject(0);
        return null;
    }
}
