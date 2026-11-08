package io.github.codex07.iou.ledger.views

import io.github.codex07.iou.api.ActivityFilter
import io.github.codex07.iou.api.ActivityItem
import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.EntryDetail
import io.github.codex07.iou.api.EntryDraft
import io.github.codex07.iou.api.EntryKind
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.FlowView
import io.github.codex07.iou.api.FxChoice
import io.github.codex07.iou.api.FxQuote
import io.github.codex07.iou.api.FxSnapshotView
import io.github.codex07.iou.api.GroupDraft
import io.github.codex07.iou.api.HandleDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PayerView
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.PlanItem
import io.github.codex07.iou.api.RevisionView
import io.github.codex07.iou.api.SettleUpDraft
import io.github.codex07.iou.api.ShareView
import io.github.codex07.iou.api.SplitPreview
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.api.VersionDraft
import io.github.codex07.iou.api.map
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.engine.RevisionDiff
import io.github.codex07.iou.ledger.engine.SettlePlanner
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.Entry
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurringExpense
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.ledger.ops.EntryOps
import io.github.codex07.iou.ledger.ops.WriteContext
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant
import java.time.LocalDate

fun LedgerViews.activity(filter: ActivityFilter): List<ActivityItem> {
    val person = filter.personId?.let(resolver::canonical)
    val from = filter.from
    val to = filter.to
    return state.entries.asSequence()
        .filter { filter.includeDeleted || !it.isDeleted }
        .filter { kindOf(it) in filter.kinds }
        .filter { filter.groupId == null || it.groupId == filter.groupId }
        .filter { person == null || it.personIds.any { id -> resolver.canonical(id) == person } }
        .filter { from == null || !it.occurredOn.isBefore(from) }
        .filter { to == null || !it.occurredOn.isAfter(to) }
        .sortedWith(compareByDescending<Entry> { it.occurredOn }.thenByDescending { it.meta.createdAt }.thenByDescending { it.id.value })
        .map { activityItem(it) }
        .toList()
}

fun LedgerViews.activityItem(entry: Entry): ActivityItem = when (entry) {
    is Transfer -> ActivityItem(
        entryId = entry.id, kind = EntryKind.TRANSFER, occurredOn = entry.occurredOn, description = null,
        note = entry.note, paidBy = personRef(entry.fromPersonId), payers = emptyList(), receiver = personRef(entry.toPersonId),
        participants = emptyList(), transferKind = entry.kind, category = null,
        group = entry.groupId?.let(::groupRef), paid = entry.amount, effect = entry.effectAmount,
        myBalanceEffect = myEffect(entry), deleted = entry.isDeleted, recurringId = null, recurringOn = null,
    )
    is Expense -> ActivityItem(
        entryId = entry.id, kind = EntryKind.EXPENSE, occurredOn = entry.occurredOn, description = entry.description,
        note = entry.note, paidBy = personRef(entry.payers.first().personId), payers = payerViews(entry), receiver = null,
        participants = entry.shares.map { resolver.canonical(it.personId) }.distinct().map(::personRef),
        transferKind = null, category = entry.category,
        group = entry.groupId?.let(::groupRef), paid = entry.total, effect = entry.effectTotal,
        myBalanceEffect = myEffect(entry), deleted = entry.isDeleted, recurringId = entry.recurringId, recurringOn = entry.recurringOn,
    )
}

fun LedgerViews.entry(id: EntryId): EntryDetail? {
    val entry = state.entry(id) ?: return null
    val keep = if (entry.fxSnapshotId != null) FxChoice.KeepExisting else null
    return EntryDetail(
        generation = generation,
        item = activityItem(entry),
        token = token(entry),
        shares = (entry as? Expense)?.let { shareViews(it) }.orEmpty(),
        fx = entry.fxSnapshotId?.let { state.fxSnapshots[it] }?.let(::fxView),
        flows = LedgerEngine.entryEffects(entry, resolver).map { FlowView(personRef(it.creditor), personRef(it.debtor), it.amount) },
        editDraft = draftOf(entry, keep),
        history = history(entry.entityType, id.value),
    )
}

/** Shares per canonical person, records of one person added up. */
fun LedgerViews.shareViews(expense: Expense): List<ShareView> {
    val inputs = EntryOps.splitInputsOf(expense, resolver).associate { it.personId to it.value }
    val sums = LinkedHashMap<PersonId, Long>()
    for (share in expense.shares) {
        val person = resolver.canonical(share.personId)
        sums[person] = Math.addExact(sums[person] ?: 0L, share.shareMinor)
    }
    return sums.map { (person, sum) -> ShareView(personRef(person), Money(sum, expense.effectTotal.currency), inputs[person]) }
}

/** Payers per canonical person, records of one person added up, in stored order (spec §4.4, §6.2). */
fun LedgerViews.payerViews(expense: Expense): List<PayerView> =
    payerViews(expense.payers, expense.payerSplit, expense.effectTotal.currency)

