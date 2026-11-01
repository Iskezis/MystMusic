package com.iskezis.myst.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.pm.PackageInfoCompat
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.R
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
    val isBeta: Boolean = false,
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

    suspend fun check(channel: UpdateChannel): UpdateCheckResult = withContext(Dispatchers.IO) {
        val repo = BuildConfig.GITHUB_REPO.trim().trim('/')
        if (!REPO_PATTERN.matches(repo)) {
            AppLog.w(AppLog.TAG_UPDATE, "GITHUB_REPO inválido: '$repo'")
            return@withContext UpdateCheckResult.Failure(R.string.update_unavailable)
        }
        val requestBuilder = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases?per_page=30")
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
                            parseReleases(cached, channel)
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
                            parseReleases(body, channel)
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
                // O tamanho informado pela API é a referência do asset. Alguns
                // proxies devolvem uma página HTML com HTTP 200 e Content-Length
                // próprio; sem esta conferência o arquivo inválido chegaria ao
                // instalador do Android como "erro ao analisar o pacote".
                if (release.assetSize > 0L && downloaded != release.assetSize) {
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
                validateDownloadedApk(target)
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

    private fun parseReleases(body: String, channel: UpdateChannel): UpdateCheckResult {
        val releases = try {
            UpdateReleaseParser.parse(body)
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_UPDATE, "JSON de releases inválido", t)
            return UpdateCheckResult.Failure(R.string.update_error_parse)
        }
        val assetName = if (BuildConfig.FLAVOR == "tv") "app-tv.apk" else "app.apk"
        val picked = UpdatePicker.pick(
            channel = channel,
            installedCode = BuildConfig.VERSION_CODE,
            installedVersionName = BuildConfig.VERSION_NAME,
            releases = releases,
            assetName = assetName,
            isAllowedAssetUrl = ::isAllowedAssetUrl,
        ) ?: return UpdateCheckResult.UpToDate

        return UpdateCheckResult.Available(
            AppRelease(
                versionName = UpdatePicker.displayVersion(
                    tagName = picked.release.tagName,
                    releaseName = picked.release.name,
                    versionCode = picked.versionCode,
                ),
                versionCode = picked.versionCode,
                notes = cleanNotes(picked.release.body),
                assetUrl = picked.asset.downloadUrl,
                assetSize = picked.asset.size.coerceAtLeast(0L),
                checksumUrl = picked.checksumUrl,
                isBeta = picked.isBeta,
            ),
        )
    }

    /** Limpa o cache condicional para uma troca de canal valer na hora. */
    fun clearCache() {
        prefs.edit().remove(KEY_ETAG).remove(KEY_BODY).apply()
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
            ?: throw UpdateException(R.string.update_error_checksum)
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

    /**
     * Confere que o arquivo baixado é mesmo um APK do MystMusic mais novo que
     * a versão instalada. O instalador do Android só recebe arquivos que
     * passaram por aqui, evitando o erro genérico de "pacote inválido" quando
     * o download foi truncado ou o servidor devolveu outra coisa.
     */
    @Suppress("DEPRECATION")
    private fun validateDownloadedApk(file: File) {
        try {
            if (!file.isFile || file.length() <= 0L) {
                throw UpdateException(R.string.update_error_invalid_apk)
            }
            val header = ByteArray(4)
            val read = file.inputStream().use { it.read(header) }
            if (read != header.size || !header.contentEquals(ZIP_MAGIC)) {
                throw UpdateException(R.string.update_error_invalid_apk)
            }
            val manager = appContext.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getPackageArchiveInfo(
                    file.absolutePath,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                manager.getPackageArchiveInfo(file.absolutePath, 0)
            }
            if (info == null || info.packageName != appContext.packageName) {
                throw UpdateException(R.string.update_error_invalid_apk)
            }
            if (PackageInfoCompat.getLongVersionCode(info) <= BuildConfig.VERSION_CODE.toLong()) {
                throw UpdateException(R.string.update_error_invalid_apk)
            }
        } catch (t: Throwable) {
            file.delete()
            if (t is UpdateException) throw t
            AppLog.failure(AppLog.TAG_UPDATE, "APK baixado inválido", t)
            throw UpdateException(R.string.update_error_invalid_apk, t)
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

    companion object {
        private const val PREFS = "myst_update_cache"
        private const val KEY_ETAG = "etag"
        private const val KEY_BODY = "body"
        private const val UPDATE_DIR = "updates"
        private const val DOWNLOAD_BUFFER = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 150L
        private const val MAX_NOTES_CHARS = 8_000
        private const val CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        private val REPO_PATTERN = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        private val VERSION_CODE_LINE = Regex("^(?i)versionCode\\s*:")
    }
}
