package com.taskly.android.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.taskly.android.core.database.NoteEntity
import com.taskly.android.core.database.TaskEntity
import java.time.ZoneId

/** Task card (AND-UI, SPEC FR-2): every property, subtasks, notes and attachments. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(vm: MainViewModel) {
    val task by vm.openTask.collectAsState()
    val t = task ?: return
    val notes by vm.openNotes.collectAsState()
    val attachments by vm.openAttachments.collectAsState()
    val subtasks by vm.openSubtasks.collectAsState()
    val tagsByTask by vm.tagNamesByTask.collectAsState()
    val lists by vm.lists.collectAsState()
    val locations by vm.locations.collectAsState()
    val inboxId by vm.inboxId.collectAsState()
    val zone = remember { ZoneId.systemDefault() }
    val labels = DueText.Labels(stringResource(R.string.view_today), stringResource(R.string.view_tomorrow), stringResource(R.string.view_yesterday))

    Column(Modifier.fillMaxSize().testTag("task_detail")) {
        BackTopBar(t.name, onBack = { vm.openTaskId.value = null }) {
            IconButton(onClick = { vm.delete(t) }, modifier = Modifier.testTag("delete_task")) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.detail_delete))
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Name: saved when it changes and is not blank
            EditableField(R.string.field_name, t.name, key = t.id + t.version, tag = "detail_name") { value ->
                if (value.isNotBlank() && value != t.name) vm.update(t) { copy(name = value.trim()) }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.toggleComplete(t) }, modifier = Modifier.testTag("detail_complete")) {
                    Text(stringResource(if (t.completedAt == null) R.string.detail_complete else R.string.detail_reopen))
                }
                OutlinedButton(onClick = { vm.postpone(t) }) { Text(stringResource(R.string.swipe_postpone)) }
            }
            if (t.postponedCount > 0) Text(stringResource(R.string.detail_postponed, t.postponedCount), style = MaterialTheme.typography.bodySmall)

            Label(R.string.detail_priority)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in listOf(1, 2, 3, null)) {
                    FilterChip(
                        selected = t.priority == p,
                        onClick = { vm.update(t) { copy(priority = p) } },
                        label = { Text(p?.toString() ?: stringResource(R.string.priority_none)) },
                        modifier = Modifier.testTag("priority_${p ?: 0}"),
                    )
                }
            }

            // Due date typed as text, parsed by the shared Smart Add date parser
            var dueError by remember(t.id) { mutableStateOf(false) }
            EditableField(
                R.string.detail_due,
                DueText.format(t.dueAt, t.dueHasTime, zone, labels).orEmpty(),
                key = t.id + t.dueAt,
                tag = "detail_due",
                placeholder = R.string.detail_due_hint,
                error = if (dueError) R.string.detail_due_invalid else null,
            ) { value ->
                val parsed = vm.parseDue(value)
                if (parsed == null) {
                    dueError = true
                } else {
                    dueError = false
                    if (parsed.first != t.dueAt || parsed.second != t.dueHasTime) vm.update(t) { copy(dueAt = parsed.first, dueHasTime = parsed.second) }
                }
            }

            EditableField(R.string.detail_estimate, t.estimateMin?.toString().orEmpty(), key = t.id + t.estimateMin, tag = "detail_estimate", number = true) { value ->
                val minutes = value.trim().toIntOrNull()?.takeIf { it > 0 }
                if (minutes != t.estimateMin) vm.update(t) { copy(estimateMin = minutes) }
            }
            EditableField(R.string.detail_url, t.url.orEmpty(), key = t.id + t.url, tag = "detail_url") { value ->
                val url = value.trim().ifEmpty { null }
                if (url != t.url) vm.update(t) { copy(url = url) }
            }
            if (t.rrule != null) {
                Label(R.string.detail_repeat)
                Text(t.rrule.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }

            // List
            val pickable = lists.filter { !it.isSmart && !it.archived }
            val listName = pickable.firstOrNull { it.id == t.listId }?.let { if (it.id == inboxId) stringResource(R.string.view_inbox) else it.name }
                ?: stringResource(R.string.view_inbox)
            Picker(R.string.detail_list, listName, pickable.map { it.id to if (it.id == inboxId) null else it.name }, tag = "detail_list") { id ->
                if (id != null && id != t.listId) vm.update(t) { copy(listId = id) }
            }
            if (locations.isNotEmpty() || t.locationId != null) {
                val locName = locations.firstOrNull { it.id == t.locationId }?.name ?: "—"
                Picker(R.string.detail_location, locName, listOf<Pair<String?, String?>>(null to "—") + locations.map { it.id to it.name }, tag = "detail_location") { id ->
                    if (id != t.locationId) vm.update(t) { copy(locationId = id) }
                }
            }

            // Tags
            Label(R.string.detail_tags)
            val tags = tagsByTask[t.id].orEmpty()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tag in tags) {
                    val removeLabel = stringResource(R.string.remove_tag, tag)
                    InputChip(
                        selected = false,
                        onClick = { vm.removeTag(t, tag) },
                        label = { Text("#$tag") },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = removeLabel) },
                    )
                }
            }
            AddField(R.string.detail_add_tag, tag = "add_tag") { vm.addTag(t, it.trim().removePrefix("#")) }

            HorizontalDivider()
            Label(R.string.detail_subtasks)
            for (s in subtasks) SubtaskRow(vm, s)
            SmartAddBar(vm, parentId = t.id, hint = R.string.detail_add_subtask, tag = "add_subtask")

            HorizontalDivider()
            Label(R.string.detail_notes)
            for (n in notes) NoteItem(vm, n)
            AddField(R.string.detail_add_note, tag = "add_note", multiline = true) { vm.addNote(t.id, it) }

            if (attachments.isNotEmpty()) {
                HorizontalDivider()
                Label(R.string.detail_attachments)
                val uri = LocalUriHandler.current
                for (a in attachments) {
                    TextButton(onClick = { runCatching { uri.openUri(a.url) } }) { Text(a.name) }
                }
            }
        }
    }
}

@Composable
private fun Label(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SubtaskRow(vm: MainViewModel, s: TaskEntity) {
    val tags by vm.tagNamesByTask.collectAsState()
    TaskRow(vm, s, tags[s.id].orEmpty())
}

/** A text field that commits on "Save" (and shows the stored value again when it changes). */
@Composable
private fun EditableField(
    label: Int,
    value: String,
    key: Any?,
    tag: String,
    placeholder: Int? = null,
    error: Int? = null,
    number: Boolean = false,
    commit: (String) -> Unit,
) {
    var text by remember(key) { mutableStateOf(value) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(label)) },
                placeholder = { if (placeholder != null) Text(stringResource(placeholder)) },
                isError = error != null,
                singleLine = true,
                keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                modifier = Modifier.weight(1f).testTag(tag),
            )
            if (text != value) {
                TextButton(onClick = { commit(text) }, modifier = Modifier.testTag("${tag}_save")) { Text(stringResource(R.string.save)) }
            }
        }
        if (error != null) Text(stringResource(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AddField(hint: Int, tag: String, multiline: Boolean = false, add: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(hint)) },
            singleLine = !multiline,
            modifier = Modifier.weight(1f).testTag(tag),
        )
        TextButton(
            onClick = { if (text.isNotBlank()) { add(text.trim()); text = "" } },
            modifier = Modifier.testTag("${tag}_send"),
        ) { Text(stringResource(R.string.smart_add_send)) }
    }
}

