package com.r1.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppStore {
    private static final String TAG = "R1AppStore";

    public static final String DEFAULT_CATALOG_URL = "https://rabbit.luma.om/apps/catalog.json";

    public static final String PHASE_IDLE        = "IDLE";
    public static final String PHASE_LOADING     = "LOADING";
    public static final String PHASE_READY       = "READY";
    public static final String PHASE_DOWNLOADING = "DOWNLOADING";
    public static final String PHASE_INSTALLING  = "INSTALLING";
    public static final String PHASE_ERROR       = "ERROR";

    public static class Entry {
        public final String slug;
        public final String pkg;
        public final String name;
        public final String tagline;
        public final int versionCode;
        public final String versionName;
        public final String apkUrl;
        public final String sha256;
        public final long sizeBytes;
        public final String iconUrl; // nullable

        Entry(JSONObject o) {
            slug = o.optString("slug");
            pkg = o.optString("package");
            name = o.optString("name");
            tagline = o.optString("tagline", "");
            versionCode = o.optInt("versionCode", 0);
            versionName = o.optString("versionName", "");
            apkUrl = o.optString("apkUrl");
            sha256 = o.optString("sha256", "").trim();
            sizeBytes = o.optLong("sizeBytes", 0);
            String i = o.optString("iconUrl", "");
            iconUrl = i.isEmpty() ? null : i;
        }
    }

    public interface StatusListener {
        void onStore(String phase, String slug, int pct, String msg);
    }

    public interface CatalogListener {
        void onCatalog(List<Entry> entries, String error);
    }

    public interface IconListener {
        void onIcon(String slug, Drawable d);
    }

    private final Context appCtx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HandlerThread worker;
    private final Handler bg;
    private String catalogUrl = DEFAULT_CATALOG_URL;
    private StatusListener statusListener;
    private final List<Entry> cache = new ArrayList<>();
    private final Map<String, Drawable> iconCache = new HashMap<>();

    public AppStore(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.worker = new HandlerThread("r1-appstore");
        this.worker.start();
        this.bg = new Handler(worker.getLooper());
    }

    public void setStatusListener(StatusListener l) { this.statusListener = l; }
    public void setCatalogUrl(String url) { this.catalogUrl = url; }
    public List<Entry> cachedEntries() { return cache; }
    public void shutdown() { worker.quitSafely(); }

    public void fetchCatalog(final CatalogListener cb) {
        status(PHASE_LOADING, null, 0, null);
        bg.post(() -> {
            try {
                JSONObject root = fetchJson(catalogUrl);
                JSONArray arr = root.optJSONArray("apps");
                List<Entry> out = new ArrayList<>();
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    out.add(new Entry(arr.getJSONObject(i)));
                }
                synchronized (cache) { cache.clear(); cache.addAll(out); }
                status(PHASE_READY, null, 0, null);
                final List<Entry> snap = Collections.unmodifiableList(out);
                if (cb != null) ui.post(() -> cb.onCatalog(snap, null));
            } catch (Exception e) {
                Log.w(TAG, "catalog fetch failed: " + e);
                status(PHASE_ERROR, null, 0, e.getClass().getSimpleName());
                if (cb != null) ui.post(() -> cb.onCatalog(null, e.getClass().getSimpleName()));
            }
        });
    }

    public void install(final Entry e) {
        bg.post(() -> installSync(e));
    }

    private void installSync(Entry e) {
        try {
            status(PHASE_DOWNLOADING, e.slug, 0, "v" + e.versionName);
            File out = new File(appCtx.getCacheDir(), "store_" + e.slug + ".apk");
            String actualSha = downloadWithSha(e.apkUrl, out, e.slug, e.sizeBytes);
            if (!e.sha256.isEmpty() && !e.sha256.equalsIgnoreCase(actualSha)) {
                Log.w(TAG, "sha mismatch for " + e.slug);
                status(PHASE_ERROR, e.slug, 0, "sha mismatch");
                return;
            }
            status(PHASE_INSTALLING, e.slug, 100, "v" + e.versionName);
            fireInstallIntent(out);
        } catch (Exception ex) {
            Log.w(TAG, "install failed for " + e.slug + ": " + ex);
            status(PHASE_ERROR, e.slug, 0, ex.getClass().getSimpleName());
        }
    }

    private void fireInstallIntent(File apk) {
        try {
            Uri uri = ApkProvider.uriFor(apk.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Updater.autoClickInstallUntil = System.currentTimeMillis() + 60_000L;
            appCtx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "install intent failed: " + e);
        }
    }

    public void uninstall(String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Updater.autoClickInstallUntil = System.currentTimeMillis() + 60_000L;
            appCtx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "uninstall intent failed: " + e);
        }
    }

    /** 0 = not installed, else positive versionCode. */
    public int installedVersionCode(String pkg) {
        try {
            PackageInfo pi = appCtx.getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    public void loadIcon(final Entry e, final IconListener cb) {
        if (e.iconUrl == null) { if (cb != null) cb.onIcon(e.slug, null); return; }
        Drawable cached;
        synchronized (iconCache) { cached = iconCache.get(e.slug); }
        if (cached != null) { if (cb != null) cb.onIcon(e.slug, cached); return; }

        bg.post(() -> {
            try {
                File dir = new File(appCtx.getCacheDir(), "store_icons");
                dir.mkdirs();
                File dest = new File(dir, e.slug + ".png");
                if (!dest.exists() || dest.length() == 0) downloadTo(e.iconUrl, dest);
                Bitmap bm = BitmapFactory.decodeFile(dest.getAbsolutePath());
                if (bm == null) { if (cb != null) ui.post(() -> cb.onIcon(e.slug, null)); return; }
                BitmapDrawable d = new BitmapDrawable(appCtx.getResources(), bm);
                synchronized (iconCache) { iconCache.put(e.slug, d); }
                if (cb != null) ui.post(() -> cb.onIcon(e.slug, d));
            } catch (Exception ex) {
                Log.w(TAG, "icon load failed " + e.slug + ": " + ex);
                if (cb != null) ui.post(() -> cb.onIcon(e.slug, null));
            }
        });
    }

    // --- http helpers ---

    private JSONObject fetchJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        conn.setRequestProperty("Cache-Control", "no-cache");
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return new JSONObject(buf.toString("UTF-8"));
        } finally { conn.disconnect(); }
    }

    private String downloadWithSha(String url, File dest, String slug, long expectedSize) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        long total = expectedSize > 0 ? expectedSize : conn.getContentLengthLong();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long read = 0;
        int lastPct = -1;
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                read += n;
                if (total > 0) {
                    int pct = (int) Math.min(100, (read * 100L) / total);
                    if (pct != lastPct && pct % 5 == 0) {
                        lastPct = pct;
                        status(PHASE_DOWNLOADING, slug, pct, null);
                    }
                }
            }
            out.flush();
        } finally { conn.disconnect(); }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private void downloadTo(String url, File dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(15_000);
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally { conn.disconnect(); }
    }

    private void status(String phase, String slug, int pct, String msg) {
        final StatusListener l = statusListener;
        if (l == null) return;
        ui.post(() -> l.onStore(phase, slug, pct, msg));
    }
}
