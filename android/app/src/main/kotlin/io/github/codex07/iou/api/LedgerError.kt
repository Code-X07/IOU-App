package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.money.MoneyError
import io.github.codex07.iou.money.MoneyParseResult

/**
 * Every failure the core reports (spec §4.4, §8). Each carries the data a good message or a
 * suggested action needs; the UI maps them to strings.
 */
sealed interface LedgerError {
    // Money and field validation
    data object InvalidAmount : LedgerError
    data class TooManyDecimals(val maxDecimals: Int) : LedgerError
    data object AmountTooLarge : LedgerError
    data class UnknownCurrency(val code: String) : LedgerError
    data class InvalidRate(val rate: String) : LedgerError
    data class ConvertedToZero(val amount: Money, val to: CurrencyCode) : LedgerError
    /** A conversion is needed but no rate was given or none is available: ask the user for one. */
    data class RateRequired(val from: CurrencyCode, val to: CurrencyCode) : LedgerError
    data class InvalidIban(val value: String) : LedgerError
    /** A required text field is blank or a value has the wrong shape (e.g. a Revtag with spaces). */
    data class InvalidField(val field: String) : LedgerError

    // Splits (spec §4.4)
    data object NoParticipants : LedgerError
    data class PercentNotHundred(val sumBp: Long) : LedgerError
    data class ExactRemaining(val diffMinor: Long) : LedgerError
    data object ZeroWeights : LedgerError
    data class NegativeInput(val personId: PersonId) : LedgerError
    data class MissingInput(val personId: PersonId) : LedgerError
    data class WeightOutOfRange(val personId: PersonId, val value: Long) : LedgerError
    data class DuplicateParticipant(val personId: PersonId) : LedgerError

    // Integrity (spec §3.4)
    data class NeedsGroup(val people: Set<PersonId>) : LedgerError
    data class NotGroupMember(val groupId: GroupId, val people: Set<PersonId>) : LedgerError
    data class MemberHasBalance(
        val groupId: GroupId,
        val personId: PersonId,
        val nets: Map<CurrencyCode, Long>,
    ) : LedgerError
    data class MemberRemoved(val groupId: GroupId, val people: Set<PersonId>) : LedgerError
    data class GroupSettingsLocked(val groupId: GroupId) : LedgerError
    data object CannotModifyMe : LedgerError
    data class StillReferenced(val id: String) : LedgerError
    data class DeletedReference(val ids: Set<String>) : LedgerError
    data class InvalidMerge(val problem: MergeProblem) : LedgerError
    data object SelfTransfer : LedgerError

    // Concurrency (spec §5.1)
    data class StaleVersion(val id: String, val expected: Int, val actual: Int) : LedgerError
    data object LedgerReplaced : LedgerError
    data object ImportPreviewStale : LedgerError

    // FX consistency and structure
    data class FxSnapshotMismatch(val entryId: EntryId?) : LedgerError
    data class CorruptData(val detail: String) : LedgerError

    // Revert to version (spec §5.3)
    data class RestoreConflict(val violations: List<RuleViolation>) : LedgerError

    // Backup, restore and import files (M1)
    data object WrongPassphraseOrDamagedFile : LedgerError
    data class CorruptFile(val detail: String) : LedgerError
    data class LimitsExceeded(val detail: String) : LedgerError
    data class SchemaTooNew(val found: Int, val supported: Int) : LedgerError
    data object ForeignLedger : LedgerError

    // Lookups
    data class NotFound(val id: String) : LedgerError
}

enum class MergeProblem { SAME_PERSON, DELETED, IS_ME, NOT_CANONICAL, NOT_MERGED }

/** A violated rule of spec §3.4 (0 = field validity) with the error it produces. */
data class RuleViolation(val rule: Int, val error: LedgerError)

fun MoneyError.toLedgerError(): LedgerError = when (this) {
    MoneyError.InvalidAmount -> LedgerError.InvalidAmount
    is MoneyError.TooManyDecimals -> LedgerError.TooManyDecimals(maxDecimals)
    MoneyError.AmountTooLarge -> LedgerError.AmountTooLarge
}

fun MoneyParseResult.toLedgerResult(): LedgerResult<Money> = when (this) {
    is MoneyParseResult.Ok -> LedgerResult.Ok(money)
    is MoneyParseResult.Err -> LedgerResult.Err(error.toLedgerError())
}
