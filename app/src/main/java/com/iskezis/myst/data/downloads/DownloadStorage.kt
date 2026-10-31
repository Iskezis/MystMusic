package com.iskezis.myst.data.downloads

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.iskezis.myst.data.account.AutoAccountBackup
import com.iskezis.myst.util.AppLog
import java.io.File

/**
 * Resolve o diretório dos downloads.
 *
 * O pedido do produto é guardar em `~/sdcard/MystMusic/MystDownload`, visível
 * no gerenciador de arquivos. Isso só é possível com acesso a todos os
 * arquivos (Android 11+) ou WRITE_EXTERNAL_STORAGE (Android 6–10). Sem essa
 * permissão o app NÃO pode falhar: usa a pasta externa privada equivalente e
 * a tela Armazenamento oferece a permissão. Caminhos antigos continuam sendo
 * encontrados, então conceder a permissão depois não perde downloads.
 */
class DownloadStorage(private val context: Context) {

    private val publicRoot: File
        get() = File(Environment.getExternalStorageDirectory(), PUBLIC_RELATIVE)

    private val privateRoot: File
        get() {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(context.filesDir, "external")
            return File(base, PUBLIC_RELATIVE)
        }

    val root: File
        get() {
            if (AutoAccountBackup.hasExactAccess(context)) {
                val exact = publicRoot
                if (ensure(exact)) return exact
                AppLog.w(
                    AppLog.TAG_DB,
                    "Pasta pública de downloads indisponível; usando a privada",
                )
            }
            val fallback = privateRoot
            ensure(fallback)
            return fallback
        }

    fun prepare(): File {
        val current = root
        ensure(File(current, COVERS_DIRECTORY))
        AppLog.i(AppLog.TAG_DB, "Pasta de downloads: ${current.absolutePath}")
        return current
    }

    fun displayPath(): String = root.absolutePath

    fun hasPublicAccess(): Boolean = AutoAccountBackup.hasExactAccess(context)

    fun audioFile(videoId: String): File = File(root, "${safeId(videoId)}.$AUDIO_EXTENSION")

    fun partFile(videoId: String): File =
        File(root, "${safeId(videoId)}.$AUDIO_EXTENSION.part")

    fun coverFile(videoId: String): File = File(
        File(root, COVERS_DIRECTORY),
        "${safeId(videoId)}.$COVER_EXTENSION",
    )

    fun transferFile(videoId: String): File =
        File(root, ".${safeId(videoId)}.transfer")

    /**
     * Procura um arquivo já concluído. `storedPath` vem do banco e pode apontar
     * para a pasta privada usada antes de o usuário conceder acesso público.
     */
    fun findAudio(videoId: String, storedPath: String? = null): File? {
        val candidates = buildList {
            storedPath?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            add(audioFile(videoId))
            add(File(publicRoot, "${safeId(videoId)}.$AUDIO_EXTENSION"))
            add(File(privateRoot, "${safeId(videoId)}.$AUDIO_EXTENSION"))
            add(File(File(context.filesDir, "offline"), "${safeId(videoId)}.$AUDIO_EXTENSION"))
        }
        return candidates.firstOrNull { it.isFile && it.length() > 0L }
    }

    fun findCover(videoId: String, storedPath: String? = null): File? {
        val candidates = buildList {
            storedPath?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            add(coverFile(videoId))
            add(File(File(publicRoot, COVERS_DIRECTORY), "${safeId(videoId)}.$COVER_EXTENSION"))
            add(File(File(privateRoot, COVERS_DIRECTORY), "${safeId(videoId)}.$COVER_EXTENSION"))
        }
        return candidates.firstOrNull { it.isFile && it.length() > 0L }
    }

    fun availableBytes(): Long = runCatching {
        val stat = StatFs(root.absolutePath)
        stat.availableBytes
    }.getOrDefault(0L)

    fun isInsideRoot(file: File): Boolean = runCatching {
        val base = root.canonicalFile
        val target = file.canonicalFile
        target.path.startsWith(base.path + File.separator)
    }.getOrDefault(false)

    fun deleteAudio(videoId: String, path: String?) {
        runCatching {
            path?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
            audioFile(videoId).delete()
            partFile(videoId).delete()
        }.onFailure {
            AppLog.failure(AppLog.TAG_DB, "Falha removendo áudio baixado $videoId", it)
        }
    }

    fun deleteCover(videoId: String, path: String?) {
        runCatching {
            path?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
            coverFile(videoId).delete()
        }.onFailure {
            AppLog.failure(AppLog.TAG_DB, "Falha removendo capa baixada $videoId", it)
        }
    }

    fun deleteTransfer(videoId: String) {
        runCatching { transferFile(videoId).delete() }
    }

    private fun ensure(directory: File): Boolean = runCatching {
        directory.isDirectory || directory.mkdirs()
    }.getOrDefault(false)

    private fun safeId(videoId: String): String =
        videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifBlank { "track" }

    companion object {
        private const val PUBLIC_RELATIVE = "MystMusic/MystDownload"
        private const val COVERS_DIRECTORY = "covers"
        const val AUDIO_EXTENSION = "m4a"
        const val COVER_EXTENSION = "jpg"
    }
}

/** Formata bytes para textos de armazenamento, sem depender de locale. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (value >= 100.0 || index < 1) {
        String.format(java.util.Locale.US, "%.0f %s", value, units[index])
    } else {
        String.format(java.util.Locale.US, "%.1f %s", value, units[index])
    }
}

/**
 * Estimativa usada nos diálogos de download. O stream AAC do YouTube Music
 * costuma ficar perto de 128 kbps; a capa soma poucos KB.
 */
fun estimateDownloadBytes(tracks: Collection<com.iskezis.myst.data.youtube.Track>): Long {
    val audio = tracks.sumOf { track ->
        val seconds = when {
            track.durationMs > 0L -> track.durationMs / 1000L
            else -> DEFAULT_TRACK_SECONDS
        }
        seconds * ESTIMATED_BYTES_PER_SECOND
    }
    return audio + tracks.size * ESTIMATED_COVER_BYTES
}

private const val ESTIMATED_BYTES_PER_SECOND = 16_000L
private const val DEFAULT_TRACK_SECONDS = 210L
private const val ESTIMATED_COVER_BYTES = 40_000L
