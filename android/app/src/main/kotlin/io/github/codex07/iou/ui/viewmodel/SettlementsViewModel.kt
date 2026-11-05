package io.github.codex07.iou.ui.viewmodel

import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.codex07.iou.api.*
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

data class PersonalSettlement(val person: PersonRef, val draft: SettleUpDraft)
data class SettlementsPage(val personal: List<PersonalSettlement>, val groups: List<GroupDetail>)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettlementsViewModel @Inject constructor(people: PeopleApi, groups: GroupsApi, ledger: LedgerApi, backup: BackupApi) : BrowseViewModel<SettlementsPage>() {
    val state = observe {
        val groupDetails = groups.observeGroups(includeArchived = true).flatMapLatest { rows ->
            if (rows.isEmpty()) flowOf(emptyList()) else combine(rows.map { groups.observeGroup(it.group.id) }) { it.filterNotNull() }
        }
        combine(people.observePeople(includeArchived = true), groupDetails, backup.observeLedgerGeneration()) { contacts, teams, generation ->
            Triple(contacts, teams, generation)
        }.mapLatest { (contacts, teams, generation) ->
            val drafts = contacts.flatMap { person ->
                when (val result = ledger.settleUpWithPerson(person.person.id, generation)) {
                    is LedgerResult.Ok -> result.value.map { PersonalSettlement(person.person, it) }
                    is LedgerResult.Err -> error("Settlement reads unavailable")
                }
            }
            SettlementsPage(drafts, teams)
        }
    }
}
