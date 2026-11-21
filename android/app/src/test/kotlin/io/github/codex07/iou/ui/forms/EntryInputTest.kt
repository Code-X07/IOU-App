package io.github.codex07.iou.ui.forms

import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.money.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EntryInputTest {
    private val eur = CurrencyCode("EUR")
    private fun base() = EntryFields(amount = "80", currency = "EUR", description = "Museum", payers = listOf("a"),
        participants = listOf("a", "b"), date = "2026-11-04")
    private fun ok(f: EntryFields) = (EntryInput.draft(f) as LedgerResult.Ok).value as ExpenseDraft

    @Test fun `one payer becomes one valueless equal input`() {
        val d = ok(base().copy(payerSplit = PayerSplit.EXACT, payerInputs = mapOf("a" to "80")))
        assertEquals(listOf(SplitInput(PersonId("a"))), d.payers); assertEquals(PayerSplit.EQUAL, d.payerSplit)
    }

    @Test fun `equal payers carry no values`() {
        val d = ok(base().copy(payers = listOf("a", "b")))
        assertEquals(listOf(SplitInput(PersonId("a")), SplitInput(PersonId("b"))), d.payers); assertEquals(PayerSplit.EQUAL, d.payerSplit)
    }

    @Test fun `exact payers parse amounts in the paid currency`() {
        val d = ok(base().copy(payers = listOf("a", "b"), payerSplit = PayerSplit.EXACT, payerInputs = mapOf("a" to "50", "b" to "30,00")))
        assertEquals(listOf(SplitInput(PersonId("a"), 5_000), SplitInput(PersonId("b"), 3_000)), d.payers)
    }

    @Test fun `no payers is NoPayers`() =
        assertEquals(LedgerResult.Err(LedgerError.NoPayers), EntryInput.draft(base().copy(payers = emptyList())))

    @Test fun `exact payer amounts respect the currency decimals`() {
        val r = EntryInput.draft(base().copy(amount = "80", currency = "JPY", payers = listOf("a", "b"),
            payerSplit = PayerSplit.EXACT, payerInputs = mapOf("a" to "50.5", "b" to "29.5")))
        assertTrue((r as LedgerResult.Err).error is LedgerError.TooManyDecimals)
    }

    @Test fun `blank exact payer amount is rejected`() {
        val r = EntryInput.draft(base().copy(payers = listOf("a", "b"), payerSplit = PayerSplit.EXACT, payerInputs = mapOf("a" to "80")))
        assertEquals(LedgerResult.Err(LedgerError.InvalidAmount), r)
    }

    @Test fun `fromDraft round trips multi payer exact drafts`() {
        val f = base().copy(payers = listOf("a", "b"), payerSplit = PayerSplit.EXACT, payerInputs = mapOf("a" to "50.00", "b" to "30.00"))
        val back = EntryInput.fromDraft(ok(f))
        assertEquals(f.payers, back.payers); assertEquals(PayerSplit.EXACT, back.payerSplit); assertEquals(f.payerInputs, back.payerInputs)
    }

    @Test fun `fromDraft keeps a single payer`() =
        assertEquals(listOf("a"), EntryInput.fromDraft(ok(base())).payers)
}
