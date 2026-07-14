package io.audiobookshelf.aaos.progress

import android.util.Log
import androidx.room.withTransaction
import io.audiobookshelf.aaos.absapi.AudiobookshelfApiClient
import io.audiobookshelf.aaos.absapi.MediaProgressSummary
import io.audiobookshelf.aaos.absapi.PlaybackSessionUpdateRequest
import io.audiobookshelf.aaos.absapi.socket.AbsSocketEvent
import io.audiobookshelf.aaos.auth.AuthenticatedRequestContext
import io.audiobookshelf.aaos.auth.AuthenticatedRequestRunner
import io.audiobookshelf.aaos.auth.AuthenticationRequiredException
import io.audiobookshelf.aaos.auth.AuthRepository
import io.audiobookshelf.aaos.auth.AuthStorage
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import io.audiobookshelf.aaos.catalog.persistence.MediaProgressEntity
import io.audiobookshelf.aaos.diagnostics.DiagnosticEventLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class ProgressSyncRepository(
    private val database: CatalogDatabase,
    private val authRepository: AuthRepository,
    private val authStorage: AuthStorage,
    private val apiClient: AudiobookshelfApiClient = AudiobookshelfApiClient(),
    private val diagnosticEventLogger: DiagnosticEventLogger? = null,
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
        val localProgress = saveLocalProgress(snapshot)
            ?: return@withContext false
        val pendingProgress = localProgress.copy(pendingUpload = true)
        database.mediaProgressDao().upsert(pendingProgress)
        try {
            authenticatedRequestRunner.execute { context ->
                syncPlaybackSession(snapshot, context)
            }
            if (snapshot.isFinished) {
                database.mediaProgressDao().deleteByBookId(snapshot.bookId)
            } else {
                database.mediaProgressDao().upsert(localProgress.copy(pendingUpload = false))
            }
            true
        } catch (exception: AuthenticationRequiredException) {
            diagnosticEventLogger?.record(
                "progress_queued_offline",
                mapOf("bookId" to snapshot.bookId, "reason" to "authentication_required"),
            )
            false
        } catch (exception: IOException) {
            Log.w(TAG, "Playback session sync failed for ${snapshot.bookId}.", exception)
            diagnosticEventLogger?.record(
                "progress_queued_offline",
                mapOf("bookId" to snapshot.bookId, "reason" to exception.javaClass.simpleName),
            )
            false
        }
    }

    suspend fun replayPendingUploads(): Boolean = withContext(Dispatchers.IO) {
        val pending = database.mediaProgressDao()
            .getPendingUploads()
            .groupBy { it.bookId }
            .mapNotNull { (_, entries) -> entries.maxByOrNull { it.lastUpdateAt } }
            .sortedBy { it.lastUpdateAt }
        if (pending.isEmpty()) {
            return@withContext true
        }

        var allSucceeded = true
        pending.forEach { progress ->
            val replayed = runCatching {
                authenticatedRequestRunner.execute { context ->
                    replayPendingUpload(progress, context)
                }
            }.getOrElse { exception ->
                if (exception is CancellationException) {
                    throw exception
                }
                Log.w(TAG, "Pending progress replay failed for ${progress.bookId}.", exception)
                false
            }
            allSucceeded = allSucceeded && replayed
        }
        allSucceeded
    }

    suspend fun applySocketEvent(
        event: AbsSocketEvent,
        activeBookId: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        when (event) {
            is AbsSocketEvent.UserUpdated -> {
                var changed = false
                event.progressEntries.forEach { server ->
                    changed = applyServerProgress(server, activeBookId) || changed
                }
                changed
            }

            is AbsSocketEvent.ProgressUpdated -> applyServerProgress(event.progress, activeBookId)
            is AbsSocketEvent.ItemUpdated,
            is AbsSocketEvent.ItemRemoved,
            -> {
                diagnosticEventLogger?.record(
                    "socket_catalog_event_received",
                    mapOf("event" to event::class.java.simpleName),
                )
                false
            }
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

        val knownProgressEntries = progressEntries.onlyForKnownBooks()
        database.withTransaction {
            database.mediaProgressDao().clearUploaded()
            if (knownProgressEntries.isNotEmpty()) {
                val merged = knownProgressEntries.filter { server ->
                    val local = database.mediaProgressDao().getByBookId(server.bookId)
                    val resolution = ProgressConflictPolicy.resolve(
                        local = local,
                        server = server,
                        isActivePlaybackForItem = false,
                    )
                    resolution is ProgressConflictResolution.ApplyServer || local == null
                }
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
        val durationMs = snapshot.durationMs ?: existing?.durationMs ?: catalogBook.durationMs?.takeIf { it > 0L }
        val progressFraction = calculateProgressFraction(snapshot.currentTimeMs, durationMs)

        val now = snapshot.lastUpdateAt
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
            pendingUpload = false,
        )
        database.mediaProgressDao().upsert(entity)
        return entity
    }

    private suspend fun replayPendingUpload(
        progress: MediaProgressEntity,
        context: AuthenticatedRequestContext,
    ): Boolean {
        val serverProgress = runCatching {
            apiClient.getMediaProgress(context.baseUrl, context.accessToken, progress.bookId).toEntity()
        }.getOrNull()
        if (!ProgressConflictPolicy.shouldReplayLocal(progress, serverProgress)) {
            database.mediaProgressDao().upsert(progress.copy(pendingUpload = false))
            diagnosticEventLogger?.record(
                "conflict_resolved",
                mapOf("bookId" to progress.bookId, "winner" to "server", "source" to "replay"),
            )
            return true
        }

        val session = apiClient.createPlaybackSession(context.baseUrl, context.accessToken, progress.bookId)
        val sessionId = session.sessionId
        if (sessionId.isNullOrBlank()) {
            diagnosticEventLogger?.record(
                "progress_replay_skipped",
                mapOf("bookId" to progress.bookId, "reason" to "missing_session_id"),
            )
            return false
        }
        syncPlaybackSession(progress.toSnapshot(sessionId), context)
        if (progress.isFinished) {
            database.mediaProgressDao().deleteByBookId(progress.bookId)
        } else {
            database.mediaProgressDao().upsert(progress.copy(pendingUpload = false))
        }
        diagnosticEventLogger?.record(
            "progress_replayed",
            mapOf("bookId" to progress.bookId, "sessionId" to sessionId),
        )
        return true
    }

    private suspend fun applyServerProgress(
        serverProgress: MediaProgressEntity,
        activeBookId: String?,
    ): Boolean {
        if (!database.bookDao().existsById(serverProgress.bookId)) {
            return false
        }
        val local = database.mediaProgressDao().getByBookId(serverProgress.bookId)
        val resolution = ProgressConflictPolicy.resolve(
            local = local,
            server = serverProgress,
            isActivePlaybackForItem = activeBookId == serverProgress.bookId,
        )
        diagnosticEventLogger?.record(
            "conflict_resolved",
            mapOf(
                "bookId" to serverProgress.bookId,
                "winner" to when (resolution) {
                    ProgressConflictResolution.ApplyServer -> "server"
                    ProgressConflictResolution.DeferServer -> "deferred_server"
                    ProgressConflictResolution.KeepLocal -> "local"
                    ProgressConflictResolution.IgnoreEquivalent -> "equivalent"
                },
                "source" to "socket",
            ),
        )
        return when (resolution) {
            ProgressConflictResolution.ApplyServer -> {
                if (serverProgress.isFinished) {
                    database.mediaProgressDao().deleteByBookId(serverProgress.bookId)
                } else {
                    database.mediaProgressDao().upsert(serverProgress.copy(pendingUpload = false))
                }
                true
            }

            ProgressConflictResolution.DeferServer,
            ProgressConflictResolution.KeepLocal,
            ProgressConflictResolution.IgnoreEquivalent,
            -> false
        }
    }

    private suspend fun syncPlaybackSession(
        snapshot: PlaybackProgressSnapshot,
        context: AuthenticatedRequestContext,
    ) {
        val sessionId = snapshot.playbackSessionId
        if (sessionId.isNullOrBlank()) {
            diagnosticEventLogger?.record(
                "playback_session_sync_skipped",
                mapOf(
                    "reason" to "missing_session_id",
                    "bookId" to snapshot.bookId,
                    "progressReason" to snapshot.reason.name,
                ),
            )
            return
        }
        val durationMs = snapshot.durationMs
        if (durationMs == null || durationMs <= 0L) {
            diagnosticEventLogger?.record(
                "playback_session_sync_skipped",
                mapOf(
                    "reason" to "missing_duration",
                    "bookId" to snapshot.bookId,
                    "sessionId" to sessionId,
                    "progressReason" to snapshot.reason.name,
                ),
            )
            return
        }
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

    private fun MediaProgressSummary.toEntity(): MediaProgressEntity {
        return toEntity(fallbackLastUpdate = lastUpdateAt)
    }

    private fun MediaProgressEntity.toSnapshot(sessionId: String): PlaybackProgressSnapshot {
        return PlaybackProgressSnapshot(
            bookId = bookId,
            playbackSessionId = sessionId,
            currentTimeMs = currentTimeMs,
            durationMs = durationMs,
            timeListenedMs = 0L,
            isFinished = isFinished,
            reason = if (isFinished) PlaybackProgressReason.ENDED else PlaybackProgressReason.PERIODIC,
            lastUpdateAt = lastUpdateAt,
            offlineStarted = true,
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
