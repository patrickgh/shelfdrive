package io.audiobookshelf.aaos.media3

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.media.utils.MediaConstants
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
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
import io.audiobookshelf.aaos.absapi.ApiException
import io.audiobookshelf.aaos.BuildConfig
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
import io.audiobookshelf.aaos.cache.PlaybackAudioCache
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import io.audiobookshelf.aaos.diagnostics.DiagnosticEventLogger
import io.audiobookshelf.aaos.diagnostics.PlaybackRestoreStatus
import io.audiobookshelf.aaos.diagnostics.StartupDiagnosticsStorage
import io.audiobookshelf.aaos.host.MediaHostIntentFactory
import io.audiobookshelf.aaos.playback.AudiobookshelfPlaybackRepository
import io.audiobookshelf.aaos.playback.PlaybackPreferences
import io.audiobookshelf.aaos.playback.PlaybackQueueMath
import io.audiobookshelf.aaos.playback.PlaybackResumePolicy
import io.audiobookshelf.aaos.playback.PlaybackSnapshotPolicy
import io.audiobookshelf.aaos.playback.PlaybackStateStorage
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlayback
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlaybackSession
import io.audiobookshelf.aaos.playback.StoredPlaybackState
import io.audiobookshelf.aaos.progress.PlaybackProgressReason
import io.audiobookshelf.aaos.progress.PlaybackProgressSnapshot
import io.audiobookshelf.aaos.progress.ProgressSyncRepository
import io.audiobookshelf.aaos.sync.CatalogSyncRepository
import io.audiobookshelf.aaos.sync.SyncCommands
import io.audiobookshelf.aaos.sync.SyncSnapshot
import io.audiobookshelf.aaos.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

