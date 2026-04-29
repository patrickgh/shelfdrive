package io.audiobookshelf.aaos.session

import android.os.Bundle
import android.os.ResultReceiver
import android.provider.MediaStore
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import io.audiobookshelf.aaos.absapi.ApiException
import io.audiobookshelf.aaos.auth.AuthCommands
import io.audiobookshelf.aaos.auth.AuthenticationRequiredException
import io.audiobookshelf.aaos.auth.AuthRepository
import io.audiobookshelf.aaos.auth.AuthSnapshot
import io.audiobookshelf.aaos.browser.BrowseNodeId
import io.audiobookshelf.aaos.browser.CatalogBrowseRepository
import io.audiobookshelf.aaos.cache.CacheCommands
import io.audiobookshelf.aaos.cache.CacheRepository
import io.audiobookshelf.aaos.playback.AudiobookshelfPlaybackManager
import io.audiobookshelf.aaos.playback.AudiobookshelfPlaybackRepository
import io.audiobookshelf.aaos.progress.ProgressSyncRepository
import io.audiobookshelf.aaos.status.UserVisibleStatus
import io.audiobookshelf.aaos.sync.CatalogSyncRepository
import io.audiobookshelf.aaos.sync.SyncCommands
import io.audiobookshelf.aaos.sync.SyncSnapshot
import io.audiobookshelf.aaos.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

