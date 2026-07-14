package io.audiobookshelf.aaos.absapi.socket

import android.util.Log
import io.audiobookshelf.aaos.auth.AuthRepository
import io.audiobookshelf.aaos.auth.AuthStatus
import io.audiobookshelf.aaos.auth.AuthStorage
import io.audiobookshelf.aaos.catalog.persistence.MediaProgressEntity
import io.audiobookshelf.aaos.diagnostics.DiagnosticEventLogger
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class AbsSocketClient(
    private val authStorage: AuthStorage,
    private val authRepository: AuthRepository,
    private val scope: CoroutineScope,
    private val diagnosticEventLogger: DiagnosticEventLogger? = null,
) {
    private var socket: Socket? = null

    private val _events = MutableSharedFlow<AbsSocketEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AbsSocketEvent> = _events

    private val _connectionState = MutableStateFlow(SocketConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SocketConnectionState> = _connectionState

    fun connect(baseUrl: String) {
        val current = socket
        if (current != null && current.connected()) {
            return
        }
        disconnect()
        _connectionState.value = SocketConnectionState.CONNECTING
        val opts = IO.Options().apply {
            reconnection = true
            reconnectionAttempts = Int.MAX_VALUE
            reconnectionDelay = 1_000
            reconnectionDelayMax = 30_000
            randomizationFactor = 0.5
            timeout = 20_000
            transports = arrayOf("websocket")
        }
        socket = IO.socket(URI.create(baseUrl), opts).apply {
            on(Socket.EVENT_CONNECT) { onConnected() }
            on(Socket.EVENT_DISCONNECT) { args -> onDisconnected(args) }
            on(Socket.EVENT_CONNECT_ERROR) { args -> onConnectError(args) }
            on("authenticated") { markAuthenticated("authenticated") }
            on("auth_success") { markAuthenticated("auth_success") }
            on("user_updated") { args -> emitParsed(parseUserUpdated(args)) }
            on("item_progress_updated") { args -> emitParsed(parseProgressUpdated(args)) }
            on("user_item_progress_updated") { args -> emitParsed(parseProgressUpdated(args)) }
            on("item_updated") { args -> emitParsed(parseItemChanged(args, removed = false)) }
            on("item_removed") { args -> emitParsed(parseItemChanged(args, removed = true)) }
            connect()
        }
    }

    fun disconnect() {
        socket?.let { current ->
            current.off()
            current.disconnect()
        }
        socket = null
        _connectionState.value = SocketConnectionState.DISCONNECTED
    }

    private fun onConnected() {
        _connectionState.value = SocketConnectionState.CONNECTED
        diagnosticEventLogger?.record("socket_connected")
        scope.launch(Dispatchers.IO) {
            val token = validAccessToken()
            if (token.isNullOrBlank()) {
                diagnosticEventLogger?.record("socket_auth_skipped", mapOf("reason" to "missing_access_token"))
                return@launch
            }
            socket?.emit("auth", token)
            diagnosticEventLogger?.record("socket_auth_sent")
        }
    }

    private suspend fun validAccessToken(): String? {
        val snapshot = authRepository.bootstrap()
        if (snapshot.status != AuthStatus.AUTHENTICATED) {
            return null
        }
        return authStorage.load().accessToken?.takeIf { it.isNotBlank() }
    }

    private fun onDisconnected(args: Array<Any>) {
        _connectionState.value = SocketConnectionState.DISCONNECTED
        diagnosticEventLogger?.record("socket_disconnected", mapOf("reason" to args.firstOrNull()?.toString()))
    }

    private fun onConnectError(args: Array<Any>) {
        _connectionState.value = SocketConnectionState.DISCONNECTED
        diagnosticEventLogger?.record("socket_connect_error", mapOf("error" to args.firstOrNull()?.toString()))
    }

    private fun markAuthenticated(source: String) {
        _connectionState.value = SocketConnectionState.AUTHENTICATED
        diagnosticEventLogger?.record("socket_auth_ok", mapOf("source" to source))
    }

    private fun emitParsed(event: AbsSocketEvent?) {
        if (event == null) {
            return
        }
        if (!_events.tryEmit(event)) {
            Log.w(TAG, "Socket event buffer full; dropping ${event::class.java.simpleName}.")
        }
    }

    private fun parseUserUpdated(args: Array<Any>): AbsSocketEvent? {
        val root = firstJsonObject(args) ?: return null
        val progress = root.optJSONArray("mediaProgress")
            ?: root.optJSONObject("user")?.optJSONArray("mediaProgress")
            ?: return null
        val entries = progress.toProgressEntities()
        return AbsSocketEvent.UserUpdated(entries)
    }

    private fun parseProgressUpdated(args: Array<Any>): AbsSocketEvent? {
        val root = firstJsonObject(args) ?: return null
        return root.toProgressEntity()?.let(AbsSocketEvent::ProgressUpdated)
    }

    private fun parseItemChanged(args: Array<Any>, removed: Boolean): AbsSocketEvent? {
        val root = firstJsonObject(args) ?: return null
        val itemId = root.optString("id")
            .ifBlank { root.optString("itemId") }
            .ifBlank { root.optString("libraryItemId") }
            .takeIf { it.isNotBlank() }
            ?: return null
        val libraryId = root.optString("libraryId").takeIf { it.isNotBlank() }
        return if (removed) {
            AbsSocketEvent.ItemRemoved(itemId, libraryId)
        } else {
            AbsSocketEvent.ItemUpdated(itemId, libraryId)
        }
    }

    private fun firstJsonObject(args: Array<Any>): JSONObject? {
        return args.asSequence()
            .mapNotNull { value ->
                when (value) {
                    is JSONObject -> value
                    is String -> runCatching { JSONObject(value) }.getOrNull()
                    else -> null
                }
            }
            .firstOrNull()
    }

    private fun JSONArray.toProgressEntities(): List<MediaProgressEntity> {
        return buildList(length()) {
            for (index in 0 until length()) {
                optJSONObject(index)?.toProgressEntity()?.let(::add)
            }
        }
    }

    private fun JSONObject.toProgressEntity(): MediaProgressEntity? {
        val bookId = optString("libraryItemId")
            .ifBlank { optString("bookId") }
            .ifBlank { optString("itemId") }
            .ifBlank { optString("id") }
            .takeIf { it.isNotBlank() }
            ?: return null
        val durationMs = secondsToMillis(optNullableDouble("duration"))
        val currentTimeMs = secondsToMillis(optNullableDouble("currentTime")) ?: 0L
        val isFinished = optBoolean("isFinished")
        return MediaProgressEntity(
            bookId = bookId,
            currentTimeMs = currentTimeMs,
            durationMs = durationMs,
            progressFraction = optNullableDouble("progress"),
            isFinished = isFinished,
            hideFromContinueListening = optBoolean("hideFromContinueListening", isFinished),
            lastUpdateAt = parseAbsTimestamp(opt("lastUpdate"))
                ?: parseAbsTimestamp(opt("progressLastUpdate"))
                ?: System.currentTimeMillis(),
            startedAt = parseAbsTimestamp(opt("startedAt")),
            finishedAt = parseAbsTimestamp(opt("finishedAt")),
            pendingUpload = false,
        )
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (!has(name) || isNull(name)) {
            return null
        }
        return optDouble(name).takeIf { !it.isNaN() }
    }

    private fun secondsToMillis(value: Double?): Long? {
        if (value == null || value.isNaN() || value < 0.0) {
            return null
        }
        return (value * 1000.0).toLong()
    }

    private fun parseAbsTimestamp(value: Any?): Long? {
        if (value == null || value == JSONObject.NULL) {
            return null
        }
        if (value is Number) {
            return value.toLong().takeIf { it >= 0L }
        }
        val raw = value.toString().trim()
        if (raw.isBlank()) {
            return null
        }
        return raw.toLongOrNull()?.takeIf { it >= 0L }
            ?: runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    companion object {
        private const val TAG = "AbsSocketClient"
    }
}