@OptIn(UnstableApi::class)
class ShelfDriveMediaLibraryService : MediaLibraryService(), Player.Listener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var authStorage: AuthStorage
    private lateinit var authRepository: AuthRepository
    private lateinit var syncRepository: CatalogSyncRepository
    private lateinit var browseRepository: CatalogBrowseRepository
    private lateinit var playbackRepository: AudiobookshelfPlaybackRepository
    private lateinit var playbackStateStorage: PlaybackStateStorage
    private lateinit var progressSyncRepository: ProgressSyncRepository
    private lateinit var cacheRepository: CacheRepository
    private lateinit var diagnosticsStorage: StartupDiagnosticsStorage
    private lateinit var diagnosticEventLogger: DiagnosticEventLogger
    private lateinit var mediaCatalog: ShelfDriveMediaCatalog
    private lateinit var sessionPolicy: ShelfDriveSessionPolicy
    private lateinit var player: ExoPlayer
    private lateinit var sessionPlayer: Player
    private lateinit var mediaLibrarySession: MediaLibrarySession

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    private var activeBook: ResolvedAudiobookPlayback? = null
    private var activeAccessToken: String? = null
    private var periodicProgressJob: Job? = null
    private var playbackRecoveryJob: Job? = null
    private var transientPlaybackRetryAttempt: Int = 0
    private var wasPlayWhenReady: Boolean = false
    private var placeholderPlaybackState: StoredPlaybackState? = null

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
        playbackStateStorage = PlaybackStateStorage(this)
        diagnosticsStorage = StartupDiagnosticsStorage(this)
        diagnosticEventLogger = DiagnosticEventLogger(this)
        diagnosticsStorage.recordServiceStarted()
        diagnosticEventLogger.record("service_started")

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    PlaybackAudioCache.createDataSourceFactory(
                        this,
                        DefaultDataSource.Factory(this, httpDataSourceFactory),
                    ),
                ),
            )
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
        player.setPlaybackParameters(
            PlaybackParameters(PlaybackPreferences.playbackSpeed(this), player.playbackParameters.pitch),
        )
        sessionPlayer = AudiobookProgressPlayer(player)

        mediaLibrarySession = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .setSessionActivity(MediaHostIntentFactory.createMediaHostPendingIntent(this))
            .setMediaButtonPreferences(sessionPolicy.mediaButtonPreferences(player.playbackParameters.speed))
            .build()

        publishStoredPlaybackPlaceholder()

        serviceScope.launch {
            val initialAuth = authRepository.bootstrap()
            updateAuthSnapshot(initialAuth)
            updateSyncSnapshot(syncRepository.loadSnapshot())
            diagnosticEventLogger.record("auth_bootstrap", mapOf("status" to initialAuth.status.name))
            if (initialAuth.isAuthenticated) {
                restoreStoredPlaybackWithTimeout()
                val snapshot = syncRepository.syncIfStale()
                updateSyncSnapshot(snapshot)
                diagnosticEventLogger.record(
                    "startup_sync_finished",
                    mapOf(
                        "status" to snapshot.status.name,
                        "books" to snapshot.bookCount.toString(),
                    ),
                )
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
        playbackRecoveryJob?.cancel()
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
            playbackStateStorage.clear()
        }
        if (playbackState == Player.STATE_READY) {
            transientPlaybackRetryAttempt = 0
            saveActivePlaybackState()
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

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        PlaybackPreferences.savePlaybackSpeed(this, playbackParameters.speed)
        saveActivePlaybackState()
        updateMediaButtonPreferences()
    }

    override fun onPlayerError(error: PlaybackException) {
        val activeTrack = activeBook?.queue?.getOrNull(player.currentMediaItemIndex)
        Log.e(
            TAG,
            "Playback failed for book=${activeBook?.bookId} track=${activeTrack?.contentUrl?.substringBefore("?")}",
            error,
        )
        diagnosticEventLogger.record(
            "player_error",
            mapOf(
                "errorCode" to error.errorCodeName,
                "message" to error.message,
                "bookActive" to (activeBook != null).toString(),
            ),
        )
        if (error.isUnauthorizedResponse()) {
            recoverPlaybackAfterUnauthorized()
        } else if (error.isTransientNetworkResponse()) {
            recoverPlaybackAfterTransientNetworkError()
        }
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
                .setMediaButtonPreferences(sessionPolicy.mediaButtonPreferences(player.playbackParameters.speed))
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
                publishRequestedPlaybackPlaceholder(requestedItem)
                val playback = resolveRequestedPlayback(requestedItem)
                Log.i(TAG, "Resolved playback for book=${playback.playback.bookId} tracks=${playback.playback.queue.size}.")
                activeBook = playback.playback
                placeholderPlaybackState = null
                configureAuthenticatedPlayback(playback.accessToken)
                transientPlaybackRetryAttempt = 0
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
            val action = customCommand.customAction
            return when {
                action == ShelfDriveSessionPolicy.CMD_SEEK_BACK_15 -> serviceFuture(action) {
                    seekBy(-SEEK_INCREMENT_MS)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                action == ShelfDriveSessionPolicy.CMD_SEEK_FORWARD_15 -> serviceFuture(action) {
                    seekBy(SEEK_INCREMENT_MS)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                action == ShelfDriveSessionPolicy.CMD_CYCLE_PLAYBACK_SPEED -> serviceFuture(action) {
                    player.setPlaybackSpeed(
                        ShelfDriveSessionPolicy.nextPlaybackSpeed(player.playbackParameters.speed),
                    )
                    saveActivePlaybackState()
                    updateMediaButtonPreferences()
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                action == AuthCommands.CMD_GET_AUTH_STATE -> serviceFuture(action) {
                    val snapshot = authRepository.bootstrap()
                    updateAuthSnapshot(snapshot)
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                action == AuthCommands.CMD_LOGIN -> serviceFuture(action) {
                    val snapshot = authRepository.login(
                        requestedBaseUrl = args.getString(AuthCommands.EXTRA_SERVER_URL),
                        requestedUsername = args.getString(AuthCommands.EXTRA_USERNAME),
                        requestedPassword = args.getString(AuthCommands.EXTRA_PASSWORD),
                    )
                    updateAuthSnapshot(snapshot)
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                action == AuthCommands.CMD_LOGOUT -> serviceFuture(action) {
                    player.stop()
                    player.clearMediaItems()
                    activeBook = null
                    activeAccessToken = null
                    placeholderPlaybackState = null
                    transientPlaybackRetryAttempt = 0
                    playbackStateStorage.clear()
                    val snapshot = authRepository.logout()
                    cacheRepository.clearCache()
                    updateAuthSnapshot(snapshot)
                    updateSyncSnapshot(SyncSnapshot(status = SyncStatus.IDLE))
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                action == CacheCommands.CMD_GET_CACHE_STATE -> serviceFuture(action) {
                    SessionResult(SessionResult.RESULT_SUCCESS, cacheRepository.loadSnapshot().toBundle())
                }

                action == CacheCommands.CMD_CLEAR_CACHE -> serviceFuture(action) {
                    player.stop()
                    player.clearMediaItems()
                    activeBook = null
                    activeAccessToken = null
                    placeholderPlaybackState = null
                    transientPlaybackRetryAttempt = 0
                    playbackStateStorage.clear()
                    val snapshot = cacheRepository.clearCache()
                    updateSyncSnapshot(SyncSnapshot(status = SyncStatus.IDLE))
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                action == SyncCommands.CMD_GET_SYNC_STATE -> serviceFuture(action) {
                    val snapshot = syncRepository.loadSnapshot()
                    updateSyncSnapshot(snapshot)
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                action == SyncCommands.CMD_SYNC_NOW -> serviceFuture(action) {
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

    private fun publishRequestedPlaybackPlaceholder(requestedItem: MediaItem) {
        val requestedBookId = (BrowseNodeId.parse(requestedItem.mediaId) as? BrowseNodeId.Book)?.bookId
            ?: return
        val placeholderState = PlaybackSnapshotPolicy.storedStateFromBrowseItem(
            bookId = requestedBookId,
            item = requestedItem,
            nowMs = System.currentTimeMillis(),
        )
        activeBook = null
        placeholderPlaybackState = placeholderState
        player.stop()
        player.setMediaItem(PlaybackSnapshotPolicy.placeholderMediaItem(placeholderState), 0L)
        player.playWhenReady = false
        diagnosticEventLogger.record(
            "playback_placeholder_published",
            mapOf(
                "source" to "browse_item",
                "hasTitle" to (!placeholderState.title.isNullOrBlank()).toString(),
            ),
        )
    }

    private fun publishStoredPlaybackPlaceholder() {
        val stored = playbackStateStorage.load()
            ?: return
        if (!PlaybackSnapshotPolicy.isRestorable(stored, System.currentTimeMillis())) {
            playbackStateStorage.clear()
            return
        }
        placeholderPlaybackState = stored
        player.setMediaItem(PlaybackSnapshotPolicy.placeholderMediaItem(stored), stored.positionMs)
        player.setPlaybackParameters(
            PlaybackParameters(stored.playbackSpeed, player.playbackParameters.pitch),
        )
        player.playWhenReady = false
        diagnosticsStorage.recordRestoreStarted(stored.bookId)
        diagnosticEventLogger.record(
            "restore_placeholder_published",
            mapOf(
                "hasTitle" to (!stored.title.isNullOrBlank()).toString(),
                "wasPlaying" to stored.wasPlaying.toString(),
            ),
        )
        Log.i(TAG, "Published local playback placeholder for book=${stored.bookId}.")
    }

    private suspend fun restoreStoredPlaybackWithTimeout() {
        val stored = playbackStateStorage.load()
        if (stored == null) {
            diagnosticsStorage.recordRestoreFinished(PlaybackRestoreStatus.SKIPPED, "No stored playback state.")
            diagnosticEventLogger.record("restore_skipped", mapOf("reason" to "no_stored_state"))
            return
        }
        diagnosticsStorage.recordRestoreStarted(stored.bookId)
        diagnosticEventLogger.record("restore_started", mapOf("hasBookId" to "true"))
        val restoreCompleted = CompletableDeferred<Unit>()
        val restoreJob = serviceScope.launch {
            try {
                restoreStoredPlayback(stored)
            } finally {
                restoreCompleted.complete(Unit)
            }
        }
        val completedInTime = withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
            restoreCompleted.await()
            true
        } == true
        if (!completedInTime) {
            diagnosticsStorage.recordRestoreFinished(
                PlaybackRestoreStatus.TIMED_OUT,
                "Stored playback restore timed out after ${RESTORE_TIMEOUT_MS}ms.",
            )
            restoreJob.cancel()
            diagnosticEventLogger.record("restore_timed_out")
            Log.w(TAG, "Stored playback restore timed out for book=${stored.bookId}.")
        }
    }

    private suspend fun restoreStoredPlayback(stored: StoredPlaybackState) {
        if (!PlaybackSnapshotPolicy.isRestorable(stored, System.currentTimeMillis())) {
            placeholderPlaybackState = null
            playbackStateStorage.clear()
            diagnosticsStorage.recordRestoreFinished(PlaybackRestoreStatus.SKIPPED, "Stored playback state is too old.")
            diagnosticEventLogger.record("restore_skipped", mapOf("reason" to "stored_state_too_old"))
            return
        }
        runCatching {
            val resolved = playbackRepository.resolveBook(stored.bookId)
            val startPosition = PlaybackQueueMath.locateStartPosition(
                resolved.playback.queue,
                stored.positionMs,
            )
            activeBook = resolved.playback
            configureAuthenticatedPlayback(resolved.accessToken)
            transientPlaybackRetryAttempt = 0
            player.setMediaItems(
                resolved.playback.toMedia3PlayableItems(),
                startPosition.trackIndex,
                startPosition.positionMs,
            )
            player.setPlaybackParameters(
                PlaybackParameters(stored.playbackSpeed, player.playbackParameters.pitch),
            )
            player.playWhenReady = false
            placeholderPlaybackState = null
            saveActivePlaybackState(wasPlaying = stored.wasPlaying)
            diagnosticsStorage.recordRestoreFinished(PlaybackRestoreStatus.SUCCESS)
            diagnosticEventLogger.record("restore_online_success")
            Log.i(TAG, "Restored playback state for book=${stored.bookId} at ${stored.positionMs}ms.")
        }.onFailure { exception ->
            if (exception is CancellationException) {
                throw exception
            }
            diagnosticsStorage.recordRestoreFinished(
                PlaybackRestoreStatus.FAILED,
                exception.message ?: exception::class.java.simpleName,
            )
            diagnosticEventLogger.record(
                "restore_failed",
                mapOf(
                    "category" to exception.restoreFailureCategory(),
                    "exception" to exception::class.java.simpleName,
                    "message" to exception.message,
                ),
            )
            Log.w(TAG, "Stored playback restore failed for book=${stored.bookId}.", exception)
        }
    }

    private fun restorePlaceholderAndPlay() {
        val stored = placeholderPlaybackState ?: return
        if (playbackRecoveryJob?.isActive == true) {
            return
        }
        playbackRecoveryJob = serviceScope.launch {
            runCatching {
                val resolved = playbackRepository.resolveBook(stored.bookId)
                val startPosition = PlaybackQueueMath.locateStartPosition(
                    resolved.playback.queue,
                    stored.positionMs,
                )
                activeBook = resolved.playback
                configureAuthenticatedPlayback(resolved.accessToken)
                transientPlaybackRetryAttempt = 0
                player.setMediaItems(
                    resolved.playback.toMedia3PlayableItems(),
                    startPosition.trackIndex,
                    startPosition.positionMs,
                )
                player.setPlaybackParameters(
                    PlaybackParameters(stored.playbackSpeed, player.playbackParameters.pitch),
                )
                player.prepare()
                player.playWhenReady = true
                placeholderPlaybackState = null
                saveActivePlaybackState(wasPlaying = true)
                diagnosticEventLogger.record("restore_placeholder_play_success")
                Log.i(TAG, "Resolved local playback placeholder on play for book=${stored.bookId}.")
            }.onFailure { exception ->
                if (exception is CancellationException) {
                    throw exception
                }
                diagnosticEventLogger.record(
                    "restore_placeholder_play_failed",
                    mapOf(
                        "category" to exception.restoreFailureCategory(),
                        "exception" to exception::class.java.simpleName,
                        "message" to exception.message,
                    ),
                )
                Log.w(TAG, "Could not resolve local playback placeholder for book=${stored.bookId}.", exception)
            }
        }
    }

    private fun recoverPlaybackAfterUnauthorized() {
        val currentBook = activeBook ?: return
        if (playbackRecoveryJob?.isActive == true) {
            return
        }
        val bookId = currentBook.bookId
        val resumePositionMs = logicalPlaybackPositionMs()
        val resumePlayWhenReady = player.playWhenReady
        playbackRecoveryJob = serviceScope.launch {
            runCatching {
                val playback = playbackRepository.resolveBook(bookId)
                val startPosition = PlaybackQueueMath.locateStartPosition(
                    playback.playback.queue,
                    resumePositionMs,
                )
                activeBook = playback.playback
                configureAuthenticatedPlayback(playback.accessToken)
                transientPlaybackRetryAttempt = 0
                player.setMediaItems(
                    playback.playback.toMedia3PlayableItems(),
                    startPosition.trackIndex,
                    startPosition.positionMs,
                )
                player.prepare()
                player.playWhenReady = resumePlayWhenReady
                saveActivePlaybackState(wasPlaying = resumePlayWhenReady)
                Log.i(TAG, "Recovered playback after unauthorized stream response for book=$bookId.")
            }.onFailure { exception ->
                Log.w(TAG, "Playback recovery failed for book=$bookId.", exception)
            }
        }
    }

    private fun recoverPlaybackAfterTransientNetworkError() {
        val currentBook = activeBook ?: return
        if (playbackRecoveryJob?.isActive == true || transientPlaybackRetryAttempt >= TRANSIENT_PLAYBACK_RETRY_DELAYS_MS.size) {
            return
        }
        val retryDelayMs = TRANSIENT_PLAYBACK_RETRY_DELAYS_MS[transientPlaybackRetryAttempt]
        transientPlaybackRetryAttempt += 1
        val resumePositionMs = logicalPlaybackPositionMs()
        val resumePlayWhenReady = player.playWhenReady
        playbackRecoveryJob = serviceScope.launch {
            delay(retryDelayMs)
            runCatching {
                val startPosition = PlaybackQueueMath.locateStartPosition(currentBook.queue, resumePositionMs)
                player.setMediaItems(
                    currentBook.toMedia3PlayableItems(),
                    startPosition.trackIndex,
                    startPosition.positionMs,
                )
                player.prepare()
                player.playWhenReady = resumePlayWhenReady
                saveActivePlaybackState(wasPlaying = resumePlayWhenReady)
                Log.i(TAG, "Retrying playback after transient stream error for book=${currentBook.bookId}.")
            }.onFailure { exception ->
                Log.w(TAG, "Transient playback retry failed for book=${currentBook.bookId}.", exception)
            }
        }
    }

    private fun configureAuthenticatedPlayback(accessToken: String) {
        activeAccessToken = accessToken
        httpDataSourceFactory.setUserAgent("ShelfDrive/${BuildConfig.VERSION_NAME}")
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
        if (reason == PlaybackProgressReason.ENDED || reason == PlaybackProgressReason.STOPPED) {
            playbackStateStorage.clear()
        } else {
            saveActivePlaybackState()
        }
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
        saveActivePlaybackState()
    }

    private fun seekBy(deltaMs: Long) {
        val durationMs = activeBook?.durationMs?.takeIf { it > 0L }
            ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val targetPositionMs = (logicalPlaybackPositionMs() + deltaMs)
            .coerceAtLeast(0L)
            .let { target -> durationMs?.let { max -> target.coerceAtMost(max) } ?: target }
        seekToLogicalPosition(targetPositionMs)
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

    private fun saveActivePlaybackState(wasPlaying: Boolean = player.playWhenReady) {
        val playback = activeBook ?: return
        playbackStateStorage.save(
            StoredPlaybackState(
                bookId = playback.bookId,
                title = playback.title,
                author = playback.author,
                artworkUri = playback.artworkUri,
                durationMs = playback.durationMs,
                positionMs = logicalPlaybackPositionMs(),
                trackIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                playbackSpeed = player.playbackParameters.speed,
                wasPlaying = wasPlaying,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun Throwable.restoreFailureCategory(): String {
        return when (this) {
            is ApiException -> if (statusCode == 401 || statusCode == 403) "auth" else "api_$statusCode"
            is IOException -> "network"
            else -> "unexpected"
        }
    }

    private fun updateMediaButtonPreferences() {
        if (::mediaLibrarySession.isInitialized) {
            mediaLibrarySession.setMediaButtonPreferences(
                sessionPolicy.mediaButtonPreferences(player.playbackParameters.speed),
            )
        }
    }

    private fun PlaybackException.isUnauthorizedResponse(): Boolean {
        return generateSequence(cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .any { it.responseCode == 401 }
    }

    private fun PlaybackException.isTransientNetworkResponse(): Boolean {
        return generateSequence(cause) { it.cause }
            .any { throwable ->
                when (throwable) {
                    is HttpDataSource.InvalidResponseCodeException ->
                        throwable.responseCode in TRANSIENT_HTTP_STATUS_CODES
                    is HttpDataSource.HttpDataSourceException -> true
                    is IOException -> true
                    else -> false
                }
            }
    }

    private inner class AudiobookProgressPlayer(delegate: Player) : ForwardingPlayer(delegate) {
        override fun getDuration(): Long {
            return activeBook?.durationMs?.takeIf { it > 0L } ?: super.getDuration()
        }

        override fun getCurrentPosition(): Long {
            return logicalPlaybackPositionMs()
        }

        override fun getBufferedPosition(): Long {
            return logicalBufferedPositionMs()
        }

        override fun getBufferedPercentage(): Int {
            val durationMs = duration
            if (durationMs == C.TIME_UNSET || durationMs <= 0L) {
                return super.getBufferedPercentage()
            }
            return ((bufferedPosition.coerceAtLeast(0L) * 100L) / durationMs)
                .coerceIn(0L, 100L)
                .toInt()
        }

        override fun getContentDuration(): Long {
            return duration
        }

        override fun getContentPosition(): Long {
            return currentPosition
        }

        override fun getContentBufferedPosition(): Long {
            return bufferedPosition
        }

        override fun seekTo(positionMs: Long) {
            seekToLogicalPosition(positionMs)
        }

        override fun seekBack() {
            seekBy(-SEEK_INCREMENT_MS)
        }

        override fun seekForward() {
            seekBy(SEEK_INCREMENT_MS)
        }

        override fun play() {
            if (activeBook == null && placeholderPlaybackState != null) {
                restorePlaceholderAndPlay()
            } else {
                if (activeBook != null && player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
                super.play()
            }
        }
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
        private const val RESTORE_TIMEOUT_MS = 8_000L
        private val TRANSIENT_PLAYBACK_RETRY_DELAYS_MS = listOf(1_000L, 3_000L, 8_000L, 15_000L)
        private val TRANSIENT_HTTP_STATUS_CODES = setOf(502, 503, 504)
    }
}

private class PlaybackResolutionException(
    override val message: String,
    override val cause: Throwable? = null,
) : IOException(message, cause)
