package com.xsr.companion.sync;

import com.xsr.companion.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Low-level HTTP client for Supabase Auth and PostgREST endpoints.
 * Uses only HttpURLConnection and org.json to keep APK footprint minimal.
 */
public class SupabaseClient {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final String USER_AGENT = "XSR-Companion/" + BuildConfig.VERSION_NAME;

    public interface SessionCallback {
        String getAccessToken();
        boolean refreshSession();
    }

    public static class HttpResponse {
        public final int statusCode;
        public final String body;
        public final Map<String, List<String>> headers;

        public HttpResponse(int statusCode, String body, Map<String, List<String>> headers) {
            this.statusCode = statusCode;
            this.body = body;
            this.headers = headers != null ? headers : new HashMap<>();
        }

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        public String getErrorMessage() {
            if (body == null || body.trim().isEmpty()) {
                return "HTTP " + statusCode;
            }
            try {
                JSONObject obj = new JSONObject(body);
                if (obj.has("message")) return obj.getString("message");
                if (obj.has("msg")) return obj.getString("msg");
                if (obj.has("error_description")) return obj.getString("error_description");
                if (obj.has("error")) return obj.getString("error");
            } catch (Exception ignored) {}
            return body;
        }
    }

    private final String baseUrl;
    private final String anonKey;
    private SessionCallback sessionCallback;

    public SupabaseClient(String baseUrl, String anonKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.anonKey = anonKey;
    }

    public void setSessionCallback(SessionCallback callback) {
        this.sessionCallback = callback;
    }

    public HttpResponse get(String path, Map<String, String> extraHeaders) throws Exception {
        return requestWithRetry("GET", path, null, extraHeaders, false);
    }

    public HttpResponse post(String path, String jsonBody, Map<String, String> extraHeaders) throws Exception {
        return requestWithRetry("POST", path, jsonBody, extraHeaders, false);
    }

    public HttpResponse patch(String path, String jsonBody, Map<String, String> extraHeaders) throws Exception {
        return requestWithRetry("PATCH", path, jsonBody, extraHeaders, false);
    }

    public HttpResponse delete(String path, Map<String, String> extraHeaders) throws Exception {
        return requestWithRetry("DELETE", path, null, extraHeaders, false);
    }

    /**
     * Requests that must NOT attach a session token or trigger a token refresh
     * (e.g. /auth/v1/token, /auth/v1/otp, /auth/v1/verify, /auth/v1/logout).
     * Using the authenticated {@link #post} for a refresh call would re-enter
     * getAccessToken() -> refreshSession() -> post(...) and overflow the stack.
     */
    public HttpResponse postUnauthenticated(String path, String jsonBody, Map<String, String> extraHeaders) throws Exception {
        return requestWithRetry("POST", path, jsonBody, extraHeaders, true);
    }

    private HttpResponse requestWithRetry(String method, String path, String body,
                                          Map<String, String> extraHeaders, boolean skipAuth) throws Exception {
        HttpResponse response = executeSingle(method, path, body, extraHeaders, skipAuth);
        if (!skipAuth && response.statusCode == 401 && sessionCallback != null) {
            // Attempt silent refresh and retry once
            boolean refreshed = sessionCallback.refreshSession();
            if (refreshed) {
                response = executeSingle(method, path, body, extraHeaders, false);
            }
        }
        return response;
    }

    private HttpResponse executeSingle(String method, String path, String body,
                                       Map<String, String> extraHeaders, boolean skipAuth) throws Exception {
        String fullUrl = path.startsWith("http://") || path.startsWith("https://")
                ? path
                : (baseUrl + (path.startsWith("/") ? path : "/" + path));

        URL url = new URL(fullUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setDoInput(true);

            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("apikey", anonKey);

            if (!skipAuth) {
                String token = sessionCallback != null ? sessionCallback.getAccessToken() : null;
                if (token != null && !token.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                }
            }

            if (extraHeaders != null) {
                for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            if (body != null && !body.isEmpty()) {
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                }
                conn.setDoOutput(true);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = "";
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    responseBody = sb.toString().trim();
                }
            }
            return new HttpResponse(code, responseBody, conn.getHeaderFields());
        } finally {
            conn.disconnect();
        }
    }
}
