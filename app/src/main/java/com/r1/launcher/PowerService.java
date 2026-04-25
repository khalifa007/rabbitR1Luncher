package com.r1.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class PowerService extends AccessibilityService {
    private static final String TAG = "R1Power";
    public static PowerService instance;

    // Long-press any activate-class key (PTT candidates) to return to launcher.
    // We don't consume the keydown — short presses still reach the foreground app.
    private static final long LONG_PRESS_MS = 700L;
    private final Handler holdHandler = new Handler(Looper.getMainLooper());
    private int holdCode = -1;
    private final Runnable holdHomeRunnable = new Runnable() {
        @Override public void run() {
            holdCode = -1;
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    };

    // Words we'll click while Updater.autoClickInstallUntil is in the future.
    // Ordered: install/update first, then post-install dismiss buttons.
    private static final String[] CLICK_WORDS = {
        "Install", "INSTALL", "install",
        "Update", "UPDATE", "update",
        "Done", "DONE", "done",
        "Open", "OPEN", "open",
        "Got it", "OK", "Ok"
    };

    private static final String[] INSTALLER_HINTS = {
        "packageinstaller", "packageinstall", "installer"
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (System.currentTimeMillis() > Updater.autoClickInstallUntil) return;

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;

        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();
        boolean isInstaller = false;
        for (String hint : INSTALLER_HINTS) {
            if (pkg.toLowerCase().contains(hint)) { isInstaller = true; break; }
        }
        if (!isInstaller) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        for (String w : CLICK_WORDS) {
            if (clickByText(root, w)) return;
        }
    }

    private boolean clickByText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null) return false;
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null) continue;
            // exact or near-exact match to avoid clicking "Cancel"
            CharSequence t = n.getText();
            if (t == null) continue;
            String got = t.toString().trim();
            if (!got.equalsIgnoreCase(text)) continue;

            AccessibilityNodeInfo target = n;
            while (target != null && !target.isClickable()) target = target.getParent();
            if (target != null && target.isClickable() && target.isEnabled()) {
                boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (ok) {
                    Log.d(TAG, "auto-clicked: " + got);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null) return false;
        int code = event.getKeyCode();

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            Log.d(TAG, "key code=" + code + " sc=" + event.getScanCode()
                + " name=" + KeyEvent.keyCodeToString(code)
                + " ptt=" + isPttCandidate(code));
        }

        if (!isPttCandidate(code)) return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                holdCode = code;
                holdHandler.removeCallbacks(holdHomeRunnable);
                holdHandler.postDelayed(holdHomeRunnable, LONG_PRESS_MS);
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            if (code == holdCode) {
                holdCode = -1;
                holdHandler.removeCallbacks(holdHomeRunnable);
            }
        }
        // don't consume — foreground app still gets the short-press
        return false;
    }

    // Denylist: wheel scroll and system keys never count as PTT. Any other
    // key counts — we don't yet know the R1's actual PTT keycode, so we cast
    // a wide net. Trim this down once logcat reveals the real code.
    private static boolean isPttCandidate(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_UNKNOWN:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return false;
        }
        return true;
    }

    @Override public void onInterrupt() {}

    @Override
    public void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    public static boolean openPowerDialog() {
        PowerService s = instance;
        if (s == null) return false;
        return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
    }

    public static boolean lockScreen() {
        PowerService s = instance;
        if (s == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
    }
}
