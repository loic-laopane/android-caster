package com.caster.app.media

import android.content.Context
import java.util.UUID

class MediaLibrary(context: Context) {

    private val prefs = context.getSharedPreferences("media_lib", Context.MODE_PRIVATE)

    data class Entry(val id: String, val title: String, val url: String, val mimeType: String = "video/mp4")

    fun getAll(): List<Entry> {
        val raw = prefs.getString("entries", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split("|", limit = 4)
            if (p.size == 4) Entry(p[0], p[1], p[2], p[3]) else null
        }
    }

    fun add(title: String, url: String, mimeType: String = "video/mp4"): Entry {
        val safeTitle = title.ifEmpty { url.substringAfterLast('/').substringBefore('?').ifEmpty { url } }
        val entry = Entry(UUID.randomUUID().toString(), safeTitle, url, mimeType)
        val existing = prefs.getString("entries", "") ?: ""
        prefs.edit().putString("entries",
            if (existing.isEmpty()) serialize(entry) else "$existing\n${serialize(entry)}"
        ).apply()
        return entry
    }

    fun remove(id: String) {
        val updated = getAll().filter { it.id != id }.joinToString("\n") { serialize(it) }
        prefs.edit().putString("entries", updated).apply()
    }

    fun getById(id: String): Entry? = getAll().find { it.id == id }

    private fun serialize(e: Entry) = "${e.id}|${e.title}|${e.url}|${e.mimeType}"
}
