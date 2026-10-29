package com.iskezis.myst.data.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Foto de perfil da conta Myst. O arquivo final fica em
 * filesDir/avatars/<userId>.jpg, sempre 512x512 JPEG. Nada de guardar a URI
 * do seletor: o provedor pode revogar o acesso e o avatar quebraria.
 */
object AvatarStore {

    private const val DIR = "avatars"
    private const val SIZE = 512
    private const val MAX_BYTES = 2 * 1024 * 1024

    suspend fun save(context: Context, userId: Long, sourcePath: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val bitmap = BitmapFactory.decodeFile(sourcePath) ?: return@withContext null
                saveBitmap(context, userId, bitmap)
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_UI, "Falha lendo avatar escolhido", t)
                null
            }
        }

    suspend fun saveBase64(context: Context, userId: Long, encoded: String): String? =
        withContext(Dispatchers.IO) {
            try {
                // Evita decodificar uma string gigante vinda de um backup
                // adulterado antes de descobrir que ela passa do limite.
                if (encoded.length > MAX_BYTES * 2) return@withContext null
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@withContext null
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withContext null
                saveBitmap(context, userId, bitmap)
            } catch (t: Throwable) {
                AppLog.failure(AppLog.TAG_PAIRING, "Falha recebendo avatar da TV", t)
                null
            }
        }

    suspend fun readBase64(path: String?): String? = withContext(Dispatchers.IO) {
        val file = path?.let(::File) ?: return@withContext null
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_BYTES) {
            return@withContext null
        }
        runCatching { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }.getOrNull()
    }

    fun cacheKey(path: String?): String? {
        val file = path?.let(::File) ?: return null
        if (!file.isFile) return null
        return "${file.absolutePath}#${file.length()}#${file.lastModified()}"
    }

    fun cleanupTemp(context: Context, path: String?) {
        val file = path?.let(::File) ?: return
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return
        val cache = runCatching { context.cacheDir.canonicalFile }.getOrNull() ?: return
        val insideCache = target.path == cache.path ||
            target.path.startsWith(cache.path + File.separator)
        if (insideCache) runCatching { target.delete() }
    }

    private fun saveBitmap(context: Context, userId: Long, bitmap: Bitmap): String? {
        var square: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            square = centerCrop(bitmap)
            scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val target = File(dir, "$userId.jpg")
            val temp = File(dir, "$userId.tmp")
            FileOutputStream(temp).use { output ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 90, output)) return null
            }
            if (target.exists() && !target.delete()) return null
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            AppLog.i(AppLog.TAG_UI, "Avatar salvo: ${target.absolutePath}")
            target.absolutePath
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_UI, "Falha gravando avatar", t)
            null
        } finally {
            if (scaled != null && scaled !== square) scaled.recycle()
            if (square != null && square !== bitmap) square.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun centerCrop(bitmap: Bitmap): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        if (side <= 0) throw IllegalArgumentException("avatar vazio")
        if (side == bitmap.width && side == bitmap.height) return bitmap
        val x = (bitmap.width - side) / 2
        val y = (bitmap.height - side) / 2
        return Bitmap.createBitmap(bitmap, x, y, side, side)
    }
}
