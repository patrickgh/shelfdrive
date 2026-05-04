package io.audiobookshelf.aaos.settings

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
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
import io.audiobookshelf.aaos.media3.ShelfDriveMediaLibraryService
import io.audiobookshelf.aaos.sync.SyncCommands
import io.audiobookshelf.aaos.sync.SyncSnapshot
import io.audiobookshelf.aaos.sync.SyncStatus

class SettingsActivity : AppCompatActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentAuthSnapshot: AuthSnapshot = AuthSnapshot(status = AuthStatus.LOGGED_OUT)
    private var currentSyncSnapshot: SyncSnapshot = SyncSnapshot(status = SyncStatus.IDLE)
    private var currentCacheSnapshot: CacheSnapshot = CacheSnapshot()
    private var pendingAutoSyncAfterLogin: Boolean = false
    private var loginInProgress: Boolean = false
    private var isStarted: Boolean = false
    private var reconnectAttempt: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
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
        super.onStop()
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
        sendCommand(
            AuthCommands.CMD_LOGIN,
            bundleOf(
                AuthCommands.EXTRA_SERVER_URL to serverUrl,
                AuthCommands.EXTRA_USERNAME to username,
                AuthCommands.EXTRA_PASSWORD to password,
            ),
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

    internal fun currentAuthSnapshot(): AuthSnapshot = currentAuthSnapshot

    internal fun currentSyncSnapshot(): SyncSnapshot = currentSyncSnapshot

    internal fun currentCacheSnapshot(): CacheSnapshot = currentCacheSnapshot

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
                    onResult(result.extras)
                }.onFailure {
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
                    renderState()
                    requestAuthState()
                    requestSyncState()
                    requestCacheState()
                }.onFailure {
                    controllerFuture = null
                    mediaController = null
                    loginInProgress = false
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
        (supportFragmentManager.findFragmentById(R.id.settings_container) as? SettingsFragment)?.renderState(
            commandChannelReady = isCommandChannelReady(),
            authSnapshot = currentAuthSnapshot,
            syncSnapshot = currentSyncSnapshot,
            cacheSnapshot = currentCacheSnapshot,
            loginInProgress = loginInProgress,
        )
    }

    companion object {
        private const val RECONNECT_INITIAL_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
        private const val RECONNECT_MAX_SHIFT = 5
    }
}
