package com.xlollx.songport.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.sync.Labels

/**
 * What the tag editor holds: the sync's tags, and the key and value being typed. Typed text counts
 * as a tag when the sync is saved ([all]), so nothing is lost by not pressing "+".
 */
data class TagDraft(val tags: List<Pair<String, String>> = emptyList(), val key: String = "", val value: String = "") {
    /** The tags to save: those added, and the one being typed when it has a key. */
    val all: Map<String, String> get() = Labels.cleanTags((tags + (key to value)).toMap())

    fun added() = if (key.isBlank()) this else TagDraft(Labels.cleanTags((tags.filter { Labels.fold(it.first) != Labels.fold(key) } + (key to value)).toMap()).toList())
    fun without(k: String) = copy(tags = tags.filter { it.first != k })
}

val TagDraftSaver: Saver<TagDraft, Any> = listSaver(
    save = { d -> arrayListOf(d.key, d.value) + d.tags.flatMap { listOf(it.first, it.second) } },
    restore = { l -> TagDraft(l.drop(2).chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }, l[0], l[1]) },
)

const val TAG_KEY_FIELD = "tag-key"
const val TAG_VALUE_FIELD = "tag-value"

/**
 * The sync's tags in the editor: each as a chip that can be removed, then a key and an optional
 * value to add one. Under the fields, the keys (and, once a key is typed, its values) other syncs
 * already use, one tap away, so the same tag is not written three ways.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsEditor(draft: TagDraft, jobs: List<SyncJob>, onChange: (TagDraft) -> Unit) {
    // Folded until asked for; folding keeps what is typed, since the draft lives with the editor.
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = !open },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tags_title), style = MaterialTheme.typography.labelLarge)
                // Folded: the sync's tags in one line, so they are not hidden away.
                val summary = draft.all.entries.joinToString(" · ") { tagText(it.key, it.value) }
                if (!open && summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!open) return@Column
        Text(stringResource(R.string.tags_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (draft.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            draft.tags.forEach { (k, v) ->
                val text = tagText(k, v)
                InputChip(
                    selected = false, onClick = { onChange(draft.without(k)) }, label = { Text(text, maxLines = 1) },
                    trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.tag_remove, text), Modifier.size(InputChipDefaults.IconSize)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.key, onValueChange = { onChange(draft.copy(key = it.take(Labels.MAX_KEY))) },
                label = { Text(stringResource(R.string.tag_key), maxLines = 1) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f).testTag(TAG_KEY_FIELD),
            )
            OutlinedTextField(
                value = draft.value, onValueChange = { onChange(draft.copy(value = it.take(Labels.MAX_VALUE))) },
                label = { Text(stringResource(R.string.tag_value), maxLines = 1) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onChange(draft.added()) }),
                modifier = Modifier.weight(1f).testTag(TAG_VALUE_FIELD),
            )
            FilledTonalIconButton(enabled = draft.key.isNotBlank(), onClick = { onChange(draft.added()) }) {
                Icon(Icons.Filled.Add, stringResource(R.string.tag_add))
            }
        }
        // Already used elsewhere: the values of the key typed, or the keys this sync does not have yet.
        val key = Labels.fold(draft.key)
        val knownKey = Labels.keysInUse(jobs).firstOrNull { Labels.fold(it) == key }
        val suggestions = if (knownKey != null) {
            Labels.valuesInUse(jobs, knownKey).filter { Labels.fold(it) != Labels.fold(draft.value) && Labels.fold(it).startsWith(Labels.fold(draft.value)) }
                .map { it to draft.copy(key = knownKey, value = it) }
        } else {
            Labels.keysInUse(jobs).filter { k -> Labels.fold(k).startsWith(key) && draft.tags.none { Labels.fold(it.first) == Labels.fold(k) } }
                .map { it to draft.copy(key = it) }
        }
        if (suggestions.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.take(12).forEach { (text, next) -> SuggestionChip(onClick = { onChange(next) }, label = { Text(text, maxLines = 1) }) }
        }
    }
}

/** A tag as it reads on screen: "genre: metal", or the key alone. */
fun tagText(key: String, value: String): String = if (value.isEmpty()) key else "$key: $value"
