package io.github.codex07.iou.store

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
import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.api.MergePreview
import io.github.codex07.iou.api.Overview
import io.github.codex07.iou.api.PeopleApi
import io.github.codex07.iou.api.PersonDetail
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.PersonSummary
import io.github.codex07.iou.api.PlanItem
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.Settings
import io.github.codex07.iou.api.SettingsApi
import io.github.codex07.iou.api.SettleUpDraft
import io.github.codex07.iou.api.SetupApi
import io.github.codex07.iou.api.SetupState
import io.github.codex07.iou.api.SplitPreview
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.api.VersionDraft
import io.github.codex07.iou.api.map
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class StoreSetupApi(private val store: LedgerStore) : SetupApi {
    override fun observeSetupState(): Flow<SetupState> = store.views
        .map { views -> views.state.meOrNull?.let { SetupState.Ready(views.rawRef(it.id)) } ?: SetupState.NeedsSetup }
        .distinctUntilChanged()

    /** Creates me and sets the home currency in one write. */
    override suspend fun completeSetup(displayName: String, homeCurrency: CurrencyCode): LedgerResult<PersonId> =
        store.writeWith(beforeSetup = true) { snapshot, context ->
            PeopleOps.setup(context, displayName).map {
                StoreWrite(it.value, it.plan, settings = snapshot.settings.copy(homeCurrency = homeCurrency))
            }
        }
}

class StorePeopleApi(private val store: LedgerStore) : PeopleApi {
    override fun observePeople(includeArchived: Boolean, includeDeleted: Boolean): Flow<List<PersonSummary>> =
        store.read { it.people(includeArchived, includeDeleted) }

    override fun observePerson(id: PersonId): Flow<PersonDetail?> = store.read { it.person(id) }

    override suspend fun createPerson(draft: PersonDraft, basis: LedgerGeneration) =
        store.write { PeopleOps.createPerson(it, draft, basis) }

    override suspend fun updatePerson(id: PersonId, draft: PersonDraft, token: EditToken) =
        store.write { PeopleOps.updatePerson(it, id, draft, token) }

    override suspend fun setPersonArchived(id: PersonId, archived: Boolean, token: EditToken) =
        store.write { PeopleOps.setPersonArchived(it, id, archived, token) }

    override suspend fun deletePerson(id: PersonId, token: EditToken) = store.write { PeopleOps.deletePerson(it, id, token) }

    override suspend fun restorePerson(id: PersonId, token: EditToken) = store.write { PeopleOps.restorePerson(it, id, token) }

    override suspend fun addHandle(personId: PersonId, draft: HandleDraft, basis: LedgerGeneration): LedgerResult<HandleId> =
        store.write { PeopleOps.addHandle(it, personId, draft, basis) }

    override suspend fun updateHandle(id: HandleId, draft: HandleDraft, token: EditToken) =
        store.write { PeopleOps.updateHandle(it, id, draft, token) }

    override suspend fun deleteHandle(id: HandleId, token: EditToken) = store.write { PeopleOps.deleteHandle(it, id, token) }

    override suspend fun previewMerge(source: PersonId, target: PersonId): LedgerResult<MergePreview> =
        store.currentViews().mergePreview(source, target)

    override suspend fun merge(source: PersonId, target: PersonId, sourceToken: EditToken) =
        store.write { PeopleOps.merge(it, source, target, sourceToken) }

    override suspend fun unmerge(person: PersonId, token: EditToken) = store.write { PeopleOps.unmerge(it, person, token) }

    override suspend fun balanceSummaryText(id: PersonId): LedgerResult<String> = store.currentViews().balanceSummaryText(id)
}

class StoreGroupsApi(private val store: LedgerStore) : GroupsApi {
    override fun observeGroups(includeArchived: Boolean, includeDeleted: Boolean): Flow<List<GroupSummary>> =
        store.read { it.groups(includeArchived, includeDeleted) }

    override fun observeGroup(id: GroupId): Flow<GroupDetail?> = store.read { it.group(id) }

    override suspend fun createGroup(draft: GroupDraft, members: List<PersonId>, basis: LedgerGeneration): LedgerResult<GroupId> =
        store.write { GroupOps.createGroup(it, draft, members, basis) }

    override suspend fun updateGroup(id: GroupId, draft: GroupDraft, token: EditToken) =
        store.write { GroupOps.updateGroup(it, id, draft, token) }

