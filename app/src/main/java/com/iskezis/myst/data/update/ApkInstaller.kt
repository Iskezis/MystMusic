package com.iskezis.myst.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Entrega o APK ao instalador do Android por uma sessão do PackageInstaller.
 *
 * O fluxo antigo abria `ACTION_VIEW` com uma URI temporária do FileProvider em
 * `cacheDir`. Alguns instaladores de fabricante não conseguem ler essa URI e
 * mostram "erro ao analisar o pacote", mesmo com o APK íntegro. Na sessão, o
 * próprio app grava o arquivo no diretório privado do instalador; não há URI,
 * permissão temporária nem cache a ser limpo no meio da instalação.
 */
class ApkInstaller(private val context: Context) {

    suspend fun install(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() <= 0L) return@withContext false

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
        }

        var session: PackageInstaller.Session? = null
        try {
            val sessionId = installer.createSession(params)
            val opened = installer.openSession(sessionId)
            session = opened
            opened.openWrite(SESSION_NAME, 0L, file.length()).use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
                opened.fsync(output)
            }
            opened.commit(statusIntent(context, sessionId).intentSender)
            true
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_UPDATE, "Falha criando a sessão de instalação", t)
            runCatching { session?.abandon() }
            false
        } finally {
            runCatching { session?.close() }
        }
    }

    private fun statusIntent(context: Context, sessionId: Int): PendingIntent {
        val intent = Intent(context, UpdateInstallReceiver::class.java)
            .setAction(UpdateInstallReceiver.ACTION_INSTALL_RESULT)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    companion object {
        private const val SESSION_NAME = "base.apk"
    }
}
