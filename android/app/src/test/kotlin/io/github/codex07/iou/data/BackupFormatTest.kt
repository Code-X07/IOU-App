package io.github.codex07.iou.data

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.fake.FakeApis
import io.github.codex07.iou.fake.SeedData
import io.github.codex07.iou.fx.CachedRate
import io.github.codex07.iou.ledger.model.EntryId
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        text shouldStartWith """{"format":"iou-backup","schemaVersion":2,"appVersion":"0.1.0","createdAt":"2026-01-01T00:00:00Z","rows":{"""
    }

    @Test
    fun `a version-1 backup reads as the same ledger with one payer per expense`() {
        val written = format.write(rows, "0.1.0", T0, null)
        val content = (format.read(written, null) as LedgerResult.Ok).value.content
        val document = Json.parseToJsonElement(written.toString(Charsets.UTF_8)).jsonObject
        val v2Rows = document.getValue("rows").jsonObject
        val payerRows = v2Rows.getValue("payers").jsonArray.map { it.jsonObject }
        // A version-1 backup holds single-payer expenses only: the seed's two-payer expense and its rows stay out.
        val multi = payerRows.groupBy { it.getValue("expenseId") }.filterValues { it.size > 1 }.keys
        val payerOf = payerRows.associate { it.getValue("expenseId") to it.getValue("personId") }
        fun JsonObject.kept(key: String) = getValue(key) !in multi
        fun table(name: String, key: String) = JsonArray(v2Rows.getValue(name).jsonArray.map { it.jsonObject }.filter { it.kept(key) })
        val v1Expenses = v2Rows.getValue("expenses").jsonArray.map { it.jsonObject }.filter { it.kept("id") }.map { expense ->
            JsonObject(expense - "payerSplit" + ("payerPersonId" to payerOf.getValue(expense.getValue("id"))))
        }
        val v1Rows = v2Rows - "payers" + mapOf(
            "expenses" to JsonArray(v1Expenses),
            "shares" to table("shares", "expenseId"),
            "revisions" to table("revisions", "entityId"),
        )
        val v1 = JsonObject(document + mapOf("schemaVersion" to JsonPrimitive(1), "rows" to JsonObject(v1Rows)))

        val read = format.read(v1.toString().toByteArray(), null)

        val left = multi.map { EntryId(it.jsonPrimitive.content) }.toSet()
        left.size shouldBe 1
        (read as LedgerResult.Ok).value.content shouldBe content.copy(
            state = content.state.copy(expenses = content.state.expenses - left),
            revisions = content.revisions.filter { EntryId(it.entityId) !in left },
        )
        read.value.rows.payers.size shouldBe content.state.expenses.size - 1
    }

    @Test
    fun `a version-1 backup with an expense missing its payer is rejected, not thrown`() {
        val document = Json.parseToJsonElement(format.write(rows, "0.1.0", T0, null).toString(Charsets.UTF_8)).jsonObject
        val v2Rows = document.getValue("rows").jsonObject
        val v1Expenses = v2Rows.getValue("expenses").jsonArray.map { JsonObject(it.jsonObject - "payerSplit") }
        val v1 = JsonObject(
            document + mapOf(
                "schemaVersion" to JsonPrimitive(1),
                "rows" to JsonObject(v2Rows - "payers" + ("expenses" to JsonArray(v1Expenses))),
            ),
        )

        format.read(v1.toString().toByteArray(), null).error()::class shouldBe LedgerError.CorruptFile::class
    }

    @Test
    fun `files that aren't readable backups are rejected`() {
        val plain = format.write(rows, "0.1.0", T0, null)
        val text = plain.toString(Charsets.UTF_8)
        format.read(format.write(rows, "0.1.0", T0, passphrase), null).error() shouldBe LedgerError.WrongPassphraseOrDamagedFile
        format.read("""{"hello":1}""".toByteArray(), null).error() shouldBe LedgerError.CorruptFile("Not an IOU backup")
        format.read("PK\u0003\u0004 not json".toByteArray(), null).error()::class shouldBe LedgerError.CorruptFile::class
        format.read(text.replace("\"schemaVersion\":2", "\"schemaVersion\":3").toByteArray(), null).error() shouldBe
            LedgerError.SchemaTooNew(3, 2)
        format.read(text.replace("\"schemaVersion\":2,", "").toByteArray(), null).error()::class shouldBe
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
    fun `a revision whose snapshot is of another row is rejected`() {
        val people = rows.revisions.filter { it.entityType == "PERSON" }
        val a = people.first()
        val b = people.first { it.entityId != a.entityId }
        val group = rows.revisions.first { it.entityType == "GROUP" }
        for (snapshot in listOf(b.snapshotJson, group.snapshotJson)) {
            val swapped = rows.copy(revisions = rows.revisions.map { if (it.id == a.id) it.copy(snapshotJson = snapshot) else it })
            val error = format.read(format.write(swapped, "0.1.0", T0, null), null).error() as LedgerError.CorruptFile
            error.detail shouldContain "snapshot of another row"
        }
        val wrongVersion = rows.copy(revisions = rows.revisions.map { if (it.id == a.id) it.copy(version = it.version + 1) else it })
        format.read(format.write(wrongVersion, "0.1.0", T0, null), null).error()::class shouldBe LedgerError.CorruptFile::class
    }

    @Test
    fun `rows that share a primary key are rejected, not collapsed`() {
        val person = rows.people.first()
        // The earlier copy is invalid; mapping by ID would keep only the valid later one and never check it.
        val people = rows.copy(people = listOf(person.copy(displayName = "")) + rows.people)
        (format.read(format.write(people, "0.1.0", T0, null), null).error() as LedgerError.CorruptFile).detail shouldContain
            "Duplicate person key ${person.id}"
        val revisions = rows.copy(revisions = rows.revisions + rows.revisions.first())
        (format.read(format.write(revisions, "0.1.0", T0, null), null).error() as LedgerError.CorruptFile).detail shouldContain
            "Duplicate entry_revision key"
        val share = rows.shares.first()
        val shares = rows.copy(shares = rows.shares + share.copy(shareMinor = share.shareMinor + 1))
        (format.read(format.write(shares, "0.1.0", T0, null), null).error() as LedgerError.CorruptFile).detail shouldContain
            "Duplicate expense_share key"
    }

    @Test
    fun `cached rates that don't map or aren't valid are rejected`() {
        val rate = rows.rates.single()
        for (bad in listOf(rate.copy(rateDate = "not a date"), rate.copy(base = "ZZZ"), rate.copy(rate = "0"), rate.copy(rate = "10E+2147483647"), rate.copy(rate = "100E+2147483647"))) {
            val backup = format.write(rows.copy(rates = listOf(bad)), "0.1.0", T0, null)
            format.read(backup, null).error()::class shouldBe LedgerError.CorruptFile::class
        }
    }

    @Test
    fun `a participant row of a missing schedule is rejected`() {
        val orphan = rows.copy(participants = rows.participants + RecurringParticipantRow("gone", "SHARE", "p-1", 0, null))

        format.read(format.write(orphan, "0.1.0", T0, null), null).error()::class shouldBe LedgerError.CorruptFile::class
    }
}
