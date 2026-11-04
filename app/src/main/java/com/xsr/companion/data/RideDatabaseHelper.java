package com.xsr.companion.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.xsr.companion.diagnostics.DtcManager;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class RideDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "xsr_rides.db";
    private static final int DATABASE_VERSION = 2;

    private static volatile RideDatabaseHelper instance;

    public static final String TABLE_RIDES = "rides";
    public static final String COL_ID = "_id";
    public static final String COL_START_TIME = "start_time";
    public static final String COL_END_TIME = "end_time";
    public static final String COL_DURATION_SEC = "duration_sec";
    public static final String COL_MOVING_SEC = "moving_sec";
    public static final String COL_DISTANCE_KM = "distance_km";
    public static final String COL_TOP_SPEED = "top_speed_kmh";
    public static final String COL_AVG_SPEED = "avg_speed_kmh";
    public static final String COL_MAX_RPM = "max_rpm";
    public static final String COL_AVG_RPM = "avg_rpm";
    public static final String COL_MAX_COOLANT = "max_coolant";
    public static final String COL_START_ODO = "start_odo_km";
    public static final String COL_END_ODO = "end_odo_km";
    public static final String COL_FUEL_USED = "fuel_used_l";
    public static final String COL_AVG_MILEAGE = "avg_mileage_kml";
    public static final String COL_GPX_PATH = "gpx_path";
    public static final String COL_NOTES = "notes";

    public static final String TABLE_POINTS = "ride_points";
    public static final String COL_POINT_ID = "_id";
    public static final String COL_RIDE_ID = "ride_id";
    public static final String COL_POINT_TIME = "timestamp";
    public static final String COL_LAT = "latitude";
    public static final String COL_LON = "longitude";
    public static final String COL_HAS_GPS = "has_gps";
    public static final String COL_ACCURACY = "accuracy_m";
    public static final String COL_POINT_SPEED = "speed_kmh";
    public static final String COL_POINT_RPM = "rpm";
    public static final String COL_POINT_THROTTLE = "throttle_pct";
    public static final String COL_POINT_COOLANT = "coolant_temp";

    public static final String TABLE_FUEL = "fuel_log";
    public static final String TABLE_SERVICE = "service_log";
    public static final String TABLE_DTC = "dtc_log";

    private final Context context;

    public static class RideSummary {
        public long id;
        public long startTime;
        public long endTime;
        public int durationSec;
        public int movingSec;
        public double distanceKm;
        public int topSpeedKmh;
        public double avgSpeedKmh;
        public int maxRpm;
        public int avgRpm;
        public int maxCoolant;
        public double startOdoKm;
        public double endOdoKm;
        public double fuelUsedL;
        public double avgMileageKmL;
        public String gpxPath;
        public String notes;

        public String getFormattedDate() {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault());
            return sdf.format(new Date(startTime));
        }

        public String getFormattedDuration() {
            int secs = Math.max(durationSec, 0);
            int mins = secs / 60;
            if (mins >= 60) {
                int hrs = mins / 60;
                mins = mins % 60;
                return String.format(Locale.getDefault(), "%dh %02dm", hrs, mins);
            }
            return String.format(Locale.getDefault(), "%dm %02ds", mins, secs % 60);
        }

        public String getFormattedMovingTime() {
            int secs = Math.max(movingSec > 0 ? movingSec : durationSec, 0);
            int mins = secs / 60;
            if (mins >= 60) {
                return String.format(Locale.getDefault(), "%dh %02dm", mins / 60, mins % 60);
            }
            return String.format(Locale.getDefault(), "%dm", mins);
        }
    }

    public static class AggregateStats {
        public int totalRidesCount = 0;
        public double totalDistanceKm = 0.0;
        public int totalDurationSec = 0;
        public int totalMovingSec = 0;
        public double overallAvgSpeedKmh = 0.0;
        public int lifetimeTopSpeedKmh = 0;
        public int lifetimeMaxRpm = 0;
        public double totalFuelConsumedL = 0.0;
        public double overallAvgMileageKmL = 0.0;
    }

    public static class RidePoint {
        public double latitude;
        public double longitude;
        public boolean hasGps;
        public float accuracyM;
        public int speedKmh;
        public int rpm;
        public double throttlePct;
        public int coolantTemp;
        public long timestamp;
    }

    public static class FuelLogEntry {
        public long id;
        public long timestamp;
        public double odometerKm;
        public double litres;
        public double pricePerLitre;
        public double totalCost;
        public boolean isFull;
        public String notes;
    }

    public static class ServiceLogEntry {
        public long id;
        public String itemId;
        public long timestamp;
        public double odometerKm;
        public String notes;
        public double cost;
    }

    public static class DtcLogEntry {
        public long id;
        public long timestamp;
        public int code;
        public String title;
        public String severity;
        public double odometerKm;
    }

    private RideDatabaseHelper(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
        this.context = context.getApplicationContext();
        setWriteAheadLoggingEnabled(true);
    }

    public static RideDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            synchronized (RideDatabaseHelper.class) {
                if (instance == null) instance = new RideDatabaseHelper(context);
            }
        }
        return instance;
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.enableWriteAheadLogging();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_RIDES + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_START_TIME + " INTEGER, "
                + COL_END_TIME + " INTEGER, "
                + COL_DURATION_SEC + " INTEGER DEFAULT 0, "
                + COL_MOVING_SEC + " INTEGER DEFAULT 0, "
                + COL_DISTANCE_KM + " REAL DEFAULT 0, "
                + COL_TOP_SPEED + " INTEGER DEFAULT 0, "
                + COL_AVG_SPEED + " REAL DEFAULT 0, "
                + COL_MAX_RPM + " INTEGER DEFAULT 0, "
                + COL_AVG_RPM + " INTEGER DEFAULT 0, "
                + COL_MAX_COOLANT + " INTEGER DEFAULT 0, "
                + COL_START_ODO + " REAL DEFAULT 0, "
                + COL_END_ODO + " REAL DEFAULT 0, "
                + COL_FUEL_USED + " REAL DEFAULT 0, "
                + COL_AVG_MILEAGE + " REAL DEFAULT 0, "
                + COL_GPX_PATH + " TEXT, "
                + COL_NOTES + " TEXT"
                + ");");

        db.execSQL("CREATE TABLE " + TABLE_POINTS + " ("
                + COL_POINT_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_RIDE_ID + " INTEGER, "
                + COL_POINT_TIME + " INTEGER, "
                + COL_LAT + " REAL, "
                + COL_LON + " REAL, "
                + COL_HAS_GPS + " INTEGER DEFAULT 0, "
                + COL_ACCURACY + " REAL DEFAULT 0, "
                + COL_POINT_SPEED + " INTEGER, "
                + COL_POINT_RPM + " INTEGER, "
                + COL_POINT_THROTTLE + " REAL, "
                + COL_POINT_COOLANT + " INTEGER"
                + ");");

        createSecondaryTables(db);
        createIndexes(db);
    }

    private void createSecondaryTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FUEL + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "timestamp INTEGER, odometer_km REAL, litres REAL, "
                + "price_per_l REAL DEFAULT 0, total_cost REAL DEFAULT 0, "
                + "is_full INTEGER DEFAULT 0, notes TEXT);");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_SERVICE + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "item_id TEXT, timestamp INTEGER, odometer_km REAL, "
                + "notes TEXT, cost REAL DEFAULT 0);");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DTC + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "timestamp INTEGER, code INTEGER, title TEXT, "
                + "severity TEXT, odometer_km REAL DEFAULT 0);");
    }

    private void createIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_points_ride ON " + TABLE_POINTS
                + " (" + COL_RIDE_ID + ", " + COL_POINT_TIME + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rides_start ON " + TABLE_RIDES
                + " (" + COL_START_TIME + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fuel_time ON " + TABLE_FUEL + " (timestamp)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_service_item ON " + TABLE_SERVICE + " (item_id, timestamp)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dtc_time ON " + TABLE_DTC + " (timestamp)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // Non-destructive v1 -> v2 migration.
            addColumnIfMissing(db, TABLE_RIDES, COL_MOVING_SEC, "INTEGER DEFAULT 0");
            addColumnIfMissing(db, TABLE_RIDES, COL_MAX_COOLANT, "INTEGER DEFAULT 0");
            addColumnIfMissing(db, TABLE_RIDES, COL_START_ODO, "REAL DEFAULT 0");
            addColumnIfMissing(db, TABLE_RIDES, COL_END_ODO, "REAL DEFAULT 0");
            addColumnIfMissing(db, TABLE_RIDES, COL_FUEL_USED, "REAL DEFAULT 0");
            addColumnIfMissing(db, TABLE_RIDES, COL_AVG_MILEAGE, "REAL DEFAULT 0");
            addColumnIfMissing(db, TABLE_RIDES, COL_NOTES, "TEXT");
            addColumnIfMissing(db, TABLE_POINTS, COL_HAS_GPS, "INTEGER DEFAULT 0");
            addColumnIfMissing(db, TABLE_POINTS, COL_ACCURACY, "REAL DEFAULT 0");
            createSecondaryTables(db);
            createIndexes(db);

            // Older points with a real fix are marked as valid.
            db.execSQL("UPDATE " + TABLE_POINTS + " SET " + COL_HAS_GPS + "=1 WHERE "
                    + COL_LAT + " IS NOT NULL AND (" + COL_LAT + " != 0 OR " + COL_LON + " != 0)");
        }
    }

    private void addColumnIfMissing(SQLiteDatabase db, String table, String column, String type) {
        try {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (Exception ignored) {
            // Column already exists.
        }
    }

    // ---------------------------------------------------------------------
    // Rides
    // ---------------------------------------------------------------------

    public long startNewRide(long startTime, double startOdoKm) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_START_TIME, startTime);
        cv.put(COL_END_TIME, startTime);
        cv.put(COL_DURATION_SEC, 0);
        cv.put(COL_MOVING_SEC, 0);
        cv.put(COL_DISTANCE_KM, 0.0);
        cv.put(COL_TOP_SPEED, 0);
        cv.put(COL_AVG_SPEED, 0.0);
        cv.put(COL_MAX_RPM, 0);
        cv.put(COL_AVG_RPM, 0);
        cv.put(COL_START_ODO, startOdoKm);
        cv.put(COL_END_ODO, startOdoKm);
        cv.put(COL_FUEL_USED, 0.0);
        return db.insert(TABLE_RIDES, null, cv);
    }

    public void insertPoint(long rideId, long timestamp, double lat, double lon, boolean hasGps,
                            float accuracyM, int speed, int rpm, double throttle, int coolant) {
        if (rideId <= 0) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_RIDE_ID, rideId);
        cv.put(COL_POINT_TIME, timestamp);
        if (hasGps) {
            cv.put(COL_LAT, lat);
            cv.put(COL_LON, lon);
        } else {
            cv.putNull(COL_LAT);
            cv.putNull(COL_LON);
        }
        cv.put(COL_HAS_GPS, hasGps ? 1 : 0);
        cv.put(COL_ACCURACY, accuracyM);
        cv.put(COL_POINT_SPEED, speed);
        cv.put(COL_POINT_RPM, rpm);
        cv.put(COL_POINT_THROTTLE, throttle);
        cv.put(COL_POINT_COOLANT, coolant);
        db.insert(TABLE_POINTS, null, cv);
    }

    /**
     * Finalizes a ride and produces the GPX file. Distance is supplied by the
     * service using wheel-speed integration; if it is zero we fall back to the
     * recorded points. Invalid (no-fix) points never contribute to distance.
     */
    public boolean finalizeRide(long rideId, long endTime, double distanceKm, int movingSec,
                                int maxCoolant, double endOdoKm, double fuelUsedL) {
        if (rideId <= 0) return false;
        SQLiteDatabase db = getWritableDatabase();

        List<RidePoint> points = getPointsForRide(rideId);
        if (points.isEmpty()) {
            deleteRide(rideId);
            return false;
        }

        int maxSpeed = 0;
        int maxRpm = 0;
        long totalSpeed = 0;
        long totalRpm = 0;
        double gpsDistanceKm = 0.0;
        double wheelDistanceKm = 0.0;

        RidePoint prev = null;
        RidePoint prevValid = null;
        for (RidePoint p : points) {
            if (p.speedKmh > maxSpeed) maxSpeed = p.speedKmh;
            if (p.rpm > maxRpm) maxRpm = p.rpm;
            totalSpeed += p.speedKmh;
            totalRpm += p.rpm;

            if (prev != null) {
                long dtMs = Math.max(0, p.timestamp - prev.timestamp);
                wheelDistanceKm += prev.speedKmh * (dtMs / 1000.0) / 3600.0;
            }
            if (p.hasGps && prevValid != null) {
                gpsDistanceKm += calculateDistance(prevValid.latitude, prevValid.longitude,
                        p.latitude, p.longitude);
            }
            if (p.hasGps) prevValid = p;
            prev = p;
        }

        if (distanceKm <= 0.05) {
            distanceKm = Math.max(wheelDistanceKm, gpsDistanceKm);
        }

        Cursor c = db.query(TABLE_RIDES,
                new String[]{COL_START_TIME, COL_START_ODO}, COL_ID + "=?",
                new String[]{String.valueOf(rideId)}, null, null, null);
        long startTime = endTime;
        double startOdo = 0.0;
        if (c != null && c.moveToFirst()) {
            startTime = c.getLong(0);
            startOdo = c.getDouble(1);
            c.close();
        }

        int durationSec = (int) Math.max(1, (endTime - startTime) / 1000);
        if (movingSec <= 0) movingSec = durationSec;

        // 50 m / 5 km/h noise filter, as documented.
        if (distanceKm < 0.05 && maxSpeed < 5) {
            deleteRide(rideId);
            return false;
        }

        double avgSpeed = movingSec > 0 ? distanceKm / (movingSec / 3600.0) : 0.0;
        int avgRpm = (int) (totalRpm / Math.max(1, points.size()));
        double avgMileage = fuelUsedL > 0.01 ? distanceKm / fuelUsedL : 0.0;

        String gpxPath = exportGpxFileInternal(rideId, startTime, points);

        ContentValues cv = new ContentValues();
        cv.put(COL_END_TIME, endTime);
        cv.put(COL_DURATION_SEC, durationSec);
        cv.put(COL_MOVING_SEC, movingSec);
        cv.put(COL_DISTANCE_KM, distanceKm);
        cv.put(COL_TOP_SPEED, maxSpeed);
        cv.put(COL_AVG_SPEED, avgSpeed);
        cv.put(COL_MAX_RPM, maxRpm);
        cv.put(COL_AVG_RPM, avgRpm);
        cv.put(COL_MAX_COOLANT, maxCoolant);
        cv.put(COL_START_ODO, startOdo);
        cv.put(COL_END_ODO, endOdoKm > 0 ? endOdoKm : startOdo + distanceKm);
        cv.put(COL_FUEL_USED, fuelUsedL);
        cv.put(COL_AVG_MILEAGE, avgMileage);
        cv.put(COL_GPX_PATH, gpxPath);
        db.update(TABLE_RIDES, cv, COL_ID + "=?", new String[]{String.valueOf(rideId)});
        return true;
    }

    /** Finalizes rides left open by a process kill / crash. */
    public int recoverOpenRides() {
        List<Long> open = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_RIDES, new String[]{COL_ID},
                COL_GPX_PATH + " IS NULL AND " + COL_END_TIME + " <= " + COL_START_TIME,
                null, null, null, COL_START_TIME + " ASC");
        if (c != null) {
            while (c.moveToNext()) open.add(c.getLong(0));
            c.close();
        }
        int recovered = 0;
        for (long id : open) {
            List<RidePoint> pts = getPointsForRide(id);
            long end = System.currentTimeMillis();
            if (!pts.isEmpty()) end = Math.max(end, pts.get(pts.size() - 1).timestamp);
            if (finalizeRide(id, end, 0.0, 0, 0, 0.0, 0.0)) recovered++;
        }
        return recovered;
    }

    public List<RidePoint> getPointsForRide(long rideId) {
        List<RidePoint> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_POINTS, null, COL_RIDE_ID + "=?",
                new String[]{String.valueOf(rideId)}, null, null, COL_POINT_TIME + " ASC");
        if (c != null) {
            while (c.moveToNext()) {
                RidePoint p = new RidePoint();
                p.hasGps = c.getInt(c.getColumnIndexOrThrow(COL_HAS_GPS)) == 1
                        && !c.isNull(c.getColumnIndexOrThrow(COL_LAT));
                p.latitude = p.hasGps ? c.getDouble(c.getColumnIndexOrThrow(COL_LAT)) : 0.0;
                p.longitude = p.hasGps ? c.getDouble(c.getColumnIndexOrThrow(COL_LON)) : 0.0;
                p.accuracyM = c.getFloat(c.getColumnIndexOrThrow(COL_ACCURACY));
                p.speedKmh = c.getInt(c.getColumnIndexOrThrow(COL_POINT_SPEED));
                p.rpm = c.getInt(c.getColumnIndexOrThrow(COL_POINT_RPM));
                p.throttlePct = c.getDouble(c.getColumnIndexOrThrow(COL_POINT_THROTTLE));
                p.coolantTemp = c.getInt(c.getColumnIndexOrThrow(COL_POINT_COOLANT));
                p.timestamp = c.getLong(c.getColumnIndexOrThrow(COL_POINT_TIME));
                list.add(p);
            }
            c.close();
        }
        return list;
    }

    public List<RideSummary> getRecentRides(int limit) {
        return queryRides(COL_END_TIME + ">0", COL_START_TIME + " DESC", String.valueOf(limit));
    }

    public List<RideSummary> getAllRides() {
        return queryRides(COL_END_TIME + ">0", COL_START_TIME + " DESC", null);
    }

    public RideSummary getRide(long rideId) {
        List<RideSummary> list = queryRides(COL_ID + "=?", null, String.valueOf(rideId));
        return list.isEmpty() ? null : list.get(0);
    }

    private List<RideSummary> queryRides(String where, String order, String limit) {
        List<RideSummary> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_RIDES, null, where, null, null, null, order, limit);
        if (c != null) {
            while (c.moveToNext()) {
                RideSummary s = new RideSummary();
                s.id = c.getLong(c.getColumnIndexOrThrow(COL_ID));
                s.startTime = c.getLong(c.getColumnIndexOrThrow(COL_START_TIME));
                s.endTime = c.getLong(c.getColumnIndexOrThrow(COL_END_TIME));
                s.durationSec = c.getInt(c.getColumnIndexOrThrow(COL_DURATION_SEC));
                s.movingSec = c.getInt(c.getColumnIndexOrThrow(COL_MOVING_SEC));
                s.distanceKm = c.getDouble(c.getColumnIndexOrThrow(COL_DISTANCE_KM));
                s.topSpeedKmh = c.getInt(c.getColumnIndexOrThrow(COL_TOP_SPEED));
                s.avgSpeedKmh = c.getDouble(c.getColumnIndexOrThrow(COL_AVG_SPEED));
                s.maxRpm = c.getInt(c.getColumnIndexOrThrow(COL_MAX_RPM));
                s.avgRpm = c.getInt(c.getColumnIndexOrThrow(COL_AVG_RPM));
                s.maxCoolant = c.getInt(c.getColumnIndexOrThrow(COL_MAX_COOLANT));
                s.startOdoKm = c.getDouble(c.getColumnIndexOrThrow(COL_START_ODO));
                s.endOdoKm = c.getDouble(c.getColumnIndexOrThrow(COL_END_ODO));
                s.fuelUsedL = c.getDouble(c.getColumnIndexOrThrow(COL_FUEL_USED));
                s.avgMileageKmL = c.getDouble(c.getColumnIndexOrThrow(COL_AVG_MILEAGE));
                s.gpxPath = c.getString(c.getColumnIndexOrThrow(COL_GPX_PATH));
                s.notes = c.getString(c.getColumnIndexOrThrow(COL_NOTES));
                list.add(s);
            }
            c.close();
        }
        return list;
    }

    public AggregateStats getAggregateStats() {
        AggregateStats stats = new AggregateStats();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*), SUM(" + COL_DISTANCE_KM + "), "
                + "SUM(" + COL_DURATION_SEC + "), SUM(CASE WHEN " + COL_MOVING_SEC + ">0 THEN "
                + COL_MOVING_SEC + " ELSE " + COL_DURATION_SEC + " END), MAX(" + COL_TOP_SPEED + "), "
                + "MAX(" + COL_MAX_RPM + "), SUM(" + COL_FUEL_USED + ") FROM " + TABLE_RIDES
                + " WHERE " + COL_DISTANCE_KM + " > 0", null);
        if (c != null) {
            if (c.moveToFirst()) {
                stats.totalRidesCount = c.getInt(0);
                stats.totalDistanceKm = c.getDouble(1);
                stats.totalDurationSec = c.getInt(2);
                stats.totalMovingSec = c.getInt(3);
                stats.lifetimeTopSpeedKmh = c.getInt(4);
                stats.lifetimeMaxRpm = c.getInt(5);
                stats.totalFuelConsumedL = c.getDouble(6);

                int secs = stats.totalMovingSec > 0 ? stats.totalMovingSec : stats.totalDurationSec;
                if (secs > 0 && stats.totalDistanceKm > 0) {
                    stats.overallAvgSpeedKmh = stats.totalDistanceKm / (secs / 3600.0);
                }
                if (stats.totalFuelConsumedL > 0.01) {
                    stats.overallAvgMileageKmL = stats.totalDistanceKm / stats.totalFuelConsumedL;
                }
            }
            c.close();
        }
        return stats;
    }

    public void deleteRide(long rideId) {
        RideSummary s = getRide(rideId);
        if (s != null && s.gpxPath != null) {
            try {
                File f = new File(s.gpxPath);
                if (f.exists()) f.delete();
            } catch (Exception ignored) {}
        }
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_POINTS, COL_RIDE_ID + "=?", new String[]{String.valueOf(rideId)});
        db.delete(TABLE_RIDES, COL_ID + "=?", new String[]{String.valueOf(rideId)});
    }

    public void clearAllRideData() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_POINTS, null, null);
        db.delete(TABLE_RIDES, null, null);
        File dir = ridesDir();
        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
        }
    }

    // ---------------------------------------------------------------------
    // Fuel log
    // ---------------------------------------------------------------------

    public long insertFuelLog(long timestamp, double odometerKm, double litres,
                              double pricePerLitre, boolean isFull, String notes) {
        ContentValues cv = new ContentValues();
        cv.put("timestamp", timestamp);
        cv.put("odometer_km", odometerKm);
        cv.put("litres", litres);
        cv.put("price_per_l", pricePerLitre);
        cv.put("total_cost", pricePerLitre * litres);
        cv.put("is_full", isFull ? 1 : 0);
        cv.put("notes", notes);
        return getWritableDatabase().insert(TABLE_FUEL, null, cv);
    }

    public List<FuelLogEntry> getFuelLog(int limit) {
        List<FuelLogEntry> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_FUEL, null, null, null, null, null,
                "timestamp DESC", limit > 0 ? String.valueOf(limit) : null);
        if (c != null) {
            while (c.moveToNext()) {
                FuelLogEntry e = new FuelLogEntry();
                e.id = c.getLong(c.getColumnIndexOrThrow("_id"));
                e.timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"));
                e.odometerKm = c.getDouble(c.getColumnIndexOrThrow("odometer_km"));
                e.litres = c.getDouble(c.getColumnIndexOrThrow("litres"));
                e.pricePerLitre = c.getDouble(c.getColumnIndexOrThrow("price_per_l"));
                e.totalCost = c.getDouble(c.getColumnIndexOrThrow("total_cost"));
                e.isFull = c.getInt(c.getColumnIndexOrThrow("is_full")) == 1;
                e.notes = c.getString(c.getColumnIndexOrThrow("notes"));
                list.add(e);
            }
            c.close();
        }
        return list;
    }

    /**
     * Tank-to-tank mileage: distance between the last two full fills divided by
     * every litre added between them (including partial fills).
     */
    public double calculateActualMileageKmL() {
        List<FuelLogEntry> log = new ArrayList<>(getFuelLog(0));
        java.util.Collections.reverse(log); // oldest first
        int lastFull = -1;
        int prevFull = -1;
        for (int i = log.size() - 1; i >= 0; i--) {
            if (log.get(i).isFull) {
                if (lastFull < 0) lastFull = i;
                else if (prevFull < 0) {
                    prevFull = i;
                    break;
                }
            }
        }
        if (lastFull < 0 || prevFull < 0) return 0.0;
        FuelLogEntry a = log.get(prevFull);
        FuelLogEntry b = log.get(lastFull);
        if (b.odometerKm <= a.odometerKm) return 0.0;
        double litres = 0.0;
        for (int i = prevFull + 1; i <= lastFull; i++) {
            litres += log.get(i).litres;
        }
        return litres > 0.1 ? (b.odometerKm - a.odometerKm) / litres : 0.0;
    }

    public void deleteFuelLog(long id) {
        getWritableDatabase().delete(TABLE_FUEL, "_id=?", new String[]{String.valueOf(id)});
    }

    // ---------------------------------------------------------------------
    // Service history
    // ---------------------------------------------------------------------

    public long insertServiceLog(String itemId, long timestamp, double odo, String notes, double cost) {
        ContentValues cv = new ContentValues();
        cv.put("item_id", itemId);
        cv.put("timestamp", timestamp);
        cv.put("odometer_km", odo);
        cv.put("notes", notes);
        cv.put("cost", cost);
        return getWritableDatabase().insert(TABLE_SERVICE, null, cv);
    }

    public List<ServiceLogEntry> getServiceLog(int limit) {
        List<ServiceLogEntry> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_SERVICE, null, null, null, null, null,
                "timestamp DESC", limit > 0 ? String.valueOf(limit) : null);
        if (c != null) {
            while (c.moveToNext()) {
                ServiceLogEntry e = new ServiceLogEntry();
                e.id = c.getLong(c.getColumnIndexOrThrow("_id"));
                e.itemId = c.getString(c.getColumnIndexOrThrow("item_id"));
                e.timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"));
                e.odometerKm = c.getDouble(c.getColumnIndexOrThrow("odometer_km"));
                e.notes = c.getString(c.getColumnIndexOrThrow("notes"));
                e.cost = c.getDouble(c.getColumnIndexOrThrow("cost"));
                list.add(e);
            }
            c.close();
        }
        return list;
    }

    public void deleteServiceLog(long id) {
        getWritableDatabase().delete(TABLE_SERVICE, "_id=?", new String[]{String.valueOf(id)});
    }

    // ---------------------------------------------------------------------
    // DTC history
    // ---------------------------------------------------------------------

    public long insertDtcLog(long timestamp, int code, String title, String severity, double odo) {
        ContentValues cv = new ContentValues();
        cv.put("timestamp", timestamp);
        cv.put("code", code);
        cv.put("title", title);
        cv.put("severity", severity);
        cv.put("odometer_km", odo);
        return getWritableDatabase().insert(TABLE_DTC, null, cv);
    }

    public List<DtcLogEntry> getDtcLog(int limit) {
        List<DtcLogEntry> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_DTC, null, null, null, null, null,
                "timestamp DESC", limit > 0 ? String.valueOf(limit) : null);
        if (c != null) {
            while (c.moveToNext()) {
                DtcLogEntry e = new DtcLogEntry();
                e.id = c.getLong(c.getColumnIndexOrThrow("_id"));
                e.timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"));
                e.code = c.getInt(c.getColumnIndexOrThrow("code"));
                e.title = c.getString(c.getColumnIndexOrThrow("title"));
                e.severity = c.getString(c.getColumnIndexOrThrow("severity"));
                e.odometerKm = c.getDouble(c.getColumnIndexOrThrow("odometer_km"));
                list.add(e);
            }
            c.close();
        }
        return list;
    }

    public void clearDtcLog() {
        getWritableDatabase().delete(TABLE_DTC, null, null);
    }

    // ---------------------------------------------------------------------
    // GPX + export
    // ---------------------------------------------------------------------

    private File ridesDir() {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        return new File(base, "rides");
    }

    private String exportGpxFileInternal(long rideId, long startTime, List<RidePoint> points) {
        try {
            File dir = ridesDir();
            if (dir == null) return null;
            if (!dir.exists() && !dir.mkdirs()) return null;

            SimpleDateFormat nameFmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            String filename = "xsr_ride_" + nameFmt.format(new Date(startTime)) + ".gpx";
            File gpxFile = new File(dir, filename);

            SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(gpxFile), StandardCharsets.UTF_8))) {
                writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                writer.write("<gpx version=\"1.1\" creator=\"Yamaha XSR Companion\" "
                        + "xmlns=\"http://www.topografix.com/GPX/1/1\" "
                        + "xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n");
                writer.write("  <metadata>\n");
                writer.write("    <name>Yamaha XSR 155 Ride</name>\n");
                writer.write("    <time>" + isoFmt.format(new Date(startTime)) + "</time>\n");
                writer.write("  </metadata>\n");
                writer.write("  <trk>\n");
                writer.write("    <name>Ride " + nameFmt.format(new Date(startTime)) + "</name>\n");
                writer.write("    <trkseg>\n");

                for (RidePoint p : points) {
                    if (!p.hasGps) continue;
                    writer.write(String.format(Locale.US,
                            "      <trkpt lat=\"%.6f\" lon=\"%.6f\">\n", p.latitude, p.longitude));
                    writer.write("        <time>" + isoFmt.format(new Date(p.timestamp)) + "</time>\n");
                    double speedMs = (p.speedKmh * 1000.0) / 3600.0;
                    writer.write(String.format(Locale.US, "        <speed>%.2f</speed>\n", speedMs));
                    writer.write("        <extensions>\n");
                    writer.write("          <gpxtpx:TrackPointExtension>\n");
                    writer.write("            <gpxtpx:cad>" + p.rpm + "</gpxtpx:cad>\n");
                    writer.write("            <gpxtpx:temp>" + p.coolantTemp + "</gpxtpx:temp>\n");
                    writer.write("          </gpxtpx:TrackPointExtension>\n");
                    writer.write("        </extensions>\n");
                    writer.write("      </trkpt>\n");
                }

                writer.write("    </trkseg>\n");
                writer.write("  </trk>\n");
                writer.write("</gpx>\n");
            }

            return gpxFile.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Creates a ZIP with every GPX plus CSV exports of rides, fuel and service
     * history. Returns null on failure.
     */
    public File exportEverything() {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        File dir = new File(base, "exports");
        if (!dir.exists() && !dir.mkdirs()) return null;

        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        File zip = new File(dir, "xsr_export_" + fmt.format(new Date()) + ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            File rides = ridesDir();
            if (rides != null && rides.exists()) {
                File[] files = rides.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (!f.isFile()) continue;
                        zos.putNextEntry(new ZipEntry("rides/" + f.getName()));
                        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                        }
                        zos.closeEntry();
                    }
                }
            }

            writeCsvEntry(zos, "rides.csv", ridesCsv());
            writeCsvEntry(zos, "fuel.csv", fuelCsv());
            writeCsvEntry(zos, "service.csv", serviceCsv());
            zos.finish();
            return zip;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCsvEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private String ridesCsv() {
        StringBuilder sb = new StringBuilder("id,start,end,duration_sec,moving_sec,distance_km,"
                + "top_speed_kmh,avg_speed_kmh,max_rpm,avg_rpm,max_coolant,start_odo_km,"
                + "end_odo_km,fuel_used_l,avg_mileage_kml\n");
        for (RideSummary r : getAllRides()) {
            sb.append(r.id).append(',')
                    .append(r.startTime).append(',')
                    .append(r.endTime).append(',')
                    .append(r.durationSec).append(',')
                    .append(r.movingSec).append(',')
                    .append(String.format(Locale.US, "%.3f", r.distanceKm)).append(',')
                    .append(r.topSpeedKmh).append(',')
                    .append(String.format(Locale.US, "%.2f", r.avgSpeedKmh)).append(',')
                    .append(r.maxRpm).append(',')
                    .append(r.avgRpm).append(',')
                    .append(r.maxCoolant).append(',')
                    .append(String.format(Locale.US, "%.2f", r.startOdoKm)).append(',')
                    .append(String.format(Locale.US, "%.2f", r.endOdoKm)).append(',')
                    .append(String.format(Locale.US, "%.3f", r.fuelUsedL)).append(',')
                    .append(String.format(Locale.US, "%.2f", r.avgMileageKmL)).append('\n');
        }
        return sb.toString();
    }

    private String fuelCsv() {
        StringBuilder sb = new StringBuilder("id,timestamp,odometer_km,litres,price_per_l,total_cost,is_full,notes\n");
        for (FuelLogEntry e : getFuelLog(0)) {
            sb.append(e.id).append(',')
                    .append(e.timestamp).append(',')
                    .append(String.format(Locale.US, "%.2f", e.odometerKm)).append(',')
                    .append(String.format(Locale.US, "%.3f", e.litres)).append(',')
                    .append(String.format(Locale.US, "%.2f", e.pricePerLitre)).append(',')
                    .append(String.format(Locale.US, "%.2f", e.totalCost)).append(',')
                    .append(e.isFull ? 1 : 0).append(',')
                    .append(csvEscape(e.notes)).append('\n');
        }
        return sb.toString();
    }

    private String serviceCsv() {
        StringBuilder sb = new StringBuilder("id,item_id,timestamp,odometer_km,cost,notes\n");
        for (ServiceLogEntry e : getServiceLog(0)) {
            sb.append(e.id).append(',')
                    .append(csvEscape(e.itemId)).append(',')
                    .append(e.timestamp).append(',')
                    .append(String.format(Locale.US, "%.2f", e.odometerKm)).append(',')
                    .append(String.format(Locale.US, "%.2f", e.cost)).append(',')
                    .append(csvEscape(e.notes)).append('\n');
        }
        return sb.toString();
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
