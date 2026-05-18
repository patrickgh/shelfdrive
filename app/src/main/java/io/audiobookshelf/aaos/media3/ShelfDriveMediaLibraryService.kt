package io.audiobookshelf.aaos.media3

import android.app.PendingIntent
import android.content.Intent
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
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
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.auth.AuthCommands
import io.audiobookshelf.aaos.auth.AuthRepository
import io.audiobookshelf.aaos.auth.AuthSnapshot
import io.audiobookshelf.aaos.auth.AuthStatus
import io.audiobookshelf.aaos.auth.AuthStorage
import io.audiobookshelf.aaos.browser.BrowseNodeId
import io.audiobookshelf.aaos.browser.CatalogBrowseRepository
import io.audiobookshelf.aaos.cache.CacheCommands
import io.audiobookshelf.aaos.cache.CacheRepository
import io.audiobookshelf.aaos.catalog.persistence.CatalogDatabase
import io.audiobookshelf.aaos.diagnostics.DiagnosticEventLogger
import io.audiobookshelf.aaos.diagnostics.PlaybackRestoreStatus
import io.audiobookshelf.aaos.diagnostics.StartupDiagnosticsStorage
import io.audiobookshelf.aaos.playback.AudiobookshelfPlaybackRepository
import io.audiobookshelf.aaos.playback.PlaybackPreferences
import io.audiobookshelf.aaos.playback.PlaybackQueueMath
import io.audiobookshelf.aaos.playback.PlaybackResumePolicy
import io.audiobookshelf.aaos.playback.PlaybackSnapshotPolicy
import io.audiobookshelf.aaos.playback.PlaybackStateStorage
import io.audiobookshelf.aaos.playback.QueueStartPosition
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlayback
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlaybackSession
import io.audiobookshelf.aaos.playback.ResolvedPlaybackTrack
import io.audiobookshelf.aaos.playback.StoredPlaybackState
import io.audiobookshelf.aaos.playback.StoredPlaybackTrack
import io.audiobookshelf.aaos.progress.PlaybackProgressReason
import io.audiobookshelf.aaos.progress.PlaybackProgressSnapshot
import io.audiobookshelf.aaos.progress.ProgressSyncRepository
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
    private var activePlaybackSessionId: String? = null
    private var lastProgressSampleElapsedRealtimeMs: Long? = null
    private var periodicProgressJob: Job? = null
    private var playbackRecoveryJob: Job? = null
    private var transientPlaybackRetryAttempt: Int = 0
    private var wasPlayWhenReady: Boolean = false
    private var placeholderPlaybackState: StoredPlaybackState? = null

    override fun onCreate() {
        super.onCreate()
        diagnosticsStorage = StartupDiagnosticsStorage(this)
        diagnosticEventLogger = DiagnosticEventLogger(this)
        diagnosticsStorage.recordServiceStarted()
        traceMethod("onCreate")

        authStorage = AuthStorage(this)
        authRepository = AuthRepository(storage = authStorage)
        val database = CatalogDatabase.getInstance(this)
        browseRepository = CatalogBrowseRepository(database)
        mediaCatalog = ShelfDriveMediaCatalog(this, browseRepository)
        mediaCatalog.hasStoredLoginCredentials = hasStoredLoginCredentials()
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
            diagnosticEventLogger = diagnosticEventLogger,
        )
        cacheRepository = CacheRepository(this, database)
        playbackRepository = AudiobookshelfPlaybackRepository(
            authRepository = authRepository,
            authStorage = authStorage,
            database = database,
        )
        playbackStateStorage = PlaybackStateStorage(this)
        traceMethod("onCreate:diagnosticsInitialized")
        diagnosticEventLogger.record("service_started")

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(this, httpDataSourceFactory),
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
            .setCustomLayout(sessionPolicy.customLayout(player.playbackParameters.speed))
            .setMediaButtonPreferences(sessionPolicy.mediaButtonPreferences(player.playbackParameters.speed))
            .build()

        publishStoredPlaybackPlaceholder()

        serviceScope.launch {
            runCatching {
                val initialAuth = authRepository.bootstrap()
                updateAuthSnapshot(initialAuth)
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
                updateAuthSnapshot(AuthSnapshot(status = AuthStatus.LOGGED_OUT))
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        traceMethod(
            "onGetSession",
            mapOf(
                "controllerPackage" to controllerInfo.packageName,
                "controllerUid" to controllerInfo.uid.toString(),
            ),
        )
        diagnosticEventLogger.record(
            "session_requested",
            mapOf(
                "controllerPackage" to controllerInfo.packageName,
                "controllerUid" to controllerInfo.uid.toString(),
            ),
        )
        return mediaLibrarySession
    }

    private fun traceMethod(method: String, details: Map<String, String?> = emptyMap()) {
        if (::diagnosticEventLogger.isInitialized) {
            diagnosticEventLogger.record(
                "library_service_method_entered",
                buildMap {
                    put("method", method)
                    putAll(details)
                },
            )
        } else {
            Log.d(TAG, "LibraryService method entered before diagnostics init: $method")
        }
    }

    override fun onDestroy() {
        traceMethod("onDestroy")
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
        mediaLibrarySession.release()
        player.removeListener(this)
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        traceMethod("onIsPlayingChanged", mapOf("isPlaying" to isPlaying.toString()))
        if (isPlaying) {
            lastProgressSampleElapsedRealtimeMs = SystemClock.elapsedRealtime()
            startPeriodicProgressUpdates()
        } else {
            periodicProgressJob?.cancel()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        traceMethod(
            "onPlayWhenReadyChanged",
            mapOf(
                "playWhenReady" to playWhenReady.toString(),
                "reason" to reason.toString(),
            ),
        )
        if (wasPlayWhenReady && !playWhenReady && player.playbackState != Player.STATE_ENDED) {
            applyRewindOnPauseIfEnabled()
            emitProgress(PlaybackProgressReason.PAUSED)
        }
        wasPlayWhenReady = playWhenReady
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        traceMethod("onPlaybackStateChanged", mapOf("playbackState" to playbackState.toString()))
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
        traceMethod("onPositionDiscontinuity", mapOf("reason" to reason.toString()))
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            emitProgress(PlaybackProgressReason.SEEKED)
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        traceMethod(
            "onMediaItemTransition",
            mapOf(
                "mediaId" to mediaItem?.mediaId,
                "reason" to reason.toString(),
            ),
        )
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            emitProgress(PlaybackProgressReason.TRACK_CHANGED)
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        traceMethod("onPlaybackParametersChanged", mapOf("speed" to playbackParameters.speed.toString()))
        PlaybackPreferences.savePlaybackSpeed(this, playbackParameters.speed)
        saveActivePlaybackState()
    }

    override fun onPlayerError(error: PlaybackException) {
        traceMethod(
            "onPlayerError",
            mapOf(
                "errorCode" to error.errorCodeName,
                "message" to error.message,
            ),
        )
        val activeTrack = activeBook?.queue?.getOrNull(currentGlobalTrackIndex())
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
            traceMethod(
                "LibraryCallback.onConnect",
                mapOf(
                    "controllerPackage" to controller.packageName,
                    "controllerUid" to controller.uid.toString(),
                ),
            )
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
            traceMethod(
                "LibraryCallback.onGetLibraryRoot",
                mapOf(
                    "controllerPackage" to browser.packageName,
                    "controllerUid" to browser.uid.toString(),
                ),
            )
            diagnosticEventLogger.record(
                "browse_root_requested",
                mapOf(
                    "controllerPackage" to browser.packageName,
                    "controllerUid" to browser.uid.toString(),
                ),
            )
            return Futures.immediateFuture(LibraryResult.ofItem(mediaCatalog.buildRootItem(), mediaCatalog.rootParams(params)))
        }

        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            traceMethod(
                "LibraryCallback.onSubscribe",
                mapOf(
                    "controllerPackage" to browser.packageName,
                    "controllerUid" to browser.uid.toString(),
                    "parentId" to parentId,
                ),
            )
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
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            traceMethod(
                "LibraryCallback.onGetItem",
                mapOf(
                    "controllerPackage" to browser.packageName,
                    "mediaId" to mediaId,
                ),
            )
            if (!hasStoredLoginCredentials() && mediaId.requiresAuthentication()) {
                return Futures.immediateFuture(authRequiredItemResult(browser, mediaId, null))
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
            traceMethod(
                "LibraryCallback.onGetChildren",
                mapOf(
                    "controllerPackage" to browser.packageName,
                    "parentId" to parentId,
                    "page" to page.toString(),
                    "pageSize" to pageSize.toString(),
                ),
            )
            val effectiveParentId = parentId.normalizedBrowseParentId()
            if (!hasStoredLoginCredentials() && effectiveParentId != BrowseNodeId.Root.serialize()) {
                return Futures.immediateFuture(authRequiredItemListResult(browser, effectiveParentId, params))
            }
            return serviceFuture("getChildren:$parentId") {
                val children = mediaCatalog.loadChildren(effectiveParentId)
                val node = BrowseNodeId.parse(effectiveParentId)
                val items = if (node == BrowseNodeId.Root) {
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
                    if (node == BrowseNodeId.Root) mediaCatalog.rootParams(params) else params,
                )
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            traceMethod(
                "LibraryCallback.onSearch",
                mapOf(
                    "controllerPackage" to browser.packageName,
                    "query" to query,
                ),
            )
            if (!hasStoredLoginCredentials()) {
                return Futures.immediateFuture(authRequiredVoidResult(browser, "search:$query", params))
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
            traceMethod(
                "LibraryCallback.onGetSearchResult",
                mapOf(
                    "controllerPackage" to browser.packageName,
                    "query" to query,
                    "page" to page.toString(),
                    "pageSize" to pageSize.toString(),
                ),
            )
            if (!hasStoredLoginCredentials()) {
                return Futures.immediateFuture(authRequiredItemListResult(browser, "search:$query", params))
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
            traceMethod(
                "LibraryCallback.onAddMediaItems",
                mapOf(
                    "controllerPackage" to controller.packageName,
                    "items" to mediaItems.size.toString(),
                ),
            )
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
            traceMethod(
                "LibraryCallback.onSetMediaItems",
                mapOf(
                    "controllerPackage" to controller.packageName,
                    "items" to mediaItems.size.toString(),
                    "startIndex" to startIndex.toString(),
                    "startPositionMs" to startPositionMs.toString(),
                ),
            )
            return serviceFuture("setMediaItems") {
                val enrichedMediaItems = enrichRequestedMediaItems(mediaItems, controller)
                val requestedItem = enrichedMediaItems.getOrNull(startIndex.takeIf { it >= 0 } ?: 0)
                    ?: enrichedMediaItems.firstOrNull()
                    ?: throw PlaybackResolutionException("Kein Medium ausgewaehlt.")
                Log.i(TAG, "Host requested playback for mediaId=${requestedItem.mediaId}.")
                publishRequestedPlaybackPlaceholder(requestedItem)
                val playback = resolveRequestedPlayback(requestedItem)
                Log.i(TAG, "Resolved playback for book=${playback.playback.bookId} tracks=${playback.playback.queue.size}.")
                activeBook = playback.playback
                activePlaybackSessionId = playback.sessionId
                placeholderPlaybackState = null
                configureAuthenticatedPlayback(playback.accessToken)
                transientPlaybackRetryAttempt = 0
                playbackItemsWithStartPosition(playback.playback, playback.playback.startQueuePosition())
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            traceMethod(
                "LibraryCallback.onPlaybackResumption",
                mapOf(
                    "controllerPackage" to controller.packageName,
                    "controllerUid" to controller.uid.toString(),
                    "isForPlayback" to isForPlayback.toString(),
                ),
            )
            return serviceFuture("playbackResumption") {
                val stored = playbackStateStorage.load()
                    ?: return@serviceFuture emptyPlaybackResumption(
                        reason = "no_stored_state",
                        controller = controller,
                        isForPlayback = isForPlayback,
                        allowCurrentPlayerFallback = true,
                    )
                if (!PlaybackSnapshotPolicy.isRestorable(stored, System.currentTimeMillis())) {
                    playbackStateStorage.clear()
                    return@serviceFuture emptyPlaybackResumption(
                        reason = "stored_state_too_old",
                        controller = controller,
                        isForPlayback = isForPlayback,
                        bookId = stored.bookId,
                        allowCurrentPlayerFallback = false,
                    )
                }

                diagnosticEventLogger.record(
                    "playback_resumption_requested",
                    mapOf(
                        "controllerPackage" to controller.packageName,
                        "controllerUid" to controller.uid.toString(),
                        "isForPlayback" to isForPlayback.toString(),
                        "wasPlaying" to stored.wasPlaying.toString(),
                    ),
                )

                resolveStoredPlaybackForSession(stored)
                    ?: placeholderPlaybackResumption(
                        stored = stored,
                        reason = "stored_state_unresolved",
                        controller = controller,
                        isForPlayback = isForPlayback,
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
            traceMethod(
                "LibraryCallback.onCustomCommand",
                mapOf(
                    "controllerPackage" to controller.packageName,
                    "action" to action,
                ),
            )
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
                    activePlaybackSessionId = null
                    lastProgressSampleElapsedRealtimeMs = null
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
                    activePlaybackSessionId = null
                    lastProgressSampleElapsedRealtimeMs = null
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

        private fun String.requiresAuthentication(): Boolean {
            return when (BrowseNodeId.parse(this)) {
                BrowseNodeId.Root,
                BrowseNodeId.Recent,
                BrowseNodeId.Books,
                BrowseNodeId.Authors,
                -> false

                else -> true
            }
        }

        private fun authRequiredItemResult(
            controller: MediaSession.ControllerInfo,
            mediaId: String,
            params: LibraryParams?,
        ): LibraryResult<MediaItem> {
            recordAuthRequired(controller, mediaId)
            return LibraryResult.ofError(authRequiredSessionError(), authRequiredParams(params))
        }

        private fun authRequiredItemListResult(
            controller: MediaSession.ControllerInfo,
            mediaId: String,
            params: LibraryParams?,
        ): LibraryResult<ImmutableList<MediaItem>> {
            recordAuthRequired(controller, mediaId)
            return LibraryResult.ofError(authRequiredSessionError(), authRequiredParams(params))
        }

        private fun authRequiredVoidResult(
            controller: MediaSession.ControllerInfo,
            mediaId: String,
            params: LibraryParams?,
        ): LibraryResult<Void> {
            recordAuthRequired(controller, mediaId)
            return LibraryResult.ofError(authRequiredSessionError(), authRequiredParams(params))
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

    private suspend fun resolveStoredPlaybackForSession(
        stored: StoredPlaybackState,
    ): MediaSession.MediaItemsWithStartPosition? {
        traceMethod("resolveStoredPlaybackForSession", mapOf("bookId" to stored.bookId))
        return runCatching {
            val resolved = playbackRepository.resolveBook(stored.bookId)
            val startPosition = PlaybackQueueMath.locateStartPosition(
                resolved.playback.queue,
                stored.positionMs,
            )
            activeBook = resolved.playback
            activePlaybackSessionId = resolved.sessionId
            placeholderPlaybackState = null
            configureAuthenticatedPlayback(resolved.accessToken)
            transientPlaybackRetryAttempt = 0
            player.setPlaybackParameters(
                PlaybackParameters(stored.playbackSpeed, player.playbackParameters.pitch),
            )
            diagnosticEventLogger.record(
                "playback_resumption_resolved",
                mapOf(
                    "trackIndex" to startPosition.trackIndex.toString(),
                    "positionMs" to startPosition.positionMs.toString(),
                ),
            )
            playbackItemsWithStartPosition(resolved.playback, startPosition)
        }.getOrElse { exception ->
            if (exception is CancellationException) {
                throw exception
            }
            diagnosticEventLogger.record(
                "playback_resumption_resolve_failed",
                mapOf(
                    "category" to exception.restoreFailureCategory(),
                    "exception" to exception::class.java.simpleName,
                    "message" to exception.message,
                ),
            )
            Log.w(TAG, "Could not resolve playback resumption for book=${stored.bookId}.", exception)
            null
        }
    }

    private fun placeholderPlaybackResumption(
        stored: StoredPlaybackState,
        reason: String,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): MediaSession.MediaItemsWithStartPosition {
        traceMethod(
            "placeholderPlaybackResumption",
            mapOf(
                "bookId" to stored.bookId,
                "reason" to reason,
                "controllerPackage" to controller.packageName,
                "isForPlayback" to isForPlayback.toString(),
            ),
        )
        placeholderPlaybackState = stored
        activeBook = null
        activePlaybackSessionId = null
        lastProgressSampleElapsedRealtimeMs = null
        transientPlaybackRetryAttempt = 0
        player.setPlaybackParameters(
            PlaybackParameters(stored.playbackSpeed, player.playbackParameters.pitch),
        )
        if (isForPlayback) {
            startPlaceholderPlaybackRecovery(stored, playWhenResolved = true, source = "playback_resumption")
        }
        diagnosticEventLogger.record(
            "playback_resumption_placeholder_returned",
            mapOf(
                "reason" to reason,
                "controllerPackage" to controller.packageName,
                "isForPlayback" to isForPlayback.toString(),
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
        bookId: String? = null,
        allowCurrentPlayerFallback: Boolean,
    ): MediaSession.MediaItemsWithStartPosition {
        traceMethod(
            "emptyPlaybackResumption",
            mapOf(
                "reason" to reason,
                "controllerPackage" to controller.packageName,
                "isForPlayback" to isForPlayback.toString(),
                "bookId" to bookId,
                "allowCurrentPlayerFallback" to allowCurrentPlayerFallback.toString(),
            ),
        )
        val currentItems = if (allowCurrentPlayerFallback) {
            (0 until player.mediaItemCount).map { index -> player.getMediaItemAt(index) }
        } else {
            emptyList()
        }
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
                    if (!bookId.isNullOrBlank()) {
                        put("bookId", bookId)
                    }
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
                if (!bookId.isNullOrBlank()) {
                    put("bookId", bookId)
                }
            },
        )
        return MediaSession.MediaItemsWithStartPosition(
            emptyList(),
            C.INDEX_UNSET,
            C.TIME_UNSET,
        )
    }

    private suspend fun resolveRequestedPlayback(requestedItem: MediaItem): ResolvedAudiobookPlaybackSession {
        traceMethod("resolveRequestedPlayback", mapOf("mediaId" to requestedItem.mediaId))
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
        traceMethod("publishRequestedPlaybackPlaceholder", mapOf("mediaId" to requestedItem.mediaId))
        val requestedBookId = (BrowseNodeId.parse(requestedItem.mediaId) as? BrowseNodeId.Book)?.bookId
            ?: return
        val placeholderState = PlaybackSnapshotPolicy.storedStateFromBrowseItem(
            bookId = requestedBookId,
            item = requestedItem,
            nowMs = System.currentTimeMillis(),
        )
        activeBook = null
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

    private fun publishStoredPlaybackPlaceholder() {
        traceMethod("publishStoredPlaybackPlaceholder")
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
        traceMethod("restoreStoredPlaybackWithTimeout")
        val stored = playbackStateStorage.load()
        if (stored == null) {
            diagnosticsStorage.recordRestoreFinished(PlaybackRestoreStatus.SKIPPED, "No stored playback state.")
            diagnosticEventLogger.record("restore_skipped", mapOf("reason" to "no_stored_state"))
            return
        }
        diagnosticsStorage.recordRestoreStarted(stored.bookId)
        diagnosticEventLogger.record("restore_started", mapOf("hasBookId" to "true"))
        val completedInTime = withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
            restoreStoredPlayback(stored)
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

    private suspend fun restoreStoredPlayback(stored: StoredPlaybackState) {
        traceMethod("restoreStoredPlayback", mapOf("bookId" to stored.bookId))
        if (!PlaybackSnapshotPolicy.isRestorable(stored, System.currentTimeMillis())) {
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
            activePlaybackSessionId = resolved.sessionId
            placeholderPlaybackState = null
            configureAuthenticatedPlayback(resolved.accessToken)
            transientPlaybackRetryAttempt = 0
            setPlaybackQueue(resolved.playback, startPosition)
            player.setPlaybackParameters(
                PlaybackParameters(stored.playbackSpeed, player.playbackParameters.pitch),
            )
            player.playWhenReady = false
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
        traceMethod("restorePlaceholderAndPlay")
        val stored = placeholderPlaybackState ?: return
        startPlaceholderPlaybackRecovery(stored, playWhenResolved = true, source = "play")
    }

    private fun startPlaceholderPlaybackRecovery(
        stored: StoredPlaybackState,
        playWhenResolved: Boolean,
        source: String,
    ) {
        traceMethod(
            "startPlaceholderPlaybackRecovery",
            mapOf(
                "bookId" to stored.bookId,
                "playWhenResolved" to playWhenResolved.toString(),
                "source" to source,
            ),
        )
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
                activePlaybackSessionId = resolved.sessionId
                placeholderPlaybackState = null
                configureAuthenticatedPlayback(resolved.accessToken)
                transientPlaybackRetryAttempt = 0
                setPlaybackQueue(resolved.playback, startPosition)
                player.setPlaybackParameters(
                    PlaybackParameters(stored.playbackSpeed, player.playbackParameters.pitch),
                )
                player.prepare()
                player.playWhenReady = playWhenResolved
                saveActivePlaybackState(wasPlaying = playWhenResolved)
            }.onSuccess {
                diagnosticEventLogger.record(
                    "restore_placeholder_play_success",
                    mapOf("source" to source),
                )
                Log.i(TAG, "Resolved local playback placeholder on $source for book=${stored.bookId}.")
            }.onFailure { exception ->
                if (exception is CancellationException) {
                    throw exception
                }
                diagnosticEventLogger.record(
                    "restore_placeholder_play_failed",
                    mapOf(
                        "source" to source,
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
        traceMethod("recoverPlaybackAfterUnauthorized")
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
                activePlaybackSessionId = playback.sessionId
                configureAuthenticatedPlayback(playback.accessToken)
                transientPlaybackRetryAttempt = 0
                setPlaybackQueue(playback.playback, startPosition)
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
        traceMethod("recoverPlaybackAfterTransientNetworkError")
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
                setPlaybackQueue(currentBook, startPosition)
                player.prepare()
                player.playWhenReady = resumePlayWhenReady
                saveActivePlaybackState(wasPlaying = resumePlayWhenReady)
                Log.i(TAG, "Retrying playback after transient stream error for book=${currentBook.bookId}.")
            }.onFailure { exception ->
                Log.w(TAG, "Transient playback retry failed for book=${currentBook.bookId}.", exception)
            }
        }
    }

    private fun setPlaybackQueue(
        playback: ResolvedAudiobookPlayback,
        startPosition: QueueStartPosition,
    ) {
        val items = playback.toMedia3PlaybackItems()
        player.setMediaItems(items, startPosition.trackIndex, startPosition.positionMs)
        recordPlaybackQueue(playback, items.size, "set_player")
    }

    private fun playbackItemsWithStartPosition(
        playback: ResolvedAudiobookPlayback,
        startPosition: QueueStartPosition,
    ): MediaSession.MediaItemsWithStartPosition {
        val items = playback.toMedia3PlaybackItems()
        recordPlaybackQueue(playback, items.size, "session_result")
        return MediaSession.MediaItemsWithStartPosition(
            items,
            startPosition.trackIndex,
            startPosition.positionMs,
        )
    }

    private fun currentGlobalTrackIndex(): Int {
        return player.currentMediaItemIndex.coerceAtLeast(0)
    }

    private fun recordPlaybackQueue(
        playback: ResolvedAudiobookPlayback,
        itemCount: Int,
        source: String,
    ) {
        diagnosticEventLogger.record(
            "playback_queue_selected",
            mapOf(
                "source" to source,
                "bookId" to playback.bookId,
                "queueSize" to playback.queue.size.toString(),
                "items" to itemCount.toString(),
            ),
        )
    }

    private fun configureAuthenticatedPlayback(accessToken: String) {
        traceMethod("configureAuthenticatedPlayback")
        httpDataSourceFactory.setUserAgent("ShelfDrive/${BuildConfig.VERSION_NAME}")
        httpDataSourceFactory.setDefaultRequestProperties(mapOf("Authorization" to "Bearer $accessToken"))
    }

    private fun startPeriodicProgressUpdates() {
        traceMethod("startPeriodicProgressUpdates")
        periodicProgressJob?.cancel()
        periodicProgressJob = serviceScope.launch {
            while (true) {
                delay(PROGRESS_UPDATE_INTERVAL_MS)
                emitProgress(PlaybackProgressReason.PERIODIC)
            }
        }
    }

    private fun emitProgress(reason: PlaybackProgressReason) {
        traceMethod("emitProgress", mapOf("reason" to reason.name))
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
        traceMethod("applyRewindOnPauseIfEnabled")
        if (!PlaybackPreferences.isRewindOnPauseEnabled(this)) {
            return
        }
        val targetPositionMs = PlaybackResumePolicy.positionAfterPause(logicalPlaybackPositionMs())
        seekToLogicalPosition(targetPositionMs)
    }

    private fun seekToLogicalPosition(positionMs: Long) {
        traceMethod("seekToLogicalPosition", mapOf("positionMs" to positionMs.toString()))
        val playback = activeBook
        if (playback == null) {
            player.seekTo(positionMs.coerceAtLeast(0L))
        } else {
            val startPosition = PlaybackQueueMath.locateStartPosition(playback.queue, positionMs)
            setPlaybackQueue(playback, startPosition)
            player.prepare()
        }
        saveActivePlaybackState()
    }

    private fun seekBy(deltaMs: Long) {
        traceMethod("seekBy", mapOf("deltaMs" to deltaMs.toString()))
        val durationMs = activeBook?.durationMs?.takeIf { it > 0L }
            ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val targetPositionMs = (logicalPlaybackPositionMs() + deltaMs)
            .coerceAtLeast(0L)
            .let { target -> durationMs?.let { max -> target.coerceAtMost(max) } ?: target }
        seekToLogicalPosition(targetPositionMs)
    }

    private fun logicalPlaybackPositionMs(): Long {
        traceMethod("logicalPlaybackPositionMs")
        val playback = activeBook ?: return player.currentPosition.coerceAtLeast(0L)
        val queueTrack = playback.queue.getOrNull(currentGlobalTrackIndex())
            ?: playback.queue.firstOrNull()
            ?: return player.currentPosition.coerceAtLeast(0L)
        return (queueTrack.startOffsetMs + player.currentPosition.coerceAtLeast(0L))
            .coerceAtMost(playback.durationMs ?: Long.MAX_VALUE)
    }

    private fun logicalBufferedPositionMs(): Long {
        traceMethod("logicalBufferedPositionMs")
        val playback = activeBook ?: return player.bufferedPosition.coerceAtLeast(0L)
        val queueTrack = playback.queue.getOrNull(currentGlobalTrackIndex())
            ?: playback.queue.firstOrNull()
            ?: return player.bufferedPosition.coerceAtLeast(0L)
        return (queueTrack.startOffsetMs + player.bufferedPosition.coerceAtLeast(0L))
            .coerceAtMost(playback.durationMs ?: Long.MAX_VALUE)
    }

    private fun saveActivePlaybackState(wasPlaying: Boolean = player.playWhenReady) {
        traceMethod("saveActivePlaybackState", mapOf("wasPlaying" to wasPlaying.toString()))
        val playback = activeBook ?: return
        playbackStateStorage.save(
            StoredPlaybackState(
                bookId = playback.bookId,
                title = playback.title,
                author = playback.author,
                artworkUri = playback.artworkUri,
                durationMs = playback.durationMs,
                positionMs = logicalPlaybackPositionMs(),
                trackIndex = currentGlobalTrackIndex(),
                queue = playback.queue.map { it.toStoredPlaybackTrack() },
                playbackSpeed = player.playbackParameters.speed,
                wasPlaying = wasPlaying,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun updateMediaButtonPreferences() {
        traceMethod("updateMediaButtonPreferences")
        if (::mediaLibrarySession.isInitialized) {
            mediaLibrarySession.setCustomLayout(
                sessionPolicy.customLayout(player.playbackParameters.speed),
            )
            mediaLibrarySession.setMediaButtonPreferences(
                sessionPolicy.mediaButtonPreferences(player.playbackParameters.speed),
            )
        }
    }

    private fun Throwable.restoreFailureCategory(): String {
        traceMethod("restoreFailureCategory", mapOf("exception" to this::class.java.simpleName))
        return when (this) {
            is ApiException -> if (statusCode == 401 || statusCode == 403) "auth" else "api_$statusCode"
            is IOException -> "network"
            else -> "unexpected"
        }
    }

    private fun PlaybackException.isUnauthorizedResponse(): Boolean {
        traceMethod("isUnauthorizedResponse", mapOf("errorCode" to errorCodeName))
        return generateSequence(cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .any { it.responseCode == 401 }
    }

    private fun PlaybackException.isTransientNetworkResponse(): Boolean {
        traceMethod("isTransientNetworkResponse", mapOf("errorCode" to errorCodeName))
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
            traceMethod("AudiobookProgressPlayer.getDuration")
            return activeBook?.durationMs?.takeIf { it > 0L } ?: super.getDuration()
        }

        override fun getCurrentPosition(): Long {
            traceMethod("AudiobookProgressPlayer.getCurrentPosition")
            return logicalPlaybackPositionMs()
        }

        override fun getBufferedPosition(): Long {
            traceMethod("AudiobookProgressPlayer.getBufferedPosition")
            return logicalBufferedPositionMs()
        }

        override fun getBufferedPercentage(): Int {
            traceMethod("AudiobookProgressPlayer.getBufferedPercentage")
            val durationMs = duration
            if (durationMs == C.TIME_UNSET || durationMs <= 0L) {
                return super.getBufferedPercentage()
            }
            return ((bufferedPosition.coerceAtLeast(0L) * 100L) / durationMs)
                .coerceIn(0L, 100L)
                .toInt()
        }

        override fun getContentDuration(): Long {
            traceMethod("AudiobookProgressPlayer.getContentDuration")
            return duration
        }

        override fun getContentPosition(): Long {
            traceMethod("AudiobookProgressPlayer.getContentPosition")
            return currentPosition
        }

        override fun getContentBufferedPosition(): Long {
            traceMethod("AudiobookProgressPlayer.getContentBufferedPosition")
            return bufferedPosition
        }

        override fun seekTo(positionMs: Long) {
            traceMethod("AudiobookProgressPlayer.seekTo", mapOf("positionMs" to positionMs.toString()))
            seekToLogicalPosition(positionMs)
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            traceMethod(
                "AudiobookProgressPlayer.seekToMediaItem",
                mapOf(
                    "mediaItemIndex" to mediaItemIndex.toString(),
                    "positionMs" to positionMs.toString(),
                ),
            )
            val playback = activeBook
            val track = playback?.queue?.getOrNull(mediaItemIndex)
            if (playback == null || track == null) {
                super.seekTo(mediaItemIndex, positionMs)
                return
            }
            val logicalPositionMs = track.startOffsetMs + positionMs.coerceAtLeast(0L)
            seekToLogicalPosition(logicalPositionMs)
        }

        override fun seekBack() {
            traceMethod("AudiobookProgressPlayer.seekBack")
            seekBy(-SEEK_INCREMENT_MS)
        }

        override fun seekForward() {
            traceMethod("AudiobookProgressPlayer.seekForward")
            seekBy(SEEK_INCREMENT_MS)
        }

        override fun play() {
            traceMethod("AudiobookProgressPlayer.play")
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
        traceMethod("updateAuthSnapshot", mapOf("status" to snapshot.status.name))
        mediaCatalog.authSnapshot = snapshot
        mediaCatalog.hasStoredLoginCredentials = hasStoredLoginCredentials()
        notifyBrowseTreeChanged()
    }

    private fun updateSyncSnapshot(snapshot: SyncSnapshot) {
        traceMethod("updateSyncSnapshot", mapOf("status" to snapshot.status.name))
        mediaCatalog.syncSnapshot = snapshot
        notifyBrowseTreeChanged()
    }

    private fun notifyBrowseTreeChanged() {
        traceMethod("notifyBrowseTreeChanged")
        if (!this::mediaLibrarySession.isInitialized) {
            return
        }
        notifyBrowseTreeChangedGlobally()
    }

    private fun notifyBrowseTreeChangedGlobally() {
        traceMethod("notifyBrowseTreeChangedGlobally")
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Root.serialize(), ROOT_CHILD_COUNT, mediaCatalog.rootParams(null))
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Recent.serialize(), Int.MAX_VALUE, null)
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Books.serialize(), Int.MAX_VALUE, null)
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Authors.serialize(), Int.MAX_VALUE, null)
    }

    private fun hasStoredLoginCredentials(): Boolean {
        val stored = authStorage.load()
        val baseUrl = stored.baseUrl?.trim().orEmpty()
        val username = stored.username?.trim().orEmpty()
        val password = stored.password.orEmpty()
        return baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    }

    private fun String.normalizedBrowseParentId(): String {
        traceMethod("normalizedBrowseParentId", mapOf("parentId" to this))
        return trim().takeIf { it.isNotBlank() && BrowseNodeId.parse(it) != null }
            ?: BrowseNodeId.Root.serialize()
    }

    private suspend fun enrichRequestedMediaItems(
        mediaItems: List<MediaItem>,
        controller: MediaSession.ControllerInfo,
    ): List<MediaItem> {
        traceMethod(
            "enrichRequestedMediaItems",
            mapOf(
                "controllerPackage" to controller.packageName,
                "controllerUid" to controller.uid.toString(),
                "requested" to mediaItems.size.toString(),
            ),
        )
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
        traceMethod("serviceFuture", mapOf("label" to label))
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
        traceMethod("exceptionDiagnostics", mapOf("exception" to throwable::class.java.simpleName))
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

    private fun ResolvedAudiobookPlayback.logicalStartPositionMs(): Long {
        traceMethod("logicalStartPositionMs", mapOf("bookId" to bookId))
        val track = queue.getOrNull(startIndex)
        return ((track?.startOffsetMs ?: 0L) + startPositionMs).coerceAtLeast(0L)
    }

    private fun ResolvedAudiobookPlayback.startQueuePosition(): QueueStartPosition {
        return QueueStartPosition(
            trackIndex = startIndex,
            positionMs = startPositionMs,
        )
    }

    private fun ResolvedPlaybackTrack.toStoredPlaybackTrack(): StoredPlaybackTrack {
        traceMethod("ResolvedPlaybackTrack.toStoredPlaybackTrack", mapOf("trackId" to id))
        return StoredPlaybackTrack(
            id = id,
            title = title,
            contentUrl = contentUrl,
            mimeType = mimeType,
            durationMs = durationMs,
            startOffsetMs = startOffsetMs,
        )
    }

    companion object {
        private const val TAG = "ShelfDriveMedia3"
        private const val SEEK_INCREMENT_MS = 15_000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 15_000L
        private const val RESTORE_TIMEOUT_MS = 8_000L
        private const val AUTH_REQUIRED_SETTINGS_REQUEST_CODE = 1001
        private const val ROOT_CHILD_COUNT = 3
        private val TRANSIENT_PLAYBACK_RETRY_DELAYS_MS = listOf(1_000L, 3_000L, 8_000L, 15_000L)
        private val TRANSIENT_HTTP_STATUS_CODES = setOf(502, 503, 504)
    }
}

private class PlaybackResolutionException(
    override val message: String,
    override val cause: Throwable? = null,
) : IOException(message, cause)
