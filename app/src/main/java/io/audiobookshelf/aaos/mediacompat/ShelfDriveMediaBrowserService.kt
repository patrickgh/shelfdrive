package io.audiobookshelf.aaos.mediacompat

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.OptIn
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import io.audiobookshelf.aaos.BuildConfig
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
import io.audiobookshelf.aaos.playback.ResolvedPlaybackTrack
import io.audiobookshelf.aaos.playback.StoredPlaybackState
import io.audiobookshelf.aaos.playback.StoredPlaybackTrack
import io.audiobookshelf.aaos.progress.PlaybackProgressReason
import io.audiobookshelf.aaos.progress.PlaybackProgressSnapshot
import io.audiobookshelf.aaos.progress.ProgressSyncRepository
import io.audiobookshelf.aaos.sync.CatalogSyncRepository
import io.audiobookshelf.aaos.sync.SyncCommands
import io.audiobookshelf.aaos.sync.SyncSnapshot
import io.audiobookshelf.aaos.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

@OptIn(UnstableApi::class)
open class ShelfDriveMediaBrowserService : MediaBrowserServiceCompat(), Player.Listener {
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
    private lateinit var compatSession: MediaSessionCompat

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
            .apply { addListener(this@ShelfDriveMediaBrowserService) }
        player.setPlaybackParameters(
            PlaybackParameters(PlaybackPreferences.playbackSpeed(this), player.playbackParameters.pitch),
        )

        compatSession = MediaSessionCompat(this, TAG).apply {
            setCallback(SessionCallback())
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setSessionActivity(buildMediaTemplatePendingIntent())
            isActive = true
        }
        sessionToken = compatSession.sessionToken

