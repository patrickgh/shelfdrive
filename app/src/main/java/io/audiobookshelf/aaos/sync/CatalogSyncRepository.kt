package io.audiobookshelf.aaos.sync

import android.util.Log
import androidx.room.withTransaction
import io.audiobookshelf.aaos.absapi.AudiobookshelfApiClient
import io.audiobookshelf.aaos.auth.AuthenticatedRequestRunner
import io.audiobookshelf.aaos.auth.AuthenticationRequiredException
import io.audiobookshelf.aaos.auth.AuthRepository
import io.audiobookshelf.aaos.auth.AuthStorage
import io.audiobookshelf.aaos.catalog.persistence.AuthorEntity
import io.audiobookshelf.aaos.catalog.persistence.BookAuthorCrossRef
import io.audiobookshelf.aaos.catalog.persistence.BookEntity
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import io.audiobookshelf.aaos.catalog.persistence.LibraryEntity
import io.audiobookshelf.aaos.catalog.persistence.SyncStateEntity
import io.audiobookshelf.aaos.status.UserVisibleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class CatalogSyncRepository(
    private val database: CatalogDatabase,
    private val authRepository: AuthRepository,
    private val authStorage: AuthStorage,
    private val apiClient: AudiobookshelfApiClient = AudiobookshelfApiClient(),
) {
    private val authenticatedRequestRunner = AuthenticatedRequestRunner(authStorage, authRepository)

    suspend fun loadSnapshot(): SyncSnapshot = withContext(Dispatchers.IO) {
        database.syncStateDao().get()?.toSnapshot() ?: SyncSnapshot(status = SyncStatus.IDLE)
    }

    suspend fun clearCatalog(): SyncSnapshot = withContext(Dispatchers.IO) {
        database.withTransaction {
            database.mediaProgressDao().clearAll()
            database.bookAuthorCrossRefDao().clearAll()
            database.bookDao().clearAll()
            database.authorDao().clearAll()
            database.libraryDao().clearAll()
        }
        val idle = SyncSnapshot(status = SyncStatus.IDLE)
        persistSyncState(idle)
        idle
    }

    suspend fun syncNow(): SyncSnapshot = withContext(Dispatchers.IO) {
        val previous = loadSnapshot()
        val runningSnapshot = previous.copy(status = SyncStatus.RUNNING, message = "Synchronisierung läuft.")
        persistSyncState(runningSnapshot)

        return@withContext try {
            authenticatedRequestRunner.execute { context ->
                performSync(
                    baseUrl = context.baseUrl,
                    accessToken = context.accessToken,
                    serverVersion = previous.serverVersion,
                )
            }
        } catch (exception: IOException) {
            Log.w(TAG, "Sync failed due to network or parsing error.", exception)
            if (exception !is AuthenticationRequiredException && previous.lastSyncedAt != null) {
                val staleButUsable = previous.copy(
                    status = if (previous.status == SyncStatus.FAILED) SyncStatus.SUCCESS else previous.status,
                    message = UserVisibleStatus.SERVER_UNREACHABLE,
                    serverVersion = previous.serverVersion,
                )
                persistSyncState(staleButUsable)
                return@withContext staleButUsable
            }
            val failed = previous.copy(
                status = SyncStatus.FAILED,
                message = when (exception) {
                    is AuthenticationRequiredException -> UserVisibleStatus.SESSION_EXPIRED
                    else -> UserVisibleStatus.CATALOG_SYNC_FAILED
                },
                serverVersion = previous.serverVersion,
            )
            persistSyncState(failed)
            failed
        }
    }

    suspend fun syncIfStale(
        maxCacheAgeMs: Long = SyncFreshnessPolicy.DEFAULT_MAX_CACHE_AGE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): SyncSnapshot {
        val snapshot = loadSnapshot()
        return if (SyncFreshnessPolicy.shouldRefresh(snapshot, nowMs, maxCacheAgeMs)) {
            syncNow()
        } else {
            snapshot
        }
    }

    private suspend fun performSync(
        baseUrl: String,
        accessToken: String,
        serverVersion: String?,
    ): SyncSnapshot {
        val libraries = apiClient.getLibraries(baseUrl, accessToken)
            .filter { it.mediaType.equals("book", ignoreCase = true) }

        val libraryEntities = libraries.mapIndexed { index, library ->
            LibraryEntity(
                id = library.id,
                name = library.name,
                mediaType = library.mediaType,
                displayOrder = index,
            )
        }

        val authorMap = linkedMapOf<String, AuthorEntity>()
        val books = mutableListOf<BookEntity>()
        val crossRefs = linkedSetOf<BookAuthorCrossRef>()

        libraries.forEach { library ->
            val libraryAuthors = apiClient.getLibraryAuthors(baseUrl, accessToken, library.id)
            libraryAuthors.forEach { author ->
                val current = authorMap[author.id]
                authorMap[author.id] = if (current == null) {
                    AuthorEntity(
                        id = author.id,
                        name = author.name,
                        sortName = author.sortName,
                        imagePath = author.imagePath,
                        numBooks = author.numBooks,
                    )
                } else {
                    current.copy(numBooks = current.numBooks + author.numBooks)
                }
            }

            val libraryBooks = apiClient.getLibraryItems(baseUrl, accessToken, library.id)
            libraryBooks.forEach { book ->
                books += BookEntity(
                    id = book.id,
                    libraryId = library.id,
                    title = book.title,
                    sortTitle = book.sortTitle,
                    subtitle = book.subtitle,
                    description = book.description,
                    coverPath = book.coverPath,
                    durationMs = book.durationMs?.takeIf { it > 0L },
                    authorDisplay = book.authorDisplay,
                    addedAt = book.addedAt,
                    updatedAt = System.currentTimeMillis(),
                    isPlayable = book.isPlayable,
                )

                val resolvedAuthors = if (book.authors.isNotEmpty()) {
                    book.authors
                } else {
                    resolveAuthorsFromDisplay(book.authorDisplay, authorMap.values.toList())
                }

                resolvedAuthors.forEach { author ->
                    val knownAuthor = authorMap[author.id]
                    if (knownAuthor == null) {
                        authorMap[author.id] = AuthorEntity(
                            id = author.id,
                            name = author.name,
                            sortName = author.sortName,
                            imagePath = author.imagePath,
                            numBooks = author.numBooks,
                        )
                    }
                    crossRefs += BookAuthorCrossRef(bookId = book.id, authorId = author.id)
                }
            }
        }

        database.withTransaction {
            database.bookAuthorCrossRefDao().clearAll()
            database.bookDao().clearAll()
            database.authorDao().clearAll()
            database.libraryDao().clearAll()

            database.libraryDao().upsertAll(libraryEntities)
            database.bookDao().upsertAll(books)
            database.authorDao().upsertAll(authorMap.values.sortedBy { it.sortName })
            database.bookAuthorCrossRefDao().upsertAll(crossRefs.toList())
        }

        val success = SyncSnapshot(
            status = SyncStatus.SUCCESS,
            libraryCount = libraryEntities.size,
            bookCount = books.size,
            authorCount = authorMap.size,
            lastSyncedAt = System.currentTimeMillis(),
            serverVersion = serverVersion,
            message = "Synchronisierung abgeschlossen.",
        )
        persistSyncState(success)
        return success
    }

    private suspend fun persistSyncState(snapshot: SyncSnapshot) {
        database.syncStateDao().upsert(
            SyncStateEntity(
                status = snapshot.status.name,
                lastFullSyncAt = snapshot.lastSyncedAt,
                lastDeltaSyncAt = snapshot.lastSyncedAt,
                lastSyncError = snapshot.message.takeIf { snapshot.status == SyncStatus.FAILED },
                libraryCount = snapshot.libraryCount,
                bookCount = snapshot.bookCount,
                authorCount = snapshot.authorCount,
                serverVersion = snapshot.serverVersion,
            ),
        )
    }

    private fun SyncStateEntity.toSnapshot(): SyncSnapshot {
        return SyncSnapshot(
            status = SyncStatus.valueOf(status),
            libraryCount = libraryCount,
            bookCount = bookCount,
            authorCount = authorCount,
            lastSyncedAt = lastFullSyncAt ?: lastDeltaSyncAt,
            serverVersion = serverVersion,
            message = lastSyncError,
        )
    }

    companion object {
        private const val TAG = "CatalogSyncRepo"
    }

    private fun resolveAuthorsFromDisplay(
        authorDisplay: String?,
        knownAuthors: List<AuthorEntity>,
    ): List<io.audiobookshelf.aaos.absapi.AuthorSummary> {
        val display = authorDisplay?.trim().orEmpty()
        if (display.isBlank()) {
            return emptyList()
        }

        val normalizedNames = display
            .split(",", "&")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return normalizedNames.mapNotNull { candidate ->
            val normalizedCandidate = candidate.lowercase()
            val match = knownAuthors.firstOrNull { author ->
                author.name.trim().lowercase() == normalizedCandidate
            } ?: return@mapNotNull null

            io.audiobookshelf.aaos.absapi.AuthorSummary(
                id = match.id,
                name = match.name,
                sortName = match.sortName,
                imagePath = match.imagePath,
                numBooks = match.numBooks,
            )
        }
    }
}