private fun LedgerViews.payerViews(payers: List<ExpensePayer>, split: PayerSplit, currency: CurrencyCode): List<PayerView> {
    val inputs = EntryOps.combineInputs(payers.map { SplitInput(it.personId, it.inputValue) }, split.splitType, resolver)
        .associate { it.personId to it.value }
    val sums = LinkedHashMap<PersonId, Long>()
    for (payer in payers) {
        val person = resolver.canonical(payer.personId)
        sums[person] = Math.addExact(sums[person] ?: 0L, payer.paidMinor)
    }
    return sums.map { (person, sum) -> PayerView(personRef(person), Money(sum, currency), inputs[person]) }
}

fun fxView(snapshot: FxSnapshot) = FxSnapshotView(
    base = snapshot.baseCurrency,
    quote = snapshot.quoteCurrency,
    rate = snapshot.rate,
    rateDate = snapshot.rateDate,
    source = snapshot.source,
    isStale = snapshot.isStale,
    paid = snapshot.paidMinor?.let { Money(it, snapshot.baseCurrency) },
    charged = snapshot.chargedMinor?.let { Money(it, snapshot.quoteCurrency) },
)

/** Revisions of one row, newest first, each diffed against the previous version (spec §5.3). */
fun LedgerViews.history(type: EntityType, id: String): List<RevisionView> = history(listOf(EntityRef(type, id)))

/** Revisions of several rows in one list, newest first; each is diffed against its own row's previous version. */
fun LedgerViews.history(refs: Collection<EntityRef>): List<RevisionView> {
    val wanted = refs.toSet()
    return revisions
        .filter { EntityRef(it.entityType, it.entityId) in wanted }
        .groupBy { EntityRef(it.entityType, it.entityId) }
        .flatMap { (ref, revisionsOfRow) ->
            val own = revisionsOfRow.sortedBy { it.version }
            val subject = subject(ref)
            val rowToken = state.entity(ref.type, ref.id)?.let { token(it) }
            own.mapIndexed { index, revision ->
                RevisionView(
                    entity = ref,
                    subject = subject,
                    version = revision.version,
                    action = revision.action,
                    at = revision.at,
                    author = personRef(revision.authorId),
                    deviceId = revision.deviceId,
                    reason = revision.reason,
                    changes = RevisionDiff.diff(own.getOrNull(index - 1)?.snapshot, revision.snapshot, names),
                    token = rowToken,
                )
            }
        }
        .sortedWith(compareByDescending<RevisionView> { it.at }.thenByDescending { it.version }.thenBy { it.entity.id })
}

/** How a row is named in a mixed history (`RevisionView.subject`). */
fun LedgerViews.subject(ref: EntityRef): String = when (val entity = state.entity(ref.type, ref.id)) {
    is Person -> entity.displayName
    is PaymentHandle -> "${entity.type.name} · ${entity.label}"
    is Group -> entity.name
    is GroupMember -> "${rawRef(entity.personId).displayName} in ${groupRef(entity.groupId).name}"
    is Transfer -> "Transfer on ${entity.occurredOn}"
    is Expense -> entity.description
    is RecurringExpense -> entity.description
    null -> ref.id
}

/** "Open as draft" (spec §5.3): the old version's fields as its edit form's draft; an entry's FX becomes a fresh choice. */
fun LedgerViews.draftFromVersion(ref: EntityRef, version: Int): LedgerResult<VersionDraft> {
    val old = revisions.firstOrNull { it.entityType == ref.type && it.entityId == ref.id && it.version == version }?.snapshot
        ?.takeIf { it.entityType == ref.type && it.entityId == ref.id }
        ?: return LedgerResult.Err(LedgerError.NotFound("${ref.id} v$version"))
    val draft = when (old) {
        is Entry -> VersionDraft.OfEntry(draftOf(old, fxChoiceOf(old)))
        is Person -> VersionDraft.OfPerson(PersonDraft(old.displayName, old.note, old.defaultCurrency))
        is PaymentHandle -> VersionDraft.OfHandle(HandleDraft(old.type, old.value, old.label, old.holderName, old.bic, old.isDefault))
        is Group -> VersionDraft.OfGroup(GroupDraft(old.name, old.defaultCurrency, old.fxMode))
        is RecurringExpense -> VersionDraft.OfSchedule(scheduleDraftOf(old))
        // Memberships have no edit form: the UI adds or removes the member instead.
        else -> return LedgerResult.Err(LedgerError.InvalidField("entity"))
    }
    return LedgerResult.Ok(draft)
}

private fun LedgerViews.fxChoiceOf(entry: Entry): FxChoice? =
    entry.fxSnapshotId?.let { state.fxSnapshots[it] }?.let { snapshot ->
        if (snapshot.source == FxSource.BANK_ACTUAL) {
            FxChoice.BankActual(Money(snapshot.chargedMinor!!, snapshot.quoteCurrency))
        } else {
            FxChoice.Rate(FxQuote(snapshot.baseCurrency, snapshot.quoteCurrency, snapshot.rate, snapshot.rateDate, snapshot.source, snapshot.isStale))
        }
    }

