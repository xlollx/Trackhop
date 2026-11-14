package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.xlollx.songport.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.coroutines.cancellation.CancellationException

/**
 * How far the back gesture has come on the screen on top (0..1), and from which edge. The
 * screens handle "back" themselves with [ScreenBackHandler]; [BackAwareScreen], around them in
 * MainActivity, reads this to draw the predictive-back preview (the screen shrinks and slides a
 * little as the finger moves, and springs back if the gesture is cancelled).
 */
class BackProgress {
    var value by mutableFloatStateOf(0f)
    /** 0 = left edge, 1 = right edge (BackEventCompat.EDGE_*). */
    var edge by mutableIntStateOf(0)
}

val LocalBackProgress = compositionLocalOf<BackProgress?> { null }

/**
 * Closes the screen on back, with the system's predictive animation on Android 14+ where the
 * gesture is enabled. A one-line replacement for `BackHandler { onClose() }`.
 */
@Composable
fun ScreenBackHandler(onClose: () -> Unit) {
    val progress = LocalBackProgress.current
    PredictiveBackHandler { events ->
        try {
            events.collect { e ->
                progress?.let { it.value = e.progress; it.edge = e.swipeEdge }
            }
            progress?.value = 0f
            onClose()
        } catch (_: CancellationException) {
            progress?.value = 0f
        }
    }
    // Composed after the screen's own, so it is asked first: with the keyboard up, Back closes it.
    ImeBackHandler()
}

/**
 * Back with the keyboard up closes the keyboard (and leaves the field) instead of leaving the
 * screen: some keyboards hand Back to the app first (owner, 12/11). Inactive with no keyboard.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ImeBackHandler() {
    val ime = androidx.compose.foundation.layout.WindowInsets.Companion.isImeVisible
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.activity.compose.BackHandler(enabled = ime) {
        keyboard?.hide()
        focus.clearFocus()
    }
}

/**
 * The frame the overlay screens are drawn in: a soft enter (fade and a slight zoom), and the
 * predictive-back preview driven by [BackProgress]. [key] names the screen, so a change of
 * screen animates and a recomposition of the same screen does not.
 */
@Composable
fun BackAwareScreen(key: Any?, content: @Composable () -> Unit) {
    val progress = remember { BackProgress() }
    AnimatedContent(
        targetState = key,
        transitionSpec = {
            (fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = spring(stiffness = 600f)))
                .togetherWith(fadeOut(tween(120)))
        },
        label = "screen",
    ) { _ ->
        val p = progress.value
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                // Material's predictive back: shrink to 90% at most, slide towards the gesture's edge.
                val scale = 1f - 0.1f * p
                scaleX = scale; scaleY = scale
                translationX = (if (progress.edge == 0) 1f else -1f) * p * size.width * 0.08f
            },
        ) {
            CompositionLocalProvider(LocalBackProgress provides progress) { content() }
        }
    }
}

/** The main tabs cross-fade: no slide, the glass bar stays where it is. */
@Composable
fun TabContent(tab: Int, content: @Composable (Int) -> Unit) {
    AnimatedContent(
        targetState = tab,
        transitionSpec = { (fadeIn(tween(180)) + scaleIn(initialScale = 0.98f, animationSpec = tween(180))).togetherWith(fadeOut(tween(90))) },
        label = "tab",
    ) { t -> content(t) }
}

/** A provider in the saved instance state, by id. */
val ProviderSaver = androidx.compose.runtime.saveable.Saver<com.xlollx.songport.providers.MusicProvider?, String>(
    save = { it?.id },
    restore = { com.xlollx.songport.providers.Providers.byId(it) },
)

/**
 * Leaving a form (a dialog, the sync editor) with something typed or chosen and not saved: back, a
 * tap beside the dialog or the top bar's arrow asks "Discard changes?" (Discard / Keep editing)
 * instead of dropping it; with nothing [changed] it closes at once. Returns that close: for the
 * dialog's onDismissRequest, the arrow and, on a full-screen editor, [ScreenBackHandler]. An
 * explicit Cancel button keeps closing at once. [onKeep]: "Keep editing" chosen (a sheet swiped
 * away shows itself again).
 */
@Composable
fun rememberDiscardGuard(changed: Boolean, onKeep: () -> Unit = {}, onClose: () -> Unit): () -> Unit {
    var asking by rememberSaveable { mutableStateOf(false) }
    val nowChanged by rememberUpdatedState(changed)
    val close by rememberUpdatedState(onClose)
    val keep by rememberUpdatedState(onKeep)
    if (asking) FitDialog(
        onDismissRequest = { asking = false; keep() },
        title = { Text(stringResource(R.string.discard_changes_title)) },
        text = { Text(stringResource(R.string.discard_changes_body)) },
        confirmButton = { TextButton(onClick = { asking = false; close() }) { Text(stringResource(R.string.discard_changes_discard)) } },
        dismissButton = { TextButton(onClick = { asking = false; keep() }) { Text(stringResource(R.string.discard_changes_keep)) } },
    )
    // Only a Back, a Cancel or a tap beside it, with the app on screen: a request that comes while the
    // app is going away or just coming back (Home, recent apps, another app) changes nothing.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val resumedAt = remember { longArrayOf(0L) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        // The events replayed while the observer is added are the dialog opening, not the app coming back.
        var adding = true
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (!adding && e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumedAt[0] = android.os.SystemClock.uptimeMillis()
        }
        lifecycle.addObserver(observer)
        adding = false
        onDispose { lifecycle.removeObserver(observer) }
    }
    return remember {
        {
            val onScreen = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                android.os.SystemClock.uptimeMillis() - resumedAt[0] > RESUME_SETTLE_MS
            if (onScreen) { if (nowChanged) asking = true else close() }
        }
    }
}

/** How long after the app comes back on screen a dialog's close request is taken as the way back, not as a tap. */
private const val RESUME_SETTLE_MS = 500L

/** How long a tap beside a dialog waits to see whether it was the start of the Home gesture. */
private const val HOME_GESTURE_MS = 500L

/**
 * A dialog's "tap beside it" that is not the Home gesture. With gesture navigation the swipe up to Home
 * starts with a touch at the bottom of the screen, beside a dialog in the middle: the dialog heard a
 * tap outside and closed (owner, 14/11: "home chiude ancora le schermate"). The request is acted on
 * only once [HOME_GESTURE_MS] went by with the app still on screen; one that comes while the app is
 * going away or just came back does nothing.
 */
@Composable
fun rememberStayingDismiss(onDismiss: () -> Unit): () -> Unit {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(onDismiss)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pausedAt = remember { longArrayOf(0L) }
    val resumedAt = remember { longArrayOf(0L) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        var adding = true
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) pausedAt[0] = android.os.SystemClock.uptimeMillis()
            if (!adding && e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumedAt[0] = android.os.SystemClock.uptimeMillis()
        }
        lifecycle.addObserver(observer)
        adding = false
        onDispose { lifecycle.removeObserver(observer) }
    }
    return remember {
        {
            val asked = android.os.SystemClock.uptimeMillis()
            val onScreen = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && asked - resumedAt[0] > RESUME_SETTLE_MS
            if (onScreen) scope.launch {
                kotlinx.coroutines.delay(HOME_GESTURE_MS)
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && pausedAt[0] < asked) latest()
            }
        }
    }
}
