package com.iskezis.myst.data.youtube

import com.iskezis.myst.util.AppLog

class YouTubeRepository(private val source: MusicSource) {

    private data class Cached(val at: Long, val tracks: List<Track>)

    private val cache = HashMap<String, Cached>()
    private val lock = Any()

    suspend fun search(query: String, limit: Int = 30): List<Track> {
        val key = "search:${query.trim().lowercase()}:$limit"
        AppLog.d(AppLog.TAG_TUBE, "Busca solicitada: \"$query\" limite=$limit")
        return cached(key) { source.search(query, limit) }
    }

    suspend fun trending(limit: Int = 30): List<Track> {
        AppLog.d(AppLog.TAG_TUBE, "Em alta solicitado limite=$limit")
        return cached("trending:$limit") { source.trending(limit) }
    }

    suspend fun related(videoId: String, limit: Int = 20): List<Track> {
        AppLog.d(AppLog.TAG_TUBE, "Relacionados solicitados: $videoId limite=$limit")
        return cached("related:$videoId:$limit") { source.related(videoId, limit) }
    }

    suspend fun homeFeed(limit: Int = 30): List<Track> {
        AppLog.d(AppLog.TAG_TUBE, "Home feed solicitado limite=$limit")
        return cached("home:$limit") { source.homeFeed(limit) }
    }

    suspend fun accountName(): String? = source.accountName()

    fun streamUrlBlocking(videoId: String): String {
        AppLog.d(AppLog.TAG_STREAM, "Resolvendo URL (bloqueante) para $videoId")
        return source.streamUrlBlocking(videoId)
    }

    fun streamInfoBlocking(videoId: String): StreamInfo {
        AppLog.d(AppLog.TAG_STREAM, "Resolvendo StreamInfo (bloqueante) para $videoId")
        return source.streamInfoBlocking(videoId)
    }

    fun invalidateStream(videoId: String) {
        AppLog.d(AppLog.TAG_STREAM, "Repositório invalidando stream $videoId")
        source.invalidateStream(videoId)
    }

    fun invalidate() {
        synchronized(lock) { cache.clear() }
        AppLog.d(AppLog.TAG_TUBE, "Cache de listas do YouTube limpo")
    }

    private suspend fun cached(key: String, block: suspend () -> List<Track>): List<Track> {
        synchronized(lock) {
            val entry = cache[key]
            if (entry != null && System.currentTimeMillis() - entry.at < CACHE_TTL_MS) {
                AppLog.d(AppLog.TAG_TUBE, "Cache hit: $key (${entry.tracks.size} faixas)")
                return entry.tracks
            }
        }
        AppLog.d(AppLog.TAG_TUBE, "Cache miss: $key")
        val startedAt = System.currentTimeMillis()
        val value = block()
        synchronized(lock) { cache[key] = Cached(System.currentTimeMillis(), value) }
        AppLog.i(
            AppLog.TAG_TUBE,
            "Resposta de $key: ${value.size} faixas em ${System.currentTimeMillis() - startedAt}ms",
        )
        return value
    }

    companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
