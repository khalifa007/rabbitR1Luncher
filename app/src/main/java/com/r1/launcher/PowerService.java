package com.r1.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class PowerService extends AccessibilityService {
    private static final String TAG = "R1Power";
    public static PowerService instance;

    // Double-press any activate-class key (PTT candidates) to return to launcher.
    // We don't consume the keydown — the first press still reaches the foreground app.
    // 350ms is a comfortable double-tap window on the R1's small side button.
    private static final long DOUBLE_PRESS_MAX_MS = 350L;
    private long lastDownTime = 0L;
    private int lastDownCode = -1;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No-op: OTAUpdater installs silently via root (`pm install -r`) so there's
        // no system installer dialog to auto-tap. The accessibility service still
        // exists for its key-event handling (double-press home) and the static
        // power/lock helpers below.
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

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            long now = SystemClock.uptimeMillis();
            if (code == lastDownCode && (now - lastDownTime) <= DOUBLE_PRESS_MAX_MS) {
                // Second press within the double-tap window → return home.
                // Reset state so a third press doesn't immediately re-fire.
                lastDownCode = -1;
                lastDownTime = 0L;
                performGlobalAction(GLOBAL_ACTION_HOME);
            } else {
                // First press (or out-of-window) — record and let it pass through.
                lastDownCode = code;
                lastDownTime = now;
            }
        }
        // don't consume — foreground app still gets the press
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
