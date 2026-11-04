package io.github.codex07.iou.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.codex07.iou.api.*
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.ui.components.*
import io.github.codex07.iou.ui.viewmodel.BrowseState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun <T> BrowseContent(state: BrowseState<T>, retry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        BrowseState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        BrowseState.Failed -> Box(Modifier.padding(20.dp)) {
            EmptyState("Unable to load", "Your records could not be loaded. Try again.", "Retry", IouIcons.Info, retry)
        }
        BrowseState.Missing -> Box(Modifier.padding(20.dp)) {
            Notice("Record unavailable", "This record no longer exists in the current ledger. Use Back to return.")
        }
        is BrowseState.Ready -> content(state.value)
    }
}

@Composable
fun BrowseList(state: LazyListState, content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), state = state, content = content)
}

@Composable
fun BrowseHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

@Composable
fun BrowseSearch(query: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(query, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        shape = MaterialTheme.shapes.medium, trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { change("") }) { Icon(IouIcons.Close, "Clear search") }
        })
}

@Composable
fun BrowseChips(options: List<String>, selected: String, change: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { option -> FilterChip(selected == option, { change(option) }, label = { Text(option) }) }
    }
}

@Composable
fun DetailHeader(name: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().animateContentSize().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar(name, Modifier.size(64.dp))
        Text(name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BalancePanel(amounts: List<Money>, hidden: Boolean, group: Boolean = false) {
    IouCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (amounts.isEmpty()) Text("All settled", color = MaterialTheme.colorScheme.onSurfaceVariant)
            amounts.forEach { money ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val label = when {
                        money.amountMinor == 0L -> "Settled"
                        group && money.amountMinor > 0L -> "Gets back from group"
                        group -> "Owes the group"
                        money.amountMinor > 0L -> "Owes you"
                        else -> "You owe"
                    }
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    MoneyText(money, hidden, magnitude = true,
                        color = if (money.amountMinor < 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

fun LazyListScope.activityRows(entries: List<ActivityItem>, hidden: Boolean, open: (String) -> Unit) {
    if (entries.isEmpty()) item { Notice("No activity", "There are no records to display in this view.") }
    entries.groupBy { it.occurredOn }.forEach { (date, rows) ->
        item { BrowseHeading(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))) }
        items(rows, key = { "entry:${it.entryId.value}" }) { entry ->
            IouCard { Column {
                if (entry.deleted) Text("Deleted · history kept", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                EntryPreview(entry, hidden) { open(entry.entryId.value) }
            } }
        }
    }
}

fun LazyListScope.revisionRows(history: List<RevisionView>, hidden: Boolean) {
    if (history.isEmpty()) item { Text("No revision history available.") }
    items(history) { revision ->
        IouCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Version ${revision.version} · ${revision.action.name.lowercase().replaceFirstChar { it.uppercase() }}",
                style = MaterialTheme.typography.titleMedium)
            Text(revision.subject)
            Text(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(revision.at),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("By ${revision.author.displayName}", style = MaterialTheme.typography.bodySmall)
            revision.reason?.let { Text(if (hidden) "Reason hidden" else it) }
            revision.changes.forEach { change ->
                Text(change.field, style = MaterialTheme.typography.labelLarge)
                // Diffs are opaque backend display strings and can contain amounts inside any field.
                Text(if (hidden) "Values hidden" else "${change.before ?: "—"} → ${change.after ?: "—"}")
            }
        } }
    }
}
