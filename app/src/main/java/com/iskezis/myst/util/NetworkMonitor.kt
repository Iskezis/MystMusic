package com.iskezis.myst.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado de conectividade observável. A Navegação usa para trocar os erros de
 * rede pelas músicas baixadas, sem esperar uma chamada falhar.
 */
class NetworkMonitor(context: Context) {

    private val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _online = MutableStateFlow(isOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _online.value = true
        }

        override fun onLost(network: Network) {
            _online.value = isOnline()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            _online.value = hasInternet(capabilities)
        }
    }

    init {
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            manager.registerNetworkCallback(request, callback)
        }.onFailure {
            AppLog.failure(AppLog.TAG, "Não foi possível observar a conectividade", it)
        }
    }

    fun isOnline(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(active) ?: return false
            return hasInternet(capabilities)
        }
        @Suppress("DEPRECATION")
        val connected = manager.activeNetworkInfo?.isConnected == true
        return connected
    }

    private fun hasInternet(capabilities: NetworkCapabilities): Boolean {
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            true
        }
    }
}
