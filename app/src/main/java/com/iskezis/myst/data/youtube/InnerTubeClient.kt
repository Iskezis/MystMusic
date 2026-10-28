package com.iskezis.myst.data.youtube

import android.content.Context
import android.net.Uri
import com.iskezis.myst.data.account.SessionManager
import com.iskezis.myst.util.Thumbnails
import com.iskezis.myst.util.sha1Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Cliente InnerTube do YouTube Music. Toda a API não oficial fica isolada aqui,
 * atrás de MusicSource. Nenhum campo é acessado com !! e qualquer JSON fora do
 * formato esperado vira lista vazia ou erro de domínio.
 */
class InnerTubeClient(
    @Suppress("unused") private val context: Context,
    private val session: SessionManager,
) : MusicSource {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val urlCache = ConcurrentHashMap<String, CachedUrl>()

    override suspend fun search(query: String, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("context", clientContext(WEB_REMIX))
            put("query", query)
            put("params", SONGS_FILTER)
        }
        val response = post("search", body, headers(WEB_REMIX))
        parseTracks(response).take(limit)
    }

    override suspend fun trending(limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("context", clientContext(WEB_REMIX))
            put("browseId", "FEmusic_charts")
        }
        val response = post("browse", body, headers(WEB_REMIX))
        parseTracks(response).take(limit)
    }

    override suspend fun related(videoId: String, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("context", clientContext(WEB_REMIX))
            put("videoId", videoId)
            put("isAudioOnly", true)
        }
        val response = post("next", body, headers(WEB_REMIX))
        parseTracks(response).filter { it.videoId != videoId }.take(limit)
    }

    override suspend fun homeFeed(limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("context", clientContext(WEB_REMIX))
            put("browseId", "FEmusic_home")
        }
        val response = post("browse", body, headers(WEB_REMIX))
        parseTracks(response).take(limit)
    }

    override suspend fun streamUrl(videoId: String): String = withContext(Dispatchers.IO) {
        resolveStream(videoId)
    }

    override fun streamUrlBlocking(videoId: String): String = resolveStream(videoId)

    override fun invalidateStream(videoId: String) {
        urlCache.remove(videoId)
    }

    override suspend fun accountName(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject { put("context", clientContext(WEB_REMIX)) }
            val response = post("account/account_menu", body, headers(WEB_REMIX))
            val nameObject = findFirstObject(response, "accountName") ?: return@runCatching null
            runsText(nameObject).ifBlank { null }
        }.getOrNull()
    }

    /**
     * Chamado pela thread de carregamento do ExoPlayer. Tenta clientes diferentes,
     * porque a disponibilidade de URL direta (sem cifra) varia por cliente.
     */
    fun resolveStream(videoId: String): String {
        val cached = urlCache[videoId]
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) return cached.url

        var lastError: Throwable? = null
        for (clientName in CLIENTS) {
            try {
                val body = buildJsonObject {
                    put("context", clientContext(clientName))
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                    put("playbackContext", buildJsonObject {
                        put("contentPlaybackContext", buildJsonObject {
                            put("html5Preference", "HTML5_PREF_WANTS")
                        })
                    })
                }
                val response = post(
                    endpoint = "player",
                    body = body,
                    requestHeaders = headers(clientName),
                    baseUrl = if (clientName == WEB_REMIX) BASE_URL else YOUTUBE_BASE_URL,
                )
                val status = response["playabilityStatus"]?.let { element ->
                    (element as? JsonObject)?.get("status")?.let { (it as? JsonPrimitive)?.contentOrNull }
                }
                if (status != null && status != "OK") {
                    lastError = MusicException("Faixa indisponível ($status)")
                    continue
                }
                val url = extractAudioUrl(response)
                if (!url.isNullOrBlank()) {
                    urlCache[videoId] = CachedUrl(url, System.currentTimeMillis() + URL_TTL_MS)
                    return url
                }
                lastError = MusicException("Sem URL de áudio")
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw MusicException("Não foi possível obter o áudio desta faixa.", lastError)
    }

    private fun extractAudioUrl(root: JsonObject): String? {
        val streaming = root["streamingData"] as? JsonObject ?: return null
        val formats = mutableListOf<JsonObject>()
        (streaming["adaptiveFormats"] as? JsonArray)?.forEach { element ->
            (element as? JsonObject)?.let { formats.add(it) }
        }
        (streaming["formats"] as? JsonArray)?.forEach { element ->
            (element as? JsonObject)?.let { formats.add(it) }
        }
        val audio = formats.filter { format ->
            val mime = (format["mimeType"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            mime.contains("audio", ignoreCase = true) &&
                format["drmAdditionalHeader"] == null &&
                format["drmFamilies"] == null
        }
        // AAC (itag 140/141) toca em praticamente qualquer aparelho e TV. Opus
        // (251) fica como reserva: alguns decodificadores antigos não o suportam.
        val sorted = audio.sortedWith(
            compareBy<JsonObject> { audioRank(it) }
                .thenByDescending { number(it["bitrate"]) },
        )
        sorted.forEach { format ->
            formatUrl(format)?.takeIf { it.startsWith("http") }?.let { return it }
        }
        return null
    }

    private fun audioRank(format: JsonObject): Int {
        val itag = number(format["itag"])?.toInt()
        val mime = (format["mimeType"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when {
            itag == 140 -> 0
            itag == 141 -> 1
            itag == 139 -> 2
            mime.contains("mp4", ignoreCase = true) -> 3
            itag == 251 -> 4
            itag == 250 -> 5
            itag == 249 -> 6
            mime.contains("webm", ignoreCase = true) -> 7
            else -> 8
        }
    }

    private fun number(element: JsonElement?): Long? =
        (element as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    /**
     * Muitos formatos vêm em signatureCipher. Quando a assinatura já vem pronta
     * (sig) ela pode ser anexada; quando vem cifrada (s) não há como decifrar sem
     * baixar o JS do player, então o formato é ignorado e outro cliente é tentado.
     */
    private fun formatUrl(format: JsonObject): String? {
        (format["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val cipher = ((format["signatureCipher"] ?: format["cipher"]) as? JsonPrimitive)
            ?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val params = try {
            Uri.parse("https://local.invalid/?$cipher")
        } catch (t: Throwable) {
            return null
        }
        val url = params.getQueryParameter("url")?.takeIf { it.isNotBlank() } ?: return null
        val signature = params.getQueryParameter("sig")
            ?: params.getQueryParameter("signature")
            ?: return null
        val param = params.getQueryParameter("sp")?.takeIf { it.isNotBlank() } ?: "signature"
        val separator = if (url.contains("?")) "&" else "?"
        return "$url$separator$param=$signature"
    }

    private fun parseTracks(root: JsonObject): List<Track> {
        val found = LinkedHashMap<String, Track>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    (element["musicResponsiveListItemRenderer"] as? JsonObject)?.let { renderer ->
                        parseResponsive(renderer)?.let { track -> if (!found.containsKey(track.videoId)) found[track.videoId] = track }
                    }
                    (element["musicTwoRowItemRenderer"] as? JsonObject)?.let { renderer ->
                        parseTwoRow(renderer)?.let { track -> if (!found.containsKey(track.videoId)) found[track.videoId] = track }
                    }
                    element.values.forEach { walk(it) }
                }
                is JsonArray -> element.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(root)
        return found.values.toList()
    }

    private fun parseResponsive(item: JsonObject): Track? {
        val columns = (item["flexColumns"] as? JsonArray)?.mapNotNull { column ->
            (column as? JsonObject)
                ?.get("musicResponsiveListItemFlexColumnRenderer")
                ?.let { it as? JsonObject }
                ?.get("text")
                ?.let { it as? JsonObject }
        }.orEmpty()

        val title = columns.getOrNull(0)?.let(::runsText).orEmpty()
        if (title.isBlank()) return null

        val rawArtist = columns.getOrNull(1)?.let(::runsText).orEmpty()
        val artist = cleanArtist(rawArtist)
        val videoId = findVideoId(item) ?: return null
        if (!isVideoId(videoId)) return null

        val fixedColumns = (item["fixedColumns"] as? JsonArray)?.mapNotNull { column ->
            (column as? JsonObject)
                ?.get("musicResponsiveListItemFixedColumnRenderer")
                ?.let { it as? JsonObject }
                ?.get("text")
                ?.let { it as? JsonObject }
        }.orEmpty()
        val durationText = fixedColumns.firstOrNull()?.let(::runsText)
            ?: columns.getOrNull(2)?.let(::runsText)
        val duration = parseDuration(durationText)

        val thumb = extractThumbnail(item)
        return Track(videoId, title.trim(), artist, thumb, duration)
    }

    private fun parseTwoRow(item: JsonObject): Track? {
        val title = (item["title"] as? JsonObject)?.let(::runsText).orEmpty()
        if (title.isBlank()) return null
        val subtitle = (item["subtitle"] as? JsonObject)?.let(::runsText).orEmpty()
        val navigation = item["navigationEndpoint"] as? JsonObject ?: return null
        val watch = navigation["watchEndpoint"] as? JsonObject ?: return null
        val videoId = (watch["videoId"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (!isVideoId(videoId)) return null
        val thumb = extractThumbnail(item)
        return Track(videoId, title.trim(), cleanArtist(subtitle), thumb, 0L)
    }

    private fun findVideoId(item: JsonObject): String? {
        (item["playlistItemData"] as? JsonObject)
            ?.get("videoId")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.let { return it }
        findFirstObject(item, "watchEndpoint")?.let { endpoint ->
            (endpoint["videoId"] as? JsonPrimitive)?.contentOrNull?.let { return it }
        }
        findFirstObject(item, "playNavigationEndpoint")?.let { endpoint ->
            (endpoint["watchEndpoint"] as? JsonObject)
                ?.get("videoId")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?.let { return it }
        }
        return null
    }

    private fun extractThumbnail(item: JsonObject): String? {
        val candidates = mutableListOf<JsonObject>()
        fun collect(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    val thumbnails = element["thumbnails"] as? JsonArray
                    thumbnails?.forEach { thumb ->
                        (thumb as? JsonObject)?.let { candidates.add(it) }
                    }
                    element.forEach { (key, value) ->
                        if (key != "thumbnails") collect(value)
                    }
                }
                is JsonArray -> element.forEach { collect(it) }
                else -> Unit
            }
        }
        collect(item)
        val best = candidates.maxByOrNull { thumb ->
            val width = number(thumb["width"]) ?: 0L
            val height = number(thumb["height"]) ?: 0L
            width * height
        } ?: return null
        val url = (best["url"] as? JsonPrimitive)?.contentOrNull
        return Thumbnails.best(url, findVideoId(item))
    }

    private fun runsText(text: JsonObject): String {
        val runs = text["runs"] as? JsonArray ?: return ""
        return runs.joinToString("") { run ->
            ((run as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        }.trim()
    }

    private fun cleanArtist(raw: String): String {
        if (raw.isBlank()) return ""
        val first = raw.split("•").firstOrNull().orEmpty().trim()
        return first.removeSuffix(" E").removeSuffix(" E ").trim()
    }

    private fun parseDuration(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val cleaned = raw.trim()
        if (!cleaned.contains(":")) return 0L
        val parts = cleaned.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            2 -> (parts[0] * 60 + parts[1]) * 1000
            else -> 0L
        }
    }

    private fun isVideoId(value: String): Boolean {
        if (value.length != 11) return false
        return value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    private fun findFirstObject(element: JsonElement, key: String): JsonObject? =
        findFirstValue(element, key) as? JsonObject

    private fun findFirstValue(element: JsonElement, key: String): JsonElement? {
        return when (element) {
            is JsonObject -> {
                element[key]?.let { return it }
                for (value in element.values) {
                    findFirstValue(value, key)?.let { return it }
                }
                null
            }
            is JsonArray -> {
                for (value in element) {
                    findFirstValue(value, key)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun post(
        endpoint: String,
        body: JsonObject,
        requestHeaders: Headers,
        baseUrl: String = BASE_URL,
    ): JsonObject {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url("$baseUrl/$endpoint?prettyPrint=false")
            .post(body.toString().toRequestBody(mediaType))
            .headers(requestHeaders)
            .build()

        var lastError: Throwable? = null
        for (attempt in 0 until 3) {
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val error = MusicException("HTTP ${response.code}")
                        if (response.code in 400..499) throw error
                        lastError = error
                    } else {
                        val parsed = json.parseToJsonElement(text) as? JsonObject
                            ?: throw MusicException("Resposta inválida")
                        (parsed["responseContext"] as? JsonObject)
                            ?.get("visitorData")
                            ?.let { (it as? JsonPrimitive)?.contentOrNull }
                            ?.let { session.youtubeVisitorData = it }
                        return parsed
                    }
                }
            } catch (t: Throwable) {
                lastError = t
                if (t is MusicException && t.message?.startsWith("HTTP 4") == true) break
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(400L * (attempt + 1))
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw MusicException("Falha ao falar com o YouTube Music.", lastError)
    }

    private fun headers(clientName: String): Headers {
        val builder = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "application/json")
            .add(
                "Origin",
                if (clientName == WEB_REMIX) "https://music.youtube.com" else "https://www.youtube.com",
            )
            .add(
                "Referer",
                if (clientName == WEB_REMIX) "https://music.youtube.com/" else "https://www.youtube.com/",
            )
            .add("X-Goog-Api-Format-Version", "2")
            .add("X-YouTube-Client-Name", clientId(clientName))
            .add("X-YouTube-Client-Version", clientVersion(clientName))
            .add("User-Agent", userAgent(clientName))
        session.youtubeCookies?.takeIf { it.isNotBlank() }?.let { builder.add("Cookie", it) }
        session.youtubeSapisid?.takeIf { it.isNotBlank() }?.let { sapisid ->
            val timestamp = System.currentTimeMillis() / 1000
            val hash = sha1Hex("$timestamp $sapisid https://music.youtube.com")
            builder.add("Authorization", "SAPISIDHASH ${timestamp}_$hash")
        }
        session.youtubeVisitorData?.takeIf { it.isNotBlank() }?.let {
            builder.add("X-Goog-Visitor-Id", it)
        }
        return builder.build()
    }

    private fun clientContext(clientName: String): JsonObject = buildJsonObject {
        put("client", buildJsonObject {
            put("clientName", clientName)
            put("clientVersion", clientVersion(clientName))
            put("hl", "pt")
            put("gl", "BR")
            when (clientName) {
                WEB_REMIX -> put("originalUrl", "https://music.youtube.com/")
                IOS -> {
                    put("deviceMake", "Apple")
                    put("deviceModel", "iPhone16,2")
                    put("osName", "iPhone")
                    put("osVersion", "18.1.0.22B83")
                }
                ANDROID_VR -> {
                    put("androidSdkVersion", 32)
                    put("osName", "Android")
                    put("osVersion", "12")
                }
                else -> {
                    put("androidSdkVersion", 30)
                    put("osName", "Android")
                    put("osVersion", "11")
                }
            }
            session.youtubeVisitorData?.takeIf { it.isNotBlank() }?.let { put("visitorData", it) }
        })
        put("user", buildJsonObject { put("lockedSafetyMode", false) })
    }

    private fun clientId(clientName: String): String = when (clientName) {
        WEB_REMIX -> "67"
        ANDROID_MUSIC -> "21"
        IOS -> "5"
        ANDROID_VR -> "28"
        ANDROID -> "3"
        else -> "67"
    }

    private fun clientVersion(clientName: String): String = when (clientName) {
        WEB_REMIX -> "1.20241001.01.00"
        ANDROID_MUSIC -> "6.42.52"
        IOS -> "19.45.4"
        ANDROID_VR -> "1.62.27"
        ANDROID -> "19.44.38"
        else -> "1.20241001.01.00"
    }

    private fun userAgent(clientName: String): String = when (clientName) {
        ANDROID_MUSIC -> "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 11) gzip"
        IOS -> "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
        ANDROID_VR -> "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12; en_US; Quest 3 Build/SQ3A.220605.009.A1) gzip"
        ANDROID -> "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip"
        else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private data class CachedUrl(val url: String, val expiresAt: Long)

    companion object {
        private const val BASE_URL = "https://music.youtube.com/youtubei/v1"
        private const val YOUTUBE_BASE_URL = "https://www.youtube.com/youtubei/v1"
        private const val WEB_REMIX = "WEB_REMIX"
        private const val ANDROID_MUSIC = "ANDROID_MUSIC"
        private const val IOS = "IOS"
        private const val ANDROID_VR = "ANDROID_VR"
        private const val ANDROID = "ANDROID"
        private const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="
        private const val URL_TTL_MS = 30 * 60 * 1000L
        // Clientes "de app" costumam devolver URL direta; o web é o último recurso.
        private val CLIENTS = listOf(ANDROID_VR, IOS, ANDROID_MUSIC, ANDROID, WEB_REMIX)
    }
}
