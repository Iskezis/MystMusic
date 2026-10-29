package com.iskezis.myst.data.account

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.iskezis.myst.util.AppLog
import java.io.File

/**
 * Sessão do app com duas contas obrigatórias: Myst (perfil e dados locais) e
 * YouTube (catálogo e reprodução). A sessão antiga de uma conta só é migrada
 * na primeira abertura da 0.2.0 sem apagar playlists, histórico ou afinidade.
 *
 * Quando o cofre do Android está disponível, tudo fica em
 * EncryptedSharedPreferences; se o keystore falhar em algum aparelho, cai para
 * um arquivo privado comum para que o app continue abrindo sem crashar.
 */
class SessionManager(context: Context) {

    data class Session(
        val type: String,
        val userId: Long,
        val displayName: String,
    )

    data class YouTubeSnapshot(
        val cookies: String?,
        val sapisid: String?,
        val visitorData: String?,
        val dataSyncId: String?,
        val accountId: String?,
        val name: String?,
        val conflict: Boolean,
    )

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    init {
        migrateLegacySession()
    }

    /** Sessão Myst, usada como dona de playlists, histórico e recomendações. */
    val session: Session?
        get() {
            if (!hasMyst) return null
            return Session(
                type = TYPE_MYST,
                userId = mystUserId,
                displayName = mystDisplayName.ifBlank { "Myst" },
            )
        }

    val mystUserId: Long
        get() = prefs.getLong(KEY_MYST_ID, -1L)

    val mystDisplayName: String
        get() = prefs.getString(KEY_MYST_NAME, "").orEmpty()

    val hasMyst: Boolean get() = mystUserId > 0L

    var youtubeCookies: String?
        get() = prefs.getString(KEY_YT_COOKIES, null)
        set(value) = prefs.edit().putString(KEY_YT_COOKIES, value).apply()

    var youtubeSapisid: String?
        get() = prefs.getString(KEY_YT_SAPISID, null)
        set(value) = prefs.edit().putString(KEY_YT_SAPISID, value).apply()

    var youtubeVisitorData: String?
        get() = prefs.getString(KEY_YT_VISITOR, null)
        set(value) = prefs.edit().putString(KEY_YT_VISITOR, value).apply()

    var youtubeDataSyncId: String?
        get() = prefs.getString(KEY_YT_SYNC, null)
        set(value) = prefs.edit().putString(KEY_YT_SYNC, value).apply()

    val youtubeAccountId: String?
        get() {
            val saved = prefs.getString(KEY_YT_ACCOUNT_ID, null)
            if (!saved.isNullOrBlank()) return saved
            val cookies = youtubeCookies
            return if (cookies.isNullOrBlank()) null else YouTubeAccount.accountId(cookies)
        }

    val youtubeDisplayName: String
        get() = prefs.getString(KEY_YT_NAME, "").orEmpty()

    val hasYouTube: Boolean
        get() = !youtubeCookies.isNullOrBlank() && !youtubeAccountId.isNullOrBlank()

    var youtubeConflict: Boolean
        get() = prefs.getBoolean(KEY_YT_CONFLICT, false)
        set(value) = prefs.edit().putBoolean(KEY_YT_CONFLICT, value).apply()

    /** Dados da sessão 0.1.0 que ainda pertencem ao usuário antigo do YouTube. */
    val legacyDataUserId: Long
        get() = prefs.getLong(KEY_LEGACY_USER_ID, -1L)

    val hasLegacyData: Boolean
        get() = hasMyst && legacyDataUserId > 0L && legacyDataUserId != mystUserId

    val isLoggedIn: Boolean
        get() = hasMyst && hasYouTube && !youtubeConflict

    var discordToken: String?
        get() = prefs.getString(KEY_DISCORD_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_DISCORD_TOKEN, value).apply()

    var discordName: String?
        get() = prefs.getString(KEY_DISCORD_NAME, null)
        set(value) = prefs.edit().putString(KEY_DISCORD_NAME, value).apply()

    var tvPaired: Boolean
        get() = prefs.getBoolean(KEY_TV_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_TV_PAIRED, value).apply()

