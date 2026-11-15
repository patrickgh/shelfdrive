package io.audiobookshelf.aaos.settings

import android.content.ComponentName
import android.os.Bundle
import io.audiobookshelf.aaos.server.ServerType
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.audiobookshelf.aaos.BuildConfig
import io.audiobookshelf.aaos.R
import io.audiobookshelf.aaos.auth.AuthCommands
import io.audiobookshelf.aaos.auth.AuthSnapshot
import io.audiobookshelf.aaos.auth.AuthStatus
import io.audiobookshelf.aaos.cache.CacheCommands
import io.audiobookshelf.aaos.cache.CacheSnapshot
import io.audiobookshelf.aaos.diagnostics.DiagnosticEventLogger
import io.audiobookshelf.aaos.diagnostics.DiagnosticsPackageBuilder
import io.audiobookshelf.aaos.diagnostics.DiagnosticsUploadSnapshot
import io.audiobookshelf.aaos.diagnostics.DiagnosticsUploadStatus
import io.audiobookshelf.aaos.diagnostics.DiagnosticsUploadStorage
import io.audiobookshelf.aaos.diagnostics.DiagnosticsUploader
import io.audiobookshelf.aaos.diagnostics.StartupDiagnosticsSnapshot
import io.audiobookshelf.aaos.diagnostics.StartupDiagnosticsStorage
import io.audiobookshelf.aaos.media3.ShelfDriveMediaLibraryService
import io.audiobookshelf.aaos.sync.SyncCommands
import io.audiobookshelf.aaos.sync.SyncSnapshot
import io.audiobookshelf.aaos.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal enum class SettingsAuthAction { LOGIN, LOGOUT }

internal data class SettingsState(
    val authSnapshot: AuthSnapshot = AuthSnapshot(status = AuthStatus.LOGGED_OUT),
    val syncSnapshot: SyncSnapshot = SyncSnapshot(status = SyncStatus.IDLE),
    val cacheSnapshot: CacheSnapshot? = null,
    val diagnosticsSnapshot: StartupDiagnosticsSnapshot = StartupDiagnosticsSnapshot(),
    val diagnosticsUploadSnapshot: DiagnosticsUploadSnapshot = DiagnosticsUploadSnapshot(),
    val commandChannelReady: Boolean = false,
    val authStateKnown: Boolean = false,
    val authAction: SettingsAuthAction? = null,
    val authRequestFailed: Boolean = false,
    val cacheClearing: Boolean = false,
    val cacheClearFailed: Boolean = false,
) {
    val authActionInProgress: Boolean get() = authAction != null
}

