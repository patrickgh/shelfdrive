package io.audiobookshelf.aaos.playback

import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.audiobookshelf.aaos.browser.BrowseNodeId
import io.audiobookshelf.aaos.progress.PlaybackProgressReason
import io.audiobookshelf.aaos.progress.PlaybackProgressSnapshot
import io.audiobookshelf.aaos.session.AudiobookshelfSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class AudiobookshelfPlaybackManager(
    context: Context,
    private val session: AudiobookshelfSession,
    private val scope: CoroutineScope,
) : Player.Listener {
    private val appContext = context.applicationContext
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    private val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)
    private val player = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            addListener(this@AudiobookshelfPlaybackManager)
        }

    private var activeBook: ResolvedAudiobookPlayback? = null
    var progressListener: ((PlaybackProgressSnapshot) -> Unit)? = null
    private var periodicProgressJob: Job? = null
    private var playWhenReadyAfterLoad: Boolean = false

    fun playResolvedBook(
        playback: ResolvedAudiobookPlayback,
        accessToken: String,
    ) {
        loadResolvedBook(playback, accessToken, playWhenReady = true)
    }

    fun prepareResolvedBook(
        playback: ResolvedAudiobookPlayback,
        accessToken: String,
    ) {
        loadResolvedBook(playback, accessToken, playWhenReady = false)
    }

    private fun loadResolvedBook(
        playback: ResolvedAudiobookPlayback,
        accessToken: String,
        playWhenReady: Boolean,
    ) {
        activeBook = playback
        session.updateQueue(playback)
        updateMetadata(playback, playback.startIndex)
        session.setPlaybackState(
            session.buildPlaybackState(
                state = PlaybackStateCompat.STATE_CONNECTING,
                positionMs = playback.startLogicalPositionMs(),
                playbackSpeed = 0f,
                activeQueueItemId = activeQueueItemId(),
                mediaId = playback.mediaBrowserId(),
            ),
        )

        val mediaItems = playback.queue.map { track ->
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(track.contentUrl)
                .setMimeType(track.mimeType)
                .setTag(track)
                .build()
        }

        val configuredPlayer = player.apply {
            stop()
            clearMediaItems()
            httpDataSourceFactory.setUserAgent("ShelfDrive/0.1.0")
            httpDataSourceFactory.setDefaultRequestProperties(mapOf("Authorization" to "Bearer $accessToken"))
            setMediaItems(mediaItems, playback.startIndex, playback.startPositionMs)
            prepare()
        }

        val shouldPlay = playWhenReady || playWhenReadyAfterLoad
        playWhenReadyAfterLoad = false
        if (shouldPlay) {
            configuredPlayer.play()
        }
        updatePlaybackState()
    }

    fun play() {
        if (player.mediaItemCount == 0) {
            playWhenReadyAfterLoad = true
            return
        }
        playWhenReadyAfterLoad = false
        player.play()
        updatePlaybackState()
    }

    fun prepareCurrent() {
        updatePlaybackState()
    }

    fun pause() {
        player.pause()
        applyRewindOnPauseIfEnabled()
        emitProgress(PlaybackProgressReason.PAUSED)
        updatePlaybackState()
    }

    fun stop() {
        emitProgress(PlaybackProgressReason.STOPPED)
        playWhenReadyAfterLoad = false
        player.stop()
        periodicProgressJob?.cancel()
        activeBook = null
        session.clearQueue()
        updatePlaybackState()
    }

    fun seekTo(positionMs: Long) {
        seekToLogicalPosition(positionMs)
        emitProgress(PlaybackProgressReason.SEEKED)
        updatePlaybackState()
    }

    private fun seekToLogicalPosition(positionMs: Long) {
        val playback = activeBook
        if (playback == null) {
            player.seekTo(positionMs.coerceAtLeast(0L))
        } else {
            val startPosition = PlaybackQueueMath.locateStartPosition(playback.queue, positionMs)
            player.seekTo(startPosition.trackIndex, startPosition.positionMs)
        }
    }

    fun rewind() {
        seekBy(-REWIND_MS)
    }

    fun fastForward() {
        seekBy(FAST_FORWARD_MS)
    }

    fun setPlaybackSpeed(speed: Float) {
        val normalizedSpeed = speed
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
            ?: return
        player.setPlaybackParameters(
            PlaybackParameters(normalizedSpeed, player.playbackParameters.pitch),
        )
        updatePlaybackState()
    }

    fun skipToNext() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        }
        updatePlaybackState()
    }

    fun skipToPrevious() {
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(0L)
        }
        updatePlaybackState()
    }

    fun skipToQueueItem(queueItemId: Long) {
        if (queueItemId == AudiobookshelfSession.ACTIVE_BOOK_QUEUE_ID) {
            updatePlaybackState()
        }
    }

    fun release() {
        player.removeListener(this)
        player.release()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            startPeriodicProgressUpdates()
        } else {
            periodicProgressJob?.cancel()
        }
        updatePlaybackState()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            emitProgress(PlaybackProgressReason.ENDED)
            periodicProgressJob?.cancel()
        }
        if (playbackState == Player.STATE_READY) {
            activeBook?.let { updateMetadata(it, player.currentMediaItemIndex) }
        }
        updatePlaybackState()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        activeBook?.let { updateMetadata(it, player.currentMediaItemIndex) }
        emitProgress(PlaybackProgressReason.TRACK_CHANGED)
        updatePlaybackState()
    }

    override fun onPlayerError(error: PlaybackException) {
        periodicProgressJob?.cancel()
        val activeTrack = activeBook?.queue?.getOrNull(player.currentMediaItemIndex)
        Log.e(
            TAG,
            "Playback failed for book=${activeBook?.bookId} track=${activeTrack?.contentUrl?.substringBefore("?")}",
            error,
        )
        session.setPlaybackState(
            session.buildPlaybackState(
                state = PlaybackStateCompat.STATE_ERROR,
                positionMs = logicalPlaybackPositionMs(),
                playbackSpeed = 0f,
                errorMessage = error.localizedMessage ?: "Playback-Fehler",
                activeQueueItemId = activeQueueItemId(),
                mediaId = activeBook?.mediaBrowserId(),
            ),
        )
    }

    private fun updatePlaybackState() {
        val state = when {
            player.playerError != null -> PlaybackStateCompat.STATE_ERROR
            player.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            player.playbackState == Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            player.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            player.playbackState == Player.STATE_READY -> PlaybackStateCompat.STATE_PAUSED
            activeBook != null -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        val speed = if (player.isPlaying) player.playbackParameters.speed else 0f
        session.setPlaybackState(
            session.buildPlaybackState(
                state = state,
                positionMs = logicalPlaybackPositionMs(),
                playbackSpeed = speed,
                activeQueueItemId = activeQueueItemId(),
                bufferedPositionMs = logicalBufferedPositionMs(),
                mediaId = activeBook?.mediaBrowserId(),
            ),
        )
    }

    private fun updateMetadata(playback: ResolvedAudiobookPlayback, queueIndex: Int) {
        val artworkUri = playback.artworkUri?.toString()
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, BrowseNodeId.Book(playback.bookId).serialize())
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, playback.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, playback.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, playback.author)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, playback.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, playback.author)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artworkUri)
            .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artworkUri)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUri)
        playback.totalDurationForHost()?.let { durationMs ->
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
        }
        session.setMetadata(metadataBuilder.build())
    }

    private fun activeQueueItemId(): Long {
        return if (activeBook == null) {
            android.support.v4.media.session.MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong()
        } else {
            AudiobookshelfSession.ACTIVE_BOOK_QUEUE_ID
        }
    }

    private fun seekBy(deltaMs: Long) {
        val currentPositionMs = logicalPlaybackPositionMs()
        val durationMs = activeBook?.durationMs
            ?.takeIf { it > 0L }
            ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val targetPositionMs = (currentPositionMs + deltaMs)
            .coerceAtLeast(0L)
            .let { target -> durationMs?.let { max -> target.coerceAtMost(max) } ?: target }
        seekTo(targetPositionMs)
    }

    private fun ResolvedAudiobookPlayback.mediaBrowserId(): String {
        return BrowseNodeId.Book(bookId).serialize()
    }

    private fun logicalPlaybackPositionMs(): Long {
        val playback = activeBook ?: return player.currentPosition.coerceAtLeast(0L)
        val queueTrack = playback.queue.getOrNull(player.currentMediaItemIndex)
            ?: playback.queue.firstOrNull()
            ?: return player.currentPosition.coerceAtLeast(0L)
        return (queueTrack.startOffsetMs + player.currentPosition.coerceAtLeast(0L))
            .coerceAtMost(playback.durationMs ?: Long.MAX_VALUE)
    }

    private fun logicalBufferedPositionMs(): Long {
        val playback = activeBook ?: return player.bufferedPosition.coerceAtLeast(0L)
        val queueTrack = playback.queue.getOrNull(player.currentMediaItemIndex)
            ?: playback.queue.firstOrNull()
            ?: return player.bufferedPosition.coerceAtLeast(0L)
        return (queueTrack.startOffsetMs + player.bufferedPosition.coerceAtLeast(0L))
            .coerceAtMost(playback.durationMs ?: Long.MAX_VALUE)
    }

    private fun ResolvedAudiobookPlayback.startLogicalPositionMs(): Long {
        val trackOffsetMs = queue.getOrNull(startIndex)?.startOffsetMs ?: 0L
        return (trackOffsetMs + startPositionMs.coerceAtLeast(0L))
            .coerceAtMost(durationMs ?: Long.MAX_VALUE)
    }

    private fun ResolvedAudiobookPlayback.totalDurationForHost(): Long? {
        return durationMs
            ?.takeIf { it > 0L }
            ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
    }

    private fun startPeriodicProgressUpdates() {
        periodicProgressJob?.cancel()
        periodicProgressJob = scope.launch {
            while (true) {
                delay(PROGRESS_UPDATE_INTERVAL_MS)
                emitProgress(PlaybackProgressReason.PERIODIC)
            }
        }
    }

    private fun emitProgress(reason: PlaybackProgressReason) {
        val playback = activeBook ?: return
        val logicalPositionMs = logicalPlaybackPositionMs()
        progressListener?.invoke(
            PlaybackProgressSnapshot(
                bookId = playback.bookId,
                currentTimeMs = logicalPositionMs,
                durationMs = playback.durationMs,
                isFinished = reason == PlaybackProgressReason.ENDED,
                reason = reason,
            ),
        )
    }

    private fun applyRewindOnPauseIfEnabled() {
        if (!PlaybackPreferences.isRewindOnPauseEnabled(appContext)) {
            return
        }
        val targetPositionMs = PlaybackResumePolicy.positionAfterPause(logicalPlaybackPositionMs())
        seekToLogicalPosition(targetPositionMs)
    }

    companion object {
        private const val TAG = "AbsPlaybackManager"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 15_000L
        private const val REWIND_MS = 15_000L
        private const val FAST_FORWARD_MS = 15_000L
        private const val MIN_PLAYBACK_SPEED = 0.5f
        private const val MAX_PLAYBACK_SPEED = 3.0f
    }
}