class AudiobookshelfSessionCallback(
    private val scope: CoroutineScope,
    private val session: AudiobookshelfSession,
    private val authRepository: AuthRepository,
    private val browseRepository: CatalogBrowseRepository,
    private val syncRepository: CatalogSyncRepository,
    private val playbackRepository: AudiobookshelfPlaybackRepository,
    private val playbackManager: AudiobookshelfPlaybackManager,
    private val progressSyncRepository: ProgressSyncRepository,
    private val cacheRepository: CacheRepository,
    private val onAuthSnapshotChanged: (AuthSnapshot) -> Unit,
    private val onSyncSnapshotChanged: (SyncSnapshot) -> Unit,
    private val onRecentProgressChanged: () -> Unit,
) : MediaSessionCompat.Callback() {

    init {
        playbackManager.progressListener = { progressSnapshot ->
            scope.launch {
                val updated = runCatching {
                    progressSyncRepository.pushProgress(progressSnapshot)
                }.getOrElse { exception ->
                    Log.w(TAG, "Progress sync failed for ${progressSnapshot.bookId}", exception)
                    false
                }
                if (updated && progressSnapshot.reason.shouldRefreshBrowse) {
                    onRecentProgressChanged()
                }
            }
        }
    }

    override fun onPlay() {
        Log.i(TAG, "Transport onPlay.")
        playbackManager.play()
    }

    override fun onPause() {
        playbackManager.pause()
    }

    override fun onStop() {
        playbackManager.stop()
    }

    override fun onPrepare() {
        playbackManager.prepareCurrent()
    }

    override fun onSeekTo(pos: Long) {
        playbackManager.seekTo(pos)
    }

    override fun onRewind() {
        playbackManager.rewind()
    }

    override fun onFastForward() {
        playbackManager.fastForward()
    }

    override fun onSetPlaybackSpeed(speed: Float) {
        playbackManager.setPlaybackSpeed(speed)
    }

    override fun onSkipToQueueItem(id: Long) {
        playbackManager.skipToQueueItem(id)
    }

    override fun onSkipToNext() {
        playbackManager.fastForward()
    }

    override fun onSkipToPrevious() {
        playbackManager.rewind()
    }

    override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
        val bookId = (mediaId?.let(BrowseNodeId::parse) as? BrowseNodeId.Book)?.bookId ?: return
        Log.i(TAG, "Transport onPlayFromMediaId book=$bookId.")
        scope.launch {
            publishPreparingBook(bookId)
            resolveAndLoadBook(bookId, playWhenReady = true)
        }
    }

    override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
        val bookId = (mediaId?.let(BrowseNodeId::parse) as? BrowseNodeId.Book)?.bookId ?: return
        Log.i(TAG, "Transport onPrepareFromMediaId book=$bookId.")
        scope.launch {
            publishPreparingBook(bookId)
            resolveAndLoadBook(bookId, playWhenReady = false)
        }
    }

    override fun onPrepareFromSearch(query: String?, extras: Bundle?) {
        publishPreparingState()
        scope.launch {
            resolveAndLoadVoiceSearch(query, extras, playWhenReady = false)
        }
    }

    override fun onPlayFromSearch(query: String?, extras: Bundle?) {
        publishPreparingState()
        scope.launch {
            resolveAndLoadVoiceSearch(query, extras, playWhenReady = true)
        }
    }

    override fun onCommand(command: String?, extras: Bundle?, cb: ResultReceiver?) {
        when (command) {
            AuthCommands.CMD_GET_AUTH_STATE -> {
                scope.launch {
                    val snapshot = authRepository.bootstrap()
                    onAuthSnapshotChanged(snapshot)
                    cb?.send(AuthCommands.RESULT_OK, snapshot.toBundle())
                }
            }

            AuthCommands.CMD_LOGIN -> {
                scope.launch {
                    val snapshot = authRepository.login(
                        requestedBaseUrl = extras?.getString(AuthCommands.EXTRA_SERVER_URL),
                        requestedUsername = extras?.getString(AuthCommands.EXTRA_USERNAME),
                        requestedPassword = extras?.getString(AuthCommands.EXTRA_PASSWORD),
                    )
                    onAuthSnapshotChanged(snapshot)
                    cb?.send(
                        if (snapshot.isAuthenticated) AuthCommands.RESULT_OK else AuthCommands.RESULT_ERROR,
                        snapshot.toBundle(),
                    )
                }
            }

            AuthCommands.CMD_LOGOUT -> {
                scope.launch {
                    playbackManager.stop()
                    val snapshot = authRepository.logout()
                    cacheRepository.clearCache()
                    val clearedSync = SyncSnapshot(status = SyncStatus.IDLE)
                    onAuthSnapshotChanged(snapshot)
                    onSyncSnapshotChanged(clearedSync)
                    onRecentProgressChanged()
                    cb?.send(AuthCommands.RESULT_OK, snapshot.toBundle())
                }
            }

            CacheCommands.CMD_GET_CACHE_STATE -> {
                scope.launch {
                    val snapshot = cacheRepository.loadSnapshot()
                    cb?.send(AuthCommands.RESULT_OK, snapshot.toBundle())
                }
            }

            CacheCommands.CMD_CLEAR_CACHE -> {
                scope.launch {
                    playbackManager.stop()
                    val snapshot = cacheRepository.clearCache()
                    onSyncSnapshotChanged(SyncSnapshot(status = SyncStatus.IDLE))
                    onRecentProgressChanged()
                    cb?.send(AuthCommands.RESULT_OK, snapshot.toBundle())
                }
            }

            SyncCommands.CMD_GET_SYNC_STATE -> {
                scope.launch {
                    val snapshot = syncRepository.loadSnapshot()
                    onSyncSnapshotChanged(snapshot)
                    cb?.send(AuthCommands.RESULT_OK, snapshot.toBundle())
                }
            }

            SyncCommands.CMD_SYNC_NOW -> {
                scope.launch {
                    val snapshot = syncRepository.syncNow()
                    if (snapshot.status != SyncStatus.FAILED) {
                        progressSyncRepository.refreshInProgress()
                    }
                    onSyncSnapshotChanged(snapshot)
                    onRecentProgressChanged()
                    cb?.send(
                        if (snapshot.status == SyncStatus.FAILED) AuthCommands.RESULT_ERROR else AuthCommands.RESULT_OK,
                        snapshot.toBundle(),
                    )
                }
            }

            else -> super.onCommand(command, extras, cb)
        }
    }

    private suspend fun resolveAndLoadVoiceSearch(
        query: String?,
        extras: Bundle?,
        playWhenReady: Boolean,
    ) {
        val searchQueries = voiceSearchQueries(query, extras)
        val book = browseRepository.findBestPlayableBookForVoice(searchQueries)
        if (book == null) {
            val displayQuery = searchQueries.firstOrNull { it.isNotBlank() }.orEmpty()
            Log.w(TAG, "No voice search result for '$displayQuery'.")
            publishPlaybackError(
                if (displayQuery.isBlank()) {
                    "Kein zuletzt gehoertes Hoerbuch gefunden."
                } else {
                    "Kein Hoerbuch fuer '$displayQuery' gefunden."
                },
            )
            return
        }

        resolveAndLoadBook(book.id, playWhenReady)
    }

    private suspend fun publishPreparingBook(bookId: String) {
        browseRepository.getPlayableBook(bookId)?.let { book ->
            session.publishCatalogBook(book)
        }
        publishPreparingState(
            activeQueueItemId = AudiobookshelfSession.ACTIVE_BOOK_QUEUE_ID,
            mediaId = BrowseNodeId.Book(bookId).serialize(),
        )
    }

    private suspend fun resolveAndLoadBook(
        bookId: String,
        playWhenReady: Boolean,
    ) {
        try {
            val resolvedSession = playbackRepository.resolveBook(bookId)
            if (playWhenReady) {
                playbackManager.playResolvedBook(resolvedSession.playback, resolvedSession.accessToken)
            } else {
                playbackManager.prepareResolvedBook(resolvedSession.playback, resolvedSession.accessToken)
            }
        } catch (exception: IOException) {
            Log.w(TAG, "Failed to resolve playback for $bookId", exception)
            publishPlaybackError(exception.toPlaybackMessage())
        }
    }

    private fun publishPreparingState(
        activeQueueItemId: Long = android.support.v4.media.session.MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong(),
        mediaId: String? = null,
    ) {
        if (!mediaId.isNullOrBlank()) {
            session.setActive(true)
        }
        session.setPlaybackState(
            session.buildPlaybackState(
                state = PlaybackStateCompat.STATE_CONNECTING,
                positionMs = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                playbackSpeed = 0f,
                activeQueueItemId = activeQueueItemId,
                mediaId = mediaId,
            ),
        )
    }

    private fun publishPlaybackError(message: String?) {
        session.setPlaybackState(
            session.buildPlaybackState(
                state = PlaybackStateCompat.STATE_ERROR,
                positionMs = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                playbackSpeed = 0f,
                errorMessage = message,
            ),
        )
    }

    private fun voiceSearchQueries(query: String?, extras: Bundle?): List<String> {
        val title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE).orEmpty()
        val artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST).orEmpty()
        val album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM).orEmpty()
        return listOf(
            query.orEmpty(),
            "$title $artist",
            "$album $artist",
            title,
            album,
            artist,
        )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("") }
    }

    companion object {
        private const val TAG = "AbsSessionCallback"

        fun initialPlaybackState(): PlaybackStateCompat {
            return PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                        PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
                        PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
                        PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_REWIND or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED or
                        PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(
                    PlaybackStateCompat.STATE_STOPPED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    0f,
                )
                .build()
        }
    }

    private fun IOException.toPlaybackMessage(): String {
        return when (this) {
            is AuthenticationRequiredException -> UserVisibleStatus.SESSION_EXPIRED
            is ApiException -> when (statusCode) {
                401 -> UserVisibleStatus.SESSION_EXPIRED
                else -> UserVisibleStatus.PLAYBACK_START_FAILED
            }
            else -> message?.takeIf { it.isNotBlank() }
                ?: UserVisibleStatus.PLAYBACK_START_FAILED
        }
    }
}
