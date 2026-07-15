package io.audiobookshelf.aaos.progress

import io.audiobookshelf.aaos.catalog.persistence.MediaProgressEntity

internal object ProgressConflictPolicy {
    const val FORWARD_SEEK_THRESHOLD_MS = 30_000L

    fun decide(
        currentPositionMs: Long,
        server: MediaProgressEntity,
    ): ProgressUpdateDecision {
        val serverPositionMs = if (server.isFinished) {
            server.durationMs?.takeIf { it > 0L } ?: server.currentTimeMs
        } else {
            server.currentTimeMs
        }.coerceAtLeast(0L)

        return if (serverPositionMs - currentPositionMs.coerceAtLeast(0L) >= FORWARD_SEEK_THRESHOLD_MS) {
            ProgressUpdateDecision.SeekForward(serverPositionMs)
        } else {
            ProgressUpdateDecision.UploadLocal
        }
    }
}

internal sealed interface ProgressUpdateDecision {
    data object UploadLocal : ProgressUpdateDecision
    data class SeekForward(val positionMs: Long) : ProgressUpdateDecision
}
