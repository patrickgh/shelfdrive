package io.audiobookshelf.aaos.absapi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.audiobookshelf.aaos.absapi.socket.AbsSocketClient
import io.audiobookshelf.aaos.absapi.socket.SocketConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class LinkQuality {
    OFFLINE,
    DEGRADED,
    ONLINE,
}

class ConnectivityMonitor(
    context: Context,
    private val socketClient: AbsSocketClient,
    scope: CoroutineScope,
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var hasNetwork = currentNetworkAvailable()

    private val _networkAvailable = MutableStateFlow(hasNetwork)
    val networkAvailable: StateFlow<Boolean> = _networkAvailable
    private val _quality = MutableStateFlow(calculateQuality())
    val quality: StateFlow<LinkQuality> = _quality

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            publish()
        }

        override fun onLost(network: Network) {
            publish()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            publish()
        }
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        scope.launch {
            socketClient.connectionState.collect {
                publish()
            }
        }
    }

    fun close() {
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }

    private fun publish() {
        hasNetwork = currentNetworkAvailable()
        _networkAvailable.value = hasNetwork
        _quality.value = calculateQuality()
    }

    private fun calculateQuality(): LinkQuality {
        if (!hasNetwork) {
            return LinkQuality.OFFLINE
        }
        return when (socketClient.connectionState.value) {
            SocketConnectionState.AUTHENTICATED,
            SocketConnectionState.CONNECTED,
            -> LinkQuality.ONLINE
            SocketConnectionState.CONNECTING,
            SocketConnectionState.DISCONNECTED,
            -> LinkQuality.DEGRADED
        }
    }

    private fun currentNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
