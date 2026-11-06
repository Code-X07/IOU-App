package io.github.codex07.iou.ui.settings

import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.*
import java.time.LocalDate

data class ExportFields(
    val shape: ExportShape = ExportShape.NORMALIZED, val format: ExportFormat = ExportFormat.CSV,
    val scope: String = "full", val from: String = "", val to: String = "", val excelFriendly: Boolean = false,
) {
    fun request(): LedgerResult<ExportRequest> = ledgerResult {
        if (shape == ExportShape.NORMALIZED) ensure(scope == "full" && from.isBlank() && to.isBlank()) { LedgerError.InvalidField("scope") }
        ensure(format == ExportFormat.CSV || !excelFriendly) { LedgerError.InvalidField("exportFormat") }
        fun date(text: String): LocalDate? = if (text.isBlank()) null else try { LocalDate.parse(text) } catch (_: java.time.DateTimeException) { fail(LedgerError.InvalidField("date")) }
        val first = date(from); val last = date(to)
        ensure(first == null || last == null || first <= last) { LedgerError.InvalidField("dateRange") }
        val selection = when {
            scope == "full" -> ExportScope.FullLedger
            scope.startsWith("person:") -> ExportScope.Person(PersonId(scope.substringAfter(':')))
            scope.startsWith("group:") -> ExportScope.Group(GroupId(scope.substringAfter(':')))
            else -> fail(LedgerError.InvalidField("scope"))
        }
        ExportRequest(shape, format, excelFriendly, selection, first, last)
    }
    fun mime() = when { format == ExportFormat.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; shape == ExportShape.NORMALIZED -> "application/zip"; else -> "text/csv" }
    fun extension() = when { format == ExportFormat.XLSX -> "xlsx"; shape == ExportShape.NORMALIZED -> "zip"; else -> "csv" }
}

enum class DocumentOperation { BACKUP, RESTORE, IMPORT, EXPORT }
data class DocumentPickerRequest(val id: Long, val operation: DocumentOperation, val create: Boolean, val mime: String, val name: String, val launched: Boolean = false)
enum class DataConfirmation { RESTORE, UNDO_RESTORE, COMMIT_IMPORT, UNDO_IMPORT }
data class DataState(
    val loading: Boolean = true, val mode: String = "backup", val busy: Boolean = false,
    val error: LedgerError? = null, val message: String? = null,
    val encrypted: Boolean = true, val picker: DocumentPickerRequest? = null, val restoreSelected: Boolean = false,
    val confirmation: DataConfirmation? = null, val preview: ImportPreview? = null,
    val choices: ImportChoices = ImportChoices(), val undoResult: UndoImportResult? = null,
    val export: ExportFields = ExportFields(), val discard: Boolean = false, val exit: Boolean = false,
)
data class DataCatalog(val settings: Settings, val reminder: BackupReminder, val canUndoRestore: Boolean,
                       val batches: List<ImportBatchView>, val people: List<PersonSummary>, val groups: List<GroupSummary>)
