package com.iskezis.myst.data.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.MainActivity
import com.iskezis.myst.MystApp
import com.iskezis.myst.R
import com.iskezis.myst.data.db.DownloadEntity
import com.iskezis.myst.data.youtube.LoginRequiredException
import com.iskezis.myst.data.youtube.StreamInfo
import com.iskezis.myst.data.youtube.Track
import com.iskezis.myst.player.StreamResolver
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fila de downloads em primeiro plano (dataSync).
 *
 * - Duas faixas em paralelo no celular e uma na TV.
 * - O arquivo é escrito em `.part` e só ganha o nome final ao concluir; uma
 *   nova tentativa continua com Range quando o servidor permitir.
 * - Até três tentativas por faixa, com URL renovada no InnerTube. Uma falha
 *   vira FAILED com motivo e a fila continua.
 * - A notificação mostra o progresso agregado e permite cancelar.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: MystApp
    private lateinit var repository: DownloadRepository
    private lateinit var storage: DownloadStorage
    private lateinit var resolver: StreamResolver
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    private val claimMutex = Mutex()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val cancelRequested = AtomicBoolean(false)
    private var workerJob: Job? = null
    private var total = 0
    private var finished = 0
    private var failed = 0
    private var lastNotifyAt = 0L
    private val finishedOnce = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        app = application as MystApp
        repository = app.downloads
        storage = app.downloadStorage
        resolver = StreamResolver(app)
        createChannel()
        AppLog.i(AppLog.TAG_DOWNLOAD, "DownloadService criado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_ALL -> {
                cancelAll()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_ONE -> {
                val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
                if (videoId.isNullOrBlank()) {
                    if (workerJob?.isActive != true) stopSelf()
                } else {
                    // Não encerra o serviço antes de a remoção terminar; um
                    // cancelamento de faixa enfileirada não pode ficar pela
                    // metade porque a UI já sumiu da tela.
                    scope.launch {
                        cancelOne(videoId)
                        if (workerJob?.isActive != true) stopSelf()
                    }
                }
                return START_STICKY
            }
        }
        val started = startForegroundSafely(
            buildNotification(null, 0, 0, preparing = true, done = false),
        )
        if (!started) return START_NOT_STICKY
        startWork()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWork() {
        synchronized(this) {
            // Uma nova fila pode chegar logo depois de "Cancelar todos"; sem
            // zerar estes sinais o serviço recém-reiniciado sairia na hora.
            cancelRequested.set(false)
            finishedOnce.set(false)
            if (workerJob?.isActive == true) return
            workerJob = scope.launch {
                runCatching { repository.resetInterrupted() }
                val userId = app.session.mystUserId
                total = if (userId > 0L) repository.activeCount(userId) else 0
                if (total <= 0) {
                    // Nada na fila: não deixa uma notificação "0 de 0".
                    stopForegroundCompat()
                    stopSelf()
                    return@launch
                }
                finished = 0
                failed = 0
                AppLog.i(AppLog.TAG_DOWNLOAD, "Fila iniciada com $total faixa(s)")
                try {
                    coroutineScope {
                        repeat(concurrency()) {
                            launch { worker() }
                        }
                    }
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    AppLog.failure(AppLog.TAG_DOWNLOAD, "Fila de downloads interrompida", t)
                } finally {
                    if (cancelRequested.get()) {
                        // O cancelamento precisa terminar mesmo que o serviço
                        // seja encerrado em seguida (stopSelf cancela o scope).
                        withContext(NonCancellable) {
                            runCatching { repository.cancelAll(userId) }
                        }
                        finishNotification(success = false, cancelled = true)
                    } else {
                        finishNotification(success = failed == 0)
                    }
                }
            }
        }
    }

    private suspend fun worker() {
        while (scope.isActive && !cancelRequested.get()) {
            val item = claimNext() ?: return
            if (cancelled.contains(item.videoId)) continue
            val succeeded = downloadWithRetries(item)
            if (succeeded) {
                finished++
            } else {
                failed++
            }
            cancelled.remove(item.videoId)
            notifyProgress(null, 0, 0, done = false)
        }
    }

    private suspend fun claimNext(): DownloadEntity? = claimMutex.withLock {
        val userId = app.session.mystUserId
        if (userId <= 0L) return@withLock null
        val queued = repository.queued(userId)
        for (item in queued) {
            if (cancelled.contains(item.videoId)) continue
            if (repository.claim(item)) return@withLock item
        }
        null
    }

    private suspend fun downloadWithRetries(item: DownloadEntity): Boolean {
        if (cancelled.contains(item.videoId)) return false
        if (!hasEnoughSpace()) {
            repository.fail(item, "Espaço insuficiente no armazenamento")
            return false
        }
        val existing = storage.findAudio(item.videoId, item.path)
        if (existing != null && existing.length() > MIN_AUDIO_BYTES) {
            val cover = ensureCover(item)
            repository.complete(item, existing, cover, existing.length())
            app.audioCache.removeStream(item.videoId)
            return true
        }
        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            if (cancelRequested.get() || cancelled.contains(item.videoId)) return false
            // Perder a rede não queima tentativas: a faixa fica aguardando e
            // continua sozinha quando a conexão voltar.
            if (!waitForNetwork(item.videoId)) return false
            try {
                downloadOnce(item)
                return true
            } catch (cancelledError: DownloadCancelledException) {
                storage.partFile(item.videoId).delete()
                return false
            } catch (cancelledError: CancellationException) {
                throw cancelledError
            } catch (t: Throwable) {
                lastError = t
                AppLog.w(
                    AppLog.TAG_DOWNLOAD,
                    "Tentativa $attempt/$MAX_ATTEMPTS falhou para ${item.videoId}: " +
                        AppLog.describe(t),
                )
                resolver.invalidate(item.videoId)
                if (attempt < MAX_ATTEMPTS) {
                    delay(1_500L * attempt)
                }
            }
        }
        val reason = friendlyReason(lastError)
        repository.fail(item, reason)
        return false
    }

    private suspend fun downloadOnce(item: DownloadEntity) {
        val info = resolver.resolve(item.videoId)
        val part = storage.partFile(item.videoId)
        part.parentFile?.mkdirs()
        val startOffset = if (part.isFile) part.length() else 0L
        val request = buildRequest(info, startOffset)
        http.newCall(request).execute().use { response ->
            if (response.code == 416) {
                part.delete()
                throw IOException("Servidor recusou a retomada")
            }
            if (!response.isSuccessful) {
                throw HttpFailure(response.code, response.message)
            }
            val body = response.body ?: throw IOException("Resposta sem áudio")
            val contentType = body.contentType()?.toString().orEmpty()
            if (contentType.startsWith("text/", ignoreCase = true)) {
                throw IOException("Servidor devolveu uma página em vez de áudio")
            }
            val append = response.code == 206 && startOffset > 0L
            val initialBytes = if (append) startOffset else 0L
            val contentLength = body.contentLength()
            val rangeTotal = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            val expectedTotal = when {
                rangeTotal != null && rangeTotal > 0L -> rangeTotal
                contentLength > 0L -> initialBytes + contentLength
                else -> 0L
            }
            if (storage.availableBytes() in 1 until MIN_FREE_BYTES) {
                throw IOException("Espaço insuficiente")
            }
            var written = initialBytes
            var lastDbUpdate = 0L
            FileOutputStream(part, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (cancelRequested.get() || cancelled.contains(item.videoId)) {
                            throw DownloadCancelledException()
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastDbUpdate >= PROGRESS_INTERVAL_MS) {
                            lastDbUpdate = now
                            repository.progress(item, written, expectedTotal)
                            notifyProgress(item, written, expectedTotal, done = false)
                        }
                    }
                    output.fd.sync()
                }
            }
            if (written <= MIN_AUDIO_BYTES) {
                part.delete()
                throw IOException("Arquivo de áudio vazio ou inválido")
            }
            if (expectedTotal > 0L && written < expectedTotal) {
                throw IOException(
                    "Download incompleto: $written de $expectedTotal bytes",
                )
            }
            // A linha pode ter sido removida (Remover todas) ou cancelada
            // enquanto o arquivo era escrito. Não renomeia para não deixar um
            // arquivo órfão que a conta não consegue mais gerenciar.
            val current = repository.find(item.userId, item.videoId)
            if (
                cancelRequested.get() ||
                cancelled.contains(item.videoId) ||
                current == null ||
                current.status != DownloadStatus.DOWNLOADING
            ) {
                part.delete()
                throw DownloadCancelledException()
            }
            val finalFile = storage.audioFile(item.videoId)
            finalFile.parentFile?.mkdirs()
            if (finalFile.exists()) finalFile.delete()
            if (!part.renameTo(finalFile)) {
                part.copyTo(finalFile, overwrite = true)
                part.delete()
            }
            val cover = ensureCover(item)
            repository.complete(item, finalFile, cover, expectedTotal)
            notifyProgress(item, finalFile.length(), finalFile.length(), done = false)
            AppLog.i(
                AppLog.TAG_DOWNLOAD,
                "Áudio salvo: ${finalFile.absolutePath} " +
                    "(${formatBytes(finalFile.length())})",
            )
        }
    }

    /**
     * Espera a conexão voltar sem marcar a faixa como falha. O usuário pode
     * cancelar pela notificação ou pela tela Baixadas a qualquer momento.
     */
    private suspend fun waitForNetwork(videoId: String): Boolean {
        if (app.networkMonitor.isOnline()) return true
        AppLog.i(
            AppLog.TAG_DOWNLOAD,
            "Sem conexão; $videoId aguardando a rede voltar",
        )
        notifyProgress(null, 0, 0, done = false, waiting = true)
        while (scope.isActive && !cancelRequested.get() && !cancelled.contains(videoId)) {
            delay(NETWORK_POLL_MS)
            if (app.networkMonitor.isOnline()) return true
        }
        return false
    }

    private fun buildRequest(info: StreamInfo, startOffset: Long): Request {
        val headers = Headers.Builder().apply {
            info.headers.forEach { (name, value) -> add(name, value) }
        }.build()
        val builder = Request.Builder()
            .url(info.url)
            .headers(headers)
            .header("Accept", "*/*")
        if (startOffset > 0L) {
            builder.header("Range", "bytes=$startOffset-")
        }
        return builder.get().build()
    }

    private fun ensureCover(item: DownloadEntity): File? {
        val target = storage.coverFile(item.videoId)
        val existing = storage.findCover(item.videoId, item.coverPath)
        if (existing != null && existing.length() > 0L) return existing
        val track = Track(
            videoId = item.videoId,
            title = item.title,
            artist = item.artist,
            thumbnailUrl = item.thumbnailUrl,
            durationMs = item.durationMs,
            album = item.album,
        )
        val url = track.artworkUrl() ?: return null
        return runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.part")
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                temp.outputStream().use { output -> body.byteStream().copyTo(output) }
                if (temp.length() <= 0L) {
                    temp.delete()
                    return@use null
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                target
            }
        }.getOrNull()
    }

    private fun hasEnoughSpace(): Boolean {
        val free = storage.availableBytes()
        return free <= 0L || free >= MIN_FREE_BYTES
    }

    private fun friendlyReason(error: Throwable?): String = when {
        error is LoginRequiredException -> "Entre no YouTube novamente para baixar"
        error is HttpFailure && error.code in listOf(401, 403) ->
            "Faixa restrita ou conta do YouTube expirada"
        error is HttpFailure && error.code == 404 -> "Faixa indisponível"
        error is IOException && error.message?.contains("Espaço", ignoreCase = true) == true ->
            "Espaço insuficiente no armazenamento"
        error is IOException && error.message?.contains("conex", ignoreCase = true) == true ->
            "Sem conexão; tente baixar novamente"
        else -> "Não foi possível baixar; tente novamente"
    }

    private fun concurrency(): Int = if (BuildConfig.FLAVOR == "tv") 1 else 2

    private fun startForegroundSafely(notification: Notification): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure {
            AppLog.failure(
                AppLog.TAG_DOWNLOAD,
                "Android recusou o serviço em primeiro plano de downloads",
                it,
            )
            stopSelf()
        }.isSuccess
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.download_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        item: DownloadEntity?,
        bytes: Long,
        totalBytes: Long,
        preparing: Boolean = false,
        done: Boolean = false,
        cancelled: Boolean = false,
        waiting: Boolean = false,
    ): Notification {
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingFlags(),
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            pendingFlags(),
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arrow_down)
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setAutoCancel(done)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (done) {
            builder.setContentTitle(
                getString(
                    if (cancelled) R.string.download_cancelled else R.string.download_finished,
                ),
            )
            builder.setContentText(
                getString(
                    if (failed > 0) R.string.download_finished_failed else R.string.download_finished_ok,
                    finished,
                    failed,
                ),
            )
            builder.setProgress(100, 100, false)
            return builder.build()
        }
        val completed = total.coerceAtLeast(finished)
        val aggregatePercent = if (completed > 0) {
            (finished * 100 / completed).coerceIn(0, 100)
        } else {
            0
        }
        builder.setContentTitle(
            getString(
                when {
                    waiting -> R.string.download_waiting_network
                    preparing -> R.string.download_preparing
                    else -> R.string.download_running
                },
            ),
        )
        val itemPercent = if (totalBytes > 0L) {
            (bytes * 100 / totalBytes).coerceIn(0, 100).toInt()
        } else {
            aggregatePercent
        }
        builder.setContentText(
            if (item != null) {
                getString(
                    R.string.download_progress,
                    finished.coerceAtMost(completed),
                    completed,
                    item.title.ifBlank { getString(R.string.player_unknown_title) },
                    itemPercent,
                )
            } else {
                getString(
                    R.string.download_progress_aggregate,
                    finished.coerceAtMost(completed),
                    completed,
                )
            },
        )
        builder.setProgress(100, itemPercent, (preparing || waiting) && item == null)
        builder.addAction(
            R.drawable.ic_close,
            getString(R.string.download_cancel),
            cancel,
        )
        return builder.build()
    }

    private fun notifyProgress(
        item: DownloadEntity?,
        bytes: Long,
        totalBytes: Long,
        done: Boolean,
        waiting: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        if (!done && now - lastNotifyAt < NOTIFY_INTERVAL_MS) return
        lastNotifyAt = now
        runCatching {
            val notification = buildNotification(
                item,
                bytes,
                totalBytes,
                done = done,
                waiting = waiting,
            )
            notificationManager().notify(NOTIFICATION_ID, notification)
        }
    }

    private fun finishNotification(success: Boolean, cancelled: Boolean = false) {
        if (!finishedOnce.compareAndSet(false, true)) return
        if (success) failed = 0
        runCatching {
            val notification = buildNotification(
                item = null,
                bytes = 0,
                totalBytes = 0,
                done = true,
                cancelled = cancelled,
            )
            notificationManager().notify(NOTIFICATION_ID, notification)
        }
        stopForegroundCompat()
        stopSelf()
    }

    private fun cancelAll() {
        AppLog.i(AppLog.TAG_DOWNLOAD, "Cancelamento de todos os downloads")
        cancelRequested.set(true)
        scope.launch {
            // Não deixa a fila presa se o serviço não tiver workers ativos.
            val userId = app.session.mystUserId
            withContext(NonCancellable) {
                runCatching { repository.cancelAll(userId) }
            }
            if (workerJob?.isActive != true) {
                finishNotification(success = false, cancelled = true)
            }
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private suspend fun cancelOne(videoId: String) {
        AppLog.i(AppLog.TAG_DOWNLOAD, "Cancelando download $videoId")
        cancelled += videoId
        storage.deleteTransfer(videoId)
        storage.partFile(videoId).delete()
        val userId = app.session.mystUserId
        if (userId > 0L) {
            val item = repository.find(userId, videoId)
            if (item != null && item.status != DownloadStatus.DONE) {
                repository.remove(userId, videoId)
            }
            // Um item apenas na fila não tem worker que limpe o marcador; sem
            // isso, baixar a mesma faixa de novo nesta sessão seria ignorado.
            if (item?.status != DownloadStatus.DOWNLOADING) {
                cancelled.remove(videoId)
            }
        } else {
            cancelled.remove(videoId)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    private fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    override fun onDestroy() {
        AppLog.i(AppLog.TAG_DOWNLOAD, "DownloadService.onDestroy")
        cancelRequested.set(true)
        workerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private class HttpFailure(val code: Int, message: String) :
        IOException("HTTP $code $message")

    private class DownloadCancelledException : Exception("Download cancelado")

    companion object {
        private const val CHANNEL_ID = "myst_downloads"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_CANCEL_ALL = "com.iskezis.myst.action.CANCEL_DOWNLOADS"
        private const val ACTION_CANCEL_ONE = "com.iskezis.myst.action.CANCEL_DOWNLOAD"
        private const val EXTRA_VIDEO_ID = "videoId"
        private const val MIN_FREE_BYTES = 200L * 1024L * 1024L
        private const val MIN_AUDIO_BYTES = 24L * 1024L
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 600L
        private const val NOTIFY_INTERVAL_MS = 900L
        private const val NETWORK_POLL_MS = 2_000L
        private const val MAX_ATTEMPTS = 3

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DownloadService::class.java),
                )
            }.onFailure {
                AppLog.failure(
                    AppLog.TAG_DOWNLOAD,
                    "Falha iniciando o serviço de downloads",
                    it,
                )
            }
        }

        fun cancelAll(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
                )
            }.onFailure {
                AppLog.failure(AppLog.TAG_DOWNLOAD, "Falha cancelando downloads", it)
            }
        }

        fun cancel(context: Context, videoId: String) {
            runCatching {
                context.startService(
                    Intent(context, DownloadService::class.java)
                        .setAction(ACTION_CANCEL_ONE)
                        .putExtra(EXTRA_VIDEO_ID, videoId),
                )
            }.onFailure {
                AppLog.failure(AppLog.TAG_DOWNLOAD, "Falha cancelando download $videoId", it)
            }
        }

        suspend fun enqueueAndStart(
            context: Context,
            repository: DownloadRepository,
            userId: Long,
            tracks: List<Track>,
        ): EnqueueResult {
            val result = repository.enqueue(userId, tracks)
            if (result.added > 0) start(context)
            return result
        }
    }
}
