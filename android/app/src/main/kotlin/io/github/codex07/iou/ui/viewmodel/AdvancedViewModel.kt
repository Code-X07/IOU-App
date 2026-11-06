package io.github.codex07.iou.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.*
import javax.inject.Inject
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class LifeOperation { ARCHIVE, UNARCHIVE, DELETE, RESTORE }
sealed interface AdvancedAction {
    val subject: String
    data class Lifecycle(val entity: EntityRef, val token: EditToken, val operation: LifeOperation, override val subject: String) : AdvancedAction
    data class Membership(val group: GroupId, val person: PersonId, val basis: LedgerGeneration, val remove: Boolean, override val subject: String) : AdvancedAction
    data class Unmerge(val alias: AliasView) : AdvancedAction { override val subject = alias.person.displayName }
    data class Revision(val revision: RevisionView, val token: EditToken, val owner: PersonId? = null, val editable: Boolean = true, val deletedHandle: Boolean = false) : AdvancedAction { override val subject = revision.subject }
}
data class RecoveryDraft(val entity: EntityRef, val token: EditToken, val draft: VersionDraft, val owner: PersonId? = null)
sealed interface PreparedForm {
    data class Repayment(val draft: SettleUpDraft) : PreparedForm
    data class Recovery(val draft: RecoveryDraft) : PreparedForm
}
enum class AdvancedOutcome { SUCCESS, FAILED, UNCONFIRMED }
data class AdvancedState(
    val pending: AdvancedAction? = null, val busy: Boolean = false, val error: LedgerError? = null,
    val message: String? = null, val openRoute: String? = null, val outcome: AdvancedOutcome? = null,
)

