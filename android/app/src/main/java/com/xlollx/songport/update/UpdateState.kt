package com.xlollx.songport.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A newer Trackhop than the one running, as this build's AppUpdates found it (GitHub's latest
 * release for the GitHub build, Google Play for the Play build): what the top bar's arrow shows.
 */
object UpdateState {
    /** [versionName] when the source says it (GitHub), [url] where to get it (the APK), both null on Play. */
    data class Available(val versionName: String?, val url: String?)

    private val state = MutableStateFlow<Available?>(null)
    val available: StateFlow<Available?> = state

    fun set(value: Available?) { state.value = value }

    /** True when [candidate] ("1.5.4", "v1.5.4") is a later version than [current] ("1.5.3"), part by part as numbers. */
    fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
