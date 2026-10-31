package com.iskezis.myst.data.downloads

import com.iskezis.myst.MystApp
import com.iskezis.myst.data.db.DownloadEntity
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Índice em memória de todos os downloads. Um único observador do Room
 * alimenta os selos das listas e a resolução offline do player, evitando uma
 * consulta por linha visível.
 *
 * O mapa é global (todas as contas) porque o mesmo arquivo pode ser
 * compartilhado; as consultas filtram pela conta Myst ativa no momento.
 */
class DownloadIndex(private val app: MystApp) {

    private val _items = MutableStateFlow<Map<String, DownloadEntity>>(emptyMap())
    val items: StateFlow<Map<String, DownloadEntity>> = _items.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = app.backgroundScope.launch {
            try {
                app.downloads.observeAll().collect { list ->
                    _items.value = list.associateBy { key(it.userId, it.videoId) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DB, "Falha observando o índice de downloads", t)
            }
        }
    }

    fun status(videoId: String): DownloadEntity? {
        if (videoId.isBlank()) return null
        val userId = app.session.mystUserId
        if (userId <= 0L) return null
        return _items.value[key(userId, videoId)]
    }

    /** Arquivo local de uma faixa DONE da conta ativa, se existir. */
    fun localFile(videoId: String): File? {
        val item = status(videoId) ?: return null
        if (item.status != DownloadStatus.DONE) return null
        return app.downloads.localFile(videoId, item.path)
    }

    /** Capa local da faixa baixada, usada para continuar bonita sem rede. */
    fun localCover(videoId: String): File? {
        val item = status(videoId) ?: return null
        if (item.status != DownloadStatus.DONE) return null
        return app.downloads.localCover(videoId, item.coverPath)
    }

    private fun key(userId: Long, videoId: String): String = "$userId:$videoId"
}
