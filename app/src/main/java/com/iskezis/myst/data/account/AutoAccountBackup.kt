package com.iskezis.myst.data.account

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Cópia automática do Mystaccount.json no armazenamento compartilhado do
 * celular. O caminho preferido é /sdcard/MystMusic/Mystaccount.json.
 *
 * A partir do Android 11 o acesso direto à raiz do armazenamento exige
 * MANAGE_EXTERNAL_STORAGE. Sem essa permissão o arquivo continua sendo salvo
 * automaticamente em Download/MystMusic (MediaStore), sem pedir nada ao
 * usuário. A permissão especial é pedida uma única vez na interface; quando é
 * concedida, a próxima sincronização grava no caminho preferido.
 *
 * A TV nunca usa esta classe.
 */
class AutoAccountBackup(private val context: Context) {

    data class Result(
        val saved: Boolean,
        val exact: Boolean,
        val path: String,
        val error: Throwable? = null,
    )

    private val writeMutex = Mutex()

    suspend fun write(text: String): Result = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            if (hasExactAccess(context)) {
                runCatching { writeExact(text) }.getOrElse { error ->
                    AppLog.failure(
                        AppLog.TAG_AUTH,
                        "Falha gravando em /sdcard/MystMusic; usando Download/MystMusic",
                        error,
                    )
                    writeFallback(text)
                }
            } else {
                writeFallback(text)
            }
        }
    }

    private fun writeExact(text: String): Result {
        val directory = File(Environment.getExternalStorageDirectory(), FOLDER_NAME)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Não foi possível criar ${directory.absolutePath}")
        }
        val target = File(directory, FILE_NAME)
        val temp = File(directory, ".$FILE_NAME.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!replace(temp, target)) {
            throw IOException("Não foi possível substituir ${target.absolutePath}")
        }
        AppLog.i(AppLog.TAG_AUTH, "Mystaccount.json automático: ${target.absolutePath}")
        return Result(
            saved = true,
            exact = true,
            path = "$DISPLAY_ROOT/$FOLDER_NAME/$FILE_NAME",
        )
    }

    private fun writeFallback(text: String): Result {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { writeMediaStore(text) }.getOrElse { error ->
                AppLog.failure(
                    AppLog.TAG_AUTH,
                    "Falha gravando Mystaccount.json em Download/MystMusic",
                    error,
                )
                writeAppPrivate(text)
            }
        } else {
            runCatching { writeLegacyDownloads(text) }.getOrElse { error ->
                AppLog.failure(
                    AppLog.TAG_AUTH,
                    "Falha gravando Mystaccount.json nos Downloads",
                    error,
                )
                writeAppPrivate(text)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeMediaStore(text: String): Result {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME/"
        val projection = arrayOf(android.provider.BaseColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(FILE_NAME, relativePath)

        val existingId = runCatching {
            resolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        }.onFailure {
            AppLog.w(
                AppLog.TAG_AUTH,
                "MediaStore não deixou consultar a cópia existente; criando uma nova: " +
                    AppLog.describe(it),
            )
        }.getOrNull()

        val created = existingId == null
        val uri = if (existingId != null) {
            ContentUris.withAppendedId(collection, existingId)
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            resolver.insert(collection, values)
                ?: throw IOException("MediaStore não criou o arquivo")
        }

        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val ready = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        return try {
            if (created) resolver.update(uri, pending, null, null)
            val stream = resolver.openOutputStream(uri, "wt")
                ?: resolver.openOutputStream(uri, "w")
                ?: throw IOException("MediaStore não abriu o arquivo para escrita")
            stream.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            if (created) resolver.update(uri, ready, null, null)
            AppLog.i(
                AppLog.TAG_AUTH,
                "Mystaccount.json automático (MediaStore): $DISPLAY_ROOT/" +
                    "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME/$FILE_NAME",
            )
            Result(
                saved = true,
                exact = false,
                path = "$DISPLAY_ROOT/${Environment.DIRECTORY_DOWNLOADS}/" +
                    "$FOLDER_NAME/$FILE_NAME",
            )
        } catch (t: Throwable) {
            // Só apaga o que esta chamada criou. Uma cópia antiga que não
            // pôde ser sobrescrita deve continuar intacta para o usuário.
            if (created) runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun writeLegacyDownloads(text: String): Result {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FOLDER_NAME,
        )
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Não foi possível criar ${directory.absolutePath}")
        }
        val target = File(directory, FILE_NAME)
        val temp = File(directory, ".$FILE_NAME.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!replace(temp, target)) {
            throw IOException("Não foi possível substituir ${target.absolutePath}")
        }
        AppLog.i(
            AppLog.TAG_AUTH,
            "Mystaccount.json automático (Downloads): ${target.absolutePath}",
        )
        return Result(
            saved = true,
            exact = false,
            path = "$DISPLAY_ROOT/${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME/$FILE_NAME",
        )
    }

    /**
     * Último recurso quando o usuário negou as permissões e o MediaStore não
     * abriu o arquivo. Fica no armazenamento externo privado do app: some ao
     * desinstalar, por isso a interface sempre orienta a conceder o acesso.
     */
    private fun writeAppPrivate(text: String): Result {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val directory = File(base, FOLDER_NAME)
        if (!directory.isDirectory && !directory.mkdirs()) {
            return Result(
                saved = false,
                exact = false,
                path = "",
                error = IOException("Armazenamento externo indisponível"),
            )
        }
        val target = File(directory, FILE_NAME)
        val temp = File(directory, ".$FILE_NAME.tmp")
        return runCatching {
            temp.writeText(text, Charsets.UTF_8)
            if (!replace(temp, target)) {
                throw IOException("Não foi possível substituir ${target.absolutePath}")
            }
            AppLog.w(
                AppLog.TAG_AUTH,
                "Mystaccount.json automático no armazenamento privado do app: " +
                    target.absolutePath,
            )
            Result(saved = true, exact = false, path = target.absolutePath)
        }.getOrElse { error ->
            runCatching { temp.delete() }
            AppLog.failure(AppLog.TAG_AUTH, "Falha gravando cópia automática privada", error)
            Result(saved = false, exact = false, path = "", error = error)
        }
    }

    private fun replace(temp: File, target: File): Boolean {
        if (target.exists() && !target.delete()) return false
        if (temp.renameTo(target)) return true
        return runCatching {
            temp.copyTo(target, overwrite = true)
            temp.delete()
            true
        }.getOrDefault(false)
    }

    companion object {
        const val FILE_NAME = "Mystaccount.json"

        private const val FOLDER_NAME = "MystMusic"
        private const val DISPLAY_ROOT = "/sdcard"

        /** Caminho exibido na interface e usado quando a permissão especial existe. */
        const val EXACT_PATH = "$DISPLAY_ROOT/$FOLDER_NAME/$FILE_NAME"

        /**
         * true quando dá para gravar em /sdcard/MystMusic sem MediaStore.
         * No Android 10 o requestLegacyExternalStorage só funciona com a
         * permissão de escrita antiga; no 11+ é MANAGE_EXTERNAL_STORAGE.
         */
        fun hasExactAccess(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return Environment.isExternalStorageManager()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED
            }
            return true
        }

        /** A permissão que ainda precisa ser pedida ao usuário, ou null. */
        fun runtimePermission(): String? =
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M until Build.VERSION_CODES.R) {
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            } else {
                null
            }

        /** Tela do Android para conceder acesso a todos os arquivos no 11+. */
        fun allFilesSettingsIntent(context: Context): Intent? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        }
    }
}
