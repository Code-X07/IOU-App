package io.github.codex07.iou.data

import io.github.codex07.iou.ledger.model.Category
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.ExpenseShare
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.FxSnapshotId
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.ImportBatch
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.ImportSource
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.MembershipId
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.ParticipantRole
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.RecurringExpense
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.RecurringParticipant
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.RevisionId
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.SyncMeta
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.ledger.model.TransferMethod
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.JPY
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.testMeta
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.sek
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

/** Every optional field set, instants with nanoseconds: the mapping must be lossless (AC M1-7). */
class RowMappingTest {

    private val at = Instant.parse("2026-02-03T04:05:06.123456789Z")
    private val meta = SyncMeta(at, at.plusSeconds(60), ME, deletedAt = at.plusSeconds(120), version = 3, importBatchId = ImportBatchId("batch-1"))
    private val bare = SyncMeta(at, at, ME)

    private val person = Person(PersonId("p-1"), "Alex", note = "Uni friend", defaultCurrency = SEK, archivedAt = at, mergedIntoId = PersonId("p-2"), meta = meta)
    private val me = Person(ME, "Me", isMe = true, meta = bare)
    private val handle = PaymentHandle(HandleId("h-1"), PersonId("p-1"), HandleType.IBAN, "DE89370400440532013000", "Alex E.", "COBADEFFXXX", "Main", isDefault = true, meta = meta)
    private val group = Group(GroupId("g-1"), "Trip", EUR, FxMode.KEEP, archivedAt = at, meta = meta)
    private val member = GroupMember(MembershipId("m-1"), GroupId("g-1"), PersonId("p-1"), joinedAt = at, removedAt = at.plusSeconds(5), meta = meta)
    private val transfer = Transfer(
        EntryId("t-1"), ME, PersonId("p-1"), sek(100_000), eur(8_765), FxSnapshotId("fx-1"), TransferKind.REPAYMENT,
        TransferMethod.CARD, "Revolut", GroupId("g-1"), LocalDate.parse("2026-02-01"), "Taxi", meta,
    )
    private val expense = Expense(
        EntryId("e-1"), GroupId("g-1"), PayerSplit.EXACT,
        listOf(ExpensePayer(PersonId("p-1"), 1_875, 250), ExpensePayer(ME, 1_125, 150)), "Dinner", Category.DINING,
        Money(400, JPY), eur(3_000), FxSnapshotId("fx-1"),
        SplitType.EXACT,
        listOf(ExpenseShare(PersonId("p-1"), 2_000, 2_000), ExpenseShare(ME, 1_000, 1_000), ExpenseShare(PersonId("p-0"), 0, null)),
        LocalDate.parse("2026-02-02"), "Birthday", meta,
    )
    private val snapshot = FxSnapshot(FxSnapshotId("fx-1"), SEK, EUR, "0.08765", LocalDate.parse("2026-02-01"), FxSource.BANK_ACTUAL, isStale = true, fetchedAt = at, paidMinor = 100_000, chargedMinor = 8_765)
    private val batch = ImportBatch(ImportBatchId("batch-1"), ImportSource.OWN_NORMALIZED_CSV, "export.zip", at, rowCount = 12, undoneAt = at.plusSeconds(1))

    private val entities: List<LedgerEntity> = listOf(person, me, handle, group, member, transfer, expense)

    @Test
    fun `every record survives the trip to a row and back`() {
        person.toRow().toPerson() shouldBe person
        me.toRow().toPerson() shouldBe me
        handle.toRow().toHandle() shouldBe handle
        group.toRow().toGroup() shouldBe group
        member.toRow().toMember() shouldBe member
        transfer.toRow().toTransfer() shouldBe transfer
        expense.toRow().toExpense(expense.shareRows().reversed(), expense.payerRows().reversed()) shouldBe expense
        expense.payerRows().map { it.position } shouldBe listOf(0, 1)
        snapshot.toRow().toSnapshot() shouldBe snapshot
        batch.toRow().toBatch() shouldBe batch
    }

    @Test
    fun `snapshots carry their schema and decode to the same entity`() {
        entities.forEach { entity ->
            val json = SnapshotCodec.encode(entity)
            json shouldContain "\"schema\":2"
            SnapshotCodec.decode(json) shouldBe entity
        }
    }

    @Test
    fun `a snapshot from a newer schema is refused`() {
        val newer = SnapshotCodec.encode(person).replace("\"schema\":2", "\"schema\":3")
        shouldThrow<IllegalArgumentException> { SnapshotCodec.decode(newer) }.message shouldBe "Snapshot schema 3 is newer than 2"
    }

    @Test
    fun `revisions survive the trip to a row and back`() {
        val revision = EntryRevision(
            RevisionId("r-1"), EntityType.EXPENSE, "e-1", 3, RevisionAction.UPDATED, ME, DeviceId("device-1"), at,
            reason = "Reverted to v1", importBatchId = ImportBatchId("batch-1"), snapshot = expense,
        )
        revision.toRow().toRevision() shouldBe revision
    }

    @Test
    fun `a schema-1 expense snapshot reads as one EQUAL payer paying the effect total`() {
        val single = expense.copy(payerSplit = PayerSplit.EQUAL, payers = ExpensePayer.single(PersonId("p-1"), 3_000))
        val row = SnapshotCodec.json.encodeToJsonElement(ExpenseRow.serializer(), single.toRow()).jsonObject
        val v1Row = JsonObject(row - "payerSplit" + ("payerPersonId" to JsonPrimitive("p-1")))
        val v2 = SnapshotCodec.json.parseToJsonElement(SnapshotCodec.encode(single)).jsonObject
        val v1 = JsonObject(v2 - "payers" + mapOf("schema" to JsonPrimitive(1), "expense" to v1Row))

        SnapshotCodec.decode(v1.toString()) shouldBe single
        SnapshotCodec.decode(SnapshotCodec.encode(person).replace("\"schema\":2", "\"schema\":1")) shouldBe person
    }

    @Test
    fun `a schedule maps to its rows and back with each role's order kept, and so does an expense's link`() {
        val schedule = RecurringExpense(
            id = RecurringId("r-1"), groupId = GroupId("g-1"), description = "Rent", category = Category.RENT, note = "Flat",
            total = Money(400, JPY), payerSplit = PayerSplit.EXACT, splitType = SplitType.SHARES,
            participants = listOf(
                RecurringParticipant(ParticipantRole.PAYER, PersonId("p-2"), 300),
                RecurringParticipant(ParticipantRole.PAYER, PersonId("p-1"), 100),
                RecurringParticipant(ParticipantRole.SHARE, PersonId("p-1"), 2),
                RecurringParticipant(ParticipantRole.SHARE, ME, 1),
            ),
            unit = RecurrenceUnit.WEEK, interval = 2, startOn = D0, endOn = D0.plusDays(70), handledThrough = D0.plusDays(14),
            autoPostFrom = D0, autoPost = true, meta = testMeta(),
        )

        schedule.participantRows().map { it.role to it.position } shouldBe listOf("PAYER" to 0, "PAYER" to 1, "SHARE" to 0, "SHARE" to 1)
        schedule.toRow().toSchedule(schedule.participantRows().reversed()) shouldBe schedule
        SnapshotCodec.decode(SnapshotCodec.encode(schedule)) shouldBe schedule

        val posted = expense.copy(recurringId = RecurringId("r-1"), recurringOn = D0)
        posted.toRow().toExpense(posted.shareRows(), posted.payerRows()) shouldBe posted
        SnapshotCodec.decode(SnapshotCodec.encode(posted)) shouldBe posted
    }
}
