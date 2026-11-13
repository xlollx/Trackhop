package com.xlollx.songport.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone has the internet now: the one answer the app asks (the offline banner, a run
 * started without a connection, the engine's [NetError]). From the system's default network: online
 * while it has internet the system checked (validated), or is a VPN, which carries the check of the
 * network under it. Until the system has answered, and wherever it cannot, the app counts as online,
 * so nothing that worked before waits for an answer that never comes.
 */
object Connectivity {
    private val state = MutableStateFlow(true)
    /** True while online (or not known); false once the system says there is no internet. */
    val online: StateFlow<Boolean> = state.asStateFlow()

    @Volatile private var system: Boolean? = null
    @Volatile private var forced: Boolean? = null
    @Volatile private var started = false

    /** Tests: the answer the app takes instead of the system's (null gives the system's back). */
    fun simulate(online: Boolean?) {
        forced = online
        publish()
    }

    /** False only when it is known that there is no internet. */
    val isOffline: Boolean get() = !state.value

    private fun publish() {
        state.value = forced ?: system ?: true
    }

    /** Starts following the system's default network; once per process, at the app's start. */
    fun start(ctx: Context) {
        if (started) return
        started = true
        // The engine's classification asks this too: known offline, known online, or not known yet.
        NetError.networkUp = { forced ?: system }
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            system = usable(cm.getNetworkCapabilities(cm.activeNetwork))
            publish()
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                /** The default network now: a switch (Wi-Fi to mobile data) brings the new one before the old one goes. */
                @Volatile var current: Network? = cm.activeNetwork
                override fun onAvailable(network: Network) { current = network }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    current = network
                    system = usable(caps); publish()
                }
                override fun onLost(network: Network) {
                    if (current != null && network != current) return
                    current = null
                    system = false; publish()
                }
                override fun onUnavailable() {
                    system = false; publish()
                }
            })
        }.onFailure {
            // Not allowed or not there: the app stays as it was, online as far as it knows.
            system = null; publish()
        }
    }

    /** A network the app can use: internet, checked by the system (or a VPN over a checked one). */
    internal fun usable(caps: NetworkCapabilities?): Boolean {
        caps ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
