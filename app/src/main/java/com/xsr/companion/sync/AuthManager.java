package com.xsr.companion.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Manages Supabase Auth session, email OTP flows, token refresh, and sync metadata.
 */
public class AuthManager implements SupabaseClient.SessionCallback {

    private static final String PREF_SYNC = "xsr_sync_prefs";
    private static final String PREF_TOKENS = "xsr_secure_tokens";

    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_LAST_SYNC_AT = "last_sync_at";
    private static final String KEY_LAST_SYNC_STATUS = "last_sync_status";
    private static final String KEY_LAST_SYNC_ERROR = "last_sync_error";
    private static final String KEY_FIRST_SYNC_DONE = "first_sync_done";

    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at";

    private static final long EXPIRY_BUFFER_MS = 5 * 60 * 1000L; // 5 minutes

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,64}$", Pattern.CASE_INSENSITIVE);

    public interface AuthCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    private static volatile AuthManager instance;

    private final Context context;
    private final SharedPreferences syncPrefs;
    private final SharedPreferences securePrefs;
    private final SupabaseClient client;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Reentrancy guard: true while this thread is inside refreshSession(). */
    private final ThreadLocal<Boolean> refreshing = ThreadLocal.withInitial(() -> false);

    private AuthManager(Context context) {
        this.context = context.getApplicationContext();
        this.syncPrefs = this.context.getSharedPreferences(PREF_SYNC, Context.MODE_PRIVATE);
        this.securePrefs = initSecurePrefs(this.context);
        this.client = new SupabaseClient(SupabaseConfig.getUrl(), SupabaseConfig.getAnonKey());
        this.client.setSessionCallback(this);
    }

    public static AuthManager getInstance(Context context) {
        if (instance == null) {
            synchronized (AuthManager.class) {
                if (instance == null) {
                    instance = new AuthManager(context);
                }
            }
        }
        return instance;
    }

    private SharedPreferences initSecurePrefs(Context ctx) {
        try {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    ctx,
                    PREF_TOKENS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            return ctx.getSharedPreferences("xsr_tokens_fallback", Context.MODE_PRIVATE);
        }
    }

    public SupabaseClient getClient() {
        return client;
    }

    public synchronized boolean isSignedIn() {
        return getUserId() != null && !getUserId().isEmpty() && getRefreshToken() != null;
    }

    public String getUserId() {
        return syncPrefs.getString(KEY_USER_ID, null);
    }

    public String getEmail() {
        return syncPrefs.getString(KEY_EMAIL, null);
    }

    public long getLastSyncAt() {
        return syncPrefs.getLong(KEY_LAST_SYNC_AT, 0L);
    }

    public void setLastSyncAt(long timestamp) {
        syncPrefs.edit().putLong(KEY_LAST_SYNC_AT, timestamp).apply();
    }

    public String getLastSyncStatus() {
        return syncPrefs.getString(KEY_LAST_SYNC_STATUS, null);
    }

    public String getLastSyncError() {
        return syncPrefs.getString(KEY_LAST_SYNC_ERROR, null);
    }

    public void setLastSyncResult(String status, String error) {
        syncPrefs.edit()
                .putString(KEY_LAST_SYNC_STATUS, status)
                .putString(KEY_LAST_SYNC_ERROR, error)
                .apply();
    }

    public boolean isFirstSyncDone() {
        return syncPrefs.getBoolean(KEY_FIRST_SYNC_DONE, false);
    }

    public void setFirstSyncDone(boolean done) {
        syncPrefs.edit().putBoolean(KEY_FIRST_SYNC_DONE, done).apply();
    }

    private String getRawAccessToken() {
        return securePrefs.getString(KEY_ACCESS_TOKEN, null);
    }

    public String getRefreshToken() {
        return securePrefs.getString(KEY_REFRESH_TOKEN, null);
    }

    public long getExpiresAt() {
        return securePrefs.getLong(KEY_EXPIRES_AT, 0L);
    }

    @Override
    public synchronized String getAccessToken() {
        String token = getRawAccessToken();
        // Never re-enter refresh from within refresh on the same thread —
        // that cycle (getAccessToken -> refreshSession -> post -> getAccessToken)
        // caused a StackOverflowError crash loop.
        if (refreshing.get()) return token;
        long expiresAt = getExpiresAt();
        long now = System.currentTimeMillis();
        boolean expiring = token == null
                ? getRefreshToken() != null
                : (expiresAt - now < EXPIRY_BUFFER_MS);
        if (expiring) {
            // Expiring soon, already expired, or missing access token; refresh synchronously
            refreshSession();
            token = getRawAccessToken();
        }
        return token;
    }

    @Override
    public synchronized boolean refreshSession() {
        if (refreshing.get()) return false;
        String refreshToken = getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) return false;
        refreshing.set(true);
        try {
            JSONObject body = new JSONObject();
            body.put("refresh_token", refreshToken);

            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");

            // Unauthenticated: attaching a session token here would call
            // getAccessToken() again and recurse until StackOverflow.
            SupabaseClient.HttpResponse resp = client.postUnauthenticated(
                    "/auth/v1/token?grant_type=refresh_token", body.toString(), headers);
            if (resp.isSuccess()) {
                JSONObject json = new JSONObject(resp.body);
                saveSessionTokens(json);
                return true;
            } else if (resp.statusCode == 400 || resp.statusCode == 401) {
                // Refresh token invalid or revoked; clear session
                clearSession();
                return false;
            }
        } catch (Exception e) {
            // Network failure or parsing failure; do not wipe tokens yet
        } finally {
            refreshing.set(false);
        }
        return false;
    }

    private void saveSessionTokens(JSONObject json) throws Exception {
        String accessToken = json.getString("access_token");
        String refreshToken = json.optString("refresh_token", getRefreshToken());
        long expiresInSec = json.optLong("expires_in", 3600L);
        long expiresAt = System.currentTimeMillis() + (expiresInSec * 1000L);

        securePrefs.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .apply();

        if (json.has("user")) {
            JSONObject user = json.getJSONObject("user");
            if (user.has("id")) {
                syncPrefs.edit().putString(KEY_USER_ID, user.getString("id")).apply();
            }
            if (user.has("email")) {
                syncPrefs.edit().putString(KEY_EMAIL, user.getString("email")).apply();
            }
        }
    }

    public void sendOtp(String email, AuthCallback callback) {
        if (!SupabaseConfig.isConfigured()) {
            notifyError(callback, "Supabase credentials are not configured in build.");
            return;
        }
        final String trimmedEmail = email != null ? email.trim() : "";
        if (!isValidEmail(trimmedEmail)) {
            notifyError(callback, "Please enter a valid email address.");
            return;
        }

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", trimmedEmail);
                body.put("create_user", true);

                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");

                SupabaseClient.HttpResponse resp = client.postUnauthenticated("/auth/v1/otp", body.toString(), headers);
                if (resp.isSuccess()) {
                    notifySuccess(callback, "Verification code sent to " + trimmedEmail);
                } else if (resp.statusCode == 429) {
                    notifyError(callback, "Too many attempts. Please wait a minute before requesting another code.");
                } else {
                    notifyError(callback, resp.getErrorMessage());
                }
            } catch (Exception e) {
                notifyError(callback, "Failed to connect. Check your internet connection.");
            }
        });
    }

    public void verifyOtp(String email, String token, AuthCallback callback) {
        if (!SupabaseConfig.isConfigured()) {
            notifyError(callback, "Supabase credentials are not configured in build.");
            return;
        }
        final String trimmedEmail = email != null ? email.trim() : "";
        final String trimmedToken = token != null ? token.trim() : "";

        if (trimmedToken.length() < 6 || trimmedToken.length() > 10) {
            notifyError(callback, "Please enter a valid verification code.");
            return;
        }

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("type", "email");
                body.put("email", trimmedEmail);
                body.put("token", trimmedToken);

                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");

                SupabaseClient.HttpResponse resp = client.postUnauthenticated("/auth/v1/verify", body.toString(), headers);
                if (resp.isSuccess()) {
                    JSONObject json = new JSONObject(resp.body);
                    saveSessionTokens(json);
                    syncPrefs.edit().putString(KEY_EMAIL, trimmedEmail).apply();
                    notifySuccess(callback, "Authenticated successfully");
                } else if (resp.statusCode == 400 || resp.statusCode == 401 || resp.statusCode == 403) {
                    notifyError(callback, "Code is invalid or expired. Request a new one.");
                } else {
                    notifyError(callback, resp.getErrorMessage());
                }
            } catch (Exception e) {
                notifyError(callback, "Verification failed: " + e.getMessage());
            }
        });
    }

    public void signOut(AuthCallback callback) {
        executor.execute(() -> {
            String token = getRawAccessToken();
            if (token != null) {
                try {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Authorization", "Bearer " + token);
                    client.postUnauthenticated("/auth/v1/logout", "{}", headers);
                } catch (Exception ignored) {}
            }
            clearSession();
            notifySuccess(callback, "Signed out");
        });
    }

    public synchronized void clearSession() {
        securePrefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_EXPIRES_AT)
                .apply();
        syncPrefs.edit()
                .remove(KEY_USER_ID)
                .remove(KEY_EMAIL)
                .remove(KEY_FIRST_SYNC_DONE)
                .apply();
    }

    public void restoreSession() {
        if (isSignedIn()) {
            executor.execute(this::refreshSession);
        }
    }

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email.trim()).matches();
    }

    private void notifySuccess(AuthCallback callback, String msg) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSuccess(msg));
        }
    }

    private void notifyError(AuthCallback callback, String err) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(err));
        }
    }
}
