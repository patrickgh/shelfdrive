package io.audiobookshelf.aaos.playback

import io.audiobookshelf.aaos.artwork.ArtworkUriFactory
import io.audiobookshelf.aaos.server.ServerApis
import io.audiobookshelf.aaos.server.ServerType
import io.audiobookshelf.aaos.server.AudiobookServerApi
import io.audiobookshelf.aaos.absapi.PlaybackTrackSummary
import io.audiobookshelf.aaos.auth.AuthenticatedRequestRunner
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import io.audiobookshelf.aaos.catalog.persistence.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

class AudiobookPlaybackRepository(
    private val authenticatedRequestRunner: AuthenticatedRequestRunner,
    private val database: CatalogDatabase,
    private val serverApis: ServerApis = ServerApis(),
) {
    suspend fun resolveBook(bookId: String, sourceId: String? = null): ResolvedAudiobookPlaybackSession = withContext(Dispatchers.IO) {
        authenticatedRequestRunner.execute { context ->
            resolveBookOnce(
                apiClient = serverApis.forType(context.serverType),
                account = context,
                bookId = bookId,
                sourceId = sourceId,
                baseUrl = context.baseUrl,
                accessToken = context.accessToken,
            )
        }
    }

    private suspend fun resolveBookOnce(
        apiClient: AudiobookServerApi,
        account: io.audiobookshelf.aaos.auth.AuthenticatedRequestContext,
        bookId: String,
        sourceId: String?,
        baseUrl: String,
        accessToken: String,
    ): ResolvedAudiobookPlaybackSession {
        val playbackSession = apiClient.createPlaybackSession(baseUrl, accessToken, bookId, sourceId)
        val sessionId = playbackSession.sessionId

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
                    contentUrl = playbackTrackUri(bookId, track.index),
                    mimeType = track.mimeType,
                    durationMs = track.durationMs,
                    startOffsetMs = track.startOffsetMs,
                    streamPath = track.contentUrl.takeIf { account.serverType == ServerType.BOOKORBIT },
                    clipStartMs = track.clipStartMs,
                    clipEndMs = track.clipEndMs,
                )
            }

        if (queue.isEmpty()) {
            throw PlaybackResolutionException("Die Playback-Session enthaelt keine abspielbaren Audiodateien.")
        }

        val requestedStart = playbackSession.currentTimeMs
            ?: playbackSession.startTimeMs
            ?: 0L
        val startPosition = PlaybackQueueMath.locateStartPosition(queue, requestedStart)

        return ResolvedAudiobookPlaybackSession(
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
                chapters = playbackSession.chapters,
                sourceId = playbackSession.sourceId,
                startIndex = startPosition.trackIndex,
                startPositionMs = startPosition.positionMs,
            ),
            baseUrl = baseUrl,
            accessToken = accessToken,
            sessionId = sessionId,
            account = account,
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

class PlaybackResolutionException(
    override val message: String,
    override val cause: Throwable? = null,
) : IOException(message, cause)
