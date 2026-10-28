package com.iskezis.myst.data.youtube

import com.iskezis.myst.util.Thumbnails

data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
) {
    /** Melhor arte disponível, mesmo para faixas antigas salvas com URL pequena. */
    fun artworkUrl(): String? = Thumbnails.best(thumbnailUrl, videoId)
}

/**
 * URL de áudio resolvida e os headers que devem acompanhar a requisição.
 * Os headers incluem o User-Agent do cliente InnerTube que emitiu a URL; alguns
 * servidores do googlevideo rejeitam a URL quando o agente muda no download.
 */
data class StreamInfo(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

open class MusicException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * O YouTube recusou o player pedindo login/verificação. Acontece com sessões
 * anônimas em redes marcadas como bot ou quando os cookies expiraram. O player
 * usa isso para oferecer o login do YouTube em vez de um erro genérico.
 */
class LoginRequiredException(message: String, cause: Throwable? = null) : MusicException(message, cause)

interface MusicSource {
    suspend fun search(query: String, limit: Int = 30): List<Track>
    suspend fun trending(limit: Int = 30): List<Track>
    suspend fun related(videoId: String, limit: Int = 20): List<Track>
    suspend fun homeFeed(limit: Int = 30): List<Track>
    suspend fun streamUrl(videoId: String): String
    suspend fun streamInfo(videoId: String): StreamInfo = StreamInfo(streamUrl(videoId))
    fun streamUrlBlocking(videoId: String): String
    fun streamInfoBlocking(videoId: String): StreamInfo = StreamInfo(streamUrlBlocking(videoId))
    fun invalidateStream(videoId: String)
    suspend fun accountName(): String?
}