fun LedgerViews.splitPreview(draft: ExpenseDraft, editing: EntryId?, now: Instant): LedgerResult<SplitPreview> {
    val existing = editing?.let { state.expenses[it] ?: return LedgerResult.Err(LedgerError.NotFound(it.value)) }
    val ctx = WriteContext(state, generation, now, IdGenerator.Random)
    return EntryOps.previewShares(ctx, draft, existing).map { preview ->
        val stored = existing?.let { shareViews(it) }
        SplitPreview(
            effectTotal = preview.effectTotal,
            // Kept shares are the stored rows, possibly several per person after a merge: show them combined,
            // as storedShares does. Recomputed shares already have one row per canonical person.
            shares = if (!preview.recomputed && stored != null) {
                stored
            } else {
                preview.shares.map { ShareView(personRef(it.personId), Money(it.shareMinor, preview.effectTotal.currency), it.inputValue) }
            },
            storedShares = stored,
            recomputed = preview.recomputed,
            // Kept payers are the stored rows with the stored payerSplit, which equals the draft's when they are kept.
            payers = payerViews(preview.payers, draft.payerSplit, preview.effectTotal.currency),
            storedPayers = existing?.let { payerViews(it) },
            payersRecomputed = preview.payersRecomputed,
        )
    }
}

fun LedgerViews.settleUpFromPlan(item: PlanItem, today: LocalDate): LedgerResult<SettleUpDraft> {
    if (item.generation != generation) return LedgerResult.Err(LedgerError.LedgerReplaced)
    val group = item.group?.let { state.groups[it.id] }
    val settles = if (group != null && group.fxMode == FxMode.KEEP) item.amount.currency else null
    val draft = TransferDraft(
        from = item.from.id, to = item.to.id, amount = item.amount, kind = TransferKind.REPAYMENT,
        groupId = item.group?.id, occurredOn = today, settlesCurrency = settles,
    )
    return LedgerResult.Ok(SettleUpDraft(draft, generation))
}

fun LedgerViews.settleUpWithPerson(id: PersonId, basis: LedgerGeneration, today: LocalDate): LedgerResult<List<SettleUpDraft>> {
    if (basis != generation) return LedgerResult.Err(LedgerError.LedgerReplaced)
    val canonical = resolver.canonical(id)
    if (canonical !in state.people) return LedgerResult.Err(LedgerError.NotFound(id.value))
    val drafts = SettlePlanner.planPerson(meId, canonical, balances.net(canonical)).map {
        SettleUpDraft(
            TransferDraft(it.from, it.to, it.amount, TransferKind.REPAYMENT, occurredOn = today, settlesCurrency = it.amount.currency),
            generation,
        )
    }
    return LedgerResult.Ok(drafts)
}

fun LedgerViews.balanceSummaryText(id: PersonId): LedgerResult<String> {
    val canonical = resolver.canonical(id)
    if (canonical !in state.people) return LedgerResult.Err(LedgerError.NotFound(id.value))
    val name = personRef(canonical).displayName
    val net = amounts(balances.net(canonical))
    if (net.isEmpty()) return LedgerResult.Ok("You and $name are settled up.")
    return LedgerResult.Ok(
        net.joinToString("\n") { if (it.amountMinor > 0) "$name owes you $it" else "You owe $name ${-it}" },
    )
}

// ---- internals ----

private fun kindOf(entry: Entry) = if (entry is Transfer) EntryKind.TRANSFER else EntryKind.EXPENSE

private fun LedgerViews.myEffect(entry: Entry): Money? {
    val mine = LedgerEngine.entryEffects(entry, resolver).filter { it.creditor == meId || it.debtor == meId }
    if (mine.isEmpty()) return null
    val sum = mine.fold(0L) { acc, effect ->
        if (effect.creditor == meId) Math.addExact(acc, effect.amount.amountMinor) else Math.subtractExact(acc, effect.amount.amountMinor)
    }
    return Money(sum, entry.effect.currency)
}

/** An edit draft pre-filled as spec §4.4/§4.5 require: canonical people, `settlesCurrency` loaded. */
private fun LedgerViews.draftOf(entry: Entry, fx: FxChoice?): EntryDraft = when (entry) {
    is Transfer -> {
        val inConvertGroup = entry.groupId?.let { state.groups[it] }?.fxMode == FxMode.CONVERT
        TransferDraft(
            from = personRef(entry.fromPersonId).id,
            to = personRef(entry.toPersonId).id,
            amount = entry.amount,
            kind = entry.kind,
            method = entry.method,
            accountLabel = entry.accountLabel,
            groupId = entry.groupId,
            occurredOn = entry.occurredOn,
            note = entry.note,
            settlesCurrency = if (!inConvertGroup && entry.effectAmount.currency != entry.amount.currency) entry.effectAmount.currency else null,
            fx = fx,
        )
    }
    is Expense -> ExpenseDraft(
        payers = EntryOps.payerInputsOf(entry, resolver),
        description = entry.description,
        category = entry.category,
        total = entry.total,
        groupId = entry.groupId,
        splitType = entry.splitType,
        participants = EntryOps.splitInputsOf(entry, resolver),
        occurredOn = entry.occurredOn,
        note = entry.note,
        fx = fx,
        payerSplit = entry.payerSplit,
    )
}
