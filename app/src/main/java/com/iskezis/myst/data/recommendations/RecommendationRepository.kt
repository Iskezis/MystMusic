package com.iskezis.myst.data.recommendations

import com.iskezis.myst.data.db.AppDatabase
import com.iskezis.myst.data.db.ArtistAffinityEntity
import com.iskezis.myst.data.db.HistoryEntity
import com.iskezis.myst.data.db.YtHistoryEntity
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.data.youtube.YouTubeRepository
import com.iskezis.myst.util.AppLog
import com.iskezis.myst.util.normalizeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.random.Random

class RecommendationRepository(
    private val database: AppDatabase,
    private val youtube: YouTubeRepository,
) {

    fun observeRecent(userId: Long, limit: Int = 20): Flow<List<Track>> =
        combine(
            database.historyDao().observeRecent(userId, limit),
            database.youTubeLibraryDao().observeHistory(userId, limit * 5),
        ) { local, youtubeHistory ->
            mergeRecent(local, youtubeHistory, limit)
        }

    /**
     * Home personalizada do YouTube Music. Erros de rede são tratados pela
     * tela, que cai para o algoritmo local; sessão expirada é propagada para
     * exibir o aviso de entrar de novo.
     */
    suspend fun personalizedForUser(userId: Long): List<Track> = withContext(Dispatchers.IO) {
        AppLog.d(AppLog.TAG_TUBE, "Buscando recomendados da home do YouTube para $userId")
        youtube.personalizedHome(40)
            .filter { it.videoId.isNotBlank() }
            .distinctBy { it.videoId }
            .take(30)
    }

    suspend fun trendingForUser(userId: Long): List<Track> = withContext(Dispatchers.IO) {
        AppLog.d(AppLog.TAG_DB, "Calculando Em Alta para usuário $userId")
        val trending = youtube.trending(40)
        val affinities = database.affinityDao().top(userId, 100)
        val ytHistory = database.youTubeLibraryDao().history(userId, 100)
        val ytCounts = ytHistory
            .map { normalizeText(it.artist) }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
        if (affinities.isEmpty() && ytCounts.isEmpty()) {
            return@withContext trending.take(30)
        }
        val maxScore = max(affinities.maxOfOrNull { it.score } ?: 1.0, 1.0)
        val maxYtCount = max(ytCounts.values.maxOrNull() ?: 1, 1)
        trending.mapIndexed { index, track ->
            val artistKey = normalizeText(track.artist)
            val affinity = if (artistKey.isBlank()) 0.0 else
                (affinities.firstOrNull { it.artist == artistKey }?.score ?: 0.0) / maxScore
            val ytAffinity = if (artistKey.isBlank()) 0.0 else
                (ytCounts[artistKey] ?: 0).toDouble() / maxYtCount
            val position = 1.0 - index.toDouble() / max(trending.size, 1)
            track to (
                0.45 * affinity.coerceIn(0.0, 1.0) +
                    0.25 * ytAffinity.coerceIn(0.0, 1.0) +
                    0.30 * position
                )
        }.sortedByDescending { it.second }
            .map { it.first }
            .take(30)
    }

    suspend fun recommendedForUser(userId: Long): List<Track> = withContext(Dispatchers.IO) {
        AppLog.d(AppLog.TAG_DB, "Calculando Recomendados para usuário $userId")
        val recent = database.historyDao().recent(userId, 50)
        if (recent.isEmpty()) {
            AppLog.d(AppLog.TAG_DB, "Sem histórico; usando Em Alta como recomendação")
            return@withContext youtube.trending(30)
        }

        val seeds = recent.map { it.videoId }.distinct().take(3)
        var firstError: Throwable? = null
        val related = mutableListOf<Track>()
        seeds.forEach { videoId ->
            runCatching { youtube.related(videoId, 20) }
                .onSuccess { related.addAll(it) }
                .onFailure {
                    AppLog.w(
                        AppLog.TAG_TUBE,
                        "Relacionados falharam para $videoId: ${AppLog.describe(it)}",
                    )
                    if (firstError == null) firstError = it
                }
        }
        val artists = database.affinityDao().top(userId, 3)
        val fromArtists = mutableListOf<Track>()
        artists.forEach { affinity ->
            runCatching { youtube.search(affinity.artist, 10) }
                .onSuccess { fromArtists.addAll(it) }
                .onFailure {
                    AppLog.w(
                        AppLog.TAG_TUBE,
                        "Busca por artista ${affinity.artist} falhou: ${AppLog.describe(it)}",
                    )
                    if (firstError == null) firstError = it
                }
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
        AppLog.i(
            AppLog.TAG_DB,
            "Recomendados: ${shuffled.size} candidatos (sementes=${seeds.size}, " +
                "artistas=${artists.size})",
        )
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

    /**
     * Mescla o histórico local (timestamps reais) com o do YouTube Music
     * (ordem relativa convertida em timestamps espaçados no repositório).
     * Duplicatas por videoId ficam com a metadata local e o instante mais
     * recente dos dois lados.
     */
    private fun mergeRecent(
        local: List<HistoryEntity>,
        youtubeHistory: List<YtHistoryEntity>,
        limit: Int,
    ): List<Track> {
        data class Entry(val track: Track, val at: Long)

        val merged = LinkedHashMap<String, Entry>()
        youtubeHistory.forEach { entity ->
            val track = entity.toTrack()
            val previous = merged[entity.videoId]
            if (previous == null || entity.playedAt > previous.at) {
                merged[entity.videoId] = Entry(track, entity.playedAt)
            }
        }
        local.forEach { entity ->
            val track = entity.toTrack()
            val previous = merged[entity.videoId]
            val at = if (previous != null && previous.at > entity.playedAt) {
                previous.at
            } else {
                entity.playedAt
            }
            merged[entity.videoId] = Entry(track, at)
        }
        return merged.values
            .sortedByDescending { it.at }
            .map { it.track }
            .take(limit)
    }

    private fun HistoryEntity.toTrack(): Track = Track(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationMs = durationMs,
    )

    private fun YtHistoryEntity.toTrack(): Track = Track(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationMs = durationMs,
    )
}
