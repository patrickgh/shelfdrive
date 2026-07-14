package io.audiobookshelf.aaos.playback

import android.net.Uri
import io.audiobookshelf.aaos.artwork.ArtworkUriFactory
import io.audiobookshelf.aaos.absapi.AudiobookshelfApiClient
import io.audiobookshelf.aaos.absapi.PlaybackSessionSummary
import io.audiobookshelf.aaos.absapi.PlaybackTrackSummary
import io.audiobookshelf.aaos.auth.AuthenticatedRequestRunner
import io.audiobookshelf.aaos.auth.AuthRepository
import io.audiobookshelf.aaos.auth.AuthStorage
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import io.audiobookshelf.aaos.catalog.persistence.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

class AudiobookshelfPlaybackRepository(
    private val authRepository: AuthRepository,
    private val authStorage: AuthStorage,
    private val database: CatalogDatabase,
    private val apiClient: AudiobookshelfApiClient = AudiobookshelfApiClient(),
) {
    private val authenticatedRequestRunner = AuthenticatedRequestRunner(authStorage, authRepository)

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
                    contentUrl = resolveContentUrl(baseUrl, track.contentUrl),
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
            sessionId = playbackSession.sessionId,
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

    private fun resolveContentUrl(baseUrl: String, contentUrl: String): String {
        val contentUri = Uri.parse(contentUrl)
        if (contentUri.isAbsolute) {
            return contentUrl
        }

        val baseUri = Uri.parse(baseUrl)
        val builder = baseUri.buildUpon().encodedQuery(null).fragment(null)
        builder.path(null)
        contentUri.pathSegments
            .forEach { segment ->
                builder.appendPath(segment)
            }
        contentUri.encodedQuery?.let { builder.encodedQuery(it) }
        contentUri.encodedFragment?.let { builder.encodedFragment(it) }
        return builder.build().toString()
    }

    private fun List<PlaybackTrack>.sumOfKnownDurations(): Long? {
        return if (any { it.durationMs == null }) {
            null
        } else {
            sumOf { it.durationMs ?: 0L }
        }
    }
}

private data class ResolvedPlaybackSession(
    val playback: ResolvedAudiobookPlayback,
    val sessionId: String?,
)

class PlaybackResolutionException(
    override val message: String,
    override val cause: Throwable? = null,
) : IOException(message, cause)
