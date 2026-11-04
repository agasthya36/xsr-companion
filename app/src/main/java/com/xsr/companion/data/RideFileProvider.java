package com.xsr.companion.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Minimal, non-exported provider for sharing GPX / log / export files.
 * Paths are forced to stay inside the app's external files directory.
 */
public class RideFileProvider extends ContentProvider {

    // Must match the manifest placeholder ${applicationId}.fileprovider so
    // debug and release builds can be installed side by side.
    public static String authority(Context context) {
        return context.getPackageName() + ".fileprovider";
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = getFileForUri(uri);
        if (file == null || !file.exists()) return null;

        if (projection == null) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }

        MatrixCursor cursor = new MatrixCursor(projection, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : projection) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row.add(file.getName());
            } else if (OpenableColumns.SIZE.equals(col)) {
                row.add(file.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String path = uri.getPath();
        if (path != null && (path.endsWith(".txt") || path.endsWith(".csv"))) {
            return "text/plain";
        }
        if (path != null && path.endsWith(".zip")) {
            return "application/zip";
        }
        if (path != null && path.endsWith(".gpx")) {
            return "application/gpx+xml";
        }
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = getFileForUri(uri);
        if (file == null) {
            throw new FileNotFoundException("File not found: " + uri);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File getFileForUri(Uri uri) {
        Context context = getContext();
        if (context == null) return null;
        String path = uri.getPath();
        if (path == null) return null;
        if (path.startsWith("/")) path = path.substring(1);
        if (path.isEmpty() || path.contains("..")) return null;

        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        if (base == null) return null;

        try {
            String canonicalBase = base.getCanonicalPath() + File.separator;
            File candidate = new File(base, path);
            String canonical = candidate.getCanonicalPath();
            // Canonical path guard: a symlink or crafted segment must never
            // escape the app's private external files directory.
            if (!canonical.startsWith(canonicalBase)) return null;
            return candidate.isFile() ? candidate : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Builds a content URI that preserves the file's path relative to the app
     * files root, e.g. {@code content://authority/exports/xsr_export.zip}.
     * Carrying the subdirectory avoids ambiguity when different folders hold
     * files with the same name.
     */
    public static Uri getUriForFile(Context context, File file) {
        if (context == null || file == null) {
            throw new IllegalArgumentException("context and file are required");
        }
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        if (base == null) {
            throw new IllegalArgumentException("No app files directory available");
        }
        try {
            String canonicalBase = base.getCanonicalPath();
            String canonicalFile = file.getCanonicalPath();
            String prefix = canonicalBase + File.separator;
            if (!canonicalFile.startsWith(prefix)) {
                throw new IllegalArgumentException("File is outside the shareable directory");
            }
            Uri.Builder builder = new Uri.Builder()
                    .scheme("content")
                    .authority(authority(context));
            for (String segment : canonicalFile.substring(prefix.length()).split(File.separator)) {
                if (!segment.isEmpty()) builder.appendPath(segment);
            }
            return builder.build();
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to resolve file path", e);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
