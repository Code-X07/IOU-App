package io.github.codex07.iou.ui.viewmodel

import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.ui.forms.SafeFormViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*

enum class MetadataKind { PERSON, HANDLE, GROUP }
data class MetadataFields(
    val name: String = "", val note: String = "", val currency: String = "",
    val fxMode: FxMode = FxMode.CONVERT, val members: Set<PersonId> = emptySet(),
    val handleType: HandleType = HandleType.IBAN, val value: String = "", val label: String = "",
    val holder: String = "", val bic: String = "", val defaultHandle: Boolean = false,
)
data class MetadataState(
    val kind: MetadataKind = MetadataKind.PERSON, val fields: MetadataFields = MetadataFields(),
    val people: List<PersonSummary> = emptyList(), val locked: Boolean = false, val editing: Boolean = false, val fromHistory: Boolean = false,
)

@HiltViewModel
class MetadataFormViewModel @Inject constructor(
    private val people: PeopleApi, private val groups: GroupsApi, private val ledger: LedgerApi, backup: BackupApi,
) : SafeFormViewModel(backup) {
    private val mutableState = MutableStateFlow(MetadataState())
    val state = mutableState.asStateFlow()
    private var initial = MetadataFields()
    private var token: EditToken? = null
    private var entityId: String? = null
    private var ownerId: String? = null
    private var preset = emptySet<PersonId>()

    fun open(kind: MetadataKind, id: String? = null, owner: String? = null, members: Set<PersonId> = emptySet()) {
        initialize { load(kind, id, owner, members) }
    }
    fun openRecovery(recovery: RecoveryDraft) = initialize {
        val kind = when (recovery.draft) { is VersionDraft.OfPerson -> MetadataKind.PERSON; is VersionDraft.OfHandle -> MetadataKind.HANDLE; is VersionDraft.OfGroup -> MetadataKind.GROUP; else -> { fail(LedgerError.InvalidField("entity")); return@initialize } }
        requireGeneration(recovery.token); if (status.value.invalidated) return@initialize
        load(kind, recovery.entity.id, recovery.owner?.value, emptySet())
        if (status.value.error != null) return@initialize
        entityId = recovery.entity.id; token = recovery.token; requireGeneration(recovery.token)
        val fields = when (val draft = recovery.draft) {
            is VersionDraft.OfPerson -> state.value.fields.copy(name = draft.draft.displayName, note = draft.draft.note.orEmpty(), currency = draft.draft.defaultCurrency?.code.orEmpty())
            is VersionDraft.OfGroup -> state.value.fields.copy(name = draft.draft.name, currency = draft.draft.defaultCurrency.code, fxMode = draft.draft.fxMode)
            is VersionDraft.OfHandle -> draft.draft.let { state.value.fields.copy(handleType = it.type, value = it.value, label = it.label, holder = it.holderName.orEmpty(), bic = it.bic.orEmpty(), defaultHandle = it.isDefault) }
            else -> state.value.fields
        }
        mutableState.update { it.copy(fields = fields, fromHistory = true) }
        if (!status.value.invalidated) changed(true)
    }
    fun useCurrentCurrencySettings() = change { it.copy(currency = initial.currency, fxMode = initial.fxMode) }
    private suspend fun load(kind: MetadataKind, id: String?, owner: String?, members: Set<PersonId>) {
        entityId = id; ownerId = owner; preset = members; token = null
        var fields = MetadataFields(currency = if (kind == MetadataKind.GROUP) ledger.observeOverview().first().homeCurrency.code else "", members = members)
        var locked = false
        when (kind) {
            MetadataKind.PERSON -> if (id != null) {
                val detail = people.observePerson(PersonId(id)).first()
                if (detail == null || detail.deleted) { fail(LedgerError.NotFound(id)); return }
                entityId = detail.person.id.value
                token = detail.token
                fields = fields.copy(name = detail.person.displayName, note = detail.note.orEmpty(), currency = detail.defaultCurrency?.code.orEmpty())
            }
            MetadataKind.GROUP -> if (id != null) {
                val detail = groups.observeGroup(GroupId(id)).first()
                if (detail == null || detail.deleted) { fail(LedgerError.NotFound(id)); return }
                token = detail.token; locked = detail.settingsLocked
                fields = fields.copy(name = detail.group.name, currency = detail.defaultCurrency.code, fxMode = detail.fxMode,
                    members = detail.members.filter { it.active && !it.person.isMe }.map { it.person.id }.toSet())
            }
            MetadataKind.HANDLE -> {
                val detail = owner?.let { people.observePerson(PersonId(it)).first() }
                if (detail == null || detail.deleted) { fail(LedgerError.NotFound(owner.orEmpty())); return }
                ownerId = detail.person.id.value
                if (id != null) {
                    val handle = detail.handles.firstOrNull { it.id.value == id }
                    if (handle == null) { fail(LedgerError.NotFound(id)); return }
                    token = handle.token
                    // Preserve the backend's canonical default flag and the alias handle's own edit token.
                    fields = fields.copy(handleType = handle.type, value = handle.value, label = handle.label,
                        holder = handle.holderName.orEmpty(), bic = handle.bic.orEmpty(), defaultHandle = handle.isDefault)
                }
            }
        }
        token?.let(::requireGeneration)
        initial = fields
        mutableState.value = MetadataState(kind, fields, people.observePeople(includeArchived = true).first(), locked, id != null)
    }
    fun change(update: (MetadataFields) -> MetadataFields) {
        if (!editable) return
        val next = update(state.value.fields)
        mutableState.update { it.copy(fields = next) }; changed(next != initial)
    }
    fun reviewLatest() { reopen { load(state.value.kind, entityId, ownerId, preset) } }
    fun save() = submit {
        val page = state.value; val fields = page.fields
        ledgerResult {
            val currency = if (fields.currency.isBlank() && page.kind != MetadataKind.GROUP) null else Inputs.currency(fields.currency).bind()
            when (page.kind) {
                MetadataKind.PERSON -> {
                    ensure(fields.name.isNotBlank()) { LedgerError.InvalidField("displayName") }
                    val draft = PersonDraft(fields.name.trim(), fields.note.takeIf { it.isNotBlank() }, currency)
                    if (entityId == null) people.createPerson(draft, basis).bind().value
                    else { people.updatePerson(PersonId(entityId!!), draft, token!!).bind(); entityId!! }
                }
                MetadataKind.GROUP -> {
                    ensure(fields.name.isNotBlank()) { LedgerError.InvalidField("name") }
                    val draft = GroupDraft(fields.name.trim(), currency!!, fields.fxMode)
                    if (entityId == null) groups.createGroup(draft, fields.members.toList(), basis).bind().value
                    else { groups.updateGroup(GroupId(entityId!!), draft, token!!).bind(); entityId!! }
                }
                MetadataKind.HANDLE -> {
                    ensure(fields.value.isNotBlank()) { LedgerError.InvalidField("value") }
                    val draft = HandleDraft(fields.handleType, fields.value, fields.label,
                        fields.holder.takeIf { it.isNotBlank() }, fields.bic.takeIf { it.isNotBlank() }, fields.defaultHandle)
                    if (entityId == null) people.addHandle(PersonId(ownerId!!), draft, basis).bind().value
                    else { people.updateHandle(HandleId(entityId!!), draft, token!!).bind(); entityId!! }
                }
            }
        }
    }
}