    fun saveMyst(userId: Long, name: String) {
        prefs.edit()
            .putLong(KEY_MYST_ID, userId)
            .putString(KEY_MYST_NAME, name)
            .apply()
    }

    fun saveYouTube(accountId: String, name: String, cookies: String, sapisid: String?) {
        prefs.edit()
            .putString(KEY_YT_ACCOUNT_ID, accountId)
            .putString(KEY_YT_NAME, name)
            .putString(KEY_YT_COOKIES, cookies)
            .putString(KEY_YT_SAPISID, sapisid)
            .remove(KEY_YT_VISITOR)
            .remove(KEY_YT_SYNC)
            .putBoolean(KEY_YT_CONFLICT, false)
            .apply()
    }

    fun captureYouTube(): YouTubeSnapshot = YouTubeSnapshot(
        cookies = youtubeCookies,
        sapisid = youtubeSapisid,
        visitorData = youtubeVisitorData,
        dataSyncId = youtubeDataSyncId,
        accountId = youtubeAccountId,
        name = youtubeDisplayName,
        conflict = youtubeConflict,
    )

    fun restoreYouTube(snapshot: YouTubeSnapshot) {
        prefs.edit().apply {
            putString(KEY_YT_COOKIES, snapshot.cookies)
            putString(KEY_YT_SAPISID, snapshot.sapisid)
            putString(KEY_YT_VISITOR, snapshot.visitorData)
            putString(KEY_YT_SYNC, snapshot.dataSyncId)
            putString(KEY_YT_ACCOUNT_ID, snapshot.accountId)
            putString(KEY_YT_NAME, snapshot.name)
            putBoolean(KEY_YT_CONFLICT, snapshot.conflict)
        }.apply()
    }

    fun clearMyst() {
        prefs.edit()
            .remove(KEY_MYST_ID)
            .remove(KEY_MYST_NAME)
            .putBoolean(KEY_YT_CONFLICT, false)
            .apply()
    }

    fun clearYouTube() {
        prefs.edit()
            .remove(KEY_YT_COOKIES)
            .remove(KEY_YT_SAPISID)
            .remove(KEY_YT_VISITOR)
            .remove(KEY_YT_SYNC)
            .remove(KEY_YT_ACCOUNT_ID)
            .remove(KEY_YT_NAME)
            .putBoolean(KEY_YT_CONFLICT, false)
            .apply()
    }