    override suspend fun setGroupArchived(id: GroupId, archived: Boolean, token: EditToken) =
        store.write { GroupOps.setGroupArchived(it, id, archived, token) }

    override suspend fun deleteGroup(id: GroupId, token: EditToken) = store.write { GroupOps.deleteGroup(it, id, token) }

    override suspend fun restoreGroup(id: GroupId, token: EditToken) = store.write { GroupOps.restoreGroup(it, id, token) }

    override suspend fun addMember(groupId: GroupId, personId: PersonId, basis: LedgerGeneration) =
        store.write { GroupOps.addMember(it, groupId, personId, basis) }

    override suspend fun removeMember(groupId: GroupId, personId: PersonId, basis: LedgerGeneration) =
        store.write { GroupOps.removeMember(it, groupId, personId, basis) }
}

class StoreLedgerApi(private val store: LedgerStore) : LedgerApi {
    override fun observeOverview(): Flow<Overview> = store.read { it.overview() }

    override fun observeActivity(filter: ActivityFilter): Flow<List<ActivityItem>> =
        store.read { it.activity(filter) }

    override fun observeEntry(id: EntryId): Flow<EntryDetail?> = store.read { it.entry(id) }

    override suspend fun saveTransfer(draft: TransferDraft, mode: SaveMode): LedgerResult<EntryId> =
        store.write { EntryOps.saveTransfer(it, draft, mode) }

    override suspend fun saveExpense(draft: ExpenseDraft, mode: SaveMode): LedgerResult<EntryId> =
        store.write { EntryOps.saveExpense(it, draft, mode) }

    override suspend fun previewSplit(draft: ExpenseDraft, editing: EntryId?): LedgerResult<SplitPreview> =
        store.currentViews().splitPreview(draft, editing, store.now())

    override suspend fun settleUpFromPlan(item: PlanItem): LedgerResult<SettleUpDraft> =
        store.currentViews().settleUpFromPlan(item, store.today())

    override suspend fun settleUpWithPerson(id: PersonId, basis: LedgerGeneration): LedgerResult<List<SettleUpDraft>> =
        store.currentViews().settleUpWithPerson(id, basis, store.today())

    override suspend fun deleteEntry(id: EntryId, token: EditToken) = store.write { EntryOps.deleteEntry(it, id, token) }

    override suspend fun restoreEntry(id: EntryId, token: EditToken) = store.write { EntryOps.restoreEntry(it, id, token) }

    override suspend fun revertTo(entity: EntityRef, version: Int, token: EditToken) =
        store.writeWith { snapshot, context ->
            RevertOps.revertTo(context, entity, version, token, snapshot.revisions).map { StoreWrite(it.value, it.plan) }
        }

    override suspend fun draftFromVersion(entity: EntityRef, version: Int): LedgerResult<VersionDraft> =
        store.currentViews().draftFromVersion(entity, version)
}

/** Ledger settings live in the store (and its database); device-local ones in [device] (spec §3.3). */
class StoreSettingsApi(
    private val store: LedgerStore,
    private val device: DeviceSettingsStore,
    private val lock: LockGate,
) : SettingsApi {
    override fun observeSettings(): Flow<Settings> =
        combine(store.readSnapshot { it.settings }, device.settings) { ledger, local ->
            Settings(ledger.homeCurrency, local.appLockEnabled, local.lockTimeout, local.screenProtection, ledger.backupEncryptionDefault)
        }.distinctUntilChanged()

    override suspend fun setHomeCurrency(currency: CurrencyCode) = ok { store.updateSettings { it.copy(homeCurrency = currency) } }

    override suspend fun setAppLockEnabled(enabled: Boolean) = ok {
        try {
            device.update { it.copy(appLockEnabled = enabled) }
        } finally {
            lock.lockEnabledChanged(device.settings.value.appLockEnabled)
        }
    }

    override suspend fun setLockTimeout(timeout: LockTimeout) = ok { device.update { it.copy(lockTimeout = timeout) } }

    override suspend fun setScreenProtection(enabled: Boolean) = ok { device.update { it.copy(screenProtection = enabled) } }

    override suspend fun setBackupEncryptionDefault(enabled: Boolean) =
        ok { store.updateSettings { it.copy(backupEncryptionDefault = enabled) } }

    override fun observeLockRequired(): Flow<Boolean> = lock.required

    override fun markUnlocked() = lock.unlocked()

    private suspend fun ok(block: suspend () -> Unit): LedgerResult<Unit> {
        block()
        return LedgerResult.Ok(Unit)
    }
}
