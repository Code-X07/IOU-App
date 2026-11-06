package io.github.codex07.iou.data

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.HandleDraft
import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.ImportIdentity
import io.github.codex07.iou.api.ImportPreview
import io.github.codex07.iou.api.ImportRowChoice
import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.io.LedgerExport
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.RealHarness
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.editCsv
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.exportTo
import io.github.codex07.iou.testutil.sek
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class RealImportTest {

    @Test
    fun `out of order refresh cannot commit analysis from an older state`() = runTest {
        val backend = harness.start()
        backend.setUp()
        val queued = AtomicReference<Runnable?>()
        val hold = AtomicBoolean(false)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (hold.compareAndSet(true, false)) check(queued.compareAndSet(null, block))
                else Dispatchers.Default.dispatch(context, block)
            }
        }
        val api = RealImportExportApi(backend.store, harness.documents, LedgerExport("test"), computeDispatcher = dispatcher)
        val source = exported(backend) { zip -> editCsv(zip, "people") { rows ->
            rows + rows.first { it["is_me"] == "false" }.toMutableMap().apply {
                putAll(mapOf("id" to "race-person", "display_name" to "Incoming"))
            }
        } }
        val preview = api.parseImport(source).orFail()
        hold.set(true)
        val older = async(start = CoroutineStart.UNDISPATCHED) { api.refreshPreview(preview).orFail() }
        try {
            check(queued.get() != null)
            val localSource = exported(backend) { zip -> editCsv(zip, "people") { rows ->
                rows + rows.first { it["is_me"] == "false" }.toMutableMap().apply {
                    putAll(mapOf("id" to "race-person", "display_name" to "Local"))
                }
            } }
            val localApi = backend.apis.importExport
            localApi.commitImport(localApi.parseImport(localSource).orFail(), ImportChoices()).orFail()
            val current = api.refreshPreview(preview).orFail()
            row(current, EntityType.PERSON, "race-person").choice shouldBe ImportRowChoice.KeepOrTake
            queued.getAndSet(null)!!.run()
            older.await()
            val before = backend.store.loaded()
            api.commitImport(current, ImportChoices()) shouldBe LedgerResult.Err(LedgerError.ImportPreviewStale)
            val after = backend.store.loaded()
            after.state.people.getValue(PersonId("race-person")) shouldBe before.state.people.getValue(PersonId("race-person"))
            after.importBatches shouldBe before.importBatches
            val fresh = api.refreshPreview(current).orFail()
            api.commitImport(fresh, ImportChoices()).orFail()
            backend.store.loaded().state.people.getValue(PersonId("race-person")).displayName shouldBe "Local"
        } finally {
            queued.getAndSet(null)?.run()
            older.await()
        }
    }

    private val harness = RealHarness(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("a"))
    private val other = RealHarness(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("b"))
    private val file = DocumentRef("export.zip")

    @AfterEach
    fun stop() {
        harness.stop()
        other.stop()
    }

    private fun <T> LedgerResult<T>.orFail(): T = (this as? LedgerResult.Ok)?.value ?: error("Expected Ok, was $this")

    private suspend fun RealBackend.basis() = apis.backup.observeLedgerGeneration().first()

    private suspend fun RealBackend.setUp(): Pair<PersonId, PersonId> {
        val me = apis.setup.completeSetup("Me", EUR).orFail()
        return me to apis.people.createPerson(PersonDraft("X"), basis()).orFail()
    }

    private suspend fun RealBackend.transfer(from: PersonId, to: PersonId, amount: io.github.codex07.iou.money.Money, note: String? = null): EntryId =
        apis.ledger.saveTransfer(TransferDraft(from, to, amount, occurredOn = D0, note = note), SaveMode.Create(basis())).orFail()

    /** Exports [backend], lets [edit] change the zip, and stores it where [harness] reads documents. */
    private suspend fun exported(backend: RealBackend, from: RealHarness = harness, edit: (ByteArray) -> ByteArray = { it }): DocumentRef {
        val bytes = edit(backend.apis.exportTo(from.documents, "raw.zip").readBytes())
        harness.documents.file(file).also { requireNotNull(it.parentFile).mkdirs() }.writeBytes(bytes)
        return file
    }

    private fun row(preview: ImportPreview, type: EntityType, id: String) = preview.rows.single { it.key == ImportRowKey(type, id) }

    @Test
    fun `abandoned previews don't keep their sessions`() = runTest {
        val backend = harness.start()
        backend.setUp()
        val api = RealImportExportApi(backend.store, harness.documents, LedgerExport("test"))
        val source = exported(backend)
        val first = api.parseImport(source).orFail()
        val second = api.parseImport(source).orFail()
        api.openSessions shouldBe 1
        api.refreshPreview(first) shouldBe LedgerResult.Err(LedgerError.NotFound("preview"))

        api.discardImport(second)
        api.openSessions shouldBe 0
        api.commitImport(second, ImportChoices()) shouldBe LedgerResult.Err(LedgerError.NotFound("preview"))

        val stranded = api.parseImport(source).orFail()
        val backup = DocumentRef("replace.iou")
        backend.apis.backup.createBackup(backup, null).orFail()
        backend.apis.backup.restoreBackup(backup, null).orFail()
        api.refreshPreview(stranded) shouldBe LedgerResult.Err(LedgerError.LedgerReplaced)
        api.openSessions shouldBe 0
    }

    @Test
    fun `a parse cancelled after its session is installed but before the caller gets the preview keeps no session`() = runTest {
        val backend = harness.start()
        backend.setUp()
        val api = RealImportExportApi(backend.store, harness.documents, LedgerExport("test"))
        val source = exported(backend)
        val delivered = AtomicBoolean(false)
        // The caller runs on the test scheduler: once the parse finishes on its own dispatcher, the caller's resumption
        // waits in that queue, so cancelling before it runs is exactly the window where the preview is never delivered.
        val caller = launch { api.parseImport(source); delivered.set(true) }
        runCurrent()
        val deadline = System.nanoTime() + 10_000_000_000L
        while (api.openSessions == 0) {
            check(System.nanoTime() < deadline) { "parse never installed its session" }
            Thread.sleep(1)
        }
        caller.cancel()
        caller.join()
        delivered.get() shouldBe false
        api.openSessions shouldBe 0
    }

    @Test
    fun `a conflict shows its diff, keeps local unless taken, and records the source file and version`() = runTest {
        val backend = harness.start()
        val (me, x) = backend.setUp()
        val id = backend.transfer(me, x, eur(4_000))
        val source = exported(backend) { zip ->
            editCsv(zip, "transfers") { rows ->
                rows.onEach { it.putAll(mapOf("amount_minor" to "4500", "amount" to "45.00", "effect_amount_minor" to "4500", "effect_amount" to "45.00", "version" to "7")) }
            }
        }
        val api = backend.apis.importExport

        val preview = api.parseImport(source).orFail()
        val conflict = row(preview, EntityType.TRANSFER, id.value)
        conflict.changes shouldContainExactlyInAnyOrder listOf(FieldChange("amount", "40.00 EUR", "45.00 EUR"), FieldChange("effect", "40.00 EUR", "45.00 EUR"))
        conflict.choice shouldBe ImportRowChoice.KeepOrTake
        api.commitImport(preview, ImportChoices()).orFail()
        backend.store.loaded().state.transfers.getValue(id).amount shouldBe eur(4_000)

        val again = api.parseImport(source).orFail()
        api.commitImport(again, ImportChoices(takeIncoming = setOf(conflict.key))).orFail()
        val state = backend.store.loaded()
        state.state.transfers.getValue(id).amount shouldBe eur(4_500)
        state.revisions.last { it.entityId == id.value }.reason shouldBe "Import conflict resolved from export.zip (source v7)"
    }

    @Test
    fun `a transfer deleted in the export shows active to deleted, and taking it deletes it or restores it`() = runTest {
        val backend = harness.start()
        val (me, x) = backend.setUp()
        val id = backend.transfer(me, x, eur(1_000))
        val deleted = exported(backend) { zip -> editCsv(zip, "transfers") { rows -> rows.onEach { it["deleted_at"] = "2026-01-01T00:00:00Z" } } }
        val api = backend.apis.importExport

        val preview = api.parseImport(deleted).orFail()
        row(preview, EntityType.TRANSFER, id.value).changes shouldBe listOf(FieldChange("state", "active", "deleted"))
        api.commitImport(preview, ImportChoices(takeIncoming = setOf(ImportRowKey(EntityType.TRANSFER, id.value)))).orFail()
        backend.store.loaded().state.transfers.getValue(id).isDeleted shouldBe true

        val active = exported(backend) { zip -> editCsv(zip, "transfers") { rows -> rows.onEach { it["deleted_at"] = "" } } }
        val back = api.parseImport(active).orFail()
        row(back, EntityType.TRANSFER, id.value).changes shouldBe listOf(FieldChange("state", "deleted", "active"))
        api.commitImport(back, ImportChoices(takeIncoming = setOf(ImportRowKey(EntityType.TRANSFER, id.value)))).orFail()
        backend.store.loaded().state.transfers.getValue(id).isDeleted shouldBe false
    }

    @Test
    fun `an export from another install is mapped to the local me after confirmation`() = runTest {
        val local = harness.start()
        val (me, _) = local.setUp()
        val second = other.start()
        val (otherMe, y) = second.setUp()
        val note = "=HYPERLINK(\"http://evil\",\"x\")"
        val id = second.transfer(otherMe, y, eur(2_500), note = note)
        val source = exported(second, from = other)
        val api = local.apis.importExport

        val pending = api.parseImport(source).orFail()
        pending.identity shouldBe ImportIdentity.NeedsConfirmation(otherMe)
        val preview = api.confirmIdentity(pending, accept = true).orFail()
        preview.identity shouldBe ImportIdentity.Mapped(otherMe)
        preview.counts.new shouldBe 2
        api.commitImport(preview, ImportChoices()).orFail()

        val state = local.store.loaded()
        val transfer = state.state.transfers.getValue(id)
        (transfer.fromPersonId to transfer.toPersonId) shouldBe (me to y)
        transfer.note shouldBe note
        state.state.people.containsKey(otherMe) shouldBe false
        state.revisions.single { it.entityId == id.value }.reason shouldBe
            "Imported from export.zip (source v1), source identity ${otherMe.value} mapped to me"
    }

    @Test
    fun `deleted people merged into each other are invalid rows`() = runTest {
        val backend = harness.start()
        backend.setUp()
        backend.apis.people.createPerson(PersonDraft("Y"), backend.basis()).orFail()
        val source = exported(backend) { zip ->
            editCsv(zip, "people") { rows ->
                val (x, y) = rows.filter { it["is_me"] == "false" }.map { it.toMutableMap() }
                x.putAll(mapOf("id" to "ghost-1", "merged_into_id" to "ghost-2", "deleted_at" to "2026-01-01T00:00:00Z"))
                y.putAll(mapOf("id" to "ghost-2", "merged_into_id" to "ghost-1", "deleted_at" to "2026-01-01T00:00:00Z"))
                rows + listOf(x, y)
            }
        }

        val preview = backend.apis.importExport.parseImport(source).orFail()
        preview.invalidRows.map { it.rowNumber } shouldContainExactlyInAnyOrder listOf(5, 6)
        preview.invalidRows.forEach { it.error.shouldBeInstanceOf<LedgerError.CorruptData>() }
        backend.apis.importExport.commitImport(preview, ImportChoices()) shouldBe LedgerResult.Err(LedgerError.InvalidField("invalidRows"))
    }

    @Test
    fun `effects that their FX snapshot doesn't explain are invalid rows`() = runTest {
        val backend = harness.start()
        val (me, x) = backend.setUp()
        backend.transfer(me, x, eur(1_000))
        val source = exported(backend) { zip ->
            val withSnapshot = editCsv(zip, "fx_snapshots") {
                listOf(mapOf("id" to "fx-new", "base_currency" to "SEK", "quote_currency" to "EUR", "rate" to "0.09", "rate_date" to "2026-01-01",
                    "source" to "USER_ENTERED", "is_stale" to "false", "fetched_at" to "2026-01-01T00:00:00Z"))
            }
            editCsv(withSnapshot, "transfers") { rows ->
                val template = rows.single()
                listOf(
                    template + mapOf("id" to "t-fx", "amount_minor" to "100000", "amount" to "1000.00", "currency" to "SEK",
                        "effect_amount_minor" to "10000", "effect_amount" to "100.00", "fx_snapshot_id" to "fx-new"),
                    template + mapOf("id" to "t-plain", "effect_amount_minor" to "1500", "effect_amount" to "15.00"),
                )
            }
        }

        val preview = backend.apis.importExport.parseImport(source).orFail()
        preview.invalidRows.map { it.rowNumber to it.error } shouldContainExactlyInAnyOrder listOf(
            2 to LedgerError.FxSnapshotMismatch(EntryId("t-fx")),
            3 to LedgerError.FxSnapshotMismatch(EntryId("t-plain")),
        )
    }

    @Test
    fun `a handle added after the preview makes the commit stale, and the refresh shows it in the cascade`() = runTest {
        val backend = harness.start()
        val (_, x) = backend.setUp()
        val first = backend.apis.people.addHandle(x, HandleDraft(HandleType.IBAN, "DE89370400440532013000", "Main"), backend.basis()).orFail()
        val source = exported(backend) { zip -> editCsv(zip, "people") { rows -> rows.onEach { if (it["id"] == x.value) it["deleted_at"] = "2026-01-01T00:00:00Z" } } }
        val api = backend.apis.importExport
        val take = ImportChoices(takeIncoming = setOf(ImportRowKey(EntityType.PERSON, x.value)))

        val preview = api.applyChoices(api.parseImport(source).orFail(), take)
        row(preview, EntityType.PAYMENT_HANDLE, first.value).choice shouldBe ImportRowChoice.DeletedWith("X")
        val second = backend.apis.people.addHandle(x, HandleDraft(HandleType.REVTAG, "xrev", "Revolut"), backend.basis()).orFail()

        api.commitImport(preview, take) shouldBe LedgerResult.Err(LedgerError.ImportPreviewStale)
        val refreshed = api.refreshPreview(preview).orFail()
        val added = row(refreshed, EntityType.PAYMENT_HANDLE, second.value)
        added.choice shouldBe ImportRowChoice.DeletedWith("X")
        added.changedSinceLastPreview shouldBe true
        api.commitImport(refreshed, take).orFail()

        val state = backend.store.loaded().state
        val deletedAt = state.people.getValue(x).meta.deletedAt
        state.handles.values.map { it.meta.deletedAt } shouldBe listOf(deletedAt, deletedAt)
    }

    @Test
    fun `a person deleted together with the transfers involving them is one valid change`() = runTest {
        val backend = harness.start()
        val (me, x) = backend.setUp()
        val id = backend.transfer(me, x, eur(1_000))
        val deletedAt = "2026-01-01T00:00:00Z"
        val source = exported(backend) { zip ->
            val people = editCsv(zip, "people") { rows -> rows.onEach { if (it["id"] == x.value) it["deleted_at"] = deletedAt } }
            editCsv(people, "transfers") { rows -> rows.onEach { it["deleted_at"] = deletedAt } }
        }
        val api = backend.apis.importExport

        val preview = api.parseImport(source).orFail()
        preview.invalidRows shouldBe emptyList()
        api.commitImport(preview, ImportChoices(takeIncoming = setOf(ImportRowKey(EntityType.PERSON, x.value), ImportRowKey(EntityType.TRANSFER, id.value)))).orFail()
        val state = backend.store.loaded().state
        state.people.getValue(x).isDeleted shouldBe true
        state.transfers.getValue(id).isDeleted shouldBe true
    }

    /**
     * An export of [backend] that deletes [q] while their transfer stays active (rule 10 breaks), and adds a new
     * person `p-new` and a deleted transfer `t-new` from me to [to] (by default `p-new`).
     */
    private suspend fun invalidDeletionBeside(backend: RealBackend, q: PersonId, to: String = "p-new"): DocumentRef = exported(backend) { zip ->
        val people = editCsv(zip, "people") { rows ->
            val added = rows.first { it["id"] == q.value }.toMutableMap().apply { putAll(mapOf("id" to "p-new", "display_name" to "P")) }
            rows.onEach { if (it["id"] == q.value) it["deleted_at"] = "2026-01-01T00:00:00Z" } + added
        }
        editCsv(people, "transfers") { rows ->
            rows + rows.single().toMutableMap().apply { putAll(mapOf("id" to "t-new", "to_person_id" to to, "deleted_at" to "2026-01-01T00:00:00Z")) }
        }
    }

    @Test
    fun `a new person is not blamed for an invalid deletion that leaving them out would hide`() = runTest {
        val backend = harness.start()
        val (me, q) = backend.setUp()
        backend.transfer(me, q, eur(1_000))
        val api = backend.apis.importExport

        val preview = api.parseImport(invalidDeletionBeside(backend, q)).orFail()
        preview.invalidRows.map { it.table } shouldBe listOf("people")
        preview.invalidRows.single().reason shouldContain "breaks rule 10"
        api.commitImport(preview, ImportChoices(skipInvalidRows = true)).orFail()
        val state = backend.store.loaded().state
        state.people.getValue(PersonId("p-new")).isDeleted shouldBe false
        state.transfers.getValue(EntryId("t-new")).isDeleted shouldBe true
        state.people.getValue(q).isDeleted shouldBe false
    }

    @Test
    fun `a deleted transfer naming an unknown person does not hide an invalid deletion`() = runTest {
        val backend = harness.start()
        val (me, q) = backend.setUp()
        backend.transfer(me, q, eur(1_000))
        val api = backend.apis.importExport

        val preview = api.parseImport(invalidDeletionBeside(backend, q, to = "nobody")).orFail()
        preview.invalidRows.map { it.table } shouldContainExactlyInAnyOrder listOf("people", "transfers")
        api.commitImport(preview, ImportChoices(skipInvalidRows = true)).orFail()
        val state = backend.store.loaded().state
        state.people.getValue(PersonId("p-new")).isDeleted shouldBe false
        state.people.getValue(q).isDeleted shouldBe false
    }

    @Test
    fun `a new handle the export shows deleted apart from its locally deleted person stays deleted when the person is restored`() = runTest {
        val backend = harness.start()
        val (_, x) = backend.setUp()
        val handle = backend.apis.people.addHandle(x, HandleDraft(HandleType.IBAN, "DE89370400440532013000", "Main"), backend.basis()).orFail()
        backend.apis.people.deletePerson(x, backend.apis.people.observePerson(x).first()!!.token).orFail()
        // The source added another handle to X, deleted it, and only later deleted X.
        val source = exported(backend) { zip ->
            val people = editCsv(zip, "people") { rows -> rows.onEach { if (it["id"] == x.value) it["deleted_at"] = "2026-02-01T00:00:00Z" } }
            editCsv(people, "payment_handles") { rows -> rows.onEach { it.putAll(mapOf("id" to "h-new", "deleted_at" to "2026-01-01T00:00:00Z")) } }
        }
        val api = backend.apis.importExport
        api.commitImport(api.parseImport(source).orFail(), ImportChoices()).orFail()
        val imported = backend.store.loaded().state.handles.getValue(io.github.codex07.iou.ledger.model.HandleId("h-new"))
        imported.isDeleted shouldBe true

        backend.apis.people.restorePerson(x, backend.apis.people.observePerson(x).first()!!.token).orFail()
        val state = backend.store.loaded().state
        state.handles.getValue(handle).isDeleted shouldBe false
        state.handles.getValue(imported.id).isDeleted shouldBe true
    }

    @Test
    fun `undo of a moved transfer and its old counterparty's deletion puts both back`() = runTest {
        val backend = harness.start()
        val (me, x) = backend.setUp()
        val y = backend.apis.people.createPerson(PersonDraft("Y"), backend.basis()).orFail()
        val id = backend.transfer(me, x, eur(1_000))
        val overview = backend.apis.ledger.observeOverview().first()
        // The source moved the transfer from X to Y, then deleted X, whom nothing referenced any more.
        val source = exported(backend) { zip ->
            val people = editCsv(zip, "people") { rows -> rows.onEach { if (it["id"] == x.value) it["deleted_at"] = "2026-01-01T00:00:00Z" } }
            editCsv(people, "transfers") { rows -> rows.onEach { it["to_person_id"] = y.value } }
        }
        val api = backend.apis.importExport
        val take = ImportChoices(takeIncoming = setOf(ImportRowKey(EntityType.PERSON, x.value), ImportRowKey(EntityType.TRANSFER, id.value)))
        val batch = api.commitImport(api.parseImport(source).orFail(), take).orFail()
        backend.store.loaded().state.transfers.getValue(id).toPersonId shouldBe y

        val result = api.undoImport(batch).orFail()
        result.skipped shouldBe emptyList()
        result.complete shouldBe true
        val state = backend.store.loaded().state
        state.transfers.getValue(id).toPersonId shouldBe x
        state.people.getValue(x).isDeleted shouldBe false
        backend.apis.ledger.observeOverview().first().let { (it.owedToMe to it.iOwe) shouldBe (overview.owedToMe to overview.iOwe) }
    }

    @Test
    fun `a handle the export shows deleted before its person stays deleted when the person is restored`() = runTest {
        val backend = harness.start()
        val (_, x) = backend.setUp()
        val handle = backend.apis.people.addHandle(x, HandleDraft(HandleType.IBAN, "DE89370400440532013000", "Main"), backend.basis()).orFail()
        val source = exported(backend) { zip ->
            val people = editCsv(zip, "people") { rows -> rows.onEach { if (it["id"] == x.value) it["deleted_at"] = "2026-02-01T00:00:00Z" } }
            editCsv(people, "payment_handles") { rows -> rows.onEach { it["deleted_at"] = "2026-01-01T00:00:00Z" } }
        }
        val api = backend.apis.importExport
        api.commitImport(api.parseImport(source).orFail(), ImportChoices(takeIncoming = setOf(ImportRowKey(EntityType.PERSON, x.value)))).orFail()
        backend.store.loaded().state.handles.getValue(handle).isDeleted shouldBe true

        backend.apis.people.restorePerson(x, backend.apis.people.observePerson(x).first()!!.token).orFail()
        val state = backend.store.loaded().state
        state.people.getValue(x).isDeleted shouldBe false
        state.handles.getValue(handle).isDeleted shouldBe true
    }

    @Test
    fun `undo of an import that restored a person and their handle with new values puts the old values back`() = runTest {
        val backend = harness.start()
        val (_, x) = backend.setUp()
        val handle = backend.apis.people.addHandle(x, HandleDraft(HandleType.IBAN, "DE89370400440532013000", "Main"), backend.basis()).orFail()
        backend.apis.people.deletePerson(x, backend.apis.people.observePerson(x).first()!!.token).orFail()
        val source = exported(backend) { zip ->
            val people = editCsv(zip, "people") { rows -> rows.onEach { if (it["id"] == x.value) it.putAll(mapOf("deleted_at" to "", "display_name" to "X renamed")) } }
            editCsv(people, "payment_handles") { rows -> rows.onEach { it.putAll(mapOf("deleted_at" to "", "label" to "Renamed")) } }
        }
        val api = backend.apis.importExport
        val take = ImportChoices(takeIncoming = setOf(ImportRowKey(EntityType.PERSON, x.value), ImportRowKey(EntityType.PAYMENT_HANDLE, handle.value)))
        val batch = api.commitImport(api.parseImport(source).orFail(), take).orFail()
        backend.store.loaded().state.let { (it.people.getValue(x).displayName to it.handles.getValue(handle).label) shouldBe ("X renamed" to "Renamed") }

        val result = api.undoImport(batch).orFail()
        result.skipped shouldBe emptyList()
        result.complete shouldBe true
        val state = backend.store.loaded().state
        val person = state.people.getValue(x)
        val restored = state.handles.getValue(handle)
        (person.displayName to restored.label) shouldBe ("X" to "Main")
        person.isDeleted shouldBe true
        restored.meta.deletedAt shouldBe person.meta.deletedAt
    }

    /** Imports a second install's ledger (person Y with a handle, and a 500 SEK loan to Y) into a set-up local one. */
    private suspend fun importForeign(local: RealBackend): Pair<io.github.codex07.iou.ledger.model.ImportBatchId, PersonId> {
        val second = other.start()
        val (otherMe, y) = second.setUp()
        second.apis.people.addHandle(y, HandleDraft(HandleType.IBAN, "DE89370400440532013000", "Main"), second.basis()).orFail()
        second.transfer(otherMe, y, sek(50_000))
        val source = exported(second, from = other)
        other.stop()
        val api = local.apis.importExport
        val preview = api.confirmIdentity(api.parseImport(source).orFail(), accept = true).orFail()
        return api.commitImport(preview, ImportChoices()).orFail() to y
    }

    @Test
    fun `undo deletes what the import created and the balances are back`() = runTest {
        val local = harness.start()
        val (me, x) = local.setUp()
        val kept = local.transfer(me, x, eur(1_000))
        val overview = local.apis.ledger.observeOverview().first()

        val (batch, y) = importForeign(local)
        local.apis.ledger.observeOverview().first().owedToMe shouldBe listOf(eur(1_000), sek(50_000))
        local.apis.importExport.undoImport(batch).orFail().complete shouldBe true

        val state = local.store.loaded()
        local.apis.ledger.observeOverview().first().let { (it.owedToMe to it.iOwe) shouldBe (overview.owedToMe to overview.iOwe) }
        state.state.people.getValue(y).isDeleted shouldBe true
        state.state.handles.values.single().meta.deletedAt shouldBe state.state.people.getValue(y).meta.deletedAt
        state.state.transfers.getValue(kept).isDeleted shouldBe false
        state.revisions.last().reason shouldBe "Undo import export.zip"
        local.apis.importExport.observeImportBatches().first().single().undoneAt shouldBe T0
    }

    @Test
    fun `undo skips an owner whose owned rows changed since the import`() = runTest {
        val local = harness.start()
        local.setUp()
        val (batch, y) = importForeign(local)
        val handle = local.apis.people.observePerson(y).first()!!.handles.single()
        local.apis.people.updateHandle(handle.id, HandleDraft(HandleType.IBAN, "DE89370400440532013000", "Edited"), handle.token).orFail()

        val result = local.apis.importExport.undoImport(batch).orFail()
        result.skipped.map { it.key to it.reason } shouldContainExactlyInAnyOrder listOf(
            ImportRowKey(EntityType.PERSON, y.value) to "owns rows changed or added since the import",
            ImportRowKey(EntityType.PAYMENT_HANDLE, handle.id.value) to "owns rows changed or added since the import",
        )
        result.complete shouldBe false
        local.store.loaded().state.people.getValue(y).isDeleted shouldBe false
    }
}
