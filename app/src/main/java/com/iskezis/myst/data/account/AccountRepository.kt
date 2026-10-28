package com.iskezis.myst.data.account

import android.content.Context
import android.webkit.CookieManager
import com.iskezis.myst.data.db.AppDatabase
import com.iskezis.myst.data.db.UserEntity
import com.iskezis.myst.data.youtube.InnerTubeClient
import com.iskezis.myst.util.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UsernameTakenException : Exception("username_taken")
class InvalidCredentialsException : Exception("invalid_credentials")

class AccountRepository(
    @Suppress("unused") private val context: Context,
    private val database: AppDatabase,
    private val session: SessionManager,
    private val youTube: InnerTubeClient,
) {

    suspend fun register(username: String, password: String): Long = withContext(Dispatchers.IO) {
        val normalized = username.trim().lowercase()
        if (database.userDao().findByUsername(normalized) != null) {
            throw UsernameTakenException()
        }
        val salt = PasswordHasher.newSalt()
        val hash = PasswordHasher.hash(password, salt)
        val id = database.userDao().insert(
            UserEntity(
                username = normalized,
                displayName = normalized,
                accountType = SessionManager.TYPE_MYST,
                salt = salt,
                passwordHash = hash,
            ),
        )
        session.saveMyst(id, normalized)
        id
    }

    suspend fun login(username: String, password: String): Long = withContext(Dispatchers.IO) {
        val normalized = username.trim().lowercase()
        val user = database.userDao().findByUsername(normalized)
        val salt = user?.salt
        val hash = user?.passwordHash
        if (user == null || salt == null || hash == null) {
            throw InvalidCredentialsException()
        }
        if (!PasswordHasher.verify(password, salt, hash)) {
            throw InvalidCredentialsException()
        }
        session.saveMyst(user.id, user.displayName.ifBlank { user.username })
        user.id
    }

    suspend fun loginYouTube(cookies: String, fallbackName: String): Long = withContext(Dispatchers.IO) {
        val sapisid = extractSapisid(cookies)
        val accountKey = sha256Hex(sapisid ?: cookies.take(512)).take(20)
        val username = "yt_$accountKey"
        var user = database.userDao().findByUsername(username)
        if (user == null) {
            val id = database.userDao().insert(
                UserEntity(
                    username = username,
                    displayName = fallbackName,
                    accountType = SessionManager.TYPE_YOUTUBE,
                ),
            )
            user = database.userDao().findById(id)
        }
        val savedUser = user ?: throw IllegalStateException("Não foi possível criar a conta do YouTube")
        session.saveYouTube(savedUser.id, fallbackName, cookies, sapisid)

        val name = runCatching { youTube.accountName() }.getOrNull()
        if (!name.isNullOrBlank()) {
            database.userDao().updateDisplayName(savedUser.id, name)
            session.session = SessionManager.Session(
                SessionManager.TYPE_YOUTUBE,
                savedUser.id,
                name,
            )
        }
        session.session?.userId ?: 0L
    }

    suspend fun currentUser(): UserEntity? = withContext(Dispatchers.IO) {
        val id = session.session?.userId ?: return@withContext null
        database.userDao().findById(id)
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        session.clearSession()
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    companion object {
        fun extractSapisid(cookies: String?): String? {
            if (cookies.isNullOrBlank()) return null
            val map = cookies.split(';').mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }.toMap()
            return map["SAPISID"] ?: map["__Secure-3PASISID"] ?: map["__Secure-1PASID"]
        }
    }
}
