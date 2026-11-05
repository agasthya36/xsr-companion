package com.xsr.companion.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.xsr.companion.BuildConfig;
import com.xsr.companion.R;
import com.xsr.companion.data.RideDatabaseHelper;
import com.xsr.companion.settings.SettingsManager;
import com.xsr.companion.settings.Units;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen OpenStreetMap (Leaflet) view of one recorded ride. The route
 * comes from the locally stored GPS points. Map tiles are fetched from CARTO
 * and are the only network traffic. No API key is required, so nothing secret
 * is bundled in the APK.
 */
public class RideMapActivity extends AppCompatActivity {

    public static final String EXTRA_RIDE_ID = "extra_ride_id";
    private static final String STATE_RIDE_ID = "state_ride_id";
    private static final String MAP_URL = "file:///android_asset/map.html";
    private static final String TAG = "RideMap";

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private WebView webView;
    private boolean pageReady;
    private String pendingRouteScript;

    private long rideId = -1;
    private int gpsPointCount;
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

        webView = findViewById(R.id.map);
        configureWebView();
        webView.loadUrl(MAP_URL);

        if (rideId > 0) {
            loadRide();
        } else if (BuildConfig.DEBUG) {
            // Debug-only hook for exercising the map without a GPS ride:
            // adb shell am start ... --es demo_route "lat,lng;lat,lng"
            String demo = getIntent().getStringExtra("demo_route");
            if (demo != null && !demo.isEmpty()) drawRoute(parseDemoRoute(demo));
        }
    }

    private JSONArray parseDemoRoute(String raw) {
        JSONArray out = new JSONArray();
        for (String pair : raw.split(";")) {
            String[] parts = pair.split(",");
            if (parts.length != 2) continue;
            try {
                JSONArray latLng = new JSONArray();
                latLng.put(Double.parseDouble(parts[0].trim()));
                latLng.put(Double.parseDouble(parts[1].trim()));
                out.put(latLng);
            } catch (Exception ignored) {
                // Skip malformed demo coordinates.
            }
        }
        return out;
    }

    private class MapBridge {
        @JavascriptInterface
        public void onReady(String info) {
            Log.i(TAG, "map ready: " + info);
        }

        @JavascriptInterface
        public void onRouteDrawn(int points) {
            Log.i(TAG, "route drawn: " + points + " points");
        }

        @JavascriptInterface
        public void onTileError(String url) {
            Log.w(TAG, "tile error: " + url);
        }

        @JavascriptInterface
        public void onIdle(String info) {
            Log.i(TAG, "map idle: " + info);
        }

        @JavascriptInterface
        public void onError(String message) {
            Log.e(TAG, "js error: " + message);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);

        webView.setBackgroundColor(0xFF0E1218);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.addJavascriptInterface(new MapBridge(), "XsrMap");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pendingRouteScript != null) {
                    String script = pendingRouteScript;
                    pendingRouteScript = null;
                    view.evaluateJavascript(script, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                // The asset page itself stays inside the WebView. Attribution
                // links open in the browser so the map is never replaced.
                if ("file".equals(scheme)) return false;
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) {
                        // No browser installed; leave the map untouched.
                    }
                }
                return true;
            }
        });
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
            JSONArray coords = new JSONArray();
            int count = 0;
            if (points != null) {
                for (RideDatabaseHelper.RidePoint p : points) {
                    if (!p.hasGps) continue;
                    try {
                        JSONArray pair = new JSONArray();
                        pair.put(p.latitude);
                        pair.put(p.longitude);
                        coords.put(pair);
                        count++;
                    } catch (org.json.JSONException ignored) {
                        // A malformed coordinate must not drop the whole route.
                    }
                }
            }
            final int pointCount = count;
            final JSONArray route = coords;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                summary = loaded;
                gpsPointCount = pointCount;
                updateSummary();
                drawRoute(route);
            });
        });
    }

    private void updateSummary() {
        if (summary == null) {
            summaryText.setText(R.string.map_no_route);
            return;
        }
        String distance = Units.distance(SettingsManager.get(this), summary.distanceKm);
        summaryText.setText(String.format(Locale.getDefault(),
                "%s\n%s  •  %,d GPS points",
                summary.getFormattedDate(), distance, gpsPointCount));
    }

    private void drawRoute(JSONArray route) {
        if (route.length() == 0) {
            Toast.makeText(this, R.string.map_no_route, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String script = "window.showRoute(" + route + ","
                + JSONObject.quote(getString(R.string.map_start)) + ","
                + JSONObject.quote(getString(R.string.map_finish)) + ");";

        if (webView == null) return;
        if (pageReady) {
            webView.evaluateJavascript(script, null);
        } else {
            pendingRouteScript = script;
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_RIDE_ID, rideId);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
