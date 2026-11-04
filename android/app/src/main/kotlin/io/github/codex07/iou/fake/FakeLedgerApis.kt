package io.github.codex07.iou.fake

import io.github.codex07.iou.api.ActivityFilter
import io.github.codex07.iou.api.ActivityItem
import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.EntryDetail
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.GroupDetail
import io.github.codex07.iou.api.GroupDraft
import io.github.codex07.iou.api.GroupSummary
import io.github.codex07.iou.api.GroupsApi
import io.github.codex07.iou.api.HandleDraft
import io.github.codex07.iou.api.LedgerApi
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.MergePreview
import io.github.codex07.iou.api.Overview
import io.github.codex07.iou.api.PeopleApi
import io.github.codex07.iou.api.PersonDetail
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.PersonSummary
import io.github.codex07.iou.api.PlanItem
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.SettleUpDraft
import io.github.codex07.iou.api.SetupApi
import io.github.codex07.iou.api.SetupState
import io.github.codex07.iou.api.SplitPreview
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.api.VersionDraft
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.ops.EntryOps
import io.github.codex07.iou.ledger.ops.GroupOps
import io.github.codex07.iou.ledger.ops.PeopleOps
import io.github.codex07.iou.ledger.ops.RevertOps
import io.github.codex07.iou.ledger.views.activity
import io.github.codex07.iou.ledger.views.balanceSummaryText
import io.github.codex07.iou.ledger.views.draftFromVersion
import io.github.codex07.iou.ledger.views.entry
import io.github.codex07.iou.ledger.views.settleUpFromPlan
import io.github.codex07.iou.ledger.views.settleUpWithPerson
import io.github.codex07.iou.ledger.views.splitPreview
import io.github.codex07.iou.money.CurrencyCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class FakeSetupApi(private val ledger: InMemoryLedger) : SetupApi {
    override fun observeSetupState(): Flow<SetupState> = ledger.views
        .map { views -> views.state.meOrNull?.let { SetupState.Ready(views.rawRef(it.id)) } ?: SetupState.NeedsSetup }
        .distinctUntilChanged()

    override suspend fun completeSetup(displayName: String, homeCurrency: CurrencyCode): LedgerResult<PersonId> {
        val result = ledger.write(beforeSetup = true) { PeopleOps.setup(it, displayName) }
        if (result is LedgerResult.Ok) ledger.update { it.copy(settings = it.settings.copy(homeCurrency = homeCurrency)) }
        return result
    }
}

class FakePeopleApi(private val ledger: InMemoryLedger) : PeopleApi {
    override fun observePeople(includeArchived: Boolean, includeDeleted: Boolean): Flow<List<PersonSummary>> =
        ledger.read { it.people(includeArchived, includeDeleted) }

    override fun observePerson(id: PersonId): Flow<PersonDetail?> = ledger.read { it.person(id) }

    override suspend fun createPerson(draft: PersonDraft, basis: LedgerGeneration) =
        ledger.write { PeopleOps.createPerson(it, draft, basis) }

    override suspend fun updatePerson(id: PersonId, draft: PersonDraft, token: EditToken) =
        ledger.write { PeopleOps.updatePerson(it, id, draft, token) }

    override suspend fun setPersonArchived(id: PersonId, archived: Boolean, token: EditToken) =
        ledger.write { PeopleOps.setPersonArchived(it, id, archived, token) }

    override suspend fun deletePerson(id: PersonId, token: EditToken) = ledger.write { PeopleOps.deletePerson(it, id, token) }

    override suspend fun restorePerson(id: PersonId, token: EditToken) = ledger.write { PeopleOps.restorePerson(it, id, token) }

    override suspend fun addHandle(personId: PersonId, draft: HandleDraft, basis: LedgerGeneration): LedgerResult<HandleId> =
        ledger.write { PeopleOps.addHandle(it, personId, draft, basis) }

    override suspend fun updateHandle(id: HandleId, draft: HandleDraft, token: EditToken) =
        ledger.write { PeopleOps.updateHandle(it, id, draft, token) }

    override suspend fun deleteHandle(id: HandleId, token: EditToken) = ledger.write { PeopleOps.deleteHandle(it, id, token) }

