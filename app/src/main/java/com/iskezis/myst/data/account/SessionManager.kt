package com.iskezis.myst.data.account

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Sessão do app. Quando o cofre do Android está disponível, tudo fica em
 * EncryptedSharedPreferences; se o keystore falhar em algum aparelho, cai para
 * um arquivo privado comum para que o app continue abrindo sem crashar.
 */
class SessionManager(context: Context) {

    data class Session(
        val type: String,
        val userId: Long,
        val displayName: String,
    )

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    var session: Session?
        get() {
            val type = prefs.getString(KEY_TYPE, null) ?: return null
            val userId = prefs.getLong(KEY_USER_ID, -1L)
            if (userId <= 0L) return null
            return Session(type, userId, prefs.getString(KEY_DISPLAY_NAME, "") ?: "")
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_TYPE)
                    remove(KEY_USER_ID)
                    remove(KEY_DISPLAY_NAME)
                } else {
                    putString(KEY_TYPE, value.type)
                    putLong(KEY_USER_ID, value.userId)
                    putString(KEY_DISPLAY_NAME, value.displayName)
                }
            }.apply()
        }

    val isLoggedIn: Boolean get() = session != null

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

    var discordToken: String?
        get() = prefs.getString(KEY_DISCORD_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_DISCORD_TOKEN, value).apply()

    var discordName: String?
        get() = prefs.getString(KEY_DISCORD_NAME, null)
        set(value) = prefs.edit().putString(KEY_DISCORD_NAME, value).apply()

    var tvPaired: Boolean
        get() = prefs.getBoolean(KEY_TV_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_TV_PAIRED, value).apply()

    fun saveMyst(userId: Long, username: String) {
        session = Session(TYPE_MYST, userId, username)
    }

    fun saveYouTube(userId: Long, name: String, cookies: String, sapisid: String?) {
        youtubeCookies = cookies
        youtubeSapisid = sapisid
        youtubeVisitorData = null
        youtubeDataSyncId = null
        session = Session(TYPE_YOUTUBE, userId, name)
    }

    fun clearSession() {
        session = null
        youtubeCookies = null
        youtubeSapisid = null
        youtubeVisitorData = null
        youtubeDataSyncId = null
        tvPaired = false
    }

    fun clearDiscord() {
        discordToken = null
        discordName = null
    }

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
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
            context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
        }
    }

    companion object {
        const val TYPE_MYST = "myst"
        const val TYPE_YOUTUBE = "youtube"

        private const val FILE_NAME = "myst_session"
        private const val FALLBACK_FILE = "myst_session_private"
        private const val KEY_TYPE = "session_type"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_YT_COOKIES = "yt_cookies"
        private const val KEY_YT_SAPISID = "yt_sapisid"
        private const val KEY_YT_VISITOR = "yt_visitor"
        private const val KEY_YT_SYNC = "yt_sync"
        private const val KEY_DISCORD_TOKEN = "discord_token"
        private const val KEY_DISCORD_NAME = "discord_name"
        private const val KEY_TV_PAIRED = "tv_paired"
    }
}
