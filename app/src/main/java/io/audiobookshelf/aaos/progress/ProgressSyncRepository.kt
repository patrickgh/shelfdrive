package io.audiobookshelf.aaos.progress

import android.util.Log
import androidx.room.withTransaction
import io.audiobookshelf.aaos.absapi.ApiException
import io.audiobookshelf.aaos.server.ServerApis
import io.audiobookshelf.aaos.server.ServerProgressSupersededException
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
    private val serverApis: ServerApis = ServerApis(),
    private val diagnosticEventLogger: DiagnosticEventLogger? = null,
) {
    suspend fun flushPending(bookId: String? = null): Boolean {
        for (pending in database.mediaProgressDao().getPending().filter { bookId == null || it.bookId == bookId }) {
            when (val server = loadServerProgress(pending.bookId, pending.sourceId)) {
                ServerProgressLookup.Unavailable -> return false
                is ServerProgressLookup.Found -> {
                    if (server.supersedesLocal || ProgressConflictPolicy.decide(pending.currentTimeMs, server.progress) is ProgressUpdateDecision.SeekForward) {
                        acceptServerProgress(server.progress)
                        continue
                    }
                }
                ServerProgressLookup.Missing -> Unit
            }
            val result = uploadCheckedProgress(PlaybackProgressSnapshot(
                bookId = pending.bookId, playbackSessionId = null, currentTimeMs = pending.currentTimeMs,
                durationMs = pending.durationMs, timeListenedMs = 0, isFinished = pending.isFinished,
                reason = PlaybackProgressReason.STOPPED, lastUpdateAt = pending.lastUpdateAt, sourceId = pending.sourceId,
            ))
            if (!result.uploaded && result.superseded == null) return false
        }
        return database.mediaProgressDao().getPending().none { bookId == null || it.bookId == bookId }
    }

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

    internal suspend fun loadServerProgress(bookId: String, sourceId: String? = null): ServerProgressLookup = withContext(Dispatchers.IO) {
        try {
            authenticatedRequestRunner.execute { context ->
                try {
                    val server = serverApis.forType(context.serverType).getMediaProgress(
                        baseUrl = context.baseUrl,
                        accessToken = context.accessToken,
                        itemId = bookId,
                        retryProfile = RetryProfile.NONE,
                        sourceId = sourceId ?: "audio",
                    )
                    ServerProgressLookup.Found(server.toEntity(), server.serverChanged)
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
            Log.w(TAG, "Server progress check failed.", exception)
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
                    val duration = snapshot.durationMs?.takeIf { it > 0L }
                        ?: return@execute ProgressUploadResult(false, snapshot.playbackSessionId)
                    diagnosticEventLogger?.record(
                        "playback_session_sync_started",
                        mapOf(
                            "serverType" to context.serverType.name,
                            "bookId" to snapshot.bookId,
                            "sessionId" to snapshot.playbackSessionId,
                            "currentTimeMs" to snapshot.currentTimeMs.toString(),
                            "durationMs" to duration.toString(),
                            "timeListenedMs" to snapshot.timeListenedMs.toString(),
                            "reason" to snapshot.reason.name,
                            "isFinished" to snapshot.isFinished.toString(),
                        ),
                    )
                    val sessionId = serverApis.forType(context.serverType).updateProgress(
                        baseUrl = context.baseUrl,
                        accessToken = context.accessToken,
                        bookId = snapshot.bookId,
                        sessionId = snapshot.playbackSessionId,
                        sessionUpdate = PlaybackSessionUpdateRequest(
                            snapshot.currentTimeMs, duration, snapshot.timeListenedMs, snapshot.lastUpdateAt, snapshot.sourceId,
                        ),
                        close = snapshot.isFinished || snapshot.reason == PlaybackProgressReason.STOPPED,
                    )
                    ProgressUploadResult(uploaded = true, sessionId = sessionId)
                }
                if (result.uploaded) {
                    markUploadedIfCurrent(localProgress)
                } else {
                    recordQueuedProgress(snapshot.bookId, "missing_session_or_duration")
                }
                result
            } catch (exception: ServerProgressSupersededException) {
                val server = exception.progress.toEntity()
                if (isCurrentLocalVersion(localProgress)) acceptServerProgress(server)
                ProgressUploadResult(uploaded = false, snapshot.playbackSessionId, superseded = server)
            } catch (_: AuthenticationRequiredException) {
                recordQueuedProgress(snapshot.bookId, "authentication_required")
                ProgressUploadResult(uploaded = false, snapshot.playbackSessionId)
            } catch (exception: ApiException) {
                if (exception.statusCode == 404 && !snapshot.playbackSessionId.isNullOrBlank()) {
                    recordQueuedProgress(snapshot.bookId, "session_not_found")
                    diagnosticEventLogger?.record(
                        "playback_session_missing",
                        mapOf(
                            "bookId" to snapshot.bookId,
                            "sessionId" to snapshot.playbackSessionId,
                        ),
                    )
                    ProgressUploadResult(
                        uploaded = false,
                        sessionId = null,
                        sessionMissing = true,
                    )
                } else {
                    Log.w(TAG, "Playback session sync failed.", exception)
                    recordQueuedProgress(snapshot.bookId, "api_${exception.statusCode}")
                    ProgressUploadResult(uploaded = false, snapshot.playbackSessionId)
                }
            } catch (exception: IOException) {
                Log.w(TAG, "Playback session sync failed.", exception)
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
        val items = serverApis.forType(context.serverType).getItemsInProgress(context.baseUrl, context.accessToken)
        val progressEntries = mutableListOf<MediaProgressEntity>()
        items.forEach { item ->
            val progress = runCatching {
                serverApis.forType(context.serverType).getMediaProgress(context.baseUrl, context.accessToken, item.bookId)
            }.getOrElse { exception ->
                Log.w(TAG, "Skipping progress refresh.", exception)
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
                    (local.sourceId == server.sourceId &&
                        ProgressConflictPolicy.decide(local.currentTimeMs, server) is ProgressUpdateDecision.SeekForward)
            }
            if (merged.isNotEmpty()) {
                database.mediaProgressDao().upsertAll(merged)
            }
        }
    }

    private suspend fun saveLocalProgress(snapshot: PlaybackProgressSnapshot): MediaProgressEntity? {
        val existing = database.mediaProgressDao().getByBookId(snapshot.bookId)
        if (existing?.pendingUpload == true && existing.sourceId != snapshot.sourceId) {
            Log.w(TAG, "Cannot replace pending progress from a different edition.")
            return null
        }
        val catalogBook = database.bookDao().getPlayableById(snapshot.bookId)
        if (catalogBook == null) {
            Log.w(TAG, "Skipping local progress cache for unknown book.")
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
            sourceId = snapshot.sourceId,
        )
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
            sourceId = sourceId,
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
            current.isFinished == progress.isFinished && current.sourceId == progress.sourceId
    }

    companion object {
        private const val TAG = "ProgressSyncRepo"
    }
}

internal sealed interface ServerProgressLookup {
    data class Found(val progress: MediaProgressEntity, val supersedesLocal: Boolean = false) : ServerProgressLookup
    data object Missing : ServerProgressLookup
    data object Unavailable : ServerProgressLookup
}

internal data class ProgressUploadResult(
    val uploaded: Boolean,
    val sessionId: String?,
    val sessionMissing: Boolean = false,
    val superseded: MediaProgressEntity? = null,
)
