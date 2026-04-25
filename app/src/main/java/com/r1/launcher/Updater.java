package com.r1.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

public class Updater {
    private static final String TAG = "R1Updater";

    // VPS endpoint. HTTPS preferred; cleartext http:// fallback is whitelisted in
    // res/xml/network_security_config.xml. Override at runtime via setMetadataUrl().
    public static final String DEFAULT_METADATA_URL =
        "https://rabbit.luma.om/update/latest.json";

    // Auto-click window for the accessibility service (ms since epoch)
    public static volatile long autoClickInstallUntil = 0L;

    public static final String PHASE_IDLE        = "IDLE";
    public static final String PHASE_CHECKING    = "CHECKING";
    public static final String PHASE_UP_TO_DATE  = "UP_TO_DATE";
    public static final String PHASE_DOWNLOADING = "DOWNLOADING";
    public static final String PHASE_INSTALLING  = "INSTALLING";
    public static final String PHASE_ERROR       = "ERROR";

    public interface StatusListener {
        void onUpdate(String phase, int pct, String msg);
    }

    private final Context appCtx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HandlerThread worker;
    private final Handler bg;

    private String metadataUrl = DEFAULT_METADATA_URL;
    private StatusListener listener;
    private int lastSeenRemoteCode = -1;

    public Updater(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.worker = new HandlerThread("r1-updater");
        this.worker.start();
        this.bg = new Handler(worker.getLooper());
    }

    public void setListener(StatusListener l) { this.listener = l; }

    public void setMetadataUrl(String url) {
        this.metadataUrl = url;
    }

    public void shutdown() {
        worker.quitSafely();
    }

    public void checkNow() {
        bg.post(this::checkOnceSync);
    }

    private void checkOnceSync() {
        status(PHASE_CHECKING, 0, null);
        try {
            JSONObject meta = fetchJson(metadataUrl);
            int remoteCode = meta.getInt("versionCode");
            String remoteName = meta.optString("versionName", String.valueOf(remoteCode));
            String apkUrl = meta.getString("apkUrl");
            String expectedSha = meta.optString("sha256", "").trim();

            int currentCode = currentVersionCode();
            if (remoteCode <= currentCode) {
                status(PHASE_UP_TO_DATE, 0, "v" + currentVersionName());
                return;
            }

            if (remoteCode == lastSeenRemoteCode) {
                // already attempted this version — don't loop install dialogs
                status(PHASE_IDLE, 0, null);
                return;
            }

            status(PHASE_DOWNLOADING, 0, "v" + remoteName);
            File out = new File(appCtx.getCacheDir(), "update.apk");
            String actualSha = downloadWithSha(apkUrl, out);
            if (!expectedSha.isEmpty() && !expectedSha.equalsIgnoreCase(actualSha)) {
                Log.w(TAG, "sha mismatch: expected=" + expectedSha + " actual=" + actualSha);
                status(PHASE_ERROR, 0, "sha mismatch");
                return;
            }

            lastSeenRemoteCode = remoteCode;
            status(PHASE_INSTALLING, 0, "v" + remoteName);
            installApk(out);
        } catch (Exception e) {
            Log.w(TAG, "check failed: " + e);
            status(PHASE_ERROR, 0, e.getClass().getSimpleName());
        }
    }

    private int currentVersionCode() {
        try {
            PackageInfo pi = appCtx.getPackageManager().getPackageInfo(appCtx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) { return -1; }
    }

    private String currentVersionName() {
        try {
            PackageInfo pi = appCtx.getPackageManager().getPackageInfo(appCtx.getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) { return "?"; }
    }

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

    private String downloadWithSha(String url, File dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
            }
            out.flush();
        } finally { conn.disconnect(); }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private void installApk(File apk) {
        try {
            Uri uri = ApkProvider.uriFor(apk.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            autoClickInstallUntil = System.currentTimeMillis() + 60_000L;
            appCtx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "install intent failed: " + e);
            status(PHASE_ERROR, 0, e.getClass().getSimpleName());
        }
    }

    private void status(String phase, int pct, String msg) {
        final StatusListener l = listener;
        if (l == null) return;
        ui.post(() -> l.onUpdate(phase, pct, msg));
    }
}
