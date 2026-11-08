package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.FxChoice
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.ResultScope
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.api.ledgerResult
import io.github.codex07.iou.ledger.engine.FxConverter
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.engine.SplitResolver
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.Entry
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.ExpenseShare
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.FxSnapshotId
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.LocalDate

/** What saving an expense draft would store (no write). */
data class SharePreview(val effectTotal: Money, val shares: List<ExpenseShare>, val recomputed: Boolean)

/** Transfers and expenses: FX choices (spec §4.5), split recompute rules (spec §4.4), delete and restore. */
object EntryOps {

    fun saveTransfer(ctx: WriteContext, draft: TransferDraft, mode: SaveMode): LedgerResult<Planned<EntryId>> = ledgerResult {
        val existing: Transfer? = when (mode) {
            is SaveMode.Create -> {
                checkBasis(ctx, mode.basis)
                null
            }
            is SaveMode.Update -> edited(ctx, mode.token, mode.id.value) { it.transfers[mode.id] }
        }
        checkAmount(draft.amount)
        val required = requiredEffectCurrency(ctx.state, draft.groupId, draft.amount.currency, draft.settlesCurrency)
        val fx = resolveFx(ctx, draft.amount, required, draft.fx, existing, draft.occurredOn)

        val resolver = IdentityResolver(ctx.state)
        val transfer = Transfer(
            id = existing?.id ?: EntryId(ctx.ids.next()),
            fromPersonId = storedPerson(resolver, draft.from, existing?.fromPersonId),
            toPersonId = storedPerson(resolver, draft.to, existing?.toPersonId),
            amount = draft.amount,
            effectAmount = fx.effect,
            fxSnapshotId = fx.snapshotId,
            kind = draft.kind,
            method = draft.method,
            accountLabel = draft.accountLabel.cleaned(),
            groupId = draft.groupId,
            occurredOn = draft.occurredOn,
            note = draft.note.cleaned(),
            meta = existing?.meta ?: ctx.newMeta(),
        ).let { if (existing == null) it else ctx.touch(it) }

        val action = if (existing == null) RevisionAction.CREATED else RevisionAction.UPDATED
        Planned(transfer.id, validated(ctx, listOf(Change(transfer, action)), listOfNotNull(fx.newSnapshot)))
    }

    fun saveExpense(ctx: WriteContext, draft: ExpenseDraft, mode: SaveMode): LedgerResult<Planned<EntryId>> = ledgerResult {
        val existing: Expense? = when (mode) {
            is SaveMode.Create -> {
                checkBasis(ctx, mode.basis)
                null
            }
            is SaveMode.Update -> edited(ctx, mode.token, mode.id.value) { it.expenses[mode.id] }
        }
        val (fx, preview) = sharesFor(ctx, draft, existing)

        val expense = Expense(
            id = existing?.id ?: EntryId(ctx.ids.next()),
            groupId = draft.groupId,
            payers = ExpensePayer.single(
                storedPerson(IdentityResolver(ctx.state), draft.payer, existing?.payers?.singleOrNull()?.personId),
                preview.effectTotal.amountMinor,
            ),
            description = draft.description.trim(),
            category = draft.category,
            total = draft.total,
            effectTotal = preview.effectTotal,
            fxSnapshotId = fx.snapshotId,
            splitType = draft.splitType,
            shares = preview.shares,
            occurredOn = draft.occurredOn,
            note = draft.note.cleaned(),
            meta = existing?.meta ?: ctx.newMeta(),
        ).let { if (existing == null) it else ctx.touch(it) }

        val action = if (existing == null) RevisionAction.CREATED else RevisionAction.UPDATED
        Planned(expense.id, validated(ctx, listOf(Change(expense, action)), listOfNotNull(fx.newSnapshot)))
    }

    /** The shares a save would store, for `previewSplit` (spec §4.4). Writes nothing. */
    fun previewShares(ctx: WriteContext, draft: ExpenseDraft, existing: Expense?): LedgerResult<SharePreview> =
        ledgerResult { sharesFor(ctx, draft, existing).second }

    fun deleteEntry(ctx: WriteContext, id: EntryId, token: EditToken): LedgerResult<Planned<Unit>> = ledgerResult {
        val entry = edited(ctx, token, id.value) { it.entry(id) }
        if (entry.isDeleted) return@ledgerResult Planned(Unit, WritePlan.Empty)
        Planned(Unit, validated(ctx, listOf(Change(ctx.touch(entry, deletedAt = ctx.now), RevisionAction.DELETED))))
    }

    fun restoreEntry(ctx: WriteContext, id: EntryId, token: EditToken): LedgerResult<Planned<Unit>> = ledgerResult {
        val entry = edited(ctx, token, id.value) { it.entry(id) }
        if (!entry.isDeleted) return@ledgerResult Planned(Unit, WritePlan.Empty)
        Planned(Unit, validated(ctx, listOf(Change(ctx.touch(entry, deletedAt = null), RevisionAction.RESTORED))))
    }

    /** Spec §4.5: CONVERT group currency, else the transfer's `settlesCurrency`, else the paid currency. */
    fun requiredEffectCurrency(state: LedgerState, groupId: GroupId?, paid: CurrencyCode, settlesCurrency: CurrencyCode?): CurrencyCode {
        val group = groupId?.let { state.groups[it] }
        return when {
            group != null && group.fxMode == FxMode.CONVERT -> group.defaultCurrency
            settlesCurrency != null -> settlesCurrency
            else -> paid
        }
    }

