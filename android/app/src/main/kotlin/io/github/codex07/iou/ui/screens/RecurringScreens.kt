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
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.ui.components.*
import io.github.codex07.iou.ui.forms.userMessage
import io.github.codex07.iou.ui.viewmodel.*

@Composable
fun ScheduleListScreen(
    kind: ScheduleList, state: BrowseState<List<ScheduleSummary>>, hidden: Boolean, retry: () -> Unit,
    open: (RecurringId) -> Unit, create: () -> Unit, due: () -> Unit, held: () -> Unit, deleted: () -> Unit,
) {
    BrowseContent(state, retry) { schedules ->
        BrowseList(rememberLazyListState()) {
            if (kind == ScheduleList.ACTIVE) item {
                Button(onClick = create) { Text("New schedule") }
                TextButton(onClick = due) { Text("Due expenses") }
                TextButton(onClick = held) { Text("On hold") }
                TextButton(onClick = deleted) { Text("Recently deleted") }
            }
            if (schedules.isEmpty()) item {
                Text(when (kind) {
                    ScheduleList.ACTIVE -> "No active recurring schedules."
                    ScheduleList.HELD -> "No schedules are on hold."
                    ScheduleList.DELETED -> "No deleted schedules."
                })
            }
            items(schedules, key = { it.id.value }) { schedule ->
                IouCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ScheduleSummaryContent(schedule, hidden)
                        TextButton(onClick = { open(schedule.id) }) { Text("Open schedule") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleSummaryContent(schedule: ScheduleSummary, hidden: Boolean) {
    BrowseHeading(schedule.description)
    MoneyText(schedule.total, hidden)
    schedule.group?.let { InfoLine("Group", it.name) }
    val period = schedule.unit.name.lowercase()
    Text(if (schedule.interval == 1) "Every $period" else "Every ${schedule.interval} ${period}s")
    InfoLine("Next occurrence", schedule.next?.toString() ?: "No further occurrences")
    InfoLine("Posting", if (schedule.autoPost) "Automatic" else "Confirm each occurrence")
    InfoLine("Due", schedule.dueCount.toString())
    schedule.holds.forEach { hold ->
        Text(when (hold) {
            is HoldReason.GroupArchived -> "On hold because ${hold.group.name} is archived."
            is HoldReason.PersonArchived -> "On hold because ${hold.person.displayName} is archived."
        })
    }
}

@Composable
private fun ConfirmToResume() {
    Notice("Confirm these to resume auto-posting.", "Post or skip the older unhandled occurrences before this schedule can post automatically again.")
}

@Composable
fun ScheduleDetailScreen(
    state: BrowseState<ScheduleDetail>, hidden: Boolean, retry: () -> Unit,
    edit: () -> Unit, openDue: () -> Unit, openEntry: (String) -> Unit, request: (AdvancedAction) -> Unit,
) {
    BrowseContent(state, retry) { detail ->
        val summary = detail.summary
        val entity = EntityRef(EntityType.RECURRING_EXPENSE, summary.id.value)
        var history by rememberSaveable { mutableStateOf(false) }
        BrowseList(rememberLazyListState()) {
            item {
                IouCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScheduleSummaryContent(summary, hidden)
                        InfoLine("Last handled", detail.handledThrough?.toString() ?: "None yet")
                        detail.editDraft.category?.let { InfoLine("Category", it.name.lowercase()) }
                        detail.editDraft.note?.let { InfoLine("Note", if (hidden) "Hidden" else it) }
                        if (summary.deleted) Text("This schedule is deleted.")
                        if (!summary.deleted && summary.holds.isNotEmpty()) Text("Posting resumes when the archived references are active again.")
                    }
                }
            }
            item {
                LifecycleActions(entity, detail.token, summary.description, summary.deleted, null, request)
                if (!summary.deleted) FilledTonalButton(onClick = edit) { Text("Edit schedule") }
            }
            if (detail.confirmToResume) item { ConfirmToResume() }
            item {
                BrowseHeading("Due occurrences")
                if (detail.due.isEmpty()) Text("No occurrences due.")
                else TextButton(onClick = openDue) { Text("Review due expenses") }
            }
            items(detail.due, key = { "due:$it" }) { Text(it.toString()) }
            item { BrowseHeading("Posted expenses") }
            if (detail.posted.isEmpty()) item { Text("No expenses posted yet.") }
            else activityRows(detail.posted, hidden, openEntry)
            item { TextButton(onClick = { history = !history }) { Text(if (history) "Hide history" else "Show history") } }
            if (history) revisionRows(detail.history, hidden,
                onRestore = { revision ->
                    revision.token?.let { request(AdvancedAction.Revision(revision, it, editable = !summary.deleted)) }
                },
                canRestore = { it.entity == entity && it.token != null })
        }
    }
}

@Composable
fun DueScreen(
    state: BrowseState<List<DueOccurrence>>, action: DueAction, hidden: Boolean, retry: () -> Unit,
    open: (RecurringId) -> Unit, post: (DueOccurrence) -> Unit, skip: (DueOccurrence) -> Unit,
    confirmSkip: (java.time.LocalDate) -> Unit, dismiss: () -> Unit,
    focus: ScheduleSummary?, showAll: () -> Unit,
) {
    BrowseContent(state, retry) { allDue ->
        val due = if (focus == null) allDue else allDue.filter { it.schedule == focus.id }
        val oldest = due.distinctBy { it.schedule }.associateBy { it.schedule }
        BrowseList(rememberLazyListState()) {
            if (focus != null) item {
                BrowseHeading(focus.description)
                TextButton(onClick = { open(focus.id) }) { Text("Open schedule details") }
                TextButton(onClick = showAll) { Text("Show all due expenses") }
            }
            if (action.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            action.error?.let { error -> item { Notice("Check the due list", error.userMessage(hidden)) } }
            action.message?.let { message -> item { Text(message) } }
            if (due.isEmpty()) item { Text(if (focus == null) "No recurring expenses due." else "No expenses due for this schedule.") }
            items(due, key = { "${it.schedule.value}:${it.date}" }) { occurrence ->
                val first = oldest[occurrence.schedule]?.date == occurrence.date
                IouCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrowseHeading(occurrence.description)
                        InfoLine("Occurrence", occurrence.date.toString())
                        occurrence.group?.let { InfoLine("Group", it.name) }
                        MoneyText(occurrence.amount, hidden)
                        if (first && occurrence.confirmToResume) ConfirmToResume()
                        occurrence.autoPostError?.let { Notice("Auto-post needs attention", it.userMessage(hidden, occurrence.amount.currency)) }
                        TextButton(onClick = { open(occurrence.schedule) }, enabled = !action.busy) { Text("Open schedule") }
                        if (first) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { post(occurrence) }, enabled = !action.busy) { Text("Post") }
                            OutlinedButton(onClick = { skip(occurrence) }, enabled = !action.busy) { Text("Skip…") }
                        } else Text("Handle ${oldest[occurrence.schedule]?.date} first, or skip through this date.")
                    }
                }
            }
        }
    }
    action.review?.let { review ->
        var through by rememberSaveable(review.schedule.value, review.token.version) { mutableStateOf(review.dates.first().toString()) }
        val selected = review.dates.firstOrNull { it.toString() == through } ?: review.dates.first()
        val count = review.dates.count { it <= selected }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Skip recurring occurrences?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceField("Skip through (inclusive)", through, review.dates.map { it.toString() to it.toString() },
                        { through = it }, !action.busy)
                    Text("Skip $count from ${review.dates.first()} through $through. No expenses will be posted for these dates.")
                }
            },
            confirmButton = { TextButton(onClick = { confirmSkip(selected) }, enabled = !action.busy) { Text("Confirm skip") } },
            dismissButton = { TextButton(onClick = dismiss, enabled = !action.busy) { Text("Cancel") } },
        )
    }
}
