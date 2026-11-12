package org.ashkeshadek.reader;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves the verified pending update APK to Android's own installer UI on
 * Android 7 (API 24/25), where a file:// URI in an intent trips StrictMode
 * on apps targeting N and later. Exposes exactly one file - the app's own
 * updates/ directory - read-only, and readable only when the caller was
 * explicitly granted it with FLAG_GRANT_READ_URI_PERMISSION; the provider
 * itself is never exported. The PackageInstaller session path (API 26+)
 * does not use it.
 */
public final class UpdateApkProvider extends ContentProvider {
    static final String AUTHORITY = "org.ashkeshadek.reader.updateapk";
    static final String APK_MIME = "application/vnd.android.package-archive";
    /** The one file this provider will ever serve; AppUpdater writes to it. */
    static final String UPDATE_APK_FILE = "ashke-shadek-update.apk";

    /** Content URI for the pending update APK inside the app's files/updates directory. */
    static Uri uriFor(Context context, File apk) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY).appendPath(apk.getName()).build();
    }

    static File pendingApkFile(Context context) {
        return new File(new File(context.getFilesDir(), "updates"), UPDATE_APK_FILE);
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return APK_MIME; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (ParcelFileDescriptor.parseMode(mode) != ParcelFileDescriptor.MODE_READ_ONLY) {
            throw new SecurityException("Read-only provider");
        }
        File apk = pendingApkFile(getContext());
        if (!apk.isFile() || !UPDATE_APK_FILE.equals(uri.getLastPathSegment())) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        // The installer asks for the stream size via OpenableColumns.
        if (projection != null && projection.length == 1 && OpenableColumns.SIZE.equals(projection[0])) {
            File apk = pendingApkFile(getContext());
            MatrixCursor cursor = new MatrixCursor(projection);
            if (apk != null && apk.isFile()) {
                cursor.addRow(new Object[]{apk.length()});
            }
            return cursor;
        }
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) { return 0; }
}
