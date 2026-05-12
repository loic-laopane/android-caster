package com.caster.app.auto

import android.app.*
import android.content.Intent
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.media.MediaBrowserService
import android.util.Log
import android.view.SurfaceHolder
import com.caster.app.MainActivity
import com.caster.app.media.MediaLibrary

class AutoMediaService : MediaBrowserService() {

    inner class LocalBinder : Binder() {
        fun getService(): AutoMediaService = this@AutoMediaService
    }

    private val localBinder = LocalBinder()
    private var mediaSession: MediaSession? = null
    private var player: MediaPlayer? = null
    val library by lazy { MediaLibrary(this) }
    private var currentEntry: MediaLibrary.Entry? = null

    companion object {
        private const val TAG = "AutoMediaService"
        private const val ROOT_ID = "root"
        private const val LIBRARY_ID = "library"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "media_playback"
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaSession = MediaSession(this, TAG).apply {
            setCallback(sessionCallback)
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }
        sessionToken = mediaSession!!.sessionToken
        updatePlaybackState(PlaybackState.STATE_NONE, 0)
    }

    override fun onDestroy() {
        mediaSession?.release()
        releasePlayer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? =
        if ("android.media.browse.MediaBrowserService" == intent.action) super.onBind(intent)
        else localBinder

    // ─── MediaBrowserService ──────────────────────────────────────────────────

    override fun onGetRoot(pkg: String, uid: Int, hints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        when (parentId) {
            ROOT_ID -> result.sendResult(mutableListOf(
                makeItem(LIBRARY_ID, "Bibliothèque", "Contenus enregistrés", MediaBrowser.MediaItem.FLAG_BROWSABLE)
            ))
            LIBRARY_ID -> {
                result.detach()
                val items = library.getAll().map { e ->
                    makeItem(e.id, e.title, e.mimeType, MediaBrowser.MediaItem.FLAG_PLAYABLE)
                }.toMutableList()
                result.sendResult(items)
            }
            else -> result.sendResult(mutableListOf())
        }
    }

    // ─── Public API (Activity uses via binding) ───────────────────────────────

    fun setDisplay(holder: SurfaceHolder?) {
        try { player?.setDisplay(holder) } catch (e: Exception) { Log.w(TAG, "setDisplay: ${e.message}") }
    }

    fun playEntry(entry: MediaLibrary.Entry) {
        currentEntry = entry
        releasePlayer()
        updatePlaybackState(PlaybackState.STATE_BUFFERING, 0)
        updateMetadata(entry)
        player = MediaPlayer().apply {
            try {
                setDataSource(entry.url)
                setOnPreparedListener { mp ->
                    mp.start()
                    updatePlaybackState(PlaybackState.STATE_PLAYING, 0)
                    showNotification()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Player error $what/$extra")
                    updatePlaybackState(PlaybackState.STATE_ERROR, 0)
                    true
                }
                setOnCompletionListener { updatePlaybackState(PlaybackState.STATE_STOPPED, 0) }
                prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "setDataSource: ${e.message}")
                updatePlaybackState(PlaybackState.STATE_ERROR, 0)
            }
        }
    }

    fun addAndPlay(title: String, url: String, mimeType: String = "video/mp4") =
        playEntry(library.add(title, url, mimeType))

    fun play() {
        player?.start()
        updatePlaybackState(PlaybackState.STATE_PLAYING, currentPosition())
        showNotification()
    }

    fun pause() {
        player?.pause()
        updatePlaybackState(PlaybackState.STATE_PAUSED, currentPosition())
        showNotification()
    }

    fun stop() {
        releasePlayer()
        updatePlaybackState(PlaybackState.STATE_STOPPED, 0)
        stopForeground(true)
    }

    fun seekTo(ms: Int) = player?.seekTo(ms)

    fun isPlaying(): Boolean = player?.isPlaying == true
    fun currentPosition(): Long = (player?.currentPosition ?: 0).toLong()
    fun duration(): Long = (player?.duration ?: 0).toLong()
    fun getCurrentEntry(): MediaLibrary.Entry? = currentEntry

    // ─── MediaSession callback (Android Auto / steering wheel controls) ────────

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() { play() }
        override fun onPause() { pause() }
        override fun onStop() { stop() }
        override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            mediaId?.let { library.getById(it) }?.let { playEntry(it) }
        }
        override fun onSkipToNext() {
            val all = library.getAll(); val idx = all.indexOfFirst { it.id == currentEntry?.id }
            val next = if (idx in 0 until all.size - 1) all[idx + 1] else all.firstOrNull()
            next?.let { playEntry(it) }
        }
        override fun onSkipToPrevious() {
            val all = library.getAll(); val idx = all.indexOfFirst { it.id == currentEntry?.id }
            val prev = if (idx > 0) all[idx - 1] else all.lastOrNull()
            prev?.let { playEntry(it) }
        }
    }

    // ─── Internal ──────────────────────────────────────────────────────────────

    private fun updatePlaybackState(state: Int, position: Long) {
        val ps = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
            )
            .setState(state, position, 1.0f)
            .build()
        mediaSession?.setPlaybackState(ps)
    }

    private fun updateMetadata(entry: MediaLibrary.Entry) {
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, entry.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, entry.url)
            .build()
        mediaSession?.setMetadata(meta)
    }

    private fun releasePlayer() {
        try { player?.stop() } catch (e: Exception) { /* ignore */ }
        player?.release(); player = null
    }

    private fun makeItem(id: String, title: String, subtitle: String, flags: Int): MediaBrowser.MediaItem {
        val desc = android.media.MediaDescription.Builder()
            .setMediaId(id).setTitle(title).setSubtitle(subtitle).build()
        return MediaBrowser.MediaItem(desc, flags)
    }

    // ─── Notification ──────────────────────────────────────────────────────────

    private fun showNotification() {
        val entry = currentEntry ?: return
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif = buildNotif(entry.title, pi)
        startForeground(NOTIFICATION_ID, notif)
    }

    private fun buildNotif(title: String, pi: PendingIntent): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val bc = Notification.Builder::class.java
                val b = bc.getConstructor(android.content.Context::class.java, String::class.java)
                    .newInstance(this, CHANNEL_ID)
                bc.getMethod("setContentTitle", CharSequence::class.java).invoke(b, title)
                bc.getMethod("setContentText",  CharSequence::class.java).invoke(b, "En lecture")
                bc.getMethod("setSmallIcon", Int::class.java).invoke(b, android.R.drawable.ic_media_play)
                bc.getMethod("setContentIntent", PendingIntent::class.java).invoke(b, pi)
                bc.getMethod("setOngoing", Boolean::class.java).invoke(b, true)
                return bc.getMethod("build").invoke(b) as Notification
            } catch (e: Exception) { /* fallthrough */ }
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle(title).setContentText("En lecture")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val cc = Class.forName("android.app.NotificationChannel")
                val ch = cc.getConstructor(String::class.java, CharSequence::class.java, Int::class.java)
                    .newInstance(CHANNEL_ID, "Lecture média", 2)
                val nm = getSystemService(NotificationManager::class.java)
                nm.javaClass.getMethod("createNotificationChannel", cc).invoke(nm, ch)
            } catch (e: Exception) { Log.w(TAG, "channel: ${e.message}") }
        }
    }
}
