package io.github.codex07.iou.testutil

import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.TransferKind
import java.time.LocalDate

/** Ledgers for import and export tests. */
object SampleLedgers {

    /** The me of an install other than the one [full] comes from. */
    val LOCAL_ME = PersonId("local-me")

    /** A fresh install: only its own me. */
    fun localMe(): LedgerState = LedgerState().with(Person(LOCAL_ME, "Me", isMe = true, meta = testMeta()))

    /**
     * Every kind of row a normalized export carries (spec §7.2): people with an alias and handles (one deleted on
     * its own), a CONVERT group with an FX-converted expense, a two-payer expense and a removed member whose
     * expense and repayment cancel out, a deleted group with its memberships, a non-group transfer, a confirm-first
     * schedule with a posted expense, and an auto-post group schedule that starts next year.
     */
    fun full(): LedgerState = TestLedger().run {
        me()
        val alex = person("Alex")
        person("Alex2", mergedInto = alex)
        val bea = person("Bea")
        val cy = person("Cy")
        handle(bea, isDefault = true)
        handle(bea, type = io.github.codex07.iou.ledger.model.HandleType.REVTAG, value = "bea", deleted = true)
        val trip = group("Trip", fxMode = FxMode.CONVERT, members = listOf(ME, alex, bea))
        member(trip, cy, removed = true)
        val sekEur = fxSnapshot(SEK, EUR, "0.09")
        expense(alex, sek(100_000), mapOf(ME to 4_500L, alex to 4_500L), group = trip, effectTotal = eur(9_000), fx = sekEur, splitType = SplitType.EQUAL)
        expense(
            alex, eur(10_000), mapOf(ME to 5_000L, bea to 5_000L), group = trip, payerSplit = PayerSplit.EXACT,
            payers = listOf(ExpensePayer(alex, 6_000, 6_000), ExpensePayer(bea, 4_000, 4_000)),
        )
        expense(ME, eur(2_000), mapOf(cy to 2_000L), group = trip, splitType = SplitType.EQUAL)
        transfer(cy, ME, eur(2_000), group = trip, kind = TransferKind.REPAYMENT)
        group("Old", members = listOf(ME, bea), deleted = true)
        transfer(ME, bea, eur(3_000))
        val rent = schedule(ME, eur(90_000), listOf(ME, bea), id = "rent", handledThrough = LocalDate.parse("2026-02-01"))
        expense(
            ME, eur(90_000), mapOf(ME to 45_000L, bea to 45_000L), splitType = SplitType.EQUAL, id = "rent-feb",
            occurredOn = LocalDate.parse("2026-02-01"), recurring = rent, recurringOn = LocalDate.parse("2026-02-01"),
        )
        schedule(ME, eur(3_000), listOf(ME, alex), group = trip, id = "gym", autoPost = true, startOn = LocalDate.parse("2027-01-01"))
        check(RecurringId("rent") in state.schedules)
        state
    }
}
