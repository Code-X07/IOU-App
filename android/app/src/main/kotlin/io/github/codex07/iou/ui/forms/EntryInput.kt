package io.github.codex07.iou.ui.forms

import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.money.*
import java.math.BigDecimal
import java.time.LocalDate

/** Textual inputs only. All allocations, conversions and balance positions belong to the backend. */
data class EntryFields(
    val expense: Boolean = true, val amount: String = "", val currency: String = "EUR",
    val description: String = "", val category: Category? = null, val date: String = LocalDate.now().toString(),
    /** Transfer sender only; expense payers are [payers]. */
    val payer: String = "", val receiver: String = "", val group: String? = null,
    val payers: List<String> = emptyList(), val payerInputs: Map<String, String> = emptyMap(), val payerSplit: PayerSplit = PayerSplit.EQUAL,
    val participants: List<String> = emptyList(), val inputs: Map<String, String> = emptyMap(),
    val split: SplitType = SplitType.EQUAL, val note: String = "", val kind: TransferKind = TransferKind.LOAN,
    val method: TransferMethod = TransferMethod.BANK, val account: String = "", val settles: String = "",
    val fxMode: FxInputMode = FxInputMode.AUTO, val rate: String = "", val charged: String = "", val acceptStale: Boolean = false,
)
enum class FxInputMode { SAVED, AUTO, MANUAL, BANK }

object EntryInput {
    fun percentage(text: String, person: PersonId): LedgerResult<Long> {
        val value = text.trim()
        if (!Regex("^[0-9]+([.,][0-9]{1,2})?$").matches(value)) return LedgerResult.Err(LedgerError.InvalidField("percentage"))
        return try { LedgerResult.Ok(BigDecimal(value.replace(',', '.')).movePointRight(2).longValueExact()) }
        catch (_: ArithmeticException) { LedgerResult.Err(LedgerError.AmountTooLarge) }
    }
    fun weight(text: String, person: PersonId): LedgerResult<Long> = text.trim().toLongOrNull()?.let { LedgerResult.Ok(it) }
        ?: LedgerResult.Err(LedgerError.MissingInput(person))

    fun draft(fields: EntryFields): LedgerResult<EntryDraft> = ledgerResult {
        val currency = Inputs.currency(fields.currency).bind()
        val amount = Inputs.amount(fields.amount, currency).bind()
        ensure(amount.amountMinor > 0) { LedgerError.InvalidAmount }
        val date = try { LocalDate.parse(fields.date) } catch (_: java.time.DateTimeException) { fail(LedgerError.InvalidField("date")) }
        val group = fields.group?.let(::GroupId)
        if (fields.expense) {
            ensure(fields.description.isNotBlank()) { LedgerError.InvalidField("description") }
            ensure(fields.payers.isNotEmpty()) { LedgerError.NoPayers }
            val payerSplit = if (fields.payers.size > 1) fields.payerSplit else PayerSplit.EQUAL
            val payers = fields.payers.map { id ->
                SplitInput(PersonId(id), if (payerSplit == PayerSplit.EXACT) Inputs.amount(fields.payerInputs[id].orEmpty(), currency).bind().amountMinor else null)
            }
            val participants = fields.participants.map { id ->
                val person = PersonId(id)
                val text = fields.inputs[id].orEmpty()
                SplitInput(person, when (fields.split) {
                    SplitType.EQUAL -> null
                    SplitType.PERCENT -> percentage(text, person).bind()
                    SplitType.SHARES -> weight(text.ifBlank { "1" }, person).bind()
                    SplitType.EXACT -> Inputs.amount(text, currency).bind().amountMinor
                })
            }
            ExpenseDraft(payers = payers, description = fields.description.trim(), category = fields.category, total = amount,
                groupId = group, splitType = fields.split, participants = participants, occurredOn = date,
                note = fields.note.takeIf { it.isNotBlank() }, payerSplit = payerSplit)
        } else {
            ensure(fields.payer.isNotBlank()) { LedgerError.InvalidField("participants") }
            ensure(fields.receiver.isNotBlank()) { LedgerError.InvalidField("participants") }
            ensure(fields.payer != fields.receiver) { LedgerError.SelfTransfer }
            TransferDraft(PersonId(fields.payer), PersonId(fields.receiver), amount, fields.kind, fields.method,
                fields.account.takeIf { it.isNotBlank() }, group, date, fields.note.takeIf { it.isNotBlank() },
                fields.settles.takeIf { it.isNotBlank() }?.let { Inputs.currency(it).bind() })
        }
    }

    fun fromDraft(draft: EntryDraft): EntryFields = when (draft) {
        is ExpenseDraft -> EntryFields(expense = true, amount = draft.total.toPlainString(), currency = draft.total.currency.code,
            description = draft.description, category = draft.category, date = draft.occurredOn.toString(),
            payers = draft.payers.map { it.personId.value }, payerSplit = draft.payerSplit,
            payerInputs = draft.payers.mapNotNull { p -> p.value?.let { p.personId.value to Money(it, draft.total.currency).toPlainString() } }.toMap(),
            group = draft.groupId?.value, participants = draft.participants.map { it.personId.value }, split = draft.splitType,
            inputs = draft.participants.associate { input -> input.personId.value to when (draft.splitType) {
                SplitType.PERCENT -> input.value?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()
                SplitType.EXACT -> input.value?.let { Money(it, draft.total.currency).toPlainString() }.orEmpty()
                else -> input.value?.toString().orEmpty()
            } }, note = draft.note.orEmpty(), fxMode = if (draft.fx != null) FxInputMode.SAVED else FxInputMode.AUTO)
        is TransferDraft -> EntryFields(expense = false, amount = draft.amount.toPlainString(), currency = draft.amount.currency.code,
            payer = draft.from.value, receiver = draft.to.value, date = draft.occurredOn.toString(), group = draft.groupId?.value,
            kind = draft.kind, method = draft.method, account = draft.accountLabel.orEmpty(), note = draft.note.orEmpty(),
            settles = draft.settlesCurrency?.code.orEmpty(), fxMode = if (draft.fx != null) FxInputMode.SAVED else FxInputMode.AUTO)
    }
}
