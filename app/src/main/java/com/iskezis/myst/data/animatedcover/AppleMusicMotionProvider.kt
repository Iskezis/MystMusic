package com.iskezis.myst.data.animatedcover

import android.os.SystemClock
import com.iskezis.myst.util.AppLocale
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Provedor funcional sem credenciais: usa a busca pública do iTunes para
 * localizar o álbum e lê a arte em movimento da página do álbum da Apple
 * Music. A Apple pode mudar esse formato; nesse caso o provedor devolve
 * `null`/erro e o player mantém a capa estática.
 */
class AppleMusicMotionProvider : AnimatedCoverProvider {

    override val id: String = PROVIDER_ID

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun find(track: TrackKey): AnimatedCover? = withContext(Dispatchers.IO) {
        val preferred = storefront()
        val storefronts = if (preferred == "us") listOf("us") else listOf(preferred, "us")
        val deadline = SystemClock.elapsedRealtime() + PROVIDER_BUDGET_MS
        var lastError: Throwable? = null

        for (storefront in storefronts) {
            try {
                val candidates = search(track, storefront, deadline)
                val match = AnimatedCoverMatcher.bestMatch(track, candidates)
                if (match == null) continue
                val albumId = match.albumId ?: continue
                val html = get(albumPageUrl(storefront, albumId), deadline)
                val url = AppleMusicMotionParser.extractMotionUrl(html, albumId) ?: continue
                val mime = if (url.contains(".m3u8", ignoreCase = true)) "hls" else "mp4"
                return@withContext AnimatedCover(
                    url = url,
                    provider = id,
                    mime = mime,
                    aspect = "1:1",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                lastError = t
            }
        }

        lastError?.let { throw it }
        null
    }

    private fun search(
        track: TrackKey,
        storefront: String,
        deadline: Long,
    ): List<AnimatedCoverCandidate> {
        val term = listOf(track.artist, track.title)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "musicTrack")
            .addQueryParameter("country", storefront)
            .addQueryParameter("limit", "25")
            .build()
        val root = json.parseToJsonElement(get(url.toString(), deadline)).jsonObject
        return root["results"]?.jsonArray?.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            AnimatedCoverCandidate(
                title = item.string("trackName"),
                artist = item.string("artistName"),
                album = item.string("collectionName"),
                durationMs = item.long("trackTimeMillis"),
                albumId = item.long("collectionId"),
            )
        }.orEmpty()
    }

    private fun get(url: String, deadline: Long): String {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0L) {
            throw IOException("Provedor $PROVIDER_ID excedeu o tempo limite")
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.8")
            .header("User-Agent", USER_AGENT)
            .build()
        val call = http.newCall(request)
        call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Provedor $PROVIDER_ID respondeu HTTP ${response.code}")
            }
            return response.body?.string()
                ?: throw IOException("Provedor $PROVIDER_ID devolveu resposta vazia")
        }
    }

    private fun albumPageUrl(storefront: String, albumId: Long): String =
        "https://music.apple.com/$storefront/album/$albumId"

    private fun storefront(): String {
        val country = AppLocale.currentLocale.country.lowercase(Locale.ROOT)
        return if (country.length == 2 && country.all { it in 'a'..'z' }) country else "us"
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    companion object {
        const val PROVIDER_ID = "apple-music"
        private const val PROVIDER_BUDGET_MS = 5_500L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
