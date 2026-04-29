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
        try {
            authenticatedRequestRunner.execute { context ->
                pushProgressOnce(snapshot, context)
            }
        } catch (exception: AuthenticationRequiredException) {
            return@withContext false
        }
    }

    private suspend fun refreshInProgressOnce(context: AuthenticatedRequestContext) {
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

        database.withTransaction {
            database.mediaProgressDao().clearAll()
            if (progressEntries.isNotEmpty()) {
                database.mediaProgressDao().upsertAll(progressEntries)
            }
        }
    }

    private suspend fun pushProgressOnce(
        snapshot: PlaybackProgressSnapshot,
        context: AuthenticatedRequestContext,
    ): Boolean {
        val existing = database.mediaProgressDao().getByBookId(snapshot.bookId)
        val durationMs = snapshot.durationMs ?: existing?.durationMs
        val progressFraction = if (durationMs != null && durationMs > 0L) {
            snapshot.currentTimeMs.toDouble() / durationMs.toDouble()
        } else {
            null
        }?.coerceIn(0.0, 1.0)

        val now = System.currentTimeMillis()
        apiClient.updateMediaProgress(
            baseUrl = context.baseUrl,
            accessToken = context.accessToken,
            itemId = snapshot.bookId,
            progressUpdate = MediaProgressUpdateRequest(
                currentTimeMs = snapshot.currentTimeMs,
                durationMs = durationMs,
                progressFraction = progressFraction,
                isFinished = snapshot.isFinished,
                hideFromContinueListening = snapshot.isFinished,
                startedAt = existing?.startedAt ?: now,
                finishedAt = if (snapshot.isFinished) now else null,
            ),
        )

        if (snapshot.isFinished) {
            database.mediaProgressDao().deleteByBookId(snapshot.bookId)
        } else {
            database.mediaProgressDao().upsert(
                MediaProgressEntity(
                    bookId = snapshot.bookId,
                    currentTimeMs = snapshot.currentTimeMs,
                    durationMs = durationMs,
                    progressFraction = progressFraction,
                    isFinished = false,
                    hideFromContinueListening = false,
                    lastUpdateAt = now,
                    startedAt = existing?.startedAt ?: now,
                    finishedAt = null,
                ),
            )
        }
        return true
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
        )
    }

    companion object {
        private const val TAG = "ProgressSyncRepo"
    }
}
