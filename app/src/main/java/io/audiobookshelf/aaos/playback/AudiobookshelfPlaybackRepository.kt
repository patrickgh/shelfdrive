package io.audiobookshelf.aaos.playback

import io.audiobookshelf.aaos.artwork.ArtworkUriFactory
import io.audiobookshelf.aaos.absapi.AudiobookshelfApiClient
import io.audiobookshelf.aaos.absapi.PlaybackSessionSummary
import io.audiobookshelf.aaos.absapi.PlaybackTrackSummary
import io.audiobookshelf.aaos.auth.AuthenticatedRequestRunner
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import io.audiobookshelf.aaos.catalog.persistence.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

class AudiobookshelfPlaybackRepository(
    private val authenticatedRequestRunner: AuthenticatedRequestRunner,
    private val database: CatalogDatabase,
    private val apiClient: AudiobookshelfApiClient = AudiobookshelfApiClient(),
) {
    suspend fun resolveBook(bookId: String): ResolvedAudiobookPlaybackSession = withContext(Dispatchers.IO) {
        authenticatedRequestRunner.execute { context ->
            val resolved = resolveBookOnce(
                bookId = bookId,
                baseUrl = context.baseUrl,
                accessToken = context.accessToken,
            )
            ResolvedAudiobookPlaybackSession(
                playback = resolved.playback,
                accessToken = context.accessToken,
                sessionId = resolved.sessionId,
            )
        }
    }

    private suspend fun resolveBookOnce(
        bookId: String,
        baseUrl: String,
        accessToken: String,
    ): ResolvedPlaybackSession {
        val playbackSession = apiClient.createPlaybackSession(baseUrl, accessToken, bookId)
        val sessionId = playbackSession.sessionId
            ?: throw PlaybackResolutionException("Die Playback-Session enthaelt keine ID.")
        val catalogBook = database.bookDao().getPlayableById(bookId)

        val queue = playbackSession.audioTracks
            .sortedWith(
                compareBy<PlaybackTrackSummary>(
                    { if (it.index >= 0) it.index else Int.MAX_VALUE },
                    { it.startOffsetMs },
                    { it.title ?: it.contentUrl.lowercase(Locale.ROOT) },
                ),
            )
            .mapIndexed { index, track ->
                PlaybackTrack(
                    id = track.id.ifBlank { "track-$index" },
                    title = track.title?.takeIf { it.isNotBlank() }
                        ?: inferTrackTitle(catalogBook, index, playbackSession.audioTracks.size),
                    contentUrl = playbackSessionTrackUrl(baseUrl, sessionId, track.index),
                    mimeType = track.mimeType,
                    durationMs = track.durationMs,
                    startOffsetMs = track.startOffsetMs,
                )
            }

        if (queue.isEmpty()) {
            throw PlaybackResolutionException("Die Playback-Session enthaelt keine abspielbaren Audiodateien.")
        }

        val requestedStart = playbackSession.currentTimeMs
            ?: playbackSession.startTimeMs
            ?: 0L
        val startPosition = PlaybackQueueMath.locateStartPosition(queue, requestedStart)

        return ResolvedPlaybackSession(
            playback = ResolvedAudiobookPlayback(
                bookId = bookId,
                title = playbackSession.displayTitle
                    ?: catalogBook?.title
                    ?: playbackSession.title
                    ?: "Unbekanntes Hoerbuch",
                author = playbackSession.displayAuthor
                    ?: catalogBook?.authorDisplay
                    ?: playbackSession.author,
                artworkUri = ArtworkUriFactory.bookCover(
                    bookId,
                    ArtworkUriFactory.signatureFor(playbackSession.coverPath ?: catalogBook?.coverPath),
                ),
                durationMs = playbackSession.durationMs ?: queue.sumOfKnownDurations() ?: catalogBook?.durationMs?.takeIf { it > 0L },
                queue = queue,
                startIndex = startPosition.trackIndex,
                startPositionMs = startPosition.positionMs,
            ),
            sessionId = sessionId,
        )
    }

    private fun inferTrackTitle(book: BookEntity?, index: Int, queueSize: Int): String {
        return if (queueSize <= 1) {
            book?.title ?: "Hoerbuch"
        } else {
            val baseTitle = book?.title ?: "Hoerbuch"
            "$baseTitle ${index + 1}"
        }
    }

    private fun List<PlaybackTrack>.sumOfKnownDurations(): Long? {
        return if (any { it.durationMs == null }) {
            null
        } else {
            sumOf { it.durationMs ?: 0L }
        }
    }
}

internal fun playbackSessionTrackUrl(
    baseUrl: String,
    sessionId: String,
    trackIndex: Int,
): String {
    return "${baseUrl.trimEnd('/')}/public/session/$sessionId/track/$trackIndex"
}

private data class ResolvedPlaybackSession(
    val playback: ResolvedAudiobookPlayback,
    val sessionId: String?,
)

class PlaybackResolutionException(
    override val message: String,
    override val cause: Throwable? = null,
) : IOException(message, cause)
