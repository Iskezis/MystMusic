package com.iskezis.myst.data.pairing

import android.content.Context
import com.iskezis.myst.util.AppLog
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Retrato completo da TV que ainda não foi confirmado pelo celular.
 *
 * O banco da TV é em memória. Antes, só os eventos de reprodução eram
 * guardados no outbox; playlists, histórico e afinidades podiam desaparecer se
 * o celular estivesse inacessível no exato momento de um play ou de uma
 * edição. Este arquivo guarda tudo o que [PairingSync.exportTvChanges] produz
 * e só é apagado depois de o celular responder que mesclou o retrato.
 *
 * O conteúdo não tem cookies, senhas nem o segredo do pareamento.
 */
@Serializable
data class PendingTvSnapshot(
    val userId: Long = 0L,
    val accountUsername: String = "",
    val pairId: String = "",
    val savedAt: Long = 0L,
    val snapshot: PairSnapshot = PairSnapshot(),
)

object TvSnapshotOutbox {

    private const val DIRECTORY = "pairing"
    private const val FILE_NAME = "tv_pending_snapshot.json"

    private const val MAX_HISTORY = 2_000
    private const val MAX_AFFINITIES = 500
    private const val MAX_PLAY_EVENTS = 5_000
    private const val MAX_TOMBSTONES = 5_000

    private val lock = Any()

    /**
     * Guarda o retrato mais recente. Se já houver um retrato da mesma conta,
     * mescla eventos/histórico para que uma resposta obtida durante uma
     * gravação não apague o que ainda não foi entregue.
     */
    fun store(
        context: Context,
        userId: Long,
        accountUsername: String,
        pairId: String,
        snapshot: PairSnapshot,
    ) {
        if (userId <= 0L) return
        synchronized(lock) {
            runCatching {
                val existing = read(context)
                val sameAccount = existing != null && (
                    existing.userId == userId ||
                        (accountUsername.isNotBlank() &&
                            existing.accountUsername.equals(accountUsername, ignoreCase = true))
                    )
                val merged = if (sameAccount) {
                    merge(existing?.snapshot ?: PairSnapshot(), snapshot)
                } else {
                    snapshot
                }
                val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
                val target = File(directory, FILE_NAME)
                val temp = File(directory, "$FILE_NAME.tmp")
                val payload = PendingTvSnapshot(
                    userId = userId,
                    accountUsername = accountUsername,
                    pairId = pairId,
                    savedAt = System.currentTimeMillis(),
                    snapshot = merged,
                )
                temp.writeText(
                    PairingSync.json.encodeToString(PendingTvSnapshot.serializer(), payload),
                )
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                AppLog.d(
                    AppLog.TAG_PAIRING,
                    "Retrato pendente da TV guardado: playlists=${merged.playlists.size} " +
                        "histórico=${merged.history.size} eventos=${merged.playEvents.size}",
                )
            }.onFailure {
                AppLog.failure(
                    AppLog.TAG_PAIRING,
                    "Não foi possível guardar o retrato pendente da TV",
                    it,
                )
            }
        }
    }

    /**
     * Carrega o retrato de uma conta, incluindo o outbox antigo de
     * estatísticas para não perder eventos criados na Beta 3. Retorna `null`
     * quando o retrato pertence a outra conta Myst.
     */
    fun loadFor(context: Context, userId: Long, accountUsername: String): PendingTvSnapshot? =
        synchronized(lock) {
            val full = read(context)
            val fullMatches = full != null && (
                full.userId == userId ||
                    (accountUsername.isNotBlank() &&
                        full.accountUsername.equals(accountUsername, ignoreCase = true))
                )
            val legacy = TvStatsOutbox.load(context)
            val legacyMatches = legacy != null && legacy.userId == userId
            if (!fullMatches && !legacyMatches) return@synchronized null

            val base = if (fullMatches) full else null
            var snapshot = base?.snapshot ?: PairSnapshot()
            if (legacyMatches) {
                snapshot = merge(
                    snapshot,
                    PairSnapshot(playEvents = legacy?.events.orEmpty()),
                )
            }
            PendingTvSnapshot(
                userId = base?.userId ?: userId,
                accountUsername = base?.accountUsername ?: accountUsername,
                pairId = base?.pairId.orEmpty(),
                savedAt = base?.savedAt ?: System.currentTimeMillis(),
                snapshot = snapshot,
            )
        }

    fun clear(context: Context) {
        synchronized(lock) {
            runCatching {
                val directory = File(context.filesDir, DIRECTORY)
                File(directory, FILE_NAME).delete()
                File(directory, "$FILE_NAME.tmp").delete()
                // O arquivo de estatísticas da Beta 3 já foi absorvido pelo
                // retrato completo; pode ser removido junto.
                TvStatsOutbox.clear(context)
            }.onFailure {
                AppLog.w(
                    AppLog.TAG_PAIRING,
                    "Não foi possível limpar o retrato pendente da TV: ${it.message}",
                )
            }
        }
    }

