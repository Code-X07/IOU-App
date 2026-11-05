package io.github.codex07.iou.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ui.components.*
import io.github.codex07.iou.ui.viewmodel.*

@Composable
fun SettlementsScreen(state: BrowseState<SettlementsPage>, hidden: Boolean, retry: () -> Unit,
                      recordPersonal: (PersonalSettlement) -> Unit, recordPlan: (PlanItem) -> Unit, openGroup: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("Personal") }
    val scroll = rememberLazyListState()
    BrowseContent(state, retry) { page ->
        BrowseList(scroll) {
            item { Notice("Suggestions, not payments", "Pay each other outside IOU, then record the repayment here. You can adjust a suggestion for a partial repayment.") }
            item { BrowseChips(listOf("Personal", "Groups"), tab, { tab = it }) }
            if (tab == "Personal") {
                if (page.personal.isEmpty()) item { Notice("All personal balances settled", "There are no available personal repayment suggestions.") }
                items(page.personal, key = { "personal:${it.person.id.value}:${it.draft.draft.amount.currency.code}" }) { suggestion ->
                    val draft = suggestion.draft.draft
                    IouCard { androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                        Text(suggestion.person.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(if (draft.from == suggestion.person.id) "${suggestion.person.displayName} pays you" else "You pay ${suggestion.person.displayName}")
                        MoneyText(draft.amount, hidden)
                        TextButton(onClick = { recordPersonal(suggestion) }) { Text("Record repayment") }
                    } }
                }
            } else {
                val visible = page.groups.filter { it.settlePlan.isNotEmpty() }
                if (visible.isEmpty()) item { Notice("All group balances settled", "There are no group repayment suggestions available.") }
                visible.forEach { group ->
                    item { TextButton(onClick = { openGroup(group.group.id.value) }) { Text(group.group.name) } }
                    items(group.settlePlan, key = { "plan:${it.group?.id?.value}:${it.from.id.value}:${it.to.id.value}:${it.amount.currency.code}" }) { plan -> SettlementSuggestion(plan, hidden, { recordPlan(plan) }) }
                }
                item { Text("Group suggestions cover all members, including repayments that do not involve you.", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
