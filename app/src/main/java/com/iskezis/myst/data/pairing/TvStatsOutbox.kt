package com.iskezis.myst.data.pairing

import android.content.Context
import com.iskezis.myst.util.AppLog
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Pequeno "outbox" em disco para os eventos de reprodução da TV.
 *
 * O banco da TV é em memória de propósito, mas as estatísticas só são
 * realmente úteis quando chegam ao celular. Se o celular estiver dormindo,
 * fora da rede ou com o processo encerrado no exato momento do play, a TV
 * guarda aqui o último retrato de estatísticas e tenta de novo na próxima
 * abertura do app ou no próximo pareamento com a mesma conta Myst.
 *
 * O arquivo não contém credenciais, apenas os mesmos metadados que já viajam
 * no snapshot de pareamento.
 */
@Serializable
data class PendingTvStats(
    val userId: Long = 0L,
    val savedAt: Long = 0L,
    val events: List<PairPlayEvent> = emptyList(),
)

object TvStatsOutbox {

    private const val DIRECTORY = "pairing"
    private const val FILE_NAME = "tv_pending_stats.json"
    private const val MAX_EVENTS = 5_000

    private val lock = Any()

    /**
     * Mescla os eventos atuais com o que já estava pendente. A mesclagem por
     * `videoId|startedAt` mantém o maior tempo ouvido, igual ao merge do
     * celular, então chamar isto várias vezes não duplica reproduções.
     */
    fun store(context: Context, userId: Long, events: List<PairPlayEvent>) {
        if (userId <= 0L) return
        synchronized(lock) {
            runCatching {
                val existing = read(context)
                    ?.takeIf { it.userId == userId }
                    ?.events
                    .orEmpty()
                val merged = merge(existing, events)
                if (merged.isEmpty()) {
                    clearLocked(context)
                    return
                }
                val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
                val target = File(directory, FILE_NAME)
                val temp = File(directory, "$FILE_NAME.tmp")
                val payload = PendingTvStats(
                    userId = userId,
                    savedAt = System.currentTimeMillis(),
                    events = merged,
                )
                temp.writeText(PairingSync.json.encodeToString(PendingTvStats.serializer(), payload))
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                AppLog.d(
                    AppLog.TAG_PAIRING,
                    "Estatísticas da TV guardadas para reenvio: ${merged.size} evento(s)",
                )
            }.onFailure {
                AppLog.failure(
                    AppLog.TAG_PAIRING,
                    "Não foi possível guardar as estatísticas pendentes da TV",
                    it,
                )
            }
        }
    }

    fun load(context: Context): PendingTvStats? = synchronized(lock) {
        read(context)?.takeIf { it.userId > 0L && it.events.isNotEmpty() }
    }

    fun clear(context: Context) {
        synchronized(lock) { clearLocked(context) }
    }

    private fun read(context: Context): PendingTvStats? = runCatching {
        val file = File(File(context.filesDir, DIRECTORY), FILE_NAME)
        if (!file.isFile) return@runCatching null
        PairingSync.json.decodeFromString(
            PendingTvStats.serializer(),
            file.readText(),
        )
    }.onFailure {
        AppLog.w(
            AppLog.TAG_PAIRING,
            "Estatísticas pendentes da TV ilegíveis; ignorando: ${it.message}",
        )
    }.getOrNull()

    private fun merge(
        pending: List<PairPlayEvent>,
        current: List<PairPlayEvent>,
    ): List<PairPlayEvent> {
        val byKey = LinkedHashMap<String, PairPlayEvent>()
        (pending + current).forEach { event ->
            val videoId = event.videoId.trim()
            if (videoId.isBlank() || event.playedMs <= 0L) return@forEach
            val startedAt = event.startedAt.takeIf { it > 0L } ?: 1L
            val key = "$videoId|$startedAt"
            val previous = byKey[key]
            byKey[key] = if (previous == null) {
                event
            } else {
                event.copy(
                    playedMs = maxOf(previous.playedMs, event.playedMs),
                    durationMs = maxOf(previous.durationMs, event.durationMs),
                    completed = previous.completed || event.completed,
                )
            }
        }
        return byKey.values
            .sortedByDescending { it.startedAt }
            .take(MAX_EVENTS)
    }

    private fun clearLocked(context: Context) {
        runCatching {
            File(File(context.filesDir, DIRECTORY), FILE_NAME).delete()
            File(File(context.filesDir, DIRECTORY), "$FILE_NAME.tmp").delete()
        }.onFailure {
            AppLog.w(AppLog.TAG_PAIRING, "Falha removendo estatísticas pendentes da TV")
        }
    }
}
