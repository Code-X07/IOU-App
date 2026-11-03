package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant
import java.time.LocalDate

// ---- Backup (spec §7.1) ----

/** [due] when no manual backup exists or the last one is older than 30 days. */
data class BackupReminder(val due: Boolean, val lastBackupAt: Instant?)

data class BackupInfo(val encrypted: Boolean, val createdAt: Instant)

// ---- Export (spec §7.2, §7.3) ----

enum class ExportShape { NORMALIZED, REPORT }

enum class ExportFormat { CSV, XLSX }

/** The normalized shape is always the full ledger; other scopes apply to the report shape only. */
sealed interface ExportScope {
    data object FullLedger : ExportScope
    data class Group(val id: GroupId) : ExportScope
    data class Person(val id: PersonId) : ExportScope
}

data class ExportRequest(
    val shape: ExportShape,
    val format: ExportFormat,
    val excelFriendly: Boolean = false,
    val scope: ExportScope = ExportScope.FullLedger,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

data class ExportInfo(val rowCount: Int)

// ---- Import (spec §7.4, §7.5) ----

data class ImportPreview(
    val id: String,
    val fileName: String,
    val generation: LedgerGeneration,
    val ledgerSeq: Long,
    val identity: ImportIdentity,
    val counts: ImportCounts,
    /** Conflicts and rows whose outcome a cascade forces, with field diffs. */
    val rows: List<ImportRow>,
    val invalidRows: List<InvalidRow>,
    val balanceChanges: List<BalanceChange>,
)

sealed interface ImportIdentity {
    data object Same : ImportIdentity

    /** Confirmed: the source's me is mapped to the local me. */
    data class Mapped(val sourceMe: PersonId) : ImportIdentity

    /** Different identity: call `confirmIdentity` before anything else is shown. */
    data class NeedsConfirmation(val sourceMe: PersonId) : ImportIdentity
}

data class ImportCounts(val new: Int, val conflicts: Int, val duplicates: Int, val invalid: Int)

data class ImportRowKey(val type: EntityType, val id: String)

data class ImportRow(
    val key: ImportRowKey,
    val label: String,
    /** Field diff, with the deleted state as a field ("active → deleted"). */
    val changes: List<FieldChange>,
    val choice: ImportRowChoice,
    /** Set by `refreshPreview` for rows that are new or changed since the previous preview. */
    val changedSinceLastPreview: Boolean,
)

sealed interface ImportRowChoice {
    /** The user picks keep local (default) or take incoming. */
    data object KeepOrTake : ImportRowChoice

    /** The owner ends deleted, so this owned row does too; no choice for its deleted state. */
    data class DeletedWith(val owner: String) : ImportRowChoice

    /** The import restores the owner, which restores this row with it. */
    data class RestoredWith(val owner: String) : ImportRowChoice
}

data class InvalidRow(val table: String, val rowNumber: Int, val reason: String, val error: LedgerError?)

/** Balance with a person (or my net in a group) before and after the import, per currency. */
data class BalanceChange(val counterpart: String, val before: List<Money>, val after: List<Money>)

data class ImportChoices(
    val takeIncoming: Set<ImportRowKey> = emptySet(),
    val skipInvalidRows: Boolean = false,
)

data class ImportBatchView(
    val id: ImportBatchId,
    val fileName: String,
    val importedAt: Instant,
    val rowCount: Int,
    val undoneAt: Instant?,
)

data class SkippedRow(val key: ImportRowKey, val reason: String)

/** Undo is best-effort; balances are exactly restored only when [complete] (spec §5.4). */
data class UndoImportResult(val skipped: List<SkippedRow>) {
    val complete: Boolean get() = skipped.isEmpty()
}

// ---- Settings (spec §3.3, §9) ----

enum class LockTimeout { IMMEDIATELY, ONE_MINUTE, FIVE_MINUTES, FIFTEEN_MINUTES }

data class Settings(
    val homeCurrency: CurrencyCode,
    val appLockEnabled: Boolean,
    val lockTimeout: LockTimeout,
    val screenProtection: Boolean,
    val backupEncryptionDefault: Boolean,
)
