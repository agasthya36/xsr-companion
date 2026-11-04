package com.xsr.companion.ui;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.xsr.companion.data.RideFileProvider;

import java.io.File;

public final class ExportUtils {

    private ExportUtils() {}

    public static void shareFile(Context context, File file, String mime, String subject) {
        if (context == null || file == null || !file.exists()) return;
        try {
            Uri uri = RideFileProvider.getUriForFile(context, file);

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, subject);
            // ClipData carries the grant through the chooser on every API
            // level, including pickers that only look at the clip.
            intent.setClipData(new ClipData(subject, new String[]{mime},
                    new ClipData.Item(uri)));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(intent, subject);
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(chooser);
        } catch (Exception e) {
            Toast.makeText(context, "Nothing available to share this file",
                    Toast.LENGTH_SHORT).show();
        }
    }
}
