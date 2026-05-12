package com.caster.app.auto

import android.media.browse.MediaBrowser
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.util.Log

/**
 * Registers the app as an Android Auto media source.
 * This makes LowKick appear in the Android Auto media list on the car screen.
 */
class AutoMediaService : MediaBrowserService() {

    companion object {
        private const val TAG = "AutoMediaService"
        const val ROOT_ID = "lowkick_root"
        const val CAST_ITEM_ID = "lowkick_cast"
        const val SCREEN_ITEM_ID = "lowkick_screen"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AutoMediaService created")
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        Log.d(TAG, "onGetRoot for $clientPackageName")
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowser.MediaItem>>
    ) {
        val items = mutableListOf<MediaBrowser.MediaItem>()

        val castDesc = android.media.MediaDescription.Builder()
            .setMediaId(CAST_ITEM_ID)
            .setTitle("Diffusion d'écran")
            .setSubtitle("Castez l'écran vers votre voiture")
            .build()
        items.add(MediaBrowser.MediaItem(castDesc, MediaBrowser.MediaItem.FLAG_BROWSABLE))

        val screenDesc = android.media.MediaDescription.Builder()
            .setMediaId(SCREEN_ITEM_ID)
            .setTitle("LowKick Android Auto Caster")
            .setSubtitle("Appareils Chromecast / DLNA détectés")
            .build()
        items.add(MediaBrowser.MediaItem(screenDesc, MediaBrowser.MediaItem.FLAG_BROWSABLE))

        result.sendResult(items)
    }
}
