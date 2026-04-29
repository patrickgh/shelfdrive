package io.audiobookshelf.aaos.catalog.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "libraries")
data class LibraryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mediaType: String,
    val displayOrder: Int,
)

@Entity(
    tableName = "books",
    foreignKeys = [
        ForeignKey(
            entity = LibraryEntity::class,
            parentColumns = ["id"],
            childColumns = ["libraryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("libraryId"), Index("sortTitle"), Index("authorDisplay")],
)
data class BookEntity(
    @PrimaryKey val id: String,
    val libraryId: String,
    val title: String,
    val sortTitle: String,
    val subtitle: String?,
    val description: String?,
    val coverPath: String?,
    val durationMs: Long?,
    val authorDisplay: String?,
    val addedAt: Long?,
    val updatedAt: Long,
    val isPlayable: Boolean,
)

@Entity(tableName = "authors", indices = [Index("sortName")])
data class AuthorEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortName: String,
    val imagePath: String?,
    val numBooks: Int,
)

@Entity(
    tableName = "book_author_cross_refs",
    primaryKeys = ["bookId", "authorId"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AuthorEntity::class,
            parentColumns = ["id"],
            childColumns = ["authorId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("authorId")],
)
data class BookAuthorCrossRef(
    val bookId: String,
    val authorId: String,
)

@Entity(
    tableName = "media_progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("lastUpdateAt"), Index("isFinished")],
)
data class MediaProgressEntity(
    @PrimaryKey val bookId: String,
    val currentTimeMs: Long,
    val durationMs: Long?,
    val progressFraction: Double?,
    val isFinished: Boolean,
    val hideFromContinueListening: Boolean,
    val lastUpdateAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val status: String,
    val lastFullSyncAt: Long?,
    val lastDeltaSyncAt: Long?,
    val lastSyncError: String?,
    val libraryCount: Int,
    val bookCount: Int,
    val authorCount: Int,
    val serverVersion: String?,
)
