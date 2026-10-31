package com.iskezis.myst.player

import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.iskezis.myst.util.AppLog
import java.io.IOException

/**
 * Escolhe a fonte de dados depois que o [StreamResolver] já converteu
 * `myst://track/<id>` em `file://` (download offline) ou `https://`
 * (streaming). Downloads não passam pelo cache para não ocupar o dobro do
 * espaço; o streaming usa [androidx.media3.datasource.cache.CacheDataSource]
 * somente quando o cache está ligado nas Configurações.
 */
class RoutingDataSourceFactory(
    private val cacheFactory: DataSource.Factory,
    private val directFactory: DataSource.Factory,
    private val cacheEnabled: () -> Boolean,
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        RoutingDataSource(cacheFactory, directFactory, cacheEnabled)
}

private class RoutingDataSource(
    private val cacheFactory: DataSource.Factory,
    private val directFactory: DataSource.Factory,
    private val cacheEnabled: () -> Boolean,
) : BaseDataSource(true) {

    private var delegate: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val local = dataSpec.uri.scheme == "file"
        val useCache = !local && cacheEnabled()
        val source = if (useCache) cacheFactory.createDataSource() else directFactory.createDataSource()
        delegate = source
        return try {
            source.open(dataSpec)
        } catch (t: Throwable) {
            runCatching { source.close() }
            delegate = null
            AppLog.failure(
                AppLog.TAG_STREAM,
                "Falha abrindo fonte de áudio " +
                    "(${if (local) "arquivo" else if (useCache) "rede+cache" else "rede"})",
                t,
            )
            throw if (t is IOException) t else IOException("Falha abrindo o áudio", t)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = delegate ?: throw IOException("Fonte de áudio não aberta")
        return source.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        val source = delegate ?: return
        delegate = null
        source.close()
    }
}
