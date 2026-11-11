package com.xlollx.songport.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.widget.OneSyncWidgetReceiver
import com.xlollx.songport.widget.SyncAllWidgetReceiver
import com.xlollx.songport.widget.SyncListWidgetReceiver

// ---------------------------------------------------------------------------------------------
// "Did you know?": the widgets, the icon shortcuts and automation, which people rarely find on
// their own. A small pill on Syncs after the first sync, and a row in Settings › Help.
// ---------------------------------------------------------------------------------------------

object Tips {
    /** The pill shows once a sync has completed, until it is closed or opened. */
    fun pillShown(data: StoreData): Boolean =
        !data.settings.tipsPillDismissed && data.reports.any { it.ok && !it.partial }

    /** The home-screen widgets offered on the page, in the order they are most useful. */
    internal val widgets by lazy { listOf(
        TipWidget(SyncListWidgetReceiver::class.java, R.string.widget_list_label, R.string.widget_list_description, Icons.AutoMirrored.Filled.ViewList),
        TipWidget(OneSyncWidgetReceiver::class.java, R.string.widget_one_label, R.string.widget_one_description, Icons.Filled.PlayCircle),
        TipWidget(SyncAllWidgetReceiver::class.java, R.string.widget_all_label, R.string.widget_all_description, Icons.Filled.Sync),
    ) }

    /** Whether the launcher lets an app put a widget on the home screen for the user (it asks first). */
    fun canPinWidgets(ctx: Context): Boolean =
        runCatching { AppWidgetManager.getInstance(ctx)?.isRequestPinAppWidgetSupported == true }.getOrDefault(false)

    /** Asks the launcher to place [w]; it shows its own confirmation. */
    internal fun pinWidget(ctx: Context, w: TipWidget): Boolean =
        runCatching { AppWidgetManager.getInstance(ctx).requestPinAppWidget(ComponentName(ctx, w.receiver), null, null) }.getOrDefault(false)
}

internal class TipWidget(
    val receiver: Class<*>,
    val label: Int,
    val description: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/** The pill under the Syncs header: a tap opens the page, the cross hides it for good. */
@Composable
fun TipsPill(onOpen: () -> Unit, onDismiss: () -> Unit) {
    val label = stringResource(R.string.tips_pill)
    Row(Modifier.fillMaxWidth().testTag("tips_pill"), verticalAlignment = Alignment.CenterVertically) {
        // Rounded rather than a full pill, so it can wrap to two lines at a large font size.
        Row(
            Modifier.weight(1f, fill = false).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .clickable(onClick = onOpen)
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Lightbulb, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, stringResource(R.string.tips_pill_dismiss), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The page: widgets (with "Add" where the launcher allows it), the shortcuts on the app icon, and
 * automation, with the way to its switch in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipsScreen(data: StoreData, onClose: () -> Unit, onOpenAutomation: () -> Unit) {
    val ctx = LocalContext.current
    ScreenBackHandler(onClose)
    val canPin = remember { Tips.canPinWidgets(ctx) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tips_title)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                stringResource(R.string.tips_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            // Widgets
            PrefSection(
                stringResource(R.string.tips_widgets_title), accent = JobColor.BLUE,
                description = stringResource(R.string.tips_widgets_body), footer = stringResource(R.string.tips_widgets_how),
            ) {
                Tips.widgets.forEach { w ->
                    val name = stringResource(w.label)
                    val addDesc = stringResource(R.string.tips_widget_add_cd, name)
                    PrefRow(
                        w.icon, name, stringResource(w.description),
                        trailing = if (canPin) { {
                            FilledTonalButton(onClick = { Tips.pinWidget(ctx, w) }, modifier = Modifier.semantics { contentDescription = addDesc }) {
                                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.tips_widget_add))
                            }
                        } } else null,
                    )
                }
            }

            // App-icon shortcuts, as in res/xml/shortcuts.xml plus the recent syncs (Automation.updateShortcuts).
            PrefSection(
                stringResource(R.string.tips_shortcuts_title), accent = JobColor.GREEN,
                description = stringResource(R.string.tips_shortcuts_body), footer = stringResource(R.string.tips_shortcuts_pin),
            ) {
                PrefRow(Icons.Filled.Sync, stringResource(R.string.shortcut_sync_all_short), stringResource(R.string.shortcut_sync_all_long))
                PrefRow(Icons.Filled.Add, stringResource(R.string.shortcut_new_short), stringResource(R.string.shortcut_new_long))
                if (data.syncs.isNotEmpty()) PrefRow(Icons.Filled.History, stringResource(R.string.tips_shortcuts_recent), stringResource(R.string.tips_shortcuts_recent_desc))
            }

            // Automation: off by default, switched on in Settings › Notifications.
            PrefSection(
                stringResource(R.string.tips_automation_title), accent = JobColor.ORANGE,
                description = stringResource(R.string.tips_automation_body),
            ) {
                PrefRow(
                    Icons.Filled.Bolt, stringResource(R.string.tips_automation_open),
                    stringResource(if (data.settings.automation) R.string.tips_automation_on else R.string.tips_automation_off),
                    onClick = onOpenAutomation,
                )
                PrefRow(Icons.Filled.TouchApp, stringResource(R.string.tips_automation_examples_title), stringResource(R.string.tips_automation_examples))
            }
        }
    }
}
