package com.iskezis.myst.data.update

import android.content.Context
import androidx.annotation.StringRes
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.R
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Release encontrada no GitHub e compatível com este flavor. */
data class AppRelease(
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val assetUrl: String,
    val assetSize: Long,
    val checksumUrl: String?,
)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val release: AppRelease) : UpdateCheckResult
    data class Failure(@StringRes val messageRes: Int) : UpdateCheckResult
}

class UpdateException(
    @StringRes val messageRes: Int,
    cause: Throwable? = null,
) : IOException(cause)

/**
 * Consulta a API pública do GitHub e baixa o APK da release mais recente.
 *
 * O token nunca é embutido no APK: o repositório é público e a API aceita
 * requisições anônimas. O download só é aceito de URLs do próprio GitHub e,
 * quando a release publica SHA256SUMS.txt, a integridade é verificada antes de
 * entregar o arquivo ao instalador.
 */
class GitHubUpdateRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var activeDownload: Call? = null

    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val repo = BuildConfig.GITHUB_REPO.trim().trim('/')
        if (!REPO_PATTERN.matches(repo)) {
            AppLog.w(AppLog.TAG_UPDATE, "GITHUB_REPO inválido: '$repo'")
            return@withContext UpdateCheckResult.Failure(R.string.update_unavailable)
        }
        val requestBuilder = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases?per_page=10")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", userAgent())
        prefs.getString(KEY_ETAG, null)?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.header("If-None-Match", it)
        }

        try {
            apiClient.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.code == 304 -> {
                        val cached = prefs.getString(KEY_BODY, null)
                        if (cached.isNullOrBlank()) {
                            AppLog.w(AppLog.TAG_UPDATE, "GitHub respondeu 304 sem cache local")
                            UpdateCheckResult.Failure(R.string.update_error_network)
                        } else {
                            parseReleases(cached, repo)
                        }
                    }

                    !response.isSuccessful -> {
                        AppLog.w(
                            AppLog.TAG_UPDATE,
                            "GitHub respondeu HTTP ${response.code} ao verificar atualizações",
                        )
                        val message = if (response.code == 403 || response.code == 429) {
                            R.string.update_error_rate_limit
                        } else {
                            R.string.update_error_network
                        }
                        UpdateCheckResult.Failure(message)
                    }

                    else -> {
                        val body = response.body?.string()
                        if (body.isNullOrBlank()) {
                            UpdateCheckResult.Failure(R.string.update_error_network)
                        } else {
                            prefs.edit()
                                .putString(KEY_ETAG, response.header("ETag"))
                                .putString(KEY_BODY, body)
                                .apply()
                            parseReleases(body, repo)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLog.failure(AppLog.TAG_UPDATE, "Falha verificando atualizações", t)
            UpdateCheckResult.Failure(
                if (t is UpdateException) t.messageRes else R.string.update_error_network,
            )
        }
    }

    /**
     * Baixa o APK para cacheDir/updates. O callback de progresso é chamado na
     * thread de IO; a UI publica o valor via StateFlow.
     */
    suspend fun download(
        release: AppRelease,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (!isAllowedAssetUrl(release.assetUrl)) {
            throw UpdateException(R.string.update_error_asset)
        }
        val dir = File(appContext.cacheDir, UPDATE_DIR).apply { mkdirs() }
        cleanup(dir)
        val target = File(dir, "app-${release.versionCode}.apk")
        val part = File(dir, ".app-${release.versionCode}.apk.part")
        part.delete()
        target.delete()

        val call = downloadClient.newCall(
            Request.Builder()
                .url(release.assetUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", userAgent())
                .build(),
        )
        activeDownload = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateException(R.string.update_error_download)
                }
                val finalUrl = response.request.url
                if (!isAllowedDownloadHost(finalUrl.host)) {
                    AppLog.w(
                        AppLog.TAG_UPDATE,
                        "Download redirecionado para host não permitido: ${finalUrl.host}",
                    )
                    throw UpdateException(R.string.update_error_asset)
                }
                val body = response.body ?: throw UpdateException(R.string.update_error_download)
                val expectedLength = body.contentLength().takeIf { it > 0 }
                val total = expectedLength ?: release.assetSize
                var downloaded = 0L
                var lastNotified = 0L

                part.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastNotified >= PROGRESS_INTERVAL_MS ||
                                (expectedLength != null && downloaded >= expectedLength)
                            ) {
                                lastNotified = now
                                onProgress(downloaded, total)
                            }
                        }
                    }
                    output.flush()
                }

                if (expectedLength != null && downloaded != expectedLength) {
                    throw UpdateException(R.string.update_error_download)
                }

                release.checksumUrl?.let { checksumUrl ->
                    val assetName = release.assetUrl.toHttpUrlOrNull()
                        ?.pathSegments
                        ?.lastOrNull()
                        .orEmpty()
                    verifyChecksum(part, checksumUrl, assetName)
                }

                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                onProgress(target.length(), target.length())
                target
            }
        } catch (t: Throwable) {
            part.delete()
            if (t is CancellationException) throw t
            if (t is UpdateException) throw t
            AppLog.failure(AppLog.TAG_UPDATE, "Falha baixando atualização", t)
            throw UpdateException(R.string.update_error_download, t)
        } finally {
            activeDownload = null
        }
    }

    fun cancelDownload() {
        activeDownload?.cancel()
    }

    private fun parseReleases(body: String, repo: String): UpdateCheckResult {
        val releases = try {
            json.decodeFromString<List<GitHubRelease>>(body)
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_UPDATE, "JSON de releases inválido", t)
            return UpdateCheckResult.Failure(R.string.update_error_parse)
        }
        val candidates = releases.asSequence()
            .filterNot { it.draft }
            .mapNotNull { release ->
                val code = versionCodeOf(release) ?: return@mapNotNull null
                if (code > BuildConfig.VERSION_CODE) release to code else null
            }
            .toList()
        val chosen = candidates.maxByOrNull { it.second }
            ?: return UpdateCheckResult.UpToDate
        val (release, remoteCode) = chosen

        val assetName = if (BuildConfig.FLAVOR == "tv") "app-tv.apk" else "app.apk"
        val asset = release.assets.firstOrNull { it.name == assetName }
            ?: run {
                AppLog.w(
                    AppLog.TAG_UPDATE,
                    "Release ${release.tagName} não tem o asset $assetName",
                )
                return UpdateCheckResult.Failure(R.string.update_error_asset)
            }
        if (!isAllowedAssetUrl(asset.downloadUrl)) {
            AppLog.w(AppLog.TAG_UPDATE, "URL de asset recusada: ${asset.downloadUrl}")
            return UpdateCheckResult.Failure(R.string.update_error_asset)
        }
        val checksum = release.assets.firstOrNull { it.name == CHECKSUM_ASSET }
            ?.downloadUrl
            ?.takeIf { isAllowedAssetUrl(it) }

        val versionName = release.tagName
            .trim()
            .removePrefix("v")
            .ifBlank {
                release.name.trim().removePrefix("v").ifBlank { remoteCode.toString() }
            }
            .replace(Regex("(?i)-beta$"), " Beta")
            .replace(Regex("(?i)-alpha$"), " Alpha")
        return UpdateCheckResult.Available(
            AppRelease(
                versionName = versionName,
                versionCode = remoteCode,
                notes = cleanNotes(release.body),
                assetUrl = asset.downloadUrl,
                assetSize = asset.size.coerceAtLeast(0L),
                checksumUrl = checksum,
            ),
        )
    }

    private fun versionCodeOf(release: GitHubRelease): Int? {
        val declared = VERSION_CODE_PATTERN.find(release.body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (declared != null && declared > 0) return declared

        // Releases antigas podem não ter a linha versionCode. Nesse caso só é
        // seguro atualizar quando o núcleo SemVer remoto é maior que o local.
        val remote = SEMVER_PATTERN.find(release.tagName) ?: return null
        val local = SEMVER_PATTERN.find(BuildConfig.VERSION_NAME) ?: return null
        val remoteParts = remote.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
        val localParts = local.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
        if (remoteParts.size != 3 || localParts.size != 3) return null
        for (index in 0..2) {
            if (remoteParts[index] > localParts[index]) return BuildConfig.VERSION_CODE + 1
            if (remoteParts[index] < localParts[index]) return null
        }
        return null
    }

    private suspend fun verifyChecksum(file: File, checksumUrl: String, assetName: String) {
        if (!isAllowedAssetUrl(checksumUrl)) {
            throw UpdateException(R.string.update_error_checksum)
        }
        val text = try {
            apiClient.newCall(
                Request.Builder()
                    .url(checksumUrl)
                    .header("User-Agent", userAgent())
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) throw UpdateException(R.string.update_error_checksum)
                response.body?.string()
                    ?: throw UpdateException(R.string.update_error_checksum)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (t is UpdateException) throw t
            AppLog.failure(AppLog.TAG_UPDATE, "Falha obtendo SHA256SUMS.txt", t)
            throw UpdateException(R.string.update_error_checksum, t)
        }
        val expected = text.lineSequence()
            .map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == assetName }
            ?.first()
            ?.lowercase(Locale.US)
            ?: return
        if (!expected.matches(Regex("[0-9a-f]{64}"))) {
            throw UpdateException(R.string.update_error_checksum)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            throw UpdateException(R.string.update_error_checksum)
        }
    }

    private fun cleanup(dir: File) {
        val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS
        dir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".part") || file.lastModified() < cutoff)) {
                file.delete()
            }
        }
    }

    private fun cleanNotes(body: String): String = body
        .lineSequence()
        .filterNot { VERSION_CODE_LINE.matches(it.trim()) }
        .joinToString("\n")
        .trim()
        .take(MAX_NOTES_CHARS)

    private fun userAgent(): String =
        "MystMusic/${BuildConfig.VERSION_NAME} (Android; ${BuildConfig.FLAVOR})"

    private fun isAllowedDownloadHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.US)
        return normalized == "github.com" ||
            normalized == "objects.githubusercontent.com" ||
            normalized == "release-assets.githubusercontent.com" ||
            normalized.endsWith(".githubusercontent.com")
    }

    private fun isAllowedAssetUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https") return false
        val repo = BuildConfig.GITHUB_REPO.trim().trim('/')
        if (parsed.host.equals("github.com", ignoreCase = true)) {
            return parsed.encodedPath.startsWith("/$repo/releases/download/")
        }
        return isAllowedDownloadHost(parsed.host)
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String = "",
        val draft: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String = "",
        @SerialName("browser_download_url") val downloadUrl: String = "",
        val size: Long = 0L,
    )

    companion object {
        private const val PREFS = "myst_update_cache"
        private const val KEY_ETAG = "etag"
        private const val KEY_BODY = "body"
        private const val UPDATE_DIR = "updates"
        private const val CHECKSUM_ASSET = "SHA256SUMS.txt"
        private const val DOWNLOAD_BUFFER = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 150L
        private const val MAX_NOTES_CHARS = 8_000
        private const val CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private val REPO_PATTERN = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        private val VERSION_CODE_PATTERN = Regex(
            pattern = "(?im)^\\s*versionCode\\s*:\\s*(\\d+)\\s*$",
        )
        private val VERSION_CODE_LINE = Regex("^(?i)versionCode\\s*:")
        private val SEMVER_PATTERN = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)")
    }
}
