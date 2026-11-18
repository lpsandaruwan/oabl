package com.softiva.oabl;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;

/** Saves a report picture to Pictures/OABL in the gallery and offers it to the share sheet. */
final class ReportShare {
    private ReportShare() { }

    /** The saved picture's address, or null when it could not be written. Call off the main thread. */
    static Uri save(Context context, Bitmap bitmap, String name) {
        if (Build.VERSION.SDK_INT < 29) return null;
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OABL");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("write failed");
        } catch (IOException | RuntimeException failure) {
            resolver.delete(uri, null, null);
            return null;
        }
        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    /** Saves text (a CSV) to Download/OABL; null when it could not be written. Call off the main thread. */
    static Uri saveText(Context context, String text, String name, String mime) {
        if (Build.VERSION.SDK_INT < 29) return null;
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/OABL");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IOException("write failed");
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failure) {
            resolver.delete(uri, null, null);
            return null;
        }
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    /** The share sheet for a picture and the file behind it. */
    static Intent chooser(Uri picture, Uri file, String subject) {
        if (file == null) return chooser(picture, subject);
        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
        send.setType("*/*");
        send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new java.util.ArrayList<>(java.util.Arrays.asList(picture, file)));
        send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, "Share " + subject);
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return chooser;
    }

    /** The share sheet for a saved picture. */
    static Intent chooser(Uri uri, String subject) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/png");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, "Share " + subject);
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return chooser;
    }
}
