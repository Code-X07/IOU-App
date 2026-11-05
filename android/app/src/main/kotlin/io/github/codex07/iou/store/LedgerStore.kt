package io.github.codex07.iou.store

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.map
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.RevisionId
import io.github.codex07.iou.ledger.ops.Planned
import io.github.codex07.iou.ledger.ops.WriteContext
import io.github.codex07.iou.ledger.views.LedgerViews
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The ledger as one in-memory value, shared by both flavors (ADR-0010). Writes run a pure op from
 * `ledger.ops` under one lock, persist the validated plan through the [journal], and only then publish
 * it. The fake's journal keeps nothing; the real flavor's writes Room in one transaction.
 *
 * With [initial], the store starts loaded under a fresh generation. Without it, nothing is served until a
 * first [exclusive] block publishes the loaded ledger; reads and writes wait for that.
 */
class LedgerStore(
    val clock: Clock,
    val ids: IdGenerator,
    val deviceId: DeviceId,
    private val journal: LedgerJournal = LedgerJournal.None,
    private val rates: StateFlow<RateLookup> = MutableStateFlow(RateLookup.None),
    initial: LedgerContent? = null,
) {
    private val lock = Mutex()
    private val current = MutableStateFlow(initial?.let { StoreSnapshot(it, newGeneration()) })

    /** The live ledger, from the first load on. */
    val snapshot: Flow<StoreSnapshot> = current.filterNotNull()

    val views: Flow<LedgerViews> = combine(snapshot, rates, ::viewsOf)

    /** Views once setup has run; ledger-data reads observe this. */
    val ready: Flow<LedgerViews> = views.filter { it.state.meOrNull != null }

    /** A read of the set-up ledger. It re-emits after every replacement, even when its value compares equal (spec §6.2). */
    fun <T> read(project: (LedgerViews) -> T): Flow<T> = ready.perGeneration({ it.generation }, project)

    /** A read of other replaced state (import history, ledger settings); it re-emits the same way. */
    fun <T> readSnapshot(project: (StoreSnapshot) -> T): Flow<T> = snapshot.perGeneration({ it.generation }, project)

    /** The live ledger, waiting for the first load if needed. */
    suspend fun loaded(): StoreSnapshot = snapshot.first()

    suspend fun currentViews(): LedgerViews = viewsOf(loaded(), rates.value)

    fun now(): Instant = clock.instant()

    fun today(): LocalDate = LocalDate.now(clock)

    fun newGeneration() = LedgerGeneration(ids.next())

    /** Runs [op] against the live ledger; on success persists and publishes its plan atomically. */
    suspend fun <T> write(
        beforeSetup: Boolean = false,
        op: (WriteContext) -> LedgerResult<Planned<T>>,
    ): LedgerResult<T> = writeWith(beforeSetup) { _, context -> op(context).map { StoreWrite(it.value, it.plan) } }

    /**
     * Like [write], for writes that also record import batches or change ledger settings, or that check
     * more of the snapshot than [WriteContext] carries (e.g. an import preview's `ledgerSeq`).
     */
    suspend fun <T> writeWith(
        beforeSetup: Boolean = false,
        op: (StoreSnapshot, WriteContext) -> LedgerResult<StoreWrite<T>>,
    ): LedgerResult<T> {
        loaded()
        return lock.withLock {
            val snapshot = checkNotNull(current.value)
            if (!beforeSetup && snapshot.state.meOrNull == null) return@withLock LedgerResult.Err(LedgerError.NotFound("me"))
            val result = op(snapshot, WriteContext(snapshot.state, snapshot.generation, now(), ids))
            if (result is LedgerResult.Ok) commit(snapshot, result.value)
            result.map { it.value }
        }
    }

    /** Changes ledger settings outside any ledger write; `ledgerSeq` stays (spec §5.1 counts ledger writes). */
    suspend fun updateSettings(transform: (LedgerSettings) -> LedgerSettings) {
        loaded()
        lock.withLock {
            val snapshot = checkNotNull(current.value)
            val settings = transform(snapshot.settings)
            if (settings == snapshot.settings) return@withLock
            persist(JournalEntry(ledgerSeq = snapshot.ledgerSeq, settings = settings), snapshot.copy(content = snapshot.content.copy(settings = settings)))
        }
    }

    /**
     * Runs [block] holding the write lock, so no write is in flight and none starts until it returns.
     * [block] gets the live ledger (null before the first load) and may call `publish` to replace it:
     * the first load, a restore, or an undo of a restore, each under its own generation.
     */
    suspend fun <T> exclusive(block: suspend (live: StoreSnapshot?, publish: (LedgerContent, LedgerGeneration) -> Unit) -> T): T =
        lock.withLock { block(current.value) { content, generation -> current.value = StoreSnapshot(content, generation) } }

    private suspend fun commit(snapshot: StoreSnapshot, write: StoreWrite<*>) {
        val plan = write.plan
        if (plan.isEmpty && write.importBatches.isEmpty() && write.settings == null) return
        val at = now()
        val revisions = plan.changes.map { change ->
            val entity = change.entity
            EntryRevision(
                id = RevisionId(ids.next()), entityType = entity.entityType, entityId = entity.entityId,
                version = entity.meta.version, action = change.action, authorId = entity.meta.authorId,
                deviceId = deviceId, at = at, reason = change.reason, importBatchId = entity.meta.importBatchId,
                snapshot = entity,
            )
        }
        val ledgerWrite = !plan.isEmpty || write.importBatches.isNotEmpty()
        val content = snapshot.content
        val ledgerSeq = if (ledgerWrite) content.ledgerSeq + 1 else content.ledgerSeq
        val settings = write.settings ?: content.settings
        val batchIds = write.importBatches.map { it.id }.toSet()
        val next = snapshot.copy(
            content = content.copy(
                state = content.state.applying(plan.changes, plan.snapshots),
                revisions = content.revisions + revisions,
                importBatches = content.importBatches.filterNot { it.id in batchIds } + write.importBatches,
                ledgerSeq = ledgerSeq,
                settings = settings,
            ),
        )
        persist(JournalEntry(plan.changes, plan.snapshots, revisions, write.importBatches, ledgerSeq, settings), next)
    }

    /**
     * Persists [entry], then publishes [next]. Not cancellable: the journal may commit and still throw on the
     * way back to a cancelled caller (Room resumes it on another dispatcher), and the store must then hold what
     * the database holds. A failed append publishes nothing.
     */
    private suspend fun persist(entry: JournalEntry, next: StoreSnapshot) = withContext(NonCancellable) {
        journal.append(entry)
        current.value = next
    }

    private fun viewsOf(snapshot: StoreSnapshot, rates: RateLookup): LedgerViews {
        val home = snapshot.settings.homeCurrency
        return LedgerViews(snapshot.state, snapshot.revisions, snapshot.generation, home) { rates.rate(it, home) }
    }

    private fun <S, T> Flow<S>.perGeneration(generation: (S) -> LedgerGeneration, project: (S) -> T): Flow<T> =
        map { generation(it) to project(it) }.distinctUntilChanged().map { it.second }
}
