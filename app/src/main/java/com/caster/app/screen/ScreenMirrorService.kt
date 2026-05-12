package com.caster.app.screen

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.caster.app.MainActivity
import com.caster.app.cast.DlnaCaster

class ScreenMirrorService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): ScreenMirrorService = this@ScreenMirrorService
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var streamServer: ScreenStreamServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val dlnaCaster = DlnaCaster()
    private var dlnaServiceUrl: String? = null
    private var encodeThread: Thread? = null
    var isRunning = false
        private set

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopMirroring(); return START_NOT_STICKY }
        if (isRunning) return START_STICKY

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                javaClass.getMethod("startForeground",
                    Int::class.java, Notification::class.java, Int::class.java)
                    .invoke(this, NOTIFICATION_ID, notification, 0x00000020 /* MEDIA_PROJECTION */)
            } catch (e: Exception) { startForeground(NOTIFICATION_ID, notification) }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        dlnaServiceUrl = intent?.getStringExtra(EXTRA_DLNA_SERVICE_URL)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA) ?: return START_NOT_STICKY

        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(projectionCallback, null)

        acquireWakeLock()
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
            @Suppress("DEPRECATION")
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

            // Start HTTP stream server before creating the virtual display
            streamServer = ScreenStreamServer(STREAM_PORT).also { it.start() }

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenMirror", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )

            encoder?.start()
            isRunning = true
            Log.i(TAG, "Screen mirroring started at ${w}x${h}")

            // Send DLNA command so the renderer connects to our HTTP server
            val serviceUrl = dlnaServiceUrl
            if (serviceUrl != null) {
                val localIp = getLocalIp()
                if (localIp != null) {
                    val streamUrl = "http://$localIp:$STREAM_PORT/screen"
                    Log.i(TAG, "Sending DLNA play: $streamUrl -> $serviceUrl")
                    dlnaCaster.castUrl(serviceUrl, streamUrl,
                        onSuccess = { Log.i(TAG, "DLNA play accepted") },
                        onError   = { e -> Log.w(TAG, "DLNA error: $e") }
                    )
                } else {
                    Log.w(TAG, "Could not determine local WiFi IP — DLNA skipped")
                }
            }

            // Read encoded output and feed it to the stream server
            encodeThread = Thread({ readEncoderOutput() }, "screen-encode-out").also { it.start() }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encoding", e)
            isRunning = false
        }
    }

    private fun readEncoderOutput() {
        val info = MediaCodec.BufferInfo()
        outer@ while (isRunning) {
            val idx = encoder?.dequeueOutputBuffer(info, 10_000L) ?: break
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue
            }
            if (idx >= 0) {
                val buf = encoder!!.getOutputBuffer(idx)
                if (buf != null) {
                    val data = ByteArray(info.size)
                    buf.get(data)
                    streamServer?.write(data)
                }
                encoder!!.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@outer
            }
        }
    }

    private fun stopEncoding() {
        isRunning = false
        encodeThread?.interrupt()
        encodeThread = null
        try { encoder?.stop(); encoder?.release() } catch (e: Exception) { /* ignore */ }
        virtualDisplay?.release()
        mediaProjection?.stop()
        encoder = null; virtualDisplay = null; mediaProjection = null
        streamServer?.stop(); streamServer = null
        dlnaServiceUrl?.let { dlnaCaster.stop(it) }
        releaseWakeLock()
    }

    @Suppress("DEPRECATION")
    private fun getLocalIp(): String? {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val ip = wm.connectionInfo.ipAddress
        if (ip == 0) return null
        return String.format("%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "lowkick:screenmirror"
            ).also { it.acquire(60 * 60 * 1000L) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { /* ignore */ }
        wakeLock = null
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopEncoding() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val channelClass = Class.forName("android.app.NotificationChannel")
                val channel = channelClass
                    .getConstructor(String::class.java, CharSequence::class.java, Int::class.java)
                    .newInstance(CHANNEL_ID, "Screen Mirror", 2)
                val nm = getSystemService(NotificationManager::class.java)
                nm.javaClass.getMethod("createNotificationChannel", channelClass).invoke(nm, channel)
            } catch (e: Exception) {
                Log.w(TAG, "createNotificationChannel: ${e.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags)
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenMirrorService::class.java).setAction(ACTION_STOP), flags)

        return if (Build.VERSION.SDK_INT >= 26) {
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
        const val STREAM_PORT = 8080
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_DLNA_SERVICE_URL = "dlna_service_url"
        const val ACTION_STOP = "com.caster.app.STOP_MIRROR"
    }
}
