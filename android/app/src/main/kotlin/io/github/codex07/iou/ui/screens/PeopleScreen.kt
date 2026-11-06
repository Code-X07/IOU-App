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
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ui.forms.restoreAction
import io.github.codex07.iou.ui.components.*
import io.github.codex07.iou.ui.viewmodel.*

@Composable
fun PeopleScreen(state: BrowseState<List<PersonSummary>>, hidden: Boolean, retry: () -> Unit, open: (String) -> Unit, onAdd: () -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Active") }
    val scroll = rememberLazyListState()
    BrowseContent(state, retry) { people ->
        val visible = people.filter { when (filter) {
            "Deleted" -> it.deleted
            "Archived" -> !it.deleted && it.archived
            else -> !it.deleted && !it.archived
        } }.filter { it.person.displayName.contains(query.trim(), true) }
        BrowseList(scroll) {
            item { FilledTonalButton(onClick = onAdd) { Icon(IouIcons.Add, null); Text("Add person") } }
            item { BrowseSearch(query, { query = it }, "Search people") }
            item { BrowseChips(listOf("Active", "Archived", "Deleted"), filter, { filter = it }) }
            item { Text("Personal balances only. Group balances stay inside each group.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            if (visible.isEmpty()) item { Notice("No people found", "Try another name or filter. Contacts added later will appear here.") }
            items(visible, key = { it.person.id.value }) { person ->
                IouCard(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = androidx.compose.animation.core.tween(IouMotion.placement))) { Column {
                    if (person.deleted) Text("Deleted · history kept", Modifier.padding(16.dp))
                    PersonPreview(person, hidden) { open(person.person.id.value) }

                } }
            }
        }
    }
}

@Composable
fun PersonDetailScreen(state: BrowseState<PersonPage>, hidden: Boolean, retry: () -> Unit,
                       openGroup: (String) -> Unit, openEntry: (String) -> Unit, onEdit: () -> Unit = {},
                       onAddHandle: () -> Unit = {}, onEditHandle: (String) -> Unit = {}, request: (AdvancedAction) -> Unit = {},
                       onMerge: () -> Unit = {}, onRecordPlan: (PlanItem) -> Unit = {}) {
    var history by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    BrowseContent(state, retry) { page ->
        val person = page.detail
        BrowseList(scroll) {
            item { DetailHeader(person.person.displayName, when {
                person.deleted -> "Deleted · history kept"
                person.archived -> "Archived · balances retained"
                else -> "Personal account"
            }) }
            item { LifecycleActions(EntityRef(EntityType.PERSON, person.person.id.value), person.token, person.person.displayName, person.deleted, person.archived, request) }
            if (person.archived && person.balances.any { it.amountMinor != 0L }) item { Notice("Archived with open balances", "These personal amounts still count in your totals. Archiving does not settle them.") }
            if (!person.deleted) item { TextButton(onClick = onMerge) { Text("Merge contact") } }
            items(person.aliases, key = { "alias:${it.person.id.value}" }) { alias ->
                TextButton(onClick = { request(AdvancedAction.Unmerge(alias)) }) { Text("Unmerge ${alias.person.displayName}") }
            }
            if (!person.deleted) item { TextButton(onClick = onEdit) { Text("Edit person") } }
            person.note?.let { item { InfoLine("Note", if (hidden) "Hidden" else it) } }
            person.defaultCurrency?.let { item { InfoLine("Preferred currency", it.code) } }
            if (person.aliases.isNotEmpty()) item {
                Notice("Merged identities", person.aliases.joinToString { it.person.displayName } + ". Balances use the canonical contact.")
            }
            item { BrowseHeading("Personal outstanding balances") }
            item { BalancePanel(person.balances, hidden) }
            item { Notice("Separate currencies", "Personal debts exclude groups. Each currency is tracked separately.") }
            item { BrowseHeading("Payment details") }
            if (!person.deleted) item { TextButton(onClick = onAddHandle) { Text("Add payment handle") } }
            if (person.handles.isEmpty()) item { Text("No payment handles saved.") }
            items(person.handles, key = { it.id.value }) { handle ->
                IouCard { Column(Modifier.padding(16.dp)) {
                    InfoLine("${handle.type} · ${handle.label}${if (handle.isDefault) " · Default" else ""}",
                        if (hidden) "Hidden" else handle.value)
                    handle.holderName?.let { InfoLine("Holder", it) }
                    handle.bic?.let { InfoLine("BIC", if (hidden) "Hidden" else it) }
                    InfoLine("Owner", handle.owner.displayName)
                    if (!person.deleted) LifecycleActions(EntityRef(EntityType.PAYMENT_HANDLE, handle.id.value), handle.token, "payment handle", false, null, request)
                    if (!person.deleted) TextButton(onClick = { onEditHandle(handle.id.value) }) { Text("Edit payment handle") }
                } }
            }
            item { Text("Contact details only. IOU does not send payments.", style = MaterialTheme.typography.bodySmall) }
            item { BrowseHeading("Related groups") }
            if (page.relatedGroups.isEmpty()) item { Text("No related group activity or settlement suggestions.") }
            if (page.relatedGroups.isNotEmpty()) item { Text("Your group positions are separate from personal debts.", style = MaterialTheme.typography.bodySmall) }
            items(page.relatedGroups, key = { it.group.id.value }) { group ->
                GroupPreview(group, hidden, { openGroup(group.group.id.value) }, Modifier.fillMaxWidth())
            }
            if (person.inGroups.isNotEmpty()) item { BrowseHeading("Group suggestions · separate from personal") }
            person.inGroups.forEach { suggestion ->
                item { TextButton(onClick = { openGroup(suggestion.group.id.value) }) { Text(suggestion.group.name) } }
                items(suggestion.items, key = { "plan:${it.group?.id?.value}:${it.from.id.value}:${it.to.id.value}:${it.amount.currency.code}" }) { plan -> SettlementSuggestion(plan, hidden, { onRecordPlan(plan) }) }
            }
            item { BrowseHeading("Personal activity") }
            activityRows(person.activity.filter { it.group == null }, hidden, openEntry)
            item { HistoryToggle("Contact & payment-handle history", history, person.history.size) { history = !history } }
            if (history) revisionRows(person.history, hidden, { person.restoreAction(it)?.let(request) }, { person.restoreAction(it) != null })
        }
    }
}