    override suspend fun previewMerge(source: PersonId, target: PersonId): LedgerResult<MergePreview> =
        ledger.currentViews().mergePreview(source, target)

    override suspend fun merge(source: PersonId, target: PersonId, sourceToken: EditToken) =
        ledger.write { PeopleOps.merge(it, source, target, sourceToken) }

    override suspend fun unmerge(person: PersonId, token: EditToken) = ledger.write { PeopleOps.unmerge(it, person, token) }

    override suspend fun balanceSummaryText(id: PersonId): LedgerResult<String> = ledger.currentViews().balanceSummaryText(id)
}

class FakeGroupsApi(private val ledger: InMemoryLedger) : GroupsApi {
    override fun observeGroups(includeArchived: Boolean, includeDeleted: Boolean): Flow<List<GroupSummary>> =
        ledger.read { it.groups(includeArchived, includeDeleted) }

    override fun observeGroup(id: GroupId): Flow<GroupDetail?> = ledger.read { it.group(id) }

    override suspend fun createGroup(draft: GroupDraft, members: List<PersonId>, basis: LedgerGeneration): LedgerResult<GroupId> =
        ledger.write { GroupOps.createGroup(it, draft, members, basis) }

    override suspend fun updateGroup(id: GroupId, draft: GroupDraft, token: EditToken) =
        ledger.write { GroupOps.updateGroup(it, id, draft, token) }

    override suspend fun setGroupArchived(id: GroupId, archived: Boolean, token: EditToken) =
        ledger.write { GroupOps.setGroupArchived(it, id, archived, token) }

    override suspend fun deleteGroup(id: GroupId, token: EditToken) = ledger.write { GroupOps.deleteGroup(it, id, token) }

    override suspend fun restoreGroup(id: GroupId, token: EditToken) = ledger.write { GroupOps.restoreGroup(it, id, token) }

    override suspend fun addMember(groupId: GroupId, personId: PersonId, basis: LedgerGeneration) =
        ledger.write { GroupOps.addMember(it, groupId, personId, basis) }

    override suspend fun removeMember(groupId: GroupId, personId: PersonId, basis: LedgerGeneration) =
        ledger.write { GroupOps.removeMember(it, groupId, personId, basis) }
}

class FakeLedgerApi(private val ledger: InMemoryLedger) : LedgerApi {
    override fun observeOverview(): Flow<Overview> = ledger.read { it.overview() }

    override fun observeActivity(filter: ActivityFilter): Flow<List<ActivityItem>> =
        ledger.read { it.activity(filter) }

    override fun observeEntry(id: EntryId): Flow<EntryDetail?> = ledger.read { it.entry(id) }

    override suspend fun saveTransfer(draft: TransferDraft, mode: SaveMode): LedgerResult<EntryId> =
        ledger.write { EntryOps.saveTransfer(it, draft, mode) }

    override suspend fun saveExpense(draft: ExpenseDraft, mode: SaveMode): LedgerResult<EntryId> =
        ledger.write { EntryOps.saveExpense(it, draft, mode) }

    override suspend fun previewSplit(draft: ExpenseDraft, editing: EntryId?): LedgerResult<SplitPreview> =
        ledger.currentViews().splitPreview(draft, editing, ledger.now())

    override suspend fun settleUpFromPlan(item: PlanItem): LedgerResult<SettleUpDraft> =
        ledger.currentViews().settleUpFromPlan(item, ledger.today())

    override suspend fun settleUpWithPerson(id: PersonId, basis: LedgerGeneration): LedgerResult<List<SettleUpDraft>> =
        ledger.currentViews().settleUpWithPerson(id, basis, ledger.today())

    override suspend fun deleteEntry(id: EntryId, token: EditToken) = ledger.write { EntryOps.deleteEntry(it, id, token) }

    override suspend fun restoreEntry(id: EntryId, token: EditToken) = ledger.write { EntryOps.restoreEntry(it, id, token) }

    override suspend fun revertTo(entity: EntityRef, version: Int, token: EditToken) =
        ledger.write { RevertOps.revertTo(it, entity, version, token, ledger.snapshot.value.revisions) }

    override suspend fun draftFromVersion(entity: EntityRef, version: Int): LedgerResult<VersionDraft> =
        ledger.currentViews().draftFromVersion(entity, version)
}
