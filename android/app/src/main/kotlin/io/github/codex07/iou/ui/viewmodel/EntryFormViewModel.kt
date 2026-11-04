package io.github.codex07.iou.ui.viewmodel

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.money.*
import io.github.codex07.iou.ui.forms.*
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class EntryFormState(
    val fields: EntryFields = EntryFields(), val people: List<PersonRef> = emptyList(),
    val groups: List<GroupDetail> = emptyList(), val editing: Boolean = false,
    val preview: SplitPreview? = null, val previewError: LedgerError? = null, val previewBusy: Boolean = false,
    val quote: FxQuote? = null, val storedFx: FxSnapshotView? = null,
    val remaining: List<Money>? = null, val remainingLabel: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EntryFormViewModel @Inject constructor(
    private val ledger: LedgerApi, private val people: PeopleApi, private val groups: GroupsApi,
    private val setup: SetupApi, private val fx: FxApi, backup: BackupApi,
) : SafeFormViewModel(backup) {
    private val mutableState = MutableStateFlow(EntryFormState())
    val state = mutableState.asStateFlow()
    private var initial = EntryFields()
    private var id: EntryId? = null
    private var token: EditToken? = null
    private var me: PersonRef? = null
    private var previewJob: Job? = null
    private var catalog: Job? = null
    private var revision = 0L
    private val quotes = mutableMapOf<Triple<java.time.LocalDate, CurrencyCode, CurrencyCode>, FxQuote>()

    fun open(purpose: String = "expense", entryId: String? = null) { initialize { load(purpose, entryId) } }
    private suspend fun load(purpose: String, entryId: String?) {
        id = entryId?.let(::EntryId)
        me = (setup.observeSetupState().first() as? SetupState.Ready)?.me
        if (me == null) { fail(LedgerError.InvalidField("participants")); return }
        val contacts = people.observePeople(includeArchived = true).first().map { it.person }
        val allGroups = groups.observeGroups(includeArchived = true).first().mapNotNull { groups.observeGroup(it.group.id).first() }
        var fields = EntryFields(expense = purpose == "expense", payer = me!!.id.value,
            participants = listOf(me!!.id.value), currency = ledger.observeOverview().first().homeCurrency.code,
            kind = if (purpose == "repayment") TransferKind.REPAYMENT else TransferKind.LOAN)
        var snapshot: FxSnapshotView? = null
        if (id != null) {
            val detail = ledger.observeEntry(id!!).first()
            if (detail == null || detail.item.deleted) { fail(LedgerError.NotFound(entryId!!)); return }
            token = detail.token; requireGeneration(detail.token)
            fields = EntryInput.fromDraft(detail.editDraft); snapshot = detail.fx
        }
        initial = fields
        mutableState.value = EntryFormState(fields, listOf(me!!) + contacts, allGroups, id != null, storedFx = snapshot)
        catalog?.cancel()
        catalog = viewModelScope.launch {
            val groupFlow = groups.observeGroups(includeArchived = true).flatMapLatest { summaries ->
                if (summaries.isEmpty()) flowOf(emptyList()) else combine(summaries.map { groups.observeGroup(it.group.id) }) { records -> records.filterNotNull() }
            }
            combine(people.observePeople(includeArchived = true), groupFlow) { contactsNow, groupsNow ->
                (listOf(me!!) + contactsNow.map { it.person }) to groupsNow
            }.catch {
                cancelPending()
                mutableStatus.update { it.copy(invalidated = true, message = "Current people or membership data could not be loaded. Close and reopen this form.") }
            }.collect { (peopleNow, groupsNow) ->
                val changed = mutableState.value.groups != groupsNow || mutableState.value.people != peopleNow
                mutableState.update { it.copy(people = peopleNow, groups = groupsNow) }
                if (changed && editable) schedulePreview()
            }
        }
        // initialize() clears loading after load returns.
        viewModelScope.launch { yield(); schedulePreview() }
    }
    fun change(update: (EntryFields) -> EntryFields) {
        if (!editable) return
        val next = update(state.value.fields)
        mutableState.update { it.copy(fields = next, preview = null, previewError = null, quote = null) }
        changed(next != initial); schedulePreview()
    }
    fun chooseGroup(value: String?) = change { fields ->
        // Preserve entered selections; the user chooses valid members rather than silently losing a split.
        fields.copy(group = value, fxMode = if (id == null) FxInputMode.AUTO else fields.fxMode, acceptStale = false)
    }
    fun useCreatedGroup(value: String) { chooseGroup(value) }
    fun groupMembersForDraft(): Set<PersonId> = (state.value.fields.participants + state.value.fields.payer + state.value.fields.receiver)
        .filter { it.isNotBlank() && it != me?.id?.value }.map(::PersonId).toSet()
    fun reviewLatest() { reopen { load("expense", id?.value) } }
    override fun cancelPending() {
        revision++; previewJob?.cancel(); catalog?.cancel()
        mutableState.update { it.copy(preview = null, quote = null, previewBusy = false) }
    }
    fun finish() { end(); catalog?.cancel() }
    private fun schedulePreview() {
        previewJob?.cancel()
        val version = ++revision
        val fields = state.value.fields
        mutableState.update { it.copy(preview = null, previewError = null, previewBusy = true, quote = null) }
        previewJob = viewModelScope.launch {
            delay(250)
            try {
                val prepared = prepare(fields)
                var quote: FxQuote? = null
                val result: LedgerResult<SplitPreview?> = when (prepared) {
                    is LedgerResult.Err -> prepared
                    is LedgerResult.Ok -> {
                        quote = prepared.value.second
                        val draft = prepared.value.first
                        if (draft is ExpenseDraft) ledger.previewSplit(draft, id) else LedgerResult.Ok(null)
                    }
                }
                if (revision == version && !status.value.invalidated) mutableState.update { it.copy(
                    preview = result.getOrNull(), previewError = result.errorOrNull(), quote = quote, previewBusy = false) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (revision == version) mutableState.update { it.copy(previewBusy = false, previewError = LedgerError.InvalidField("preview")) }
            }
        }
    }
    private suspend fun prepare(fields: EntryFields): LedgerResult<Pair<EntryDraft, FxQuote?>> {
        val parsed = EntryInput.draft(fields)
        if (parsed is LedgerResult.Err) return parsed
        val draft = (parsed as LedgerResult.Ok).value
        val paid = when (draft) { is ExpenseDraft -> draft.total; is TransferDraft -> draft.amount }
        val date = when (draft) { is ExpenseDraft -> draft.occurredOn; is TransferDraft -> draft.occurredOn }
        val group = fields.group?.let { selected -> state.value.groups.firstOrNull { it.group.id.value == selected } }
        if (fields.group != null && group == null) return LedgerResult.Err(LedgerError.NotFound(fields.group))
        val target = if (group?.fxMode == FxMode.CONVERT) group.defaultCurrency
            else if (draft is TransferDraft && draft.settlesCurrency != null) draft.settlesCurrency else paid.currency
        var displayQuote: FxQuote? = null
        val choice: LedgerResult<FxChoice?> = if (target == paid.currency) LedgerResult.Ok(null) else when (fields.fxMode) {
            FxInputMode.SAVED -> if (state.value.storedFx == null) LedgerResult.Err(LedgerError.RateRequired(paid.currency, target)) else LedgerResult.Ok(FxChoice.KeepExisting)
            FxInputMode.AUTO -> {
                val key = Triple(date, paid.currency, target)
                val quote = quotes[key]?.let { LedgerResult.Ok(it) } ?: fx.quote(date, paid.currency, target)
                quote.map { value -> quotes[key] = value; displayQuote = value; FxChoice.Rate(value) }
            }
            FxInputMode.MANUAL -> fx.userRate(date, paid.currency, target, fields.rate.trim().replace(',', '.')).map { displayQuote = it; FxChoice.Rate(it) }
            FxInputMode.BANK -> Inputs.amount(fields.charged, target).flatMap { charged ->
                fx.rateFromCharged(date, paid, charged).map { displayQuote = it; FxChoice.BankActual(charged) }
            }
        }
        return choice.map { selected ->
            val updated = when (draft) { is ExpenseDraft -> draft.copy(fx = selected); is TransferDraft -> draft.copy(fx = selected) }
            updated to displayQuote
        }
    }
    fun save() {
        if (!editable) return
        previewJob?.cancel(); revision++
        submit {
            val prepared = prepare(state.value.fields)
            if (prepared is LedgerResult.Err) return@submit prepared
            val (draft, quote) = (prepared as LedgerResult.Ok).value
            mutableState.update { it.copy(quote = quote) }
            if (quote?.isStale == true && !state.value.fields.acceptStale) {
                return@submit LedgerResult.Err(LedgerError.InvalidField("staleRate"))
            }
            val mode = id?.let { SaveMode.Update(it, token!!) } ?: SaveMode.Create(basis)
            val result = when (draft) {
                is ExpenseDraft -> when (val preview = ledger.previewSplit(draft, id)) {
                    is LedgerResult.Err -> preview
                    is LedgerResult.Ok -> ledger.saveExpense(draft, mode)
                }
                is TransferDraft -> ledger.saveTransfer(draft, mode)
            }
            if (result is LedgerResult.Ok && draft is TransferDraft && draft.kind == TransferKind.REPAYMENT) {
                // Read the new position from the API. No subtraction or settlement math in the UI.
                try {
                    if (draft.groupId != null) {
                        val group = groups.observeGroup(draft.groupId).first()
                        mutableState.update { it.copy(remaining = group?.members?.firstOrNull { m -> m.person.isMe }?.nets,
                            remainingLabel = "Your remaining group position") }
                    } else {
                        val other = if (draft.from == me?.id) draft.to else draft.from
                        val person = people.observePerson(other).first()
                        mutableState.update { it.copy(remaining = person?.balances, remainingLabel = "Remaining personal balance") }
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { mutableState.update { it.copy(remainingLabel = "Open the person or group to view the updated balance.") } }
            }
            result.map { it.value }
        }
    }
}
