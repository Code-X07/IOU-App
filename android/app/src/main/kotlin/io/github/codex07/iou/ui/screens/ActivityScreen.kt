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
import io.github.codex07.iou.ui.forms.restoreAction
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.ui.components.*
import io.github.codex07.iou.ui.viewmodel.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ActivityScreen(state: BrowseState<ActivityPage>, hidden: Boolean, retry: () -> Unit,
                   filter: (ActivityQuery) -> Unit, openEntry: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(ActivityType.ALL.name) }
    var person by rememberSaveable { mutableStateOf<String?>(null) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableStateOf("Any time") }
    var status by rememberSaveable { mutableStateOf("Current") }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val query = ActivityQuery(text, ActivityType.valueOf(type), person?.let(::PersonId), group?.let(::GroupId),
        when (period) { "Last 7 days" -> 7L; "Last 30 days" -> 30L; else -> null }, status == "Deleted")
    LaunchedEffect(query) { filter(query) }
    val scroll = rememberLazyListState()
    BrowseContent(state, retry) { page ->
        BrowseList(scroll) {
            item { BrowseSearch(text, { text = it }, "Search activity") }
            item { BrowseChips(ActivityType.entries.map { it.label }, ActivityType.valueOf(type).label,
                { label -> type = ActivityType.entries.first { it.label == label }.name }) }
            item {
                FilterStrip("Activity date, person and group filters") {
                    item { AssistChip(onClick = { picker = "date" }, label = { Text(period) }) }
                    item { AssistChip(onClick = { picker = "person" }, label = {
                        Text(page.people.firstOrNull { it.person.id.value == person }?.person?.displayName ?: "Person · All")
                    }) }
                    item { AssistChip(onClick = { picker = "group" }, label = {
                        Text(page.groups.firstOrNull { it.group.id.value == group }?.group?.name ?: "Group · All")
                    }) }
                    item { FilterChip(status == "Deleted", { status = if (status == "Deleted") "Current" else "Deleted" }, label = { Text("Deleted") }) }
                    item { TextButton(onClick = {
                        text = ""; type = ActivityType.ALL.name; person = null; group = null; period = "Any time"; status = "Current"
                    }) { Text("Clear filters") } }
                }
            }
            activityRows(page.entries, hidden, openEntry)
        }
        if (picker != null) AlertDialog(onDismissRequest = { picker = null },
            title = { Text(when (picker) { "person" -> "Filter by person"; "date" -> "Date period"; else -> "Filter by group" }) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (picker == "date") items(listOf("Any time", "Last 7 days", "Last 30 days")) { choice ->
                        TextButton(onClick = { period = choice; picker = null }, modifier = Modifier.fillMaxWidth()) { Text(choice) }
                    } else item { TextButton(onClick = {
                        if (picker == "person") person = null else group = null
                        picker = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("All") } }
                    if (picker == "person") items(page.people, key = { it.person.id.value }) { contact ->
                        TextButton(onClick = { person = contact.person.id.value; picker = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(contact.person.displayName)
                        }
                    } else if (picker == "group") items(page.groups, key = { it.group.id.value }) { team ->
                        TextButton(onClick = { group = team.group.id.value; picker = null }, modifier = Modifier.fillMaxWidth()) { Text(team.group.name) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { picker = null }) { Text("Close") } })
    }
}

fun ActivityItem.typeLabel(): String = when {
    kind == EntryKind.EXPENSE -> "Shared expense"
    transferKind == TransferKind.LOAN -> "Personal loan"
    transferKind == TransferKind.REPAYMENT -> "Recorded repayment"
    else -> "Transfer"
}

@Composable
fun EntryDetailScreen(state: BrowseState<EntryDetail>, hidden: Boolean, retry: () -> Unit,
                      openPerson: (String) -> Unit, openGroup: (String) -> Unit, onEdit: () -> Unit = {}, request: (AdvancedAction) -> Unit = {}) {
    var history by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    BrowseContent(state, retry) { entry ->
        val item = entry.item
        BrowseList(scroll) {
            item { DetailHeader(item.description ?: item.typeLabel(), item.typeLabel()) }
            item { LifecycleActions(EntityRef(if (item.kind == EntryKind.EXPENSE) EntityType.EXPENSE else EntityType.TRANSFER, item.entryId.value), entry.token, "transaction", item.deleted, null, request) }
            if (!item.deleted) item { FilledTonalButton(onClick = onEdit) { Text("Edit transaction") } }
            if (item.deleted) item { Notice("Deleted transaction", "This record is kept for history. It no longer affects current balances.") }
            item { IouCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Original amount", style = MaterialTheme.typography.bodyMedium)
                MoneyText(item.paid, hidden, style = MaterialTheme.typography.headlineMedium)
                InfoLine("Date", item.occurredOn.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)))
                InfoLine("Type", item.typeLabel())
                item.category?.let { InfoLine("Category", it.name.lowercase().replace('_', ' ')) }
                InfoLine(if (item.kind == EntryKind.EXPENSE) "Payer" else "Sender", item.paidBy.displayName)
                if (!item.paidBy.isMe) TextButton(onClick = { openPerson(item.paidBy.id.value) }) { Text("View payer") }
                item.receiver?.let { person ->
                    InfoLine("Recipient", person.displayName)
                    if (!person.isMe) TextButton(onClick = { openPerson(person.id.value) }) { Text("View recipient") }
                }
                if (item.group != null) TextButton(onClick = { openGroup(item.group.id.value) }) { Text("Group · ${item.group.name}") }
                else Text("Personal transaction · outside groups")
                (entry.editDraft as? io.github.codex07.iou.api.TransferDraft)?.let { draft ->
                    InfoLine("Recorded method", draft.method.name.lowercase().replaceFirstChar { it.uppercase() })
                    draft.accountLabel?.let { InfoLine("Account label", if (hidden) "Hidden" else it) }
                    draft.settlesCurrency?.let { InfoLine("Debt currency settled", it.code) }
                }
                item.note?.let { InfoLine("Notes", if (hidden) "Hidden" else it) }
            } } }
            if (entry.shares.isNotEmpty()) {
                item { BrowseHeading("Participants & stored shares") }
                items(entry.shares, key = { it.person.id.value }) { share ->
                    IouCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(share.person.displayName, style = MaterialTheme.typography.titleMedium)
                        MoneyText(share.share, hidden)
                        if (!share.person.isMe) TextButton(onClick = { openPerson(share.person.id.value) }) { Text("View person") }
                    } }
                }
            }
            item.myBalanceEffect?.let { effect ->
                item { BrowseHeading(if (item.group == null) "Change to your personal position" else "Change to your group position") }
                item { MoneyText(effect, hidden, signed = true) }
            }
            entry.fx?.let { fx ->
                item { BrowseHeading("Stored FX snapshot") }
                item { IouCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    InfoLine("Currency pair", "${fx.base.code} → ${fx.quote.code}")
                    InfoLine("Rate", if (hidden) "Hidden" else fx.rate)
                    InfoLine("Rate date", fx.rateDate.toString())
                    InfoLine("Source", "${fx.source}${if (fx.isStale) " · Stale rate" else ""}")
                    fx.paid?.let { Text("Bank original"); MoneyText(it, hidden) }
                    fx.charged?.let { Text("Bank charged"); MoneyText(it, hidden) }
                    Text("Ledger effect"); MoneyText(item.effect, hidden)
                } } }
            }
            item { HistoryToggle("Revision history", history, entry.history.size) { history = !history } }
            if (history) revisionRows(entry.history, hidden, { entry.restoreAction(it)?.let(request) }, { entry.restoreAction(it) != null })
            item { Notice("Recorded transaction", "You can edit this record. Delete, restore and historical-version recovery use current ledger rules.") }
        }
    }
}
