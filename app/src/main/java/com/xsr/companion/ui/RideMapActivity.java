package com.xsr.companion.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.xsr.companion.BuildConfig;
import com.xsr.companion.R;
import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.settings.SettingsManager;
import com.xsr.companion.settings.Units;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen Google Maps view of one recorded ride. The route comes from the
 * locally stored GPS points; map tiles are the only network traffic.
 */
public class RideMapActivity extends AppCompatActivity implements OnMapReadyCallback {

    public static final String EXTRA_RIDE_ID = "extra_ride_id";
    private static final String STATE_RIDE_ID = "state_ride_id";

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private GoogleMap map;
    private long rideId = -1;
    private List<LatLng> route;
    private RideDatabaseHelper.RideSummary summary;
    private TextView summaryText;
    private MaterialCardView summaryCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_ride_map);

        rideId = savedInstanceState != null
                ? savedInstanceState.getLong(STATE_RIDE_ID, -1)
                : getIntent().getLongExtra(EXTRA_RIDE_ID, -1);

        MaterialToolbar toolbar = findViewById(R.id.mapToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        summaryText = findViewById(R.id.tvMapSummary);
        summaryCard = findViewById(R.id.mapSummaryCard);
        applyInsets(toolbar);

        if (BuildConfig.MAPS_API_KEY.isEmpty()) {
            Toast.makeText(this, R.string.map_no_api_key, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
                != ConnectionResult.SUCCESS) {
            Toast.makeText(this, R.string.map_play_services_missing, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment == null) {
            finish();
            return;
        }
        mapFragment.getMapAsync(this);

        if (rideId > 0) loadRide();
    }

    private void applyInsets(View toolbar) {
        View root = findViewById(R.id.mapRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            toolbar.setPadding(toolbar.getPaddingLeft(), bars.top,
                    toolbar.getPaddingRight(), toolbar.getPaddingBottom());
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) summaryCard.getLayoutParams();
            lp.bottomMargin = bars.bottom + dp(12);
            summaryCard.setLayoutParams(lp);
            return windowInsets;
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void loadRide() {
        io.execute(() -> {
            RideDatabaseHelper db = RideDatabaseHelper.getInstance(this);
            RideDatabaseHelper.RideSummary loaded = db.getRide(rideId);
            List<RideDatabaseHelper.RidePoint> points = db.getPointsForRide(rideId);
            List<LatLng> coords = new ArrayList<>();
            if (points != null) {
                for (RideDatabaseHelper.RidePoint p : points) {
                    if (p.hasGps) coords.add(new LatLng(p.latitude, p.longitude));
                }
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                summary = loaded;
                route = coords;
                updateSummary();
                drawRoute();
            });
        });
    }

    private void updateSummary() {
        if (summary == null) {
            summaryText.setText(R.string.map_no_route);
            return;
        }
        int points = route != null ? route.size() : 0;
        String distance = Units.distance(SettingsManager.get(this), summary.distanceKm);
        summaryText.setText(String.format(Locale.getDefault(),
                "%s\n%s  •  %,d GPS points",
                summary.getFormattedDate(), distance, points));
    }

    private void drawRoute() {
        if (map == null || route == null) return;
        if (route.isEmpty()) {
            Toast.makeText(this, R.string.map_no_route, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        map.getUiSettings().setMapToolbarEnabled(false);
        map.getUiSettings().setZoomControlsEnabled(false);
        try {
            map.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_dark));
        } catch (Exception ignored) {
            // A style failure must never prevent the route from being shown.
        }

        map.addPolyline(new PolylineOptions()
                .addAll(route)
                .width(12f)
                .color(0xFF4D8DFF)
                .geodesic(true));

        map.addMarker(new MarkerOptions()
                .position(route.get(0))
                .title(getString(R.string.map_start)));
        map.addMarker(new MarkerOptions()
                .position(route.get(route.size() - 1))
                .title(getString(R.string.map_finish)));

        if (route.size() == 1) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(route.get(0), 15f));
        } else {
            LatLngBounds.Builder bounds = new LatLngBounds.Builder();
            for (LatLng point : route) bounds.include(point);
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), dp(56)));
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        map = googleMap;
        drawRoute();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_RIDE_ID, rideId);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
