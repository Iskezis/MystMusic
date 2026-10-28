package com.iskezis.myst.data.youtube

data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
)

class MusicException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface MusicSource {
    suspend fun search(query: String, limit: Int = 30): List<Track>
    suspend fun trending(limit: Int = 30): List<Track>
    suspend fun related(videoId: String, limit: Int = 20): List<Track>
    suspend fun homeFeed(limit: Int = 30): List<Track>
    suspend fun streamUrl(videoId: String): String
    fun streamUrlBlocking(videoId: String): String
    suspend fun accountName(): String?
}
