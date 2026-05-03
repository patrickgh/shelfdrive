package io.audiobookshelf.aaos.media3

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.media.utils.MediaConstants
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.audiobookshelf.aaos.account.AudiobookshelfAccountContract
import io.audiobookshelf.aaos.account.AudiobookshelfAccountRegistry
import io.audiobookshelf.aaos.auth.AuthCommands
import io.audiobookshelf.aaos.auth.AuthRepository
import io.audiobookshelf.aaos.auth.AuthSnapshot
import io.audiobookshelf.aaos.auth.AuthStorage
import io.audiobookshelf.aaos.browser.BrowseNodeId
import io.audiobookshelf.aaos.browser.CatalogBrowseRepository
import io.audiobookshelf.aaos.cache.CacheCommands
import io.audiobookshelf.aaos.cache.CacheRepository
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import io.audiobookshelf.aaos.host.MediaHostIntentFactory
import io.audiobookshelf.aaos.playback.AudiobookshelfPlaybackRepository
import io.audiobookshelf.aaos.playback.PlaybackPreferences
import io.audiobookshelf.aaos.playback.PlaybackQueueMath
import io.audiobookshelf.aaos.playback.PlaybackResumePolicy
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlayback
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlaybackSession
import io.audiobookshelf.aaos.progress.PlaybackProgressReason
import io.audiobookshelf.aaos.progress.PlaybackProgressSnapshot
import io.audiobookshelf.aaos.progress.ProgressSyncRepository
import io.audiobookshelf.aaos.sync.CatalogSyncRepository
import io.audiobookshelf.aaos.sync.SyncCommands
import io.audiobookshelf.aaos.sync.SyncSnapshot
import io.audiobookshelf.aaos.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

