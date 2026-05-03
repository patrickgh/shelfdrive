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
import androidx.core.os.bundleOf
import com.google.android.material.appbar.MaterialToolbar
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
    private lateinit var mediaBrowser: MediaBrowserCompat
    private var mediaController: MediaControllerCompat? = null
    private var currentServiceStatus: Int = R.string.settings_connection_status_initial
    private var currentAuthSnapshot: AuthSnapshot = AuthSnapshot(status = AuthStatus.LOGGED_OUT)
    private var currentSyncSnapshot: SyncSnapshot = SyncSnapshot(status = SyncStatus.IDLE)
    private var currentCacheSnapshot: CacheSnapshot = CacheSnapshot()
    private var pendingAutoSyncAfterLogin: Boolean = false
    private var loginInProgress: Boolean = false

    private val authResultReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
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
    }

    private val syncResultReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            val snapshot = SyncSnapshot.fromBundle(resultData) ?: return
            currentSyncSnapshot = snapshot
            renderState()
            requestCacheState()
        }
    }

    private val cacheResultReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            val snapshot = CacheSnapshot.fromBundle(resultData) ?: return
            currentCacheSnapshot = snapshot
            renderState()
        }
    }

    private val clearCacheResultReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            val snapshot = CacheSnapshot.fromBundle(resultData) ?: return
            currentCacheSnapshot = snapshot
            currentSyncSnapshot = SyncSnapshot(status = SyncStatus.IDLE)
            renderState()
        }
    }

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            mediaController = MediaControllerCompat(this@SettingsActivity, mediaBrowser.sessionToken).also {
                MediaControllerCompat.setMediaController(this@SettingsActivity, it)
            }
            updateServiceStatus(R.string.settings_connection_status_connected)
            requestAuthState()
            requestSyncState()
            requestCacheState()
        }

        override fun onConnectionSuspended() {
            mediaController = null
            loginInProgress = false
            updateServiceStatus(R.string.settings_connection_status_suspended)
        }

        override fun onConnectionFailed() {
            mediaController = null
            loginInProgress = false
            updateServiceStatus(R.string.settings_connection_status_failed)
        }
    }

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

        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, ShelfDriveMediaLibraryService::class.java),
            connectionCallback,
            null,
        )
    }

    override fun onStart() {
        super.onStart()
        updateServiceStatus(R.string.settings_connection_status_connecting)
        mediaBrowser.connect()
    }

    override fun onStop() {
        if (this::mediaBrowser.isInitialized && mediaBrowser.isConnected) {
            mediaBrowser.disconnect()
        }
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
            authResultReceiver,
        )
    }

    internal fun performLogout() {
        loginInProgress = false
        pendingAutoSyncAfterLogin = false
        sendCommand(AuthCommands.CMD_LOGOUT, null, authResultReceiver)
    }

    internal fun performResync() {
        sendCommand(SyncCommands.CMD_SYNC_NOW, null, syncResultReceiver)
    }

    internal fun performClearCache() {
        sendCommand(CacheCommands.CMD_CLEAR_CACHE, null, clearCacheResultReceiver)
    }

    internal fun currentServiceStatusSummary(): String = getString(currentServiceStatus)

    internal fun currentAuthSnapshot(): AuthSnapshot = currentAuthSnapshot

    internal fun currentSyncSnapshot(): SyncSnapshot = currentSyncSnapshot

    internal fun currentCacheSnapshot(): CacheSnapshot = currentCacheSnapshot

    internal fun isCommandChannelReady(): Boolean = mediaController != null

    internal fun isLoginInProgress(): Boolean = loginInProgress

    private fun requestAuthState() {
        sendCommand(AuthCommands.CMD_GET_AUTH_STATE, null, authResultReceiver)
    }

    private fun requestSyncState() {
        sendCommand(SyncCommands.CMD_GET_SYNC_STATE, null, syncResultReceiver)
    }

    private fun requestCacheState() {
        sendCommand(CacheCommands.CMD_GET_CACHE_STATE, null, cacheResultReceiver)
    }

    private fun sendCommand(command: String, extras: Bundle?, receiver: ResultReceiver) {
        mediaController?.sendCommand(command, extras, receiver) ?: renderState()
    }

    private fun updateServiceStatus(stringRes: Int) {
        currentServiceStatus = stringRes
        renderState()
    }

    private fun renderState() {
        (supportFragmentManager.findFragmentById(R.id.settings_container) as? SettingsFragment)?.renderState(
            serviceStatus = getString(currentServiceStatus),
            commandChannelReady = isCommandChannelReady(),
            authSnapshot = currentAuthSnapshot,
            syncSnapshot = currentSyncSnapshot,
            cacheSnapshot = currentCacheSnapshot,
            loginInProgress = loginInProgress,
        )
    }
}
