package com.xlollx.songport.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How wide the window is, by Material's breakpoints: a phone upright, a small tablet or a phone on its side, a tablet. */
enum class WidthClass {
    COMPACT, MEDIUM, EXPANDED;

    companion object {
        fun of(widthDp: Int): WidthClass = when {
            widthDp < 600 -> COMPACT
            widthDp < 840 -> MEDIUM
            else -> EXPANDED
        }
    }
}

/**
 * The shape of the window, worked out once at the top (MainActivity) from its size in dp and read
 * by the screens through [LocalWindowLayout]. A phone held upright is [Phone]: everything as it
 * has always been.
 */
data class WindowLayout(
    val width: WidthClass,
    /** The tabs on a rail at the side instead of the bar at the bottom: any window wider than a phone, and a phone on its side. */
    val rail: Boolean,
    /** A window too low for a keyboard and a page's bottom bar together (a phone on its side). */
    val short: Boolean,
) {
    /** The sync cards side by side where there is room for two of a phone's width. */
    val syncColumns: Int get() = if (width == WidthClass.EXPANDED) 2 else 1

    /** The widest a page's content gets: a readable column, or room for the two columns of sync cards. */
    fun maxContentWidth(twoColumns: Boolean = false): Dp = if (twoColumns && syncColumns == 2) WIDE_CONTENT else READABLE_CONTENT

    companion object {
        val Phone = WindowLayout(WidthClass.COMPACT, rail = false, short = false)
        val READABLE_CONTENT = 720.dp
        val WIDE_CONTENT = 1200.dp
        private const val SHORT_HEIGHT_DP = 480

        fun of(widthDp: Int, heightDp: Int): WindowLayout {
            val width = WidthClass.of(widthDp)
            return WindowLayout(width, rail = width != WidthClass.COMPACT || widthDp > heightDp, short = heightDp < SHORT_HEIGHT_DP)
        }
    }
}

val LocalWindowLayout = staticCompositionLocalOf { WindowLayout.Phone }

/**
 * A dialog with a preview and its choices in a low, wide window (a phone on its side): wider than
 * Material's 560 dp, so the two stand side by side instead of one hiding the other. Margins at the
 * sides, no wider than [WIDE_DIALOG]. Elsewhere the dialog is Material's own.
 */
object WideDialog {
    val WIDE_DIALOG = 840.dp
    val modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = WIDE_DIALOG).fillMaxWidth()
    val properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
}

/** A page kept to a readable width and centred in a wide window; on a phone it fills the screen as before. */
@Composable
fun ReadableWidth(modifier: Modifier = Modifier, max: Dp = LocalWindowLayout.current.maxContentWidth(), content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = max).fillMaxSize()) { content() }
    }
}

/** Secrets typed and not saved yet, by field, while the activity is remade (a rotation): in memory only. */
private val unsavedSecrets = java.util.concurrent.ConcurrentHashMap<String, String>()

/**
 * A password or a client secret being typed: it survives a rotation like the other fields, but
 * never reaches the saved instance state. Kept in this process while the activity is remade and
 * dropped as soon as the screen is left any other way.
 */
@Composable
fun rememberUnsavedSecret(key: String, initial: () -> String): MutableState<String> {
    val activity = LocalContext.current as? android.app.Activity
    val state = remember(key) { mutableStateOf(unsavedSecrets.remove(key) ?: initial()) }
    DisposableEffect(key) {
        onDispose { if (activity?.isChangingConfigurations == true) unsavedSecrets[key] = state.value }
    }
    return state
}
