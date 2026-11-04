package com.xsr.companion.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class RideDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "xsr_rides.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_RIDES = "rides";
    public static final String COL_ID = "_id";
    public static final String COL_START_TIME = "start_time";
    public static final String COL_END_TIME = "end_time";
    public static final String COL_DURATION_SEC = "duration_sec";
    public static final String COL_DISTANCE_KM = "distance_km";
    public static final String COL_TOP_SPEED = "top_speed_kmh";
    public static final String COL_AVG_SPEED = "avg_speed_kmh";
    public static final String COL_MAX_RPM = "max_rpm";
    public static final String COL_AVG_RPM = "avg_rpm";
    public static final String COL_GPX_PATH = "gpx_path";

    public static final String TABLE_POINTS = "ride_points";
    public static final String COL_POINT_ID = "_id";
    public static final String COL_RIDE_ID = "ride_id";
    public static final String COL_POINT_TIME = "timestamp";
    public static final String COL_LAT = "latitude";
    public static final String COL_LON = "longitude";
    public static final String COL_POINT_SPEED = "speed_kmh";
    public static final String COL_POINT_RPM = "rpm";
    public static final String COL_POINT_THROTTLE = "throttle_pct";
    public static final String COL_POINT_COOLANT = "coolant_temp";

    private final Context context;

    public static class RideSummary {
        public long id;
        public long startTime;
        public long endTime;
        public int durationSec;
        public double distanceKm;
        public int topSpeedKmh;
        public double avgSpeedKmh;
        public int maxRpm;
        public int avgRpm;
        public String gpxPath;

        public String getFormattedDate() {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault());
            return sdf.format(new Date(startTime));
        }

        public String getFormattedDuration() {
            int mins = durationSec / 60;
            int secs = durationSec % 60;
            if (mins >= 60) {
                int hrs = mins / 60;
                mins = mins % 60;
                return String.format(Locale.getDefault(), "%dh %02dm", hrs, mins);
            }
            return String.format(Locale.getDefault(), "%dm %02ds", mins, secs);
        }
    }

    public static class AggregateStats {
        public int totalRidesCount = 0;
        public double totalDistanceKm = 0.0;
        public int totalDurationSec = 0;
        public double overallAvgSpeedKmh = 0.0;
        public int lifetimeTopSpeedKmh = 0;
        public int lifetimeMaxRpm = 0;
        public double totalFuelConsumedL = 0.0;
        public double overallAvgMileageKmL = 0.0;

        public String getFormattedTotalDuration() {
            int mins = totalDurationSec / 60;
            if (mins >= 60) {
                int hrs = mins / 60;
                mins = mins % 60;
                return String.format(Locale.getDefault(), "%dh %02dm", hrs, mins);
            }
            return String.format(Locale.getDefault(), "%dm", mins);
        }

        public String getFormattedDistance() {
            return String.format(Locale.getDefault(), "%.1f km", totalDistanceKm);
        }

        public String getFormattedAvgSpeed() {
            return String.format(Locale.getDefault(), "%.1f km/h", overallAvgSpeedKmh);
        }
    }

    public static class RidePoint {
        public double latitude;
        public double longitude;
        public int speedKmh;
        public int rpm;
        public double throttlePct;
        public int coolantTemp;
        public long timestamp;
    }

    public RideDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.context = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_RIDES + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_START_TIME + " INTEGER, "
                + COL_END_TIME + " INTEGER, "
                + COL_DURATION_SEC + " INTEGER, "
                + COL_DISTANCE_KM + " REAL, "
                + COL_TOP_SPEED + " INTEGER, "
                + COL_AVG_SPEED + " REAL, "
                + COL_MAX_RPM + " INTEGER, "
                + COL_AVG_RPM + " INTEGER, "
                + COL_GPX_PATH + " TEXT"
                + ");");

        db.execSQL("CREATE TABLE " + TABLE_POINTS + " ("
                + COL_POINT_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_RIDE_ID + " INTEGER, "
                + COL_POINT_TIME + " INTEGER, "
                + COL_LAT + " REAL, "
                + COL_LON + " REAL, "
                + COL_POINT_SPEED + " INTEGER, "
                + COL_POINT_RPM + " INTEGER, "
                + COL_POINT_THROTTLE + " REAL, "
                + COL_POINT_COOLANT + " INTEGER"
                + ");");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_POINTS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_RIDES);
        onCreate(db);
    }

    public long startNewRide(long startTime) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_START_TIME, startTime);
        cv.put(COL_END_TIME, startTime);
        cv.put(COL_DURATION_SEC, 0);
        cv.put(COL_DISTANCE_KM, 0.0);
        cv.put(COL_TOP_SPEED, 0);
        cv.put(COL_AVG_SPEED, 0.0);
        cv.put(COL_MAX_RPM, 0);
        cv.put(COL_AVG_RPM, 0);
        return db.insert(TABLE_RIDES, null, cv);
    }

    public void insertPoint(long rideId, double lat, double lon, int speed, int rpm, double throttle, int coolant) {
        if (rideId <= 0) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_RIDE_ID, rideId);
        cv.put(COL_POINT_TIME, System.currentTimeMillis());
        cv.put(COL_LAT, lat);
        cv.put(COL_LON, lon);
        cv.put(COL_POINT_SPEED, speed);
        cv.put(COL_POINT_RPM, rpm);
        cv.put(COL_POINT_THROTTLE, throttle);
        cv.put(COL_POINT_COOLANT, coolant);
        db.insert(TABLE_POINTS, null, cv);
    }

    public boolean finalizeRide(long rideId, long endTime) {
        if (rideId <= 0) return false;
        SQLiteDatabase db = getWritableDatabase();

        List<RidePoint> points = getPointsForRide(rideId);
        if (points.size() < 3) {
            // Discard negligible jitter
            deleteRide(rideId);
            return false;
        }

        double totalDistanceKm = 0.0;
        int maxSpeed = 0;
        int maxRpm = 0;
        long totalSpeed = 0;
        long totalRpm = 0;

        for (int i = 0; i < points.size(); i++) {
            RidePoint p = points.get(i);
            if (p.speedKmh > maxSpeed) maxSpeed = p.speedKmh;
            if (p.rpm > maxRpm) maxRpm = p.rpm;
            totalSpeed += p.speedKmh;
            totalRpm += p.rpm;

            if (i > 0) {
                RidePoint prev = points.get(i - 1);
                totalDistanceKm += calculateDistance(prev.latitude, prev.longitude, p.latitude, p.longitude);
            }
        }

        // If moved less than 50 meters, discard (e.g. key turned on then off in garage)
        if (totalDistanceKm < 0.05 && maxSpeed < 5) {
            deleteRide(rideId);
            return false;
        }

        Cursor c = db.query(TABLE_RIDES, new String[]{COL_START_TIME}, COL_ID + "=?", new String[]{String.valueOf(rideId)}, null, null, null);
        long startTime = endTime;
        if (c != null && c.moveToFirst()) {
            startTime = c.getLong(0);
            c.close();
        }

        int durationSec = (int) Math.max(1, (endTime - startTime) / 1000);
        double avgSpeed = points.isEmpty() ? 0.0 : (double) totalSpeed / points.size();
        int avgRpm = points.isEmpty() ? 0 : (int) (totalRpm / points.size());

        // Export GPX file
        String gpxPath = exportGpxFileInternal(rideId, startTime, points);

        ContentValues cv = new ContentValues();
        cv.put(COL_END_TIME, endTime);
        cv.put(COL_DURATION_SEC, durationSec);
        cv.put(COL_DISTANCE_KM, totalDistanceKm);
        cv.put(COL_TOP_SPEED, maxSpeed);
        cv.put(COL_AVG_SPEED, avgSpeed);
        cv.put(COL_MAX_RPM, maxRpm);
        cv.put(COL_AVG_RPM, avgRpm);
        cv.put(COL_GPX_PATH, gpxPath);

        db.update(TABLE_RIDES, cv, COL_ID + "=?", new String[]{String.valueOf(rideId)});
        return true;
    }

    public List<RidePoint> getPointsForRide(long rideId) {
        List<RidePoint> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_POINTS, null, COL_RIDE_ID + "=?", new String[]{String.valueOf(rideId)}, null, null, COL_POINT_TIME + " ASC");
        if (c != null) {
            while (c.moveToNext()) {
                RidePoint p = new RidePoint();
                p.latitude = c.getDouble(c.getColumnIndexOrThrow(COL_LAT));
                p.longitude = c.getDouble(c.getColumnIndexOrThrow(COL_LON));
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
        List<RideSummary> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_RIDES, null, COL_DISTANCE_KM + ">0", null, null, null, COL_START_TIME + " DESC", String.valueOf(limit));
        if (c != null) {
            while (c.moveToNext()) {
                RideSummary s = new RideSummary();
                s.id = c.getLong(c.getColumnIndexOrThrow(COL_ID));
                s.startTime = c.getLong(c.getColumnIndexOrThrow(COL_START_TIME));
                s.endTime = c.getLong(c.getColumnIndexOrThrow(COL_END_TIME));
                s.durationSec = c.getInt(c.getColumnIndexOrThrow(COL_DURATION_SEC));
                s.distanceKm = c.getDouble(c.getColumnIndexOrThrow(COL_DISTANCE_KM));
                s.topSpeedKmh = c.getInt(c.getColumnIndexOrThrow(COL_TOP_SPEED));
                s.avgSpeedKmh = c.getDouble(c.getColumnIndexOrThrow(COL_AVG_SPEED));
                s.maxRpm = c.getInt(c.getColumnIndexOrThrow(COL_MAX_RPM));
                s.avgRpm = c.getInt(c.getColumnIndexOrThrow(COL_AVG_RPM));
                s.gpxPath = c.getString(c.getColumnIndexOrThrow(COL_GPX_PATH));
                list.add(s);
            }
            c.close();
        }
        return list;
    }

    public AggregateStats getAggregateStats() {
        AggregateStats stats = new AggregateStats();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*), SUM(" + COL_DISTANCE_KM + "), SUM(" + COL_DURATION_SEC + "), MAX(" + COL_TOP_SPEED + "), MAX(" + COL_MAX_RPM + ") FROM " + TABLE_RIDES + " WHERE " + COL_DISTANCE_KM + " > 0", null);
        if (c != null) {
            if (c.moveToFirst()) {
                stats.totalRidesCount = c.getInt(0);
                stats.totalDistanceKm = c.getDouble(1);
                stats.totalDurationSec = c.getInt(2);
                stats.lifetimeTopSpeedKmh = c.getInt(3);
                stats.lifetimeMaxRpm = c.getInt(4);

                if (stats.totalDurationSec > 0 && stats.totalDistanceKm > 0) {
                    double hours = stats.totalDurationSec / 3600.0;
                    stats.overallAvgSpeedKmh = stats.totalDistanceKm / hours;
                }
                stats.totalFuelConsumedL = stats.totalDistanceKm / 48.5;
                stats.overallAvgMileageKmL = stats.totalFuelConsumedL > 0 ? (stats.totalDistanceKm / stats.totalFuelConsumedL) : 48.5;
            }
            c.close();
        }
        return stats;
    }

    public void deleteRide(long rideId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_POINTS, COL_RIDE_ID + "=?", new String[]{String.valueOf(rideId)});
        db.delete(TABLE_RIDES, COL_ID + "=?", new String[]{String.valueOf(rideId)});
    }

    private String exportGpxFileInternal(long rideId, long startTime, List<RidePoint> points) {
        try {
            File dir = new File(context.getExternalFilesDir(null), "rides");
            if (!dir.exists()) dir.mkdirs();

            SimpleDateFormat nameFmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            String filename = "xsr_ride_" + nameFmt.format(new Date(startTime)) + ".gpx";
            File gpxFile = new File(dir, filename);

            SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));

            FileWriter writer = new FileWriter(gpxFile);
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            writer.write("<gpx version=\"1.1\" creator=\"Yamaha XSR 155 Companion\" "
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
                if (p.latitude == 0.0 && p.longitude == 0.0) continue;
                writer.write(String.format(Locale.US, "      <trkpt lat=\"%.6f\" lon=\"%.6f\">\n", p.latitude, p.longitude));
                writer.write("        <time>" + isoFmt.format(new Date(p.timestamp)) + "</time>\n");
                // Speed in meters/second for standard GPX
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
            writer.flush();
            writer.close();

            return gpxFile.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Earth radius in km
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
