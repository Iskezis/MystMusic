package com.iskezis.myst.data.account

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.data.db.AppDatabase
import com.iskezis.myst.data.db.ArtistAffinityEntity
import com.iskezis.myst.data.db.HistoryEntity
import com.iskezis.myst.data.db.PlaylistEntity
import com.iskezis.myst.data.db.PlaylistTrackEntity
import com.iskezis.myst.data.db.UserEntity
import com.iskezis.myst.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Arquivo portátil da conta Myst.
 *
 * O usuário salva uma cópia fora do armazenamento privado do app (Downloads,
 * Drive, cartão SD). O login pode ler esse arquivo e restaurar perfil, avatar,
 * playlists, histórico e afinidade. A cópia interna é regravada sempre que o
 * Room muda, e a cópia externa também é atualizada quando o provedor concede
 * permissão persistente de escrita.
 *
 * O arquivo NÃO contém cookies do YouTube nem token do Discord. Essas são
 * sessões, não dados da conta, e não devem viajar em um JSON legível. O hash
 * PBKDF2 da senha vai no arquivo para que o login por usuário/senha continue
 * funcionando depois de uma restauração; quem tiver o arquivo pode entrar na
 * conta, por isso a UI avisa que ele deve ser guardado como uma chave.
 */
class MystAccountFileRepository(
    private val context: Context,
    private val database: AppDatabase,
) {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        prettyPrint = true
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val writeMutex = Mutex()
    private val _status = MutableStateFlow(AccountFileStatus())
    private var started = false
    private var observer: InvalidationTracker.Observer? = null

    val status: StateFlow<AccountFileStatus> = _status.asStateFlow()

    /** Liga a atualização automática da cópia interna e da cópia externa. */
    fun start(session: SessionManager) {
        if (started) return
        started = true
        scope.launch {
            // addObserver executa SQL para instalar os triggers do Room; precisa
            // rodar fora da thread principal, senão o Room recusa a abertura.
            runCatching {
                val created = object : InvalidationTracker.Observer(ACCOUNT_TABLES) {
                    override fun onInvalidated(tables: Set<String>) {
                        requestSync()
                    }
                }
                observer = created
                database.invalidationTracker.addObserver(created)
            }.onFailure {
                observer = null
                AppLog.failure(AppLog.TAG_AUTH, "Falha observando mudanças da conta Myst", it)
            }
            requestSync()
            while (isActive) {
                requests.receive()
                delay(SYNC_DEBOUNCE_MS)
                syncCurrent(session)
            }
        }
    }

    fun requestSync() {
        requests.trySend(Unit)
    }

    /** Caminho da cópia privada, usada pela UI para diagnóstico. */
    fun internalFile(userId: Long): File =
        File(File(context.filesDir, ACCOUNT_DIR), internalFileName(userId))

    /** Gera o JSON completo da conta. */
    suspend fun buildJson(userId: Long): String = withContext(Dispatchers.IO) {
        val user = database.userDao().findById(userId)
            ?: throw AccountFileException(AccountFileException.Reason.INVALID)
        val avatarBase64 = AvatarStore.readBase64(user.avatarPath)
        var coverBudget = MAX_COVER_BUDGET_BYTES
        var omittedCovers = 0
        var trackBudget = MAX_EXPORT_TRACKS_TOTAL
        var omittedTracks = 0

        val playlists = database.playlistDao().playlists(userId).map { playlist ->
            var coverBase64: String? = null
            val cover = readCover(playlist.coverUri)
            if (cover != null && cover.second <= coverBudget) {
                coverBase64 = cover.first
                coverBudget -= cover.second
            } else if (cover != null) {
                omittedCovers++
            }
            val allTracks = database.playlistDao().tracks(playlist.id)
            val exportedTracks = if (allTracks.size <= trackBudget) {
                allTracks
            } else {
                allTracks.take(trackBudget)
            }
            omittedTracks += allTracks.size - exportedTracks.size
            trackBudget -= exportedTracks.size
            MystAccountPlaylistBackup(
                name = playlist.name,
                coverUri = playlist.coverUri?.take(MAX_URL_LENGTH),
                coverBase64 = coverBase64,
                createdAt = playlist.createdAt,
                tracks = exportedTracks.map { track ->
                    MystAccountTrackBackup(
                        videoId = track.videoId,
                        title = track.title,
                        artist = track.artist,
                        thumbnailUrl = track.thumbnailUrl?.take(MAX_URL_LENGTH),
                        durationMs = track.durationMs,
                    )
                },
            )
        }

        val history = database.historyDao().recent(userId, MAX_EXPORT_HISTORY).map { item ->
            MystAccountHistoryBackup(
                videoId = item.videoId,
                title = item.title,
                artist = item.artist,
                thumbnailUrl = item.thumbnailUrl?.take(MAX_URL_LENGTH),
                durationMs = item.durationMs,
                playedMs = item.playedMs,
                playedAt = item.playedAt,
                completed = item.completed,
            )
        }

        val affinities = database.affinityDao().top(userId, MAX_EXPORT_AFFINITIES).map { item ->
            MystAccountAffinityBackup(
                artist = item.artist,
                score = item.score,
                updatedAt = item.updatedAt,
            )
        }

        val file = MystAccountBackup(
            appVersion = BuildConfig.VERSION_NAME,
            generatedAt = System.currentTimeMillis(),
            account = MystAccountProfileBackup(
                username = user.username,
                displayName = user.displayName.ifBlank { user.username },
                createdAt = user.createdAt,
                salt = user.salt,
                passwordHash = user.passwordHash,
                avatarBase64 = avatarBase64,
                youtubeAccountId = user.youtubeAccountId?.take(MAX_URL_LENGTH),
                youtubeDisplayName = user.youtubeDisplayName?.take(MAX_NAME_LENGTH),
            ),
            playlists = playlists,
            history = history,
            affinities = affinities,
        )
        if (omittedTracks > 0 || omittedCovers > 0) {
            AppLog.w(
                AppLog.TAG_AUTH,
                "Mystaccount.json limitado para caber no arquivo de importação: " +
                    "faixas omitidas=$omittedTracks capas omitidas=$omittedCovers",
            )
        }
        json.encodeToString(MystAccountBackup.serializer(), file)
    }

    /** Salva uma cópia escolhida pelo usuário e passa a mantê-la atualizada, se possível. */
    suspend fun saveToUri(userId: Long, uri: Uri): AccountFileSaveResult =
        withContext(Dispatchers.IO) {
            val text = buildJson(userId)
            writeInternal(userId, text)
            writeUri(uri, text)

            val persistent = takePersistablePermission(uri)
            val previous = externalUri(userId)
            if (previous != null && previous != uri.toString()) {
                releasePersistablePermission(previous)
            }
            val now = System.currentTimeMillis()
            val editor = prefs.edit().putLong(externalSavedKey(userId), now)
            if (persistent) {
                editor.putString(externalKey(userId), uri.toString())
            } else {
                editor.remove(externalKey(userId))
            }
            editor.apply()
            _status.value = AccountFileStatus(
                userId = userId,
                lastSyncAt = now,
                externalSaved = true,
                externalAutoSync = persistent,
                error = false,
                syncing = false,
            )
            AppLog.i(
                AppLog.TAG_AUTH,
                "Mystaccount.json salvo: usuário=$userId persistente=$persistent",
            )
            AccountFileSaveResult(autoSync = persistent)
        }

    /**
     * Lê e restaura o arquivo. Se a conta já existe no aparelho, os dados são
     * mesclados (nada é apagado); se não existe, a conta inteira é recriada.
     */
    suspend fun import(uri: Uri): RestoredAccount = withContext(Dispatchers.IO) {
        val text = readText(uri)
        val file = try {
            json.decodeFromString(MystAccountBackup.serializer(), text)
        } catch (t: Throwable) {
            throw AccountFileException(AccountFileException.Reason.CORRUPT, t)
        }
        if (file.app != APP_NAME || file.kind != KIND || file.schemaVersion < 1) {
            throw AccountFileException(AccountFileException.Reason.INVALID)
        }
        if (file.schemaVersion > SCHEMA_VERSION) {
            AppLog.w(
                AppLog.TAG_AUTH,
                "Mystaccount.json de versão futura (${file.schemaVersion}); lendo campos conhecidos",
            )
        }

        val username = file.account.username.trim().lowercase()
        if (!USERNAME_REGEX.matches(username) || username.length !in 3..20) {
            throw AccountFileException(AccountFileException.Reason.INVALID)
        }
        val displayName = file.account.displayName.trim().take(MAX_NAME_LENGTH)
            .ifBlank { username }
        val credentials = validatedCredentials(file.account.salt, file.account.passwordHash)
        val youtubeId = file.account.youtubeAccountId?.trim()?.take(MAX_URL_LENGTH)
            ?.takeIf { it.isNotBlank() }
        val youtubeName = file.account.youtubeDisplayName?.trim()?.take(MAX_NAME_LENGTH)
            ?.takeIf { it.isNotBlank() }

        val playlists = file.playlists.take(MAX_IMPORT_PLAYLISTS)
        if (file.playlists.size > MAX_IMPORT_PLAYLISTS) {
            AppLog.w(AppLog.TAG_AUTH, "Backup com playlists demais; importando as primeiras")
        }
        val history = file.history.take(MAX_IMPORT_HISTORY)
        if (file.history.size > MAX_IMPORT_HISTORY) {
            AppLog.w(AppLog.TAG_AUTH, "Backup com histórico demais; importando o mais recente")
        }
        val affinities = file.affinities.take(MAX_IMPORT_AFFINITIES)

        val now = System.currentTimeMillis()
        val createdAt = file.account.createdAt.takeIf { it in 1..now } ?: now
        var userId = 0L
        var created = false
        var importedTracks = 0
        var importedHistory = 0

        database.withTransaction {
            val dao = database.userDao()
            val existing = dao.findByUsername(username)
            if (existing == null) {
                userId = dao.insert(
                    UserEntity(
                        username = username,
                        displayName = displayName,
                        accountType = SessionManager.TYPE_MYST,
                        salt = credentials?.first,
                        passwordHash = credentials?.second,
                        youtubeAccountId = youtubeId,
                        youtubeDisplayName = youtubeName,
                        createdAt = createdAt,
                    ),
                )
                created = true
            } else {
                userId = existing.id
                dao.updateDisplayName(userId, displayName)
                if (credentials != null) {
                    dao.updatePassword(userId, credentials.first, credentials.second)
                }
                if (existing.youtubeAccountId.isNullOrBlank() && youtubeId != null) {
                    dao.updateYouTubeLink(userId, youtubeId, youtubeName)
                }
            }

            val playlistDao = database.playlistDao()
            val existingByName = playlistDao.playlists(userId)
                .associateBy { it.name.trim().lowercase() }
            playlists.forEach { backup ->
                val name = backup.name.trim().take(40).ifBlank { "Playlist restaurada" }
                val existingPlaylist = existingByName[name.lowercase()]
                val playlistId = existingPlaylist?.id ?: playlistDao.insert(
                    PlaylistEntity(
                        userId = userId,
                        name = name,
                        createdAt = backup.createdAt.takeIf { it in 1..now } ?: now,
                    ),
                )
                if (existingPlaylist == null) {
                    existingByName[name.lowercase()] = PlaylistEntity(
                        id = playlistId,
                        userId = userId,
                        name = name,
                    )
                }
                if (existingPlaylist == null || existingPlaylist.coverUri.isNullOrBlank()) {
                    val cover = saveCover(playlistId, backup)
                    if (cover != null) playlistDao.updateCover(playlistId, cover)
                }
                val knownTracks = playlistDao.tracks(playlistId)
                    .mapTo(mutableSetOf()) { it.videoId }
                var position = playlistDao.maxPosition(playlistId) + 1
                backup.tracks.take(MAX_IMPORT_TRACKS).forEach { track ->
                    val videoId = track.videoId.trim().take(MAX_ID_LENGTH)
                    if (videoId.isBlank() || !knownTracks.add(videoId)) return@forEach
                    val inserted = playlistDao.insertTrack(
                        PlaylistTrackEntity(
                            playlistId = playlistId,
                            videoId = videoId,
                            title = track.title.trim().take(MAX_TITLE_LENGTH),
                            artist = track.artist.trim().take(MAX_ARTIST_LENGTH),
                            thumbnailUrl = track.thumbnailUrl?.take(MAX_URL_LENGTH),
                            durationMs = track.durationMs.coerceAtLeast(0L),
                            position = position,
                        ),
                    )
                    if (inserted != -1L) {
                        position++
                        importedTracks++
                    }
                }
            }

            val historyDao = database.historyDao()
            val historyKeys = historyDao.keys(userId).toMutableSet()
            history.forEach { item ->
                val videoId = item.videoId.trim().take(MAX_ID_LENGTH)
                if (videoId.isBlank()) return@forEach
                val playedAt = item.playedAt.takeIf { it > 0L } ?: now
                val key = "$videoId|$playedAt"
                if (!historyKeys.add(key)) return@forEach
                historyDao.insert(
                    HistoryEntity(
                        userId = userId,
                        videoId = videoId,
                        title = item.title.trim().take(MAX_TITLE_LENGTH),
                        artist = item.artist.trim().take(MAX_ARTIST_LENGTH),
                        thumbnailUrl = item.thumbnailUrl?.take(MAX_URL_LENGTH),
                        durationMs = item.durationMs.coerceAtLeast(0L),
                        playedMs = item.playedMs.coerceAtLeast(0L),
                        playedAt = playedAt,
                        completed = item.completed,
                    ),
                )
                importedHistory++
            }

            val affinityDao = database.affinityDao()
            val knownAffinities = affinityDao.top(userId, MAX_IMPORT_AFFINITIES)
                .associateBy { it.artist }
            affinities.forEach { item ->
                val artist = item.artist.trim().take(MAX_ARTIST_LENGTH)
                if (artist.isBlank() || !item.score.isFinite()) return@forEach
                val previous = knownAffinities[artist]
                affinityDao.upsert(
                    ArtistAffinityEntity(
                        userId = userId,
                        artist = artist,
                        score = maxOf(previous?.score ?: 0.0, item.score.coerceAtLeast(0.0)),
                        updatedAt = maxOf(previous?.updatedAt ?: 0L, item.updatedAt),
                    ),
                )
            }
        }

        if (!file.account.avatarBase64.isNullOrBlank()) {
            AvatarStore.saveBase64(context, userId, file.account.avatarBase64)?.let { path ->
                database.userDao().updateAvatar(userId, path)
            }
        }

        val user = database.userDao().findById(userId)
            ?: throw AccountFileException(AccountFileException.Reason.CORRUPT)
        AppLog.i(
            AppLog.TAG_AUTH,
            "Mystaccount.json importado: usuário=$userId novo=$created " +
                "playlists=${playlists.size} faixas=$importedTracks histórico=$importedHistory",
        )
        RestoredAccount(
            user = user,
            created = created,
            playlists = playlists.size,
            tracks = importedTracks,
            history = importedHistory,
        )
    }

    private suspend fun syncCurrent(session: SessionManager) {
        val userId = session.mystUserId
        if (userId <= 0L) return
        _status.value = _status.value.copy(userId = userId, syncing = true)
        val text = try {
            buildJson(userId)
        } catch (t: Throwable) {
            AppLog.failure(AppLog.TAG_AUTH, "Falha montando Mystaccount.json", t)
            _status.value = _status.value.copy(syncing = false, error = true)
            return
        }
        val internalOk = runCatching { writeInternal(userId, text) }
            .onFailure { AppLog.failure(AppLog.TAG_AUTH, "Falha gravando cópia interna", it) }
            .isSuccess

        val uriText = externalUri(userId)
        val manualSaved = prefs.getLong(externalSavedKey(userId), 0L) > 0L
        var externalSaved = manualSaved
        var externalError = false
        if (uriText != null) {
            val result = runCatching { writeUri(Uri.parse(uriText), text) }
            result.onFailure {
                AppLog.failure(AppLog.TAG_AUTH, "Falha atualizando Mystaccount.json externo", it)
            }
            externalSaved = result.isSuccess || manualSaved
            externalError = result.isFailure
        }
        _status.value = AccountFileStatus(
            userId = userId,
            lastSyncAt = if (internalOk) System.currentTimeMillis() else _status.value.lastSyncAt,
            externalSaved = externalSaved,
            externalAutoSync = uriText != null,
            error = !internalOk || externalError,
            syncing = false,
        )
    }

    private fun readCover(coverUri: String?): Pair<String, Int>? {
        if (coverUri.isNullOrBlank() || !coverUri.startsWith("file:")) return null
        return runCatching {
            val path = Uri.parse(coverUri).path ?: return null
            val file = File(path)
            if (!file.isFile || file.length() <= 0L || file.length() > MAX_COVER_BYTES) return null
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP) to bytes.size
        }.onFailure {
            AppLog.failure(AppLog.TAG_AUTH, "Falha lendo capa para o Mystaccount.json", it)
        }.getOrNull()
    }

    private fun saveCover(playlistId: Long, backup: MystAccountPlaylistBackup): String? {
        val encoded = backup.coverBase64
        if (!encoded.isNullOrBlank() && encoded.length <= MAX_COVER_BYTES * 2) {
            val saved = runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) {
                    null
                } else {
                    val dir = File(context.filesDir, COVERS_DIR).apply { mkdirs() }
                    val target = File(dir, "$playlistId.jpg")
                    val temp = File(dir, "$playlistId.tmp")
                    temp.writeBytes(bytes)
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                    Uri.fromFile(target).toString()
                }
            }.onFailure {
                AppLog.failure(AppLog.TAG_AUTH, "Falha restaurando capa de playlist", it)
            }.getOrNull()
            if (saved != null) return saved
        }
        val uri = backup.coverUri?.trim()?.take(MAX_URL_LENGTH) ?: return null
        return when {
            uri.startsWith("https://") || uri.startsWith("http://") -> uri
            uri.startsWith("file:") && File(Uri.parse(uri).path.orEmpty()).isFile -> uri
            else -> null
        }
    }

    private fun validatedCredentials(salt: String?, hash: String?): Pair<String, String>? {
        if (salt.isNullOrBlank() || hash.isNullOrBlank()) return null
        val saltBytes = runCatching { Base64.decode(salt, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        if (saltBytes.size !in 8..64) return null
        val parts = hash.split(':')
        if (parts.size != 3) return null
        if (parts[0] != "PBKDF2WithHmacSHA256" && parts[0] != "PBKDF2WithHmacSHA1") return null
        val iterations = parts[1].toIntOrNull() ?: return null
        if (iterations !in 1..1_000_000) return null
        val hashBytes = runCatching { Base64.decode(parts[2], Base64.NO_WRAP) }.getOrNull()
            ?: return null
        if (hashBytes.size !in 16..128) return null
        return salt to hash
    }

    private suspend fun writeInternal(userId: Long, text: String) = writeMutex.withLock {
        val dir = File(context.filesDir, ACCOUNT_DIR).apply { mkdirs() }
        val target = File(dir, internalFileName(userId))
        val temp = File(dir, internalFileName(userId) + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private suspend fun writeUri(uri: Uri, text: String) = writeMutex.withLock {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val resolver = context.contentResolver
        val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: runCatching { resolver.openOutputStream(uri, "w") }.getOrNull()
            ?: throw IOException("Provedor não abriu o arquivo para escrita")
        stream.use { output ->
            output.write(bytes)
            output.flush()
        }
    }

    private fun readText(uri: Uri): String {
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (t: Throwable) {
            throw AccountFileException(AccountFileException.Reason.READ_FAILED, t)
        } ?: throw AccountFileException(AccountFileException.Reason.READ_FAILED)
        return try {
            stream.use { input -> String(input.readLimited(MAX_FILE_BYTES), Charsets.UTF_8) }
        } catch (t: AccountFileException) {
            throw t
        } catch (t: Throwable) {
            throw AccountFileException(AccountFileException.Reason.READ_FAILED, t)
        }
    }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw AccountFileException(AccountFileException.Reason.TOO_LARGE)
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun takePersistablePermission(uri: Uri): Boolean {
        val both = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val resolver = context.contentResolver
        val taken = runCatching {
            resolver.takePersistableUriPermission(uri, both)
            true
        }.getOrElse {
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                true
            }.getOrDefault(false)
        }
        if (!taken) return false
        return resolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isWritePermission
        }
    }

    private fun releasePersistablePermission(uriText: String) {
        val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return
        val both = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val resolver = context.contentResolver
        runCatching { resolver.releasePersistableUriPermission(uri, both) }
            .onFailure {
                runCatching {
                    resolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
    }

    private fun externalUri(userId: Long): String? =
        prefs.getString(externalKey(userId), null)?.takeIf { it.isNotBlank() }

    private fun externalKey(userId: Long): String = "external_uri_$userId"

    private fun externalSavedKey(userId: Long): String = "external_saved_$userId"

    private fun internalFileName(userId: Long): String = "Mystaccount-$userId.json"

    companion object {
        const val DEFAULT_FILE_NAME = "Mystaccount.json"

        private const val APP_NAME = "MystMusic"
        private const val KIND = "MystAccount"
        private const val SCHEMA_VERSION = 1
        private const val PREFS_NAME = "myst_account_file"
        private const val ACCOUNT_DIR = "account"
        private const val COVERS_DIR = "covers"
        private const val SYNC_DEBOUNCE_MS = 1_500L
        private const val MAX_FILE_BYTES = 32 * 1024 * 1024
        private const val MAX_COVER_BYTES = 2 * 1024 * 1024
        private const val MAX_COVER_BUDGET_BYTES = 4 * 1024 * 1024
        private const val MAX_EXPORT_HISTORY = 20_000
        private const val MAX_IMPORT_HISTORY = 20_000
        private const val MAX_EXPORT_AFFINITIES = 5_000
        private const val MAX_IMPORT_AFFINITIES = 5_000
        private const val MAX_EXPORT_TRACKS_TOTAL = 30_000
        private const val MAX_IMPORT_PLAYLISTS = 2_000
        private const val MAX_IMPORT_TRACKS = 10_000
        private const val MAX_ID_LENGTH = 128
        private const val MAX_TITLE_LENGTH = 300
        private const val MAX_ARTIST_LENGTH = 200
        private const val MAX_NAME_LENGTH = 60
        private const val MAX_URL_LENGTH = 2_000

        private val USERNAME_REGEX = Regex("[a-z0-9._-]+")

        private val ACCOUNT_TABLES = arrayOf(
            "users",
            "playlists",
            "playlist_tracks",
            "history",
            "artist_affinity",
        )
    }
}

data class AccountFileStatus(
    val userId: Long = 0L,
    val lastSyncAt: Long = 0L,
    val externalSaved: Boolean = false,
    val externalAutoSync: Boolean = false,
    val error: Boolean = false,
    val syncing: Boolean = false,
)

data class AccountFileSaveResult(val autoSync: Boolean)

data class RestoredAccount(
    val user: UserEntity,
    val created: Boolean,
    val playlists: Int,
    val tracks: Int,
    val history: Int,
)

class AccountFileException(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception(reason.name, cause) {
    enum class Reason {
        INVALID,
        CORRUPT,
        TOO_LARGE,
        READ_FAILED,
    }
}

@Serializable
data class MystAccountBackup(
    val app: String = "MystMusic",
    val kind: String = "MystAccount",
    val schemaVersion: Int = 1,
    val appVersion: String = "",
    val generatedAt: Long = 0L,
    val account: MystAccountProfileBackup,
    val playlists: List<MystAccountPlaylistBackup> = emptyList(),
    val history: List<MystAccountHistoryBackup> = emptyList(),
    val affinities: List<MystAccountAffinityBackup> = emptyList(),
    /** Campo reservado para dados que versões futuras adicionarem ao arquivo. */
    val extras: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class MystAccountProfileBackup(
    val username: String,
    val displayName: String,
    val createdAt: Long = 0L,
    val salt: String? = null,
    val passwordHash: String? = null,
    val avatarBase64: String? = null,
    val youtubeAccountId: String? = null,
    val youtubeDisplayName: String? = null,
)

@Serializable
data class MystAccountPlaylistBackup(
    val name: String,
    val coverUri: String? = null,
    val coverBase64: String? = null,
    val createdAt: Long = 0L,
    val tracks: List<MystAccountTrackBackup> = emptyList(),
)

@Serializable
data class MystAccountTrackBackup(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
)

@Serializable
data class MystAccountHistoryBackup(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
    val playedMs: Long = 0L,
    val playedAt: Long = 0L,
    val completed: Boolean = false,
)

@Serializable
data class MystAccountAffinityBackup(
    val artist: String,
    val score: Double = 0.0,
    val updatedAt: Long = 0L,
)
