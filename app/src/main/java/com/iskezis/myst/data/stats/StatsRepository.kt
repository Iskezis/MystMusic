package com.iskezis.myst.data.stats

import androidx.room.withTransaction
import com.iskezis.myst.data.db.AppDatabase
import com.iskezis.myst.data.db.PlayEventEntity
import com.iskezis.myst.data.youtube.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class StatsPeriod(val days: Int) {
    WEEK(7),
    MONTH(30),
    YEAR(365),
    ALL(0),
}

data class StatsTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val plays: Int,
    val playedMs: Long,
)

data class StatsArtist(
    val name: String,
    val thumbnailUrl: String?,
    val plays: Int,
    val playedMs: Long,
)

data class StatsAlbum(
    val name: String,
    val artist: String,
    val thumbnailUrl: String?,
    val plays: Int,
    val playedMs: Long,
)

data class StatsDay(
    val label: String,
    val playedMs: Long,
)

data class StatsData(
    val period: StatsPeriod,
    val eventCount: Int,
    val totalPlayedMs: Long,
    val previousPlayedMs: Long?,
    val playCount: Int,
    val topTracks: List<StatsTrack>,
    val topArtists: List<StatsArtist>,
    val topAlbums: List<StatsAlbum>,
    val hourly: List<Long>,
    val daily: List<StatsDay>,
    val currentStreak: Int,
    val longestStreak: Int,
) {
    val isEmpty: Boolean get() = eventCount == 0

    companion object {
        fun empty(period: StatsPeriod): StatsData = StatsData(
            period = period,
            eventCount = 0,
            totalPlayedMs = 0L,
            previousPlayedMs = null,
            playCount = 0,
            topTracks = emptyList(),
            topArtists = emptyList(),
            topAlbums = emptyList(),
            hourly = List(24) { 0L },
            daily = emptyList(),
            currentStreak = 0,
            longestStreak = 0,
        )
    }
}

/**
 * Consultas e gravação dos eventos de reprodução. Tudo é filtrado por
 * `userId`; nenhuma estatística vaza entre contas Myst.
 */
class StatsRepository(private val database: AppDatabase) {

    suspend fun record(
        userId: Long,
        track: Track,
        startedAt: Long,
        playedMs: Long,
        durationMs: Long,
        completed: Boolean,
        source: String = SOURCE_APP,
    ) = withContext(Dispatchers.IO) {
        if (userId <= 0L || track.videoId.isBlank() || playedMs <= 0L) return@withContext
        database.withTransaction {
            database.playEventDao().insert(
                PlayEventEntity(
                    userId = userId,
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    thumbnailUrl = track.thumbnailUrl,
                    startedAt = startedAt.coerceAtLeast(1L),
                    playedMs = playedMs.coerceAtMost(MAX_EVENT_MS),
                    durationMs = durationMs.coerceIn(0L, MAX_EVENT_MS),
                    completed = completed,
                    source = source.take(MAX_SOURCE_LENGTH),
                ),
            )
        }
    }

