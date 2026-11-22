package io.github.codex07.iou.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.codex07.iou.api.ActivityItem
import io.github.codex07.iou.api.BackupApi
import io.github.codex07.iou.api.BackupReminder
import io.github.codex07.iou.api.FxApi
import io.github.codex07.iou.api.GroupSummary
import io.github.codex07.iou.api.GroupsApi
import io.github.codex07.iou.api.LedgerApi
import io.github.codex07.iou.api.Overview
import io.github.codex07.iou.api.PeopleApi
import io.github.codex07.iou.api.PersonSummary
import io.github.codex07.iou.api.SetupApi
import io.github.codex07.iou.api.SetupState
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface OverviewUiState {
    data object Loading : OverviewUiState
    data object NeedsSetup : OverviewUiState
    data object Failed : OverviewUiState
    data class Ready(
        val overview: Overview,
        val displayName: String,
        val people: List<PersonSummary>,
        val groups: List<GroupSummary>,
        val recent: List<ActivityItem>,
        val backupReminder: BackupReminder,
        val hasArchivedOpenBalances: Boolean,
    ) : OverviewUiState
}

/** All financial values are core read models. Only presentation filtering/preview limits live here. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val ledger: LedgerApi,
    private val people: PeopleApi,
    private val groups: GroupsApi,
    private val setup: SetupApi,
    private val backup: BackupApi,
    private val fx: FxApi,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    val state: StateFlow<OverviewUiState> = refresh.flatMapLatest {
        dashboard().onStart { emit(OverviewUiState.Loading) }
            .catch { emit(OverviewUiState.Failed) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewUiState.Loading)

    fun retry() { refresh.update { it + 1 } }

    private val pulling = MutableStateFlow(false)

    /** True while a pull-to-refresh runs. */
    val refreshing: StateFlow<Boolean> = pulling.asStateFlow()

    /**
     * Pull-to-refresh: fetches the latest exchange rates now (approximate totals update through the read models).
     * Manual sync joins here later. [done] gets false when nothing could be fetched; a pull while one runs is ignored.
     */
    fun pullToRefresh(done: (Boolean) -> Unit = {}) {
        if (!pulling.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            val fetched = try { fx.refreshRates() } finally { pulling.value = false }
            done(fetched)
        }
    }

    private data class Records(
        val people: List<PersonSummary>, val groups: List<GroupSummary>, val activity: List<ActivityItem>,
    )

    private fun dashboard(): Flow<OverviewUiState> = setup.observeSetupState().flatMapLatest { setupState ->
        if (setupState !is SetupState.Ready) return@flatMapLatest flowOf(OverviewUiState.NeedsSetup)
        val records = combine(
            people.observePeople(includeArchived = true),
            groups.observeGroups(includeArchived = true),
            ledger.observeActivity(),
        ) { people, groups, activity -> Records(people, groups, activity) }
        combine(ledger.observeOverview(), records, backup.observeBackupReminder()) { overview, records, reminder ->
            val visiblePeople = records.people.filter { person ->
                !person.deleted && (!person.archived || person.balances.any { it.amountMinor != 0L })
            }.sortedBy { person -> person.balances.none { it.amountMinor != 0L } }
            val visibleGroups = records.groups.filter { group ->
                !group.deleted && (!group.archived || group.myNets.any { it.amountMinor != 0L })
            }
            OverviewUiState.Ready(
                overview = overview,
                displayName = setupState.me.displayName,
                people = visiblePeople.take(4), groups = visibleGroups,
                recent = records.activity.filterNot { it.deleted }.take(5),
                backupReminder = reminder,
                hasArchivedOpenBalances = visiblePeople.any { it.archived } || visibleGroups.any { it.archived },
            ) as OverviewUiState
        }
    }
}
