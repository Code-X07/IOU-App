package io.github.codex07.iou.fake

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.FxChoice
import io.github.codex07.iou.api.FxQuote
import io.github.codex07.iou.api.GroupDraft
import io.github.codex07.iou.api.HandleDraft
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.ledger.model.Category
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.ImportBatch
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.ImportSource
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.ledger.ops.EntryOps
import io.github.codex07.iou.ledger.ops.GroupOps
import io.github.codex07.iou.ledger.ops.PeopleOps
import io.github.codex07.iou.ledger.ops.RecurringOps
import io.github.codex07.iou.ledger.ops.WriteContext
import io.github.codex07.iou.ledger.ops.WritePlan
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.StoreWrite
import java.time.Duration

/**
 * Demo data for the fake (spec §6.3), written through the real ops so every row is validated and has
 * revisions: me, five people (one merged duplicate, one archived with an open balance), handles,
 * a CONVERT EUR trip with a SEK expense, and a KEEP flat with SEK and EUR expenses.
 */
object SeedData {

    suspend fun populate(store: LedgerStore) {
        val today = store.today()
        val eur = CurrencyCode("EUR")
        val sek = CurrencyCode("SEK")

        fun <T> ok(result: LedgerResult<T>): T = when (result) {
            is LedgerResult.Ok -> result.value
            is LedgerResult.Err -> error("Seed write failed: ${result.error}")
        }
        fun WriteContext.token(entity: LedgerEntity) = EditToken(generation, entity.meta.version)
        suspend fun state() = store.loaded().state
        suspend fun person(name: String): PersonId = ok(store.write { PeopleOps.createPerson(it, PersonDraft(name), it.generation) })
        suspend fun transfer(from: PersonId, to: PersonId, amount: Money, daysAgo: Long, kind: TransferKind = TransferKind.LOAN, group: GroupId? = null, note: String? = null) =
            ok(store.write {
                EntryOps.saveTransfer(it, TransferDraft(from, to, amount, kind, groupId = group, occurredOn = today.minusDays(daysAgo), note = note), SaveMode.Create(it.generation))
            })
        suspend fun expense(draft: ExpenseDraft) = ok(store.write { EntryOps.saveExpense(it, draft, SaveMode.Create(it.generation)) })

        val me = ok(store.write(beforeSetup = true) { PeopleOps.setup(it, "Me") })
        val alex = person("Alex")
        val alexDuplicate = person("Alex S.")
        val bea = person("Bea")
        val chen = person("Chen")
        val dana = person("Dana")
        val erik = person("Erik")

        ok(store.write { PeopleOps.addHandle(it, alex, HandleDraft(HandleType.IBAN, "DE89 3704 0044 0532 0130 00", "Main account", holderName = "Alex Example", isDefault = true), it.generation) })
        ok(store.write { PeopleOps.addHandle(it, alex, HandleDraft(HandleType.REVTAG, "@alexexample", "Revolut", isDefault = true), it.generation) })
        ok(store.write { PeopleOps.addHandle(it, bea, HandleDraft(HandleType.PHONE, "+46 70 123 45 67", "Mobile"), it.generation) })

        // Alex: a loan edited once, a partial repayment, and a duplicate record merged into Alex.
        val loan = transfer(me, alex, Money(5_000, eur), daysAgo = 30, note = "Concert tickets")
        val loanRow = state().transfers.getValue(loan)
        ok(store.write {
            EntryOps.saveTransfer(it, TransferDraft(me, alex, Money(4_500, eur), occurredOn = loanRow.occurredOn, note = "Concert tickets"), SaveMode.Update(loan, it.token(loanRow)))
        })
        transfer(alex, me, Money(2_000, eur), daysAgo = 10, kind = TransferKind.REPAYMENT)
        transfer(me, alexDuplicate, Money(500, eur), daysAgo = 25)
        ok(store.write { PeopleOps.merge(it, alexDuplicate, alex, it.token(it.state.people.getValue(alexDuplicate))) })

        // Erik: an ad-hoc dinner he paid.
        expense(ExpenseDraft(erik, "Dinner", Category.DINING, Money(6_000, eur), participants = listOf(SplitInput(me), SplitInput(erik)), occurredOn = today.minusDays(3)))

        // Dana: archived with an open balance.
        transfer(me, dana, Money(2_000, eur), daysAgo = 60)
        ok(store.write { PeopleOps.setPersonArchived(it, dana, true, it.token(it.state.people.getValue(dana))) })

        // Group 1: CONVERT EUR trip with a SEK expense.
        val trip = ok(store.write { GroupOps.createGroup(it, GroupDraft("Lisbon trip", eur, FxMode.CONVERT), listOf(alex, bea, chen), it.generation) })
        val everyone = listOf(SplitInput(me), SplitInput(alex), SplitInput(bea), SplitInput(chen))
        expense(ExpenseDraft(me, "Apartment", Category.ACCOMMODATION, Money(48_000, eur), trip, SplitType.EQUAL, everyone, today.minusDays(20)))
        val sekToEur = FakeRates.rate(sek, eur)!!
        expense(
            ExpenseDraft(bea, "Airport taxi", Category.TRANSPORT, Money(120_000, sek), trip, SplitType.EQUAL, everyone, today.minusDays(19),
                fx = FxChoice.Rate(FxQuote(sek, eur, sekToEur, today.minusDays(19), FxSource.ECB_FRANKFURTER, isStale = false))),
        )
        expense(ExpenseDraft(chen, "Groceries", Category.GROCERIES, Money(6_350, eur), trip, SplitType.SHARES,
            listOf(SplitInput(me, 1), SplitInput(alex, 2), SplitInput(chen, 1)), today.minusDays(18)))
        // Two payers: Alex and Bea bought the museum tickets together.
        expense(
            ExpenseDraft(
                listOf(SplitInput(alex, 5_000), SplitInput(bea, 3_000)), "Museum tickets", Category.ENTERTAINMENT, Money(8_000, eur), trip,
                SplitType.EQUAL, everyone, today.minusDays(17), payerSplit = PayerSplit.EXACT,
            ),
        )

        // Group 2: KEEP flat with SEK rent and a EUR purchase.
        val flat = ok(store.write { GroupOps.createGroup(it, GroupDraft("Flat", sek, FxMode.KEEP), listOf(bea, chen), it.generation) })
        val flatmates = listOf(SplitInput(me), SplitInput(bea), SplitInput(chen))
        expense(ExpenseDraft(chen, "Rent", Category.RENT, Money(1_500_000, sek), flat, SplitType.EQUAL, flatmates, today.minusDays(5)))
        expense(ExpenseDraft(me, "Vacuum cleaner", Category.UTILITIES, Money(12_000, eur), flat, SplitType.EXACT,
            listOf(SplitInput(me, 4_000), SplitInput(bea, 4_000), SplitInput(chen, 4_000)), today.minusDays(4)))
        transfer(bea, chen, Money(500_000, sek), daysAgo = 2, kind = TransferKind.REPAYMENT, group = flat)

        // Schedules: the flat's monthly rent waits for confirmation (one date due), a subscription posts by itself.
        suspend fun schedule(draft: RecurringDraft) =
            ok(store.write { RecurringOps.save(it, draft, ScheduleSaveMode.Create(it.generation)) })
        schedule(
            RecurringDraft(
                listOf(SplitInput(chen)), "Rent", Category.RENT, Money(1_500_000, sek), flat,
                participants = flatmates, unit = RecurrenceUnit.MONTH, startOn = today.minusDays(2),
            ),
        )
        schedule(
            RecurringDraft(
                listOf(SplitInput(me)), "Streaming", Category.ENTERTAINMENT, Money(1_299, eur),
                participants = listOf(SplitInput(me), SplitInput(erik)), unit = RecurrenceUnit.MONTH,
                startOn = today.plusDays(3), autoPost = true,
            ),
        )

        // One earlier import, so the import history isn't empty.
        ok(store.writeWith { _, context ->
            val batch = ImportBatch(ImportBatchId(context.ids.next()), ImportSource.OWN_NORMALIZED_CSV, "iou-export-2026-10.zip", context.now.minus(Duration.ofDays(15)), rowCount = 42)
            LedgerResult.Ok(StoreWrite(Unit, WritePlan.Empty, importBatches = listOf(batch)))
        })
    }
}
