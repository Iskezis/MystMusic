package com.iskezis.myst.data.animatedcover

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import com.iskezis.myst.BuildConfig
import com.iskezis.myst.data.settings.AnimatedCoverPreferences

/**
 * Regras de economia de dados/bateria e de hardware. O vídeo é opcional:
 * fora de Wi-Fi, em economia de bateria, em economia de dados, na TV ou em
 * aparelhos de baixa memória, a capa estática continua sendo usada.
 */
object AnimatedCoverPolicy {

    fun isSupported(context: Context): Boolean {
        if (BuildConfig.FLAVOR == "tv") return false
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true
        return !activityManager.isLowRamDevice
    }

    fun canResolve(context: Context, preferences: AnimatedCoverPreferences): Boolean {
        if (!preferences.enabled || !isSupported(context)) return false
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isPowerSaveMode == true) return false
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            connectivity.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        ) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivity.activeNetwork ?: return false
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        @Suppress("DEPRECATION")
        val active = connectivity.activeNetworkInfo ?: return false
        @Suppress("DEPRECATION")
        return active.isConnected && !connectivity.isActiveNetworkMetered
    }
}
