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

class MusicException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface MusicSource {
    suspend fun search(query: String, limit: Int = 30): List<Track>
    suspend fun trending(limit: Int = 30): List<Track>
    suspend fun related(videoId: String, limit: Int = 20): List<Track>
    suspend fun homeFeed(limit: Int = 30): List<Track>
    suspend fun streamUrl(videoId: String): String
    fun streamUrlBlocking(videoId: String): String
    fun invalidateStream(videoId: String)
    suspend fun accountName(): String?
}
