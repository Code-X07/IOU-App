package io.github.codex07.iou.testutil

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.ImportIdentity
import io.github.codex07.iou.api.ImportPreview
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.UndoImportResult
import io.github.codex07.iou.data.RealImportExportApi
import io.github.codex07.iou.io.LedgerExport
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.RevisionId
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.StoreGroupsApi
import io.github.codex07.iou.store.StoreLedgerApi
import io.github.codex07.iou.store.StorePeopleApi
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking

/**
 * One ledger in a [LedgerStore] without persistence or runner, with the real import, export and undo on top
 * (spec §7.4, §7.5, §5.4). Every row of [initial] gets a CREATED revision, as a ledger built through the api
 * would have, so undo can read the pre-import state of the rows an import changed. The clock stands at [now]
 * (UTC); [today] is its date. Writes commit through `LedgerStore.writeWith`, so the cut-off rule and hold
 * resumes apply as in the app.
 */
class ImportBench(initial: LedgerState, val now: Instant = T0.plusSeconds(86_400)) {
    val documents = FileDocuments(createTempDirectory("iou-import").toFile())
    val store = LedgerStore(
        Clock.fixed(now, ZoneOffset.UTC), SequentialIds("bench"), DeviceId("bench"),
        initial = LedgerContent(initial, revisions = created(initial)),
    )
    val io = RealImportExportApi(store, documents, LedgerExport("test"))
    val groups = StoreGroupsApi(store)
    val people = StorePeopleApi(store)
    val ledger = StoreLedgerApi(store)
    val today: LocalDate get() = store.today()

    val state: LedgerState get() = runBlocking { store.loaded().state }

    fun <T> LedgerResult<T>.ok(): T = (this as? LedgerResult.Ok)?.value ?: error("Expected Ok, was $this")

    suspend fun basis(): LedgerGeneration = store.loaded().generation

    suspend fun token(entity: LedgerEntity): EditToken = EditToken(basis(), entity.meta.version)

    /** [source] as a normalized export, changed by [edit], stored as the document [name]. */
    fun export(source: LedgerState, name: String = "export.zip", edit: (ByteArray) -> ByteArray = { it }): DocumentRef {
        val bytes = ByteArrayOutputStream().also { LedgerExport("test").normalized(source, T0, excelFriendly = false, it) }.toByteArray()
        val ref = DocumentRef(name)
        documents.file(ref).also { requireNotNull(it.parentFile).mkdirs() }.writeBytes(edit(bytes))
        return ref
    }

    /** The preview of [source]'s export; an export from another identity is confirmed as the user's own. */
    suspend fun preview(source: LedgerState, name: String = "export.zip", edit: (ByteArray) -> ByteArray = { it }): ImportPreview {
        val preview = io.parseImport(export(source, name, edit)).ok()
        return if (preview.identity is ImportIdentity.NeedsConfirmation) io.confirmIdentity(preview, accept = true).ok() else preview
    }

    suspend fun commit(preview: ImportPreview, choices: ImportChoices = ImportChoices()): ImportBatchId =
        io.commitImport(io.applyChoices(preview, choices), choices).ok()

    suspend fun import(source: LedgerState, choices: ImportChoices = ImportChoices()): ImportBatchId = commit(preview(source), choices)

    suspend fun undo(batch: ImportBatchId): UndoImportResult = io.undoImport(batch).ok()

    private companion object {
        fun created(state: LedgerState): List<EntryRevision> =
            (state.people.values + state.handles.values + state.groups.values + state.members.values + state.entries + state.schedules.values)
                .mapIndexed { i, entity ->
                    EntryRevision(
                        id = RevisionId("initial-$i"), entityType = entity.entityType, entityId = entity.entityId,
                        version = entity.meta.version, action = RevisionAction.CREATED, authorId = ME, deviceId = DeviceId("bench"),
                        at = T0, snapshot = entity,
                    )
                }
    }
}
