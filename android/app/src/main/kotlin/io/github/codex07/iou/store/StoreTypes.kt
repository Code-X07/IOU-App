package io.github.codex07.iou.store

import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.ImportBatch
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.ops.WritePlan
import io.github.codex07.iou.money.CurrencyCode
import java.time.Instant

/** Ledger settings (spec §3.3): stored with the ledger, so a restore swaps them with it. */
data class LedgerSettings(
    val homeCurrency: CurrencyCode = CurrencyCode("EUR"),
    val backupEncryptionDefault: Boolean = true,
)

/** Device-local settings (spec §3.3): never backed up, restored or exported. */
data class DeviceSettings(
    val appLockEnabled: Boolean = false,
    val lockTimeout: LockTimeout = LockTimeout.ONE_MINUTE,
    val screenProtection: Boolean = false,
    val lastManualBackupAt: Instant? = null,
)

/** Everything one ledger database holds; a restore replaces all of it. */
data class LedgerContent(
    val state: LedgerState = LedgerState(),
    val revisions: List<EntryRevision> = emptyList(),
    val importBatches: List<ImportBatch> = emptyList(),
    val ledgerSeq: Long = 0,
    val settings: LedgerSettings = LedgerSettings(),
)

/** The live ledger and the generation it is served under (spec §5.1). */
data class StoreSnapshot(val content: LedgerContent, val generation: LedgerGeneration) {
    val state: LedgerState get() = content.state
    val revisions: List<EntryRevision> get() = content.revisions
    val importBatches: List<ImportBatch> get() = content.importBatches
    val ledgerSeq: Long get() = content.ledgerSeq
    val settings: LedgerSettings get() = content.settings
}

/** What a store write persists, besides the plan's rows: import batches to insert or replace, new settings. */
data class StoreWrite<out T>(
    val value: T,
    val plan: WritePlan,
    val importBatches: List<ImportBatch> = emptyList(),
    val settings: LedgerSettings? = null,
)

/** One committed write, in the order the journal must persist it. [ledgerSeq] and [settings] are the new values. */
data class JournalEntry(
    val changes: List<Change> = emptyList(),
    val snapshots: List<FxSnapshot> = emptyList(),
    val revisions: List<EntryRevision> = emptyList(),
    val importBatches: List<ImportBatch> = emptyList(),
    val ledgerSeq: Long,
    val settings: LedgerSettings,
)

/** Where the store persists committed writes: nowhere for the fake, one Room transaction for the real flavor. */
fun interface LedgerJournal {
    /** Persists [entry] atomically. Throws if it could not; then nothing of it was persisted. */
    suspend fun append(entry: JournalEntry)

    companion object {
        val None = LedgerJournal { }
    }
}

/** Rates for approximate home-currency totals: units of [to] per one unit of [from], or null if unknown. */
fun interface RateLookup {
    fun rate(from: CurrencyCode, to: CurrencyCode): String?

    companion object {
        val None = RateLookup { from, to -> if (from == to) "1" else null }
    }
}