    suspend fun load(userId: Long, period: StatsPeriod): StatsData =
        withContext(Dispatchers.IO) {
            if (userId <= 0L) return@withContext StatsData.empty(period)
            val now = System.currentTimeMillis()
            val rangeStart = if (period == StatsPeriod.ALL) 0L else startOfDay(now, period.days)
            val chartDays = if (period == StatsPeriod.WEEK) 7 else 30
            val chartStart = startOfDay(now, chartDays)

            database.withTransaction {
                val dao = database.playEventDao()
                val eventCount = dao.eventCount(userId, rangeStart, now)
                if (eventCount == 0) return@withTransaction StatsData.empty(period)

                val totalPlayedMs = dao.totalPlayedMs(userId, rangeStart, now)
                val previousPlayedMs = if (period == StatsPeriod.ALL) {
                    null
                } else {
                    val elapsed = (now - rangeStart).coerceAtLeast(1L)
                    dao.totalPlayedMs(
                        userId = userId,
                        from = (rangeStart - elapsed).coerceAtLeast(0L),
                        to = rangeStart,
                    )
                }
                val playCount = dao.playCount(
                    userId = userId,
                    from = rangeStart,
                    to = now,
                    minPlayedMs = MIN_PLAY_MS,
                )
                val topTracks = dao.topTracks(
                    userId = userId,
                    from = rangeStart,
                    to = now,
                    minPlayedMs = MIN_PLAY_MS,
                    limit = TOP_LIMIT,
                ).map { item ->
                    StatsTrack(
                        videoId = item.videoId,
                        title = item.title,
                        artist = item.artist,
                        thumbnailUrl = item.thumbnailUrl,
                        plays = item.plays,
                        playedMs = item.playedMs,
                    )
                }
                val topArtists = dao.topArtists(
                    userId = userId,
                    from = rangeStart,
                    to = now,
                    minPlayedMs = MIN_PLAY_MS,
                    limit = TOP_LIMIT,
                ).map { item ->
                    StatsArtist(
                        name = item.name,
                        thumbnailUrl = item.thumbnailUrl,
                        plays = item.plays,
                        playedMs = item.playedMs,
                    )
                }
                val topAlbums = dao.topAlbums(
                    userId = userId,
                    from = rangeStart,
                    to = now,
                    minPlayedMs = MIN_PLAY_MS,
                    limit = TOP_LIMIT,
                ).map { item ->
                    StatsAlbum(
                        name = item.name,
                        artist = item.artist,
                        thumbnailUrl = item.thumbnailUrl,
                        plays = item.plays,
                        playedMs = item.playedMs,
                    )
                }

                val hourlyValues = LongArray(24)
                dao.hourlyPlayedMs(userId, rangeStart, now).forEach { item ->
                    if (item.hour in 0..23) hourlyValues[item.hour] = item.playedMs
                }

                val dailyValues = dao.dailyPlayedMs(userId, chartStart, now)
                    .associate { it.day to it.playedMs }
                val daily = buildDailyBuckets(period, chartStart, chartDays, dailyValues)
                val streak = calculateStreak(dao.listenedDays(userId), now)

                StatsData(
                    period = period,
                    eventCount = eventCount,
                    totalPlayedMs = totalPlayedMs,
                    previousPlayedMs = previousPlayedMs,
                    playCount = playCount,
                    topTracks = topTracks,
                    topArtists = topArtists,
                    topAlbums = topAlbums,
                    hourly = hourlyValues.toList(),
                    daily = daily,
                    currentStreak = streak.first,
                    longestStreak = streak.second,
                )
            }
        }

    private fun buildDailyBuckets(
        period: StatsPeriod,
        chartStart: Long,
        chartDays: Int,
        values: Map<String, Long>,
    ): List<StatsDay> {
        val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val labelFormat = SimpleDateFormat(
            if (period == StatsPeriod.WEEK) "EEE" else "dd/MM",
            Locale.getDefault(),
        )
        val calendar = Calendar.getInstance().apply {
            timeInMillis = chartStart
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return buildList {
            repeat(chartDays) {
                val key = keyFormat.format(calendar.time)
                val rawLabel = labelFormat.format(calendar.time)
                val label = if (period == StatsPeriod.WEEK) {
                    rawLabel.replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
                    }
                } else {
                    rawLabel
                }
                add(StatsDay(label = label, playedMs = values[key] ?: 0L))
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    /**
     * Calcula a sequência atual (terminando hoje ou ontem) e o recorde de dias
     * consecutivos. A lista vem do SQLite já em ordem decrescente de dia local
     * (dia juliano inteiro, alinhado com [localJulianDay]).
     */
    private fun calculateStreak(days: List<Long>, now: Long): Pair<Int, Int> {
        if (days.isEmpty()) return 0 to 0
        val today = localJulianDay(now)
        var current = 0
        val first = days.first()
        if (first == today || first == today - 1L) {
            var expected = first
            for (day in days) {
                if (day != expected) break
                current++
                expected--
            }
        }
        var longest = 0
        var run = 0
        var previous: Long? = null
        days.asReversed().forEach { day ->
            run = if (previous != null && day == previous + 1L) run + 1 else 1
            if (run > longest) longest = run
            previous = day
        }
        return current to longest
    }

    private fun startOfDay(now: Long, days: Int): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -(days - 1))
        }
        return calendar.timeInMillis
    }

    private fun localJulianDay(now: Long): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val localMillis = now + calendar.timeZone.getOffset(now)
        return localMillis / DAY_MS + JULIAN_EPOCH
    }

    companion object {
        const val SOURCE_APP = "app"
        private const val MIN_PLAY_MS = 30_000L
        private const val TOP_LIMIT = 5
        private const val MAX_EVENT_MS = 24L * 60L * 60L * 1000L
        private const val MAX_SOURCE_LENGTH = 32
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val JULIAN_EPOCH = 2440588L
    }
}