        publishStoredPlaybackPlaceholder()
        updatePlaybackState()

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
                if (exception is CancellationException) throw exception
                diagnosticEventLogger.record("startup_bootstrap_failed", exceptionDiagnostics(exception))
                Log.w(TAG, "Startup bootstrap failed. Keeping MediaBrowserServiceCompat available.", exception)
                updateAuthSnapshot(AuthSnapshot(status = AuthStatus.LOGGED_OUT))
            }
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
        compatSession.release()
        player.removeListener(this)
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot {
        traceMethod(
            "onGetRoot",
            mapOf(
                "clientPackage" to clientPackageName,
                "clientUid" to clientUid.toString(),
            ),
        )
        diagnosticEventLogger.record(
            "browse_root_requested",
            mapOf(
                "clientPackage" to clientPackageName,
                "clientUid" to clientUid.toString(),
            ),
        )
        return BrowserRoot(BrowseNodeId.Root.serialize(), mediaCatalog.rootExtras())
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>,
    ) {
        onLoadChildren(parentId, result, Bundle.EMPTY)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>,
        options: Bundle,
    ) {
        traceMethod("onLoadChildren", mapOf("parentId" to parentId))
        val effectiveParentId = parentId.normalizedBrowseParentId()
        result.detach()
        serviceScope.launch {
            runCatching {
                if (!hasStoredLoginCredentials() && effectiveParentId != BrowseNodeId.Root.serialize()) {
                    emptyList()
                } else {
                    val children = mediaCatalog.loadChildren(effectiveParentId)
                    val node = BrowseNodeId.parse(effectiveParentId)
                    if (node == BrowseNodeId.Root) {
                        children
                    } else {
                        mediaCatalog.pageItems(
                            children,
                            options.getInt(MediaBrowserCompat.EXTRA_PAGE, -1),
                            options.getInt(MediaBrowserCompat.EXTRA_PAGE_SIZE, -1),
                        )
                    }
                }
            }.onSuccess { items ->
                result.sendResult(items)
            }.onFailure { exception ->
                if (exception is CancellationException) throw exception
                Log.w(TAG, "Compat browse load failed for parent=$parentId", exception)
                diagnosticEventLogger.record(
                    "mediacompat_browse_load_failed",
                    exceptionDiagnostics(exception, "parentId" to parentId),
                )
                result.sendResult(emptyList())
            }
        }
    }

    override fun onLoadItem(
        itemId: String,
        result: Result<MediaBrowserCompat.MediaItem>,
    ) {
        traceMethod("onLoadItem", mapOf("itemId" to itemId))
        result.detach()
        serviceScope.launch {
            runCatching {
                if (!hasStoredLoginCredentials() && itemId.requiresAuthentication()) {
                    null
                } else {
                    mediaCatalog.loadItem(itemId)
                }
            }.onSuccess { item ->
                result.sendResult(item)
            }.onFailure { exception ->
                if (exception is CancellationException) throw exception
                Log.w(TAG, "Compat item load failed for item=$itemId", exception)
                result.sendResult(null)
            }
        }
    }

    override fun onSearch(
        query: String,
        extras: Bundle?,
        result: Result<List<MediaBrowserCompat.MediaItem>>,
    ) {
        traceMethod("onSearch", mapOf("query" to query))
        result.detach()
        serviceScope.launch {
            runCatching {
                if (!hasStoredLoginCredentials()) {
                    emptyList()
                } else {
                    mediaCatalog.loadSearchResults(query)
                }
            }.onSuccess { items ->
                result.sendResult(items)
            }.onFailure { exception ->
                if (exception is CancellationException) throw exception
                Log.w(TAG, "Compat search failed for query=$query", exception)
                result.sendResult(emptyList())
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        traceMethod("onIsPlayingChanged", mapOf("isPlaying" to isPlaying.toString()))
        if (isPlaying) {
            lastProgressSampleElapsedRealtimeMs = SystemClock.elapsedRealtime()
            startPeriodicProgressUpdates()
        } else {
            periodicProgressJob?.cancel()
        }
        updatePlaybackState()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        traceMethod(
            "onPlayWhenReadyChanged",
            mapOf("playWhenReady" to playWhenReady.toString(), "reason" to reason.toString()),
        )
        if (wasPlayWhenReady && !playWhenReady && player.playbackState != Player.STATE_ENDED) {
            applyRewindOnPauseIfEnabled()
            emitProgress(PlaybackProgressReason.PAUSED)
        }
        wasPlayWhenReady = playWhenReady
        updatePlaybackState()
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
        updatePlaybackState()
        updateSessionMetadata()
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
        updatePlaybackState()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        traceMethod(
            "onMediaItemTransition",
            mapOf("mediaId" to mediaItem?.mediaId, "reason" to reason.toString()),
        )
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            emitProgress(PlaybackProgressReason.TRACK_CHANGED)
        }
        updateSessionMetadata()
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        traceMethod("onPlaybackParametersChanged", mapOf("speed" to playbackParameters.speed.toString()))
        PlaybackPreferences.savePlaybackSpeed(this, playbackParameters.speed)
        saveActivePlaybackState()
        updatePlaybackState()
    }

    override fun onPlayerError(error: PlaybackException) {
        traceMethod("onPlayerError", mapOf("errorCode" to error.errorCodeName, "message" to error.message))
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
        updatePlaybackState()
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPrepare() {
            traceMethod("SessionCallback.onPrepare")
            if (activeBook != null && player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            updateSessionMetadata()
            updatePlaybackState()
        }

        override fun onPrepareFromMediaId(mediaId: String, extras: Bundle?) {
            traceMethod("SessionCallback.onPrepareFromMediaId", mapOf("mediaId" to mediaId))
            serviceScope.launch {
                resolveAndLoadPlayback(mediaId = mediaId, searchQuery = null, playWhenReady = false)
            }
        }

        override fun onPrepareFromSearch(query: String, extras: Bundle?) {
            traceMethod("SessionCallback.onPrepareFromSearch", mapOf("query" to query))
            serviceScope.launch {
                resolveAndLoadPlayback(mediaId = null, searchQuery = query, playWhenReady = false)
            }
        }

        override fun onPlay() {
            traceMethod("SessionCallback.onPlay")
            if (activeBook == null && placeholderPlaybackState != null) {
                restorePlaceholderAndPlay()
                return
            }
            if (activeBook != null && player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
            updatePlaybackState()
        }

        override fun onPause() {
            traceMethod("SessionCallback.onPause")
            player.pause()
            updatePlaybackState()
        }

        override fun onStop() {
            traceMethod("SessionCallback.onStop")
            emitProgress(PlaybackProgressReason.STOPPED)
            player.stop()
            activePlaybackSessionId = null
            lastProgressSampleElapsedRealtimeMs = null
            updatePlaybackState()
        }

        override fun onSeekTo(pos: Long) {
            traceMethod("SessionCallback.onSeekTo", mapOf("positionMs" to pos.toString()))
            seekToLogicalPosition(pos)
            updatePlaybackState()
        }

        override fun onRewind() {
            traceMethod("SessionCallback.onRewind")
            seekBy(-SEEK_INCREMENT_MS)
            updatePlaybackState()
        }

        override fun onFastForward() {
            traceMethod("SessionCallback.onFastForward")
            seekBy(SEEK_INCREMENT_MS)
            updatePlaybackState()
        }

        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
            traceMethod("SessionCallback.onPlayFromMediaId", mapOf("mediaId" to mediaId))
            serviceScope.launch {
                resolveAndLoadPlayback(mediaId = mediaId, searchQuery = null, playWhenReady = true)
            }
        }

        override fun onPlayFromSearch(query: String, extras: Bundle?) {
            traceMethod("SessionCallback.onPlayFromSearch", mapOf("query" to query))
            serviceScope.launch {
                resolveAndLoadPlayback(mediaId = null, searchQuery = query, playWhenReady = true)
            }
        }

        override fun onCustomAction(action: String, extras: Bundle?) {
            traceMethod("SessionCallback.onCustomAction", mapOf("action" to action))
            when (action) {
                ShelfDriveCompatConstants.CMD_SEEK_BACK_15 -> seekBy(-SEEK_INCREMENT_MS)
                ShelfDriveCompatConstants.CMD_SEEK_FORWARD_15 -> seekBy(SEEK_INCREMENT_MS)
                ShelfDriveCompatConstants.CMD_CYCLE_PLAYBACK_SPEED -> {
                    player.setPlaybackSpeed(
                        ShelfDriveSessionPolicy.nextPlaybackSpeed(player.playbackParameters.speed),
                    )
                    saveActivePlaybackState()
                }
            }
            updatePlaybackState()
        }

        override fun onCommand(command: String, extras: Bundle?, cb: ResultReceiver?) {
            traceMethod("SessionCallback.onCommand", mapOf("command" to command))
            serviceScope.launch {
                val result = runCatching {
                    handleCompatCommand(command, extras ?: Bundle.EMPTY)
                }
                result.onSuccess { bundle ->
                    cb?.send(AuthCommands.RESULT_OK, bundle)
                }.onFailure { exception ->
                    if (exception is CancellationException) throw exception
                    diagnosticEventLogger.record(
                        "settings_command_failed",
                        exceptionDiagnostics(exception, "command" to command),
                    )
                    cb?.send(
                        AuthCommands.RESULT_ERROR,
                        Bundle().apply {
                            putString(EXTRA_COMMAND_ERROR, exception.message ?: exception::class.java.simpleName)
                        },
                    )
                }
            }
        }
    }

    private suspend fun resolveAndLoadPlayback(
        mediaId: String?,
        searchQuery: String?,
        playWhenReady: Boolean,
    ) {
        runCatching {
            if (mediaId != null) {
                val requestedItem = mediaCatalog.loadItem(mediaId)
                if (requestedItem != null) {
                    publishRequestedPlaybackPlaceholder(requestedItem)
                }
            }
            val playback = resolveRequestedPlayback(mediaId = mediaId, searchQuery = searchQuery)
            startResolvedPlayback(playback, playback.playback.startQueuePosition(), playWhenReady)
        }.onFailure { exception ->
            if (exception is CancellationException) throw exception
            Log.w(TAG, "Could not load playback for mediaId=$mediaId search=$searchQuery.", exception)
            diagnosticEventLogger.record(
                "playback_load_failed",
                exceptionDiagnostics(exception, "mediaId" to mediaId, "searchQuery" to searchQuery),
            )
            updatePlaybackState(errorMessage = exception.message ?: exception::class.java.simpleName)
        }
    }

    private suspend fun handleCompatCommand(command: String, args: Bundle): Bundle {
        return when (command) {
            AuthCommands.CMD_GET_AUTH_STATE -> {
                val snapshot = authRepository.bootstrap()
                updateAuthSnapshot(snapshot)
                snapshot.toBundle()
            }

            AuthCommands.CMD_LOGIN -> {
                val snapshot = authRepository.login(
                    requestedBaseUrl = args.getString(AuthCommands.EXTRA_SERVER_URL),
                    requestedUsername = args.getString(AuthCommands.EXTRA_USERNAME),
                    requestedPassword = args.getString(AuthCommands.EXTRA_PASSWORD),
                )
                updateAuthSnapshot(snapshot)
                snapshot.toBundle()
            }

            AuthCommands.CMD_LOGOUT -> {
                stopAndClearPlayback()
                val snapshot = authRepository.logout()
                cacheRepository.clearCache()
                updateAuthSnapshot(snapshot)
                updateSyncSnapshot(SyncSnapshot(status = SyncStatus.IDLE))
                snapshot.toBundle()
            }

            CacheCommands.CMD_GET_CACHE_STATE -> cacheRepository.loadSnapshot().toBundle()

            CacheCommands.CMD_CLEAR_CACHE -> {
                stopAndClearPlayback()
                val snapshot = cacheRepository.clearCache()
                updateSyncSnapshot(SyncSnapshot(status = SyncStatus.IDLE))
                snapshot.toBundle()
            }

            SyncCommands.CMD_GET_SYNC_STATE -> {
                val snapshot = syncRepository.loadSnapshot()
                updateSyncSnapshot(snapshot)
                snapshot.toBundle()
            }

            SyncCommands.CMD_SYNC_NOW -> {
                val snapshot = syncRepository.syncNow()
                if (snapshot.status != SyncStatus.FAILED) {
                    progressSyncRepository.refreshInProgress()
                }
                updateSyncSnapshot(snapshot)
                snapshot.toBundle()
            }

            else -> throw IllegalArgumentException("Unknown MediaCompat command: $command")
        }
    }

    private fun stopAndClearPlayback() {
        player.stop()
        player.clearMediaItems()
        activeBook = null
        activePlaybackSessionId = null
        lastProgressSampleElapsedRealtimeMs = null
        placeholderPlaybackState = null
        transientPlaybackRetryAttempt = 0
        playbackStateStorage.clear()
        updateSessionMetadata()
        updatePlaybackState()
    }

    private fun buildMediaTemplatePendingIntent(): PendingIntent {
        val mediaComponent = ComponentName(this, ShelfDriveMediaBrowserService::class.java).flattenToString()
        val intent = Intent(ACTION_MEDIA_TEMPLATE)
            .putExtra(EXTRA_MEDIA_COMPONENT, mediaComponent)
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_MEDIA_TEMPLATE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun startResolvedPlayback(
        resolved: io.audiobookshelf.aaos.playback.ResolvedAudiobookPlaybackSession,
        startPosition: QueueStartPosition,
        playWhenReady: Boolean,
    ) {
        activeBook = resolved.playback
        activePlaybackSessionId = resolved.sessionId
        placeholderPlaybackState = null
        configureAuthenticatedPlayback(resolved.accessToken)
        transientPlaybackRetryAttempt = 0
        setPlaybackQueue(resolved.playback, startPosition)
        player.prepare()
        player.playWhenReady = playWhenReady
        saveActivePlaybackState(wasPlaying = playWhenReady)
        updateSessionMetadata()
        updatePlaybackState()
    }

    private fun updateSessionMetadata() {
        if (!::compatSession.isInitialized) {
            return
        }
        val playback = activeBook
        val metadata = if (playback != null) {
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, sessionPlaybackMediaId(playback.bookId))
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, playback.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, playback.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, playback.author)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, playback.author)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, playback.durationMs ?: 0L)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, playback.artworkUri?.toString())
                .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, playback.artworkUri?.toString())
                .build()
        } else {
            val placeholder = placeholderPlaybackState
            if (placeholder == null) {
                MediaMetadataCompat.Builder().build()
            } else {
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, sessionPlaybackMediaId(placeholder.bookId))
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, placeholder.title ?: "Hoerbuch")
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, placeholder.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, placeholder.author)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, placeholder.author)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, placeholder.durationMs ?: 0L)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, placeholder.artworkUri?.toString())
                    .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, placeholder.artworkUri?.toString())
                    .build()
            }
        }
        compatSession.setMetadata(metadata)
    }

    private fun sessionPlaybackMediaId(bookId: String): String {
        return "playback:${BrowseNodeId.Book(bookId).serialize()}"
    }

    private fun updatePlaybackState(errorMessage: String? = null) {
        if (!::compatSession.isInitialized) {
            return
        }
        val compatState = when {
            errorMessage != null -> PlaybackStateCompat.STATE_ERROR
            player.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            player.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            player.playbackState == Player.STATE_IDLE && activeBook == null && placeholderPlaybackState == null ->
                PlaybackStateCompat.STATE_NONE
            player.playbackState == Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val builder = PlaybackStateCompat.Builder()
            .setActions(sessionPolicy.playbackActions())
            .setState(
                compatState,
                logicalPlaybackPositionMs(),
                player.playbackParameters.speed,
                SystemClock.elapsedRealtime(),
            )
            .setBufferedPosition(logicalBufferedPositionMs())
        sessionPolicy.customActions(player.playbackParameters.speed).forEach(builder::addCustomAction)
        if (errorMessage != null) {
            builder.setErrorMessage(errorMessage)
        }
        compatSession.setPlaybackState(builder.build())
    }

    private suspend fun resolveRequestedPlayback(
        mediaId: String?,
        searchQuery: String?,
    ): io.audiobookshelf.aaos.playback.ResolvedAudiobookPlaybackSession {
        traceMethod("resolveRequestedPlayback", mapOf("mediaId" to mediaId, "searchQuery" to searchQuery))
        val requestedBookId = mediaId?.let { BrowseNodeId.parse(it) as? BrowseNodeId.Book }?.bookId
        if (requestedBookId != null) {
            return playbackRepository.resolveBook(requestedBookId)
        }

        val query = searchQuery.orEmpty()
        val book = browseRepository.findBestPlayableBookForVoice(listOf(query))
            ?: throw PlaybackResolutionException(
                if (query.isBlank()) {
                    "Kein zuletzt gehoertes Hoerbuch gefunden."
                } else {
                    "Kein Hoerbuch fuer '$query' gefunden."
                },
            )
        return playbackRepository.resolveBook(book.id)
    }

    private fun publishRequestedPlaybackPlaceholder(requestedItem: MediaBrowserCompat.MediaItem) {
        traceMethod("publishRequestedPlaybackPlaceholder", mapOf("mediaId" to requestedItem.mediaId))
        val requestedBookId = (BrowseNodeId.parse(requestedItem.mediaId.orEmpty()) as? BrowseNodeId.Book)?.bookId
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
        updateSessionMetadata()
        updatePlaybackState()
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
        val stored = playbackStateStorage.load() ?: return
        if (!PlaybackSnapshotPolicy.isRestorable(stored, System.currentTimeMillis())) {
            playbackStateStorage.clear()
            return
        }
        placeholderPlaybackState = stored
        player.setMediaItem(PlaybackSnapshotPolicy.placeholderMediaItem(stored), stored.positionMs)
        player.setPlaybackParameters(PlaybackParameters(stored.playbackSpeed, player.playbackParameters.pitch))
        player.playWhenReady = false
        diagnosticsStorage.recordRestoreStarted(stored.bookId)
        updateSessionMetadata()
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
            val startPosition = PlaybackQueueMath.locateStartPosition(resolved.playback.queue, stored.positionMs)
            activeBook = resolved.playback
            activePlaybackSessionId = resolved.sessionId
            placeholderPlaybackState = null
            configureAuthenticatedPlayback(resolved.accessToken)
            transientPlaybackRetryAttempt = 0
            setPlaybackQueue(resolved.playback, startPosition)
            player.setPlaybackParameters(PlaybackParameters(stored.playbackSpeed, player.playbackParameters.pitch))
            player.playWhenReady = false
            saveActivePlaybackState(wasPlaying = stored.wasPlaying)
            updateSessionMetadata()
            updatePlaybackState()
            diagnosticsStorage.recordRestoreFinished(PlaybackRestoreStatus.SUCCESS)
            diagnosticEventLogger.record("restore_online_success")
            Log.i(TAG, "Restored playback state for book=${stored.bookId} at ${stored.positionMs}ms.")
        }.onFailure { exception ->
            if (exception is CancellationException) throw exception
            diagnosticsStorage.recordRestoreFinished(
                PlaybackRestoreStatus.FAILED,
                exception.message ?: exception::class.java.simpleName,
            )
            diagnosticEventLogger.record("restore_failed", exceptionDiagnostics(exception))
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
                val startPosition = PlaybackQueueMath.locateStartPosition(resolved.playback.queue, stored.positionMs)
                activeBook = resolved.playback
                activePlaybackSessionId = resolved.sessionId
                placeholderPlaybackState = null
                configureAuthenticatedPlayback(resolved.accessToken)
                transientPlaybackRetryAttempt = 0
                setPlaybackQueue(resolved.playback, startPosition)
                player.setPlaybackParameters(PlaybackParameters(stored.playbackSpeed, player.playbackParameters.pitch))
                player.prepare()
                player.playWhenReady = playWhenResolved
                saveActivePlaybackState(wasPlaying = playWhenResolved)
                updateSessionMetadata()
                updatePlaybackState()
            }.onSuccess {
                diagnosticEventLogger.record("restore_placeholder_play_success", mapOf("source" to source))
                Log.i(TAG, "Resolved local playback placeholder on $source for book=${stored.bookId}.")
            }.onFailure { exception ->
                if (exception is CancellationException) throw exception
                diagnosticEventLogger.record(
                    "restore_placeholder_play_failed",
                    exceptionDiagnostics(exception, "source" to source),
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
                val startPosition = PlaybackQueueMath.locateStartPosition(playback.playback.queue, resumePositionMs)
                activeBook = playback.playback
                activePlaybackSessionId = playback.sessionId
                configureAuthenticatedPlayback(playback.accessToken)
                transientPlaybackRetryAttempt = 0
                setPlaybackQueue(playback.playback, startPosition)
                player.prepare()
                player.playWhenReady = resumePlayWhenReady
                saveActivePlaybackState(wasPlaying = resumePlayWhenReady)
                updateSessionMetadata()
                updatePlaybackState()
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
                updatePlaybackState()
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
        val items = ShelfDrivePlaybackItems.build(playback)
        player.setMediaItems(items, startPosition.trackIndex, startPosition.positionMs)
        recordPlaybackQueue(playback, items.size, "set_player")
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
        notifyChildrenChanged(BrowseNodeId.Root.serialize())
        notifyChildrenChanged(BrowseNodeId.Recent.serialize())
        notifyChildrenChanged(BrowseNodeId.Books.serialize())
        notifyChildrenChanged(BrowseNodeId.Authors.serialize())
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

    private fun traceMethod(method: String, details: Map<String, String?> = emptyMap()) {
        if (::diagnosticEventLogger.isInitialized) {
            diagnosticEventLogger.record(
                "mediacompat_service_method_entered",
                buildMap {
                    put("method", method)
                    putAll(details)
                },
            )
        } else {
            Log.d(TAG, "MediaBrowserServiceCompat method entered before diagnostics init: $method")
        }
    }

    private fun Throwable.restoreFailureCategory(): String {
        return when (this) {
            is io.audiobookshelf.aaos.absapi.ApiException ->
                if (statusCode == 401 || statusCode == 403) "auth" else "api_$statusCode"
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

    private fun ResolvedPlaybackTrack.toStoredPlaybackTrack(): StoredPlaybackTrack {
        return StoredPlaybackTrack(
            id = id,
            title = title,
            contentUrl = contentUrl,
            mimeType = mimeType,
            durationMs = durationMs,
            startOffsetMs = startOffsetMs,
        )
    }

    private val MediaBrowserCompat.MediaItem.mediaId: String?
        get() = description.mediaId

    companion object {
        private const val TAG = "ShelfDriveCompat"
        private const val SEEK_INCREMENT_MS = 15_000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 15_000L
        private const val RESTORE_TIMEOUT_MS = 8_000L
        private const val EXTRA_COMMAND_ERROR = "io.shelfdrive.app.extra.COMMAND_ERROR"
        private const val REQUEST_CODE_MEDIA_TEMPLATE = 1001
        private const val ACTION_MEDIA_TEMPLATE = "android.car.intent.action.MEDIA_TEMPLATE"
        private const val EXTRA_MEDIA_COMPONENT = "android.car.intent.extra.MEDIA_COMPONENT"
        private val TRANSIENT_PLAYBACK_RETRY_DELAYS_MS = listOf(1_000L, 3_000L, 8_000L, 15_000L)
        private val TRANSIENT_HTTP_STATUS_CODES = setOf(502, 503, 504)
    }
}

private class PlaybackResolutionException(
    override val message: String,
    override val cause: Throwable? = null,
) : IOException(message, cause)
