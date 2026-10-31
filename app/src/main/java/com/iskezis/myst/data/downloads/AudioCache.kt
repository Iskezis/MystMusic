package com.iskezis.myst.data.downloads

import android.content.Context
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.iskezis.myst.util.AppLog
import java.io.File

/**
 * Cache de áudio do streaming, separado dos downloads permanentes.
 *
 * O download é uma escolha do usuário e nunca é apagado automaticamente. O
 * cache é temporário: acelera faixas já ouvidas e permite ouvir sem rede o
 * que estava em cache, respeitando o tamanho e a retenção escolhidos em
 * Configurações → Armazenamento.
 *
 * Usa NoOpCacheEvictor de propósito: o limite não é fixo no construtor (o
 * usuário pode mudar em Configurações). A poda é feita por trim(), sempre em
 * segundo plano e sem apagar o item em reprodução.
 */
class AudioCache(
    context: Context,
    private val preferences: StoragePreferences,
) {

    private val directory = File(context.applicationContext.cacheDir, "audio_cache")
    private var simpleCache: SimpleCache? = null

    @Synchronized
    fun cache(): SimpleCache {
        simpleCache?.let { return it }
        directory.mkdirs()
        val created = SimpleCache(directory, NoOpCacheEvictor())
        simpleCache = created
        AppLog.i(AppLog.TAG_PLAYER, "Cache de áudio aberto em ${directory.absolutePath}")
        return created
    }

    fun existing(): Cache? = simpleCache

    /** `videoId`s com algum trecho de áudio realmente gravado no cache. */
    fun cachedVideoIds(): Set<String> {
        val cache = cache()
        return runCatching {
            cache.keys.toList()
                .mapNotNull { key ->
                    key.removePrefix(STREAM_PREFIX)
                        .takeIf { key.startsWith(STREAM_PREFIX) && it.isNotBlank() }
                }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /** Bytes ocupados por uma faixa no cache (0 quando não há trecho gravado). */
    fun entryBytes(videoId: String): Long {
        val cache = cache()
        return runCatching {
            cache.getCachedSpans(streamKey(videoId))
                .sumOf { span -> if (span.isCached) span.length else 0L }
        }.getOrDefault(0L)
    }

    fun usedBytes(): Long {
        val cache = simpleCache
        if (cache != null) {
            return runCatching { cache.cacheSpace }.getOrDefault(directorySize(directory))
        }
        return directorySize(directory)
    }

    /**
     * Remove itens antigos e depois os menos usados até caber no limite.
     * `keepKey` é o videoId em reprodução; ele é o último a sair.
     */
    @Synchronized
    fun trim(keepKey: String? = null) {
        val cache = simpleCache ?: return
        if (!preferences.cacheEnabled) {
            clear()
            return
        }
        runCatching {
            val cutoff = if (preferences.cacheMaxAgeDays > 0) {
                System.currentTimeMillis() - preferences.cacheMaxAgeMs
            } else {
                0L
            }
            val spans = mutableListOf<CacheSpan>()
            cache.keys.toList().forEach { key ->
                runCatching { cache.getCachedSpans(key) }.getOrNull()?.forEach { span ->
                    if (span.isCached) spans += span
                }
            }
            spans.sortBy { if (it.lastTouchTimestamp > 0L) it.lastTouchTimestamp else 0L }
            var total = runCatching { cache.cacheSpace }.getOrDefault(0L)
            val max = preferences.cacheMaxBytes.coerceAtLeast(StoragePreferences.MIN_CACHE_BYTES)
            var removed = 0L
            var removedCount = 0
            for (span in spans) {
                val expired = cutoff > 0L && span.lastTouchTimestamp in 1 until cutoff
                val tooBig = total > max
                if (!expired && !tooBig) break
                if (keepKey != null && span.key == streamKey(keepKey)) continue
                runCatching { cache.removeSpan(span) }.onSuccess {
                    total -= span.length
                    removed += span.length
                    removedCount++
                }
            }
            if (removedCount > 0) {
                AppLog.i(
                    AppLog.TAG_PLAYER,
                    "Cache: removidos $removedCount itens (${formatBytes(removed)}); " +
                        "agora ${formatBytes(total)} de ${formatBytes(max)}",
                )
            }
        }.onFailure {
            AppLog.failure(AppLog.TAG_PLAYER, "Falha podando o cache de áudio", it)
        }
    }

    @Synchronized
    fun clear() {
        val cache = simpleCache
        if (cache != null) {
            runCatching {
                cache.keys.toList().forEach { key -> cache.removeResource(key) }
            }.onFailure {
                AppLog.failure(AppLog.TAG_PLAYER, "Falha limpando o cache de áudio", it)
            }
        } else {
            runCatching { directory.deleteRecursively() }
        }
        AppLog.i(AppLog.TAG_PLAYER, "Cache de áudio limpo")
    }

    /**
     * Descarta o cache de streaming de uma faixa que virou download. Sem isso
     * o mesmo áudio ficaria duas vezes no aparelho e o cache poderia ser
     * servido no lugar do arquivo offline.
     */
    @Synchronized
    fun removeStream(videoId: String) {
        val cache = simpleCache ?: return
        runCatching { cache.removeResource(streamKey(videoId)) }
            .onFailure {
                AppLog.failure(
                    AppLog.TAG_PLAYER,
                    "Falha removendo o cache da faixa baixada $videoId",
                    it,
                )
            }
    }

    private fun streamKey(videoId: String): String = "$STREAM_PREFIX$videoId"

    private fun directorySize(directory: File): Long {
        if (!directory.exists()) return 0L
        return runCatching {
            directory.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        }.getOrDefault(0L)
    }

    companion object {
        const val STREAM_PREFIX = "myst-stream:"
    }
}
