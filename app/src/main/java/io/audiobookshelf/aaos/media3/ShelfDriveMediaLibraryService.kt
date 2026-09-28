package io.audiobookshelf.aaos.media3

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.preference.PreferenceManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
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
import io.audiobookshelf.aaos.playback.PlaybackStateStorage
import io.audiobookshelf.aaos.playback.QueueStartPosition
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlayback
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlaybackSession
import io.audiobookshelf.aaos.playback.StoredPlaybackState
import io.audiobookshelf.aaos.playback.isShelfDrivePlaybackUri
import io.audiobookshelf.aaos.playback.parsePlaybackTrackUri
import io.audiobookshelf.aaos.playback.playbackSessionTrackUrl
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
import java.io.IOException
import java.io.InterruptedIOException
import java.util.IdentityHashMap
import java.util.UUID

@OptIn(UnstableApi::class)
class ShelfDriveMediaLibraryService : MediaLibraryService(), Player.Listener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val serviceInstanceId by lazy { UUID.randomUUID().toString() }
    private val processId by lazy { Process.myPid() }
    private val serviceCreateStartedElapsedRealtimeMs by lazy { SystemClock.elapsedRealtime() }

    private lateinit var authStorage: AuthStorage
    private lateinit var authRepository: AuthRepository
    private lateinit var syncRepository: CatalogSyncRepository
    private lateinit var browseRepository: CatalogBrowseRepository
    private lateinit var playbackRepository: AudiobookshelfPlaybackRepository
    private lateinit var playbackStateStorage: PlaybackStateStorage
    private lateinit var progressSyncRepository: ProgressSyncRepository
    private lateinit var cacheRepository: CacheRepository
    private lateinit var connectivityMonitor: ConnectivityMonitor
    private var diagnosticsStorage: StartupDiagnosticsStorage? = null
    private var diagnosticEventLogger: DiagnosticEventLogger? = null
    private lateinit var mediaCatalog: ShelfDriveMediaCatalog
    private lateinit var sessionPolicy: ShelfDriveSessionPolicy
    private lateinit var player: ExoPlayer
    private lateinit var sessionPlayer: Player
    private lateinit var playbackUpstreamFactory: DataSource.Factory
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
    @Volatile
    private var activePlaybackSessionId: String? = null
    @Volatile
    private var activePlaybackBaseUrl: String? = null
    private var lastProgressSampleElapsedRealtimeMs: Long? = null
    private var periodicProgressJob: Job? = null
    private var playbackRecoveryJob: Job? = null
    private var forwardCacheJob: Job? = null
    private var activeBookCacheJob: Job? = null
    private var catalogSyncJob: Job? = null
    private var playbackSessionRecoveryAttempts = 0
    private var transientRetryState: TransientRetryState = TransientRetryState.NONE
    private var lastTrackTransitionAtMs: Long? = null
    private var wasPlayWhenReady: Boolean = false
    private var lastSyncSnapshot: SyncSnapshot = SyncSnapshot(status = SyncStatus.IDLE)
    private val subscribedSeriesParents = mutableSetOf<String>()
    private val progressUpdateMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            diagnosticsStorage = StartupDiagnosticsStorage(this)
            diagnosticEventLogger = DiagnosticEventLogger(this)
            diagnosticsStorage?.recordServiceStarted()
            diagnosticEventLogger?.record(
                "service_started",
                serviceStartDiagnostics(),
            )
            setListener(
                object : MediaSessionService.Listener {
                    @RequiresApi(Build.VERSION_CODES.S)
                    override fun onForegroundServiceStartNotAllowedException() {
                        diagnosticEventLogger?.record(
                            "foreground_start_denied",
                            serviceLifecycleDiagnostics(),
                        )
                    }
                },
            )
        }

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
        observeConnectivity()

        val skipIncrementMs = PlaybackPreferences.skipIncrementMs(this)
        playbackUpstreamFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this, httpDataSourceFactory),
        ) { dataSpec -> resolvePlaybackDataSpec(dataSpec) }
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
                        playbackUpstreamFactory,
                    ),
                ),
            )
            .setSeekBackIncrementMs(skipIncrementMs)
            .setSeekForwardIncrementMs(skipIncrementMs)
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
            .setMediaButtonPreferences(sessionPolicy.mediaButtonPreferences(player.playbackParameters.speed))
            .build()
        defaultSharedPreferences.registerOnSharedPreferenceChangeListener(playbackPreferenceChangeListener)
        diagnosticEventLogger?.record(
            "service_ready",
            serviceLifecycleDiagnostics() + storedPlaybackDiagnostics() + mapOf(
                "createDurationMs" to
                    (SystemClock.elapsedRealtime() - serviceCreateStartedElapsedRealtimeMs).toString(),
            ),
        )

        serviceScope.launch {
            runCatching {
                val initialAuth = authRepository.bootstrap()
                notifyCatalogChanged()
                updateSyncSnapshot(syncRepository.loadSnapshot())
                diagnosticEventLogger?.record("auth_bootstrap", mapOf("status" to initialAuth.status.name))
                if (initialAuth.isAuthenticated) {
                    val snapshot = syncRepository.syncIfStale()
                    updateSyncSnapshot(snapshot)
                    diagnosticEventLogger?.record(
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
                diagnosticEventLogger?.record(
                    "startup_bootstrap_failed",
                    exceptionDiagnostics(exception),
                )
                Log.w(TAG, "Startup bootstrap failed. Keeping MediaLibrarySession available.", exception)
                notifyCatalogChanged()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        diagnosticEventLogger?.record(
            "session_requested",
            controllerDiagnostics(mediaLibrarySession, controllerInfo),
        )
        return mediaLibrarySession
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (diagnosticEventLogger != null) {
            diagnosticEventLogger?.record(
                "service_bound",
                serviceLifecycleDiagnostics(intent),
            )
        }
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (diagnosticEventLogger != null) {
            diagnosticEventLogger?.record(
                "service_start_command",
                serviceLifecycleDiagnostics(intent) + mapOf(
                    "flags" to flags.toString(),
                    "startId" to startId.toString(),
                    "result" to result.toString(),
                ),
            )
        }
        return result
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (diagnosticEventLogger != null) {
            diagnosticEventLogger?.record(
                "service_task_removed",
                serviceLifecycleDiagnostics(rootIntent),
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (diagnosticEventLogger != null) {
            diagnosticEventLogger?.record(
                "service_unbound",
                serviceLifecycleDiagnostics(intent),
            )
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (diagnosticEventLogger != null) {
            diagnosticEventLogger?.recordBlocking(
                "service_destroyed",
                serviceLifecycleDiagnostics(),
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

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (diagnosticEventLogger != null) {
            diagnosticEventLogger?.record(
                "notification_update_requested",
                serviceLifecycleDiagnostics() + mapOf(
                    "startInForegroundRequired" to startInForegroundRequired.toString(),
                ),
            )
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onTrimMemory(level: Int) {
        if (diagnosticEventLogger != null) {
            diagnosticEventLogger?.record(
                "memory_trimmed",
                serviceLifecycleDiagnostics() + mapOf("level" to level.toString()),
            )
        }
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        if (diagnosticEventLogger != null) {
            diagnosticEventLogger?.record(
                "low_memory",
                serviceLifecycleDiagnostics(),
            )
        }
        super.onLowMemory()
    }

    private fun serviceStartDiagnostics(): Map<String, String?> = buildMap {
        put("serviceInstanceId", serviceInstanceId)
        put("processId", processId.toString())
        put("processStartElapsedRealtimeMs", Process.getStartElapsedRealtime().toString())
        put("serviceCreateStartedElapsedRealtimeMs", serviceCreateStartedElapsedRealtimeMs.toString())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val previousExits = runCatching {
                getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(packageName, 0, MAX_RECORDED_PROCESS_EXITS)
            }
            put("previousExitLookupException", previousExits.exceptionOrNull()?.javaClass?.simpleName)
            put("previousExitCount", previousExits.getOrNull()?.size?.toString())
            val now = System.currentTimeMillis()
            previousExits.getOrNull()?.forEachIndexed { index, exit ->
                val prefix = "previousExit$index"
                put("${prefix}Timestamp", exit.timestamp.toString())
                put("${prefix}AgeMs", (now - exit.timestamp).coerceAtLeast(0L).toString())
                put("${prefix}ProcessId", exit.pid.toString())
                put("${prefix}ProcessName", exit.processName)
                put("${prefix}Reason", exit.reason.toString())
                put("${prefix}ReasonName", applicationExitReasonDiagnosticName(exit.reason))
                put("${prefix}Status", exit.status.toString())
                put("${prefix}Importance", exit.importance.toString())
                put("${prefix}PssKb", exit.pss.toString())
                put("${prefix}RssKb", exit.rss.toString())
                put("${prefix}Description", exit.description)
            }
        }
    }

    private fun serviceLifecycleDiagnostics(intent: Intent? = null): Map<String, String?> = buildMap {
        put("serviceInstanceId", serviceInstanceId)
        put("processId", processId.toString())
        put("processStartElapsedRealtimeMs", Process.getStartElapsedRealtime().toString())
        put("intentAction", intent?.action)
        put("intentHasData", intent?.data?.let { true.toString() })
        put("intentDataScheme", intent?.data?.scheme)
        put(
            "isPlaybackOngoing",
            if (::mediaLibrarySession.isInitialized) isPlaybackOngoing().toString() else null,
        )
        put("hasActiveBook", (activeBook != null).toString())
        if (::player.isInitialized) {
            put("mediaItemCount", player.mediaItemCount.toString())
            put("currentMediaItemIndex", player.currentMediaItemIndex.toString())
            put("currentMediaId", player.currentMediaItem?.mediaId)
            put("playbackState", player.playbackState.toString())
            put("playWhenReady", player.playWhenReady.toString())
        }
    }

    private fun storedPlaybackDiagnostics(): Map<String, String?> {
        val storedPlayback = runCatching { playbackStateStorage.load() }
        val playback = storedPlayback.getOrNull()
        return mapOf(
            "storedPlaybackLoadException" to storedPlayback.exceptionOrNull()?.javaClass?.simpleName,
            "hasStoredPlayback" to (playback != null).toString(),
            "storedQueueSize" to playback?.queue?.size?.toString(),
            "storedPositionMs" to playback?.positionMs?.toString(),
            "storedDurationMs" to playback?.durationMs?.toString(),
            "storedHasTitle" to (playback?.title != null).toString(),
            "storedHasAuthor" to (playback?.author != null).toString(),
            "storedHasArtwork" to (playback?.artworkUri != null).toString(),
        )
    }

    private fun controllerDiagnostics(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): Map<String, String?> = buildMap {
        put("serviceInstanceId", serviceInstanceId)
        put("controllerIdentity", System.identityHashCode(controller).toString())
        put("controllerPackage", controller.packageName)
        put("controllerUid", controller.uid.toString())
        put("controllerVersion", controller.controllerVersion.toString())
        put("controllerInterfaceVersion", controller.interfaceVersion.toString())
        put("controllerLegacy", (controller.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION).toString())
        put("controllerTrusted", controller.isTrusted.toString())
        put("controllerPackageVerified", controller.isPackageNameVerified.toString())
        put("controllerAutomotive", session.isAutomotiveController(controller).toString())
        put("controllerAutoCompanion", session.isAutoCompanionController(controller).toString())
        put("controllerMediaNotification", session.isMediaNotificationController(controller).toString())
        put("controllerMaxItemCommands", controller.maxCommandsForMediaItems.toString())
        put("controllerHintKeys", controller.connectionHints.keySet().sorted().joinToString(","))
    }

    private fun libraryParamsDiagnostics(prefix: String, params: LibraryParams?): Map<String, String?> {
        val extras = params?.extras
        return mapOf(
            "${prefix}Recent" to (params?.isRecent == true).toString(),
            "${prefix}Offline" to (params?.isOffline == true).toString(),
            "${prefix}Suggested" to (params?.isSuggested == true).toString(),
            "${prefix}ExtraKeys" to extras?.keySet()?.sorted()?.joinToString(","),
            "${prefix}RootChildrenLimit" to extras.intString(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT),
            "${prefix}RootChildrenSupportedFlags" to extras.intString(ROOT_HINT_SUPPORTED_FLAGS),
            "${prefix}MediaArtSizePixels" to extras.intString(MediaConstants.EXTRAS_KEY_MEDIA_ART_SIZE_PIXELS),
            "${prefix}CustomBrowserActionLimit" to extras.intString(ROOT_HINT_CUSTOM_BROWSER_ACTION_LIMIT),
        )
    }

    private fun Bundle?.intString(key: String): String? {
        return this?.takeIf { it.containsKey(key) }?.getInt(key)?.toString()
    }

    private fun mediaItemDiagnostics(prefix: String, item: MediaItem): Map<String, String?> {
        val metadata = item.mediaMetadata
        return mapOf(
            "${prefix}MediaId" to item.mediaId,
            "${prefix}Browsable" to metadata.isBrowsable?.toString(),
            "${prefix}Playable" to metadata.isPlayable?.toString(),
            "${prefix}MediaType" to metadata.mediaType?.toString(),
            "${prefix}HasTitle" to (metadata.title != null).toString(),
            "${prefix}HasArtwork" to (metadata.artworkUri != null || metadata.artworkData != null).toString(),
            "${prefix}ArtworkScheme" to metadata.artworkUri?.scheme,
            "${prefix}ExtraKeys" to metadata.extras?.keySet()?.sorted()?.joinToString(","),
        )
    }

    private fun mediaItemSummary(item: MediaItem): String {
        val metadata = item.mediaMetadata
        return listOf(
            item.mediaId,
            "b=${metadata.isBrowsable}",
            "p=${metadata.isPlayable}",
            "type=${metadata.mediaType}",
            "title=${metadata.title != null}",
            "art=${metadata.artworkUri != null || metadata.artworkData != null}",
        ).joinToString(",")
    }

    private fun Intent.mediaButtonKeyEvent(): KeyEvent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        diagnosticEventLogger?.record(
            "player_is_playing_changed",
            playerStateDiagnostics("isPlayingEvent" to isPlaying.toString()),
        )
        if (isPlaying) {
            lastProgressSampleElapsedRealtimeMs = SystemClock.elapsedRealtime()
            startPeriodicProgressUpdates()
        } else {
            periodicProgressJob?.cancel()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        diagnosticEventLogger?.record(
            "player_play_when_ready_changed",
            playerStateDiagnostics(
                "playWhenReadyEvent" to playWhenReady.toString(),
                "reason" to reason.toString(),
            ),
        )
        if (wasPlayWhenReady && !playWhenReady && player.playbackState != Player.STATE_ENDED) {
            applyRewindOnPauseIfEnabled()
            emitProgress(PlaybackProgressReason.PAUSED)
        }
        wasPlayWhenReady = playWhenReady
    }

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        diagnosticEventLogger?.record(
            "player_suppression_changed",
            playerStateDiagnostics("suppressionReasonEvent" to playbackSuppressionReason.toString()),
        )
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        diagnosticEventLogger?.record(
            "player_timeline_changed",
            playerStateDiagnostics(
                "timelineWindowCount" to timeline.windowCount.toString(),
                "timelinePeriodCount" to timeline.periodCount.toString(),
                "reason" to reason.toString(),
            ),
        )
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        diagnosticEventLogger?.record(
            "player_playback_state_changed",
            playerStateDiagnostics("playbackStateEvent" to playbackState.toString()),
        )
        if (playbackState == Player.STATE_BUFFERING) {
            cancelForwardCache()
        }
        if (playbackState == Player.STATE_ENDED) {
            cancelForwardCache()
            emitProgress(PlaybackProgressReason.ENDED)
            periodicProgressJob?.cancel()
        }
        if (playbackState == Player.STATE_READY) {
            playbackSessionRecoveryAttempts = 0
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
        updateMediaButtonPreferences()
    }

    override fun onPlayerError(error: PlaybackException) {
        cancelForwardCache()
        val activeTrack = activeBook?.queue?.getOrNull(currentGlobalTrackIndex())
        Log.e(
            TAG,
            "Playback failed.",
            error,
        )
        diagnosticEventLogger?.record(
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
        } else if (error.isMissingPlaybackSessionResponse(activeTrack?.contentUrl)) {
            if (playbackSessionRecoveryAttempts < MAX_PLAYBACK_SESSION_RECOVERY_ATTEMPTS) {
                playbackSessionRecoveryAttempts++
                recoverPlaybackAfterMissingSession()
            } else {
                diagnosticEventLogger?.record(
                    "playback_recovery_exhausted",
                    mapOf("reason" to "session_not_found", "bookId" to activeBook?.bookId),
                )
            }
        } else if (error.isTransientNetworkResponse()) {
            recoverPlaybackAfterTransientNetworkError()
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            diagnosticEventLogger?.record(
                "controller_connected",
                controllerDiagnostics(session, controller) + mapOf("connectionPhase" to "requested"),
            )
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionPolicy.availableSessionCommands(controller))
                .setMediaButtonPreferences(sessionPolicy.mediaButtonPreferences(player.playbackParameters.speed))
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            diagnosticEventLogger?.record(
                "controller_post_connected",
                controllerDiagnostics(session, controller),
            )
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            diagnosticEventLogger?.record(
                "controller_disconnected",
                controllerDiagnostics(session, controller),
            )
        }

        override fun onPlayerInteractionFinished(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            playerCommands: Player.Commands,
        ) {
            val commandCodes = (0 until playerCommands.size()).map(playerCommands::get)
            diagnosticEventLogger?.record(
                "player_interaction_finished",
                controllerDiagnostics(session, controllerInfo) + playerStateDiagnostics(
                    "playerCommandCodes" to commandCodes.joinToString(","),
                    "playerCommands" to commandCodes.joinToString(",") { playerCommandDiagnosticName(it) },
                ),
            )
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val keyEvent = intent.mediaButtonKeyEvent()
            diagnosticEventLogger?.record(
                "media_button_received",
                controllerDiagnostics(session, controllerInfo) + mapOf(
                    "intentAction" to intent.action,
                    "keyCode" to keyEvent?.keyCode?.toString(),
                    "keyAction" to keyEvent?.action?.toString(),
                    "repeatCount" to keyEvent?.repeatCount?.toString(),
                ),
            )
            return super<MediaLibrarySession.Callback>.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val startedAt = SystemClock.elapsedRealtime()
            diagnosticEventLogger?.record(
                "browse_root_requested",
                controllerDiagnostics(session, browser) + libraryParamsDiagnostics("requested", params),
            )
            // Media3 handles System UI playback-resumption requests before invoking this
            // callback. Every actual library browser receives the stable catalog root.
            val rootItem = mediaCatalog.buildRootItem()
            val rootParams = mediaCatalog.rootParams(params)
            val result = LibraryResult.ofItem(rootItem, rootParams)
            diagnosticEventLogger?.record(
                "browse_root_returned",
                controllerDiagnostics(session, browser) +
                    libraryParamsDiagnostics("returned", rootParams) +
                    mediaItemDiagnostics("root", rootItem) +
                    mapOf(
                        "durationMs" to (SystemClock.elapsedRealtime() - startedAt).toString(),
                        "resultCode" to result.resultCode.toString(),
                    ),
            )
            return Futures.immediateFuture(result)
        }

        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            when (BrowseNodeId.parse(parentId)) {
                is BrowseNodeId.SeriesBucket, is BrowseNodeId.SeriesDetail -> subscribedSeriesParents.add(parentId)
                else -> Unit
            }
            val startedAt = SystemClock.elapsedRealtime()
            diagnosticEventLogger?.record(
                "browse_subscribe_requested",
                browseControllerDiagnostics(session, browser, parentId),
            )
            return super<MediaLibrarySession.Callback>.onSubscribe(session, browser, parentId, params).also { future ->
                recordBrowseOperationResult(
                    "browse_subscribe_finished",
                    session,
                    browser,
                    parentId,
                    startedAt,
                    future,
                )
            }
        }

        override fun onUnsubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
        ): ListenableFuture<LibraryResult<Void>> {
            val startedAt = SystemClock.elapsedRealtime()
            diagnosticEventLogger?.record(
                "browse_unsubscribe_requested",
                browseControllerDiagnostics(session, browser, parentId),
            )
            return super<MediaLibrarySession.Callback>.onUnsubscribe(session, browser, parentId).also { future ->
                recordBrowseOperationResult(
                    "browse_unsubscribe_finished",
                    session,
                    browser,
                    parentId,
                    startedAt,
                    future,
                )
            }
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
            val startedAt = SystemClock.elapsedRealtime()
            diagnosticEventLogger?.record(
                "browse_children_requested",
                browseControllerDiagnostics(session, browser, parentId) +
                    libraryParamsDiagnostics("requested", params) + mapOf(
                        "page" to page.toString(),
                        "pageSize" to pageSize.toString(),
                    ),
            )

            val node = BrowseNodeId.parse(parentId)
            val future: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = when {
                node == null -> Futures.immediateFuture(
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params),
                )

                !hasStoredLoginCredentials() && node != BrowseNodeId.Root -> Futures.immediateFuture(
                    authRequiredResult(browser, parentId, params),
                )

                isCatalogUnavailable(node) -> Futures.immediateFuture(
                    LibraryResult.ofError(SessionError.ERROR_IO, params),
                )

                else -> serviceFuture("getChildren:$parentId") {
                    val children = mediaCatalog.loadChildren(parentId)
                    val items = mediaCatalog.pageItems(children, page, pageSize)
                    LibraryResult.ofItemList(
                        items,
                        when (node) {
                            BrowseNodeId.Root -> mediaCatalog.rootParams(params)
                            else -> params
                        },
                    )
                }
            }
            recordBrowseChildrenResult(session, browser, parentId, page, pageSize, params, startedAt, future)
            return future
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
            if (isCatalogUnavailable()) {
                return Futures.immediateFuture(LibraryResult.ofError<Void>(SessionError.ERROR_IO, params))
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
            if (isCatalogUnavailable()) {
                return Futures.immediateFuture(
                    LibraryResult.ofError<ImmutableList<MediaItem>>(SessionError.ERROR_IO, params),
                )
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
            diagnosticEventLogger?.record(
                "add_media_items_rejected",
                mapOf(
                    "controllerPackage" to controller.packageName,
                    "requested" to mediaItems.size.toString(),
                ),
            )
            return Futures.immediateFailedFuture(
                UnsupportedOperationException("ShelfDrive does not support adding items to the active audiobook."),
            )
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return serviceFuture("setMediaItems") {
                val requestedIndex = if (startIndex == C.INDEX_UNSET) 0 else startIndex
                val requestedItem = mediaItems.getOrNull(requestedIndex)
                    ?: throw PlaybackResolutionException("Kein Medium ausgewaehlt.")
                if (BuildConfig.DIAGNOSTICS_ENABLED) {
                    Log.i(TAG, "Host requested playback for mediaId=${requestedItem.mediaId}.")
                }
                val playback = resolveRequestedPlayback(requestedItem)
                if (BuildConfig.DIAGNOSTICS_ENABLED) {
                    Log.i(TAG, "Resolved playback for book=${playback.playback.bookId} tracks=${playback.playback.queue.size}.")
                }
                updateActiveBook(playback.playback)
                activePlaybackSessionId = playback.sessionId
                activePlaybackBaseUrl = playback.baseUrl
                playbackSessionRecoveryAttempts = 0
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

                diagnosticEventLogger?.record(
                    "playback_resumption_requested",
                    controllerDiagnostics(mediaSession, controller) + mapOf(
                        "isForPlayback" to isForPlayback.toString(),
                    ),
                )

                if (!isForPlayback) {
                    return@serviceFuture metadataOnlyPlaybackResumption(stored, controller)
                }
                playbackResumption(stored, controller)
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
                action == ShelfDriveSessionPolicy.CMD_CYCLE_PLAYBACK_SPEED -> serviceFuture(action) {
                    player.setPlaybackSpeed(
                        ShelfDriveSessionPolicy.nextPlaybackSpeed(player.playbackParameters.speed),
                    )
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }

                action == AuthCommands.CMD_GET_AUTH_STATE -> serviceFuture(action) {
                    val snapshot = authRepository.bootstrap()
                    SessionResult(SessionResult.RESULT_SUCCESS, snapshot.toBundle())
                }

                action == AuthCommands.CMD_LOGIN -> serviceFuture(action) {
                    val snapshot = authRepository.login(
                        requestedBaseUrl = args.getString(AuthCommands.EXTRA_SERVER_URL),
                        requestedUsername = args.getString(AuthCommands.EXTRA_USERNAME),
                        requestedPassword = args.getString(AuthCommands.EXTRA_PASSWORD),
                    )
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
                    } else {
                        notifyCatalogChanged()
                    }
                    SessionResult(SessionResult.RESULT_SUCCESS, result)
                }

                action == AuthCommands.CMD_LOGOUT -> serviceFuture(action) {
                    clearPlayback()
                    val snapshot = authRepository.logout()
                    cacheRepository.clearCache()
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

        private fun browseControllerDiagnostics(
            session: MediaSession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
        ): Map<String, String?> = controllerDiagnostics(session, browser) + mapOf("parentId" to parentId)

        private fun recordBrowseOperationResult(
            event: String,
            session: MediaSession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            startedAt: Long,
            future: ListenableFuture<LibraryResult<Void>>,
        ) {
            val logger = diagnosticEventLogger ?: return
            future.addListener(
                {
                    val result = runCatching { Futures.getDone(future) }
                    logger.record(
                        event,
                        browseControllerDiagnostics(session, browser, parentId) + mapOf(
                            "durationMs" to (SystemClock.elapsedRealtime() - startedAt).toString(),
                            "cancelled" to future.isCancelled.toString(),
                            "resultCode" to result.getOrNull()?.resultCode?.toString(),
                            "exception" to result.exceptionOrNull()?.javaClass?.simpleName,
                        ),
                    )
                },
                MoreExecutors.directExecutor(),
            )
        }

        private fun recordBrowseChildrenResult(
            session: MediaSession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
            startedAt: Long,
            future: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>,
        ) {
            val logger = diagnosticEventLogger ?: return
            future.addListener(
                {
                    val result = runCatching { Futures.getDone(future) }
                    val libraryResult = result.getOrNull()
                    val items = libraryResult?.value
                    logger.record(
                        "browse_children_finished",
                        browseControllerDiagnostics(session, browser, parentId) +
                            libraryParamsDiagnostics("requested", params) +
                            mapOf(
                                "page" to page.toString(),
                                "pageSize" to pageSize.toString(),
                                "durationMs" to (SystemClock.elapsedRealtime() - startedAt).toString(),
                                "cancelled" to future.isCancelled.toString(),
                                "resultCode" to libraryResult?.resultCode?.toString(),
                                "returned" to items?.size?.toString(),
                                "exception" to result.exceptionOrNull()?.javaClass?.simpleName,
                                "rootChildren" to items
                                    ?.takeIf { parentId == BrowseNodeId.Root.serialize() }
                                    ?.joinToString(";") { mediaItemSummary(it) },
                            ),
                    )
                },
                MoreExecutors.directExecutor(),
            )
        }

        private fun String.requiresAuthentication(): Boolean {
            return when (BrowseNodeId.parse(this)) {
                BrowseNodeId.Root,
                BrowseNodeId.Recent,
                BrowseNodeId.Books,
                BrowseNodeId.Authors,
                BrowseNodeId.Series,
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
            diagnosticEventLogger?.record(
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
        activePlaybackBaseUrl = null
        playbackSessionRecoveryAttempts = 0
        lastProgressSampleElapsedRealtimeMs = null
        resetTransientPlaybackRetry()
        playbackStateStorage.clear()
    }

    private fun metadataOnlyPlaybackResumption(
        stored: StoredPlaybackState,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.MediaItemsWithStartPosition {
        diagnosticEventLogger?.record(
            "playback_resumption_metadata_returned",
            mapOf(
                "controllerPackage" to controller.packageName,
                "hasTitle" to (!stored.title.isNullOrBlank()).toString(),
            ),
        )
        return MediaSession.MediaItemsWithStartPosition(
            listOf(stored.toMedia3MetadataItem()),
            0,
            stored.positionMs.coerceAtLeast(0L),
        )
    }

    private suspend fun playbackResumption(
        stored: StoredPlaybackState,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.MediaItemsWithStartPosition {
        diagnosticsStorage?.recordRestoreStarted(stored.bookId)
        return try {
            val localPlayback = stored.toResolvedPlayback()
            val playback: ResolvedAudiobookPlayback
            val source: String
            val baseUrl: String
            val sessionId: String?
            val accessToken: String?
            val localSessionId = activePlaybackSessionId?.takeIf {
                activeBook?.bookId == stored.bookId
            }
            val localBaseUrl = activePlaybackBaseUrl?.takeIf { localSessionId != null }
            if (localPlayback != null && localSessionId != null && localBaseUrl != null) {
                playback = localPlayback
                source = "local_manifest"
                baseUrl = localBaseUrl
                sessionId = localSessionId
                accessToken = authStorage.load().accessToken?.takeIf { it.isNotBlank() }
            } else {
                val resolved = playbackRepository.resolveBook(stored.bookId)
                playback = resolved.playback
                source = if (localPlayback == null) "online_resolution" else "online_resolution_after_restart"
                baseUrl = resolved.baseUrl
                sessionId = resolved.sessionId
                accessToken = resolved.accessToken
            }

            accessToken?.let(::configureAuthenticatedPlayback)
            player.setPlaybackParameters(
                PlaybackParameters(stored.playbackSpeed, player.playbackParameters.pitch),
            )
            updateActiveBook(playback)
            activePlaybackSessionId = sessionId
            activePlaybackBaseUrl = baseUrl
            lastProgressSampleElapsedRealtimeMs = null
            resetTransientPlaybackRetry()
            val startPosition = PlaybackQueueMath.locateStartPosition(
                playback.queue,
                stored.positionMs,
            )
            diagnosticsStorage?.recordRestoreFinished(PlaybackRestoreStatus.SUCCESS)
            diagnosticEventLogger?.record(
                "playback_resumption_items_returned",
                mapOf(
                    "controllerPackage" to controller.packageName,
                    "source" to source,
                    "tracks" to playback.queue.size.toString(),
                    "trackIndex" to startPosition.trackIndex.toString(),
                    "positionMs" to startPosition.positionMs.toString(),
                ),
            )
            playbackItemsWithStartPosition(playback, startPosition)
        } catch (exception: Throwable) {
            if (exception is CancellationException) {
                throw exception
            }
            diagnosticsStorage?.recordRestoreFinished(
                PlaybackRestoreStatus.FAILED,
                exception.message ?: exception::class.java.simpleName,
            )
            diagnosticEventLogger?.record(
                "playback_resumption_failed",
                exceptionDiagnostics(exception, "controllerPackage" to controller.packageName),
            )
            throw exception
        }
    }

    private fun emptyPlaybackResumption(
        reason: String,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): MediaSession.MediaItemsWithStartPosition {
        if (isForPlayback) {
            diagnosticsStorage?.recordRestoreFinished(PlaybackRestoreStatus.SKIPPED, "No stored playback state.")
        }
        diagnosticEventLogger?.record(
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

        val searchQuery = requestedItem.requestMetadata.searchQuery?.trim().orEmpty()
        if (searchQuery.isBlank()) {
            throw PlaybackResolutionException("Unbekannte Medien-ID '${requestedItem.mediaId}'.")
        }
        val book = browseRepository.findBestPlayableBookForVoice(listOf(searchQuery))
            ?: throw PlaybackResolutionException(
                "Kein Hoerbuch fuer '$searchQuery' gefunden.",
            )
        return playbackRepository.resolveBook(book.id)
    }

    private fun recoverPlaybackAfterUnauthorized() {
        recoverPlaybackSession("unauthorized")
    }

    private fun recoverPlaybackAfterMissingSession() {
        recoverPlaybackSession("session_not_found")
    }

    private fun recoverPlaybackSession(source: String) {
        val currentBook = activeBook ?: return
        val bookId = currentBook.bookId
        startPlaybackRecovery(
            source = source,
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
                diagnosticEventLogger?.record(
                    "playback_recovery_success",
                    mapOf("source" to source, "bookId" to bookId),
                )
                if (BuildConfig.DIAGNOSTICS_ENABLED) {
                    Log.i(TAG, "Recovered playback from $source for book=$bookId.")
                }
            }.onFailure { exception ->
                if (exception is CancellationException) {
                    throw exception
                }
                diagnosticEventLogger?.record(
                    "playback_recovery_failed",
                    exceptionDiagnostics(
                        exception,
                        "source" to source,
                        "bookId" to bookId,
                    ),
                )
                Log.w(TAG, "Playback recovery from $source failed.", exception)
            }
        }
    }

    private suspend fun resolveAndActivatePlayback(
        bookId: String,
        positionMs: Long,
        speed: Float,
        playWhenReady: Boolean,
    ) {
        val resolved = playbackRepository.resolveBook(bookId)
        activateResolvedPlayback(
            resolved = resolved,
            positionMs = positionMs,
            speed = speed,
            playWhenReady = playWhenReady,
        )
    }

    private fun activateResolvedPlayback(
        resolved: ResolvedAudiobookPlaybackSession,
        positionMs: Long,
        speed: Float,
        playWhenReady: Boolean,
    ) {
        val startPosition = PlaybackQueueMath.locateStartPosition(resolved.playback.queue, positionMs)
        val hadPlayerError = player.playerError != null
        val needsQueue = player.mediaItemCount != resolved.playback.queue.size
        selectResolvedPlayback(resolved)
        if (needsQueue) {
            setPlaybackQueue(resolved.playback, startPosition)
        }
        player.setPlaybackParameters(PlaybackParameters(speed, player.playbackParameters.pitch))
        if (needsQueue || player.playbackState == Player.STATE_IDLE || hadPlayerError) {
            player.prepare()
            if (!needsQueue) {
                player.seekTo(startPosition.trackIndex, startPosition.positionMs)
            }
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
        activePlaybackBaseUrl = resolved.baseUrl
        configureAuthenticatedPlayback(resolved.accessToken)
        resetTransientPlaybackRetry()
    }

    private fun recoverPlaybackAfterTransientNetworkError() {
        val currentBook = activeBook ?: return
        saveActivePlaybackState()
        if (!connectivityMonitor.networkValidated.value) {
            transientRetryState = TransientRetryState.WAITING_FOR_NETWORK
            diagnosticEventLogger?.record(
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
                    if (BuildConfig.DIAGNOSTICS_ENABLED) {
                        Log.i(TAG, "Retrying playback once after transient stream error for book=${currentBook.bookId}.")
                    }
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
                diagnosticEventLogger?.record(
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
                Log.w(TAG, "Could not retain audio cache.", exception)
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

    private fun resolvePlaybackDataSpec(dataSpec: DataSpec): DataSpec {
        val track = parsePlaybackTrackUri(dataSpec.uri.toString()) ?: return dataSpec
        if (activeBook?.bookId != track.bookId) {
            return dataSpec
        }
        val sessionId = activePlaybackSessionId ?: return dataSpec
        val baseUrl = activePlaybackBaseUrl ?: return dataSpec
        return dataSpec.withUri(
            Uri.parse(playbackSessionTrackUrl(baseUrl, sessionId, track.trackIndex)),
        )
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
                            upstreamFactory = playbackUpstreamFactory,
                            uri = track.contentUrl,
                            cacheKey = cacheKey,
                            onProgress = { contentLengthBytes, cachedBytes ->
                                val now = SystemClock.elapsedRealtime()
                                val complete = contentLengthBytes != null && cachedBytes >= contentLengthBytes
                                if (
                                    lastProgressEventAtMs == 0L ||
                                    complete ||
                                    now - lastProgressEventAtMs >= CACHE_PROGRESS_EVENT_INTERVAL_MS
                                ) {
                                    lastProgressEventAtMs = now
                                    diagnosticEventLogger?.record(
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
                    diagnosticEventLogger?.record(
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
                diagnosticEventLogger?.record(
                    "cache_track_failed",
                    mapOf(
                        "bookId" to playback.bookId,
                        "trackIndex" to cacheTrackIndex?.toString(),
                        "message" to exception.message,
                    ),
                )
                Log.w(TAG, "Forward cache failed.", exception)
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
        } else if (reason != PlaybackProgressReason.SEEKED) {
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
                    diagnosticEventLogger?.record(
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
        if (
            result.sessionMissing &&
            activeBook?.bookId == snapshot.bookId &&
            activePlaybackSessionId == snapshot.playbackSessionId
        ) {
            activePlaybackSessionId = null
            if (
                snapshot.reason != PlaybackProgressReason.ENDED &&
                snapshot.reason != PlaybackProgressReason.STOPPED
            ) {
                recoverPlaybackAfterMissingSession()
            }
        }
        if (result.uploaded && activeBook?.bookId == snapshot.bookId) {
            activePlaybackSessionId = result.sessionId
        }
        if (result.uploaded && snapshot.reason.shouldRefreshBrowse) {
            notifyRecentChanged()
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
        diagnosticEventLogger?.record(
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

    private fun playerStateDiagnostics(vararg details: Pair<String, String?>): Map<String, String?> = buildMap {
        val currentItem = player.currentMediaItem
        val metadata = currentItem?.mediaMetadata
        put("serviceInstanceId", serviceInstanceId)
        put("hasActiveBook", (activeBook != null).toString())
        put("mediaItemCount", player.mediaItemCount.toString())
        put("currentMediaItemIndex", player.currentMediaItemIndex.toString())
        put("currentMediaId", currentItem?.mediaId)
        put("currentHasTitle", (metadata?.title != null).toString())
        put("currentHasArtwork", (metadata?.artworkUri != null || metadata?.artworkData != null).toString())
        put("positionMs", logicalPlaybackPositionMs().toString())
        put("durationMs", player.duration.takeIf { it != C.TIME_UNSET }?.toString())
        put("bufferedPositionMs", player.bufferedPosition.toString())
        put("isPlaying", player.isPlaying.toString())
        put("playWhenReady", player.playWhenReady.toString())
        put("playbackState", player.playbackState.toString())
        put("suppressionReason", player.playbackSuppressionReason.toString())
        putAll(details)
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
            mediaLibrarySession.setMediaButtonPreferences(
                sessionPolicy.mediaButtonPreferences(player.playbackParameters.speed),
            )
        }
    }

    private fun applySkipIncrement() {
        val incrementMs = PlaybackPreferences.skipIncrementMs(this)
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

    private fun PlaybackException.isMissingPlaybackSessionResponse(contentUrl: String?): Boolean {
        if (contentUrl == null || !isShelfDrivePlaybackUri(contentUrl)) {
            return false
        }
        return generateSequence(cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .any { it.responseCode == 404 }
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
        private val listeners = IdentityHashMap<Player.Listener, Player.Listener>()

        override fun getAvailableCommands(): Player.Commands {
            return audiobookCommands(super.getAvailableCommands())
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return availableCommands.contains(command)
        }

        private fun audiobookCommands(commands: Player.Commands): Player.Commands {
            return commands
                .buildUpon()
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                // Hardware next/previous controls use the same time jumps as the screen buttons.
                .addIf(Player.COMMAND_SEEK_TO_NEXT, commands.contains(Player.COMMAND_SEEK_FORWARD))
                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, commands.contains(Player.COMMAND_SEEK_BACK))
                .remove(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                .remove(Player.COMMAND_SET_SPEED_AND_PITCH)
                .build()
        }

        override fun addListener(listener: Player.Listener) {
            synchronized(listeners) {
                val forwardingListener = listeners.getOrPut(listener) {
                    // Java default listener methods need explicit forwarding in Kotlin.
                    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                    object : Player.Listener {
                        private var lastCommands = availableCommands
                        private var commandsChanged = false

                        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                            val commands = audiobookCommands(availableCommands)
                            if (commands != lastCommands) {
                                lastCommands = commands
                                commandsChanged = true
                                listener.onAvailableCommandsChanged(commands)
                            }
                        }

                        override fun onEvents(player: Player, events: Player.Events) {
                            val filteredEvents = if (
                                events.contains(Player.EVENT_AVAILABLE_COMMANDS_CHANGED) && !commandsChanged
                            ) {
                                Player.Events(
                                    FlagSet.Builder().apply {
                                        for (index in 0 until events.size()) {
                                            val event = events[index]
                                            if (event != Player.EVENT_AVAILABLE_COMMANDS_CHANGED) {
                                                add(event)
                                            }
                                        }
                                    }.build(),
                                )
                            } else {
                                events
                            }
                            commandsChanged = false
                            if (filteredEvents.size() > 0) {
                                listener.onEvents(player, filteredEvents)
                            }
                        }

                        override fun onTimelineChanged(timeline: Timeline, reason: Int) =
                            listener.onTimelineChanged(timeline, reason)

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
                            listener.onMediaItemTransition(mediaItem, reason)

                        override fun onTracksChanged(tracks: Tracks) = listener.onTracksChanged(tracks)

                        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) =
                            listener.onMediaMetadataChanged(mediaMetadata)

                        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) =
                            listener.onPlaylistMetadataChanged(mediaMetadata)

                        override fun onIsLoadingChanged(isLoading: Boolean) = listener.onIsLoadingChanged(isLoading)

                        override fun onLoadingChanged(isLoading: Boolean) = listener.onLoadingChanged(isLoading)

                        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) =
                            listener.onTrackSelectionParametersChanged(parameters)

                        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
                            listener.onPlayerStateChanged(playWhenReady, playbackState)

                        override fun onPlaybackStateChanged(playbackState: Int) =
                            listener.onPlaybackStateChanged(playbackState)

                        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
                            listener.onPlayWhenReadyChanged(playWhenReady, reason)

                        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
                            listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)

                        override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)

                        override fun onRepeatModeChanged(repeatMode: Int) = listener.onRepeatModeChanged(repeatMode)

                        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
                            listener.onShuffleModeEnabledChanged(shuffleModeEnabled)

                        override fun onPlayerError(error: PlaybackException) = listener.onPlayerError(error)

                        override fun onPlayerErrorChanged(error: PlaybackException?) = listener.onPlayerErrorChanged(error)

                        override fun onPositionDiscontinuity(reason: Int) = listener.onPositionDiscontinuity(reason)

                        override fun onPositionDiscontinuity(
                            oldPosition: Player.PositionInfo,
                            newPosition: Player.PositionInfo,
                            reason: Int,
                        ) = listener.onPositionDiscontinuity(oldPosition, newPosition, reason)

                        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
                            listener.onPlaybackParametersChanged(playbackParameters)

                        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) =
                            listener.onSeekBackIncrementChanged(seekBackIncrementMs)

                        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) =
                            listener.onSeekForwardIncrementChanged(seekForwardIncrementMs)

                        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
                            listener.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)

                        override fun onVideoSizeChanged(videoSize: VideoSize) = listener.onVideoSizeChanged(videoSize)

                        override fun onSurfaceSizeChanged(width: Int, height: Int) = listener.onSurfaceSizeChanged(width, height)

                        override fun onRenderedFirstFrame() = listener.onRenderedFirstFrame()

                        override fun onAudioSessionIdChanged(audioSessionId: Int) = listener.onAudioSessionIdChanged(audioSessionId)

                        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) =
                            listener.onAudioAttributesChanged(audioAttributes)

                        override fun onVolumeChanged(volume: Float) = listener.onVolumeChanged(volume)

                        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) =
                            listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)

                        override fun onCues(cues: List<Cue>) = listener.onCues(cues)

                        override fun onCues(cueGroup: CueGroup) = listener.onCues(cueGroup)

                        override fun onMetadata(metadata: Metadata) = listener.onMetadata(metadata)

                        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = listener.onDeviceInfoChanged(deviceInfo)

                        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = listener.onDeviceVolumeChanged(volume, muted)
                    }
                }
                super.addListener(forwardingListener)
            }
        }

        override fun removeListener(listener: Player.Listener) {
            synchronized(listeners) {
                super.removeListener(listeners.remove(listener) ?: listener)
            }
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
            seekBy(-PlaybackPreferences.skipIncrementMs(this@ShelfDriveMediaLibraryService))
        }

        override fun seekForward() {
            seekBy(PlaybackPreferences.skipIncrementMs(this@ShelfDriveMediaLibraryService))
        }

        override fun seekToPrevious() {
            seekBack()
        }

        override fun seekToNext() {
            seekForward()
        }

        override fun play() {
            playbackRecoveryJob?.cancel()
            playbackSessionRecoveryAttempts = 0
            resetTransientPlaybackRetry()
            if (activeBook != null && player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            super.play()
            if (activeBook != null) {
                emitProgress(PlaybackProgressReason.STARTED)
            }
        }
    }

    private fun observeConnectivity() {
        serviceScope.launch {
            var wasValidated = connectivityMonitor.networkValidated.value
            connectivityMonitor.networkValidated.collect { isValidated ->
                if (isValidated != wasValidated) {
                    diagnosticEventLogger?.record(
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
                    emitProgress(PlaybackProgressReason.PERIODIC)
                    progressSyncRepository.refreshInProgress()
                    retryActivePlaybackAfterNetworkReturn()
                    cacheForwardHorizon()
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
            val previous = lastSyncSnapshot
            runCatching {
                syncRepository.syncIfStale()
            }.onSuccess { snapshot ->
                if (snapshot != previous) {
                    updateSyncSnapshot(snapshot)
                    diagnosticEventLogger?.record(
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
                diagnosticEventLogger?.record(
                    "background_catalog_sync_failed",
                    exceptionDiagnostics(exception, "source" to source),
                )
                Log.w(TAG, "Background catalog sync failed from $source.", exception)
            }
        }
    }

    private fun retryActivePlaybackAfterNetworkReturn() {
        val currentBook = activeBook ?: return
        if (!player.playWhenReady) {
            return
        }
        if (activePlaybackSessionId == null) {
            startPlaybackRecovery(
                source = "network_return_no_session",
                bookId = currentBook.bookId,
                positionMs = logicalPlaybackPositionMs(),
                speed = player.playbackParameters.speed,
                playWhenReady = true,
            )
            return
        }
        if (
            transientRetryState == TransientRetryState.WAITING_FOR_NETWORK &&
            (player.playbackState == Player.STATE_IDLE || player.playerError != null)
        ) {
            playbackRecoveryJob?.cancel()
            transientRetryState = TransientRetryState.RETRYING
            player.prepare()
        }
    }

    private fun updateSyncSnapshot(snapshot: SyncSnapshot) {
        if (snapshot == lastSyncSnapshot) {
            return
        }
        lastSyncSnapshot = snapshot
        notifyCatalogChanged()
    }

    private fun notifyCatalogChanged() {
        if (!this::mediaLibrarySession.isInitialized) {
            return
        }
        diagnosticEventLogger?.record(
            "catalog_children_changed_notified",
            mapOf(
                "serviceInstanceId" to serviceInstanceId,
                "parents" to listOf(
                    BrowseNodeId.Recent.serialize(),
                    BrowseNodeId.Books.serialize(),
                    BrowseNodeId.Authors.serialize(),
                    BrowseNodeId.Series.serialize(),
                ).joinToString(","),
                "syncStatus" to lastSyncSnapshot.status.name,
            ),
        )
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Recent.serialize(), Int.MAX_VALUE, null)
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Books.serialize(), Int.MAX_VALUE, null)
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Authors.serialize(), Int.MAX_VALUE, null)
        mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Series.serialize(), Int.MAX_VALUE, null)
        subscribedSeriesParents.removeAll { mediaLibrarySession.getSubscribedControllers(it).isEmpty() }
        subscribedSeriesParents.forEach { parentId ->
            mediaLibrarySession.notifyChildrenChanged(parentId, Int.MAX_VALUE, null)
        }
    }

    private fun notifyRecentChanged() {
        if (::mediaLibrarySession.isInitialized) {
            diagnosticEventLogger?.record(
                "catalog_children_changed_notified",
                mapOf(
                    "serviceInstanceId" to serviceInstanceId,
                    "parents" to BrowseNodeId.Recent.serialize(),
                    "syncStatus" to lastSyncSnapshot.status.name,
                ),
            )
            mediaLibrarySession.notifyChildrenChanged(BrowseNodeId.Recent.serialize(), Int.MAX_VALUE, null)
        }
    }

    private fun isCatalogUnavailable(node: BrowseNodeId? = null): Boolean {
        if (lastSyncSnapshot.status != SyncStatus.FAILED) {
            return false
        }
        return when (node) {
            BrowseNodeId.Root,
            is BrowseNodeId.Book,
            BrowseNodeId.Series,
            is BrowseNodeId.SeriesBucket,
            is BrowseNodeId.SeriesDetail,
            -> false

            BrowseNodeId.Authors,
            is BrowseNodeId.AuthorsBucket,
            -> lastSyncSnapshot.authorCount == 0

            null -> lastSyncSnapshot.bookCount == 0 && lastSyncSnapshot.authorCount == 0
            else -> lastSyncSnapshot.bookCount == 0
        }
    }

    private fun hasStoredLoginCredentials(): Boolean {
        val stored = authStorage.load()
        val baseUrl = stored.baseUrl?.trim().orEmpty()
        val username = stored.username?.trim().orEmpty()
        val password = stored.password.orEmpty()
        return baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
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
                    diagnosticEventLogger?.record(
                        "media3_callback_failed",
                        exceptionDiagnostics(exception, "label" to label),
                    )
                    completer.setException(exception)
                } catch (throwable: Throwable) {
                    Log.e(TAG, "Media3 callback crashed: $label", throwable)
                    diagnosticEventLogger?.record(
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
        private const val AUTH_REQUIRED_SETTINGS_REQUEST_CODE = 1001
        private const val TRANSIENT_PLAYBACK_RETRY_DELAY_MS = 3_000L
        private const val MAX_PLAYBACK_SESSION_RECOVERY_ATTEMPTS = 1
        private const val MAX_RECORDED_PROCESS_EXITS = 3
        private const val ROOT_HINT_SUPPORTED_FLAGS =
            "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_SUPPORTED_FLAGS"
        private const val ROOT_HINT_CUSTOM_BROWSER_ACTION_LIMIT =
            "androidx.media.utils.MediaBrowserCompat.extras.CUSTOM_BROWSER_ACTION_LIMIT"
        private val TRANSIENT_HTTP_STATUS_CODES = setOf(502, 503, 504)
    }

    private enum class TransientRetryState {
        NONE,
        WAITING_FOR_NETWORK,
        RETRYING,
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@SuppressLint("InlinedApi")
internal fun applicationExitReasonDiagnosticName(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
    ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
    ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
    ApplicationExitInfo.REASON_CRASH -> "CRASH"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
    ApplicationExitInfo.REASON_ANR -> "ANR"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
    ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
    ApplicationExitInfo.REASON_OTHER -> "OTHER"
    ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
    else -> "REASON_$reason"
}

@Suppress("DEPRECATION")
internal fun playerCommandDiagnosticName(command: Int): String = when (command) {
    Player.COMMAND_PLAY_PAUSE -> "PLAY_PAUSE"
    Player.COMMAND_PREPARE -> "PREPARE"
    Player.COMMAND_STOP -> "STOP"
    Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> "SEEK_TO_DEFAULT_POSITION"
    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> "SEEK_IN_CURRENT_MEDIA_ITEM"
    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> "SEEK_TO_PREVIOUS_MEDIA_ITEM"
    Player.COMMAND_SEEK_TO_PREVIOUS -> "SEEK_TO_PREVIOUS"
    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> "SEEK_TO_NEXT_MEDIA_ITEM"
    Player.COMMAND_SEEK_TO_NEXT -> "SEEK_TO_NEXT"
    Player.COMMAND_SEEK_TO_MEDIA_ITEM -> "SEEK_TO_MEDIA_ITEM"
    Player.COMMAND_SEEK_BACK -> "SEEK_BACK"
    Player.COMMAND_SEEK_FORWARD -> "SEEK_FORWARD"
    Player.COMMAND_SET_SPEED_AND_PITCH -> "SET_SPEED_AND_PITCH"
    Player.COMMAND_SET_SHUFFLE_MODE -> "SET_SHUFFLE_MODE"
    Player.COMMAND_SET_REPEAT_MODE -> "SET_REPEAT_MODE"
    Player.COMMAND_SET_MEDIA_ITEM -> "SET_MEDIA_ITEM"
    Player.COMMAND_CHANGE_MEDIA_ITEMS -> "CHANGE_MEDIA_ITEMS"
    Player.COMMAND_RELEASE -> "RELEASE"
    else -> "COMMAND_$command"
}
