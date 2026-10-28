package com.iskezis.myst.data.db

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
    ],
    indices = [Index(value = ["playlistId", "position"])],
)
data class PlaylistTrackEntity(
    val playlistId: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationMs: Long,
    val position: Int,
)

@Entity(
    tableName = "history",
    indices = [Index(value = ["userId", "playedAt"]), Index("videoId")],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val userId: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationMs: Long,
    val playedMs: Long,
    val playedAt: Long = System.currentTimeMillis(),
    val completed: Boolean = false,
)

@Entity(
    tableName = "artist_affinity",
    primaryKeys = ["userId", "artist"],
    indices = [Index("userId")],
)
data class ArtistAffinityEntity(
    val userId: Long,
    val artist: String,
    val score: Double,
    val updatedAt: Long = System.currentTimeMillis(),
)
