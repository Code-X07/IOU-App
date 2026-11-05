package io.github.codex07.iou.fake

import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.SEK
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FakeRatesTest {

    @Test
    fun `fake rates cross through EUR`() {
        FakeRates.rate(EUR, EUR) shouldBe "1"
        FakeRates.rate(EUR, SEK) shouldBe "11.5"
        FakeRates.rate(SEK, EUR) shouldBe "0.08695652173913043478260869565217391"
    }
}
