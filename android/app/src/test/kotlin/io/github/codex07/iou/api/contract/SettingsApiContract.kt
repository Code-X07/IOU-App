package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.LockTimeout
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

abstract class SettingsApiContract : ApiContract() {

    @Test
    fun `settings start with the spec defaults and changes are observed`() = runTest {
        val settings = apis.settings.observeSettings().first()
        settings.backupEncryptionDefault shouldBe true
        settings.screenProtection shouldBe false
        settings.lockTimeout shouldBe LockTimeout.ONE_MINUTE

        apis.settings.setHomeCurrency(SEK).orFail()
        apis.settings.setLockTimeout(LockTimeout.FIVE_MINUTES).orFail()
        apis.settings.observeSettings().first().homeCurrency shouldBe SEK
        apis.settings.observeSettings().first().lockTimeout shouldBe LockTimeout.FIVE_MINUTES
    }

    @Test
    fun `turning app lock on mid-session doesn't require a lock, and unlocking keeps it clear`() = runTest {
        apis.settings.observeLockRequired().first() shouldBe false
        apis.settings.setAppLockEnabled(true).orFail()
        apis.settings.markUnlocked()
        apis.settings.observeLockRequired().first() shouldBe false
    }
}
