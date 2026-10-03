package io.github.ponpokoo.mastodonclient;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

// Runs in the test APK's separate process, which has no target-app Kotlin runtime.
// Only fixed synthetic fixtures are exposed; user files cannot be addressed.
public class SharedMediaTestProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (Arrays.asList("jpeg", "jpeg-two", "broken", "no-metadata").contains(name)) return "image/jpeg";
        if ("mp4".equals(name)) return "video/mp4";
        if ("mp3".equals(name)) return "audio/mpeg";
        if ("broad".equals(name)) return "audio/*";
        if ("wrong-extension".equals(name)) return "application/pdf";
        return null;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String name = uri.getLastPathSegment();
        if ("denied".equals(name)) throw new SecurityException("Synthetic permission failure");
        if ("no-metadata".equals(name)) throw new UnsupportedOperationException("No display name");
        if (Arrays.asList("fallback", "broad", "wrong-extension").contains(name)) name = "song.mp3";
        if ("unknown".equals(name)) name = "unknown.unknown-extension";
        MatrixCursor cursor = new MatrixCursor(new String[] { OpenableColumns.DISPLAY_NAME });
        cursor.addRow(new Object[] { name });
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!"r".equals(mode) || !Arrays.asList("jpeg", "jpeg-two", "mp4", "mp3", "fallback", "broad", "wrong-extension", "no-metadata").contains(name))
            throw new FileNotFoundException("Unknown synthetic fixture");
        File file = new File(getContext().getCacheDir(), "shared-test-" + name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[] { 1, 2, 3, 4 });
        } catch (IOException error) {
            throw new FileNotFoundException("Cannot create synthetic fixture");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
