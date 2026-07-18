package io.audiobookshelf.aaos.settings

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.android.material.appbar.MaterialToolbar
import com.google.common.util.concurrent.ListenableFuture
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

internal data class SettingsState(
    val authSnapshot: AuthSnapshot = AuthSnapshot(status = AuthStatus.LOGGED_OUT),
    val syncSnapshot: SyncSnapshot = SyncSnapshot(status = SyncStatus.IDLE),
    val cacheSnapshot: CacheSnapshot = CacheSnapshot(),
    val diagnosticsSnapshot: StartupDiagnosticsSnapshot = StartupDiagnosticsSnapshot(),
    val diagnosticsUploadSnapshot: DiagnosticsUploadSnapshot = DiagnosticsUploadSnapshot(),
    val commandChannelReady: Boolean = false,
    val loginInProgress: Boolean = false,
)

class SettingsActivity : AppCompatActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var state = SettingsState()
    private var isStarted: Boolean = false
    private var reconnectAttempt: Int = 0
    private lateinit var diagnosticEventLogger: DiagnosticEventLogger

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        diagnosticEventLogger = DiagnosticEventLogger(this)
        diagnosticEventLogger.record("settings_created")
        setContentView(R.layout.activity_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationOnClickListener { finish() }
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commitNow()
        }

    }

    override fun onStart() {
        super.onStart()
        isStarted = true
        val diagnosticsUploadStorage = DiagnosticsUploadStorage(this)
        if (diagnosticsUploadStorage.load().lastUploadStatus == DiagnosticsUploadStatus.RUNNING) {
            diagnosticsUploadStorage.recordUploadFinished(
                DiagnosticsUploadStatus.FAILED,
                "Previous upload was interrupted.",
            )
        }
        diagnosticEventLogger.record("settings_started")
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
        if (::diagnosticEventLogger.isInitialized) {
            diagnosticEventLogger.record("settings_stopped")
        }
        super.onStop()
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    internal fun performLogin(serverUrl: String?, username: String?, password: String?) {
        if (state.loginInProgress) {
            return
        }
        if (mediaController == null) {
            renderState()
            return
        }
        state = state.copy(loginInProgress = true)
        renderState()
        val args = Bundle().apply {
            putString(AuthCommands.EXTRA_SERVER_URL, serverUrl)
            putString(AuthCommands.EXTRA_USERNAME, username)
            putString(AuthCommands.EXTRA_PASSWORD, password)
        }
        sendCommand(
            AuthCommands.CMD_LOGIN,
            args,
            ::handleAuthResult,
        )
    }

    internal fun performLogout() {
        state = state.copy(loginInProgress = false)
        sendCommand(AuthCommands.CMD_LOGOUT, null, ::handleAuthResult)
    }

    internal fun performResync() {
        val previousSyncSnapshot = state.syncSnapshot
        state = state.copy(
            syncSnapshot = previousSyncSnapshot.copy(status = SyncStatus.RUNNING, message = null),
        )
        renderState()
        sendCommand(
            command = SyncCommands.CMD_SYNC_NOW,
            extras = null,
            onResult = ::handleSyncResult,
            onFailure = {
                state = state.copy(syncSnapshot = previousSyncSnapshot)
                renderState()
            },
        )
    }

    internal fun performClearCache() {
        sendCommand(CacheCommands.CMD_CLEAR_CACHE, null, ::handleClearCacheResult)
    }

    internal fun updateDiagnosticsUploadUrl(uploadUrl: String) {
        DiagnosticsUploadStorage(this).saveUploadUrl(uploadUrl)
        renderState()
    }

    internal fun performDiagnosticsUpload() {
        val storage = DiagnosticsUploadStorage(this)
        val uploadUrl = storage.load().uploadUrl
        if (uploadUrl.isBlank() || state.diagnosticsUploadSnapshot.lastUploadStatus == DiagnosticsUploadStatus.RUNNING) {
            renderState()
            return
        }

        storage.recordUploadStarted()
        renderState()

        activityScope.launch {
            DiagnosticEventLogger(this@SettingsActivity).record("diagnostics_upload_started")
            runCatching {
                val startupSnapshot = StartupDiagnosticsStorage(this@SettingsActivity).load()
                val uploadSnapshot = storage.load()
                val packageFile = DiagnosticsPackageBuilder(this@SettingsActivity).build(
                    authSnapshot = state.authSnapshot,
                    syncSnapshot = state.syncSnapshot,
                    cacheSnapshot = state.cacheSnapshot,
                    startupSnapshot = startupSnapshot,
                    uploadSnapshot = uploadSnapshot,
                )
                DiagnosticsUploader().upload(uploadUrl, packageFile)
            }.onSuccess { result ->
                storage.recordUploadFinished(
                    DiagnosticsUploadStatus.SUCCESS,
                    "HTTP ${result.statusCode}: ${result.message}",
                )
                DiagnosticEventLogger(this@SettingsActivity).record("diagnostics_upload_success")
            }.onFailure { exception ->
                storage.recordUploadFinished(
                    DiagnosticsUploadStatus.FAILED,
                    exception.message ?: exception::class.java.simpleName,
                )
                DiagnosticEventLogger(this@SettingsActivity).record(
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

    private fun requestAuthState() {
        sendCommand(AuthCommands.CMD_GET_AUTH_STATE, null, ::handleAuthResult)
    }

    private fun requestSyncState() {
        sendCommand(SyncCommands.CMD_GET_SYNC_STATE, null, ::handleSyncResult)
    }

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
            diagnosticEventLogger.record("settings_command_without_controller", mapOf("command" to command))
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
                    diagnosticEventLogger.record(
                        "settings_command_success",
                        mapOf(
                            "command" to command,
                            "resultCode" to result.resultCode.toString(),
                        ),
                    )
                    onResult(result.extras)
                }.onFailure { exception ->
                    state = state.copy(loginInProgress = false)
                    onFailure()
                    diagnosticEventLogger.record(
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
        diagnosticEventLogger.record("settings_controller_connect_started")
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
                    diagnosticEventLogger.record("settings_controller_connect_success")
                    renderState()
                    requestAuthState()
                    requestCacheState()
                }.onFailure { exception ->
                    controllerFuture = null
                    mediaController = null
                    state = state.copy(loginInProgress = false)
                    diagnosticEventLogger.record(
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
        diagnosticEventLogger.record(
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
            state = state.copy(loginInProgress = false)
            renderState()
            return
        }
        val syncSnapshot = SyncSnapshot.fromBundle(resultData)
        state = state.copy(
            authSnapshot = authSnapshot,
            syncSnapshot = syncSnapshot ?: state.syncSnapshot,
            loginInProgress = false,
        )
        renderState()
        if (syncSnapshot == null) {
            requestSyncState()
        }
        requestCacheState()
    }

    private fun handleSyncResult(resultData: Bundle?) {
        val snapshot = SyncSnapshot.fromBundle(resultData) ?: return
        state = state.copy(syncSnapshot = snapshot)
        renderState()
        requestCacheState()
    }

    private fun handleCacheResult(resultData: Bundle?) {
        val snapshot = CacheSnapshot.fromBundle(resultData) ?: return
        state = state.copy(cacheSnapshot = snapshot)
        renderState()
    }

    private fun handleClearCacheResult(resultData: Bundle?) {
        val snapshot = CacheSnapshot.fromBundle(resultData) ?: return
        state = state.copy(
            cacheSnapshot = snapshot,
            syncSnapshot = SyncSnapshot(status = SyncStatus.IDLE),
        )
        renderState()
    }

    private fun renderState() {
        state = state.copy(
            diagnosticsSnapshot = StartupDiagnosticsStorage(this).load(),
            diagnosticsUploadSnapshot = DiagnosticsUploadStorage(this).load(),
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
