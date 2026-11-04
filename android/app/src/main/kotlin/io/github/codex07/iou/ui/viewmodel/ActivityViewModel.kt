package io.github.codex07.iou.ui.viewmodel

import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.*
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.time.LocalDate

/** EntryKind is a backend query; loan/repayment subtypes and text search are presentation filters. */
enum class ActivityType(val label: String) {
    ALL("All"), EXPENSE("Shared expenses"), LOAN("Personal loans"), TRANSFER("Transfers"), REPAYMENT("Recorded repayments")
}
data class ActivityQuery(
    val text: String = "", val type: ActivityType = ActivityType.ALL,
    val person: PersonId? = null, val group: GroupId? = null,
    val days: Long? = null, val deleted: Boolean = false,
) {
    fun backendFilter(today: LocalDate = LocalDate.now()) = ActivityFilter(
        personId = person, groupId = group, from = days?.let { today.minusDays(it - 1) },
        to = if (days != null) today else null,
        kinds = when (type) {
            ActivityType.ALL -> EntryKind.entries.toSet()
            ActivityType.EXPENSE -> setOf(EntryKind.EXPENSE)
            else -> setOf(EntryKind.TRANSFER)
        }, includeDeleted = deleted,
    )
    fun matches(item: ActivityItem): Boolean {
        if (deleted != item.deleted) return false
        if (type == ActivityType.LOAN && item.transferKind != TransferKind.LOAN) return false
        if (type == ActivityType.REPAYMENT && item.transferKind != TransferKind.REPAYMENT) return false
        val q = text.trim()
        return q.isEmpty() || listOfNotNull(item.description, item.note, item.paidBy.displayName,
            item.receiver?.displayName, item.group?.name, item.category?.name, "${item.kind}",
            item.transferKind?.name).plus(item.participants.map { it.displayName }).any { it.contains(q, true) }
    }
}
data class ActivityPage(val entries: List<ActivityItem>, val people: List<PersonSummary>, val groups: List<GroupSummary>)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ActivityViewModel @Inject constructor(ledger: LedgerApi, people: PeopleApi, groups: GroupsApi) : BrowseViewModel<ActivityPage>() {
    private val query = MutableStateFlow(ActivityQuery())
    val state = observe {
        query.flatMapLatest { filter ->
            combine(ledger.observeActivity(filter.backendFilter()),
                people.observePeople(includeArchived = true, includeDeleted = true),
                groups.observeGroups(includeArchived = true, includeDeleted = true)) { entries, contacts, teams ->
                ActivityPage(entries.filter(filter::matches), contacts, teams)
            }
        }
    }
    fun filter(value: ActivityQuery) { query.value = value }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EntryDetailViewModel @Inject constructor(api: LedgerApi) : BrowseViewModel<EntryDetail>() {
    private val id = MutableStateFlow<EntryId?>(null)
    val state = observe { id.filterNotNull().flatMapLatest(api::observeEntry) }
    fun open(value: String) { id.value = EntryId(value) }
}
