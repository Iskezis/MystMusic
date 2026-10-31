package com.iskezis.myst.data.youtube

import com.iskezis.myst.util.AppLog

class YouTubeRepository(private val source: MusicSource) {

    private data class Cached(val at: Long, val tracks: List<Track>)
    private data class CachedPage<T>(val at: Long, val value: T)

    private val cache = HashMap<String, Cached>()
    private val pageCache = HashMap<String, CachedPage<Any>>()
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

    suspend fun libraryPlaylists(limit: Int = 300): List<YtPlaylist> {
        AppLog.d(AppLog.TAG_TUBE, "Playlists da biblioteca solicitadas limite=$limit")
        return source.libraryPlaylists(limit)
    }

    suspend fun accountHistory(limit: Int = 50): List<Track> {
        AppLog.d(AppLog.TAG_TUBE, "Histórico da conta solicitado limite=$limit")
        return source.accountHistory(limit)
    }

    /**
     * Envia a telemetria de reprodução para o YouTube. Best-effort: se a conta
     * não estiver conectada ou o endpoint recusar, o áudio continua normal.
     */
    suspend fun markWatched(videoId: String, playedMs: Long = 0L): Boolean {
        if (videoId.isBlank()) return false
        return source.markWatched(videoId, playedMs)
    }

    suspend fun personalizedHome(limit: Int = 40): List<Track> {
        AppLog.d(AppLog.TAG_TUBE, "Home personalizada solicitada limite=$limit")
        return cached("personalized-home:$limit") { source.personalizedHome(limit) }
    }

    suspend fun playlistTracks(playlistId: String, limit: Int = 500): List<Track> {
        AppLog.d(AppLog.TAG_TUBE, "Faixas da playlist $playlistId solicitadas limite=$limit")
        return source.playlistTracks(playlistId, limit)
    }

    suspend fun searchPage(
        query: String,
        filter: SearchFilter = SearchFilter.ALL,
        params: String? = null,
        continuation: String? = null,
    ): SearchPage {
        if (!continuation.isNullOrBlank()) {
            return source.searchPage(query, filter, params, continuation)
        }
        val key = "search-page:${query.trim().lowercase()}:${filter.name}:${params.orEmpty()}"
        AppLog.d(AppLog.TAG_TUBE, "Busca completa solicitada: \"$query\" filtro=${filter.name}")
        return cachedPage(key) { source.searchPage(query, filter, params, null) }
    }

    suspend fun artistPage(browseId: String): ArtistPage {
        AppLog.d(AppLog.TAG_TUBE, "Artista solicitado: $browseId")
        return cachedPage("artist:$browseId") { source.artistPage(browseId) }
    }

    /**
     * Catálogo completo do artista para o download em lote. Não usa o cache de
     * página porque precisa da discografia inteira, não só dos carrosséis.
     */
    suspend fun artistSongs(page: ArtistPage): List<Track> {
        AppLog.d(AppLog.TAG_TUBE, "Catálogo completo do artista ${page.browseId} solicitado")
        return source.artistSongs(page)
    }

    suspend fun albumPage(browseId: String): AlbumPage {
        AppLog.d(AppLog.TAG_TUBE, "Álbum solicitado: $browseId")
        return cachedPage("album:$browseId") { source.albumPage(browseId) }
    }

    suspend fun publicPlaylistPage(browseId: String): PlaylistPage {
        AppLog.d(AppLog.TAG_TUBE, "Playlist pública solicitada: $browseId")
        return cachedPage("public-playlist:$browseId") { source.publicPlaylistPage(browseId) }
    }

    suspend fun browseContinuation(continuation: String): TrackPage {
        AppLog.d(AppLog.TAG_TUBE, "Continuação de browse solicitada")
        return source.browseContinuation(continuation)
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
        synchronized(lock) {
            cache.clear()
            pageCache.clear()
        }
        AppLog.d(AppLog.TAG_TUBE, "Cache de listas do YouTube limpo")
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> cachedPage(key: String, block: suspend () -> T): T {
        synchronized(lock) {
            val entry = pageCache[key] as? CachedPage<T>
            if (entry != null && System.currentTimeMillis() - entry.at < CACHE_TTL_MS) {
                AppLog.d(AppLog.TAG_TUBE, "Cache hit de página: $key")
                return entry.value
            }
        }
        AppLog.d(AppLog.TAG_TUBE, "Cache miss de página: $key")
        val value = block()
        synchronized(lock) { pageCache[key] = CachedPage(System.currentTimeMillis(), value as Any) }
        return value
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
