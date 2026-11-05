package io.github.codex07.iou.store

import app.cash.turbine.test
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.map
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.ops.PeopleOps
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerStoreTest {

    /** Records every entry; fails the next append when [failNext] is set. */
    private class RecordingJournal : LedgerJournal {
        val entries = mutableListOf<JournalEntry>()
        var failNext = false

        override suspend fun append(entry: JournalEntry) {
            if (failNext) {
                failNext = false
                error("disk full")
            }
            entries += entry
        }
    }

    private val journal = RecordingJournal()

    private fun store(initial: LedgerContent? = LedgerContent()) =
        LedgerStore(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("store"), DeviceId("device-1"), journal, initial = initial)

    private suspend fun LedgerStore.setUp() = write(beforeSetup = true) { PeopleOps.setup(it, "Me") }

    @Test
    fun `today is the calendar date in the device zone, which may change while the app runs`() {
        // 19:00 UTC on 5 November is already 6 November in Kolkata and still 5 November in New York.
        var zone: ZoneId = ZoneId.of("Asia/Kolkata")
        val store = LedgerStore(
            Clock.fixed(Instant.parse("2026-11-05T19:00:00Z"), ZoneOffset.UTC), SequentialIds("store"), DeviceId("device-1"),
            initial = LedgerContent(), zone = { zone },
        )
        store.today() shouldBe LocalDate.parse("2026-11-06")
        zone = ZoneId.of("America/New_York")
        store.today() shouldBe LocalDate.parse("2026-11-05")
        store.now() shouldBe Instant.parse("2026-11-05T19:00:00Z")
        zone = ZoneOffset.UTC
        store.today() shouldBe LocalDate.parse("2026-11-05")
    }

    @Test
    fun `writes before setup are refused, setup works`() = runTest {
        val store = store()
        store.write { PeopleOps.createPerson(it, PersonDraft("X"), it.generation) } shouldBe LedgerResult.Err(LedgerError.NotFound("me"))
        (store.setUp() is LedgerResult.Ok) shouldBe true
        store.ready.first().state.me.displayName shouldBe "Me"
    }

    @Test
    fun `a successful write journals the plan with one revision per change, then publishes it`() = runTest {
        val store = store()
        store.setUp()
        val seq = store.loaded().ledgerSeq
        val id = (store.write { PeopleOps.createPerson(it, PersonDraft("X"), it.generation) } as LedgerResult.Ok).value

        val snapshot = store.loaded()
        snapshot.state.people.getValue(id).displayName shouldBe "X"
        snapshot.ledgerSeq shouldBe seq + 1
        val revision = snapshot.revisions.last()
        revision.entityId shouldBe id.value
        revision.action shouldBe RevisionAction.CREATED
        revision.deviceId shouldBe DeviceId("device-1")
        revision.at shouldBe T0

        val entry = journal.entries.last()
        entry.changes.single().entity shouldBe snapshot.state.people.getValue(id)
        entry.revisions shouldBe listOf(revision)
        entry.ledgerSeq shouldBe seq + 1
        entry.settings shouldBe snapshot.settings
    }

    @Test
    fun `a rejected write changes nothing and journals nothing`() = runTest {
        val store = store()
        store.setUp()
        val before = store.loaded()
        val journaled = journal.entries.size
        store.write { PeopleOps.createPerson(it, PersonDraft(" "), it.generation) } shouldBe
            LedgerResult.Err(LedgerError.InvalidField("displayName"))
        store.loaded() shouldBe before
        journal.entries.size shouldBe journaled
    }

    @Test
    fun `a write the journal fails to persist is not published`() = runTest {
        val store = store()
        store.setUp()
        val before = store.loaded()
        journal.failNext = true

        shouldThrow<IllegalStateException> { store.write { PeopleOps.createPerson(it, PersonDraft("X"), it.generation) } }
        store.loaded() shouldBe before
        (store.write { PeopleOps.createPerson(it, PersonDraft("X"), it.generation) } is LedgerResult.Ok) shouldBe true
    }

    @Test
    fun `a write or settings change the journal persisted is published even if its caller was cancelled meanwhile`() = runTest {
        // Persists at once, then waits to return, as Room may when it resumes the caller on another dispatcher.
        var returned = CompletableDeferred(Unit)
        val slowJournal = object : LedgerJournal {
            val entries = mutableListOf<JournalEntry>()
            override suspend fun append(entry: JournalEntry) {
                entries += entry
                returned.await()
            }
        }
        val store = LedgerStore(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("store"), DeviceId("device-1"), slowJournal, initial = LedgerContent())
        store.setUp()

        suspend fun cancelWhileJournaling(change: suspend () -> Unit) {
            returned = CompletableDeferred()
            val caller = launch { change() }
            runCurrent()
            caller.cancel()
            returned.complete(Unit)
            caller.join()
        }
        cancelWhileJournaling { store.write { PeopleOps.createPerson(it, PersonDraft("X"), it.generation) } }
        cancelWhileJournaling { store.updateSettings { it.copy(homeCurrency = SEK) } }

        slowJournal.entries.size shouldBe 3
        val snapshot = store.loaded()
        snapshot.state.people.values.map { it.displayName }.sorted() shouldBe listOf("Me", "X")
        snapshot.ledgerSeq shouldBe slowJournal.entries.last().ledgerSeq
        snapshot.settings shouldBe LedgerSettings(homeCurrency = SEK)
    }

    @Test
    fun `setup stores the home currency in the same journal entry`() = runTest {
        val store = store()
        store.writeWith(beforeSetup = true) { snapshot, context ->
            PeopleOps.setup(context, "Me").map { StoreWrite(it.value, it.plan, settings = snapshot.settings.copy(homeCurrency = SEK)) }
        }
        journal.entries.single().settings.homeCurrency shouldBe SEK
        store.loaded().settings.homeCurrency shouldBe SEK
    }

    @Test
    fun `settings changes are journaled without counting as ledger writes`() = runTest {
        val store = store()
        store.updateSettings { it.copy(homeCurrency = SEK) }
        store.updateSettings { it.copy(homeCurrency = SEK) }

        journal.entries.single() shouldBe JournalEntry(ledgerSeq = 0, settings = LedgerSettings(homeCurrency = SEK))
        store.loaded().ledgerSeq shouldBe 0
        store.loaded().settings.homeCurrency shouldBe SEK
    }

    @Test
    fun `an unloaded store serves nothing until a ledger is published`() = runTest {
        val store = store(initial = null)
        val write = async { store.setUp() }
        runCurrent()
        write.isCompleted shouldBe false
        journal.entries shouldBe emptyList()
        val generation = store.newGeneration()

        store.exclusive { live, publish ->
            live shouldBe null
            publish(LedgerContent(), generation)
        }
        (write.await() is LedgerResult.Ok) shouldBe true
        store.loaded().generation shouldBe generation
    }

    @Test
    fun `publishing a replacement re-emits reads even when the data is equal`() = runTest {
        val store = store()
        store.setUp()
        store.read { it.people(includeArchived = false, includeDeleted = false) }.test {
            val before = awaitItem()
            store.exclusive { live, publish -> publish(live!!.content, store.newGeneration()) }
            awaitItem() shouldBe before
            cancelAndIgnoreRemainingEvents()
        }
    }
}
