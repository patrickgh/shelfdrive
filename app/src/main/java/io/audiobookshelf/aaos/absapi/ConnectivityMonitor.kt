package io.audiobookshelf.aaos.absapi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ConnectivityMonitor(context: Context) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val _networkAvailable = MutableStateFlow(currentNetworkAvailable())
    val networkAvailable: StateFlow<Boolean> = _networkAvailable

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish()
        override fun onLost(network: Network) = publish()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish()
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    fun close() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }

    private fun publish() {
        _networkAvailable.value = currentNetworkAvailable()
    }

    private fun currentNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
