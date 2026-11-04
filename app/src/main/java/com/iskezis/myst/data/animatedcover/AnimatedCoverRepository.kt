package com.iskezis.myst.data.animatedcover

import com.iskezis.myst.data.db.AnimatedCoverDao
import com.iskezis.myst.data.db.AnimatedCoverEntity
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Orquestra provedores, cache negativo/positivo e timeout curto. A falha de
 * um provedor nunca interrompe a reprodução: o retorno é `null` e o player
 * continua com a capa estática.
 */
class AnimatedCoverRepository(
    private val dao: AnimatedCoverDao,
    private val providers: List<AnimatedCoverProvider>,
) {

    @Volatile
    private var lastPruneAt: Long = 0L

    suspend fun resolve(track: Track): ResolvedAnimatedCover? {
        if (track.title.isBlank() || providers.isEmpty()) return null
        val key = AnimatedCoverMatcher.albumKey(track.toKey())
        val now = System.currentTimeMillis()
        val cached = runCatching { dao.find(key) }.onFailure {
            AppLog.failure(AppLog.TAG_DB, "Capa animada: falha lendo o cache", it)
        }.getOrNull()

        if (cached != null) {
            val age = (now - cached.fetchedAt).coerceAtLeast(0L)
            val ttl = if (cached.notFound) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
            if (age < ttl) {
                return if (cached.notFound) null else cached.toResolved(key)
            }
        }

        var hadError = false
        var found: AnimatedCover? = null
        for (provider in providers) {
            try {
                found = withTimeout(PROVIDER_TIMEOUT_MS) { provider.find(track.toKey()) }
            } catch (timeout: TimeoutCancellationException) {
                hadError = true
                AppLog.w(AppLog.TAG_PLAYER, "Capa animada: ${provider.id} excedeu 6 s")
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                hadError = true
            } catch (t: Throwable) {
                hadError = true
                AppLog.failure(
                    AppLog.TAG_PLAYER,
                    "Capa animada: provedor ${provider.id} falhou",
                    t,
                )
            }
            if (found != null) break
        }

        if (found != null) {
            val entry = AnimatedCoverEntity(
                albumKey = key,
                url = found.url,
                provider = found.provider,
                mime = found.mime,
                fetchedAt = now,
                notFound = false,
            )
            runCatching { dao.upsert(entry) }.onFailure {
                AppLog.failure(AppLog.TAG_DB, "Capa animada: falha gravando o cache", it)
            }
            AppLog.i(
                AppLog.TAG_PLAYER,
                "Capa animada encontrada por ${found.provider}: ${track.title}",
            )
            pruneIfNeeded(now)
            return ResolvedAnimatedCover(key, found)
        }

        // Uma URL positiva vencida ainda pode funcionar (ou o formato da
        // página mudou). Mantém a capa e renova a validade; se o vídeo estiver
        // realmente morto, o erro de reprodução invalida e consulta de novo.
        val stale = cached?.takeIf { !it.notFound && it.url.isNotBlank() }
        if (stale != null) {
            runCatching { dao.upsert(stale.copy(fetchedAt = now)) }.onFailure {
                AppLog.w(
                    AppLog.TAG_DB,
                    "Capa animada: falha renovando o cache: ${AppLog.describe(it)}",
                )
            }
            return stale.toResolved(key)
        }

        if (hadError) return null

        runCatching {
            dao.upsert(
                AnimatedCoverEntity(
                    albumKey = key,
                    url = "",
                    provider = "",
                    mime = "",
                    fetchedAt = now,
                    notFound = true,
                ),
            )
        }.onFailure {
            AppLog.failure(AppLog.TAG_DB, "Capa animada: falha no cache negativo", it)
        }
        pruneIfNeeded(now)
        return null
    }

    /** Remove a URL que falhou para que a próxima reprodução revalide. */
    suspend fun invalidate(albumKey: String) {
        runCatching { dao.delete(albumKey) }.onFailure {
            AppLog.failure(AppLog.TAG_DB, "Capa animada: falha invalidando o cache", it)
        }
    }

    private suspend fun pruneIfNeeded(now: Long) {
        if (now - lastPruneAt < PRUNE_INTERVAL_MS) return
        lastPruneAt = now
        runCatching { dao.deleteOlderThan(now - POSITIVE_TTL_MS) }.onFailure {
            AppLog.w(
                AppLog.TAG_DB,
                "Capa animada: falha podando o cache: ${AppLog.describe(it)}",
            )
        }
    }

    private fun Track.toKey(): TrackKey = TrackKey(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
    )

    private fun AnimatedCoverEntity.toResolved(key: String): ResolvedAnimatedCover? {
        if (notFound || url.isBlank()) return null
        return ResolvedAnimatedCover(
            albumKey = key,
            cover = AnimatedCover(
                url = url,
                provider = provider,
                mime = mime.ifBlank { "hls" },
            ),
        )
    }

    companion object {
        private const val PROVIDER_TIMEOUT_MS = 6_000L
        private const val NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val PRUNE_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