class SettingsActivity : AppCompatActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var state = SettingsState()
    private var isStarted: Boolean = false
    private var reconnectAttempt: Int = 0
    private var diagnosticEventLogger: DiagnosticEventLogger? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        diagnosticEventLogger = if (BuildConfig.DIAGNOSTICS_ENABLED) DiagnosticEventLogger(this) else null
        diagnosticEventLogger?.record("settings_created")
        setContentView(R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commitNow()
        }

    }

    override fun onStart() {
        super.onStart()
        isStarted = true
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            val diagnosticsUploadStorage = DiagnosticsUploadStorage(this)
            if (diagnosticsUploadStorage.load().lastUploadStatus == DiagnosticsUploadStatus.RUNNING) {
                diagnosticsUploadStorage.recordUploadFinished(
                    DiagnosticsUploadStatus.FAILED,
                    "Previous upload was interrupted.",
                )
            }
        }
        diagnosticEventLogger?.record("settings_started")
        renderState()
        connectMediaController()
    }

    override fun onStop() {
        isStarted = false
        mainHandler.removeCallbacksAndMessages(null)
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        mediaController?.release()
        mediaController = null
        state = state.copy(authStateKnown = false)
        diagnosticEventLogger?.record("settings_stopped")
        super.onStop()
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    internal fun performLogin(serverType: ServerType, serverUrl: String?, username: String?, password: String?) {
        if (state.authActionInProgress || !state.authStateKnown) {
            return
        }
        if (mediaController == null) {
            renderState()
            return
        }
        state = state.copy(authAction = SettingsAuthAction.LOGIN, authRequestFailed = false)
        renderState()
        val args = Bundle().apply {
            putString(AuthCommands.EXTRA_SERVER_TYPE, serverType.name)
            putString(AuthCommands.EXTRA_SERVER_URL, serverUrl)
            putString(AuthCommands.EXTRA_USERNAME, username)
            putString(AuthCommands.EXTRA_PASSWORD, password)
        }
        sendCommand(
            AuthCommands.CMD_LOGIN,
            args,
            ::handleAuthResult,
            ::failAuthRequest,
        )
    }

    internal fun performLogout() {
        if (mediaController == null || !state.authStateKnown || state.authActionInProgress) return
        state = state.copy(authAction = SettingsAuthAction.LOGOUT, authRequestFailed = false)
        renderState()
        sendCommand(AuthCommands.CMD_LOGOUT, null, ::handleAuthResult, ::failAuthRequest)
    }

    internal fun performResync() {
        if (state.cacheClearing || state.authActionInProgress || state.syncSnapshot.status == SyncStatus.RUNNING) return
        val previousSyncSnapshot = state.syncSnapshot
        val failSync = {
            state = state.copy(syncSnapshot = previousSyncSnapshot.copy(status = SyncStatus.FAILED, message = null))
            renderState()
        }
        state = state.copy(
            syncSnapshot = previousSyncSnapshot.copy(status = SyncStatus.RUNNING, message = null),
        )
        renderState()
        sendCommand(
            command = SyncCommands.CMD_SYNC_NOW,
            extras = null,
            onResult = { result ->
                val snapshot = SyncSnapshot.fromBundle(result)
                if (snapshot == null) failSync() else updateSyncState(snapshot)
            },
            onFailure = failSync,
        )
    }

    internal fun performClearCache() {
        if (mediaController == null || state.cacheClearing || state.authActionInProgress ||
            state.syncSnapshot.status == SyncStatus.RUNNING
        ) return
        state = state.copy(cacheClearing = true, cacheClearFailed = false)
        renderState()
        sendCommand(CacheCommands.CMD_CLEAR_CACHE, null, ::handleClearCacheResult, ::failCacheClear)
    }

    internal fun updateDiagnosticsUploadUrl(uploadUrl: String) {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) return
        DiagnosticsUploadStorage(this).saveUploadUrl(uploadUrl)
        renderState()
    }

    internal fun performDiagnosticsUpload() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) return
        val storage = DiagnosticsUploadStorage(this)
        val uploadUrl = storage.load().uploadUrl
        if (uploadUrl.isBlank() || state.diagnosticsUploadSnapshot.lastUploadStatus == DiagnosticsUploadStatus.RUNNING) {
            renderState()
            return
        }

        storage.recordUploadStarted()
        renderState()

        activityScope.launch {
            diagnosticEventLogger?.record("diagnostics_upload_started")
            runCatching {
                val startupSnapshot = StartupDiagnosticsStorage(this@SettingsActivity).load()
                val uploadSnapshot = storage.load()
                val packageFile = DiagnosticsPackageBuilder(this@SettingsActivity).build(
                    authSnapshot = state.authSnapshot,
                    syncSnapshot = state.syncSnapshot,
                    cacheSnapshot = state.cacheSnapshot ?: CacheSnapshot(),
                    startupSnapshot = startupSnapshot,
                    uploadSnapshot = uploadSnapshot,
                )
                DiagnosticsUploader().upload(uploadUrl, packageFile)
            }.onSuccess { result ->
                storage.recordUploadFinished(
                    DiagnosticsUploadStatus.SUCCESS,
                    "HTTP ${result.statusCode}: ${result.message}",
                )
                diagnosticEventLogger?.record("diagnostics_upload_success")
            }.onFailure { exception ->
                storage.recordUploadFinished(
                    DiagnosticsUploadStatus.FAILED,
                    exception.message ?: exception::class.java.simpleName,
                )
                diagnosticEventLogger?.record(
                    "diagnostics_upload_failed",
                    mapOf(
                        "exception" to exception::class.java.simpleName,
                        "message" to exception.message,
                    ),
                )
            }
            renderState()
        }
    }

    internal fun currentState(): SettingsState = state.copy(commandChannelReady = mediaController != null)

    private fun requestCacheState() {
        sendCommand(CacheCommands.CMD_GET_CACHE_STATE, null, ::handleCacheResult)
    }

    private fun sendCommand(
        command: String,
        extras: Bundle?,
        onResult: (Bundle?) -> Unit,
        onFailure: () -> Unit = {},
    ) {
        val controller = mediaController
        if (controller == null) {
            diagnosticEventLogger?.record("settings_command_without_controller", mapOf("command" to command))
            onFailure()
            connectMediaController()
            renderState()
            return
        }
        val future = controller.sendCustomCommand(
            SessionCommand(command, Bundle.EMPTY),
            extras ?: Bundle.EMPTY,
        )
        future.addListener(
            {
                runCatching {
                    future.get()
                }.onSuccess { result ->
                    val succeeded = result.resultCode == SessionResult.RESULT_SUCCESS
                    diagnosticEventLogger?.record(
                        if (succeeded) "settings_command_success" else "settings_command_failed",
                        mapOf(
                            "command" to command,
                            "resultCode" to result.resultCode.toString(),
                        ),
                    )
                    if (succeeded) onResult(result.extras) else onFailure()
                }.onFailure { exception ->
                    onFailure()
                    diagnosticEventLogger?.record(
                        "settings_command_failed",
                        mapOf(
                            "command" to command,
                            "exception" to exception::class.java.simpleName,
                            "message" to exception.message,
                        ),
                    )
                    scheduleReconnect()
                    renderState()
                }
            },
            mainExecutor,
        )
    }

    private fun connectMediaController() {
        if (!isStarted || mediaController != null || controllerFuture != null) {
            return
        }
        val token = SessionToken(
            this,
            ComponentName(this, ShelfDriveMediaLibraryService::class.java),
        )
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        diagnosticEventLogger?.record("settings_controller_connect_started")
        future.addListener(
            {
                runCatching {
                    future.get()
                }.onSuccess { controller ->
                    if (!isStarted) {
                        MediaController.releaseFuture(future)
                        return@addListener
                    }
                    reconnectAttempt = 0
                    mediaController = controller
                    controllerFuture = null
                    diagnosticEventLogger?.record("settings_controller_connect_success")
                    renderState()
                    sendCommand(AuthCommands.CMD_GET_AUTH_STATE, null, ::handleAuthResult, ::failAuthRequest)
                    requestCacheState()
                }.onFailure { exception ->
                    controllerFuture = null
                    mediaController = null
                    state = state.copy(authAction = null)
                    diagnosticEventLogger?.record(
                        "settings_controller_connect_failed",
                        mapOf(
                            "exception" to exception::class.java.simpleName,
                            "message" to exception.message,
                        ),
                    )
                    renderState()
                    scheduleReconnect()
                }
            },
            mainExecutor,
        )
    }

    private fun scheduleReconnect() {
        if (!isStarted) {
            return
        }
        val delayMs = (RECONNECT_INITIAL_DELAY_MS shl reconnectAttempt.coerceAtMost(RECONNECT_MAX_SHIFT))
            .coerceAtMost(RECONNECT_MAX_DELAY_MS)
        reconnectAttempt += 1
        diagnosticEventLogger?.record(
            "settings_controller_reconnect_scheduled",
            mapOf("delayMs" to delayMs.toString()),
        )
        mainHandler.postDelayed(
            {
                if (isStarted && mediaController == null) {
                    connectMediaController()
                }
            },
            delayMs,
        )
    }

    private fun handleAuthResult(resultData: Bundle?) {
        val authSnapshot = AuthSnapshot.fromBundle(resultData)
        if (authSnapshot == null) {
            failAuthRequest()
            return
        }
        val syncSnapshot = SyncSnapshot.fromBundle(resultData)
        state = state.copy(
            authSnapshot = authSnapshot,
            syncSnapshot = syncSnapshot ?: state.syncSnapshot,
            authAction = null,
            authStateKnown = true,
            authRequestFailed = false,
        )
        renderState()
        if (syncSnapshot == null) {
            sendCommand(SyncCommands.CMD_GET_SYNC_STATE, null, ::handleSyncResult)
        }
        requestCacheState()
    }

    private fun failAuthRequest() {
        state = state.copy(authAction = null, authRequestFailed = true)
        renderState()
    }

    private fun handleSyncResult(resultData: Bundle?) {
        val snapshot = SyncSnapshot.fromBundle(resultData) ?: return
        updateSyncState(snapshot)
    }

    private fun updateSyncState(snapshot: SyncSnapshot) {
        state = state.copy(syncSnapshot = snapshot)
        renderState()
        requestCacheState()
    }

    private fun handleCacheResult(resultData: Bundle?) {
        if (resultData == null || resultData.isEmpty) return
        val snapshot = CacheSnapshot.fromBundle(resultData) ?: return
        state = state.copy(cacheSnapshot = snapshot)
        renderState()
    }

    private fun handleClearCacheResult(resultData: Bundle?) {
        val snapshot = resultData?.takeUnless { it.isEmpty }?.let(CacheSnapshot::fromBundle)
        if (snapshot == null) {
            failCacheClear()
            return
        }
        state = state.copy(
            cacheSnapshot = snapshot,
            syncSnapshot = SyncSnapshot(status = SyncStatus.IDLE),
            cacheClearing = false,
        )
        renderState()
    }

    private fun failCacheClear() {
        state = state.copy(cacheClearing = false, cacheClearFailed = true)
        renderState()
    }

    private fun renderState() {
        state = state.copy(
            diagnosticsSnapshot = if (BuildConfig.DIAGNOSTICS_ENABLED) {
                StartupDiagnosticsStorage(this).load()
            } else {
                StartupDiagnosticsSnapshot()
            },
            diagnosticsUploadSnapshot = if (BuildConfig.DIAGNOSTICS_ENABLED) {
                DiagnosticsUploadStorage(this).load()
            } else {
                DiagnosticsUploadSnapshot()
            },
            commandChannelReady = mediaController != null,
        )
        (supportFragmentManager.findFragmentById(R.id.settings_container) as? SettingsFragment)
            ?.renderState(state)
    }

    companion object {
        private const val RECONNECT_INITIAL_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
        private const val RECONNECT_MAX_SHIFT = 5
    }
}