@Composable
private fun NoteItem(vm: MainViewModel, note: NoteEntity) {
    var editing by remember(note.id) { mutableStateOf(false) }
    var text by remember(note.id, note.version) { mutableStateOf(note.content) }
    Column(Modifier.fillMaxWidth().testTag("note_${note.id}")) {
        if (editing) {
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().testTag("note_edit"))
            Row {
                TextButton(onClick = { editing = false; if (text.isNotBlank() && text != note.content) vm.editNote(note, text) }, modifier = Modifier.testTag("note_save")) {
                    Text(stringResource(R.string.save))
                }
            }
        } else {
            Text(note.content, style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = { editing = true }) { Text(stringResource(R.string.edit)) }
                TextButton(onClick = { vm.deleteNote(note) }) { Text(stringResource(R.string.delete)) }
            }
        }
        HorizontalDivider()
    }
}

/** Value with a drop-down of choices; a null label means "Входящие". */
@Composable
private fun <K> Picker(label: Int, current: String, options: List<Pair<K, String?>>, tag: String, pick: (K) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val inbox = stringResource(R.string.view_inbox)
    Column {
        Label(label)
        TextButton(onClick = { open = true }, modifier = Modifier.testTag(tag)) { Text(current) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((key, name) in options) {
                DropdownMenuItem(text = { Text(name ?: inbox) }, onClick = { open = false; pick(key) })
            }
        }
    }
    LaunchedEffect(current) { open = false }
}
