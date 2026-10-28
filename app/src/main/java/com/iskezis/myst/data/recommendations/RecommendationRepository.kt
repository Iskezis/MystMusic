package com.iskezis.myst.data.recommendations

import com.iskezis.myst.data.db.AppDatabase
import com.iskezis.myst.data.db.ArtistAffinityEntity
import com.iskezis.myst.data.db.HistoryEntity
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.data.youtube.YouTubeRepository
import com.iskezis.myst.util.normalizeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.random.Random

class RecommendationRepository(
    private val database: AppDatabase,
    private val youtube: YouTubeRepository,
) {

    fun observeRecent(userId: Long, limit: Int = 20): Flow<List<Track>> =
        database.historyDao().observeRecent(userId, limit).map { list ->
            list.map { it.toTrack() }
        }

    suspend fun trendingForUser(userId: Long): List<Track> = withContext(Dispatchers.IO) {
        val trending = youtube.trending(40)
        val affinities = database.affinityDao().top(userId, 100)
        if (affinities.isEmpty()) return@withContext trending.take(30)
        val maxScore = max(affinities.maxOfOrNull { it.score } ?: 1.0, 1.0)
        trending.mapIndexed { index, track ->
            val artistKey = normalizeText(track.artist)
            val affinity = if (artistKey.isBlank()) 0.0 else
                (affinities.firstOrNull { it.artist == artistKey }?.score ?: 0.0) / maxScore
            val position = 1.0 - index.toDouble() / max(trending.size, 1)
            track to (0.6 * affinity.coerceIn(0.0, 1.0) + 0.4 * position)
        }.sortedByDescending { it.second }
            .map { it.first }
            .take(30)
    }

    suspend fun recommendedForUser(userId: Long): List<Track> = withContext(Dispatchers.IO) {
        val recent = database.historyDao().recent(userId, 50)
        if (recent.isEmpty()) {
            return@withContext youtube.trending(30)
        }

        val seeds = recent.map { it.videoId }.distinct().take(3)
        var firstError: Throwable? = null
        val related = mutableListOf<Track>()
        seeds.forEach { videoId ->
            runCatching { youtube.related(videoId, 20) }
                .onSuccess { related.addAll(it) }
                .onFailure { if (firstError == null) firstError = it }
        }
        val artists = database.affinityDao().top(userId, 3)
        val fromArtists = mutableListOf<Track>()
        artists.forEach { affinity ->
            runCatching { youtube.search(affinity.artist, 10) }
                .onSuccess { fromArtists.addAll(it) }
                .onFailure { if (firstError == null) firstError = it }
        }

        val merged = (related + fromArtists).distinctBy { it.videoId }
        if (merged.isEmpty()) {
            firstError?.let { throw it }
            return@withContext youtube.trending(30)
        }

        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(14)
        val recentlyPlayed = database.historyDao().playedSince(userId, since).toSet()
        val filtered = merged.filter { track ->
            track.videoId !in recentlyPlayed && track.videoId !in seeds
        }.ifEmpty { merged }

        val daySeed = System.currentTimeMillis() / TimeUnit.DAYS.toMillis(1)
        val shuffled = filtered.shuffled(Random(daySeed))
        shuffled.take(30)
    }

    suspend fun recordPlay(userId: Long, track: Track, playedMs: Long, completed: Boolean) =
        withContext(Dispatchers.IO) {
            database.historyDao().insert(
                HistoryEntity(
                    userId = userId,
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    thumbnailUrl = track.thumbnailUrl,
                    durationMs = track.durationMs,
                    playedMs = playedMs,
                    completed = completed,
                ),
            )
            updateAffinity(userId, track.artist, if (completed) 2.0 else 1.0)
        }

    suspend fun recordSkip(userId: Long, track: Track) = withContext(Dispatchers.IO) {
        updateAffinity(userId, track.artist, -1.0)
    }

    private suspend fun updateAffinity(userId: Long, rawArtist: String, delta: Double) {
        val key = normalizeText(rawArtist).ifBlank { "desconhecido" }
        val current = database.affinityDao().get(userId, key)
        val updated = (current?.score ?: 0.0) * 0.95 + delta
        database.affinityDao().upsert(
            ArtistAffinityEntity(
                userId = userId,
                artist = key,
                score = updated.coerceAtLeast(0.0),
            ),
        )
    }

    private fun HistoryEntity.toTrack(): Track = Track(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationMs = durationMs,
    )
}
