package com.iskezis.myst.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.iskezis.myst.MystApp
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Converte myst://track/<videoId> na URL de áudio real do InnerTube no momento
 * em que o ExoPlayer abre o item. Assim a fila pode ser montada sem resolver
 * todas as URLs de uma vez e uma URL expirada é resolvida de novo no retry.
 */
class StreamResolver(private val app: MystApp) : ResolvingDataSource.Resolver {

    private data class Cached(val url: String, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme != SCHEME) return dataSpec
        val videoId = uri.lastPathSegment ?: uri.host
        if (videoId.isNullOrBlank()) {
            throw IOException("Faixa sem identificador")
        }
        val url = resolve(videoId)
        val cookies = app.session.youtubeCookies
        val headers = buildMap {
            put(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            )
            put("Referer", "https://music.youtube.com/")
            put("Origin", "https://music.youtube.com")
            if (!cookies.isNullOrBlank()) put("Cookie", cookies)
        }
        return dataSpec.buildUpon()
            .setUri(Uri.parse(url))
            .setHttpRequestHeaders(headers)
            .build()
    }

    fun resolve(videoId: String): String {
        val cached = cache[videoId]
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) return cached.url
        val url = try {
            app.youtube.streamUrlBlocking(videoId)
        } catch (t: Throwable) {
            throw IOException("Não foi possível resolver o áudio", t)
        }
        cache[videoId] = Cached(url, System.currentTimeMillis() + TTL_MS)
        return url
    }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
        app.youtube.invalidateStream(videoId)
    }

    companion object {
        const val SCHEME = "myst"
        private const val TTL_MS = 30 * 60 * 1000L
    }
}
