package io.audiobookshelf.aaos.progress

import android.util.Log
import androidx.room.withTransaction
import io.audiobookshelf.aaos.absapi.ApiException
import io.audiobookshelf.aaos.absapi.AudiobookshelfApiClient
import io.audiobookshelf.aaos.absapi.MediaProgressSummary
import io.audiobookshelf.aaos.absapi.PlaybackSessionUpdateRequest
import io.audiobookshelf.aaos.absapi.RetryProfile
import io.audiobookshelf.aaos.auth.AuthenticatedRequestContext
import io.audiobookshelf.aaos.auth.AuthenticatedRequestRunner
import io.audiobookshelf.aaos.auth.AuthenticationRequiredException
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import io.audiobookshelf.aaos.catalog.persistence.MediaProgressEntity
import io.audiobookshelf.aaos.diagnostics.DiagnosticEventLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class ProgressSyncRepository(
    private val database: CatalogDatabase,
    private val authenticatedRequestRunner: AuthenticatedRequestRunner,
    private val apiClient: AudiobookshelfApiClient = AudiobookshelfApiClient(),
    private val diagnosticEventLogger: DiagnosticEventLogger? = null,
) {
    suspend fun refreshInProgress(): Boolean = withContext(Dispatchers.IO) {
        try {
            authenticatedRequestRunner.execute { context ->
                refreshInProgressOnce(context)
            }
            true
        } catch (exception: AuthenticationRequiredException) {
            database.mediaProgressDao().clearUploaded()
            false
        } catch (exception: IOException) {
            Log.w(TAG, "Recent progress refresh failed.", exception)
            false
        }
    }

    suspend fun storePendingProgress(snapshot: PlaybackProgressSnapshot): Boolean = withContext(Dispatchers.IO) {
        val progress = saveLocalProgress(snapshot) ?: return@withContext false
        database.mediaProgressDao().upsert(progress.copy(pendingUpload = true))
        true
    }

    internal suspend fun loadServerProgress(bookId: String): ServerProgressLookup = withContext(Dispatchers.IO) {
        try {
            authenticatedRequestRunner.execute { context ->
                try {
                    ServerProgressLookup.Found(
                        apiClient.getMediaProgress(
                            baseUrl = context.baseUrl,
                            accessToken = context.accessToken,
                            itemId = bookId,
                            retryProfile = RetryProfile.NONE,
                        ).toEntity(),
                    )
                } catch (exception: ApiException) {
                    if (exception.statusCode == 404) {
                        ServerProgressLookup.Missing
                    } else {
                        throw exception
                    }
                }
            }
        } catch (_: AuthenticationRequiredException) {
            ServerProgressLookup.Unavailable
        } catch (exception: IOException) {
            Log.w(TAG, "Server progress check failed for $bookId.", exception)
            diagnosticEventLogger?.record(
                "progress_check_failed",
                mapOf("bookId" to bookId, "reason" to exception.javaClass.simpleName),
            )
            ServerProgressLookup.Unavailable
        }
    }

    internal suspend fun uploadCheckedProgress(snapshot: PlaybackProgressSnapshot): ProgressUploadResult =
        withContext(Dispatchers.IO) {
            val localProgress = saveLocalProgress(snapshot)
                ?: return@withContext ProgressUploadResult(uploaded = false, snapshot.playbackSessionId)
            database.mediaProgressDao().upsert(localProgress.copy(pendingUpload = true))

            try {
                val result = authenticatedRequestRunner.execute { context ->
                    val sessionId = snapshot.playbackSessionId
                        ?: apiClient.createPlaybackSession(context.baseUrl, context.accessToken, snapshot.bookId).sessionId
                    if (sessionId.isNullOrBlank()) {
                        diagnosticEventLogger?.record(
                            "playback_session_sync_skipped",
                            mapOf("reason" to "missing_session_id", "bookId" to snapshot.bookId),
                        )
                        return@execute ProgressUploadResult(uploaded = false, sessionId = null)
                    }
                    val uploadSnapshot = snapshot.copy(playbackSessionId = sessionId)
                    ProgressUploadResult(
                        uploaded = syncPlaybackSession(uploadSnapshot, context),
                        sessionId = sessionId,
                    )
                }
                if (result.uploaded) {
                    markUploadedIfCurrent(localProgress)
                } else {
                    recordQueuedProgress(snapshot.bookId, "missing_session_or_duration")
                }
                result
            } catch (_: AuthenticationRequiredException) {
                recordQueuedProgress(snapshot.bookId, "authentication_required")
                ProgressUploadResult(uploaded = false, snapshot.playbackSessionId)
            } catch (exception: IOException) {
                Log.w(TAG, "Playback session sync failed for ${snapshot.bookId}.", exception)
                recordQueuedProgress(snapshot.bookId, exception.javaClass.simpleName)
                ProgressUploadResult(uploaded = false, snapshot.playbackSessionId)
            }
        }

    suspend fun acceptServerProgress(progress: MediaProgressEntity): Boolean = withContext(Dispatchers.IO) {
        if (!database.bookDao().existsById(progress.bookId)) {
            return@withContext false
        }
        if (progress.isFinished) {
            database.mediaProgressDao().deleteByBookId(progress.bookId)
        } else {
            database.mediaProgressDao().upsert(progress.copy(pendingUpload = false))
        }
        diagnosticEventLogger?.record(
            "conflict_resolved",
            mapOf("bookId" to progress.bookId, "winner" to "server", "source" to "http"),
        )
        true
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
                progressEntries += progress.toEntity(fallbackLastUpdate = item.progressLastUpdateAt)
            }
        }

        val knownProgressEntries = progressEntries.onlyForKnownBooks()
        database.withTransaction {
            database.mediaProgressDao().clearUploaded()
            val merged = knownProgressEntries.filter { server ->
                val local = database.mediaProgressDao().getByBookId(server.bookId)
                local == null ||
                    !local.pendingUpload ||
                    ProgressConflictPolicy.decide(local.currentTimeMs, server) is ProgressUpdateDecision.SeekForward
            }
            if (merged.isNotEmpty()) {
                database.mediaProgressDao().upsertAll(merged)
            }
        }
    }

    private suspend fun saveLocalProgress(snapshot: PlaybackProgressSnapshot): MediaProgressEntity? {
        val existing = database.mediaProgressDao().getByBookId(snapshot.bookId)
        val catalogBook = database.bookDao().getPlayableById(snapshot.bookId)
        if (catalogBook == null) {
            Log.w(TAG, "Skipping local progress cache for unknown book ${snapshot.bookId}")
            return null
        }
        val durationMs = snapshot.durationMs
            ?: existing?.durationMs
            ?: catalogBook.durationMs?.takeIf { it > 0L }
        val now = maxOf(snapshot.lastUpdateAt, existing?.lastUpdateAt?.plus(1L) ?: snapshot.lastUpdateAt)
        return MediaProgressEntity(
            bookId = snapshot.bookId,
            currentTimeMs = snapshot.currentTimeMs,
            durationMs = durationMs,
            isFinished = snapshot.isFinished,
            hideFromContinueListening = snapshot.isFinished,
            lastUpdateAt = now,
            startedAt = existing?.startedAt ?: now,
            finishedAt = if (snapshot.isFinished) now else null,
            pendingUpload = false,
        )
    }

    private suspend fun syncPlaybackSession(
        snapshot: PlaybackProgressSnapshot,
        context: AuthenticatedRequestContext,
    ): Boolean {
        val sessionId = snapshot.playbackSessionId ?: return false
        val durationMs = snapshot.durationMs?.takeIf { it > 0L } ?: return false
        diagnosticEventLogger?.record(
            "playback_session_sync_started",
            mapOf(
                "bookId" to snapshot.bookId,
                "sessionId" to sessionId,
                "currentTimeMs" to snapshot.currentTimeMs.toString(),
                "durationMs" to durationMs.toString(),
                "timeListenedMs" to snapshot.timeListenedMs.toString(),
                "reason" to snapshot.reason.name,
                "isFinished" to snapshot.isFinished.toString(),
            ),
        )
        val sessionUpdate = PlaybackSessionUpdateRequest(
            currentTimeMs = snapshot.currentTimeMs,
            durationMs = durationMs,
            timeListenedMs = snapshot.timeListenedMs,
            lastUpdateAt = snapshot.lastUpdateAt,
        )
        if (snapshot.isFinished || snapshot.reason == PlaybackProgressReason.STOPPED) {
            apiClient.closePlaybackSession(
                baseUrl = context.baseUrl,
                accessToken = context.accessToken,
                sessionId = sessionId,
                sessionUpdate = sessionUpdate,
            )
        } else {
            apiClient.syncPlaybackSession(
                baseUrl = context.baseUrl,
                accessToken = context.accessToken,
                sessionId = sessionId,
                sessionUpdate = sessionUpdate,
            )
        }
        return true
    }

    private suspend fun List<MediaProgressEntity>.onlyForKnownBooks(): List<MediaProgressEntity> {
        if (isEmpty()) return emptyList()
        val knownBookIds = database.bookDao().getExistingIds(map { it.bookId }).toSet()
        val knownProgressEntries = filter { it.bookId in knownBookIds }
        val skippedCount = size - knownProgressEntries.size
        if (skippedCount > 0) {
            Log.w(TAG, "Skipping $skippedCount progress entries for unknown books.")
        }
        return knownProgressEntries
    }

    private fun MediaProgressSummary.toEntity(fallbackLastUpdate: Long? = lastUpdateAt): MediaProgressEntity {
        return MediaProgressEntity(
            bookId = bookId,
            currentTimeMs = currentTimeMs,
            durationMs = durationMs,
            isFinished = isFinished,
            hideFromContinueListening = hideFromContinueListening,
            lastUpdateAt = fallbackLastUpdate ?: lastUpdateAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            pendingUpload = false,
        )
    }

    private fun recordQueuedProgress(bookId: String, reason: String) {
        diagnosticEventLogger?.record(
            "progress_queued_offline",
            mapOf("bookId" to bookId, "reason" to reason),
        )
    }

    private suspend fun markUploadedIfCurrent(progress: MediaProgressEntity) {
        if (!isCurrentLocalVersion(progress)) return
        if (progress.isFinished) {
            database.mediaProgressDao().deleteByBookId(progress.bookId)
        } else {
            database.mediaProgressDao().upsert(progress.copy(pendingUpload = false))
        }
    }

    private suspend fun isCurrentLocalVersion(progress: MediaProgressEntity): Boolean {
        val current = database.mediaProgressDao().getByBookId(progress.bookId) ?: return false
        return current.lastUpdateAt == progress.lastUpdateAt &&
            current.currentTimeMs == progress.currentTimeMs &&
            current.isFinished == progress.isFinished
    }

    companion object {
        private const val TAG = "ProgressSyncRepo"
    }
}

internal sealed interface ServerProgressLookup {
    data class Found(val progress: MediaProgressEntity) : ServerProgressLookup
    data object Missing : ServerProgressLookup
    data object Unavailable : ServerProgressLookup
}

internal data class ProgressUploadResult(
    val uploaded: Boolean,
    val sessionId: String?,
)
