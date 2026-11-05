package io.github.codex07.iou.data

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.fx.FxService
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.store.LedgerStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The steps of a restore and of an undo (spec §7.1), in order. An undo has no [BUILT] step. */
enum class RestoreStep { MARKED, BUILT, LIVE_CLOSED, COMMITTED, CLEANED }

/**
 * Called after each step. Tests use it to inject a failure (throw an `Exception`) or to stand in for process
 * death (throw an `Error`, which skips all clean-up, as a killed process would).
 */
fun interface RestoreHook {
    fun reached(step: RestoreStep)

    companion object {
        val None = RestoreHook { }
    }
}

/**
 * Restore and undo through the database pointer (spec §7.1, ADR-0007). Both hold the store's write lock
 * throughout, so writes in flight finish first, new ones wait, and those that waited then meet the new
 * generation (`LedgerReplaced`). Committing the pointer is the commit point. A failure before it, the commit
 * itself included, deletes what the attempt created and reopens the live database; nothing changed. After it,
 * the operation is complete and is never rolled back (spec §7.1, §8): the new ledger is opened, loaded and
 * published, and a failure there ends like process death after the commit point (see [switchTo]); until a
 * restart, every later restore and undo is then refused. Neither runs cancellably: a caller cancelled
 * half-way would leave the database closed or the pointer switched.
 */
class RestoreProcedure(
    private val store: LedgerStore,
    private val files: DbFiles,
    private val databases: LedgerDatabases,
    private val fx: FxService,
    private val ids: IdGenerator,
    private val hook: RestoreHook,
) {
    private val undo = MutableStateFlow(false)

    /** Set once a switch past the commit point failed: the store no longer matches the pointer until a restart. */
    @Volatile private var stale = false

    /** Whether the pointer names a pre-restore copy. */
    val undoAvailable: StateFlow<Boolean> = undo.asStateFlow()

    /** Reads the pointer again; the backend calls it at start-up. */
    fun refresh() {
        undo.value = files.readPointer()?.previous != null
    }

    /** Steps 2–6 for a backup that passed step 1. */
    suspend fun restore(backup: ValidBackup): LedgerResult<Unit> {
        store.loaded()
        return store.exclusive { _, publish ->
            withContext(NonCancellable) {
                check(!stale) { RESTART_NEEDED }
                val pointer = checkNotNull(files.readPointer())
                val target = files.newDatabaseName(ids.next())
                val next = DbPointer(current = target, previous = pointer.current, generation = ids.next())
                try {
                    files.writeMarker(RestoreMarker(RESTORE, target))
                    hook.reached(RestoreStep.MARKED)
                    databases.build(target) { dao -> backup.rows.writeTo(dao) }
                    hook.reached(RestoreStep.BUILT)
                    databases.close()
                    hook.reached(RestoreStep.LIVE_CLOSED)
                    files.commitPointer(next)
                } catch (e: Exception) {
                    files.deleteDatabase(target)
                    return@withContext abandon(e)
                }
                switchTo(next, publish)
                pointer.previous?.let(files::deleteDatabase)
                finish()
            }
        }
    }

    /** Swaps `current` and `previous`, so an undo can itself be undone. `NotFound("restore")` without a copy. */
    suspend fun undo(): LedgerResult<Unit> {
        store.loaded()
        return store.exclusive { _, publish ->
            withContext(NonCancellable) {
                check(!stale) { RESTART_NEEDED }
                val pointer = checkNotNull(files.readPointer())
                val previous = pointer.previous ?: return@withContext LedgerResult.Err(LedgerError.NotFound("restore"))
                val next = DbPointer(current = previous, previous = pointer.current, generation = ids.next())
                try {
                    files.writeMarker(RestoreMarker(UNDO, previous))
                    hook.reached(RestoreStep.MARKED)
                    databases.close()
                    hook.reached(RestoreStep.LIVE_CLOSED)
                    files.commitPointer(next)
                } catch (e: Exception) {
                    return@withContext abandon(e)
                }
                switchTo(next, publish)
                finish()
            }
        }
    }

    /**
     * After the commit point: opens the database [next] names, loads it, and only then publishes it. The commit
     * stands whatever happens here. If any of it fails, the database is closed and the exception propagates, as
     * process death after the commit point would end it: the store still holds the earlier ledger but no write
     * lands (none has a database), and the next start opens the committed database under the committed pointer's
     * generation and reports nothing, since the marker names the database the pointer does. Until then the
     * procedure is stale: a retried restore or undo throws [RESTART_NEEDED] before touching anything, because
     * one that failed before its own commit point would reopen the committed database ([abandon]) under a store
     * still holding the earlier ledger and generation, and writes would land there again.
     */
    private suspend fun switchTo(next: DbPointer, publish: (LedgerContent, LedgerGeneration) -> Unit) {
        try {
            hook.reached(RestoreStep.COMMITTED)
            val generation = databases.openCurrent()
            val content = LedgerLoader.load(databases.database())
            fx.load()
            publish(content, generation)
            undo.value = next.previous != null
        } catch (e: Exception) {
            stale = true
            databases.close()
            throw e
        }
    }

    private fun finish(): LedgerResult<Unit> {
        files.deleteMarker()
        hook.reached(RestoreStep.CLEANED)
        return LedgerResult.Ok(Unit)
    }

    private fun abandon(failure: Exception): LedgerResult<Unit> {
        files.deleteTempPointer()
        files.deleteMarker()
        if (!databases.isOpen) databases.openCurrent()
        return LedgerResult.Err(LedgerError.RestoreFailed(failure.message ?: failure.toString()))
    }

    companion object {
        const val RESTORE = "restore"
        const val UNDO = "undo"
        const val RESTART_NEEDED = "The ledger switched after a failed load; restart the app"
    }
}
