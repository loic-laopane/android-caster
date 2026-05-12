package com.caster.app.screen

import android.app.*
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.caster.app.MainActivity

class ScreenMirrorService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): ScreenMirrorService = this@ScreenMirrorService
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var isRunning = false

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA) ?: return START_NOT_STICKY

        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(projectionCallback, null)

        startEncoding()
        return START_STICKY
    }

    override fun onDestroy() {
        stopEncoding()
        super.onDestroy()
    }

    fun stopMirroring() {
        stopEncoding()
        stopSelf()
    }

    val isMirroring: Boolean get() = isRunning

    private fun startEncoding() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            val w = metrics.widthPixels
            val h = metrics.heightPixels
            val dpi = metrics.densityDpi

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder?.createInputSurface()

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenMirror", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )

            encoder?.start()
            isRunning = true
            Log.i(TAG, "Screen mirroring started at ${w}x${h}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encoding", e)
            isRunning = false
        }
    }

    private fun stopEncoding() {
        isRunning = false
        try {
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) { /* ignore */ }
        virtualDisplay?.release()
        mediaProjection?.stop()
        encoder = null
        virtualDisplay = null
        mediaProjection = null
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopEncoding()
        }
    }

    private fun createNotificationChannel() {
        // NotificationChannel required on API 26+ — use reflection to compile against API 23
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val channelClass = Class.forName("android.app.NotificationChannel")
                val channel = channelClass
                    .getConstructor(String::class.java, CharSequence::class.java, Int::class.java)
                    .newInstance(CHANNEL_ID, "Screen Mirror", 2 /* IMPORTANCE_LOW */)
                val nm = getSystemService(NotificationManager::class.java)
                nm.javaClass.getMethod("createNotificationChannel", channelClass).invoke(nm, channel)
            } catch (e: Exception) {
                Log.w(TAG, "createNotificationChannel: ${e.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        // FLAG_IMMUTABLE required when targeting SDK 31+
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags)
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenMirrorService::class.java).setAction(ACTION_STOP), flags)

        return if (Build.VERSION.SDK_INT >= 26) {
            // Use reflection to call Notification.Builder(context, channelId) — added in API 26
            try {
                val builderClass = Notification.Builder::class.java
                val builder = builderClass
                    .getConstructor(android.content.Context::class.java, String::class.java)
                    .newInstance(this, CHANNEL_ID)
                builderClass.getMethod("setContentTitle", CharSequence::class.java)
                    .invoke(builder, "Diffusion d'écran active")
                builderClass.getMethod("setContentText", CharSequence::class.java)
                    .invoke(builder, "Appuyez pour gérer")
                builderClass.getMethod("setSmallIcon", Int::class.java)
                    .invoke(builder, android.R.drawable.ic_menu_slideshow)
                builderClass.getMethod("setContentIntent", PendingIntent::class.java)
                    .invoke(builder, pendingIntent)
                builderClass.getMethod("build").invoke(builder) as Notification
            } catch (e: Exception) {
                buildLegacyNotification(pendingIntent, stopIntent)
            }
        } else {
            buildLegacyNotification(pendingIntent, stopIntent)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildLegacyNotification(
        contentIntent: PendingIntent, stopIntent: PendingIntent
    ): Notification = Notification.Builder(this)
        .setContentTitle("Diffusion d'écran active")
        .setContentText("Appuyez pour gérer")
        .setSmallIcon(android.R.drawable.ic_menu_slideshow)
        .setContentIntent(contentIntent)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", stopIntent)
        .build()

    companion object {
        private const val TAG = "ScreenMirrorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_mirror_channel"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.caster.app.STOP_MIRROR"
    }
}
