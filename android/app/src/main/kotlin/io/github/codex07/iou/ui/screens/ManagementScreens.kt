package io.github.codex07.iou.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ui.components.*
import io.github.codex07.iou.ui.forms.*
import io.github.codex07.iou.ui.viewmodel.*

@Composable
fun MergeScreen(vm: MergeViewModel, page: MergeState, status: FormStatus, hidden: Boolean,
                close: () -> Unit, merged: (String) -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(status.completedId) { status.completedId?.let(merged) }
    LaunchedEffect(status.invalidated) { if (status.invalidated) confirm = false }
    FormScaffold("Merge contacts", status, vm::requestLeave, vm::keepEditing, vm::discard,
        save = { if (page.preview != null && !page.previewBusy && status.error == null) confirm = true }, hidden = hidden, onExit = close,
        saveLabel = "Review merge", canSubmit = page.preview != null && !page.previewBusy && status.error == null) {
        Text("Choose the contact to keep. No records are combined until you confirm.")
        page.source?.let { Text("Merge ${it.person.displayName} into:", style = MaterialTheme.typography.titleMedium) }
        ChoiceField("Destination contact", page.target?.value, page.candidates.map { it.person.id.value to it.person.displayName }, vm::select,
            enabled = !status.busy && !status.invalidated && status.error !is LedgerError.StaleVersion)
        if (page.previewBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.error is LedgerError.StaleVersion) Text("Close this preview and reopen Merge from the current contact. No changes were applied.")
        page.preview?.let { preview ->
            BrowseHeading("${preview.source.displayName} → ${preview.target.displayName}")
            BrowseHeading("Combined personal balances")
            BalancePanel(preview.combinedBalances, hidden)
            BrowseHeading("Combined contact positions in groups")
            preview.combinedGroupNets.forEach { group -> Text(group.group.name); BalancePanel(group.nets, hidden, group = true) }
            BrowseHeading("Entries that become neutralized")
            if (preview.neutralizedEntries.isEmpty()) Text("No entries become neutralized.")
            preview.neutralizedEntries.forEach { entry -> EntryPreview(entry, hidden) {} }
            BrowseHeading("Resulting default payment handles")
            if (preview.defaultHandles.isEmpty()) Text("No default payment handles.")
            preview.defaultHandles.forEach { handle -> InfoLine("${handle.type} · ${handle.label}", if (hidden) "Hidden" else handle.value) }
            Text("The original identity and history remain available as a merged alias. Use Unmerge to separate it later.")
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { if (!status.busy) confirm = false }, title = { Text("Merge contacts?") },
        text = { Text("${page.preview?.source?.displayName} will resolve to ${page.preview?.target?.displayName}. Balances and group identities will follow the backend preview. History is retained.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.merge() }, enabled = !status.busy) { Text("Merge contacts") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable
fun MembersScreen(state: BrowseState<MembersPage>, hidden: Boolean, retry: () -> Unit, request: (AdvancedAction) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val scroll = rememberLazyListState()
    BrowseContent(state, retry) { page ->
        val group = page.group
        BrowseList(scroll) {
            item { BrowseHeading("Members · ${group.group.name}") }
            item { Text("Removing a member keeps their history. An outstanding balance must be settled first. You cannot remove yourself.") }
            items(group.members, key = { it.person.id.value }) { member ->
                IouCard { Column(Modifier.padding(16.dp)) {
                    Text(member.person.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(if (member.active) "Active" else "Removed · history retained")
                    BalancePanel(member.nets, hidden, group = true, framed = false)
                    if (!member.person.isMe && !group.deleted) TextButton(onClick = {
                        request(AdvancedAction.Membership(group.group.id, member.person.id, group.generation, member.active, member.person.displayName))
                    }) { Text(if (member.active) "Remove member" else "Re-add member") }
                } }
            }
            if (!group.deleted) {
                item { BrowseHeading("Add member") }
                item { BrowseSearch(query, { query = it }, "Search contacts") }
                items(page.candidates.filter { it.person.displayName.contains(query.trim(), true) }, key = { "candidate:${it.person.id.value}" }) { person ->
                    TextButton(onClick = { request(AdvancedAction.Membership(group.group.id, person.person.id, group.generation, false, person.person.displayName)) }) {
                        Text("Add ${person.person.displayName}")
                    }
                }
            }
        }
    }
}
