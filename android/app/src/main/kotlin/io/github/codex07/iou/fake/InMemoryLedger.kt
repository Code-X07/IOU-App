package io.github.codex07.iou.fake

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.api.Settings
import io.github.codex07.iou.api.map
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.ledger.model.ImportBatch
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.RevisionId
import io.github.codex07.iou.ledger.ops.Planned
import io.github.codex07.iou.ledger.ops.WriteContext
import io.github.codex07.iou.ledger.ops.WritePlan
import io.github.codex07.iou.ledger.views.LedgerViews
import io.github.codex07.iou.money.CurrencyCode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Everything the fake holds, replaced as one value on every change. */
data class FakeSnapshot(
    val state: LedgerState,
    val revisions: List<EntryRevision>,
    val generation: LedgerGeneration,
    val ledgerSeq: Long,
    val settings: Settings,
    val importBatches: List<ImportBatch> = emptyList(),
    val lastBackupAt: Instant? = null,
    val preRestore: RestorePoint? = null,
) {
    /** The ledger-scoped part a restore replaces. */
    fun restorePoint() = RestorePoint(state, revisions, importBatches, settings.homeCurrency, settings.backupEncryptionDefault)

    /** This snapshot with [point]'s ledger in place; device-local settings and the last backup time stay. */
    fun replacedBy(point: RestorePoint) = copy(
        state = point.state,
        revisions = point.revisions,
        importBatches = point.importBatches,
        settings = settings.copy(homeCurrency = point.homeCurrency, backupEncryptionDefault = point.backupEncryptionDefault),
    )
}

/** What a restore swaps (spec §3.3, §7.1): the ledger with its import history and ledger settings. */
data class RestorePoint(
    val state: LedgerState,
    val revisions: List<EntryRevision>,
    val importBatches: List<ImportBatch>,
    val homeCurrency: CurrencyCode,
    val backupEncryptionDefault: Boolean,
)

/** The fake's store: applies validated plans from `ledger.ops` and publishes snapshots. */
class InMemoryLedger(
    val clock: Clock = Clock.systemUTC(),
    val ids: IdGenerator = IdGenerator.Random,
    settings: Settings = DefaultSettings,
) {
    val deviceId = DeviceId(ids.next())
    private val lock = Any()
    private val current = MutableStateFlow(
        FakeSnapshot(LedgerState(), emptyList(), LedgerGeneration(ids.next()), ledgerSeq = 0, settings = settings),
    )

    val snapshot: StateFlow<FakeSnapshot> = current.asStateFlow()

    val views: Flow<LedgerViews> = current.map(::viewsOf)

    /** Views once setup has run; ledger-data reads observe this. */
    val ready: Flow<LedgerViews> = views.filter { it.state.meOrNull != null }

    /** A read of the set-up ledger. It re-emits after every replacement, even when its value compares equal (spec §6.2). */
    fun <T> read(project: (LedgerViews) -> T): Flow<T> = ready.perGeneration({ it.generation }, project)

    /** A read of other replaced state (import history, ledger settings); it re-emits the same way. */
    fun <T> readSnapshot(project: (FakeSnapshot) -> T): Flow<T> = snapshot.perGeneration({ it.generation }, project)

    fun currentViews(): LedgerViews = viewsOf(current.value)

    fun now(): Instant = clock.instant()

    fun today(): LocalDate = LocalDate.now(clock)

    fun newGeneration() = LedgerGeneration(ids.next())

    /** Runs [op] against the current state; on success applies its plan atomically. */
    fun <T> write(beforeSetup: Boolean = false, op: (WriteContext) -> LedgerResult<Planned<T>>): LedgerResult<T> {
        synchronized(lock) {
            val snapshot = current.value
            if (!beforeSetup && snapshot.state.meOrNull == null) return LedgerResult.Err(LedgerError.NotFound("me"))
            val result = op(WriteContext(snapshot.state, snapshot.generation, now(), ids))
            if (result is LedgerResult.Ok && !result.value.plan.isEmpty) current.value = applied(snapshot, result.value.plan)
            return result.map { it.value }
        }
    }

    /** Non-ledger changes (settings, simulated backup and import state). */
    fun update(transform: (FakeSnapshot) -> FakeSnapshot) = synchronized(lock) {
        current.value = transform(current.value)
    }

    private fun applied(snapshot: FakeSnapshot, plan: WritePlan): FakeSnapshot {
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
        return snapshot.copy(
            state = snapshot.state.applying(plan.changes, plan.snapshots),
            revisions = snapshot.revisions + revisions,
            ledgerSeq = snapshot.ledgerSeq + 1,
        )
    }

    private fun viewsOf(snapshot: FakeSnapshot): LedgerViews {
        val home = snapshot.settings.homeCurrency
        return LedgerViews(snapshot.state, snapshot.revisions, snapshot.generation, home) { FakeRates.rate(it, home) }
    }

    private fun <S, T> Flow<S>.perGeneration(generation: (S) -> LedgerGeneration, project: (S) -> T): Flow<T> =
        map { generation(it) to project(it) }.distinctUntilChanged().map { it.second }

    companion object {
        val DefaultSettings = Settings(
            homeCurrency = CurrencyCode("EUR"),
            appLockEnabled = false,
            lockTimeout = LockTimeout.ONE_MINUTE,
            screenProtection = false,
            backupEncryptionDefault = true,
        )
    }
}
