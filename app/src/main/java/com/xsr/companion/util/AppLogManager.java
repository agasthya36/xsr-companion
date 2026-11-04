package com.xsr.companion.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * Thread-safe in-memory rolling log buffer and file exporter.
 * Captures live BLE events, GATT callbacks, protocol packets, and lifecycle events.
 */
public class AppLogManager {

    private static final int MAX_LOG_LINES = 1000;
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat FILE_DATE_FMT = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private static AppLogManager sInstance;

    private final LinkedList<String> logEntries = new LinkedList<>();
    private final List<OnLogListener> listeners = new ArrayList<>();

    public interface OnLogListener {
        void onNewLogLine(String formattedLine);
    }

    private AppLogManager() {}

    public static synchronized AppLogManager getInstance() {
        if (sInstance == null) {
            sInstance = new AppLogManager();
        }
        return sInstance;
    }

    public void log(String tag, String message) {
        // Output to standard Android Logcat
        Log.i(tag, message);

        String timestamp;
        synchronized (TIME_FMT) {
            timestamp = TIME_FMT.format(new Date());
        }
        String entry = "[" + timestamp + "] [" + tag + "] " + message;

        List<OnLogListener> listenersCopy;
        synchronized (this) {
            logEntries.add(entry);
            if (logEntries.size() > MAX_LOG_LINES) {
                logEntries.removeFirst();
            }
            listenersCopy = new ArrayList<>(listeners);
        }

        for (OnLogListener listener : listenersCopy) {
            try {
                listener.onNewLogLine(entry);
            } catch (Exception ignored) {}
        }
    }

    public void log(String tag, String format, Object... args) {
        try {
            log(tag, String.format(Locale.US, format, args));
        } catch (Exception e) {
            log(tag, format);
        }
    }

    public synchronized List<String> getAllLogs() {
        return new ArrayList<>(logEntries);
    }

    public synchronized String getLogsAsString() {
        StringBuilder sb = new StringBuilder();
        for (String line : logEntries) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    public synchronized void clear() {
        logEntries.clear();
    }

    public synchronized void addListener(OnLogListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public synchronized void removeListener(OnLogListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Exports all in-memory logs to a timestamped file in app's external files directory.
     */
    public File exportToFile(Context context) throws IOException {
        String content = getLogsAsString();
        File logsDir = new File(context.getExternalFilesDir(null), "logs");
        if (!logsDir.exists()) {
            logsDir.mkdirs();
        }

        String fileName;
        synchronized (FILE_DATE_FMT) {
            fileName = "xsr_companion_log_" + FILE_DATE_FMT.format(new Date()) + ".txt";
        }

        File logFile = new File(logsDir, fileName);
        try (FileOutputStream fos = new FileOutputStream(logFile)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
        return logFile;
    }
}
