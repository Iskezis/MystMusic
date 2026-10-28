package com.iskezis.myst.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.iskezis.myst.MystApp
import com.iskezis.myst.StreamFailure
import com.iskezis.myst.data.youtube.LoginRequiredException
import com.iskezis.myst.data.youtube.StreamInfo
import com.iskezis.myst.util.AppLog
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Converte myst://track/<videoId> na URL de áudio real do InnerTube no momento
 * em que o ExoPlayer abre o item. Assim a fila pode ser montada sem resolver
 * todas as URLs de uma vez e uma URL expirada é resolvida de novo no retry.
 */
class StreamResolver(private val app: MystApp) : ResolvingDataSource.Resolver {

    private data class Cached(val info: StreamInfo, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme != SCHEME) return dataSpec
        val videoId = uri.lastPathSegment ?: uri.host
        if (videoId.isNullOrBlank()) {
            AppLog.e(AppLog.TAG_STREAM, "DataSpec myst sem videoId: $uri")
            throw IOException("Faixa sem identificador")
        }
        AppLog.d(
            AppLog.TAG_STREAM,
            "ExoPlayer pediu stream de $videoId " +
                "(posição=${dataSpec.position}, tamanho=${dataSpec.length})",
        )
        val info = resolve(videoId)
        AppLog.d(
            AppLog.TAG_STREAM,
            "Entregando URL para $videoId: ${AppLog.urlForLog(info.url)} " +
                "| headers=${info.headers.keys}",
        )
        return dataSpec.buildUpon()
            .setUri(Uri.parse(info.url))
            .setHttpRequestHeaders(info.headers)
            .build()
    }

    fun resolve(videoId: String): StreamInfo {
        val cached = cache[videoId]
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            AppLog.d(AppLog.TAG_STREAM, "Resolver cache hit: $videoId")
            return cached.info
        }
        val info = try {
            app.youtube.streamInfoBlocking(videoId)
        } catch (t: Throwable) {
            app.lastStreamFailure = StreamFailure(
                videoId = videoId,
                loginRequired = t is LoginRequiredException,
            )
            AppLog.failure(AppLog.TAG_STREAM, "Falha ao resolver stream $videoId", t)
            throw IOException("Não foi possível resolver o áudio", t)
        }
        app.lastStreamFailure = null
        cache[videoId] = Cached(info, System.currentTimeMillis() + TTL_MS)
        AppLog.i(AppLog.TAG_STREAM, "Resolver cache store: $videoId")
        return info
    }

    fun invalidate(videoId: String) {
        AppLog.w(AppLog.TAG_STREAM, "Invalidando stream $videoId após falha de reprodução")
        cache.remove(videoId)
        app.youtube.invalidateStream(videoId)
    }

    companion object {
        const val SCHEME = "myst"
        private const val TTL_MS = 30 * 60 * 1000L
    }
}