@OptIn(UnstableApi::class)
class ShelfDriveMediaLibraryService : MediaLibraryService(), Player.Listener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var authStorage: AuthStorage
    private lateinit var authRepository: AuthRepository
    private lateinit var syncRepository: CatalogSyncRepository
    private lateinit var browseRepository: CatalogBrowseRepository
    private lateinit var playbackRepository: AudiobookshelfPlaybackRepository
    private lateinit var progressSyncRepository: ProgressSyncRepository
    private lateinit var cacheRepository: CacheRepository
    private lateinit var mediaCatalog: ShelfDriveMediaCatalog
    private lateinit var sessionPolicy: ShelfDriveSessionPolicy
    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    private var activeBook: ResolvedAudiobookPlayback? = null
    private var periodicProgressJob: Job? = null
    private var wasPlayWhenReady: Boolean = false

    override fun onCreate() {
        super.onCreate()

        authStorage = AuthStorage(this)
        authRepository = AuthRepository(
            storage = authStorage,
            accountRegistry = AudiobookshelfAccountRegistry(this),
        )
        val database = CatalogDatabase.getInstance(this)
        browseRepository = CatalogBrowseRepository(database)
        mediaCatalog = ShelfDriveMediaCatalog(this, browseRepository)
        sessionPolicy = ShelfDriveSessionPolicy(this)
        syncRepository = CatalogSyncRepository(
            database = database,
            authRepository = authRepository,
            authStorage = authStorage,
        )
        progressSyncRepository = ProgressSyncRepository(
            database = database,
            authRepository = authRepository,
            authStorage = authStorage,
        )
        cacheRepository = CacheRepository(this, database)
        playbackRepository = AudiobookshelfPlaybackRepository(
            authRepository = authRepository,
            authStorage = authStorage,
            database = database,
        )

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(this@ShelfDriveMediaLibraryService)
            }

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(MediaHostIntentFactory.createMediaHostPendingIntent(this))
            .setMediaButtonPreferences(sessionPolicy.mediaButtonPreferences())
            .build()

        serviceScope.launch {
            val initialAuth = authRepository.bootstrap()
            updateAuthSnapshot(initialAuth)
            updateSyncSnapshot(syncRepository.loadSnapshot())
            if (initialAuth.isAuthenticated) {
                val snapshot = syncRepository.syncIfStale()
                updateSyncSnapshot(snapshot)
                if (snapshot.status != SyncStatus.FAILED) {
                    progressSyncRepository.refreshInProgress()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        periodicProgressJob?.cancel()
        mediaLibrarySession.release()
        player.removeListener(this)
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            startPeriodicProgressUpdates()
        } else {
            periodicProgressJob?.cancel()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (wasPlayWhenReady && !playWhenReady && player.playbackState != Player.STATE_ENDED) {
            applyRewindOnPauseIfEnabled()
            emitProgress(PlaybackProgressReason.PAUSED)
        }
        wasPlayWhenReady = playWhenReady
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            emitProgress(PlaybackProgressReason.ENDED)
            periodicProgressJob?.cancel()
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            emitProgress(PlaybackProgressReason.SEEKED)
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            emitProgress(PlaybackProgressReason.TRACK_CHANGED)
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val activeTrack = activeBook?.queue?.getOrNull(player.currentMediaItemIndex)
        Log.e(
            TAG,
            "Playback failed for book=${activeBook?.bookId} track=${activeTrack?.contentUrl?.substringBefore("?")}",
            error,
        )
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (!sessionPolicy.isAllowedController(controller)) {
                Log.w(TAG, "Rejected Media3 controller ${controller.packageName}/${controller.uid}.")
                return MediaSession.ConnectionResult.reject()
            }

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionPolicy.availableSessionCommands())
                .setAvailablePlayerCommands(sessionPolicy.availablePlayerCommands())
                .setMediaButtonPreferences(sessionPolicy.mediaButtonPreferences())
                .setSessionActivity(MediaHostIntentFactory.createMediaHostPendingIntent(this@ShelfDriveMediaLibraryService))
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(LibraryResult.ofItem(mediaCatalog.buildRootItem(), mediaCatalog.rootParams(params)))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return serviceFuture("getItem:$mediaId") {
                val item = mediaCatalog.loadItem(mediaId)
                if (item == null) {
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                } else {
                    LibraryResult.ofItem(item, null)
                }
            }
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceFuture("getChildren:$parentId") {
                val children = mediaCatalog.loadChildren(parentId)
                LibraryResult.ofItemList(mediaCatalog.pageItems(children, page, pageSize), params)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            return serviceFuture("search:$query") {
                val count = mediaCatalog.loadSearchResults(query).size
                session.notifySearchResultChanged(browser, query, count, params)
                LibraryResult.ofVoid(params)
            }
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceFuture("getSearchResult:$query") {
                LibraryResult.ofItemList(
                    mediaCatalog.pageItems(mediaCatalog.loadSearchResults(query), page, pageSize),
                    params,
                )
            }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return serviceFuture("setMediaItems") {
                val requestedItem = mediaItems.firstOrNull()
                    ?: throw PlaybackResolutionException("Kein Medium ausgewaehlt.")
                Log.i(TAG, "Host requested playback for mediaId=${requestedItem.mediaId}.")
                val playback = resolveRequestedPlayback(requestedItem)
                Log.i(TAG, "Resolved playback for book=${playback.playback.bookId} tracks=${playback.playback.queue.size}.")
                activeBook = playback.playback
                configureAuthenticatedPlayback(playback.accessToken)
                MediaSession.MediaItemsWithStartPosition(
                    playback.playback.toMedia3PlayableItems(),
                    playback.playback.startIndex,
                    playback.playback.startPositionMs,
                )
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                ShelfDriveSessionPolicy.CMD_SEEK_BACK_15 -> serviceFuture(customCommand.customAction) {
                    player.seekBack()
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                ShelfDriveSessionPolicy.CMD_SEEK_FORWARD_15 -> serviceFuture(customCommand.customAction) {
                    player.seekForward()
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                AuthCommands.CMD_GET_AUTH_STATE -> serviceFuture(customCommand.customAction) {
                    val snapshot = authRepository.bootstrap()
                    updateAuthSnapshot(snapshot)
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                AuthCommands.CMD_LOGIN -> serviceFuture(customCommand.customAction) {
                    val snapshot = authRepository.login(
                        requestedBaseUrl = args.getString(AuthCommands.EXTRA_SERVER_URL),
                        requestedUsername = args.getString(AuthCommands.EXTRA_USERNAME),
                        requestedPassword = args.getString(AuthCommands.EXTRA_PASSWORD),
                    )
                    updateAuthSnapshot(snapshot)
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                AuthCommands.CMD_LOGOUT -> serviceFuture(customCommand.customAction) {
                    player.stop()
                    player.clearMediaItems()
                    activeBook = null
                    val snapshot = authRepository.logout()
                    cacheRepository.clearCache()
                    updateAuthSnapshot(snapshot)
                    updateSyncSnapshot(SyncSnapshot(status = SyncStatus.IDLE))
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                CacheCommands.CMD_GET_CACHE_STATE -> serviceFuture(customCommand.customAction) {
                    SessionResult(SessionResult.RESULT_SUCCESS, cacheRepository.loadSnapshot().toBundle())
                }

                CacheCommands.CMD_CLEAR_CACHE -> serviceFuture(customCommand.customAction) {
                    player.stop()
                    player.clearMediaItems()
                    activeBook = null
                    val snapshot = cacheRepository.clearCache()
                    updateSyncSnapshot(SyncSnapshot(status = SyncStatus.IDLE))
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                SyncCommands.CMD_GET_SYNC_STATE -> serviceFuture(customCommand.customAction) {
                    val snapshot = syncRepository.loadSnapshot()
                    updateSyncSnapshot(snapshot)
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                SyncCommands.CMD_SYNC_NOW -> serviceFuture(customCommand.customAction) {
                    val snapshot = syncRepository.syncNow()
                    if (snapshot.status != SyncStatus.FAILED) {
                        progressSyncRepository.refreshInProgress()
                    }
                    updateSyncSnapshot(snapshot)
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }
    }

    private suspend fun resolveRequestedPlayback(requestedItem: MediaItem): ResolvedAudiobookPlaybackSession {
        val requestedBookId = (BrowseNodeId.parse(requestedItem.mediaId) as? BrowseNodeId.Book)?.bookId
        if (requestedBookId != null) {
            return playbackRepository.resolveBook(requestedBookId)
        }

        val searchQuery = requestedItem.requestMetadata.searchQuery.orEmpty()
        val book = browseRepository.findBestPlayableBookForVoice(listOf(searchQuery))
            ?: throw PlaybackResolutionException(
                if (searchQuery.isBlank()) {
                    "Kein zuletzt gehoertes Hoerbuch gefunden."
                } else {
                    "Kein Hoerbuch fuer '$searchQuery' gefunden."
                },
            )
        return playbackRepository.resolveBook(book.id)
    }

    private fun configureAuthenticatedPlayback(accessToken: String) {
        httpDataSourceFactory.setUserAgent("ShelfDrive/0.1.0")
        httpDataSourceFactory.setDefaultRequestProperties(mapOf("Authorization" to "Bearer $accessToken"))
    }

    private fun startPeriodicProgressUpdates() {
        periodicProgressJob?.cancel()
        periodicProgressJob = serviceScope.launch {
            while (true) {
                delay(PROGRESS_UPDATE_INTERVAL_MS)
                emitProgress(PlaybackProgressReason.PERIODIC)
            }
        }
    }

    private fun emitProgress(reason: PlaybackProgressReason) {
        val playback = activeBook ?: return
        val snapshot = PlaybackProgressSnapshot(
            bookId = playback.bookId,
            currentTimeMs = logicalPlaybackPositionMs(),
            durationMs = playback.durationMs,
            isFinished = reason == PlaybackProgressReason.ENDED,
            reason = reason,
        )
        serviceScope.launch {
            val updated = runCatching {
                progressSyncRepository.pushProgress(snapshot)
            }.getOrElse { exception ->
                Log.w(TAG, "Progress sync failed for ${snapshot.bookId}", exception)
                false
            }
            if (updated && reason.shouldRefreshBrowse) {
                notifyBrowseTreeChanged()
            }
        }
    }

    private fun applyRewindOnPauseIfEnabled() {
        if (!PlaybackPreferences.isRewindOnPauseEnabled(this)) {
            return
        }
        val targetPositionMs = PlaybackResumePolicy.positionAfterPause(logicalPlaybackPositionMs())
        seekToLogicalPosition(targetPositionMs)
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

    private fun logicalPlaybackPositionMs(): Long {
        val playback = activeBook ?: return player.currentPosition.coerceAtLeast(0L)
        val queueTrack = playback.queue.getOrNull(player.currentMediaItemIndex)
            ?: playback.queue.firstOrNull()
            ?: return player.currentPosition.coerceAtLeast(0L)
        return (queueTrack.startOffsetMs + player.currentPosition.coerceAtLeast(0L))
            .coerceAtMost(playback.durationMs ?: Long.MAX_VALUE)
    }

    private fun updateAuthSnapshot(snapshot: AuthSnapshot) {
        mediaCatalog.authSnapshot = snapshot
        mediaLibrarySession.setSessionExtras(
            Bundle().apply {
                if (!snapshot.username.isNullOrBlank()) {
                    putString(MediaConstants.SESSION_EXTRAS_KEY_ACCOUNT_NAME, snapshot.username)
                    putString(MediaConstants.SESSION_EXTRAS_KEY_ACCOUNT_TYPE, AudiobookshelfAccountContract.ACCOUNT_TYPE)
                }
            },
        )
        notifyBrowseTreeChanged()
    }

    private fun updateSyncSnapshot(snapshot: SyncSnapshot) {
        mediaCatalog.syncSnapshot = snapshot
        notifyBrowseTreeChanged()
    }

    private fun notifyBrowseTreeChanged() {
        if (!this::mediaLibrarySession.isInitialized) {
            return
        }
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Root.serialize(), Int.MAX_VALUE, null)
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Recent.serialize(), Int.MAX_VALUE, null)
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Books.serialize(), Int.MAX_VALUE, null)
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Authors.serialize(), Int.MAX_VALUE, null)
    }

    private fun <T> serviceFuture(
        label: String,
        block: suspend () -> T,
    ): ListenableFuture<T> {
        return CallbackToFutureAdapter.getFuture { completer ->
            serviceScope.launch {
                try {
                    completer.set(block())
                } catch (exception: IOException) {
                    Log.w(TAG, "Media3 callback failed: $label", exception)
                    completer.setException(exception)
                } catch (throwable: Throwable) {
                    Log.e(TAG, "Media3 callback crashed: $label", throwable)
                    completer.setException(throwable)
                }
            }
            label
        }
    }

    private val PlaybackProgressReason.shouldRefreshBrowse: Boolean
        get() = this == PlaybackProgressReason.PAUSED ||
            this == PlaybackProgressReason.STOPPED ||
            this == PlaybackProgressReason.ENDED

    companion object {
        private const val TAG = "ShelfDriveMedia3"
        private const val SEEK_INCREMENT_MS = 15_000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 15_000L
    }
}

private class PlaybackResolutionException(
    override val message: String,
    override val cause: Throwable? = null,
) : IOException(message, cause)
