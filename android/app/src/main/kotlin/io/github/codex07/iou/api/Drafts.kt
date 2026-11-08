package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.Category
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.ledger.model.TransferMethod
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.LocalDate

data class PersonDraft(
    val displayName: String,
    val note: String? = null,
    val defaultCurrency: CurrencyCode? = null,
)

/**
 * IBAN: any case, spaces allowed (stored uppercase without spaces, mod-97 checked); Revtag: with or
 * without `@` (stored without); phone: E.164 (`+` and 8–15 digits). [isDefault] means the canonical
 * person's default of [type], as `PaymentHandleView.isDefault` shows it: fill it from the view.
 */
data class HandleDraft(
    val type: HandleType,
    val value: String,
    val label: String,
    val holderName: String? = null,
    val bic: String? = null,
    val isDefault: Boolean = false,
)

data class GroupDraft(
    val name: String,
    val defaultCurrency: CurrencyCode,
    val fxMode: FxMode = FxMode.CONVERT,
)

sealed interface EntryDraft

/**
 * A transfer to create or save. [settlesCurrency] makes an explicit cross-currency settlement outside
 * CONVERT groups: the effect lands in that currency (spec §4.5, §4.6). [fx] is needed whenever the
 * effect currency differs from [amount]'s currency.
 */
data class TransferDraft(
    val from: PersonId,
    val to: PersonId,
    val amount: Money,
    val kind: TransferKind = TransferKind.LOAN,
    val method: TransferMethod = TransferMethod.BANK,
    val accountLabel: String? = null,
    val groupId: GroupId? = null,
    val occurredOn: LocalDate,
    val note: String? = null,
    val settlesCurrency: CurrencyCode? = null,
    val fx: FxChoice? = null,
) : EntryDraft

/**
 * An expense to create or save. [payers] and [participants] carry split inputs (see [SplitInput]); EXACT
 * values of both are in [total]'s currency. [payerSplit] divides the total among the payers (spec §4.4):
 * `EQUAL` with no values, or `EXACT` amounts that are each greater than 0 and add up to [total]. A single
 * payer is one entry with no value. [fx] is needed in a CONVERT group when [total]'s currency differs from
 * the group currency.
 */
data class ExpenseDraft(
    val payers: List<SplitInput>,
    val description: String,
    val category: Category? = null,
    val total: Money,
    val groupId: GroupId? = null,
    val splitType: SplitType = SplitType.EQUAL,
    val participants: List<SplitInput>,
    val occurredOn: LocalDate,
    val note: String? = null,
    val fx: FxChoice? = null,
    val payerSplit: PayerSplit = PayerSplit.EQUAL,
) : EntryDraft {

    /** A single-payer draft with the parameters in their pre-M2 order: [payer] paid the whole total. */
    constructor(
        payer: PersonId,
        description: String,
        category: Category? = null,
        total: Money,
        groupId: GroupId? = null,
        splitType: SplitType = SplitType.EQUAL,
        participants: List<SplitInput>,
        occurredOn: LocalDate,
        note: String? = null,
        fx: FxChoice? = null,
    ) : this(listOf(SplitInput(payer)), description, category, total, groupId, splitType, participants, occurredOn, note, fx, PayerSplit.EQUAL)

    /**
     * The first payer, kept so single-payer forms still compile. A draft rebuilt from it alone has one payer,
     * so saving it replaces every other payer of the expense.
     */
    @Deprecated("An expense can have several payers: use payers and payerSplit.", ReplaceWith("payers.first().personId"))
    val payer: PersonId get() = payers.first().personId
}

/** A pre-filled repayment built by the core, with the generation it was prepared against (spec §4.6). */
data class SettleUpDraft(
    val draft: TransferDraft,
    val basis: LedgerGeneration,
)

/**
 * "Open as draft" (spec §5.3): an old version's fields as the draft its edit form takes. Save it with
 * the entity's normal update (`saveTransfer`/`saveExpense` with `SaveMode.Update`, `updatePerson`,
 * `updateHandle`, `updateGroup`) and the entity's current token.
 */
sealed interface VersionDraft {
    data class OfEntry(val draft: EntryDraft) : VersionDraft
    data class OfPerson(val draft: PersonDraft) : VersionDraft
    data class OfHandle(val draft: HandleDraft) : VersionDraft
    data class OfGroup(val draft: GroupDraft) : VersionDraft
    data class OfSchedule(val draft: RecurringDraft) : VersionDraft
}