    /** Canonical participants, records of one person combined: inputs added, one slot for EQUAL (spec §4.4). */
    fun combineInputs(inputs: List<SplitInput>, type: SplitType, resolver: IdentityResolver): List<SplitInput> {
        val combined = LinkedHashMap<PersonId, Long?>()
        for (input in inputs) {
            val person = resolver.canonical(input.personId)
            combined[person] = when {
                type == SplitType.EQUAL -> null
                person !in combined -> input.value
                else -> {
                    val a = combined[person]
                    val b = input.value
                    if (a == null || b == null) null else Math.addExact(a, b)
                }
            }
        }
        return combined.map { (person, value) -> SplitInput(person, value) }
    }

    /** The stored split as an edit draft shows it. */
    fun splitInputsOf(expense: Expense, resolver: IdentityResolver): List<SplitInput> =
        combineInputs(expense.shares.map { SplitInput(it.personId, it.inputValue) }, expense.splitType, resolver)

    // ---- internals ----

    /**
     * The ID to store for a drafted person (spec §5.6): the canonical person, so a new entry stays with the
     * merge target after an unmerge; an edit keeps the stored record while it resolves to the same person.
     */
    private fun storedPerson(resolver: IdentityResolver, drafted: PersonId, stored: PersonId?): PersonId {
        val canonical = resolver.canonical(drafted)
        return if (stored != null && resolver.canonical(stored) == canonical) stored else canonical
    }

    private data class FxResolution(val effect: Money, val snapshotId: FxSnapshotId?, val newSnapshot: FxSnapshot?)

    private fun ResultScope.sharesFor(ctx: WriteContext, draft: ExpenseDraft, existing: Expense?): Pair<FxResolution, SharePreview> {
        checkAmount(draft.total)
        val required = requiredEffectCurrency(ctx.state, draft.groupId, draft.total.currency, settlesCurrency = null)
        val fx = resolveFx(ctx, draft.total, required, draft.fx, existing, draft.occurredOn)
        val resolver = IdentityResolver(ctx.state)
        // Negatives are rejected per record first: combining records of one person could hide one, and
        // combining is exact arithmetic, so an overflow there is AmountTooLarge (index decision 4).
        draft.participants.firstOrNull { (it.value ?: 0L) < 0L }?.let { fail(LedgerError.NegativeInput(it.personId)) }
        // The same record twice is a malformed draft (spec §4.4); only distinct records of one merged person combine.
        draft.participants.groupingBy { it.personId }.eachCount().entries.firstOrNull { it.value > 1 }
            ?.let { fail(LedgerError.DuplicateParticipant(it.key)) }
        val inputs = try {
            combineInputs(draft.participants, draft.splitType, resolver)
        } catch (overflow: ArithmeticException) {
            null
        } ?: fail(LedgerError.AmountTooLarge)

        val keep = existing != null &&
            (draft.fx == null || draft.fx == FxChoice.KeepExisting) &&
            draft.total == existing.total &&
            fx.effect == existing.effectTotal &&
            draft.splitType == existing.splitType &&
            inputs.toSet() == splitInputsOf(existing, resolver).toSet()

        val shares = if (keep) {
            existing!!.shares
        } else {
            SplitResolver.resolve(draft.splitType, fx.effect.amountMinor, draft.total.amountMinor, inputs).bind()
        }
        return fx to SharePreview(fx.effect, shares, recomputed = !keep)
    }

    private fun ResultScope.resolveFx(
        ctx: WriteContext,
        paid: Money,
        required: CurrencyCode,
        choice: FxChoice?,
        existing: Entry?,
        occurredOn: LocalDate,
    ): FxResolution {
        if (required == paid.currency) return FxResolution(paid, null, null)
        val mismatch = LedgerError.FxSnapshotMismatch(existing?.id)
        return when (choice) {
            null -> fail(LedgerError.RateRequired(paid.currency, required))

            FxChoice.KeepExisting -> {
                val snapshot = existing?.fxSnapshotId?.let { ctx.state.fxSnapshots[it] } ?: fail(mismatch)
                ensure(snapshot.baseCurrency == paid.currency && snapshot.quoteCurrency == required) { mismatch }
                val effect = if (snapshot.source == FxSource.BANK_ACTUAL) {
                    ensure(snapshot.paidMinor == paid.amountMinor) { mismatch }
                    Money(snapshot.chargedMinor!!, required)
                } else {
                    FxConverter.convert(paid, required, snapshot.rate).bind()
                }
                FxResolution(effect, snapshot.id, null)
            }

            is FxChoice.Rate -> {
                val quote = choice.quote
                ensure(quote.base == paid.currency && quote.quote == required) { mismatch }
                ensure(quote.source != FxSource.BANK_ACTUAL) { LedgerError.InvalidRate(quote.rate) }
                val effect = FxConverter.convert(paid, required, quote.rate).bind()
                val snapshot = FxSnapshot(
                    id = FxSnapshotId(ctx.ids.next()), baseCurrency = paid.currency, quoteCurrency = required,
                    rate = quote.rate.trim(), rateDate = quote.rateDate, source = quote.source,
                    isStale = quote.isStale, fetchedAt = ctx.now,
                )
                FxResolution(effect, snapshot.id, snapshot)
            }

            is FxChoice.BankActual -> {
                ensure(choice.charged.currency == required) { mismatch }
                checkAmount(choice.charged)
                val snapshot = FxSnapshot(
                    id = FxSnapshotId(ctx.ids.next()), baseCurrency = paid.currency, quoteCurrency = required,
                    rate = FxConverter.deriveRate(paid, choice.charged), rateDate = occurredOn,
                    source = FxSource.BANK_ACTUAL, isStale = false, fetchedAt = ctx.now,
                    paidMinor = paid.amountMinor, chargedMinor = choice.charged.amountMinor,
                )
                FxResolution(choice.charged, snapshot.id, snapshot)
            }
        }
    }
}