    private fun read(context: Context): PendingTvSnapshot? = runCatching {
        val file = File(File(context.filesDir, DIRECTORY), FILE_NAME)
        if (!file.isFile) return@runCatching null
        PairingSync.json.decodeFromString(
            PendingTvSnapshot.serializer(),
            file.readText(),
        )
    }.onFailure {
        AppLog.w(
            AppLog.TAG_PAIRING,
            "Retrato pendente da TV ilegível; ignorando: ${it.message}",
        )
    }.getOrNull()

    private fun merge(existing: PairSnapshot, incoming: PairSnapshot): PairSnapshot {
        val playlists = if (incoming.playlists.isNotEmpty()) {
            incoming.playlists
        } else {
            existing.playlists
        }
        val youtube = if (incoming.youtube.playlists.isNotEmpty() ||
            incoming.youtube.history.isNotEmpty() ||
            incoming.youtube.sync.isNotEmpty()
        ) {
            incoming.youtube
        } else {
            existing.youtube
        }
        return PairSnapshot(
            generatedAt = maxOf(existing.generatedAt, incoming.generatedAt),
            playlists = playlists,
            tombstones = mergeTombstones(existing.tombstones, incoming.tombstones),
            history = mergeHistory(existing.history, incoming.history),
            affinities = mergeAffinities(existing.affinities, incoming.affinities),
            playEvents = mergePlayEvents(existing.playEvents, incoming.playEvents),
            searchHistory = (incoming.searchHistory + existing.searchHistory)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .take(8),
            youtube = youtube,
        )
    }

    private fun mergeTombstones(
        existing: List<PairTombstone>,
        incoming: List<PairTombstone>,
    ): List<PairTombstone> {
        val merged = LinkedHashMap<String, PairTombstone>()
        (existing + incoming).forEach { item ->
            val key = item.key.trim()
            if (key.isBlank()) return@forEach
            val previous = merged[key]
            if (previous == null || item.deletedAt > previous.deletedAt) {
                merged[key] = item
            }
        }
        return merged.values.take(MAX_TOMBSTONES)
    }

    private fun mergeHistory(
        existing: List<PairHistory>,
        incoming: List<PairHistory>,
    ): List<PairHistory> {
        val merged = LinkedHashMap<String, PairHistory>()
        (existing + incoming).forEach { item ->
            val key = "${item.videoId}|${item.playedAt}"
            val previous = merged[key]
            if (previous == null || item.playedMs > previous.playedMs ||
                (item.completed && !previous.completed)
            ) {
                merged[key] = if (previous == null) {
                    item
                } else {
                    item.copy(
                        playedMs = maxOf(previous.playedMs, item.playedMs),
                        completed = previous.completed || item.completed,
                    )
                }
            }
        }
        return merged.values.sortedByDescending { it.playedAt }.take(MAX_HISTORY)
    }

    private fun mergeAffinities(
        existing: List<PairAffinity>,
        incoming: List<PairAffinity>,
    ): List<PairAffinity> {
        val merged = LinkedHashMap<String, PairAffinity>()
        (existing + incoming).forEach { item ->
            val artist = item.artist.trim()
            if (artist.isBlank()) return@forEach
            val previous = merged[artist]
            if (previous == null || item.score > previous.score ||
                item.updatedAt > previous.updatedAt
            ) {
                merged[artist] = item.copy(
                    score = maxOf(previous?.score ?: 0.0, item.score),
                    updatedAt = maxOf(previous?.updatedAt ?: 0L, item.updatedAt),
                )
            }
        }
        return merged.values.sortedByDescending { it.score }.take(MAX_AFFINITIES)
    }

    private fun mergePlayEvents(
        existing: List<PairPlayEvent>,
        incoming: List<PairPlayEvent>,
    ): List<PairPlayEvent> {
        val merged = LinkedHashMap<String, PairPlayEvent>()
        (existing + incoming).forEach { item ->
            val key = "${item.videoId}|${item.startedAt}"
            val previous = merged[key]
            if (previous == null || item.playedMs > previous.playedMs ||
                (item.completed && !previous.completed)
            ) {
                merged[key] = if (previous == null) {
                    item
                } else {
                    item.copy(
                        playedMs = maxOf(previous.playedMs, item.playedMs),
                        durationMs = maxOf(previous.durationMs, item.durationMs),
                        completed = previous.completed || item.completed,
                    )
                }
            }
        }
        return merged.values.sortedByDescending { it.startedAt }.take(MAX_PLAY_EVENTS)
    }
}
