package io.github.codex07.iou.api.contract

import app.cash.turbine.test
import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

abstract class BackupApiContract : ApiContract() {

    /** Where the implementation can write and read a backup file. */
    protected abstract fun backupTarget(name: String): DocumentRef

    @Test
    fun `the reminder is due until a backup is made`() = runTest {
        setUpMe()
        apis.backup.observeBackupReminder().first().due shouldBe true
        apis.backup.createBackup(backupTarget("a.iou"), "correct horse".toCharArray()).orFail().encrypted shouldBe true
        apis.backup.observeBackupReminder().first().due shouldBe false
    }

    @Test
    fun `a restore and its undo each change the generation, and older tokens and bases stay invalid`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val id = transfer(me, x, eur(1_000))
        val target = backupTarget("b.iou")
        apis.backup.createBackup(target, "pass-12345".toCharArray()).orFail()
        val before = basis()
        val token = apis.ledger.observeEntry(id).first()!!.token

        apis.backup.restoreBackup(target, "pass-12345".toCharArray()).orFail()
        val afterRestore = basis()
        afterRestore shouldNotBe before
        apis.ledger.deleteEntry(id, token) shouldBe LedgerResult.Err(LedgerError.LedgerReplaced)
        apis.people.createPerson(PersonDraft("Y"), before) shouldBe LedgerResult.Err(LedgerError.LedgerReplaced)

        apis.backup.observeUndoRestoreAvailable().first() shouldBe true
        apis.backup.undoLastRestore().orFail()
        basis() shouldNotBe afterRestore
        basis() shouldNotBe before
        apis.ledger.deleteEntry(id, token) shouldBe LedgerResult.Err(LedgerError.LedgerReplaced)
    }

    @Test
    fun `reads re-emit after a restore even when the restored data looks the same`() = runTest {
        setUpMe()
        val target = backupTarget("d.iou")
        apis.backup.createBackup(target, null).orFail()
        apis.people.observePeople().test {
            val before = awaitItem()
            apis.backup.restoreBackup(target, null).orFail()
            awaitItem() shouldBe before
            cancelAndIgnoreRemainingEvents()
        }
        apis.people.observePeople().test {
            val before = awaitItem()
            apis.backup.undoLastRestore().orFail()
            awaitItem() shouldBe before
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `undoing a restore brings back the ledger settings and keeps the device settings`() = runTest {
        setUpMe()
        val target = backupTarget("e.iou")
        apis.backup.createBackup(target, null).orFail()
        apis.backup.restoreBackup(target, null).orFail()
        apis.settings.setHomeCurrency(SEK).orFail()
        apis.settings.setBackupEncryptionDefault(false).orFail()
        apis.settings.setScreenProtection(true).orFail()

        apis.backup.undoLastRestore().orFail()
        val settings = apis.settings.observeSettings().first()
        settings.homeCurrency shouldBe EUR
        settings.backupEncryptionDefault shouldBe true
        settings.screenProtection shouldBe true
    }

    @Test
    fun `a wrong passphrase changes nothing`() = runTest {
        setUpMe()
        val target = backupTarget("c.iou")
        apis.backup.createBackup(target, "pass-12345".toCharArray()).orFail()
        val before = basis()
        apis.backup.restoreBackup(target, "wrong-pass".toCharArray()) shouldBe LedgerResult.Err(LedgerError.WrongPassphraseOrDamagedFile)
        basis() shouldBe before
    }

    @Test
    fun `a passphrase needs at least 8 characters`() = runTest {
        setUpMe()
        apis.backup.createBackup(backupTarget("f.iou"), "short".toCharArray()) shouldBe
            LedgerResult.Err(LedgerError.InvalidField("passphrase"))
        apis.backup.observeBackupReminder().first().due shouldBe true
    }

    @Test
    fun `a document that doesn't exist can't be restored`() = runTest {
        setUpMe()
        val result = apis.backup.restoreBackup(backupTarget("missing.iou"), null)
        (result as LedgerResult.Err).error::class shouldBe LedgerError.FileAccessFailed::class
    }

    @Test
    fun `nothing is undone or reported before any restore`() = runTest {
        setUpMe()
        apis.backup.observeUndoRestoreAvailable().first() shouldBe false
        apis.backup.undoLastRestore() shouldBe LedgerResult.Err(LedgerError.NotFound("restore"))
        apis.backup.observeRestoreInterrupted().first() shouldBe false
    }
}
