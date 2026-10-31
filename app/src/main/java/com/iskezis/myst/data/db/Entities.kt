package com.iskezis.myst.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "users",
    indices = [Index(value = ["username"], unique = true)],
)
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val username: String,
    val displayName: String,
    val accountType: String,
    val salt: String? = null,
    val passwordHash: String? = null,
    val avatarUrl: String? = null,
    val avatarPath: String? = null,
    val youtubeAccountId: String? = null,
    val youtubeDisplayName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlists",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("userId")],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val userId: Long,
    val name: String,
    val coverUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "videoId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("userId"), Index(value = ["playlistId", "position"])],
)
data class PlaylistTrackEntity(
    val userId: Long,
    val playlistId: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    @ColumnInfo(defaultValue = "''") val album: String = "",
    val thumbnailUrl: String?,
    val durationMs: Long,
    val position: Int,
)

@Entity(
    tableName = "history",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["userId", "playedAt"]), Index("videoId")],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val userId: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    @ColumnInfo(defaultValue = "''") val album: String = "",
    val thumbnailUrl: String?,
    val durationMs: Long,
    val playedMs: Long,
    val playedAt: Long = System.currentTimeMillis(),
    val completed: Boolean = false,
)

@Entity(
    tableName = "artist_affinity",
    primaryKeys = ["userId", "artist"],
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("userId")],
)
data class ArtistAffinityEntity(
    val userId: Long,
    val artist: String,
    val score: Double,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Lápide de playlist apagada ou renomeada. A sincronização celular↔TV usa o
 * nome normalizado para não ressuscitar uma playlist que o usuário removeu em
 * um dos aparelhos.
 */
@Entity(
    tableName = "pair_tombstones",
    primaryKeys = ["userId", "key"],
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("userId")],
)
data class PairTombstoneEntity(
    val userId: Long,
    val key: String,
    val deletedAt: Long,
)

/**
 * Playlist da biblioteca do YouTube Music. O cache é somente leitura: o app
 * nunca escreve na conta do YouTube. `ytId` guarda o browseId devolvido pela
 * biblioteca (por exemplo, `VLPL...`) e `VLLM` identifica "Músicas curtidas".
 */
@Entity(
    tableName = "yt_playlists",
    primaryKeys = ["userId", "ytId"],
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("userId")],
)
data class YtPlaylistEntity(
    val userId: Long,
    val ytId: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val trackCount: Int = 0,
    val liked: Boolean = false,
    val syncedAt: Long,
)

/** Faixas de uma playlist do YouTube Music, carregadas sob demanda. */
@Entity(
    tableName = "yt_playlist_tracks",
    primaryKeys = ["userId", "ytId", "videoId"],
    foreignKeys = [
        ForeignKey(
            entity = YtPlaylistEntity::class,
            parentColumns = ["userId", "ytId"],
            childColumns = ["userId", "ytId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["userId", "ytId", "position"])],
)
data class YtPlaylistTrackEntity(
    val userId: Long,
    val ytId: String,
    val videoId: String,
    val title: String,
    val artist: String,
    @ColumnInfo(defaultValue = "''") val album: String = "",
    val thumbnailUrl: String?,
    val durationMs: Long,
    val position: Int,
    val syncedAt: Long,
)

/**
 * Histórico recente lido da conta do YouTube Music. `playedAt` usa a ordem
 * relativa devolvida pela API (o serviço só informa o dia), para mesclar com o
 * histórico local sem perder a ordenação.
 */
@Entity(
    tableName = "yt_history",
    primaryKeys = ["userId", "videoId"],
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["userId", "playedAt"])],
)
data class YtHistoryEntity(
    val userId: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    @ColumnInfo(defaultValue = "''") val album: String = "",
    val thumbnailUrl: String?,
    val durationMs: Long,
    val playedAt: Long,
)

/** Momento da última sincronização de uma seção do YouTube Music. */
@Entity(
    tableName = "yt_sync_state",
    primaryKeys = ["userId", "section"],
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("userId")],
)
data class YtSyncEntity(
    val userId: Long,
    val section: String,
    val syncedAt: Long,
)

/**
 * Download offline de uma faixa. O arquivo pode estar na pasta pública
 * `MystMusic/MystDownload` (quando o usuário concedeu acesso) ou no diretório
 * externo privado do app. `path` guarda sempre o caminho real do arquivo para
 * que uma mudança de permissão não torne downloads antigos invisíveis.
 *
 * A chave é por conta Myst: trocar de conta mostra somente as faixas dela,
 * mas o mesmo arquivo pode ser compartilhado por duas contas no disco. A
 * remoção só apaga o arquivo quando nenhuma outra conta ainda o referencia.
 */
@Entity(
    tableName = "downloads",
    primaryKeys = ["userId", "videoId"],
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["userId", "status"]),
        Index(value = ["userId", "addedAt"]),
    ],
)
data class DownloadEntity(
    val userId: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
    /** QUEUED, DOWNLOADING, DONE ou FAILED. */
    val status: String,
    val bytes: Long = 0L,
    val totalBytes: Long = 0L,
    val path: String? = null,
    val coverPath: String? = null,
    val error: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Tempo realmente ouvido em uma faixa, base das Estatísticas. O evento é
 * gravado ao trocar de música, terminar, pausar por mais de 30 s ou fechar o
 * serviço. `playedMs` acumula apenas o tempo em reprodução (pausas e saltos
 * não contam). A música passa a contar nas Estatísticas depois dos primeiros
 * 10 s ouvidos, inclusive quando a faixa é trocada no meio; o tempo gravado é
 * o trecho exato escutado, não a duração inteira.
 */
@Entity(
    tableName = "play_events",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["userId", "startedAt"]),
        Index(value = ["userId", "artist"]),
    ],
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val userId: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val thumbnailUrl: String?,
    val startedAt: Long,
    val playedMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val source: String,
)
