package com.iskezis.myst.ui.common

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.downloads.DownloadService
import com.iskezis.myst.data.downloads.EnqueueResult
import com.iskezis.myst.data.downloads.formatBytes
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fluxo comum de download usado pelo menu de faixa, pelos cabeçalhos de
 * álbum/playlist/artista e pela tela Baixadas.
 *
 * Centraliza o aviso de uso pessoal (uma única vez), a estimativa de espaço, o
 * diálogo de confirmação e o Snackbar com o resultado. Nenhuma tela deve
 * chamar o serviço diretamente.
 */
object DownloadActions {

    private const val MIN_FREE_AFTER_DOWNLOAD = 50L * 1024L * 1024L
    private const val FALLBACK_TRACK_BYTES = 3_500_000L
    private const val BYTES_PER_SECOND = 16_000L

    fun estimateBytes(tracks: List<Track>): Long = tracks.sumOf { track ->
        if (track.durationMs > 0L) {
            (track.durationMs / 1_000L) * BYTES_PER_SECOND
        } else {
            FALLBACK_TRACK_BYTES
        }
    }.coerceAtLeast(0L)

    /** Baixa uma única faixa (menu de toque longo), sem confirmação de lote. */
    fun downloadSingle(fragment: Fragment, track: Track) {
        val app = app(fragment) ?: return
        if (app.session.mystUserId <= 0L) {
            snackbar(fragment, R.string.track_download_need_login)
            return
        }
        ensureWarning(fragment) {
            enqueue(fragment, listOf(track)) { result ->
                if (result.added > 0) {
                    snackbar(fragment, R.string.track_download_queued_toast)
                } else {
                    snackbar(fragment, R.string.download_all_already)
                }
            }
        }
    }

    /**
     * "Baixar tudo" de álbum, playlist ou artista. A lista já deve conter
     * todas as faixas (quem chama carrega as continuações antes).
     */
    fun confirmAndDownload(fragment: Fragment, tracks: List<Track>) {
        val app = app(fragment) ?: return
        if (app.session.mystUserId <= 0L) {
            snackbar(fragment, R.string.track_download_need_login)
            return
        }
        if (tracks.isEmpty()) {
            snackbar(fragment, R.string.download_all_none)
            return
        }
        ensureWarning(fragment) {
            val estimate = estimateBytes(tracks)
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.download_all_confirm_title)
                .setMessage(
                    fragment.getString(
                        R.string.download_all_confirm_message,
                        tracks.size,
                        formatBytes(estimate),
                    ),
                )
                .setNegativeButton(R.string.download_all_cancel, null)
                .setPositiveButton(R.string.download_all_confirm) { _, _ ->
                    enqueue(fragment, tracks) { result ->
                        snackbar(fragment, resultMessage(fragment, result))
                    }
                }
                .show()
        }
    }

    /**
     * Confirma e baixa faixas que já foram carregadas por uma tela. Usado pelo
     * botão de baixar tudo do artista, que precisa mostrar a contagem real.
     */
    fun confirmLoaded(fragment: Fragment, tracks: List<Track>) = confirmAndDownload(fragment, tracks)

    fun retry(fragment: Fragment, track: Track) = downloadSingle(fragment, track)

    private fun enqueue(
        fragment: Fragment,
        tracks: List<Track>,
        onResult: ((EnqueueResult) -> Unit)? = null,
    ) {
        val app = app(fragment) ?: return
        val userId = app.session.mystUserId
        if (userId <= 0L) {
            snackbar(fragment, R.string.track_download_need_login)
            return
        }
        val estimate = estimateBytes(tracks)
        if (fragment.view == null) return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val free = withContext(Dispatchers.IO) { app.downloadStorage.availableBytes() }
                if (free > 0L && free < estimate + MIN_FREE_AFTER_DOWNLOAD) {
                    snackbar(fragment, R.string.track_download_space)
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    DownloadService.enqueueAndStart(
                        context = app,
                        repository = app.downloads,
                        userId = userId,
                        tracks = tracks,
                    )
                }
                AppLog.i(
                    AppLog.TAG_DOWNLOAD,
                    "UI enfileirou downloads: novos=${result.added} " +
                        "jáBaixados=${result.alreadyDownloaded} ignorados=${result.skipped}",
                )
                onResult?.invoke(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_DOWNLOAD, "Falha enfileirando downloads", t)
                snackbar(fragment, R.string.download_all_error)
            }
        }
    }

    private fun resultMessage(fragment: Fragment, result: EnqueueResult): String = when {
        result.added > 0 && (result.alreadyDownloaded > 0 || result.skipped > 0) ->
            fragment.getString(
                R.string.download_all_partial,
                result.added,
                result.alreadyDownloaded,
                result.skipped,
            )
        result.added > 0 -> fragment.getString(R.string.download_all_started, result.added)
        result.alreadyDownloaded > 0 && result.skipped == 0 ->
            fragment.getString(R.string.download_all_already)
        result.skipped > 0 -> fragment.getString(
            R.string.download_all_partial,
            0,
            result.alreadyDownloaded,
            result.skipped,
        )
        else -> fragment.getString(R.string.download_all_none)
    }

    private fun ensureWarning(fragment: Fragment, onAccept: () -> Unit) {
        val app = app(fragment) ?: return
        if (app.storagePreferences.firstDownloadWarningSeen) {
            onAccept()
            return
        }
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.track_download_warning_title)
            .setMessage(R.string.track_download_warning_message)
            .setNegativeButton(R.string.track_download_warning_cancel, null)
            .setPositiveButton(R.string.track_download_warning_accept) { _, _ ->
                app.storagePreferences.firstDownloadWarningSeen = true
                onAccept()
            }
            .show()
    }

    private fun app(fragment: Fragment): MystApp? {
        val activity = fragment.activity ?: return null
        return activity.application as? MystApp
    }

    private fun snackbar(fragment: Fragment, @StringRes messageRes: Int) {
        if (!fragment.isAdded) return
        val view = fragment.view ?: return
        Snackbar.make(view, messageRes, Snackbar.LENGTH_LONG).show()
    }

    private fun snackbar(fragment: Fragment, message: String) {
        if (!fragment.isAdded) return
        val view = fragment.view ?: return
        Snackbar.make(view, message, Snackbar.LENGTH_LONG).show()
    }
}
