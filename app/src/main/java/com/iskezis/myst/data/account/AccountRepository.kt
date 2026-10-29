package com.iskezis.myst.data.account

import android.content.Context
import android.webkit.CookieManager
import androidx.room.withTransaction
import com.iskezis.myst.data.db.AppDatabase
import com.iskezis.myst.data.db.UserEntity
import com.iskezis.myst.data.youtube.InnerTubeClient
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UsernameTakenException : Exception("username_taken")
class InvalidCredentialsException : Exception("invalid_credentials")

data class MystAuthResult(
    val userId: Long,
    val needsLegacyTransfer: Boolean,
    val youtubeConflict: Boolean,
)

data class AccountSnapshot(
    val mystReady: Boolean = false,
    val mystName: String = "",
    val mystAvatarPath: String? = null,
    val youtubeReady: Boolean = false,
    val youtubeName: String = "",
    val linkedYouTubeName: String = "",
    val youtubeConflict: Boolean = false,
    val needsLegacyTransfer: Boolean = false,
    val hasLegacyData: Boolean = false,
)

sealed interface YouTubeLinkResult {
    data class Linked(val name: String) : YouTubeLinkResult
    data class Conflict(val linkedName: String, val newName: String) : YouTubeLinkResult
}

private data class PendingYouTube(
    val accountId: String,
    val name: String,
    val cookies: String,
    val sapisid: String?,
)

class AccountRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val session: SessionManager,
    private val youTube: InnerTubeClient,
) {

    private var pendingYouTube: PendingYouTube? = null

    suspend fun register(
        username: String,
        password: String,
        avatarPath: String? = null,
    ): MystAuthResult = withContext(Dispatchers.IO) {
        val normalized = username.trim().lowercase()
        val displayName = username.trim().ifBlank { normalized }
        if (database.userDao().findByUsername(normalized) != null) {
            throw UsernameTakenException()
        }
        val salt = PasswordHasher.newSalt()
        val hash = PasswordHasher.hash(password, salt)
        val id = database.userDao().insert(
            UserEntity(
                username = normalized,
                displayName = displayName,
                accountType = SessionManager.TYPE_MYST,
                salt = salt,
                passwordHash = hash,
            ),
        )
        val savedAvatar = avatarPath?.let { AvatarStore.save(context, id, it) }
        if (savedAvatar != null) {
            database.userDao().updateAvatar(id, savedAvatar)
        }
        AvatarStore.cleanupTemp(context, avatarPath)
        session.saveMyst(id, displayName)
        AppLog.i(AppLog.TAG_AUTH, "Conta Myst criada: id=$id avatar=${savedAvatar != null}")
        completeMystAuth(
            database.userDao().findById(id) ?: throw IllegalStateException("Conta Myst não encontrada"),
        )
    }

    suspend fun login(username: String, password: String): MystAuthResult =
        withContext(Dispatchers.IO) {
            val normalized = username.trim().lowercase()
            val user = database.userDao().findByUsername(normalized)
            val salt = user?.salt
            val hash = user?.passwordHash
            if (user == null || salt == null || hash == null) {
                AppLog.w(AppLog.TAG_AUTH, "Login Myst: usuário inexistente $normalized")
                throw InvalidCredentialsException()
            }
            if (!PasswordHasher.verify(password, salt, hash)) {
                AppLog.w(AppLog.TAG_AUTH, "Login Myst: senha inválida para $normalized")
                throw InvalidCredentialsException()
            }
            session.saveMyst(user.id, user.displayName.ifBlank { user.username })
            AppLog.i(AppLog.TAG_AUTH, "Login Myst: sessão salva para id=${user.id}")
            completeMystAuth(user)
        }

    /**
     * Valida os cookies do YouTube antes de trocar o vínculo. Se a conta Myst
     * já aponta para outro YouTube, devolve Conflict sem alterar a sessão.
     */
    suspend fun prepareYouTubeLink(
        cookies: String,
        fallbackName: String,
    ): YouTubeLinkResult = withContext(Dispatchers.IO) {
        val sapisid = YouTubeAccount.extractSapisid(cookies)
        val accountId = YouTubeAccount.accountId(cookies)
        val previous = session.captureYouTube()

        // O InnerTube usa a sessão para ler o nome; a conta nova fica ativa só
        // durante a consulta e é restaurada se houver conflito de vínculo.
        session.saveYouTube(accountId, fallbackName, cookies, sapisid)
        val name = runCatching { youTube.accountName() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackName

        val mystId = session.mystUserId
        val user = if (mystId > 0L) database.userDao().findById(mystId) else null
        val linkedId = user?.youtubeAccountId
        if (!linkedId.isNullOrBlank() && linkedId != accountId) {
            session.restoreYouTube(previous)
            // A conta nova fica pendente: "Trocar vínculo" a confirma, "Usar a
            // vinculada" descarta e mantém a sessão anterior intacta.
            pendingYouTube = PendingYouTube(accountId, name, cookies, sapisid)
            return@withContext YouTubeLinkResult.Conflict(
                linkedName = user.youtubeDisplayName
                    ?.takeIf { it.isNotBlank() }
                    ?: fallbackName,
                newName = name,
            )
        }

        val pending = PendingYouTube(accountId, name, cookies, sapisid)
        commitYouTube(pending)
        if (user != null) {
            database.userDao().updateYouTubeLink(user.id, accountId, name)
        }
        AppLog.i(
            AppLog.TAG_AUTH,
            "YouTube vinculado: conta=$accountId nome=$name cookies=${cookies.length} bytes",
        )
        YouTubeLinkResult.Linked(name)
    }

    /**
     * O player pede o login do YouTube quando a reprodução exige conta. Ali a
     * escolha da conta foi explícita, então um vínculo diferente é trocado
     * depois do aviso automático, sem deixar a reprodução em um beco sem saída.
     */
    suspend fun linkYouTube(cookies: String, fallbackName: String): String =
        withContext(Dispatchers.IO) {
            when (val result = prepareYouTubeLink(cookies, fallbackName)) {
                is YouTubeLinkResult.Linked -> result.name
                is YouTubeLinkResult.Conflict -> {
                    val name = confirmYouTubeLink() ?: result.newName
                    AppLog.i(
                        AppLog.TAG_AUTH,
                        "Vínculo do YouTube trocado pelo player: ${result.linkedName} -> $name",
                    )
                    name
                }
            }
        }

    suspend fun confirmYouTubeLink(): String? = withContext(Dispatchers.IO) {
        val pending = pendingYouTube ?: return@withContext null
        commitYouTube(pending)
        val mystId = session.mystUserId
        if (mystId > 0L) {
            database.userDao().updateYouTubeLink(mystId, pending.accountId, pending.name)
        }
        pendingYouTube = null
        AppLog.i(AppLog.TAG_AUTH, "Vínculo do YouTube trocado: ${pending.accountId}")
        pending.name
    }

    fun cancelYouTubeLink() {
        pendingYouTube = null
    }

    fun hasPendingYouTube(): Boolean = pendingYouTube != null

    /** Confirma o YouTube atual quando o conflito apareceu ao entrar no Myst. */
    suspend fun confirmMystYouTubeLink() = withContext(Dispatchers.IO) {
        val mystId = session.mystUserId
        val accountId = session.youtubeAccountId
        if (mystId > 0L && accountId != null) {
            database.userDao().updateYouTubeLink(
                mystId,
                accountId,
                session.youtubeDisplayName.ifBlank { "YouTube" },
            )
        }
        session.youtubeConflict = false
        AppLog.i(AppLog.TAG_AUTH, "Conflito de YouTube resolvido mantendo a conta atual")
    }

    /**
     * "Usar a conta vinculada": se o YouTube ativo já é o vinculado, mantém a
     * sessão; caso contrário, sai dele para a pessoa entrar com a conta certa.
     * Devolve true quando foi necessário desconectar o YouTube atual.
     */
    suspend fun useLinkedYouTube(): Boolean = withContext(Dispatchers.IO) {
        val mystId = session.mystUserId
        val linkedId = if (mystId > 0L) {
            database.userDao().findById(mystId)?.youtubeAccountId
        } else {
            null
        }
        val currentId = session.youtubeAccountId
        pendingYouTube = null
        session.youtubeConflict = false
        if (!linkedId.isNullOrBlank() && linkedId == currentId) {
            AppLog.i(AppLog.TAG_AUTH, "Conflito resolvido mantendo o YouTube vinculado")
            false
        } else {
            session.clearYouTube()
            clearWebViewCookies()
            AppLog.i(AppLog.TAG_AUTH, "YouTube atual desconectado para entrar com a conta vinculada")
            true
        }
    }

    suspend fun currentUser(): UserEntity? = withContext(Dispatchers.IO) {
        val id = session.mystUserId
        if (id <= 0L) null else database.userDao().findById(id)
    }

    /** Contas Myst salvas no aparelho, usadas para reencontrar o perfil após restaurar um backup. */
    suspend fun localAccounts(): List<UserEntity> = withContext(Dispatchers.IO) {
        database.userDao().localAccounts()
    }

    /**
     * Confere se a sessão ainda aponta para uma conta existente. Uma sessão
     * órfã acontece quando o banco é restaurado sem a sessão (ou vice-versa);
     * limpar só a identidade Myst permite entrar de novo sem apagar playlists.
     * Em caso de falha temporária do banco, mantém a sessão para não deslogar
     * o usuário por engano.
     */
    suspend fun reconcileMystSession(): Boolean = withContext(Dispatchers.IO) {
        val id = session.mystUserId
        if (id <= 0L) return@withContext false
        val user = try {
            database.userDao().findById(id)
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_AUTH, "Falha lendo a conta Myst da sessão", t)
            return@withContext true
        }
        if (user == null) {
            AppLog.w(
                AppLog.TAG_AUTH,
                "Sessão Myst órfã (id=$id); limpando a identidade para permitir novo login",
            )
            session.clearMyst()
            session.tvPaired = false
            return@withContext false
        }
        val name = user.displayName.ifBlank { user.username }
        if (name != session.mystDisplayName) {
            session.saveMyst(user.id, name)
        }
        true
    }

    suspend fun snapshot(): AccountSnapshot = withContext(Dispatchers.IO) {
        val savedId = session.mystUserId
        val user = savedId
            .takeIf { it > 0L }
            ?.let { database.userDao().findById(it) }
        if (savedId > 0L && user == null) {
            AppLog.w(
                AppLog.TAG_AUTH,
                "Sessão Myst aponta para a conta $savedId, que não existe; limpando",
            )
            session.clearMyst()
            session.tvPaired = false
        }
        val name = user?.displayName?.ifBlank { user.username }
            ?: session.mystDisplayName
        AccountSnapshot(
            mystReady = session.hasMyst && user != null,
            mystName = name,
            mystAvatarPath = user?.avatarPath,
            youtubeReady = session.hasYouTube,
            youtubeName = session.youtubeDisplayName,
            linkedYouTubeName = user?.youtubeDisplayName.orEmpty(),
            youtubeConflict = session.youtubeConflict,
            needsLegacyTransfer = session.hasLegacyData,
            hasLegacyData = session.legacyDataUserId > 0L,
        )
    }

    suspend fun updateAvatar(userId: Long, sourcePath: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val saved = AvatarStore.save(context, userId, sourcePath)
                if (saved != null) {
                    database.userDao().updateAvatar(userId, saved)
                    AppLog.i(AppLog.TAG_UI, "Avatar da conta atualizado: $saved")
                }
                saved
            } finally {
                AvatarStore.cleanupTemp(context, sourcePath)
            }
        }

    /**
     * Reatribui playlists, histórico e afinidade do usuário YouTube antigo
     * para a conta Myst em uma única transação. Se algo falhar, nada muda.
     */
    suspend fun transferLegacyData(): Boolean = withContext(Dispatchers.IO) {
        val from = session.legacyDataUserId
        val to = session.mystUserId
        if (to <= 0L) return@withContext false
        if (from <= 0L || from == to) {
            session.clearLegacyDataUserId()
            return@withContext true
        }
        runCatching {
            database.withTransaction {
                database.playlistDao().reassignUser(from, to)
                database.historyDao().reassignUser(from, to)
                database.affinityDao().copyToUser(from, to)
                database.affinityDao().deleteUser(from)
            }
            session.clearLegacyDataUserId()
            AppLog.i(AppLog.TAG_DB, "Dados da versão anterior transferidos: $from -> $to")
            true
        }.getOrElse { t ->
            AppLog.failure(AppLog.TAG_DB, "Falha transferindo dados da versão anterior", t)
            false
        }
    }

    /** "Agora não": os dados antigos ficam no lugar e o convite não reaparece. */
    fun skipLegacyTransfer() {
        AppLog.i(AppLog.TAG_AUTH, "Usuário optou por não transferir os dados antigos")
        session.clearLegacyDataUserId()
    }

    suspend fun logoutMyst() = withContext(Dispatchers.IO) {
        AppLog.i(AppLog.TAG_AUTH, "Logout da conta Myst")
        session.clearMyst()
        session.tvPaired = false
    }

    suspend fun logoutYouTube() = withContext(Dispatchers.IO) {
        AppLog.i(AppLog.TAG_AUTH, "Logout da conta YouTube")
        session.clearYouTube()
        session.tvPaired = false
        clearWebViewCookies()
    }

    suspend fun logoutAll() = withContext(Dispatchers.IO) {
        AppLog.i(AppLog.TAG_AUTH, "Logout das duas contas")
        session.clearSession()
        clearWebViewCookies()
    }

    /** Mantido para chamadas antigas; desloga as duas contas. */
    suspend fun logout() = logoutAll()

    private fun commitYouTube(pending: PendingYouTube) {
        session.saveYouTube(
            accountId = pending.accountId,
            name = pending.name,
            cookies = pending.cookies,
            sapisid = pending.sapisid,
        )
    }

    private suspend fun completeMystAuth(user: UserEntity): MystAuthResult {
        // Um vínculo pendente pertence ao fluxo anterior de login do YouTube.
        // Ao autenticar no Myst, a decisão volta a ser do conflito atual.
        pendingYouTube = null
        var conflict = false
        if (session.hasYouTube) {
            val current = user.youtubeAccountId
            val accountId = session.youtubeAccountId
            when {
                current.isNullOrBlank() && accountId != null -> {
                    // Conta Myst nova: o YouTube que já estava logado passa a
                    // ser o vínculo oficial desta conta.
                    runCatching {
                        database.userDao().updateYouTubeLink(
                            user.id,
                            accountId,
                            session.youtubeDisplayName.ifBlank { "YouTube" },
                        )
                    }.onFailure {
                        AppLog.failure(AppLog.TAG_AUTH, "Falha gravando vínculo do YouTube", it)
                    }
                }
                accountId != null && current != accountId -> conflict = true
            }
        }
        session.youtubeConflict = conflict
        return MystAuthResult(
            userId = user.id,
            needsLegacyTransfer = session.hasLegacyData,
            youtubeConflict = conflict,
        )
    }

    private fun clearWebViewCookies() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }.onFailure {
            AppLog.failure(AppLog.TAG_AUTH, "Falha limpando cookies do WebView", it)
        }
    }

    companion object {
        fun extractSapisid(cookies: String?): String? = YouTubeAccount.extractSapisid(cookies)
    }
}
