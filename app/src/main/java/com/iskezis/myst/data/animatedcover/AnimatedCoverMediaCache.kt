package com.iskezis.myst.data.animatedcover

import android.content.Context
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.iskezis.myst.util.AppLog
import java.io.File

/**
 * Cache pequeno e temporário só para os vídeos de capa. Fica fora do cache de
 * áudio e é limitado a 60 MB; nunca guarda a capa animada como download
 * permanente. A instância é única porque o Media3 não permite abrir o mesmo
 * diretório duas vezes no processo.
 */
object AnimatedCoverMediaCache {

    private const val MAX_BYTES = 60L * 1024 * 1024
    private var cache: SimpleCache? = null

    @Synchronized
    fun get(context: Context): SimpleCache? {
        cache?.let { return it }
        return runCatching {
            val directory = File(context.applicationContext.cacheDir, "animated_cover_media")
            directory.mkdirs()
            SimpleCache(directory, LeastRecentlyUsedCacheEvictor(MAX_BYTES)).also {
                cache = it
                AppLog.i(AppLog.TAG_PLAYER, "Cache de capas animadas aberto em ${directory.absolutePath}")
            }
        }.onFailure {
            AppLog.failure(AppLog.TAG_PLAYER, "Não foi possível abrir o cache de capas animadas", it)
        }.getOrNull()
    }
}
