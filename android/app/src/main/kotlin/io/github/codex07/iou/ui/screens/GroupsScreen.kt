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
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ui.forms.restoreAction
import io.github.codex07.iou.ui.components.*
import io.github.codex07.iou.ui.viewmodel.*

@Composable
fun GroupsScreen(state: BrowseState<List<GroupSummary>>, hidden: Boolean, retry: () -> Unit, open: (String) -> Unit, onAdd: () -> Unit = {}) {
    var filter by rememberSaveable { mutableStateOf("Active") }
    val scroll = rememberLazyListState()
    BrowseContent(state, retry) { groups ->
        val visible = groups.filter { when (filter) {
            "Deleted" -> it.deleted
            "Archived" -> !it.deleted && it.archived
            else -> !it.deleted && !it.archived
        } }
        BrowseList(scroll) {
            item { FilledTonalButton(onClick = onAdd) { Icon(IouIcons.Add, null); Text("Create group") } }
            item { BrowseChips(listOf("Active", "Archived", "Deleted"), filter, { filter = it }) }
            if (visible.isEmpty()) item { Notice("No groups here", "Groups in this category will appear here with their original currency balances.") }
            items(visible, key = { it.group.id.value }) { group ->
                Column(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = androidx.compose.animation.core.tween(IouMotion.placement)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (group.deleted) Text("Deleted · history kept")
                    GroupPreview(group, hidden, { open(group.group.id.value) }, Modifier.fillMaxWidth())
                    Text("Default currency · ${group.defaultCurrency.code}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun GroupDetailScreen(state: BrowseState<GroupDetail>, hidden: Boolean, retry: () -> Unit,
                      openPerson: (String) -> Unit, openEntry: (String) -> Unit, onEdit: () -> Unit = {},
                      request: (AdvancedAction) -> Unit = {}, onMembers: () -> Unit = {}, onRecordPlan: (PlanItem) -> Unit = {}) {
    var tab by rememberSaveable { mutableStateOf("Overview") }
    val scroll = rememberLazyListState()
    BrowseContent(state, retry) { group ->
        BrowseList(scroll) {
            item { DetailHeader(group.group.name, when {
                group.deleted -> "Deleted · history kept"
                group.archived -> "Archived"
                else -> "${group.members.count { it.active }} active members · ${group.defaultCurrency.code}"
            }) }
            item { LifecycleActions(EntityRef(EntityType.GROUP, group.group.id.value), group.token, group.group.name, group.deleted, group.archived, request) }
            if (group.archived) item { Notice("Archived group", "Outstanding positions remain in the ledger and in your totals.") }
            if (!group.deleted) item { TextButton(onClick = onMembers) { Text("Manage members") } }
            if (!group.deleted) item { TextButton(onClick = onEdit) { Text("Edit group") } }
            item { BrowseChips(listOf("Overview", "Expenses", "Members", "Balances", "Suggestions", "Activity", "History"), tab, { tab = it }) }
            when (tab) {
                "Overview" -> {
                    item { InfoLine("Default currency", group.defaultCurrency.code) }
                    item { Notice("Currency mode · ${group.fxMode}", if (group.fxMode == FxMode.CONVERT)
                        "Entry effects are converted to ${group.defaultCurrency.code} using stored FX snapshots. Original paid amounts remain available."
                        else "Original currencies are kept separately. No combined total is calculated.") }
                    item { BrowseHeading("Your group position") }
                    item {
                        val me = group.members.firstOrNull { it.person.isMe }
                        if (me == null) Notice("Position unavailable", "Your member position is unavailable in this group.")
                        else BalancePanel(me.nets, hidden, group = true)
                    }
                    item { InfoLine("Last activity", group.activity.firstOrNull()?.occurredOn?.toString() ?: "No activity") }
                    item { Text("Browse members, expenses and backend settlement suggestions using the tabs above.") }
                }
                "Expenses" -> activityRows(group.activity.filter { it.kind == EntryKind.EXPENSE }, hidden, openEntry)
                "Activity" -> activityRows(group.activity, hidden, openEntry)
                "Members", "Balances" -> {
                    if (group.members.isEmpty()) item { Text("No members available.") }
                    items(group.members, key = { it.person.id.value }) { member ->
                        IouCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Avatar(member.person.displayName)
                                Column(Modifier.weight(1f)) {
                                    Text(member.person.displayName, style = MaterialTheme.typography.titleMedium)
                                    Text(if (member.active) "Active member" else "Former member", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            BalancePanel(member.nets, hidden, group = true, framed = false)
                            if (!member.person.isMe) TextButton(onClick = { openPerson(member.person.id.value) }) { Text("View person") }
                        } }
                    }
                }
                "Suggestions" -> {
                    item { Notice("Suggested transfers", "These suggestions come from the ledger. No payment has been recorded. Choose Record repayment to review a prefilled entry, including partial amounts.") }
                    if (group.settlePlan.isEmpty()) item { Text("No settlement suggestions available.") }
                    items(group.settlePlan, key = { "plan:${it.group?.id?.value}:${it.from.id.value}:${it.to.id.value}:${it.amount.currency.code}" }) { plan -> SettlementSuggestion(plan, hidden, { onRecordPlan(plan) }) }
                }
                "History" -> revisionRows(group.history, hidden, { group.restoreAction(it)?.let(request) }, { group.restoreAction(it) != null }, onMembers)
            }
        }
    }
}

@Composable
fun SettlementSuggestion(plan: PlanItem, hidden: Boolean, record: (() -> Unit)? = null) {
    IouCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${plan.from.displayName} → ${plan.to.displayName}", style = MaterialTheme.typography.titleMedium)
            Text("Suggested · ${plan.from.displayName} pays ${plan.to.displayName}", style = MaterialTheme.typography.bodySmall)
            MoneyText(plan.amount, hidden)
            if (record != null) TextButton(onClick = record) { Text("Record repayment") }
        }
    }
}
