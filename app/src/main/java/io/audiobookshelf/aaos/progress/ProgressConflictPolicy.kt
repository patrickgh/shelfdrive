package io.audiobookshelf.aaos.progress

import io.audiobookshelf.aaos.catalog.persistence.MediaProgressEntity
import kotlin.math.abs

internal object ProgressConflictPolicy {
    const val POSITION_CONFLICT_THRESHOLD_MS = 10_000L
    const val FORWARD_SEEK_THRESHOLD_MS = 30_000L
    const val TIMESTAMP_TOLERANCE_MS = 2_000L

    fun resolve(
        local: MediaProgressEntity?,
        server: MediaProgressEntity,
        isActivePlaybackForItem: Boolean,
    ): ProgressConflictResolution {
        if (local == null) {
            return ProgressConflictResolution.ApplyServer
        }

        val serverNewer = server.lastUpdateAt > local.lastUpdateAt + TIMESTAMP_TOLERANCE_MS
        val localNewer = local.lastUpdateAt > server.lastUpdateAt + TIMESTAMP_TOLERANCE_MS
        val largePositionDelta = abs(server.currentTimeMs - local.currentTimeMs) > POSITION_CONFLICT_THRESHOLD_MS

        return when {
            isActivePlaybackForItem && serverNewer && largePositionDelta -> ProgressConflictResolution.DeferServer
            serverNewer -> ProgressConflictResolution.ApplyServer
            localNewer -> ProgressConflictResolution.KeepLocal
            else -> ProgressConflictResolution.IgnoreEquivalent
        }
    }

    fun shouldReplayLocal(local: MediaProgressEntity, server: MediaProgressEntity?): Boolean {
        if (server == null) {
            return true
        }
        if (server.isFinished) {
            return false
        }
        if (server.currentTimeMs >= local.currentTimeMs + FORWARD_SEEK_THRESHOLD_MS) {
            return false
        }
        if (local.currentTimeMs >= server.currentTimeMs + FORWARD_SEEK_THRESHOLD_MS) {
            return true
        }
        return local.lastUpdateAt > server.lastUpdateAt + TIMESTAMP_TOLERANCE_MS
    }

    fun reconcileActivePlayback(
        currentPositionMs: Long,
        lastAppliedServerUpdateAt: Long,
        server: MediaProgressEntity,
    ): ActiveProgressReconciliation {
        val serverPositionMs = if (server.isFinished) {
            server.durationMs?.takeIf { it > 0L } ?: server.currentTimeMs
        } else {
            server.currentTimeMs
        }
        if (
            server.lastUpdateAt <= lastAppliedServerUpdateAt ||
            serverPositionMs < currentPositionMs + FORWARD_SEEK_THRESHOLD_MS
        ) {
            return ActiveProgressReconciliation.KeepCurrent
        }
        val targetPositionMs = server.durationMs
            ?.takeIf { it > 0L }
            ?.let(serverPositionMs::coerceAtMost)
            ?: serverPositionMs
        return ActiveProgressReconciliation.SeekForwardOnce(
            positionMs = targetPositionMs,
            serverUpdateAt = server.lastUpdateAt,
        )
    }
}

internal sealed interface ActiveProgressReconciliation {
    data object KeepCurrent : ActiveProgressReconciliation
    data class SeekForwardOnce(
        val positionMs: Long,
        val serverUpdateAt: Long,
    ) : ActiveProgressReconciliation
}

internal sealed interface ProgressConflictResolution {
    data object ApplyServer : ProgressConflictResolution
    data object DeferServer : ProgressConflictResolution
    data object KeepLocal : ProgressConflictResolution
    data object IgnoreEquivalent : ProgressConflictResolution
}
