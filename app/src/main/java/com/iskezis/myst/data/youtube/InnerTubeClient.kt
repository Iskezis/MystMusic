package com.iskezis.myst.data.youtube

import android.content.Context
import android.net.Uri
import com.iskezis.myst.data.account.SessionManager
import com.iskezis.myst.util.AppLog
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
import java.io.IOException
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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val probeHttp = http.newBuilder()
        .callTimeout(10, TimeUnit.SECONDS)
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

    /**
     * Playlists da biblioteca da conta. "Músicas curtidas" (VLLM) é uma
     * playlist especial e vem sempre primeiro. A paginação é defensiva: para
     * quando o token repete, quando o limite chega ou quando o YouTube para de
     * devolver continuação.
     */
    override suspend fun libraryPlaylists(limit: Int): List<YtPlaylist> =
        withContext(Dispatchers.IO) {
            requireSession()
            val found = LinkedHashMap<String, YtPlaylist>()
            var continuation: String? = null
            var page = 0
            val seenTokens = HashSet<String>()
            do {
                val response = browse("FEmusic_liked_playlists", continuation)
                collectLibraryPlaylists(response, found, limit)
                if (found.size >= limit) break
                continuation = continuationToken(response)
                if (continuation != null && !seenTokens.add(continuation)) continuation = null
                page++
            } while (continuation != null && page < MAX_LIBRARY_PAGES)

            if (found.isEmpty()) {
                // Algumas contas devolvem a grade em library_landing em vez de
                // liked_playlists. O parser filtra apenas playlists (VL...).
                val landing = browse("FEmusic_library_landing")
                collectLibraryPlaylists(landing, found, limit)
            }

            val items = found.values.toMutableList()
            val liked = items.firstOrNull { it.liked }
                ?: YtPlaylist(
                    id = LIKED_PLAYLIST_ID,
                    title = "Músicas curtidas",
                    liked = true,
                )
            (listOf(liked) + items.filterNot { it.liked }).take(limit)
        }

    override suspend fun accountHistory(limit: Int): List<Track> =
        withContext(Dispatchers.IO) {
            requireSession()
            val found = LinkedHashMap<String, Track>()
            var continuation: String? = null
            var page = 0
            val seenTokens = HashSet<String>()
            do {
                val response = browse("FEmusic_history", continuation)
                parseTracks(response).forEach { track ->
                    if (found.size < limit) found.putIfAbsent(track.videoId, track)
                }
                if (found.size >= limit) break
                continuation = continuationToken(response)
                if (continuation != null && !seenTokens.add(continuation)) continuation = null
                page++
            } while (continuation != null && page < MAX_HISTORY_PAGES)
            found.values.toList()
        }

    override suspend fun personalizedHome(limit: Int): List<Track> =
        withContext(Dispatchers.IO) {
            requireSession()
            val found = LinkedHashMap<String, Track>()
            var continuation: String? = null
            var page = 0
            val seenTokens = HashSet<String>()
            do {
                val response = browse("FEmusic_home", continuation)
                parseTracks(response).forEach { track ->
                    if (found.size < limit) found.putIfAbsent(track.videoId, track)
                }
                if (found.size >= limit) break
                continuation = continuationToken(response)
                if (continuation != null && !seenTokens.add(continuation)) continuation = null
                page++
            } while (continuation != null && page < MAX_HOME_PAGES)
            found.values.toList()
        }

    override suspend fun playlistTracks(playlistId: String, limit: Int): List<Track> =
        withContext(Dispatchers.IO) {
            requireSession()
            val id = playlistId.trim()
            if (id.isBlank()) throw MusicException("Playlist do YouTube Music sem identificador")
            val browseId = if (id.startsWith("VL")) id else "VL$id"
            val found = LinkedHashMap<String, Track>()
            var continuation: String? = null
            var page = 0
            val seenTokens = HashSet<String>()
            do {
                val response = browse(browseId, continuation)
                parseTracks(response).forEach { track ->
                    if (found.size < limit) found.putIfAbsent(track.videoId, track)
                }
                if (found.size >= limit) break
                continuation = continuationToken(response)
                if (continuation != null && !seenTokens.add(continuation)) continuation = null
                page++
            } while (continuation != null && page < MAX_PLAYLIST_PAGES)
            found.values.toList()
        }

    override suspend fun streamUrl(videoId: String): String = withContext(Dispatchers.IO) {
        resolveStreamInfo(videoId).url
    }

    override suspend fun streamInfo(videoId: String): StreamInfo = withContext(Dispatchers.IO) {
        resolveStreamInfo(videoId)
    }

    override fun streamUrlBlocking(videoId: String): String = resolveStreamInfo(videoId).url

    override fun streamInfoBlocking(videoId: String): StreamInfo = resolveStreamInfo(videoId)

    override fun invalidateStream(videoId: String) {
        urlCache.remove(videoId)
        AppLog.d(AppLog.TAG_STREAM, "Cache de stream invalidado: $videoId")
    }

    /**
     * Aquece o visitante anônimo e a conexão TLS em segundo plano, para o
     * primeiro play não pagar a home do YouTube Music antes de resolver a
     * faixa. Nunca lança: se a rede falhar, o primeiro play tenta de novo.
     */
    fun warmUp() {
        runCatching { ensureVisitorData() }
            .onFailure { AppLog.failure(AppLog.TAG_STREAM, "Falha aquecendo o InnerTube", it) }
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
     *
     * A URL escolhida é validada com uma requisição Range de 2 bytes usando o
     * mesmo User-Agent antes de ser devolvida. Assim uma URL expirada/bloqueada
     * não chega ao ExoPlayer e o próximo cliente pode ser tentado.
     */
    fun resolveStreamInfo(videoId: String): StreamInfo {
        val cached = urlCache[videoId]
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            AppLog.d(AppLog.TAG_STREAM, "Stream em cache: $videoId (${AppLog.urlForLog(cached.info.url)})")
            return cached.info
        }

        val startedAt = System.currentTimeMillis()
        var lastError: Throwable? = null
        var sawLoginRequired = false
        ensureVisitorData()
        val clients = if (!session.youtubeCookies.isNullOrBlank()) CLIENTS_WITH_COOKIES else CLIENTS_GUEST
        for (clientName in clients) {
            try {
                AppLog.d(AppLog.TAG_STREAM, "Resolvendo $videoId com cliente $clientName")
                val body = buildJsonObject {
                    put("context", clientContext(clientName))
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                    if (clientNeedsPlaybackContext(clientName)) {
                        put("playbackContext", buildJsonObject {
                            put("contentPlaybackContext", buildJsonObject {
                                put("html5Preference", "HTML5_PREF_WANTS")
                            })
                        })
                    }
                }
                val response = post(
                    endpoint = "player",
                    body = body,
                    requestHeaders = headers(clientName),
                    baseUrl = if (usesMusicEndpoint(clientName)) BASE_URL else YOUTUBE_BASE_URL,
                )
                val status = response["playabilityStatus"]?.let { element ->
                    (element as? JsonObject)?.get("status")?.let { (it as? JsonPrimitive)?.contentOrNull }
                }
                val reason = response["playabilityStatus"]?.let { element ->
                    (element as? JsonObject)?.get("reason")?.let { (it as? JsonPrimitive)?.contentOrNull }
                }
                if (status != null && status != "OK" && status != "LIVE_STREAM_OFFLINE") {
                    lastError = MusicException("Faixa indisponível ($status)")
                    val normalizedReason = reason.orEmpty().lowercase()
                    if (status == "LOGIN_REQUIRED" || status == "SIGN_IN_REQUIRED" ||
                        status == "AGE_CHECK_REQUIRED" ||
                        normalizedReason.contains("faça login") ||
                        normalizedReason.contains("nao e um bot") ||
                        normalizedReason.contains("não é um bot") ||
                        normalizedReason.contains("not a bot") ||
                        normalizedReason.contains("sign in")
                    ) {
                        sawLoginRequired = true
                    }
                    AppLog.w(
                        AppLog.TAG_STREAM,
                        "$clientName recusou $videoId: $status ${reason.orEmpty()}",
                    )
                    continue
                }
                val formats = selectAudioFormats(response)
                if (formats.isEmpty()) {
                    lastError = MusicException("Sem formatos de áudio")
                    val ciphered = countCipheredAudio(response)
                    AppLog.w(
                        AppLog.TAG_STREAM,
                        "$clientName não retornou formatos de áudio para $videoId " +
                            "(formatos cifrados=$ciphered)",
                    )
                    continue
                }
                var accepted: StreamInfo? = null
                for (format in formats) {
                    val url = playableUrl(format) ?: continue
                    val itag = number(format["itag"])?.toInt() ?: -1
                    val mime = (format["mimeType"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val bitrate = number(format["bitrate"]) ?: 0L
                    AppLog.d(
                        AppLog.TAG_STREAM,
                        "Candidato $clientName/$videoId: itag=$itag mime=$mime bitrate=$bitrate",
                    )
                    val candidate = StreamInfo(url, streamHeaders(clientName))
                    if (validateAudioUrl(videoId, clientName, candidate)) {
                        accepted = candidate
                        break
                    }
                }
                if (accepted == null) {
                    lastError = MusicException("Nenhuma URL de áudio passou na validação")
                    continue
                }
                urlCache[videoId] = CachedUrl(
                    accepted,
                    System.currentTimeMillis() + URL_TTL_MS,
                )
                AppLog.i(
                    AppLog.TAG_STREAM,
                    "Stream pronto: $videoId via $clientName em " +
                        "${System.currentTimeMillis() - startedAt}ms | " +
                        AppLog.urlForLog(accepted.url),
                )
                return accepted
            } catch (t: Throwable) {
                lastError = t
                AppLog.failure(AppLog.TAG_STREAM, "Falha resolvendo $videoId com $clientName", t)
                if (t is IOException || t.cause is IOException) {
                    AppLog.w(AppLog.TAG_STREAM, "Erro de rede; interrompendo tentativas de cliente")
                    break
                }
            }
        }
        val elapsed = System.currentTimeMillis() - startedAt
        AppLog.e(
            AppLog.TAG_STREAM,
            "Não foi possível obter áudio de $videoId após ${elapsed}ms: " +
                AppLog.describe(lastError),
            lastError,
        )
        if (sawLoginRequired) {
            throw LoginRequiredException(
                "O YouTube pediu login para liberar o áudio desta faixa.",
                lastError,
            )
        }
        throw MusicException("Não foi possível obter o áudio desta faixa.", lastError)
    }

    private fun countCipheredAudio(root: JsonObject): Int {
        val streaming = root["streamingData"] as? JsonObject ?: return 0
        var count = 0
        (streaming["adaptiveFormats"] as? JsonArray)?.forEach { element ->
            val format = element as? JsonObject ?: return@forEach
            val mime = (format["mimeType"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (!mime.contains("audio", ignoreCase = true)) return@forEach
            val cipher = ((format["signatureCipher"] ?: format["cipher"]) as? JsonPrimitive)
                ?.contentOrNull
            if (!cipher.isNullOrBlank()) count++
        }
        return count
    }

    /**
     * Requisição mínima para confirmar que o googlevideo aceita a URL e o UA.
     * 200/206 são sucesso; 3xx são seguidos pelo OkHttp. Qualquer outro código
     * faz o cliente ser descartado.
     */
    private fun validateAudioUrl(
        videoId: String,
        clientName: String,
        info: StreamInfo,
    ): Boolean {
        return try {
            val requestHeaders = Headers.Builder().apply {
                add("Range", "bytes=0-1")
                info.headers.forEach { (name, value) -> add(name, value) }
            }.build()
            val request = Request.Builder()
                .url(info.url)
                .headers(requestHeaders)
                .build()
            probeHttp.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty()
                val ok = (response.code == 206 || response.code == 200) &&
                    !contentType.startsWith("text/", ignoreCase = true)
                AppLog.d(
                    AppLog.TAG_STREAM,
                    "Validação $clientName/$videoId: HTTP ${response.code} " +
                        "| tipo=${contentType.ifBlank { "?" }}",
                )
                if (!ok) {
                    AppLog.w(
                        AppLog.TAG_STREAM,
                        "URL rejeitada ($clientName/$videoId): HTTP ${response.code} " +
                            "tipo=$contentType",
                    )
                }
                ok
            }
        } catch (t: Throwable) {
            AppLog.failure(
                AppLog.TAG_STREAM,
                "Falha validando URL ($clientName/$videoId)",
                t,
            )
            false
        }
    }

    private fun streamHeaders(clientName: String): Map<String, String> {
        val music = usesMusicEndpoint(clientName)
        val origin = if (music) "https://music.youtube.com" else "https://www.youtube.com"
        return mapOf(
            "User-Agent" to userAgent(clientName),
            "Origin" to origin,
            "Referer" to "$origin/",
            "Accept" to "*/*",
            "Accept-Encoding" to "identity",
        )
    }

    private fun selectAudioFormats(root: JsonObject): List<JsonObject> {
        val streaming = root["streamingData"] as? JsonObject ?: return emptyList()
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
        return audio.sortedWith(
            compareBy<JsonObject> { audioRank(it) }
                .thenByDescending { number(it["bitrate"]) },
        ).filter(::hasPlayableAudioData)
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

    private fun hasPlayableAudioData(format: JsonObject): Boolean {
        directUrl(format)?.let { return true }
        val cipher = cipherString(format) ?: return false
        val params = parseCipher(cipher) ?: return false
        params.getQueryParameter("url")?.takeIf { it.isNotBlank() } ?: return false
        return !params.getQueryParameter("sig").isNullOrBlank()
    }

    /**
     * Converte o formato em URL reproduzível. Só usamos `url` direta ou
     * `signatureCipher` que já traga `sig`; formatos com `s` cifrado são
     * ignorados porque o cliente VISIONOS devolve URLs diretas.
     */
    private fun playableUrl(format: JsonObject): String? {
        directUrl(format)?.let { return it }
        val rawCipher = cipherString(format) ?: return null
        val params = parseCipher(rawCipher) ?: return null
        val url = params.getQueryParameter("url")?.takeIf { it.isNotBlank() } ?: return null
        val signature = params.getQueryParameter("sig")
            ?: params.getQueryParameter("signature")
            ?: return null
        val param = params.getQueryParameter("sp")?.takeIf { it.isNotBlank() } ?: "signature"
        val separator = if (url.contains("?")) "&" else "?"
        return "$url$separator$param=$signature"
    }

    private fun directUrl(format: JsonObject): String? =
        (format["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") }

    private fun cipherString(format: JsonObject): String? =
        ((format["signatureCipher"] ?: format["cipher"]) as? JsonPrimitive)
            ?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun parseCipher(cipher: String): Uri? = try {
        Uri.parse("https://local.invalid/?$cipher")
    } catch (t: Throwable) {
        null
    }

    private fun requireSession() {
        if (session.youtubeCookies.isNullOrBlank()) {
            throw SessionExpiredException("Sessão do YouTube Music não está disponível")
        }
    }

    private fun browse(browseId: String, continuation: String? = null): JsonObject {
        val body = buildJsonObject {
            put("context", clientContext(WEB_REMIX))
            if (continuation.isNullOrBlank()) {
                put("browseId", browseId)
            } else {
                put("continuation", continuation)
            }
        }
        return try {
            post("browse", body, headers(WEB_REMIX))
        } catch (t: Throwable) {
            if (t.isHttpAuthFailure()) {
                throw SessionExpiredException(
                    "Sessão do YouTube Music expirou",
                    t,
                )
            }
            throw t
        }
    }

    private fun Throwable.isHttpAuthFailure(): Boolean =
        generateSequence(this) { it.cause }.any { failure ->
            val message = failure.message.orEmpty()
            message.startsWith("HTTP 401") || message.startsWith("HTTP 403")
        }

    private fun continuationToken(root: JsonObject): String? {
        val command = findFirstObject(root, "continuationCommand") ?: return null
        return (command["token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun collectLibraryPlaylists(
        root: JsonObject,
        found: MutableMap<String, YtPlaylist>,
        limit: Int,
    ) {
        walkObjects(root) { item ->
            playlistSummary(item)?.let { playlist ->
                if (found.size < limit && !found.containsKey(playlist.id)) {
                    found[playlist.id] = playlist
                }
            }
        }
    }

    private fun playlistSummary(item: JsonObject): YtPlaylist? {
        return when {
            item["musicTwoRowItemRenderer"] is JsonObject -> {
                val renderer = item["musicTwoRowItemRenderer"] as JsonObject
                val title = (renderer["title"] as? JsonObject)?.let(::runsText).orEmpty()
                val subtitle = (renderer["subtitle"] as? JsonObject)?.let(::runsText).orEmpty()
                buildPlaylistSummary(renderer, title, subtitle)
            }
            item["musicResponsiveListItemRenderer"] is JsonObject -> {
                val renderer = item["musicResponsiveListItemRenderer"] as JsonObject
                val columns = (renderer["flexColumns"] as? JsonArray)?.mapNotNull { column ->
                    (column as? JsonObject)
                        ?.get("musicResponsiveListItemFlexColumnRenderer")
                        ?.let { it as? JsonObject }
                        ?.get("text")
                        ?.let { it as? JsonObject }
                }.orEmpty()
                val title = columns.getOrNull(0)?.let(::runsText).orEmpty()
                val subtitle = columns.getOrNull(1)?.let(::runsText).orEmpty()
                buildPlaylistSummary(renderer, title, subtitle)
            }
            else -> null
        }
    }

    private fun buildPlaylistSummary(
        renderer: JsonObject,
        rawTitle: String,
        subtitle: String,
    ): YtPlaylist? {
        val title = rawTitle.trim()
        if (title.isBlank()) return null
        val browseId = directBrowseId(renderer)
            ?: findFirstObject(renderer, "browseEndpoint")
                ?.get("browseId")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (browseId.isNullOrBlank()) return null
        if (!browseId.startsWith("VL")) return null
        val normalizedTitle = title.lowercase()
        val liked = browseId == LIKED_PLAYLIST_ID ||
            normalizedTitle == "músicas curtidas" ||
            normalizedTitle == "liked music" ||
            normalizedTitle == "títulos curtidos"
        val id = if (liked) LIKED_PLAYLIST_ID else browseId
        return YtPlaylist(
            id = id,
            title = title,
            thumbnailUrl = extractThumbnail(renderer),
            trackCount = parseTrackCount(subtitle),
            liked = liked,
        )
    }

    private fun directBrowseId(renderer: JsonObject): String? =
        ((renderer["navigationEndpoint"] as? JsonObject)
            ?.get("browseEndpoint") as? JsonObject)
            ?.get("browseId")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }

    private fun parseTrackCount(raw: String): Int {
        if (raw.isBlank()) return 0
        val normalized = raw.replace(".", "").replace(",", "")
        return Regex("\\d{1,6}").find(normalized)?.value?.toIntOrNull() ?: 0
    }

    private fun walkObjects(element: JsonElement, visit: (JsonObject) -> Unit) {
        when (element) {
            is JsonObject -> {
                visit(element)
                element.values.forEach { walkObjects(it, visit) }
            }
            is JsonArray -> element.forEach { walkObjects(it, visit) }
            else -> Unit
        }
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
        for (attempt in 0 until 2) {
            val startedAt = System.currentTimeMillis()
            try {
                AppLog.d(
                    AppLog.TAG_TUBE,
                    "POST $baseUrl/$endpoint tentativa=${attempt + 1} " +
                        "cliente=${request.header("X-YouTube-Client-Name")} " +
                        "thread=${Thread.currentThread().name}",
                )
                http.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - startedAt
                    val text = response.body?.string().orEmpty()
                    AppLog.d(
                        AppLog.TAG_TUBE,
                        "Resposta $endpoint: HTTP ${response.code} em ${elapsed}ms " +
                            "(${AppLog.bytesForLog(text.length)})",
                    )
                    if (!response.isSuccessful) {
                        val error = MusicException("HTTP ${response.code}")
                        if (response.code in 400..499) {
                            AppLog.w(
                                AppLog.TAG_TUBE,
                                "Erro HTTP do cliente (não repete): ${response.code}",
                            )
                            throw error
                        }
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
                AppLog.w(
                    AppLog.TAG_TUBE,
                    "Falha em $endpoint tentativa=${attempt + 1}: ${AppLog.describe(t)}",
                )
                if (t is MusicException && t.message?.startsWith("HTTP 4") == true) break
            }
            if (attempt < 1) {
                try {
                    Thread.sleep(300L)
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw MusicException("Falha ao falar com o YouTube Music.", lastError)
    }

    /**
     * O VISIONOS só devolve URL direta quando a requisição carrega um visitor
     * válido. Buscamos o visitor da home do YouTube Music quando ainda não há
     * um (primeiro play, conta recém-vinculada ou visitor limpo no login).
     */
    @Synchronized
    private fun ensureVisitorData(): String? {
        session.youtubeVisitorData?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            val request = Request.Builder()
                .url("https://music.youtube.com/")
                .header("User-Agent", userAgent(VISIONOS))
                .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.w(AppLog.TAG_STREAM, "Falha obtendo visitor: HTTP ${response.code}")
                    return null
                }
                val html = response.body?.string().orEmpty()
                val visitor = VISITOR_DATA_REGEX.find(html)?.groupValues?.getOrNull(1)
                if (visitor.isNullOrBlank()) {
                    AppLog.w(AppLog.TAG_STREAM, "Home do YouTube Music sem visitorData")
                    return null
                }
                session.youtubeVisitorData = visitor
                AppLog.i(AppLog.TAG_STREAM, "VisitorData renovado (${visitor.length} caracteres)")
                visitor
            }
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_STREAM, "Falha renovando visitorData", t)
            null
        }
    }

    private fun headers(clientName: String): Headers {
        val origin = if (usesMusicEndpoint(clientName)) "https://music.youtube.com" else "https://www.youtube.com"
        val builder = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "application/json")
            .add("Origin", origin)
            .add("X-Origin", origin)
            .add("Referer", "$origin/")
            .add("X-Goog-Api-Format-Version", "1")
            .add("X-YouTube-Client-Name", clientId(clientName))
            .add("X-YouTube-Client-Version", clientVersion(clientName))
            .add("User-Agent", userAgent(clientName))
        session.youtubeVisitorData?.takeIf { it.isNotBlank() }?.let {
            builder.add("X-Goog-Visitor-Id", it)
        }
        // Só os clientes que declaram suporte a login recebem Cookie/Authorization.
        // O VISIONOS é anônimo por definição: mandar cookies nele faz o YouTube
        // tratar a requisição como sessão web e cair no bloqueio de bot.
        if (clientSupportsLogin(clientName)) {
            val cookies = session.youtubeCookies?.takeIf { it.isNotBlank() }
            if (clientUsesCookies(clientName) && cookies != null) {
                builder.add("Cookie", cookies)
            }
            val sapisid = session.youtubeSapisid?.takeIf { it.isNotBlank() }
            if (cookies != null || sapisid != null) {
                builder.add("X-Goog-AuthUser", "0")
            }
            if (sapisid != null) {
                val timestamp = System.currentTimeMillis() / 1000
                val hash = sha1Hex("$timestamp $sapisid $origin")
                builder.add("Authorization", "SAPISIDHASH ${timestamp}_$hash")
            }
        }
        return builder.build()
    }

    private fun clientSupportsLogin(clientName: String): Boolean = when (clientName) {
        TVHTML5, WEB_REMIX, WEB_EMBEDDED_PLAYER -> true
        else -> false
    }

    private fun clientUsesCookies(clientName: String): Boolean = when (clientName) {
        TVHTML5, WEB_REMIX, WEB_EMBEDDED_PLAYER -> true
        else -> false
    }

    private fun usesMusicEndpoint(clientName: String): Boolean = when (clientName) {
        WEB_REMIX, VISIONOS, ANDROID_VR -> true
        else -> false
    }

    private fun clientNeedsPlaybackContext(clientName: String): Boolean = when (clientName) {
        TVHTML5, WEB_REMIX, WEB_EMBEDDED_PLAYER -> true
        else -> false
    }

    private fun clientContext(clientName: String): JsonObject = buildJsonObject {
        put("client", buildJsonObject {
            put("clientName", clientName)
            put("clientVersion", clientVersion(clientName))
            put("hl", "pt")
            put("gl", "BR")
            when (clientName) {
                VISIONOS -> {
                    put("osName", "VISION_OS")
                    put("osVersion", "1.3")
                    put("deviceMake", "Apple")
                    put("deviceModel", "RealityDevice14,1")
                    put("platform", "MOBILE")
                    put("userAgent", userAgent(VISIONOS))
                }
                WEB_REMIX -> put("originalUrl", "https://music.youtube.com/")
                IOS -> {
                    put("deviceMake", "Apple")
                    put("deviceModel", "iPhone16,2")
                    put("osName", "iPhone")
                    put("osVersion", "18.3.2.22D82")
                }
                ANDROID_VR -> {
                    put("deviceMake", "Oculus")
                    put("deviceModel", "Quest 3")
                    put("androidSdkVersion", 32)
                    put("osName", "Android")
                    put("osVersion", "12L")
                }
                TVHTML5 -> put("userAgent", userAgent(TVHTML5))
                WEB_EMBEDDED_PLAYER -> {
                    put("userAgent", userAgent(WEB_EMBEDDED_PLAYER))
                    put("originalUrl", "https://www.youtube.com/")
                }
                else -> {
                    put("androidSdkVersion", 30)
                    put("osName", "Android")
                    put("osVersion", "11")
                }
            }
            session.youtubeVisitorData?.takeIf { it.isNotBlank() }?.let { put("visitorData", it) }
        })
        if (clientName == WEB_EMBEDDED_PLAYER) {
            put("thirdParty", buildJsonObject { put("embedUrl", "https://www.reddit.com/") })
        }
        put("user", buildJsonObject { put("lockedSafetyMode", false) })
    }

    private fun clientId(clientName: String): String = when (clientName) {
        VISIONOS -> "101"
        WEB_REMIX -> "67"
        TVHTML5 -> "7"
        WEB_EMBEDDED_PLAYER -> "56"
        ANDROID_MUSIC -> "21"
        IOS -> "5"
        ANDROID_VR -> "28"
        ANDROID -> "3"
        else -> "67"
    }

    private fun clientVersion(clientName: String): String = when (clientName) {
        VISIONOS -> "0.1"
        WEB_REMIX -> "1.20260707.12.00"
        TVHTML5 -> "7.20260707.07.00"
        WEB_EMBEDDED_PLAYER -> "2.20260708.00.00"
        ANDROID_MUSIC -> "6.42.52"
        IOS -> "21.26.4"
        ANDROID_VR -> "1.65.10"
        ANDROID -> "21.26.364"
        else -> "1.20260707.12.00"
    }

    private fun userAgent(clientName: String): String = when (clientName) {
        VISIONOS -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
        WEB_REMIX -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        TVHTML5 -> "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
        WEB_EMBEDDED_PLAYER -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"
        ANDROID_MUSIC -> "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 11) gzip"
        IOS -> "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
        ANDROID_VR -> "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        ANDROID -> "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"
        else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private data class CachedUrl(val info: StreamInfo, val expiresAt: Long)

    companion object {
        private const val BASE_URL = "https://music.youtube.com/youtubei/v1"
        private const val YOUTUBE_BASE_URL = "https://www.youtube.com/youtubei/v1"
        private const val VISIONOS = "VISIONOS"
        private const val WEB_REMIX = "WEB_REMIX"
        private const val TVHTML5 = "TVHTML5"
        private const val WEB_EMBEDDED_PLAYER = "WEB_EMBEDDED_PLAYER"
        private const val ANDROID_MUSIC = "ANDROID_MUSIC"
        private const val IOS = "IOS"
        private const val ANDROID_VR = "ANDROID_VR"
        private const val ANDROID = "ANDROID"
        private const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="
        private const val URL_TTL_MS = 30 * 60 * 1000L
        private const val LIKED_PLAYLIST_ID = "VLLM"
        private const val MAX_LIBRARY_PAGES = 8
        private const val MAX_HISTORY_PAGES = 4
        private const val MAX_HOME_PAGES = 3
        private const val MAX_PLAYLIST_PAGES = 12
        private val VISITOR_DATA_REGEX =
            Regex("\"(?:VISITOR_DATA|visitorData)\"\\s*:\\s*\"([^\"]+)\"")
        private val CLIENTS_WITH_COOKIES = listOf(
            VISIONOS,
            ANDROID_VR,
            IOS,
            WEB_REMIX,
            WEB_EMBEDDED_PLAYER,
            TVHTML5,
        )
        private val CLIENTS_GUEST = listOf(
            VISIONOS,
            ANDROID_VR,
            IOS,
            WEB_REMIX,
            WEB_EMBEDDED_PLAYER,
            ANDROID_MUSIC,
        )
    }
}
