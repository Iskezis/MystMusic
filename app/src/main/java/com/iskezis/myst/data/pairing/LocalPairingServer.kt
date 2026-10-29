package com.iskezis.myst.data.pairing

import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Servidor HTTP mínimo usado SOMENTE na rede local durante o pareamento.
 *
 * Não é um servidor genérico: aceita um único endpoint por instância, limita o
 * corpo, fecha a conexão depois de cada resposta e nunca registra o corpo (que
 * contém payload cifrado/conta). Ele é encerrado assim que a tela fecha.
 */
class LocalPairingServer(
    private val maxBodyBytes: Int = MAX_BODY_BYTES,
    private val handler: suspend (path: String, body: String, remoteHost: String) -> HttpResult,
) : Closeable {

    data class HttpResult(
        val status: Int = 200,
        val body: String = "{}",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var closed = false

    val port: Int
        get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun start(): Int {
        if (closed) throw IllegalStateException("Servidor de pareamento já encerrado")
        serverSocket?.let { return it.localPort }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0))
        serverSocket = socket
        AppLog.i(AppLog.TAG_PAIRING, "Servidor local do pareamento na porta ${socket.localPort}")
        acceptJob = scope.launch {
            while (isActive && !closed) {
                val client = try {
                    socket.accept()
                } catch (t: SocketException) {
                    if (!closed) AppLog.w(AppLog.TAG_PAIRING, "Accept encerrado: ${t.message}")
                    break
                } catch (t: IOException) {
                    if (!closed) AppLog.failure(AppLog.TAG_PAIRING, "Falha no accept", t)
                    break
                }
                launch { serve(client) }
            }
        }
        return socket.localPort
    }

    private suspend fun serve(client: Socket) = withContext(Dispatchers.IO) {
        client.use { socket ->
            try {
                socket.soTimeout = 10_000
                val input = socket.getInputStream()
                val headerBytes = ByteArrayOutputStream()
                var matched = 0
                val delimiter = byteArrayOf(13, 10, 13, 10)
                while (headerBytes.size() < MAX_HEADER_BYTES) {
                    val value = input.read()
                    if (value < 0) break
                    headerBytes.write(value)
                    if (value.toByte() == delimiter[matched]) {
                        matched++
                        if (matched == delimiter.size) break
                    } else {
                        matched = if (value.toByte() == delimiter[0]) 1 else 0
                    }
                }
                val headerText = headerBytes.toString(Charsets.UTF_8.name())
                if (!headerText.contains("\r\n\r\n")) {
                    writeResponse(socket, 400, """{"ok":false,"error":"bad_request"}""")
                    return@use
                }
                val lines = headerText.substringBefore("\r\n\r\n").split("\r\n")
                val requestLine = lines.firstOrNull().orEmpty()
                val pieces = requestLine.split(' ')
                if (pieces.size < 2) {
                    writeResponse(socket, 400, """{"ok":false,"error":"bad_request"}""")
                    return@use
                }
                val path = pieces[1].substringBefore('?')
                val headers = lines.drop(1).mapNotNull { line ->
                    val index = line.indexOf(':')
                    if (index <= 0) null
                    else line.substring(0, index).trim().lowercase() to
                        line.substring(index + 1).trim()
                }.toMap()
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length < 0 || length > maxBodyBytes) {
                    writeResponse(socket, 413, """{"ok":false,"error":"too_large"}""")
                    return@use
                }
                val bodyBytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val read = input.read(bodyBytes, offset, length - offset)
                    if (read < 0) break
                    offset += read
                }
                val body = String(bodyBytes, 0, offset, Charsets.UTF_8)
                val remote = socket.inetAddress?.hostAddress.orEmpty()
                val result = try {
                    handler(path, body, remote)
                } catch (t: Throwable) {
                    AppLog.failure(AppLog.TAG_PAIRING, "Erro tratando ${path.take(40)}", t)
                    HttpResult(500, """{"ok":false,"error":"internal"}""")
                }
                writeResponse(socket, result.status, result.body)
            } catch (t: Throwable) {
                if (t !is SocketException) {
                    AppLog.w(
                        AppLog.TAG_PAIRING,
                        "Conexão de pareamento encerrada: ${t.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private fun writeResponse(socket: Socket, status: Int, body: String) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Internal Server Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().apply {
            write(header)
            write(bytes)
            flush()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
        AppLog.i(AppLog.TAG_PAIRING, "Servidor local do pareamento encerrado")
    }

    private companion object {
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 64 * 1024
    }
}
