package com.r1.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.r1.launcher.apk";

    public static Uri uriFor(String name) {
        return Uri.parse("content://" + AUTHORITY + "/" + name);
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        if (f == null || !f.exists()) throw new FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] proj, String sel, String[] selArgs, String order) {
        File f = fileFor(uri);
        String[] columns = (proj != null) ? proj
            : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = f == null ? "" : f.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = f == null ? 0L : f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    private File fileFor(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return null;
        return new File(getContext().getCacheDir(), name);
    }

    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
}
