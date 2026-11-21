package io.github.codex07.iou.ui.forms

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.ui.format.MoneyFormatter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PayerErrorMessageTest {
    private val eur = CurrencyCode("EUR")

    @Test fun `paid remaining names the amount still to allocate`() {
        val m = LedgerError.PaidRemaining(1050).userMessage(currency = eur)
        assertTrue(m.startsWith("Payer amounts must add up to the expense total."))
        assertTrue(m.contains("Still to allocate"))
        assertTrue(m.contains(MoneyFormatter.format(Money(1050, eur), magnitude = true)))
    }

    @Test fun `paid remaining over allocation`() =
        assertTrue(LedgerError.PaidRemaining(-200).userMessage(currency = eur).contains("Over allocated"))

    @Test fun `paid remaining hides the amount when balances are hidden`() =
        assertEquals("Payer amounts must add up to the expense total.",
            LedgerError.PaidRemaining(1050).userMessage(hidden = true, currency = eur))

    @Test fun `input errors cover payers and participants`() {
        assertEquals("Enter a positive value for each payer and participant.",
            LedgerError.NegativeInput(PersonId("a")).userMessage())
        assertEquals("Enter a value for each payer and participant.",
            LedgerError.MissingInput(PersonId("a")).userMessage())
    }
}
