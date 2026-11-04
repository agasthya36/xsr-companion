package com.xsr.companion.settings;

import java.util.Locale;

/**
 * Converts the canonical metric values used everywhere in the app into the
 * currently selected display system.
 */
public final class Units {

    public static final double KM_TO_MI = 0.621371;
    public static final double L_TO_US_GAL = 0.264172;
    public static final double KML_TO_MPG_US = 2.35215;
    public static final double KMH_TO_MPH = 0.621371;

    private Units() {}

    public static String speed(SettingsManager s, double kmh) {
        if (s.isImperial()) {
            return String.format(Locale.getDefault(), "%.0f mph", kmh * KMH_TO_MPH);
        }
        return String.format(Locale.getDefault(), "%.0f km/h", kmh);
    }

    public static String speedValue(SettingsManager s, double kmh) {
        return String.format(Locale.getDefault(), "%.0f", s.isImperial() ? kmh * KMH_TO_MPH : kmh);
    }

    public static String speedUnit(SettingsManager s) {
        return s.isImperial() ? "mph" : "km/h";
    }

    public static String distance(SettingsManager s, double km) {
        if (s.isImperial()) {
            return String.format(Locale.getDefault(), "%.1f mi", km * KM_TO_MI);
        }
        return String.format(Locale.getDefault(), "%.1f km", km);
    }

    public static String distanceValue(SettingsManager s, double km) {
        return String.format(Locale.getDefault(), "%.1f", s.isImperial() ? km * KM_TO_MI : km);
    }

    public static String distanceUnit(SettingsManager s) {
        return s.isImperial() ? "mi" : "km";
    }

    public static String shortDistance(SettingsManager s, double km) {
        if (s.isImperial()) {
            return String.format(Locale.getDefault(), "%.0f mi", km * KM_TO_MI);
        }
        return String.format(Locale.getDefault(), "%.0f km", km);
    }

    public static String temp(SettingsManager s, int celsius) {
        if (s.isImperial()) {
            return String.format(Locale.getDefault(), "%.0f °F", celsius * 9.0 / 5.0 + 32.0);
        }
        return String.format(Locale.getDefault(), "%d °C", celsius);
    }

    public static String tempValue(SettingsManager s, int celsius) {
        return String.format(Locale.getDefault(), "%.0f", s.isImperial() ? celsius * 9.0 / 5.0 + 32.0 : celsius);
    }

    public static String tempUnit(SettingsManager s) {
        return s.isImperial() ? "°F" : "°C";
    }

    public static String mileage(SettingsManager s, double kmL) {
        if (kmL <= 0.05) return "--";
        if (s.isImperial()) {
            return String.format(Locale.getDefault(), "%.1f mpg", kmL * KML_TO_MPG_US);
        }
        return String.format(Locale.getDefault(), "%.1f km/L", kmL);
    }

    public static String mileageValue(SettingsManager s, double kmL) {
        if (kmL <= 0.05) return "--";
        return String.format(Locale.getDefault(), "%.1f", s.isImperial() ? kmL * KML_TO_MPG_US : kmL);
    }

    public static String mileageUnit(SettingsManager s) {
        return s.isImperial() ? "mpg" : "km/L";
    }

    public static String fuel(SettingsManager s, double litres) {
        if (s.isImperial()) {
            return String.format(Locale.getDefault(), "%.2f gal", litres * L_TO_US_GAL);
        }
        return String.format(Locale.getDefault(), "%.2f L", litres);
    }

    public static String fuelValue(SettingsManager s, double litres) {
        return String.format(Locale.getDefault(), "%.2f", s.isImperial() ? litres * L_TO_US_GAL : litres);
    }

    public static String fuelUnit(SettingsManager s) {
        return s.isImperial() ? "gal" : "L";
    }

    public static String fuelRate(SettingsManager s, double lph) {
        if (s.isImperial()) {
            return String.format(Locale.getDefault(), "%.2f gal/h", lph * L_TO_US_GAL);
        }
        return String.format(Locale.getDefault(), "%.2f L/h", lph);
    }

    /** Distance in the selected unit, used for maintenance intervals. */
    public static String interval(SettingsManager s, int km) {
        if (s.isImperial()) {
            return String.format(Locale.getDefault(), "%,.0f mi", km * KM_TO_MI);
        }
        return String.format(Locale.getDefault(), "%,d km", km);
    }
}
