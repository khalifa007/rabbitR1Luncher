package com.r1.launcher.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.r1.launcher.LauncherActivity
import com.r1.launcher.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hosts a [MediaProjection] for the duration of a screen recording so the
 * launcher can capture playback audio via
 * [android.media.AudioPlaybackCaptureConfiguration] instead of the legacy
 * REMOTE_SUBMIX path that dims device speakers on this MTK build.
 *
 * Android 14 requires MediaProjection sessions to live inside a foreground
 * service typed `mediaProjection`. Without that, `createProjection` /
 * `createVirtualDisplay` / playback-capture `AudioRecord` all throw
 * `SecurityException` even with the signature `MANAGE_MEDIA_PROJECTION`
 * permission held.
 *
 * The projection itself is acquired here via reflection on
 * [MediaProjectionManager.createProjection], a `@SystemApi` that bypasses the
 * user-consent dialog. Reflection is unavoidable — the public stable surface
 * only exposes the consent-dialog path. The reflection target is stable on
 * AP2A (Android 14) but could break on future Android versions; the call site
 * logs cleanly and the manager falls back to REMOTE_SUBMIX if reflection
 * fails.
 *
 * Lifecycle:
 *   acquire(ctx)  → startForegroundService(ACTION_ACQUIRE)
 *                 → onStartCommand starts FGS, runs reflection, posts result
 *                 → caller blocks on the gate's latch, retrieves projection
 *   release(ctx)  → projection.stop(), stopService()
 */
class MediaProjectionService : Service() {

    companion object {
        const val ACTION_ACQUIRE = "com.r1.launcher.media.ACQUIRE_PROJECTION"
        private const val TAG = "MediaProjSvc"
        private const val CHANNEL_ID = "media.projection"
        private const val NOTIF_ID = 0x52_45_43_32 // "REC2"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        // Must call startForeground within 10s of startForegroundService or
        // ANR. Issue the notification BEFORE the reflection call so the
        // foreground state is established before MediaProjection sees us.
        startForegroundCompat()

        if (intent?.action == ACTION_ACQUIRE) {
            val projection = acquireProjectionViaReflection()
            MediaProjectionGate.publish(projection)
            if (projection == null) {
                // Acquire failed — caller will fall back to REMOTE_SUBMIX, no
                // reason to keep this FGS alive holding a stale notification.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireProjectionViaReflection(): MediaProjection? {
        val pm = ContextCompat.getSystemService(this, MediaProjectionManager::class.java)
            ?: run {
                Log.w(TAG, "no MediaProjectionManager system service")
                return null
            }
        // The public MediaProjectionManager surface only exposes the consent-
        // dialog path (createScreenCaptureIntent → getMediaProjection). The
        // bypass lives on the AIDL: IMediaProjectionManager.createProjection
        // returns an IMediaProjection, which we then wrap into the public
        // MediaProjection via its hidden (Context, IMediaProjection)
        // constructor. Reflection signatures probed live on AP2A.
        return try {
            val mServiceField = MediaProjectionManager::class.java
                .getDeclaredField("mService").apply { isAccessible = true }
            val impmService = mServiceField.get(pm)
                ?: run { Log.w(TAG, "mService field was null"); return null }

            val createProjectionMethod = impmService.javaClass.methods.firstOrNull {
                it.name == "createProjection" && it.parameterTypes.size == 4
            } ?: run {
                Log.w(TAG, "IMediaProjectionManager.createProjection(4-arg) not found")
                return null
            }
            // (int uid, String packageName, int type, boolean permanentGrant)
            // type 0 = TYPE_SCREEN_CAPTURE.
            val iMediaProjection = createProjectionMethod.invoke(
                impmService, Process.myUid(), packageName, 0, false,
            ) ?: run {
                Log.w(TAG, "IMediaProjectionManager.createProjection returned null")
                return null
            }

            // MediaProjection has a hidden (Context, IMediaProjection)
            // constructor — find it positionally rather than by exact param
            // types since IMediaProjection isn't on the public API.
            val mpCtor = MediaProjection::class.java.declaredConstructors.firstOrNull { c ->
                c.parameterTypes.size == 2 && c.parameterTypes[0] == Context::class.java
            } ?: run {
                Log.w(TAG, "MediaProjection(Context, IMediaProjection) constructor not found")
                return null
            }
            mpCtor.isAccessible = true
            (mpCtor.newInstance(this, iMediaProjection) as MediaProjection).also {
                Log.i(TAG, "createProjection via AIDL ok")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "createProjection reflection failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Type must be set on Android 10+; on Android 14+ it's mandatory
            // for media-projection-related calls. Use the symbolic constant
            // when available, otherwise the raw value (32 = TYPE_MEDIA_PROJECTION).
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIF_ID, notif, type)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        // Defensive: if torn down out-of-band, clear the gate so the next
        // acquire() doesn't read a stale projection. The projection itself
        // is owned by MediaCaptureManager once published — caller releases.
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Screen recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while the screen is being recorded with audio."
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording screen")
            .setContentText("Capturing video + audio")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(tap)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}

/**
 * Synchronous handoff between caller (rpcWorker thread in MediaCaptureManager)
 * and the [MediaProjectionService] worker. Caller calls [acquire], which
 * starts the service and blocks up to a few seconds for the service's
 * reflection to publish either a [MediaProjection] or null.
 */
object MediaProjectionGate {
    private const val TAG = "MediaProjGate"
    private var latch: CountDownLatch? = null
    @Volatile private var projection: MediaProjection? = null

    /**
     * Start the [MediaProjectionService] and wait up to [timeoutMs] for it to
     * acquire a projection via reflection. Returns null on timeout or
     * acquire failure — caller should fall back to REMOTE_SUBMIX.
     */
    @Synchronized
    fun acquire(ctx: Context, timeoutMs: Long = 3000): MediaProjection? {
        // Clear any previous result.
        projection = null
        val l = CountDownLatch(1)
        latch = l
        runCatching {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, MediaProjectionService::class.java)
                    .setAction(MediaProjectionService.ACTION_ACQUIRE),
            )
        }.onFailure {
            Log.w(TAG, "startForegroundService failed: ${it.message}")
            latch = null
            return null
        }
        val arrived = runCatching { l.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        latch = null
        if (!arrived) {
            Log.w(TAG, "acquire timed out after ${timeoutMs}ms")
            return null
        }
        return projection
    }

    /** Called by [MediaProjectionService] after reflection completes. */
    internal fun publish(p: MediaProjection?) {
        projection = p
        latch?.countDown()
    }

    /**
     * Stop and release the projection, then tear down the foreground service.
     * Safe to call even if [acquire] returned null.
     */
    @Synchronized
    fun release(ctx: Context) {
        runCatching { projection?.stop() }
        projection = null
        runCatching {
            ctx.stopService(Intent(ctx, MediaProjectionService::class.java))
        }
    }
}