@HiltViewModel
class AdvancedViewModel @Inject constructor(
    private val people: PeopleApi, private val groups: GroupsApi, private val ledger: LedgerApi, private val backup: BackupApi,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AdvancedState())
    val state = mutableState.asStateFlow()
    private val prepared = mutableMapOf<String, PreparedForm>()
    private var generation: LedgerGeneration? = null
    private var job: Job? = null
    init {
        viewModelScope.launch {
            backup.observeLedgerGeneration().catch {
                job?.cancel(); prepared.clear(); mutableState.value = AdvancedState(error = LedgerError.LedgerReplaced)
            }.collect { next ->
                if (generation != null && generation != next) {
                    job?.cancel(); prepared.clear()
                    // Only an action under review or in flight was invalidated; an idle dialog has nothing to report.
                    val interrupted = state.value.pending != null || state.value.busy
                    mutableState.value = if (interrupted) AdvancedState(error = LedgerError.LedgerReplaced) else AdvancedState()
                }
                generation = next
            }
        }
    }
    fun request(action: AdvancedAction) {
        if (state.value.busy) return
        val basis = when (action) {
            is AdvancedAction.Lifecycle -> action.token.generation
            is AdvancedAction.Membership -> action.basis
            is AdvancedAction.Unmerge -> action.alias.token.generation
            is AdvancedAction.Revision -> action.token.generation
        }
        mutableState.value = if (generation != null && generation != basis) AdvancedState(error = LedgerError.LedgerReplaced) else AdvancedState(pending = action)
    }
    fun dismiss() { if (!state.value.busy) mutableState.value = AdvancedState() }
    fun consumeRoute() { mutableState.update { it.copy(openRoute = null) } }
    fun form(key: String): PreparedForm? = prepared[key]
    fun forget(key: String) { prepared.remove(key) }
    private fun launchForm(form: PreparedForm) {
        val key = UUID.randomUUID().toString(); prepared[key] = form
        mutableState.value = AdvancedState(openRoute = "form-prepared:$key")
    }
    private fun execute(block: suspend () -> LedgerResult<Unit>, success: String? = null) {
        if (state.value.busy || state.value.openRoute != null) return
        mutableState.update { it.copy(busy = true, error = null, message = null) }
        job = viewModelScope.launch {
            try {
                val result = block()
                currentCoroutineContext().ensureActive()
                when (result) {
                    is LedgerResult.Ok -> if (state.value.openRoute == null) mutableState.value = AdvancedState(message = success, outcome = AdvancedOutcome.SUCCESS)
                    is LedgerResult.Err -> mutableState.update { it.copy(busy = false, error = result.error, outcome = AdvancedOutcome.FAILED) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableState.update { it.copy(busy = false, pending = null, error = null, outcome = AdvancedOutcome.UNCONFIRMED, message = "The operation could not be confirmed. Review the latest records before trying again.") } }
        }
    }
    fun preparePlan(plan: PlanItem) = execute({
        val current = backup.observeLedgerGeneration().first()
        if (plan.generation != current) return@execute LedgerResult.Err(LedgerError.LedgerReplaced)
        if (plan.group != null && groups.observeGroup(plan.group.id).first()?.settlePlan?.contains(plan) != true)
            return@execute LedgerResult.Err(LedgerError.InvalidField("settlementPlan"))
        ledger.settleUpFromPlan(plan).map { currentCoroutineContext().ensureActive(); launchForm(PreparedForm.Repayment(it)) }
    })
    fun preparePersonal(suggestion: PersonalSettlement) = execute({
        if (suggestion.draft.basis != backup.observeLedgerGeneration().first()) return@execute LedgerResult.Err(LedgerError.LedgerReplaced)
        when (val result = ledger.settleUpWithPerson(suggestion.person.id, suggestion.draft.basis)) {
            is LedgerResult.Err -> result
            is LedgerResult.Ok -> if (suggestion.draft !in result.value) LedgerResult.Err(LedgerError.InvalidField("settlementPlan"))
                else { currentCoroutineContext().ensureActive(); launchForm(PreparedForm.Repayment(suggestion.draft)); LedgerResult.Ok(Unit) }
        }
    })
    fun confirm() {
        if (state.value.error != null) return // A stale/rejected action requires review, never automatic retry.
        val action = state.value.pending ?: return
        execute({ when (action) {
            is AdvancedAction.Unmerge -> people.unmerge(action.alias.person.id, action.alias.token)
            is AdvancedAction.Membership -> if (action.remove) groups.removeMember(action.group, action.person, action.basis)
                else groups.addMember(action.group, action.person, action.basis)
            is AdvancedAction.Revision -> ledger.revertTo(action.revision.entity, action.revision.version, action.token)
            is AdvancedAction.Lifecycle -> when (action.entity.type) {
                EntityType.PERSON -> when (action.operation) {
                    LifeOperation.ARCHIVE, LifeOperation.UNARCHIVE -> people.setPersonArchived(PersonId(action.entity.id), action.operation == LifeOperation.ARCHIVE, action.token)
                    LifeOperation.DELETE -> people.deletePerson(PersonId(action.entity.id), action.token)
                    LifeOperation.RESTORE -> people.restorePerson(PersonId(action.entity.id), action.token)
                }
                EntityType.GROUP -> when (action.operation) {
                    LifeOperation.ARCHIVE, LifeOperation.UNARCHIVE -> groups.setGroupArchived(GroupId(action.entity.id), action.operation == LifeOperation.ARCHIVE, action.token)
                    LifeOperation.DELETE -> groups.deleteGroup(GroupId(action.entity.id), action.token)
                    LifeOperation.RESTORE -> groups.restoreGroup(GroupId(action.entity.id), action.token)
                }
                EntityType.PAYMENT_HANDLE -> if (action.operation == LifeOperation.DELETE) people.deleteHandle(HandleId(action.entity.id), action.token) else LedgerResult.Err(LedgerError.InvalidField("entity"))
                EntityType.EXPENSE, EntityType.TRANSFER -> when (action.operation) {
                    LifeOperation.DELETE -> ledger.deleteEntry(EntryId(action.entity.id), action.token)
                    LifeOperation.RESTORE -> ledger.restoreEntry(EntryId(action.entity.id), action.token)
                    else -> LedgerResult.Err(LedgerError.InvalidField("entity"))
                }
                else -> LedgerResult.Err(LedgerError.InvalidField("entity"))
            }
        } }, "Changes saved. Current balances and history are updated by the ledger.")
    }
    fun archiveInstead() {
        val action = state.value.pending as? AdvancedAction.Lifecycle ?: return
        request(action.copy(operation = LifeOperation.ARCHIVE))
    }
    fun openAsDraft() {
        if (state.value.error !is LedgerError.RestoreConflict) return
        val action = state.value.pending as? AdvancedAction.Revision ?: return
        if (!action.editable) return
        execute({
            ledger.draftFromVersion(action.revision.entity, action.revision.version).map {
                currentCoroutineContext().ensureActive()
                launchForm(PreparedForm.Recovery(RecoveryDraft(action.revision.entity, action.token, it, action.owner)))
            }
        })
    }
}
