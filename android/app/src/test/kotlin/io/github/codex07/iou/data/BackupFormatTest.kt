package io.github.codex07.iou.data

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.fake.FakeApis
import io.github.codex07.iou.fake.SeedData
import io.github.codex07.iou.fx.CachedRate
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class BackupFormatTest {

    private val format = BackupFormat(KdfParams(memoryKiB = 1_024, iterations = 1, parallelism = 1))
    private val content = FakeApis.store(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("seed"))
        .also { runBlocking { SeedData.populate(it) } }
        .let { runBlocking { it.loaded().content } }
    private val rows = LedgerRows.of(content, listOf(CachedRate(EUR, SEK, D0, "10.978", T0)))
    private val passphrase = "pass-12345".toCharArray()

    private fun LedgerResult<ValidBackup>.error() = (this as LedgerResult.Err).error

    @Test
    fun `the seeded ledger round-trips encrypted and plain`() {
        for (secret in listOf(passphrase, null)) {
            val backup = (format.read(format.write(rows, "0.1.0", T0, secret), secret) as LedgerResult.Ok).value
            backup.rows shouldBe rows
            backup.content shouldBe content
        }
    }

    @Test
    fun `an empty ledger round-trips too`() {
        val empty = LedgerRows.of(LedgerContent(), emptyList())
        (format.read(format.write(empty, "0.1.0", T0, null), null) as LedgerResult.Ok).value.content shouldBe LedgerContent()
    }

    @Test
    fun `a plain backup is JSON with its schema, app version and time`() {
        val text = format.write(rows, "0.1.0", T0, null).toString(Charsets.UTF_8)
        text shouldStartWith """{"format":"iou-backup","schemaVersion":1,"appVersion":"0.1.0","createdAt":"2026-01-01T00:00:00Z","rows":{"""
    }

    @Test
    fun `files that aren't readable backups are rejected`() {
        val plain = format.write(rows, "0.1.0", T0, null)
        val text = plain.toString(Charsets.UTF_8)
        format.read(format.write(rows, "0.1.0", T0, passphrase), null).error() shouldBe LedgerError.WrongPassphraseOrDamagedFile
        format.read("""{"hello":1}""".toByteArray(), null).error() shouldBe LedgerError.CorruptFile("Not an IOU backup")
        format.read("PK\u0003\u0004 not json".toByteArray(), null).error()::class shouldBe LedgerError.CorruptFile::class
        format.read(text.replace("\"schemaVersion\":1", "\"schemaVersion\":2").toByteArray(), null).error() shouldBe
            LedgerError.SchemaTooNew(2, 1)
        format.read(text.replace("\"schemaVersion\":1,", "").toByteArray(), null).error()::class shouldBe
            LedgerError.CorruptFile::class
        format.read(text.replace("\"people\":[", "\"people\":7,\"x\":[").toByteArray(), null).error()::class shouldBe
            LedgerError.CorruptFile::class
        BackupFormat(maxFileBytes = plain.size - 1L).read(plain, null).error()::class shouldBe LedgerError.LimitsExceeded::class
    }

    @Test
    fun `rows that don't map or break a ledger rule are rejected`() {
        val orphan = LedgerRows.of(LedgerContent(), emptyList()).copy(
            shares = listOf(ShareRow("missing-expense", "person", 0, 100, null)),
        )
        format.read(format.write(orphan, "0.1.0", T0, null), null).error()::class shouldBe LedgerError.CorruptFile::class

        val badKind = rows.copy(transfers = rows.transfers.map { it.copy(kind = "BOGUS") })
        format.read(format.write(badKind, "0.1.0", T0, null), null).error()::class shouldBe LedgerError.CorruptFile::class

        val twoMes = rows.copy(people = rows.people.map { it.copy(isMe = true) })
        (format.read(format.write(twoMes, "0.1.0", T0, null), null).error() as LedgerError.CorruptFile).detail shouldContain "rule 15"
    }

    @Test
    fun `cached rates that don't map or aren't valid are rejected`() {
        val rate = rows.rates.single()
        for (bad in listOf(rate.copy(rateDate = "not a date"), rate.copy(base = "ZZZ"), rate.copy(rate = "0"), rate.copy(rate = "10E+2147483647"), rate.copy(rate = "100E+2147483647"))) {
            val backup = format.write(rows.copy(rates = listOf(bad)), "0.1.0", T0, null)
            format.read(backup, null).error()::class shouldBe LedgerError.CorruptFile::class
        }
    }
}
