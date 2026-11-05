package io.github.codex07.iou.fx

import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.JPY
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.USD
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import org.junit.jupiter.api.Test

class RateTableTest {

    private val day1 = D0
    private val day2 = D0.plusDays(1)

    private fun eur(date: LocalDate, vararg rates: Pair<String, String>) =
        rates.map { (code, rate) -> CachedRate(EUR, CurrencyCode(code), date, rate, T0) }

    private val table = RateTable(eur(day1, "SEK" to "10", "USD" to "1.25"))

    @Test
    fun `cached, inverted and crossed rates on one date`() {
        table.on(day1, EUR, SEK) shouldBe "10"
        table.on(day1, SEK, EUR) shouldBe "0.1"
        table.on(day1, SEK, USD) shouldBe "0.125"
        table.on(day1, USD, SEK) shouldBe "8"
        table.on(day1, USD, USD) shouldBe "1"
    }

    @Test
    fun `a date answers only from its own rates`() {
        table.on(day2, EUR, SEK) shouldBe null
        table.on(day1, EUR, JPY) shouldBe null
        (table + eur(day2, "JPY" to "160")).on(day2, SEK, JPY) shouldBe null
    }

    @Test
    fun `the latest rate comes from the newest date that has one`() {
        val later = table + eur(day2, "SEK" to "11")
        later.latest(SEK, EUR) shouldBe DatedRate("0.09090909090909090909090909090909091", day2)
        later.latest(USD, EUR) shouldBe DatedRate("0.8", day1)
        later.latest(EUR, JPY) shouldBe null
    }

    @Test
    fun `a newer fetch of the same pair and date replaces the old rate`() {
        val replaced = table + eur(day1, "SEK" to "10.5")
        replaced.on(day1, EUR, SEK) shouldBe "10.5"
        replaced.rates.size shouldBe 2
    }
}
