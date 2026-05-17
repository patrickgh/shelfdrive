package io.audiobookshelf.aaos.progress

import android.util.Log
import androidx.room.withTransaction
import io.audiobookshelf.aaos.absapi.AudiobookshelfApiClient
import io.audiobookshelf.aaos.absapi.MediaProgressSummary
import io.audiobookshelf.aaos.absapi.MediaProgressUpdateRequest
import io.audiobookshelf.aaos.auth.AuthenticatedRequestContext
import io.audiobookshelf.aaos.auth.AuthenticatedRequestRunner
import io.audiobookshelf.aaos.auth.AuthenticationRequiredException
import io.audiobookshelf.aaos.auth.AuthRepository
import io.audiobookshelf.aaos.auth.AuthStorage
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import io.audiobookshelf.aaos.catalog.persistence.MediaProgressEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class ProgressSyncRepository(
    private val database: CatalogDatabase,
    private val authRepository: AuthRepository,
    private val authStorage: AuthStorage,
    private val apiClient: AudiobookshelfApiClient = AudiobookshelfApiClient(),
) {
    private val authenticatedRequestRunner = AuthenticatedRequestRunner(authStorage, authRepository)

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        database.mediaProgressDao().clearAll()
    }

    suspend fun refreshInProgress(): Boolean = withContext(Dispatchers.IO) {
        try {
            authenticatedRequestRunner.execute { context ->
                refreshInProgressOnce(context)
            }
            true
        } catch (exception: AuthenticationRequiredException) {
            database.mediaProgressDao().clearAll()
            false
        } catch (exception: IOException) {
            Log.w(TAG, "Recent progress refresh failed.", exception)
            false
        }
    }

    suspend fun pushProgress(snapshot: PlaybackProgressSnapshot) = withContext(Dispatchers.IO) {
        val pendingProgress = saveLocalProgress(snapshot, pendingUpload = true)
            ?: return@withContext false
        try {
            authenticatedRequestRunner.execute { context ->
                uploadProgress(pendingProgress, context)
            }
            true
        } catch (exception: AuthenticationRequiredException) {
            false
        } catch (exception: IOException) {
            Log.w(TAG, "Progress upload deferred for ${snapshot.bookId}.", exception)
            true
        }
    }

    private suspend fun refreshInProgressOnce(context: AuthenticatedRequestContext) {
        flushPendingUploads(context)
        val items = apiClient.getItemsInProgress(context.baseUrl, context.accessToken)
        val progressEntries = mutableListOf<MediaProgressEntity>()
        items.forEach { item ->
            val progress = runCatching {
                apiClient.getMediaProgress(context.baseUrl, context.accessToken, item.bookId)
            }.getOrElse { exception ->
                Log.w(TAG, "Skipping progress refresh for ${item.bookId}", exception)
                null
            } ?: return@forEach

            if (!progress.isFinished && !progress.hideFromContinueListening) {
                progressEntries += progress.toEntity(
                    fallbackLastUpdate = item.progressLastUpdateAt,
                )
            }
        }

        val knownProgressEntries = progressEntries.onlyForKnownBooks()
        val pendingProgressEntries = database.mediaProgressDao().getPendingUploads().onlyForKnownBooks()
        val mergedEntries = (knownProgressEntries + pendingProgressEntries)
            .associateBy { it.bookId }
            .values
            .toList()
        database.withTransaction {
            database.mediaProgressDao().clearAll()
            if (mergedEntries.isNotEmpty()) {
                database.mediaProgressDao().upsertAll(mergedEntries)
            }
        }
    }

    private suspend fun saveLocalProgress(
        snapshot: PlaybackProgressSnapshot,
        pendingUpload: Boolean,
    ): MediaProgressEntity? {
        val existing = database.mediaProgressDao().getByBookId(snapshot.bookId)
        val catalogBook = database.bookDao().getPlayableById(snapshot.bookId)
        if (catalogBook == null) {
            Log.w(TAG, "Skipping local progress cache for unknown book ${snapshot.bookId}")
            return null
        }
        val durationMs = snapshot.durationMs ?: existing?.durationMs ?: catalogBook.durationMs?.takeIf { it > 0L }
        val progressFraction = calculateProgressFraction(snapshot.currentTimeMs, durationMs)

        val now = System.currentTimeMillis()
        val entity = MediaProgressEntity(
            bookId = snapshot.bookId,
            currentTimeMs = snapshot.currentTimeMs,
            durationMs = durationMs,
            progressFraction = progressFraction,
            isFinished = snapshot.isFinished,
            hideFromContinueListening = snapshot.isFinished,
            lastUpdateAt = now,
            startedAt = existing?.startedAt ?: now,
            finishedAt = if (snapshot.isFinished) now else null,
            pendingUpload = pendingUpload,
        )
        database.mediaProgressDao().upsert(entity)
        return entity
    }

    private suspend fun uploadProgress(
        progress: MediaProgressEntity,
        context: AuthenticatedRequestContext,
    ) {
        apiClient.updateMediaProgress(
            baseUrl = context.baseUrl,
            accessToken = context.accessToken,
            itemId = progress.bookId,
            progressUpdate = MediaProgressUpdateRequest(
                currentTimeMs = progress.currentTimeMs,
                durationMs = progress.durationMs,
                progressFraction = progress.progressFraction,
                isFinished = progress.isFinished,
                hideFromContinueListening = progress.hideFromContinueListening,
                startedAt = progress.startedAt,
                finishedAt = progress.finishedAt,
            ),
        )

        if (progress.isFinished) {
            database.mediaProgressDao().deleteByBookId(progress.bookId)
        } else {
            database.mediaProgressDao().upsert(progress.copy(pendingUpload = false))
        }
    }

    private suspend fun flushPendingUploads(context: AuthenticatedRequestContext) {
        database.mediaProgressDao().getPendingUploads().forEach { pending ->
            runCatching {
                uploadProgress(pending, context)
            }.onFailure { exception ->
                Log.w(TAG, "Pending progress upload still deferred for ${pending.bookId}.", exception)
            }
        }
    }

    private suspend fun List<MediaProgressEntity>.onlyForKnownBooks(): List<MediaProgressEntity> {
        if (isEmpty()) {
            return emptyList()
        }

        val knownBookIds = database.bookDao().getExistingIds(map { it.bookId }).toSet()
        val knownProgressEntries = filter { it.bookId in knownBookIds }
        val skippedCount = size - knownProgressEntries.size
        if (skippedCount > 0) {
            Log.w(TAG, "Skipping $skippedCount progress entries for unknown books.")
        }
        return knownProgressEntries
    }

    private fun MediaProgressSummary.toEntity(fallbackLastUpdate: Long?): MediaProgressEntity {
        return MediaProgressEntity(
            bookId = bookId,
            currentTimeMs = currentTimeMs,
            durationMs = durationMs,
            progressFraction = progressFraction,
            isFinished = isFinished,
            hideFromContinueListening = hideFromContinueListening,
            lastUpdateAt = fallbackLastUpdate ?: lastUpdateAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            pendingUpload = false,
        )
    }

    companion object {
        private const val TAG = "ProgressSyncRepo"
    }
}

internal fun calculateProgressFraction(currentTimeMs: Long, durationMs: Long?): Double? {
    return if (durationMs != null && durationMs > 0L) {
        currentTimeMs.toDouble() / durationMs.toDouble()
    } else {
        null
    }?.coerceIn(0.0, 1.0)
}
