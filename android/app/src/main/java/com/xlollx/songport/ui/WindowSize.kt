package com.xlollx.songport.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
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
    /** How many columns of sync cards the window has room for ([cardColumns] beside the rail); one on a phone upright. */
    val syncColumns: Int = 1,
) {
    /**
     * The largest sync card that leaves room for two in this window: a low window (a phone on its
     * side, about 350 dp of list under the pinned pill) shows them [CardSize.SMALL].
     */
    val largestCard: CardSize get() = if (short) CardSize.SMALL else CardSize.LARGE

    /** The widest a page's content gets: a readable column, or room for the two columns of sync cards. */
    fun maxContentWidth(twoColumns: Boolean = false): Dp = if (twoColumns && syncColumns >= 2) WIDE_CONTENT else READABLE_CONTENT

    companion object {
        val Phone = WindowLayout(WidthClass.COMPACT, rail = false, short = false)
        val READABLE_CONTENT = 720.dp
        val WIDE_CONTENT = 1200.dp
        private const val SHORT_HEIGHT_DP = 480

        /** The tiles view: as many columns of tiles at least [TILE_MIN_DP] wide as fit in [widthDp] of list, never fewer than two. */
        const val TILE_MIN_DP = 160

        /** About what the rail of tabs takes from a window's width. */
        const val RAIL_DP = 80

        /** The narrowest a sync card gets in a column of its own beside others. */
        const val CARD_MIN_DP = 360

        /**
         * Columns of sync cards in [listDp] of list (side padding included): as many cards at least
         * [CARD_MIN_DP] wide as fit, at most three. A phone on its side ([short]) with 520 dp or more
         * always gets two, its cards Small and narrower: more of them in sight instead of one alone.
         */
        fun cardColumns(listDp: Int, short: Boolean): Int {
            val fit = ((minOf(listDp, WIDE_CONTENT.value.toInt()) - 2 * 16 + 12) / (CARD_MIN_DP + 12)).coerceIn(1, 3)
            return if (short && listDp >= 520) maxOf(fit, 2) else fit
        }

        /** A window too low for Material's narrow dialogs and a page's bottom bar: a phone on its side. */
        fun isShort(heightDp: Int): Boolean = heightDp < SHORT_HEIGHT_DP

        fun tileColumns(widthDp: Int): Int = ((widthDp - 2 * 16 + 12) / (TILE_MIN_DP + 12)).coerceAtLeast(2)

        fun of(widthDp: Int, heightDp: Int): WindowLayout {
            val width = WidthClass.of(widthDp)
            val rail = width != WidthClass.COMPACT || widthDp > heightDp
            val short = isShort(heightDp)
            return WindowLayout(width, rail, short, syncColumns = if (rail) cardColumns(widthDp - RAIL_DP, short) else 1)
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

/**
 * Material's [AlertDialog], made for a low window too (a phone on its side, about 400 dp high):
 * - [text] always scrolls, so every field and button in it can be reached, while the dialog's own
 *   buttons stay in sight under it. A [text] that is a list of its own ([scroll] = false) scrolls that instead;
 * - in a low window the dialog is wider than Material's 560 dp, up to [FitDialogDefaults.MAX_WIDTH] with margins
 *   at the sides, so its chips, fields and buttons take fewer rows of the little height there is.
 * Upright and on a tablet it looks as ever. Read from the window itself, so it also works outside the
 * main screens (another activity, the lock screen).
 */
@Composable
fun FitDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    scroll: Boolean = true,
    text: @Composable (() -> Unit)? = null,
) {
    val short = WindowLayout.isShort(LocalConfiguration.current.screenHeightDp)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = if (short) modifier.then(FitDialogDefaults.modifier) else modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text?.let { body -> { if (scroll) Column(Modifier.verticalScroll(rememberScrollState())) { body() } else body() } },
        properties = if (short) WideDialog.properties else DialogProperties(),
    )
}

object FitDialogDefaults {
    val MAX_WIDTH = 640.dp
    val modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = MAX_WIDTH).fillMaxWidth()
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
