package com.xsr.companion.sync;

import com.xsr.companion.BuildConfig;

/**
 * Accessor for Supabase project credentials built into the application.
 */
public final class SupabaseConfig {

    private SupabaseConfig() {}

    public static String getUrl() {
        return BuildConfig.SUPABASE_URL != null ? BuildConfig.SUPABASE_URL.trim() : "";
    }

    public static String getAnonKey() {
        return BuildConfig.SUPABASE_ANON_KEY != null ? BuildConfig.SUPABASE_ANON_KEY.trim() : "";
    }

    public static boolean isConfigured() {
        return !getUrl().isEmpty() && !getAnonKey().isEmpty();
    }
}
