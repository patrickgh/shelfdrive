package io.audiobookshelf.aaos.progress

import io.audiobookshelf.aaos.catalog.persistence.MediaProgressEntity
import kotlin.math.abs

internal object ProgressConflictPolicy {
    const val POSITION_CONFLICT_THRESHOLD_MS = 10_000L
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
        return server == null || local.lastUpdateAt > server.lastUpdateAt + TIMESTAMP_TOLERANCE_MS
    }
}

internal sealed interface ProgressConflictResolution {
    data object ApplyServer : ProgressConflictResolution
    data object DeferServer : ProgressConflictResolution
    data object KeepLocal : ProgressConflictResolution
    data object IgnoreEquivalent : ProgressConflictResolution
}
