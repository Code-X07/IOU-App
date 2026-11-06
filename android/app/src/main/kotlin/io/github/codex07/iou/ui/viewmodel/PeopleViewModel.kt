package io.github.codex07.iou.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.*
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

sealed interface BrowseState<out T> {
    data object Loading : BrowseState<Nothing>
    data object Failed : BrowseState<Nothing>
    data object Missing : BrowseState<Nothing>
    data class Ready<T>(val value: T) : BrowseState<T>
}

/** Read-only observation with retry. Null is a missing record, never an endless loading state. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BrowseViewModel<T> : ViewModel() {
    private val refresh = MutableStateFlow(0)
    protected fun observe(source: () -> Flow<T?>): StateFlow<BrowseState<T>> = refresh.flatMapLatest {
        source().map<T?, BrowseState<T>> { value ->
            if (value == null) BrowseState.Missing else BrowseState.Ready(value)
        }.onStart { emit(BrowseState.Loading) }.catch { emit(BrowseState.Failed) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseState.Loading)
    fun retry() { refresh.update { it + 1 } }
}

@HiltViewModel
class PeopleViewModel @Inject constructor(api: PeopleApi) : BrowseViewModel<List<PersonSummary>>() {
    val state = observe { api.observePeople(includeArchived = true, includeDeleted = true) }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PersonDetailViewModel @Inject constructor(api: PeopleApi, groups: GroupsApi) : BrowseViewModel<PersonPage>() {
    private val id = MutableStateFlow<PersonId?>(null)
    val state = observe { id.filterNotNull().flatMapLatest { personId ->
        groups.observeGroups(includeArchived = true).flatMapLatest { summaries ->
            val memberships: Flow<List<GroupDetail?>> = if (summaries.isEmpty()) flowOf(emptyList())
                else combine(summaries.map { groups.observeGroup(it.group.id) }) { it.toList() }
            combine(api.observePerson(personId), memberships) { person, details ->
                person?.let { contact -> PersonPage(contact, summaries.filter { summary ->
                    details.any { detail -> detail?.group?.id == summary.group.id &&
                        detail.members.any { it.person.id == contact.person.id } }
                }) }
            }
        }
    } }
    fun open(value: String) { id.value = PersonId(value) }
}

data class PersonPage(val detail: PersonDetail, val relatedGroups: List<GroupSummary>)

@HiltViewModel
class BrowseSessionViewModel @Inject constructor(backup: BackupApi) : ViewModel() {
    val generation = backup.observeLedgerGeneration().stateIn(viewModelScope,
        SharingStarted.WhileSubscribed(5_000), null)
}
