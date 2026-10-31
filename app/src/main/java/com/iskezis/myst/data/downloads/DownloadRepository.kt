package com.iskezis.myst.data.downloads

import com.iskezis.myst.data.db.AppDatabase
import com.iskezis.myst.data.db.DownloadEntity
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.flow.Flow
import java.io.File

object DownloadStatus {
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
}

/** Tempo sem progresso real que caracteriza um download travado. */
const val DOWNLOAD_STALL_TIMEOUT_MS = 60_000L

data class EnqueueResult(
    val added: Int,
    val alreadyDownloaded: Int,
    val skipped: Int,
)

/**
 * Fonte única dos downloads da conta Myst. A fila vive no Room; o
 * DownloadService só reivindica linhas QUEUED. Isso sobrevive ao processo ser
 * encerrado e mantém a UI consistente sem guardar estado em memória.
 */
class DownloadRepository(
    private val database: AppDatabase,
    private val storage: DownloadStorage,
) {

    private val dao get() = database.downloadDao()

    fun observe(userId: Long): Flow<List<DownloadEntity>> = dao.observe(userId)

    /** Índice global usado pelos selos de download e pela reprodução offline. */
    fun observeAll(): Flow<List<DownloadEntity>> = dao.observeAll()

    fun observeDone(userId: Long): Flow<List<DownloadEntity>> =
        dao.observeByStatus(userId, DownloadStatus.DONE)

    fun observeOne(userId: Long, videoId: String): Flow<DownloadEntity?> =
        dao.observeOne(userId, videoId)

    suspend fun find(userId: Long, videoId: String): DownloadEntity? =
        dao.find(userId, videoId)

    suspend fun all(userId: Long): List<DownloadEntity> = dao.all(userId)

    suspend fun done(userId: Long): List<DownloadEntity> =
        dao.byStatus(userId, DownloadStatus.DONE)

    suspend fun usedBytes(userId: Long): Long = dao.usedBytes(userId)

    suspend fun doneCount(userId: Long): Int = dao.doneCount(userId)

    suspend fun activeCount(userId: Long): Int = dao.activeCount(userId)

    /** Arquivo local de uma faixa DONE, mesmo se o usuário mudou de pasta. */
    fun localFile(videoId: String, storedPath: String? = null): File? =
        storage.findAudio(videoId, storedPath)

    fun localCover(videoId: String, storedPath: String? = null): File? =
        storage.findCover(videoId, storedPath)

    suspend fun enqueue(userId: Long, tracks: List<Track>): EnqueueResult {
        if (userId <= 0L) return EnqueueResult(0, 0, tracks.size)
        val now = System.currentTimeMillis()
        var added = 0
        var downloaded = 0
        var skipped = 0
        tracks.distinctBy { it.videoId }.forEach { track ->
            if (track.videoId.isBlank()) {
                skipped++
                return@forEach
            }
            val existing = dao.find(userId, track.videoId)
            when (existing?.status) {
                DownloadStatus.DONE -> downloaded++
                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> skipped++
                else -> {
                    if (existing == null) {
                        dao.upsert(
                            DownloadEntity(
                                userId = userId,
                                videoId = track.videoId,
                                title = track.title,
                                artist = track.artist,
                                album = track.album,
                                thumbnailUrl = track.thumbnailUrl,
                                durationMs = track.durationMs,
                                status = DownloadStatus.QUEUED,
                                artistId = track.artistId,
                                albumId = track.albumId,
                                addedAt = now,
                                updatedAt = now,
                            ),
                        )
                    } else {
                        dao.requeue(
                            userId = userId,
                            videoId = track.videoId,
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
                            thumbnailUrl = track.thumbnailUrl,
                            durationMs = track.durationMs,
                            artistId = track.artistId,
                            albumId = track.albumId,
                            addedAt = now,
                        )
                    }
                    added++
                }
            }
        }
        AppLog.i(
            AppLog.TAG_DB,
            "Downloads enfileirados: novos=$added jáBaixados=$downloaded ignorados=$skipped",
        )
        return EnqueueResult(added, downloaded, skipped)
    }

    /**
     * A TV também usa esta tabela (em memória). Ao iniciar o serviço, linhas
     * que ficaram DOWNLOADING de um processo morto voltam para a fila.
     */
    suspend fun resetInterrupted() = dao.resetInterrupted(System.currentTimeMillis())

    /**
     * Recupera downloads que ficaram DOWNLOADING sem atualizar o progresso.
     * [exclude] protege faixas que ainda pertencem a um worker ativo: quem
     * devolve essas linhas à fila é o próprio worker, evitando que dois
     * workers escrevam no mesmo arquivo `.part`.
     */
    suspend fun recoverStalled(
        userId: Long,
        stallMs: Long = DOWNLOAD_STALL_TIMEOUT_MS,
        exclude: Set<String> = emptySet(),
    ): Int {
        if (userId <= 0L) return 0
        val now = System.currentTimeMillis()
        val recovered = dao.recoverStalled(
            userId,
            now - stallMs,
            now,
            if (exclude.isEmpty()) listOf("") else exclude.toList(),
        )
        if (recovered > 0) {
            AppLog.i(AppLog.TAG_DOWNLOAD, "$recovered download(s) parado(s) devolvido(s) à fila")
        }
        return recovered
    }

    /** Reenfileira uma faixa específica sem apagar o arquivo parcial. */
    suspend fun requeueOne(item: DownloadEntity) {
        if (item.userId <= 0L) return
        dao.requeueOne(item.userId, item.videoId, System.currentTimeMillis())
    }

    suspend fun stalledCount(userId: Long, stallMs: Long = DOWNLOAD_STALL_TIMEOUT_MS): Int {
        if (userId <= 0L) return 0
        return dao.stalledCount(userId, System.currentTimeMillis() - stallMs)
    }

    suspend fun queued(userId: Long): List<DownloadEntity> = dao.queued(userId)

    suspend fun claim(item: DownloadEntity): Boolean {
        if (item.userId <= 0L) return false
        return dao.markDownloading(
            item.userId,
            item.videoId,
            System.currentTimeMillis(),
        ) == 1
    }

    suspend fun progress(item: DownloadEntity, bytes: Long, totalBytes: Long) {
        dao.progress(
            item.userId,
            item.videoId,
            bytes.coerceAtLeast(0L),
            totalBytes.coerceAtLeast(0L),
            System.currentTimeMillis(),
        )
    }

    suspend fun complete(
        item: DownloadEntity,
        audio: File,
        cover: File?,
        totalBytes: Long,
    ) {
        val current = dao.find(item.userId, item.videoId)
        if (current == null || current.status != DownloadStatus.DOWNLOADING) {
            // Removido/cancelado durante a escrita: não recria a linha nem
            // deixa o arquivo órfão.
            runCatching { audio.delete() }
            runCatching { cover?.delete() }
            AppLog.w(
                AppLog.TAG_DB,
                "Download ${item.videoId} terminou depois de ser removido; arquivo descartado",
            )
            return
        }
        val updated = dao.markDone(
            userId = item.userId,
            videoId = item.videoId,
            bytes = audio.length(),
            totalBytes = if (totalBytes > 0L) totalBytes else audio.length(),
            path = audio.absolutePath,
            coverPath = cover?.absolutePath,
            now = System.currentTimeMillis(),
        )
        if (updated == 0) {
            // A linha desapareceu entre a conferência e o UPDATE.
            runCatching { audio.delete() }
            runCatching { cover?.delete() }
            AppLog.w(
                AppLog.TAG_DB,
                "Download ${item.videoId} removido durante a conclusão; arquivo descartado",
            )
            return
        }
        AppLog.i(
            AppLog.TAG_DB,
            "Download concluído: ${item.videoId} (${formatBytes(audio.length())})",
        )
    }

    suspend fun fail(item: DownloadEntity, reason: String) {
        dao.markFailed(
            item.userId,
            item.videoId,
            reason.take(240),
            System.currentTimeMillis(),
        )
        AppLog.w(AppLog.TAG_DB, "Download falhou: ${item.videoId}: $reason")
    }

    suspend fun cancelAll(userId: Long, reason: String = "Cancelado pelo usuário") {
        if (userId <= 0L) return
        dao.cancelActive(userId, reason, System.currentTimeMillis())
    }

    /**
     * Remove um download. Só apaga o arquivo físico quando nenhuma outra conta
     * Myst o referencia; uma playlist removida nunca apaga arquivo em uso.
     */
    suspend fun remove(userId: Long, videoId: String) {
        val item = dao.find(userId, videoId)
        if (item == null) {
            // A linha pode ter sido removida pelo serviço enquanto a UI
            // confirmava. Só apaga o arquivo se nenhuma outra conta o usa.
            val existing = storage.findAudio(videoId, null)
            if (existing == null || dao.referencesToPath(existing.absolutePath) == 0) {
                storage.deleteAudio(videoId, existing?.absolutePath)
            }
            storage.deleteTransfer(videoId)
            return
        }
        dao.delete(userId, videoId)
        removeFileIfUnused(item)
    }

    suspend fun removeAll(userId: Long) {
        val items = dao.all(userId)
        dao.deleteAll(userId)
        items.forEach { removeFileIfUnused(it) }
    }

    /** Remove somente as linhas, mantendo arquivos que outra conta usa. */
    private suspend fun removeFileIfUnused(item: DownloadEntity) {
        val path = item.path
        val stillUsed = path != null && dao.referencesToPath(path) > 0
        if (!stillUsed) {
            storage.deleteAudio(item.videoId, item.path)
            storage.deleteCover(item.videoId, item.coverPath)
            storage.deleteTransfer(item.videoId)
        }
    }

    /**
     * Importa um download recebido da TV. O arquivo já foi escrito no
     * armazenamento do celular; aqui só registramos o caminho final.
     */
    suspend fun importTransferred(
        userId: Long,
        remote: DownloadEntity,
        audio: File,
        cover: File?,
    ) {
        if (userId <= 0L || !audio.isFile) return
        val now = System.currentTimeMillis()
        dao.upsert(
            remote.copy(
                userId = userId,
                status = DownloadStatus.DONE,
                bytes = audio.length(),
                totalBytes = remote.totalBytes.coerceAtLeast(audio.length()),
                path = audio.absolutePath,
                coverPath = cover?.absolutePath,
                error = null,
                updatedAt = now,
            ),
        )
        AppLog.i(
            AppLog.TAG_DB,
            "Download recebido da TV: ${remote.videoId} (${formatBytes(audio.length())})",
        )
    }

    fun trackFor(item: DownloadEntity): Track = Track(
        videoId = item.videoId,
        title = item.title,
        artist = item.artist,
        thumbnailUrl = localCover(item.videoId, item.coverPath)?.let { cover ->
            android.net.Uri.fromFile(cover).toString()
        } ?: item.thumbnailUrl,
        durationMs = item.durationMs,
        album = item.album,
        artistId = item.artistId,
        albumId = item.albumId,
    )
}
