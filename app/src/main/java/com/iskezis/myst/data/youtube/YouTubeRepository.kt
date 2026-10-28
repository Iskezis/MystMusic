package com.iskezis.myst.data.youtube

class YouTubeRepository(private val source: MusicSource) {

    private data class Cached(val at: Long, val tracks: List<Track>)

    private val cache = HashMap<String, Cached>()
    private val lock = Any()

    suspend fun search(query: String, limit: Int = 30): List<Track> {
        val key = "search:${query.trim().lowercase()}:$limit"
        return cached(key) { source.search(query, limit) }
    }

    suspend fun trending(limit: Int = 30): List<Track> =
        cached("trending:$limit") { source.trending(limit) }

    suspend fun related(videoId: String, limit: Int = 20): List<Track> =
        cached("related:$videoId:$limit") { source.related(videoId, limit) }

    suspend fun homeFeed(limit: Int = 30): List<Track> =
        cached("home:$limit") { source.homeFeed(limit) }

    suspend fun accountName(): String? = source.accountName()

    fun streamUrlBlocking(videoId: String): String = source.streamUrlBlocking(videoId)

    fun invalidateStream(videoId: String) {
        source.invalidateStream(videoId)
    }

    fun invalidate() {
        synchronized(lock) { cache.clear() }
    }

    private suspend fun cached(key: String, block: suspend () -> List<Track>): List<Track> {
        synchronized(lock) {
            val entry = cache[key]
            if (entry != null && System.currentTimeMillis() - entry.at < CACHE_TTL_MS) {
                return entry.tracks
            }
        }
        val value = block()
        synchronized(lock) { cache[key] = Cached(System.currentTimeMillis(), value) }
        return value
    }

    companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
