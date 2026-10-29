package com.iskezis.myst.data.account

import android.content.Context
import com.iskezis.myst.util.AppLog
import java.io.File

/**
 * Limpeza dos dados que a TV possa ter gravado em versões anteriores.
 *
 * A partir da 0.2.0-dev.8 a TV usa Room em memória e não escreve
 * Mystaccount.json, capas ou avatar em disco. Esta rotina remove resíduos de
 * versões antigas e é chamada ao abrir o app e ao desconectar do celular.
 */
object TvEphemeralData {

    private val EPHEMERAL_DIRECTORIES = arrayOf("account", "covers", "avatars")

    fun purge(context: Context) {
        EPHEMERAL_DIRECTORIES.forEach { name ->
            runCatching {
                File(context.filesDir, name).deleteRecursively()
            }.onFailure {
                AppLog.failure(AppLog.TAG_AUTH, "Falha limpando $name temporário da TV", it)
            }
        }
        runCatching {
            context.getSharedPreferences("myst_account_file", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }.onFailure {
            AppLog.failure(AppLog.TAG_AUTH, "Falha limpando URI antiga da conta na TV", it)
        }
        AppLog.i(AppLog.TAG_AUTH, "TV: dados temporários antigos removidos do disco")
    }
}