    fun clearLegacyDataUserId() {
        prefs.edit().remove(KEY_LEGACY_USER_ID).apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_MYST_ID)
            .remove(KEY_MYST_NAME)
            .remove(KEY_YT_COOKIES)
            .remove(KEY_YT_SAPISID)
            .remove(KEY_YT_VISITOR)
            .remove(KEY_YT_SYNC)
            .remove(KEY_YT_ACCOUNT_ID)
            .remove(KEY_YT_NAME)
            .putBoolean(KEY_YT_CONFLICT, false)
            .putBoolean(KEY_TV_PAIRED, false)
            .apply()
    }

    fun clearDiscord() {
        prefs.edit().remove(KEY_DISCORD_TOKEN).remove(KEY_DISCORD_NAME).apply()
    }

    /**
     * Migração 0.1.0 -> 0.2.0. A chave antiga `session_type` guardava apenas
     * uma conta. Se era Myst, o usuário já é dono dos dados; se era YouTube,
     * o id antigo fica reservado para o convite "Trazer meus dados".
     */
    private fun migrateLegacySession() {
        if (prefs.getBoolean(KEY_DUAL_MIGRATED, false)) return
        val type = prefs.getString(KEY_TYPE, null)
        val userId = prefs.getLong(KEY_USER_ID, -1L)
        val name = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty()
        val cookies = prefs.getString(KEY_YT_COOKIES, null)
        val editor = prefs.edit()

        if (userId > 0L) {
            when (type) {
                TYPE_MYST -> {
                    if (!prefs.contains(KEY_MYST_ID)) {
                        editor.putLong(KEY_MYST_ID, userId)
                        editor.putString(KEY_MYST_NAME, name)
                    }
                    if (!cookies.isNullOrBlank()) {
                        if (!prefs.contains(KEY_YT_ACCOUNT_ID)) {
                            editor.putString(KEY_YT_ACCOUNT_ID, YouTubeAccount.accountId(cookies))
                        }
                        if (prefs.getString(KEY_YT_NAME, null).isNullOrBlank()) {
                            editor.putString(KEY_YT_NAME, "YouTube")
                        }
                    }
                }
                TYPE_YOUTUBE -> {
                    if (!prefs.contains(KEY_LEGACY_USER_ID)) {
                        editor.putLong(KEY_LEGACY_USER_ID, userId)
                    }
                    if (!cookies.isNullOrBlank()) {
                        if (!prefs.contains(KEY_YT_ACCOUNT_ID)) {
                            editor.putString(KEY_YT_ACCOUNT_ID, YouTubeAccount.accountId(cookies))
                        }
                        if (prefs.getString(KEY_YT_NAME, null).isNullOrBlank()) {
                            editor.putString(KEY_YT_NAME, name.ifBlank { "YouTube" })
                        }
                    }
                }
            }
        }

        editor.remove(KEY_TYPE)
            .remove(KEY_USER_ID)
            .remove(KEY_DISPLAY_NAME)
            .putBoolean(KEY_DUAL_MIGRATED, true)
            .apply()
    }

    private fun createPrefs(context: Context): SharedPreferences {
        createEncryptedPrefs(context)?.let { return it }

        // O arquivo criptografado pode ter sido restaurado por um backup sem a
        // chave do Keystore (que nunca sai do aparelho). Nesse caso ele jamais
        // abrirá de novo; apagar e recriar evita repetir o erro em toda abertura
        // e faz a sessão voltar a ser gravada no cofre.
        val sharedPrefsDir = File(context.applicationContext.filesDir.parentFile, "shared_prefs")
        val encryptedFile = File(sharedPrefsDir, "$FILE_NAME.xml")
        if (encryptedFile.exists()) {
            val deleted = runCatching { encryptedFile.delete() }
                .onFailure {
                    AppLog.w(
                        AppLog.TAG_AUTH,
                        "Não foi possível remover o cofre de sessão ilegível",
                        it,
                    )
                }
                .getOrDefault(false)
            if (deleted) {
                AppLog.w(
                    AppLog.TAG_AUTH,
                    "Cofre de sessão restaurado sem a chave do aparelho; recriando",
                )
            }
        }
        createEncryptedPrefs(context)?.let { return it }

        AppLog.w(
            AppLog.TAG_AUTH,
            "Cofre de sessão indisponível; usando arquivo privado sem criptografia",
        )
        return context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        AppLog.w(AppLog.TAG_AUTH, "Cofre de sessão indisponível", t)
        null
    }

    companion object {
        const val TYPE_MYST = "myst"
        const val TYPE_YOUTUBE = "youtube"

        private const val FILE_NAME = "myst_session"
        private const val FALLBACK_FILE = "myst_session_private"
        private const val KEY_TYPE = "session_type"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_MYST_ID = "myst_user_id"
        private const val KEY_MYST_NAME = "myst_display_name"
        private const val KEY_LEGACY_USER_ID = "legacy_data_user_id"
        private const val KEY_DUAL_MIGRATED = "dual_session_migrated"
        private const val KEY_YT_COOKIES = "yt_cookies"
        private const val KEY_YT_SAPISID = "yt_sapisid"
        private const val KEY_YT_VISITOR = "yt_visitor"
        private const val KEY_YT_SYNC = "yt_sync"
        private const val KEY_YT_ACCOUNT_ID = "yt_account_id"
        private const val KEY_YT_NAME = "yt_display_name"
        private const val KEY_YT_CONFLICT = "yt_conflict"
        private const val KEY_DISCORD_TOKEN = "discord_token"
        private const val KEY_DISCORD_NAME = "discord_name"
        private const val KEY_TV_PAIRED = "tv_paired"
    }
}
