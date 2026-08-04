package com.example.impulse.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.impulse.BuildConfig
import com.example.impulse.ConnectionManager
import com.example.impulse.transport.ConnectionState
import com.example.impulse.util.LogManager

/**
 * Watches for connectivity changes and reconnects any server whose transport
 * died while the network was down. Previously the client relied solely on QUIC
 * to notice a WiFi <-> cellular handover, which can take many seconds or fail
 * entirely in Doze (Bug: "no instant reconnect on network change").
 *
 * The callback is registered app-process-wide by [register]; it only needs to
 * run once. [ConnectionManager] is a process-lifetime singleton, so observing
 * its live controllers is safe here.
 */
class NetworkMonitor private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            LogManager.i(TAG, "network available — reconnecting errored servers")
            try {
                val cm = ConnectionManager.getInstance(appContext)
                for ((_, server) in cm.serverStates.value) {
                    if (server.state == ConnectionState.ERROR) {
                        val controller = cm.getControllerOrNull(server.server.id) ?: continue
                        // Re-connect only if it is still alive and not mid-reconnect.
                        if (controller.state.value == ConnectionState.ERROR) {
                            LogManager.i(TAG, "reconnecting server=${server.server.id} after network change")
                            cm.connect(server.server, controller.clientName)
                        }
                    }
                }
            } catch (t: Throwable) {
                // Never let a background network callback tear down the process.
                LogManager.e(TAG, "onAvailable failed", t)
                com.example.impulse.util.CrashLog.writeCrash(
                    com.example.impulse.util.CrashLog.buildCrashReport(
                        thread = Thread.currentThread(),
                        throwable = t,
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        sdkInt = android.os.Build.VERSION.SDK_INT,
                        release = android.os.Build.VERSION.RELEASE,
                        manufacturer = android.os.Build.MANUFACTURER,
                        model = android.os.Build.MODEL,
                        timeMillis = System.currentTimeMillis(),
                        extra = "NetworkMonitor.onAvailable",
                    )
                )
            }
        }
    }

    fun register() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            LogManager.i(TAG, "network callback registered")
        } catch (e: Exception) {
            LogManager.w(TAG, "failed to register network callback", e)
        }
    }

    fun unregister() {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) { }
    }

    companion object {
        private const val TAG = "NetworkMonitor"

        @Volatile
        private var INSTANCE: NetworkMonitor? = null

        fun getInstance(context: Context): NetworkMonitor =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkMonitor(context.applicationContext).also { it.register() }
            }
    }
}
