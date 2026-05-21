package io.audiobookshelf.aaos.settings

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.appbar.MaterialToolbar
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
import io.audiobookshelf.aaos.mediacompat.ShelfDriveMediaBrowserService
import io.audiobookshelf.aaos.sync.SyncCommands
import io.audiobookshelf.aaos.sync.SyncSnapshot
import io.audiobookshelf.aaos.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentAuthSnapshot: AuthSnapshot = AuthSnapshot(status = AuthStatus.LOGGED_OUT)
    private var currentSyncSnapshot: SyncSnapshot = SyncSnapshot(status = SyncStatus.IDLE)
    private var currentCacheSnapshot: CacheSnapshot = CacheSnapshot()
    private var currentDiagnosticsSnapshot: StartupDiagnosticsSnapshot = StartupDiagnosticsSnapshot()
    private var currentDiagnosticsUploadSnapshot: DiagnosticsUploadSnapshot = DiagnosticsUploadSnapshot()
    private var pendingAutoSyncAfterLogin: Boolean = false
    private var loginInProgress: Boolean = false
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
        currentDiagnosticsSnapshot = StartupDiagnosticsStorage(this).load()
        val diagnosticsUploadStorage = DiagnosticsUploadStorage(this)
        currentDiagnosticsUploadSnapshot = diagnosticsUploadStorage.load()
        if (currentDiagnosticsUploadSnapshot.lastUploadStatus == DiagnosticsUploadStatus.RUNNING) {
            diagnosticsUploadStorage.recordUploadFinished(
                DiagnosticsUploadStatus.FAILED,
                "Previous upload was interrupted.",
            )
            currentDiagnosticsUploadSnapshot = diagnosticsUploadStorage.load()
        }
        diagnosticEventLogger.record("settings_started")
        renderState()
        connectMediaController()
    }

    override fun onStop() {
        isStarted = false
        mainHandler.removeCallbacksAndMessages(null)
        mediaBrowser?.disconnect()
        mediaBrowser = null
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
        if (loginInProgress) {
            return
        }
        if (mediaController == null) {
            loginInProgress = false
            renderState()
            return
        }
        loginInProgress = true
        pendingAutoSyncAfterLogin = true
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
        loginInProgress = false
        pendingAutoSyncAfterLogin = false
        sendCommand(AuthCommands.CMD_LOGOUT, null, ::handleAuthResult)
    }

    internal fun performResync() {
        currentSyncSnapshot = currentSyncSnapshot.copy(status = SyncStatus.RUNNING, message = null)
        renderState()
        sendCommand(SyncCommands.CMD_SYNC_NOW, null, ::handleSyncResult)
    }

    internal fun performClearCache() {
        currentCacheSnapshot = CacheSnapshot(totalBytes = 0L, clearedAt = System.currentTimeMillis())
        currentSyncSnapshot = SyncSnapshot(status = SyncStatus.IDLE)
        renderState()
        sendCommand(CacheCommands.CMD_CLEAR_CACHE, null, ::handleClearCacheResult)
    }

    internal fun updateDiagnosticsUploadUrl(uploadUrl: String) {
        DiagnosticsUploadStorage(this).saveUploadUrl(uploadUrl)
        currentDiagnosticsUploadSnapshot = DiagnosticsUploadStorage(this).load()
        renderState()
    }

    internal fun performDiagnosticsUpload() {
        val storage = DiagnosticsUploadStorage(this)
        val uploadUrl = storage.load().uploadUrl
        if (uploadUrl.isBlank() || currentDiagnosticsUploadSnapshot.lastUploadStatus == DiagnosticsUploadStatus.RUNNING) {
            renderState()
            return
        }

        storage.recordUploadStarted()
        currentDiagnosticsUploadSnapshot = storage.load()
        renderState()

        activityScope.launch {
            DiagnosticEventLogger(this@SettingsActivity).record("diagnostics_upload_started")
            runCatching {
                val startupSnapshot = StartupDiagnosticsStorage(this@SettingsActivity).load()
                val uploadSnapshot = storage.load()
                val packageFile = DiagnosticsPackageBuilder(this@SettingsActivity).build(
                    authSnapshot = currentAuthSnapshot,
                    syncSnapshot = currentSyncSnapshot,
                    cacheSnapshot = currentCacheSnapshot,
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
            currentDiagnosticsSnapshot = StartupDiagnosticsStorage(this@SettingsActivity).load()
            currentDiagnosticsUploadSnapshot = storage.load()
            renderState()
        }
    }

    internal fun currentAuthSnapshot(): AuthSnapshot = currentAuthSnapshot

    internal fun currentSyncSnapshot(): SyncSnapshot = currentSyncSnapshot

    internal fun currentCacheSnapshot(): CacheSnapshot = currentCacheSnapshot

    internal fun currentDiagnosticsSnapshot(): StartupDiagnosticsSnapshot = currentDiagnosticsSnapshot

    internal fun currentDiagnosticsUploadSnapshot(): DiagnosticsUploadSnapshot = currentDiagnosticsUploadSnapshot

    internal fun isCommandChannelReady(): Boolean = mediaController != null

    internal fun isLoginInProgress(): Boolean = loginInProgress

    private fun requestAuthState() {
        sendCommand(AuthCommands.CMD_GET_AUTH_STATE, null, ::handleAuthResult)
    }

    private fun requestSyncState() {
        sendCommand(SyncCommands.CMD_GET_SYNC_STATE, null, ::handleSyncResult)
    }

    private fun requestCacheState() {
        sendCommand(CacheCommands.CMD_GET_CACHE_STATE, null, ::handleCacheResult)
    }

    private fun sendCommand(command: String, extras: Bundle?, onResult: (Bundle?) -> Unit) {
        val controller = mediaController
        if (controller == null) {
            diagnosticEventLogger.record("settings_command_without_controller", mapOf("command" to command))
            connectMediaController()
            renderState()
            return
        }
        controller.sendCommand(
            command,
            extras ?: Bundle.EMPTY,
            object : ResultReceiver(mainHandler) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    diagnosticEventLogger.record(
                        "settings_command_success",
                        mapOf(
                            "command" to command,
                            "resultCode" to resultCode.toString(),
                        ),
                    )
                    if (resultCode == AuthCommands.RESULT_OK) {
                        onResult(resultData)
                    } else {
                        scheduleReconnect()
                        renderState()
                    }
                }
            },
        )
    }

    private fun connectMediaController() {
        if (!isStarted || mediaController != null || mediaBrowser != null) {
            return
        }
        val browser = MediaBrowserCompat(
            this,
            ComponentName(this, ShelfDriveMediaBrowserService::class.java),
            object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    val connectedBrowser = mediaBrowser ?: return
                    reconnectAttempt = 0
                    mediaController = MediaControllerCompat(
                        this@SettingsActivity,
                        connectedBrowser.sessionToken,
                    )
                    diagnosticEventLogger.record("settings_controller_connect_success")
                    renderState()
                    requestAuthState()
                    requestSyncState()
                    requestCacheState()
                }

                override fun onConnectionSuspended() {
                    mediaController = null
                    mediaBrowser = null
                    loginInProgress = false
                    diagnosticEventLogger.record("settings_controller_connect_suspended")
                    renderState()
                    scheduleReconnect()
                }

                override fun onConnectionFailed() {
                    mediaController = null
                    mediaBrowser = null
                    loginInProgress = false
                    diagnosticEventLogger.record("settings_controller_connect_failed")
                    renderState()
                    scheduleReconnect()
                }
            },
            null,
        )
        mediaBrowser = browser
        diagnosticEventLogger.record("settings_controller_connect_started")
        browser.connect()
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
        val snapshot = AuthSnapshot.fromBundle(resultData)
        if (snapshot == null) {
            loginInProgress = false
            renderState()
            return
        }
        loginInProgress = false
        currentAuthSnapshot = snapshot
        renderState()
        if (snapshot.isAuthenticated && pendingAutoSyncAfterLogin) {
            pendingAutoSyncAfterLogin = false
            performResync()
        } else {
            pendingAutoSyncAfterLogin = false
            requestSyncState()
        }
        requestCacheState()
    }

    private fun handleSyncResult(resultData: Bundle?) {
        val snapshot = SyncSnapshot.fromBundle(resultData) ?: return
        currentSyncSnapshot = snapshot
        renderState()
        requestCacheState()
    }

    private fun handleCacheResult(resultData: Bundle?) {
        val snapshot = CacheSnapshot.fromBundle(resultData) ?: return
        currentCacheSnapshot = snapshot
        renderState()
    }

    private fun handleClearCacheResult(resultData: Bundle?) {
        val snapshot = CacheSnapshot.fromBundle(resultData) ?: return
        currentCacheSnapshot = snapshot
        currentSyncSnapshot = SyncSnapshot(status = SyncStatus.IDLE)
        renderState()
    }

    private fun renderState() {
        currentDiagnosticsSnapshot = StartupDiagnosticsStorage(this).load()
        currentDiagnosticsUploadSnapshot = DiagnosticsUploadStorage(this).load()
        (supportFragmentManager.findFragmentById(R.id.settings_container) as? SettingsFragment)?.renderState(
            commandChannelReady = isCommandChannelReady(),
            authSnapshot = currentAuthSnapshot,
            syncSnapshot = currentSyncSnapshot,
            cacheSnapshot = currentCacheSnapshot,
            diagnosticsSnapshot = currentDiagnosticsSnapshot,
            diagnosticsUploadSnapshot = currentDiagnosticsUploadSnapshot,
            loginInProgress = loginInProgress,
        )
    }

    companion object {
        private const val RECONNECT_INITIAL_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
        private const val RECONNECT_MAX_SHIFT = 5
    }
}
