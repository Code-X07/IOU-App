package io.github.codex07.iou.data

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.fake.FakeRates
import io.github.codex07.iou.fake.SeedData
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.RevisionId
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.ops.EntryOps
import io.github.codex07.iou.ledger.ops.PeopleOps
import io.github.codex07.iou.store.JournalEntry
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.store.LedgerSettings
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.RateLookup
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.TestDatabases
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.eur
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class RoomJournalTest {

    private val db = TestDatabases.inMemory()
    private val journal = RoomJournal { db }

    @AfterEach
    fun close() = db.close()

    private fun store(initial: LedgerContent = LedgerContent()) = LedgerStore(
        Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("room"), DeviceId("device-1"), journal,
        MutableStateFlow(RateLookup(FakeRates::rate)), initial,
    )

    @Test
    fun `an empty database loads as an empty ledger with default settings`() = runTest {
        LedgerLoader.load(db) shouldBe LedgerContent()
    }

    @Test
    fun `the seeded ledger written through the journal loads back identically`() = runTest {
        val store = store()
        SeedData.populate(store)
        store.updateSettings { it.copy(homeCurrency = SEK, backupEncryptionDefault = false) }

        LedgerLoader.load(db) shouldBe store.loaded().content
    }

    @Test
    fun `editing an expense replaces its shares`() = runTest {
        val store = store()
        store.write(beforeSetup = true) { PeopleOps.setup(it, "Me") }
        val x = (store.write { PeopleOps.createPerson(it, PersonDraft("X"), it.generation) } as LedgerResult.Ok).value
        val me = store.loaded().state.me.id
        val draft = ExpenseDraft(me, "Dinner", null, eur(3_000), splitType = SplitType.EXACT,
            participants = listOf(SplitInput(me, 1_000), SplitInput(x, 2_000)), occurredOn = D0)
        val id = (store.write { EntryOps.saveExpense(it, draft, SaveMode.Create(it.generation)) } as LedgerResult.Ok).value
        val edited = draft.copy(participants = listOf(SplitInput(me, 2_500), SplitInput(x, 500)))
        store.write { EntryOps.saveExpense(it, edited, SaveMode.Update(id, EditToken(it.generation, 1))) }

        val loaded = LedgerLoader.load(db).state.expenses.getValue(id)
        loaded.shares.map { it.shareMinor } shouldBe listOf(2_500L, 500L)
        loaded shouldBe store.loaded().state.expenses.getValue(id)
    }

    @Test
    fun `an append that fails part-way leaves no row and no revision`() = runTest {
        val ledger = TestLedger().apply { me() }
        val fx = ledger.fxSnapshot(base = SEK, quote = EUR, rate = "0.09")
        val person = ledger.state.people.getValue(ME)
        val revision = EntryRevision(RevisionId("r-1"), EntityType.PERSON, ME.value, 1, RevisionAction.CREATED, ME, DeviceId("device-1"), T0, snapshot = person)
        val settings = LedgerSettings()
        journal.append(JournalEntry(snapshots = listOf(ledger.state.fxSnapshots.getValue(fx)), ledgerSeq = 1, settings = settings))

        // The person row is written before the revisions; the duplicate revision ID then fails the transaction.
        shouldThrowAny {
            journal.append(JournalEntry(listOf(Change(person, RevisionAction.CREATED)), revisions = listOf(revision, revision), ledgerSeq = 2, settings = settings))
        }
        val loaded = LedgerLoader.load(db)
        loaded.state.people shouldBe emptyMap()
        loaded.revisions shouldBe emptyList()
        loaded.ledgerSeq shouldBe 1
        loaded.state.fxSnapshots.keys shouldBe setOf(fx)
    }
}
