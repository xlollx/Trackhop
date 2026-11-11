package com.xlollx.songport.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A sync to bring into view on the Syncs tab and light up for a moment: asked by a tap on it in a
 * home-screen widget, so it is clear which card that was.
 */
object JobFocus {
    val request = MutableStateFlow<String?>(null)
}

/** A connector to bring into view on the Accounts tab: asked by the "sign in again" notification. */
object ConnectorFocus {
    val request = MutableStateFlow<String?>(null)
}
