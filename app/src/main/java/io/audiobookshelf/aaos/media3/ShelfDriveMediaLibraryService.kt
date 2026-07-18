package io.audiobookshelf.aaos.media3

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.concurrent.futures.CallbackToFutureAdapter
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.preference.PreferenceManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.audiobookshelf.aaos.absapi.ApiException
import io.audiobookshelf.aaos.absapi.AudiobookshelfApiClient
import io.audiobookshelf.aaos.BuildConfig
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.absapi.ConnectivityMonitor
import io.audiobookshelf.aaos.auth.AuthCommands
import io.audiobookshelf.aaos.auth.AuthRepository
import io.audiobookshelf.aaos.auth.AuthStorage
import io.audiobookshelf.aaos.auth.AuthenticatedRequestRunner
import io.audiobookshelf.aaos.browser.BrowseNodeId
import io.audiobookshelf.aaos.browser.CatalogBrowseRepository
import io.audiobookshelf.aaos.cache.CacheCommands
import io.audiobookshelf.aaos.cache.CacheRepository
import io.audiobookshelf.aaos.cache.PlaybackAudioCache
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import io.audiobookshelf.aaos.catalog.persistence.MediaProgressEntity
import io.audiobookshelf.aaos.diagnostics.DiagnosticEventLogger
import io.audiobookshelf.aaos.diagnostics.PlaybackRestoreStatus
import io.audiobookshelf.aaos.diagnostics.StartupDiagnosticsStorage
import io.audiobookshelf.aaos.playback.AudiobookshelfPlaybackRepository
import io.audiobookshelf.aaos.playback.PlaybackCachePolicy
import io.audiobookshelf.aaos.playback.PlaybackPreferences
import io.audiobookshelf.aaos.playback.PlaybackQueueMath
import io.audiobookshelf.aaos.playback.PlaybackResolutionException
import io.audiobookshelf.aaos.playback.PlaybackResumePolicy
import io.audiobookshelf.aaos.playback.PlaybackSnapshotPolicy
import io.audiobookshelf.aaos.playback.PlaybackStateStorage
import io.audiobookshelf.aaos.playback.PlaybackTrack
import io.audiobookshelf.aaos.playback.QueueStartPosition
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlayback
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlaybackSession
import io.audiobookshelf.aaos.playback.StoredPlaybackState
import io.audiobookshelf.aaos.playback.toResolvedPlayback
import io.audiobookshelf.aaos.progress.PlaybackProgressReason
import io.audiobookshelf.aaos.progress.PlaybackProgressSnapshot
import io.audiobookshelf.aaos.progress.ProgressConflictPolicy
import io.audiobookshelf.aaos.progress.ProgressSyncRepository
import io.audiobookshelf.aaos.progress.ProgressUpdateDecision
import io.audiobookshelf.aaos.progress.ServerProgressLookup
import io.audiobookshelf.aaos.settings.SettingsActivity
import io.audiobookshelf.aaos.sync.CatalogSyncRepository
import io.audiobookshelf.aaos.sync.SyncCommands
import io.audiobookshelf.aaos.sync.SyncSnapshot
import io.audiobookshelf.aaos.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InterruptedIOException

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
    private lateinit var connectivityMonitor: ConnectivityMonitor
    private lateinit var diagnosticsStorage: StartupDiagnosticsStorage
    private lateinit var diagnosticEventLogger: DiagnosticEventLogger
    private lateinit var mediaCatalog: ShelfDriveMediaCatalog
    private lateinit var sessionPolicy: ShelfDriveSessionPolicy
    private lateinit var player: ExoPlayer
    private lateinit var sessionPlayer: Player
    private lateinit var mediaLibrarySession: MediaLibrarySession

    private lateinit var defaultSharedPreferences: SharedPreferences
    private val playbackPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == getString(R.string.settings_key_skip_increment) && ::player.isInitialized) {
                applySkipIncrement()
            }
        }

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    private var activeBook: ResolvedAudiobookPlayback? = null
    private var activePlaybackSessionId: String? = null
    private var lastProgressSampleElapsedRealtimeMs: Long? = null
    private var periodicProgressJob: Job? = null
    private var playbackRecoveryJob: Job? = null
    private var forwardCacheJob: Job? = null
    private var activeBookCacheJob: Job? = null
    private var catalogSyncJob: Job? = null
    private var transientRetryState: TransientRetryState = TransientRetryState.NONE
    private var lastTrackTransitionAtMs: Long? = null
    private var wasPlayWhenReady: Boolean = false
    private var placeholderPlaybackState: StoredPlaybackState? = null
    private val progressUpdateMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        diagnosticsStorage = StartupDiagnosticsStorage(this)
        diagnosticEventLogger = DiagnosticEventLogger(this)
        diagnosticsStorage.recordServiceStarted()

        defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        authStorage = AuthStorage(this)
        val apiClient = AudiobookshelfApiClient()
        authRepository = AuthRepository(storage = authStorage, apiClient = apiClient)
        val authenticatedRequestRunner = AuthenticatedRequestRunner(authStorage, authRepository)
        val database = CatalogDatabase.getInstance(this)
        browseRepository = CatalogBrowseRepository(database)
        mediaCatalog = ShelfDriveMediaCatalog(this, browseRepository)
        sessionPolicy = ShelfDriveSessionPolicy(this)
        syncRepository = CatalogSyncRepository(
            database = database,
            authenticatedRequestRunner = authenticatedRequestRunner,
            apiClient = apiClient,
        )
        progressSyncRepository = ProgressSyncRepository(
            database = database,
            authenticatedRequestRunner = authenticatedRequestRunner,
            apiClient = apiClient,
            diagnosticEventLogger = diagnosticEventLogger,
        )
        connectivityMonitor = ConnectivityMonitor(this)
        cacheRepository = CacheRepository(this, database)
        playbackRepository = AudiobookshelfPlaybackRepository(
            authenticatedRequestRunner = authenticatedRequestRunner,
            database = database,
            apiClient = apiClient,
        )
        playbackStateStorage = PlaybackStateStorage(this)
        diagnosticEventLogger.record("service_started")
        observeConnectivity()

        player = ExoPlayer.Builder(this)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        MIN_BUFFER_MS,
                        MAX_BUFFER_MS,
                        BUFFER_FOR_PLAYBACK_MS,
                        BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    PlaybackAudioCache.createDataSourceFactory(
                        this,
                        DefaultDataSource.Factory(this, httpDataSourceFactory),
                    ),
                ),
            )
            .setSeekBackIncrementMs(skipIncrementMs())
            .setSeekForwardIncrementMs(skipIncrementMs())
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
            .setCustomLayout(sessionPolicy.customLayout(player.playbackParameters.speed))
            .setMediaButtonPreferences(sessionPolicy.mediaButtonPreferences(player.playbackParameters.speed))
            .build()
        defaultSharedPreferences.registerOnSharedPreferenceChangeListener(playbackPreferenceChangeListener)

        restoreStoredPlaybackLocally()

        serviceScope.launch {
            runCatching {
                val initialAuth = authRepository.bootstrap()
                notifyBrowseTreeChanged()
                updateSyncSnapshot(syncRepository.loadSnapshot())
                diagnosticEventLogger.record("auth_bootstrap", mapOf("status" to initialAuth.status.name))
                if (initialAuth.isAuthenticated || hasStoredLoginCredentials()) {
                    restoreStoredPlaybackWithTimeout()
                }
                if (initialAuth.isAuthenticated) {
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
            }.onFailure { exception ->
                if (exception is CancellationException) {
                    throw exception
                }
                diagnosticEventLogger.record(
                    "startup_bootstrap_failed",
                    exceptionDiagnostics(exception),
                )
                Log.w(TAG, "Startup bootstrap failed. Keeping MediaLibrarySession available.", exception)
                notifyBrowseTreeChanged()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        diagnosticEventLogger.record(
            "session_requested",
            mapOf(
                "controllerPackage" to controllerInfo.packageName,
                "controllerUid" to controllerInfo.uid.toString(),
            ),
        )
        return mediaLibrarySession
    }

    override fun onDestroy() {
        if (::diagnosticEventLogger.isInitialized) {
            diagnosticEventLogger.record(
                "service_destroyed",
                mapOf(
                    "hasActiveBook" to (activeBook != null).toString(),
                    "playbackState" to if (::player.isInitialized) player.playbackState.toString() else null,
                    "playWhenReady" to if (::player.isInitialized) player.playWhenReady.toString() else null,
                ),
            )
        }
        periodicProgressJob?.cancel()
        playbackRecoveryJob?.cancel()
        forwardCacheJob?.cancel()
        activeBookCacheJob?.cancel()
        if (::connectivityMonitor.isInitialized) {
            connectivityMonitor.close()
        }
        if (::defaultSharedPreferences.isInitialized) {
            defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(playbackPreferenceChangeListener)
        }
        mediaLibrarySession.release()
        player.removeListener(this)
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            lastProgressSampleElapsedRealtimeMs = SystemClock.elapsedRealtime()
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
        if (playbackState == Player.STATE_BUFFERING) {
            cancelForwardCache()
        }
        if (playbackState == Player.STATE_ENDED) {
            cancelForwardCache()
            emitProgress(PlaybackProgressReason.ENDED)
            periodicProgressJob?.cancel()
            playbackStateStorage.clear()
        }
        if (playbackState == Player.STATE_READY) {
            resetTransientPlaybackRetry()
            saveActivePlaybackState()
            cacheForwardHorizon()
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            saveActivePlaybackState()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        cancelForwardCache()
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            return
        }
        lastTrackTransitionAtMs = System.currentTimeMillis()
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            emitProgress(PlaybackProgressReason.TRACK_CHANGED)
        }
        cacheForwardHorizon()
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        PlaybackPreferences.savePlaybackSpeed(this, playbackParameters.speed)
        saveActivePlaybackState()
    }

    override fun onPlayerError(error: PlaybackException) {
        cancelForwardCache()
        val activeTrack = activeBook?.queue?.getOrNull(currentGlobalTrackIndex())
        Log.e(
            TAG,
            "Playback failed for book=${activeBook?.bookId} track=${activeTrack?.contentUrl?.substringBefore("?")}",
            error,
        )
        diagnosticEventLogger.record(
            "player_error",
            buildMap {
                put("errorCode", error.errorCodeName)
                put("message", error.message)
                put("bookActive", (activeBook != null).toString())
                put("playbackState", player.playbackState.toString())
                put("playWhenReady", player.playWhenReady.toString())
                put("bufferedDurationMs", player.totalBufferedDuration.toString())
                put("networkAvailable", connectivityMonitor.networkAvailable.value.toString())
                put("networkValidated", connectivityMonitor.networkValidated.value.toString())
                putAll(playbackCacheDiagnostics())
            },
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
            val packagesForUid = packageManager.getPackagesForUid(controller.uid)?.joinToString(",")

            diagnosticEventLogger.record(
                "controller_connected",
                mapOf(
                    "controllerPackage" to controller.packageName,
                    "controllerUid" to controller.uid.toString(),
                    "uidPackages" to packagesForUid,
                ),
            )
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionPolicy.availableSessionCommands(controller))
                .setAvailablePlayerCommands(sessionPolicy.availablePlayerCommands())
                .setCustomLayout(sessionPolicy.customLayout(player.playbackParameters.speed))
                .setMediaButtonPreferences(sessionPolicy.mediaButtonPreferences(player.playbackParameters.speed))
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val isRecent = params?.isRecent == true
            diagnosticEventLogger.record(
                "browse_root_requested",
                mapOf(
                    "controllerPackage" to browser.packageName,
                    "controllerUid" to browser.uid.toString(),
                    "isRecent" to isRecent.toString(),
                ),
            )
            val rootItem = if (isRecent) {
                mediaCatalog.buildResumeRootItem()
            } else {
                mediaCatalog.buildRootItem()
            }
            return Futures.immediateFuture(
                LibraryResult.ofItem(
                    rootItem,
                    mediaCatalog.rootParams(params, isRecent),
                ),
            )
        }

        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            val effectiveParentId = parentId.normalizedBrowseParentId()
            diagnosticEventLogger.record(
                "browse_subscribed",
                mapOf(
                    "controllerPackage" to browser.packageName,
                    "controllerUid" to browser.uid.toString(),
                    "parentId" to parentId,
                    "effectiveParentId" to effectiveParentId,
                ),
            )
            session.notifyChildrenChanged(
                browser,
                effectiveParentId,
                browseChildCount(effectiveParentId),
                when (effectiveParentId) {
                    BrowseNodeId.Root.serialize() -> mediaCatalog.rootParams(params)
                    BrowseNodeId.Resume.serialize() -> mediaCatalog.rootParams(params, isRecent = true)
                    else -> params
                },
            )
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (!hasStoredLoginCredentials() && mediaId.requiresAuthentication()) {
                return Futures.immediateFuture(authRequiredResult(browser, mediaId, null))
            }
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
            val effectiveParentId = parentId.normalizedBrowseParentId()
            val node = BrowseNodeId.parse(effectiveParentId)
            if (
                !hasStoredLoginCredentials() &&
                effectiveParentId != BrowseNodeId.Root.serialize() &&
                effectiveParentId != BrowseNodeId.Resume.serialize()
            ) {
                return Futures.immediateFuture(authRequiredResult(browser, effectiveParentId, params))
            }
            when (node) {
                BrowseNodeId.Root,
                BrowseNodeId.Recent,
                BrowseNodeId.Books,
                BrowseNodeId.Authors,
                -> refreshCatalogIfStaleInBackground("browse:${node.serialize()}")

                else -> Unit
            }
            return serviceFuture("getChildren:$parentId") {
                val children = if (node == BrowseNodeId.Resume) {
                    playbackStateStorage.load()
                        ?.let(PlaybackSnapshotPolicy::placeholderMediaItem)
                        ?.let(::listOf)
                        .orEmpty()
                } else {
                    mediaCatalog.loadChildren(effectiveParentId)
                }
                val items = if (node == BrowseNodeId.Root || node == BrowseNodeId.Resume) {
                    children
                } else {
                    mediaCatalog.pageItems(children, page, pageSize)
                }
                diagnosticEventLogger.record(
                    "browse_children_loaded",
                    mapOf(
                        "controllerPackage" to browser.packageName,
                        "parentId" to parentId,
                        "effectiveParentId" to effectiveParentId,
                        "page" to page.toString(),
                        "pageSize" to pageSize.toString(),
                        "children" to children.size.toString(),
                        "returned" to items.size.toString(),
                    ),
                )
                LibraryResult.ofItemList(
                    items,
                    when (node) {
                        BrowseNodeId.Root -> mediaCatalog.rootParams(params)
                        BrowseNodeId.Resume -> mediaCatalog.rootParams(params, isRecent = true)
                        else -> params
                    },
                )
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            if (!hasStoredLoginCredentials()) {
                return Futures.immediateFuture(authRequiredResult(browser, "search:$query", params))
            }
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
            if (!hasStoredLoginCredentials()) {
                return Futures.immediateFuture(authRequiredResult(browser, "search:$query", params))
            }
            return serviceFuture("getSearchResult:$query") {
                LibraryResult.ofItemList(
                    mediaCatalog.pageItems(mediaCatalog.loadSearchResults(query), page, pageSize),
                    params,
                )
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            return serviceFuture("addMediaItems") {
                enrichRequestedMediaItems(mediaItems, controller)
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
                val enrichedMediaItems = enrichRequestedMediaItems(mediaItems, controller)
                val requestedItem = enrichedMediaItems.getOrNull(startIndex.takeIf { it >= 0 } ?: 0)
                    ?: enrichedMediaItems.firstOrNull()
                    ?: throw PlaybackResolutionException("Kein Medium ausgewaehlt.")
                Log.i(TAG, "Host requested playback for mediaId=${requestedItem.mediaId}.")
                publishRequestedPlaybackPlaceholder(requestedItem)
                val playback = resolveRequestedPlayback(requestedItem)
                Log.i(TAG, "Resolved playback for book=${playback.playback.bookId} tracks=${playback.playback.queue.size}.")
                updateActiveBook(playback.playback)
                activePlaybackSessionId = playback.sessionId
                placeholderPlaybackState = null
                configureAuthenticatedPlayback(playback.accessToken)
                resetTransientPlaybackRetry()
                playbackItemsWithStartPosition(playback.playback, playback.playback.startQueuePosition())
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return serviceFuture("playbackResumption") {
                val stored = playbackStateStorage.load()
                    ?: return@serviceFuture emptyPlaybackResumption(
                        reason = "no_stored_state",
                        controller = controller,
                        isForPlayback = isForPlayback,
                    )

                diagnosticEventLogger.record(
                    "playback_resumption_requested",
                    mapOf(
                        "controllerPackage" to controller.packageName,
                        "controllerUid" to controller.uid.toString(),
                        "isForPlayback" to isForPlayback.toString(),
                    ),
                )

                if (!isForPlayback) {
                    return@serviceFuture metadataOnlyPlaybackResumption(stored, controller)
                }

                stored.toResolvedPlayback()?.let { localPlayback ->
                    val existingSessionId = activePlaybackSessionId.takeIf { activeBook?.bookId == stored.bookId }
                    updateActiveBook(localPlayback)
                    activePlaybackSessionId = existingSessionId
                    placeholderPlaybackState = null
                    authStorage.load().accessToken
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::configureAuthenticatedPlayback)
                    player.setPlaybackParameters(
                        PlaybackParameters(stored.playbackSpeed, player.playbackParameters.pitch),
                    )
                    val startPosition = PlaybackQueueMath.locateStartPosition(
                        localPlayback.queue,
                        stored.positionMs,
                    )
                    diagnosticEventLogger.record(
                        "playback_resumption_local_manifest_returned",
                        mapOf(
                            "trackIndex" to startPosition.trackIndex.toString(),
                            "positionMs" to startPosition.positionMs.toString(),
                        ),
                    )
                    return@serviceFuture playbackItemsWithStartPosition(localPlayback, startPosition)
                }

                placeholderPlaybackResumption(
                    stored = stored,
                    reason = "legacy_state_without_manifest",
                    controller = controller,
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
                action == ShelfDriveSessionPolicy.CMD_SEEK_BACK -> serviceFuture(action) {
                    seekBy(-skipIncrementMs())
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                action == ShelfDriveSessionPolicy.CMD_SEEK_FORWARD -> serviceFuture(action) {
                    seekBy(skipIncrementMs())
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
                    notifyBrowseTreeChanged()
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                action == AuthCommands.CMD_LOGIN -> serviceFuture(action) {
                    val snapshot = authRepository.login(
                        requestedBaseUrl = args.getString(AuthCommands.EXTRA_SERVER_URL),
                        requestedUsername = args.getString(AuthCommands.EXTRA_USERNAME),
                        requestedPassword = args.getString(AuthCommands.EXTRA_PASSWORD),
                    )
                    notifyBrowseTreeChanged()
                    if (snapshot.isAuthenticated && player.playWhenReady) {
                        emitProgress(PlaybackProgressReason.STARTED)
                    }
                    val result = snapshot.toBundle()
                    if (snapshot.isAuthenticated) {
                        val syncSnapshot = syncRepository.syncNow()
                        if (syncSnapshot.status != SyncStatus.FAILED) {
                            progressSyncRepository.refreshInProgress()
                        }
                        updateSyncSnapshot(syncSnapshot)
                        result.putAll(syncSnapshot.toBundle())
                    }
                    SessionResult(SessionResult.RESULT_SUCCESS, result)
                }

                action == AuthCommands.CMD_LOGOUT -> serviceFuture(action) {
                    clearPlayback()
                    val snapshot = authRepository.logout()
                    cacheRepository.clearCache()
                    notifyBrowseTreeChanged()
                    updateSyncSnapshot(SyncSnapshot(status = SyncStatus.IDLE))
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                action == CacheCommands.CMD_GET_CACHE_STATE -> serviceFuture(action) {
                    SessionResult(SessionResult.RESULT_SUCCESS, cacheRepository.loadSnapshot().toBundle())
                }

                action == CacheCommands.CMD_CLEAR_CACHE -> serviceFuture(action) {
                    clearPlayback()
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

        private fun String.requiresAuthentication(): Boolean {
            return when (BrowseNodeId.parse(this)) {
                BrowseNodeId.Root,
                BrowseNodeId.Recent,
                BrowseNodeId.Resume,
                BrowseNodeId.Books,
                BrowseNodeId.Authors,
                -> false

                else -> true
            }
        }

        private fun <T : Any> authRequiredResult(
            controller: MediaSession.ControllerInfo,
            mediaId: String,
            params: LibraryParams?,
        ): LibraryResult<T> {
            recordAuthRequired(controller, mediaId)
            return LibraryResult.ofError<T>(authRequiredSessionError(), authRequiredParams(params))
        }

        private fun authRequiredSessionError(): SessionError {
            return SessionError(
                SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
                getString(R.string.media_auth_required_title),
            )
        }

        private fun authRequiredParams(params: LibraryParams?): LibraryParams {
            return LibraryParams.Builder()
                .setExtras(
                    Bundle(params?.extras ?: Bundle.EMPTY).apply {
                        putString(
                            MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT,
                            getString(R.string.settings_title),
                        )
                        putParcelable(
                            MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT,
                            PendingIntent.getActivity(
                                this@ShelfDriveMediaLibraryService,
                                AUTH_REQUIRED_SETTINGS_REQUEST_CODE,
                                Intent(this@ShelfDriveMediaLibraryService, SettingsActivity::class.java)
                                    .setAction(Intent.ACTION_APPLICATION_PREFERENCES),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                            ),
                        )
                    },
                )
                .build()
        }

        private fun recordAuthRequired(controller: MediaSession.ControllerInfo, mediaId: String) {
            diagnosticEventLogger.record(
                "browse_auth_required",
                mapOf(
                    "controllerPackage" to controller.packageName,
                    "controllerUid" to controller.uid.toString(),
                    "mediaId" to mediaId,
                ),
            )
        }
    }

    private fun clearPlayback() {
        player.stop()
        player.clearMediaItems()
        updateActiveBook(null)
        activePlaybackSessionId = null
        lastProgressSampleElapsedRealtimeMs = null
        placeholderPlaybackState = null
        resetTransientPlaybackRetry()
        playbackStateStorage.clear()
    }

    private fun metadataOnlyPlaybackResumption(
        stored: StoredPlaybackState,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.MediaItemsWithStartPosition {
        diagnosticEventLogger.record(
            "playback_resumption_metadata_returned",
            mapOf(
                "controllerPackage" to controller.packageName,
                "hasTitle" to (!stored.title.isNullOrBlank()).toString(),
            ),
        )
        return MediaSession.MediaItemsWithStartPosition(
            listOf(PlaybackSnapshotPolicy.placeholderMediaItem(stored)),
            0,
            stored.positionMs.coerceAtLeast(0L),
        )
    }

    private fun placeholderPlaybackResumption(
        stored: StoredPlaybackState,
        reason: String,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.MediaItemsWithStartPosition {
        placeholderPlaybackState = stored
        updateActiveBook(null)
        activePlaybackSessionId = null
        lastProgressSampleElapsedRealtimeMs = null
        resetTransientPlaybackRetry()
        player.setPlaybackParameters(
            PlaybackParameters(stored.playbackSpeed, player.playbackParameters.pitch),
        )
        startPlaybackRecovery(
            source = "playback_resumption",
            bookId = stored.bookId,
            positionMs = stored.positionMs,
            speed = stored.playbackSpeed,
            playWhenReady = true,
        )
        diagnosticEventLogger.record(
            "playback_resumption_placeholder_returned",
            mapOf(
                "reason" to reason,
                "controllerPackage" to controller.packageName,
            ),
        )
        return MediaSession.MediaItemsWithStartPosition(
            listOf(PlaybackSnapshotPolicy.placeholderMediaItem(stored)),
            0,
            stored.positionMs.coerceAtLeast(0L),
        )
    }

    private fun emptyPlaybackResumption(
        reason: String,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): MediaSession.MediaItemsWithStartPosition {
        val currentItems = (0 until player.mediaItemCount).map { index -> player.getMediaItemAt(index) }
        if (currentItems.isNotEmpty()) {
            val startIndex = player.currentMediaItemIndex
                .takeIf { it in currentItems.indices }
                ?: 0
            val startPositionMs = player.currentPosition.coerceAtLeast(0L)
            diagnosticEventLogger.record(
                "playback_resumption_current_returned",
                buildMap {
                    put("reason", reason)
                    put("controllerPackage", controller.packageName)
                    put("controllerUid", controller.uid.toString())
                    put("isForPlayback", isForPlayback.toString())
                    put("items", currentItems.size.toString())
                    put("startIndex", startIndex.toString())
                    put("startPositionMs", startPositionMs.toString())
                },
            )
            return MediaSession.MediaItemsWithStartPosition(
                currentItems,
                startIndex,
                startPositionMs,
            )
        }

        diagnosticEventLogger.record(
            "playback_resumption_empty",
            buildMap {
                put("reason", reason)
                put("controllerPackage", controller.packageName)
                put("controllerUid", controller.uid.toString())
                put("isForPlayback", isForPlayback.toString())
            },
        )
        return MediaSession.MediaItemsWithStartPosition(
            emptyList(),
            C.INDEX_UNSET,
            C.TIME_UNSET,
        )
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
        )
        updateActiveBook(null)
        activePlaybackSessionId = null
        lastProgressSampleElapsedRealtimeMs = null
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

    private fun restoreStoredPlaybackLocally() {
        val stored = playbackStateStorage.load()
            ?: return
        val playback = stored.toResolvedPlayback()
        if (playback == null) {
            publishStoredPlaybackPlaceholder(stored)
            return
        }

        updateActiveBook(playback)
        activePlaybackSessionId = null
        placeholderPlaybackState = null
        authStorage.load().accessToken
            ?.takeIf { it.isNotBlank() }
            ?.let(::configureAuthenticatedPlayback)
        val startPosition = playback.startQueuePosition()
        setPlaybackQueue(playback, startPosition)
        player.setPlaybackParameters(
            PlaybackParameters(stored.playbackSpeed, player.playbackParameters.pitch),
        )
        player.playWhenReady = false
        val startTrack = playback.queue.getOrNull(startPosition.trackIndex)
        val canPrepareFromCache = startTrack != null && PlaybackAudioCache.hasCachedDataAtPosition(
            context = this,
            cacheKey = PlaybackAudioCache.stableCacheKey(playback.bookId, startTrack.id),
            positionMs = startPosition.positionMs,
            durationMs = startTrack.durationMs,
        )
        if (canPrepareFromCache) {
            player.prepare()
        }
        diagnosticsStorage.recordRestoreStarted(stored.bookId)
        diagnosticEventLogger.record(
            "restore_local_manifest_loaded",
            mapOf(
                "tracks" to playback.queue.size.toString(),
                "cacheReady" to canPrepareFromCache.toString(),
            ),
        )
        Log.i(TAG, "Loaded local playback manifest for book=${stored.bookId}.")
    }

    private fun publishStoredPlaybackPlaceholder(stored: StoredPlaybackState) {
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
            ),
        )
        Log.i(TAG, "Published local playback placeholder for book=${stored.bookId}.")
    }

    private suspend fun restoreStoredPlaybackWithTimeout(playWhenResolved: Boolean = false) {
        val stored = playbackStateStorage.load()
        if (stored == null) {
            diagnosticsStorage.recordRestoreFinished(PlaybackRestoreStatus.SKIPPED, "No stored playback state.")
            diagnosticEventLogger.record("restore_skipped", mapOf("reason" to "no_stored_state"))
            return
        }
        diagnosticsStorage.recordRestoreStarted(stored.bookId)
        diagnosticEventLogger.record("restore_started", mapOf("hasBookId" to "true"))
        val completedInTime = withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
            restoreStoredPlayback(stored, playWhenResolved)
            true
        } == true
        if (!completedInTime) {
            diagnosticsStorage.recordRestoreFinished(
                PlaybackRestoreStatus.TIMED_OUT,
                "Stored playback restore timed out after ${RESTORE_TIMEOUT_MS}ms.",
            )
            diagnosticEventLogger.record("restore_timed_out")
            Log.w(TAG, "Stored playback restore timed out for book=${stored.bookId}.")
        }
    }

    private suspend fun restoreStoredPlayback(
        stored: StoredPlaybackState,
        playWhenResolved: Boolean,
    ) {
        runCatching {
            val localPositionMs = if (activeBook?.bookId == stored.bookId) {
                logicalPlaybackPositionMs()
            } else {
                stored.positionMs
            }
            resolveAndActivatePlayback(
                bookId = stored.bookId,
                positionMs = localPositionMs,
                speed = stored.playbackSpeed,
                playWhenReady = playWhenResolved,
                preserveMatchingQueue = true,
            )
            diagnosticsStorage.recordRestoreFinished(PlaybackRestoreStatus.SUCCESS)
            diagnosticEventLogger.record("restore_online_success")
            Log.i(TAG, "Connected restored playback for book=${stored.bookId} at ${localPositionMs}ms.")
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
                exceptionDiagnostics(exception),
            )
            Log.w(TAG, "Stored playback restore failed for book=${stored.bookId}.", exception)
        }
    }

    private fun recoverPlaybackAfterUnauthorized() {
        val currentBook = activeBook ?: return
        val bookId = currentBook.bookId
        startPlaybackRecovery(
            source = "unauthorized",
            bookId = bookId,
            positionMs = logicalPlaybackPositionMs(),
            speed = player.playbackParameters.speed,
            playWhenReady = player.playWhenReady,
        )
    }

    private fun startPlaybackRecovery(
        source: String,
        bookId: String,
        positionMs: Long,
        speed: Float,
        playWhenReady: Boolean,
    ) {
        if (playbackRecoveryJob?.isActive == true) {
            return
        }
        playbackRecoveryJob = serviceScope.launch {
            runCatching {
                resolveAndActivatePlayback(bookId, positionMs, speed, playWhenReady)
            }.onSuccess {
                diagnosticEventLogger.record(
                    "playback_recovery_success",
                    mapOf("source" to source, "bookId" to bookId),
                )
                Log.i(TAG, "Recovered playback from $source for book=$bookId.")
            }.onFailure { exception ->
                if (exception is CancellationException) {
                    throw exception
                }
                diagnosticEventLogger.record(
                    "playback_recovery_failed",
                    exceptionDiagnostics(
                        exception,
                        "source" to source,
                        "bookId" to bookId,
                    ),
                )
                Log.w(TAG, "Playback recovery from $source failed for book=$bookId.", exception)
            }
        }
    }

    private suspend fun resolveAndActivatePlayback(
        bookId: String,
        positionMs: Long,
        speed: Float,
        playWhenReady: Boolean,
        preserveMatchingQueue: Boolean = false,
    ) {
        val resolved = playbackRepository.resolveBook(bookId)
        val queueMatches = activeBook?.queue?.map(PlaybackTrack::id) == resolved.playback.queue.map(PlaybackTrack::id)
        activateResolvedPlayback(
            resolved = resolved,
            positionMs = positionMs,
            speed = speed,
            playWhenReady = playWhenReady,
            replaceQueue = !preserveMatchingQueue || !queueMatches || player.mediaItemCount == 0,
        )
    }

    private fun activateResolvedPlayback(
        resolved: ResolvedAudiobookPlaybackSession,
        positionMs: Long,
        speed: Float,
        playWhenReady: Boolean,
        replaceQueue: Boolean = true,
    ) {
        val startPosition = PlaybackQueueMath.locateStartPosition(resolved.playback.queue, positionMs)
        selectResolvedPlayback(resolved)
        if (replaceQueue) {
            setPlaybackQueue(resolved.playback, startPosition)
        }
        player.setPlaybackParameters(PlaybackParameters(speed, player.playbackParameters.pitch))
        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        player.playWhenReady = playWhenReady
        saveActivePlaybackState()
        if (playWhenReady) {
            emitProgress(PlaybackProgressReason.STARTED)
        }
    }

    private fun selectResolvedPlayback(resolved: ResolvedAudiobookPlaybackSession) {
        updateActiveBook(resolved.playback)
        activePlaybackSessionId = resolved.sessionId
        placeholderPlaybackState = null
        configureAuthenticatedPlayback(resolved.accessToken)
        resetTransientPlaybackRetry()
    }

    private fun recoverPlaybackAfterTransientNetworkError() {
        val currentBook = activeBook ?: return
        saveActivePlaybackState()
        if (!connectivityMonitor.networkValidated.value) {
            transientRetryState = TransientRetryState.WAITING_FOR_NETWORK
            diagnosticEventLogger.record(
                "playback_retry_deferred",
                mapOf("reason" to "network_unavailable", "bookId" to currentBook.bookId),
            )
            return
        }
        if (playbackRecoveryJob?.isActive == true || transientRetryState != TransientRetryState.NONE) {
            return
        }
        transientRetryState = TransientRetryState.RETRYING
        playbackRecoveryJob = serviceScope.launch {
            delay(TRANSIENT_PLAYBACK_RETRY_DELAY_MS)
            when {
                activeBook?.bookId != currentBook.bookId || !player.playWhenReady ->
                    resetTransientPlaybackRetry()
                !connectivityMonitor.networkValidated.value ->
                    transientRetryState = TransientRetryState.WAITING_FOR_NETWORK
                else -> {
                    player.prepare()
                    Log.i(TAG, "Retrying playback once after transient stream error for book=${currentBook.bookId}.")
                }
            }
        }
    }

    private fun playbackCacheDiagnostics(): Map<String, String?> {
        val playback = activeBook ?: return emptyMap()
        val trackIndex = currentGlobalTrackIndex()
        val track = playback.queue.getOrNull(trackIndex) ?: return emptyMap()
        val trackPositionMs = player.currentPosition.coerceAtLeast(0L)
        val currentStatus = PlaybackAudioCache.trackCacheStatus(
            context = this,
            cacheKey = PlaybackAudioCache.stableCacheKey(playback.bookId, track.id),
            positionMs = trackPositionMs,
            durationMs = track.durationMs,
        )
        val nextTrack = playback.queue.getOrNull(trackIndex + 1)
        val nextStatus = nextTrack?.let {
            PlaybackAudioCache.trackCacheStatus(
                context = this,
                cacheKey = PlaybackAudioCache.stableCacheKey(playback.bookId, it.id),
                positionMs = 0L,
                durationMs = it.durationMs,
            )
        }
        return mapOf(
            "bookId" to playback.bookId,
            "trackIndex" to trackIndex.toString(),
            "trackId" to track.id,
            "trackPositionMs" to trackPositionMs.toString(),
            "trackDurationMs" to track.durationMs?.toString(),
            "trackRemainingMs" to track.durationMs
                ?.minus(trackPositionMs)
                ?.coerceAtLeast(0L)
                ?.toString(),
            "lastTrackTransitionAtMs" to lastTrackTransitionAtMs?.toString(),
            "currentCachedBytes" to currentStatus.cachedBytes.toString(),
            "currentContentLengthBytes" to currentStatus.contentLengthBytes?.toString(),
            "currentCacheSpanCount" to currentStatus.spanCount.toString(),
            "currentContiguousBytesFromStart" to currentStatus.contiguousBytesFromStart.toString(),
            "currentContiguousDurationMs" to currentStatus.contiguousDurationMs?.toString(),
            "currentPositionCached" to currentStatus.hasDataAtPosition.toString(),
            "nextTrackIndex" to nextTrack?.let { (trackIndex + 1).toString() },
            "nextTrackId" to nextTrack?.id,
            "nextCachedBytes" to nextStatus?.cachedBytes?.toString(),
            "nextContentLengthBytes" to nextStatus?.contentLengthBytes?.toString(),
            "nextCacheSpanCount" to nextStatus?.spanCount?.toString(),
            "nextContiguousBytesFromStart" to nextStatus?.contiguousBytesFromStart?.toString(),
            "nextContiguousDurationMs" to nextStatus?.contiguousDurationMs?.toString(),
            "nextTrackFullyCached" to nextStatus?.isFullyCached?.toString(),
            "nextTrackStartCached" to nextStatus?.hasDataAtPosition?.toString(),
        )
    }

    private fun resetTransientPlaybackRetry() {
        transientRetryState = TransientRetryState.NONE
    }

    private fun updateActiveBook(playback: ResolvedAudiobookPlayback?) {
        val bookChanged = activeBook?.bookId != playback?.bookId
        val cancelledCacheJob = if (bookChanged) cancelForwardCache() else null
        activeBook = playback
        if (bookChanged) {
            playback?.let { retainActiveBookCache(it, cancelledCacheJob) }
        }
    }

    private fun retainActiveBookCache(
        playback: ResolvedAudiobookPlayback,
        cancelledCacheJob: Job?,
    ) {
        val previousCleanupJob = activeBookCacheJob
        previousCleanupJob?.cancel()
        activeBookCacheJob = serviceScope.launch {
            previousCleanupJob?.join()
            cancelledCacheJob?.join()
            runCatching {
                runInterruptible(Dispatchers.IO) {
                    PlaybackAudioCache.retainBook(
                        context = this@ShelfDriveMediaLibraryService,
                        bookId = playback.bookId,
                    )
                }
            }.onSuccess { cleanup ->
                diagnosticEventLogger.record(
                    "active_book_cache_retained",
                    mapOf(
                        "bookId" to playback.bookId,
                        "removedTracks" to cleanup.removedTrackCount.toString(),
                        "removedBytes" to cleanup.removedBytes.toString(),
                    ),
                )
            }.onFailure { exception ->
                if (exception is CancellationException) {
                    throw exception
                }
                Log.w(TAG, "Could not retain audio cache for book=${playback.bookId}.", exception)
            }
        }
    }

    private fun setPlaybackQueue(
        playback: ResolvedAudiobookPlayback,
        startPosition: QueueStartPosition,
    ) {
        val items = playback.toMedia3PlaybackItems()
        player.setMediaItems(items, startPosition.trackIndex, startPosition.positionMs)
    }

    private fun playbackItemsWithStartPosition(
        playback: ResolvedAudiobookPlayback,
        startPosition: QueueStartPosition,
    ): MediaSession.MediaItemsWithStartPosition {
        val items = playback.toMedia3PlaybackItems()
        return MediaSession.MediaItemsWithStartPosition(
            items,
            startPosition.trackIndex,
            startPosition.positionMs,
        )
    }

    private fun currentGlobalTrackIndex(): Int {
        return player.currentMediaItemIndex.coerceAtLeast(0)
    }

    private fun configureAuthenticatedPlayback(accessToken: String) {
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

    private fun cacheForwardHorizon() {
        val playback = activeBook ?: return
        if (
            player.playbackState != Player.STATE_READY ||
            !connectivityMonitor.networkValidated.value ||
            forwardCacheJob?.isActive == true
        ) {
            return
        }
        val currentTrackIndex = currentGlobalTrackIndex()
        val trackIndices = PlaybackCachePolicy.followingTrackIndices(playback.queue, currentTrackIndex)
        if (trackIndices.isEmpty()) {
            return
        }
        forwardCacheJob = serviceScope.launch {
            var cacheTrackIndex: Int? = null
            runCatching {
                trackIndices.forEach { trackIndex ->
                    while (
                        isForwardCacheRelevant(playback, currentTrackIndex) &&
                        !hasPlaybackBufferForForwardCache(playback, currentTrackIndex)
                    ) {
                        delay(FORWARD_CACHE_POLL_INTERVAL_MS)
                    }
                    if (!isForwardCacheRelevant(playback, currentTrackIndex)) {
                        return@runCatching
                    }
                    cacheTrackIndex = trackIndex
                    val track = playback.queue[trackIndex]
                    val cacheKey = PlaybackAudioCache.stableCacheKey(playback.bookId, track.id)
                    var lastProgressEventAtMs = 0L
                    runInterruptible(Dispatchers.IO) {
                        PlaybackAudioCache.cacheTrack(
                            context = this@ShelfDriveMediaLibraryService,
                            upstreamFactory = DefaultDataSource.Factory(
                                this@ShelfDriveMediaLibraryService,
                                httpDataSourceFactory,
                            ),
                            request = PlaybackAudioCache.TrackCacheRequest(
                                uri = track.contentUrl,
                                cacheKey = cacheKey,
                            ),
                            onProgress = { contentLengthBytes, cachedBytes ->
                                val now = SystemClock.elapsedRealtime()
                                val complete = contentLengthBytes != null && cachedBytes >= contentLengthBytes
                                if (
                                    lastProgressEventAtMs == 0L ||
                                    complete ||
                                    now - lastProgressEventAtMs >= CACHE_PROGRESS_EVENT_INTERVAL_MS
                                ) {
                                    lastProgressEventAtMs = now
                                    diagnosticEventLogger.record(
                                        "cache_track_progress",
                                        mapOf(
                                            "bookId" to playback.bookId,
                                            "trackIndex" to trackIndex.toString(),
                                            "cachedBytes" to cachedBytes.toString(),
                                            "contentLengthBytes" to contentLengthBytes?.toString(),
                                        ),
                                    )
                                }
                            },
                        )
                    }
                    val status = PlaybackAudioCache.trackCacheStatus(
                        context = this@ShelfDriveMediaLibraryService,
                        cacheKey = cacheKey,
                        positionMs = 0L,
                        durationMs = track.durationMs,
                    )
                    val trackDurationMs = track.durationMs?.takeIf { it > 0L }
                    val trackBytes = status.contentLengthBytes
                        ?: status.cachedBytes.takeIf { it > 0L }
                    val estimatedBitrateKbps = if (trackBytes != null && trackDurationMs != null) {
                        trackBytes * 8L / trackDurationMs
                    } else {
                        null
                    }
                    diagnosticEventLogger.record(
                        "cache_track_cached",
                        mapOf(
                            "bookId" to playback.bookId,
                            "trackIndex" to trackIndex.toString(),
                            "durationMs" to track.durationMs?.toString(),
                            "cachedBytes" to status.cachedBytes.toString(),
                            "contentLengthBytes" to status.contentLengthBytes?.toString(),
                            "spanCount" to status.spanCount.toString(),
                            "contiguousBytesFromStart" to status.contiguousBytesFromStart.toString(),
                            "contiguousDurationMs" to status.contiguousDurationMs?.toString(),
                            "estimatedBitrateKbps" to estimatedBitrateKbps?.toString(),
                        ),
                    )
                }
            }.onFailure { exception ->
                if (exception is CancellationException) {
                    throw exception
                }
                if (
                    exception is InterruptedIOException &&
                    !isForwardCacheRelevant(playback, currentTrackIndex)
                ) {
                    return@onFailure
                }
                diagnosticEventLogger.record(
                    "cache_track_failed",
                    mapOf(
                        "bookId" to playback.bookId,
                        "trackIndex" to cacheTrackIndex?.toString(),
                        "message" to exception.message,
                    ),
                )
                Log.w(TAG, "Forward cache failed for ${playback.bookId}.", exception)
            }
        }
    }

    private fun isForwardCacheRelevant(
        playback: ResolvedAudiobookPlayback,
        currentTrackIndex: Int,
    ): Boolean {
        return activeBook?.bookId == playback.bookId &&
            currentGlobalTrackIndex() == currentTrackIndex &&
            player.playbackState == Player.STATE_READY &&
            connectivityMonitor.networkValidated.value
    }

    private fun hasPlaybackBufferForForwardCache(
        playback: ResolvedAudiobookPlayback,
        currentTrackIndex: Int,
    ): Boolean {
        val trackDurationMs = playback.queue.getOrNull(currentTrackIndex)?.durationMs
        val remainingTrackMs = trackDurationMs
            ?.minus(player.currentPosition.coerceAtLeast(0L))
            ?.coerceAtLeast(0L)
        val requiredBufferMs = remainingTrackMs
            ?.coerceAtMost(MIN_CURRENT_TRACK_BUFFER_BEFORE_CACHE_MS)
            ?: MIN_CURRENT_TRACK_BUFFER_BEFORE_CACHE_MS
        return player.totalBufferedDuration + FORWARD_CACHE_BUFFER_TOLERANCE_MS >= requiredBufferMs
    }

    private fun cancelForwardCache(): Job? {
        val job = forwardCacheJob
        job?.cancel()
        forwardCacheJob = null
        return job
    }

    private fun emitProgress(reason: PlaybackProgressReason) {
        val playback = activeBook ?: return
        val timeListenedMs = consumeListeningTimeMs(reason)
        if (reason == PlaybackProgressReason.ENDED || reason == PlaybackProgressReason.STOPPED) {
            playbackStateStorage.clear()
        } else {
            saveActivePlaybackState()
        }
        val snapshot = PlaybackProgressSnapshot(
            bookId = playback.bookId,
            playbackSessionId = activePlaybackSessionId,
            currentTimeMs = logicalPlaybackPositionMs(),
            durationMs = playback.durationMs,
            timeListenedMs = timeListenedMs,
            isFinished = reason == PlaybackProgressReason.ENDED,
            reason = reason,
        )
        if (reason == PlaybackProgressReason.ENDED || reason == PlaybackProgressReason.STOPPED) {
            activePlaybackSessionId = null
            lastProgressSampleElapsedRealtimeMs = null
        }
        serviceScope.launch {
            runProgressUpdate(snapshot)
        }
    }

    private suspend fun runProgressUpdate(initialSnapshot: PlaybackProgressSnapshot) {
        progressUpdateMutex.withLock {
            if (!progressSyncRepository.storePendingProgress(initialSnapshot)) {
                return@withLock
            }
            if (!connectivityMonitor.networkAvailable.value) {
                return@withLock
            }

            if (initialSnapshot.reason == PlaybackProgressReason.SEEKED) {
                uploadProgress(latestProgressSnapshot(initialSnapshot))
                return@withLock
            }

            val serverLookup = progressSyncRepository.loadServerProgress(initialSnapshot.bookId)
            val currentSnapshot = latestProgressSnapshot(initialSnapshot)
            when (serverLookup) {
                ServerProgressLookup.Unavailable -> return@withLock
                ServerProgressLookup.Missing -> uploadProgress(currentSnapshot)
                is ServerProgressLookup.Found -> {
                    val serverProgress = serverLookup.progress
                    val decision = ProgressConflictPolicy.decide(currentSnapshot.currentTimeMs, serverProgress)
                    diagnosticEventLogger.record(
                        "active_progress_checked",
                        mapOf(
                            "bookId" to initialSnapshot.bookId,
                            "result" to decision::class.java.simpleName,
                            "localPositionMs" to currentSnapshot.currentTimeMs.toString(),
                            "serverPositionMs" to serverProgress.currentTimeMs.toString(),
                        ),
                    )
                    when (decision) {
                        ProgressUpdateDecision.UploadLocal -> uploadProgress(currentSnapshot)
                        is ProgressUpdateDecision.SeekForward -> applyServerProgress(
                            initialSnapshot = initialSnapshot,
                            serverProgress = serverProgress,
                            targetPositionMs = decision.positionMs,
                        )
                    }
                }
            }
        }
    }

    private fun latestProgressSnapshot(initial: PlaybackProgressSnapshot): PlaybackProgressSnapshot {
        if (activeBook?.bookId != initial.bookId) {
            return initial
        }
        return initial.copy(
            playbackSessionId = activePlaybackSessionId ?: initial.playbackSessionId,
            currentTimeMs = logicalPlaybackPositionMs(),
            lastUpdateAt = System.currentTimeMillis(),
        )
    }

    private suspend fun uploadProgress(snapshot: PlaybackProgressSnapshot) {
        val result = progressSyncRepository.uploadCheckedProgress(snapshot)
        if (result.uploaded && activeBook?.bookId == snapshot.bookId) {
            activePlaybackSessionId = result.sessionId
        }
        if (result.uploaded && snapshot.reason.shouldRefreshBrowse) {
            notifyBrowseTreeChanged()
        }
    }

    private suspend fun applyServerProgress(
        initialSnapshot: PlaybackProgressSnapshot,
        serverProgress: MediaProgressEntity,
        targetPositionMs: Long,
    ) {
        val playback = activeBook?.takeIf { it.bookId == initialSnapshot.bookId }
        if (playback == null) {
            progressSyncRepository.acceptServerProgress(serverProgress)
            return
        }
        if (activeBook?.bookId != playback.bookId) {
            return
        }

        val latestSnapshot = latestProgressSnapshot(initialSnapshot)
        val latestDecision = ProgressConflictPolicy.decide(latestSnapshot.currentTimeMs, serverProgress)
        if (latestDecision !is ProgressUpdateDecision.SeekForward) {
            uploadProgress(latestSnapshot)
            return
        }
        progressSyncRepository.acceptServerProgress(serverProgress)
        diagnosticEventLogger.record(
            "server_progress_seek_applied",
            mapOf(
                "bookId" to playback.bookId,
                "positionMs" to latestDecision.positionMs.toString(),
            ),
        )
        seekToLogicalPosition(latestDecision.positionMs)
    }

    private fun consumeListeningTimeMs(reason: PlaybackProgressReason): Long {
        val now = SystemClock.elapsedRealtime()
        val previous = lastProgressSampleElapsedRealtimeMs
        val listenedMs = previous?.let { (now - it).coerceAtLeast(0L) } ?: 0L
        lastProgressSampleElapsedRealtimeMs = if (
            player.isPlaying &&
            reason != PlaybackProgressReason.ENDED &&
            reason != PlaybackProgressReason.STOPPED
        ) {
            now
        } else {
            null
        }
        return listenedMs
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
        emitProgress(PlaybackProgressReason.SEEKED)
    }

    private fun skipIncrementMs(): Long {
        return PlaybackPreferences.skipIncrementMs(this)
    }

    private fun logicalPlaybackPositionMs(): Long {
        val playback = activeBook ?: return player.currentPosition.coerceAtLeast(0L)
        val queueTrack = playback.queue.getOrNull(currentGlobalTrackIndex())
            ?: playback.queue.firstOrNull()
            ?: return player.currentPosition.coerceAtLeast(0L)
        return (queueTrack.startOffsetMs + player.currentPosition.coerceAtLeast(0L))
            .coerceAtMost(playback.durationMs ?: Long.MAX_VALUE)
    }

    private fun logicalBufferedPositionMs(): Long {
        val playback = activeBook ?: return player.bufferedPosition.coerceAtLeast(0L)
        val queueTrack = playback.queue.getOrNull(currentGlobalTrackIndex())
            ?: playback.queue.firstOrNull()
            ?: return player.bufferedPosition.coerceAtLeast(0L)
        return (queueTrack.startOffsetMs + player.bufferedPosition.coerceAtLeast(0L))
            .coerceAtMost(playback.durationMs ?: Long.MAX_VALUE)
    }

    private fun saveActivePlaybackState() {
        val playback = activeBook ?: return
        playbackStateStorage.save(
            StoredPlaybackState(
                bookId = playback.bookId,
                title = playback.title,
                author = playback.author,
                artworkUri = playback.artworkUri,
                durationMs = playback.durationMs,
                positionMs = logicalPlaybackPositionMs(),
                queue = playback.queue,
                playbackSpeed = player.playbackParameters.speed,
            ),
        )
    }

    private fun updateMediaButtonPreferences() {
        if (::mediaLibrarySession.isInitialized) {
            mediaLibrarySession.setCustomLayout(
                sessionPolicy.customLayout(player.playbackParameters.speed),
            )
            mediaLibrarySession.setMediaButtonPreferences(
                sessionPolicy.mediaButtonPreferences(player.playbackParameters.speed),
            )
        }
    }

    private fun applySkipIncrement() {
        val incrementMs = skipIncrementMs()
        player.setSeekBackIncrementMs(incrementMs)
        player.setSeekForwardIncrementMs(incrementMs)
        updateMediaButtonPreferences()
    }

    private fun Throwable.restoreFailureCategory(): String {
        return when (this) {
            is ApiException -> if (statusCode == 401 || statusCode == 403) "auth" else "api_$statusCode"
            is IOException -> "network"
            else -> "unexpected"
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
        override fun getAvailableCommands(): Player.Commands {
            return super.getAvailableCommands()
                .buildUpon()
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                .remove(Player.COMMAND_SET_SPEED_AND_PITCH)
                .build()
        }

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
            emitProgress(PlaybackProgressReason.SEEKED)
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (activeBook == null) {
                super.seekTo(mediaItemIndex, positionMs)
                return
            }
            // The session exposes book-global position and duration; AAOS repeats the current
            // queue index when issuing a normal progress-bar seek.
            seekToLogicalPosition(positionMs)
            emitProgress(PlaybackProgressReason.SEEKED)
        }

        override fun seekBack() {
            seekBy(-skipIncrementMs())
        }

        override fun seekForward() {
            seekBy(skipIncrementMs())
        }

        override fun play() {
            val placeholder = placeholderPlaybackState
            if (activeBook == null && placeholder != null) {
                startPlaybackRecovery(
                    source = "play",
                    bookId = placeholder.bookId,
                    positionMs = placeholder.positionMs,
                    speed = placeholder.playbackSpeed,
                    playWhenReady = true,
                )
            } else {
                playbackRecoveryJob?.cancel()
                resetTransientPlaybackRetry()
                if (activeBook != null && player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
                super.play()
                emitProgress(PlaybackProgressReason.STARTED)
            }
        }
    }

    private fun observeConnectivity() {
        serviceScope.launch {
            var wasValidated = connectivityMonitor.networkValidated.value
            connectivityMonitor.networkValidated.collect { isValidated ->
                if (isValidated != wasValidated) {
                    diagnosticEventLogger.record(
                        "network_validation_changed",
                        mapOf(
                            "validated" to isValidated.toString(),
                            "bufferedDurationMs" to if (::player.isInitialized) {
                                player.totalBufferedDuration.toString()
                            } else {
                                null
                            },
                        ),
                    )
                }
                if (!isValidated) {
                    cancelForwardCache()
                }
                if (isValidated && !wasValidated) {
                    refreshCatalogIfStaleInBackground("network_return")
                    if (activeBook != null && activePlaybackSessionId == null) {
                        restoreStoredPlaybackWithTimeout(playWhenResolved = player.playWhenReady)
                    }
                    emitProgress(PlaybackProgressReason.PERIODIC)
                    progressSyncRepository.refreshInProgress()
                    retryActivePlaybackAfterNetworkReturn()
                    cacheForwardHorizon()
                    notifyBrowseTreeChanged()
                }
                wasValidated = isValidated
            }
        }
    }

    private fun refreshCatalogIfStaleInBackground(source: String) {
        if (
            !hasStoredLoginCredentials() ||
            !connectivityMonitor.networkValidated.value ||
            catalogSyncJob?.isActive == true
        ) {
            return
        }
        catalogSyncJob = serviceScope.launch {
            val previous = mediaCatalog.syncSnapshot
            runCatching {
                syncRepository.syncIfStale()
            }.onSuccess { snapshot ->
                if (snapshot != previous) {
                    updateSyncSnapshot(snapshot)
                    diagnosticEventLogger.record(
                        "background_catalog_sync_finished",
                        mapOf(
                            "source" to source,
                            "status" to snapshot.status.name,
                            "books" to snapshot.bookCount.toString(),
                        ),
                    )
                }
            }.onFailure { exception ->
                if (exception is CancellationException) {
                    throw exception
                }
                diagnosticEventLogger.record(
                    "background_catalog_sync_failed",
                    exceptionDiagnostics(exception, "source" to source),
                )
                Log.w(TAG, "Background catalog sync failed from $source.", exception)
            }
        }
    }

    private fun retryActivePlaybackAfterNetworkReturn() {
        if (
            activeBook != null &&
            player.playWhenReady &&
            transientRetryState == TransientRetryState.WAITING_FOR_NETWORK &&
            (player.playbackState == Player.STATE_IDLE || player.playerError != null)
        ) {
            playbackRecoveryJob?.cancel()
            transientRetryState = TransientRetryState.RETRYING
            player.prepare()
        }
    }

    private fun updateSyncSnapshot(snapshot: SyncSnapshot) {
        mediaCatalog.syncSnapshot = snapshot
        notifyBrowseTreeChanged()
    }

    private fun notifyBrowseTreeChanged() {
        if (!this::mediaLibrarySession.isInitialized) {
            return
        }
        notifyBrowseTreeChangedGlobally()
    }

    private fun notifyBrowseTreeChangedGlobally() {
        mediaLibrarySession.notifyChildrenChanged(
            BrowseNodeId.Root.serialize(),
            browseChildCount(BrowseNodeId.Root.serialize()),
            mediaCatalog.rootParams(null),
        )
        mediaLibrarySession.notifyChildrenChanged(
            BrowseNodeId.Resume.serialize(),
            browseChildCount(BrowseNodeId.Resume.serialize()),
            mediaCatalog.rootParams(null, isRecent = true),
        )
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Recent.serialize(), Int.MAX_VALUE, null)
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Books.serialize(), Int.MAX_VALUE, null)
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Authors.serialize(), Int.MAX_VALUE, null)
    }

    private fun browseChildCount(parentId: String): Int {
        return when (parentId) {
            BrowseNodeId.Root.serialize() -> ROOT_CHILD_COUNT
            BrowseNodeId.Resume.serialize() -> if (playbackStateStorage.load() == null) 0 else 1
            else -> Int.MAX_VALUE
        }
    }

    private fun hasStoredLoginCredentials(): Boolean {
        val stored = authStorage.load()
        val baseUrl = stored.baseUrl?.trim().orEmpty()
        val username = stored.username?.trim().orEmpty()
        val password = stored.password.orEmpty()
        return baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    }

    private fun String.normalizedBrowseParentId(): String {
        return trim().takeIf { it.isNotBlank() && BrowseNodeId.parse(it) != null }
            ?: BrowseNodeId.Root.serialize()
    }

    private suspend fun enrichRequestedMediaItems(
        mediaItems: List<MediaItem>,
        controller: MediaSession.ControllerInfo,
    ): List<MediaItem> {
        if (mediaItems.isEmpty()) {
            diagnosticEventLogger.record(
                "media_items_enriched",
                mapOf(
                    "controllerPackage" to controller.packageName,
                    "requested" to "0",
                    "enriched" to "0",
                    "fallback" to "0",
                ),
            )
            return emptyList()
        }

        var enrichedCount = 0
        var fallbackCount = 0
        val enrichedItems = mediaItems.mapNotNull { item ->
            val enriched = mediaCatalog.loadItem(item.mediaId)
            if (enriched != null) {
                enrichedCount += 1
                enriched
            } else {
                fallbackCount += 1
                diagnosticEventLogger.record(
                    "media_items_enrich_failed",
                    mapOf(
                        "controllerPackage" to controller.packageName,
                        "mediaId" to item.mediaId,
                    ),
                )
                item.takeIf { it.mediaId.isNotBlank() }
            }
        }
        diagnosticEventLogger.record(
            "media_items_enriched",
            mapOf(
                "controllerPackage" to controller.packageName,
                "requested" to mediaItems.size.toString(),
                "enriched" to enrichedCount.toString(),
                "fallback" to fallbackCount.toString(),
            ),
        )
        return enrichedItems
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
                    diagnosticEventLogger.record(
                        "media3_callback_failed",
                        exceptionDiagnostics(exception, "label" to label),
                    )
                    completer.setException(exception)
                } catch (throwable: Throwable) {
                    Log.e(TAG, "Media3 callback crashed: $label", throwable)
                    diagnosticEventLogger.record(
                        "media3_callback_crashed",
                        exceptionDiagnostics(throwable, "label" to label),
                    )
                    completer.setException(throwable)
                }
            }
            label
        }
    }

    private fun exceptionDiagnostics(
        throwable: Throwable,
        vararg details: Pair<String, String?>,
    ): Map<String, String?> {
        return buildMap {
            details.forEach { (key, value) -> put(key, value) }
            put("category", throwable.restoreFailureCategory())
            put("exception", throwable::class.java.simpleName)
            put("message", throwable.message)
            throwable.cause?.let { cause ->
                put("causeException", cause::class.java.simpleName)
                put("causeMessage", cause.message)
            }
        }
    }

    private fun ResolvedAudiobookPlayback.startQueuePosition(): QueueStartPosition {
        return QueueStartPosition(
            trackIndex = startIndex,
            positionMs = startPositionMs,
        )
    }

    companion object {
        private const val TAG = "ShelfDriveMedia3"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 30_000L
        private const val MIN_BUFFER_MS = 20 * 60_000
        private const val MAX_BUFFER_MS = 30 * 60_000
        private const val MIN_CURRENT_TRACK_BUFFER_BEFORE_CACHE_MS = 5L * 60L * 1_000L
        private const val FORWARD_CACHE_POLL_INTERVAL_MS = 1_000L
        private const val FORWARD_CACHE_BUFFER_TOLERANCE_MS = 1_000L
        private const val CACHE_PROGRESS_EVENT_INTERVAL_MS = 5_000L
        private const val BUFFER_FOR_PLAYBACK_MS = 2_500
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000
        private const val RESTORE_TIMEOUT_MS = 8_000L
        private const val AUTH_REQUIRED_SETTINGS_REQUEST_CODE = 1001
        private const val ROOT_CHILD_COUNT = 3
        private const val TRANSIENT_PLAYBACK_RETRY_DELAY_MS = 3_000L
        private val TRANSIENT_HTTP_STATUS_CODES = setOf(502, 503, 504)
    }

    private enum class TransientRetryState {
        NONE,
        WAITING_FOR_NETWORK,
        RETRYING,
    }
}
