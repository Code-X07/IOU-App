package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.model.FxSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

abstract class FxApiContract : ApiContract() {

    @Test
    fun `user rates are validated and bank-actual rates are derived`() = runTest {
        apis.fx.userRate(TODAY, EUR, SEK, "0") shouldBe LedgerResult.Err(LedgerError.InvalidRate("0"))
        apis.fx.userRate(TODAY, EUR, SEK, "11.5").orFail().source shouldBe FxSource.USER_ENTERED

        val bank = apis.fx.rateFromCharged(TODAY, sek(100_000), eur(8_765)).orFail()
        bank.rate shouldBe "0.08765"
        bank.source shouldBe FxSource.BANK_ACTUAL
    }

    @Test
    fun `a quote is for the pair asked`() = runTest {
        val quote = apis.fx.quote(TODAY, SEK, EUR).orFail()
        (quote.base to quote.quote) shouldBe (SEK to EUR)
    }
}
