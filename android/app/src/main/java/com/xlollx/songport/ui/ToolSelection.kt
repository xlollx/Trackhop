package com.xlollx.songport.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.xlollx.songport.R

/**
 * Rows ticked on a tool's page (a long press starts it, as in a sync's review): the page says how
 * many and what can be done with them, and the tool's host ([ToolsScreen]) shows it in place of the
 * tool's own top bar, ✕ and "N selected", with [bottom] under the page where there is one.
 */
internal class ToolSelection(
    val count: Int,
    val onClear: () -> Unit,
    /** "Select all", null when every row is ticked already. */
    val onSelectAll: (() -> Unit)?,
    val actions: @Composable RowScope.() -> Unit = {},
    val bottom: (@Composable () -> Unit)? = null,
)

/** Where a tool's page puts its [ToolSelection]: the host's, null where the page has no host that shows it. */
internal val LocalToolSelection = compositionLocalOf<MutableState<ToolSelection?>?> { null }

/** Hands [selection] (null: nothing ticked) to the page's host while the page is shown; back clears it before leaving. */
@Composable
internal fun OfferToolSelection(selection: ToolSelection?) {
    val slot = LocalToolSelection.current
    BackHandler(enabled = selection != null) { selection?.onClear() }
    if (slot == null) return
    SideEffect { slot.value = selection }
    DisposableEffect(slot) { onDispose { slot.value = null } }
}

/** The top bar of a selection: ✕, "N selected", Select all and the page's own actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(selection: ToolSelection, windowInsets: WindowInsets? = null) {
    val title: @Composable () -> Unit = { Text(pluralStringResource(R.plurals.files_selected, selection.count, selection.count), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    val nav: @Composable () -> Unit = { IconButton(onClick = selection.onClear) { Icon(Icons.Filled.Close, stringResource(R.string.review_clear_selection)) } }
    val actions: @Composable RowScope.() -> Unit = {
        selection.onSelectAll?.let { all -> IconButton(onClick = all) { Icon(Icons.Filled.SelectAll, stringResource(R.string.review_select_all)) } }
        selection.actions(this)
    }
    if (windowInsets != null) TopAppBar(title = title, navigationIcon = nav, actions = actions, windowInsets = windowInsets)
    else TopAppBar(title = title, navigationIcon = nav, actions = actions)
}
