package io.github.codex07.iou.ledger.model

import io.github.codex07.iou.money.CurrencyCode
import java.time.Instant
import java.time.LocalDate

/** Immutable once written, never deleted (spec §3.2). */
data class FxSnapshot(
    val id: FxSnapshotId,
    val baseCurrency: CurrencyCode,
    val quoteCurrency: CurrencyCode,
    val rate: String,
    val rateDate: LocalDate,
    val source: FxSource,
    val isStale: Boolean = false,
    val fetchedAt: Instant,
    val paidMinor: Long? = null,
    val chargedMinor: Long? = null,
)

/** One append-only audit record. [snapshot] is the full entity after the write (spec §3.2). */
data class EntryRevision(
    val id: RevisionId,
    val entityType: EntityType,
    val entityId: String,
    val version: Int,
    val action: RevisionAction,
    val authorId: PersonId,
    val deviceId: DeviceId,
    val at: Instant,
    val reason: String? = null,
    val importBatchId: ImportBatchId? = null,
    val snapshot: LedgerEntity,
)

data class ImportBatch(
    val id: ImportBatchId,
    val source: ImportSource,
    val fileName: String,
    val importedAt: Instant,
    val rowCount: Int,
    val undoneAt: Instant? = null,
)

/** One row a write creates or changes, already carrying its new sync columns. One revision each. */
data class Change(
    val entity: LedgerEntity,
    val action: RevisionAction,
    val reason: String? = null,
)

/** One field of a revision diff or an import conflict; values are display strings. */
data class FieldChange(
    val field: String,
    val before: String?,
    val after: String?,
)

/**
 * One participant of a split. [value] is basis points (PERCENT), a weight (SHARES), minor units in the
 * paid currency (EXACT), or ignored (EQUAL).
 */
data class SplitInput(
    val personId: PersonId,
    val value: Long? = null,
)
