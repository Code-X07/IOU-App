package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.SetupState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

abstract class SetupApiContract : ApiContract() {

    @Test
    fun `setup creates me once and sets the home currency`() = runTest {
        apis.setup.observeSetupState().first() shouldBe SetupState.NeedsSetup
        val me = apis.setup.completeSetup("Puneet", SEK).orFail()

        val ready = apis.setup.observeSetupState().first() as SetupState.Ready
        ready.me.id shouldBe me
        ready.me.displayName shouldBe "Puneet"
        apis.settings.observeSettings().first().homeCurrency shouldBe SEK
        apis.setup.completeSetup("Again", EUR) shouldBe LedgerResult.Err(LedgerError.CannotModifyMe)
    }
}
