package com.iskezis.myst.data.downloads

import android.content.Context
import com.iskezis.myst.data.youtube.Track
import org.json.JSONObject

/**
 * Metadados das faixas que passaram pelo cache de streaming.
 *
 * O Media3 guarda o áudio por `videoId`, mas não guarda título/artista/capa.
 * Este catálogo leve (SharedPreferences, sem Room) permite listar o cache na
 * tela Configurações → Armazenamento → Cache e apagar itens individualmente.
 * A lista real é sempre filtrada pelas chaves que existem no cache; entradas
 * antigas são podadas junto com os arquivos.
 */
class CacheCatalog(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    data class Entry(
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String,
        val thumbnailUrl: String?,
        val durationMs: Long,
        val userId: Long,
        val updatedAt: Long,
    ) {
        fun toTrack(): Track = Track(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            durationMs = durationMs,
            album = album,
        )
    }

    fun record(track: Track, userId: Long) {
        if (track.videoId.isBlank()) return
        synchronized(lock) {
            val root = read()
            val entry = JSONObject()
                .put(KEY_TITLE, track.title)
                .put(KEY_ARTIST, track.artist)
                .put(KEY_ALBUM, track.album)
                .put(KEY_THUMBNAIL, track.thumbnailUrl ?: "")
                .put(KEY_DURATION, track.durationMs)
                .put(KEY_USER, userId)
                .put(KEY_UPDATED, System.currentTimeMillis())
            root.put(track.videoId, entry)
            trimOverflow(root)
            write(root)
        }
    }

    fun entries(): List<Entry> {
        val root = synchronized(lock) { read() }
        val result = ArrayList<Entry>(root.length())
        val keys = root.keys()
        while (keys.hasNext()) {
            val videoId = keys.next()
            val item = root.optJSONObject(videoId) ?: continue
            result += Entry(
                videoId = videoId,
                title = item.optString(KEY_TITLE),
                artist = item.optString(KEY_ARTIST),
                album = item.optString(KEY_ALBUM),
                thumbnailUrl = item.optString(KEY_THUMBNAIL).takeIf { it.isNotBlank() },
                durationMs = item.optLong(KEY_DURATION, 0L),
                userId = item.optLong(KEY_USER, 0L),
                updatedAt = item.optLong(KEY_UPDATED, 0L),
            )
        }
        return result.sortedByDescending { it.updatedAt }
    }

    fun remove(videoId: String) {
        if (videoId.isBlank()) return
        synchronized(lock) {
            val root = read()
            if (root.has(videoId)) {
                root.remove(videoId)
                write(root)
            }
        }
    }

    /** Mantém apenas metadados de faixas que ainda existem no cache. */
    fun retainOnly(videoIds: Set<String>) {
        synchronized(lock) {
            val root = read()
            val keys = root.keys().asSequence().toList()
            var changed = false
            keys.forEach { key ->
                if (key !in videoIds) {
                    root.remove(key)
                    changed = true
                }
            }
            if (changed) write(root)
        }
    }

    fun clear() {
        synchronized(lock) {
            prefs.edit().remove(KEY_ENTRIES).apply()
        }
    }

    private fun trimOverflow(root: JSONObject) {
        if (root.length() <= MAX_ENTRIES) return
        val ordered = root.keys().asSequence().toList().sortedBy { key ->
            root.optJSONObject(key)?.optLong(KEY_UPDATED, 0L) ?: 0L
        }
        ordered.take(root.length() - MAX_ENTRIES).forEach { root.remove(it) }
    }

    private fun read(): JSONObject = runCatching {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return@runCatching JSONObject()
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }.getOrDefault(JSONObject())

    private fun write(root: JSONObject) {
        prefs.edit().putString(KEY_ENTRIES, root.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "myst_cache_catalog"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_ALBUM = "album"
        private const val KEY_THUMBNAIL = "thumbnail"
        private const val KEY_DURATION = "duration"
        private const val KEY_USER = "user"
        private const val KEY_UPDATED = "updated"
        private const val MAX_ENTRIES = 2000
    }
}
