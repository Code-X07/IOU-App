package io.github.codex07.iou.ui.forms

import io.github.codex07.iou.api.*
import io.github.codex07.iou.money.Money
import java.math.BigDecimal

/** Exhaustive UI copy; never shows internal error class names or opaque IDs. */
fun LedgerError.userMessage(hidden: Boolean = false, currency: io.github.codex07.iou.money.CurrencyCode? = null): String = when (this) {
    LedgerError.InvalidAmount -> "Enter a valid amount greater than zero. Use a decimal point or comma, without grouping separators."
    is LedgerError.TooManyDecimals -> "This currency allows up to $maxDecimals decimal places."
    LedgerError.AmountTooLarge -> "The amount is too large. Enter a smaller value."
    is LedgerError.UnknownCurrency -> "Choose a valid three-letter currency code."
    is LedgerError.InvalidRate -> "Enter a positive exchange rate with at most 34 significant digits."
    is LedgerError.ConvertedToZero -> "The conversion is smaller than the currency can represent. Enter the actual bank charge instead."
    is LedgerError.RateRequired -> "A conversion rate from ${from.code} to ${to.code} is required."
    is LedgerError.InvalidIban -> "Enter a valid IBAN. Spaces are allowed."
    is LedgerError.InvalidField -> when (field) {
        "displayName", "name" -> "Enter a name."
        "label" -> "Enter a label for this payment handle. The ledger requires one."
        "description" -> "Enter an expense description."
        "staleRate" -> "Confirm the stale quote or choose a manual rate or actual bank charge."
        "percentage" -> "Enter percentages with at most two decimal places."
        "preview" -> "The preview could not be loaded. Change a field to retry, or try saving to validate again."
        "date" -> "Choose a valid date."
        "value" -> "Enter a valid payment handle. Phone numbers use + and 8–15 digits; Revolut tags cannot contain spaces."
        "participants" -> "Choose the people involved in this transaction."
        else -> "Check the highlighted form fields and try again."
    }
    LedgerError.NoParticipants -> "Choose at least one participant."
    is LedgerError.PercentNotHundred -> if (hidden) "Percentages must total 100%." else "Percentages must total 100%. Current total: ${BigDecimal.valueOf(sumBp, 2).toPlainString()}%."
    is LedgerError.ExactRemaining -> if (hidden || currency == null) "Exact amounts must match the expense total." else {
        val value = io.github.codex07.iou.ui.format.MoneyFormatter.format(Money(diffMinor, currency), magnitude = true)
        "Exact amounts must match the expense total. ${if (diffMinor > 0) "Still to allocate" else "Over allocated"}: $value."
    }
    LedgerError.ZeroWeights -> "Enter positive share weights."
    is LedgerError.NegativeInput -> "Split inputs cannot be negative."
    is LedgerError.MissingInput -> "Enter a split value for each participant."
    is LedgerError.WeightOutOfRange -> "Share weights must be whole numbers from 1 to 1,000,000."
    is LedgerError.DuplicateParticipant -> "Choose each participant only once."
    is LedgerError.NeedsGroup -> "Personal entries must involve you and one other person. Create a group for this selection."
    is LedgerError.NotGroupMember -> "A selected person is not a member of this group. Review the participants."
    is LedgerError.MemberRemoved -> "A selected person is no longer an active group member. Review the participants."
    is LedgerError.MemberHasBalance -> "This member has an outstanding group balance."
    is LedgerError.GroupSettingsLocked -> "This group already has entries. Its currency mode and conversion currency are locked."
    LedgerError.CannotModifyMe -> "Your own identity cannot be changed through this form."
    is LedgerError.StillReferenced -> "This record is used by existing transactions."
    is LedgerError.DeletedReference -> "A selected record was deleted. Review your selection."
    is LedgerError.InvalidMerge -> "These contacts cannot be merged in their current state."
    LedgerError.SelfTransfer -> "Sender and recipient must be different people."
    is LedgerError.StaleVersion -> "This record changed elsewhere. Review the latest version before editing again."
    LedgerError.LedgerReplaced -> "Your ledger was replaced. This unfinished form can no longer be submitted. Return to Overview."
    LedgerError.ImportPreviewStale -> "The ledger changed. Prepare a new preview."
    is LedgerError.FxSnapshotMismatch -> "The stored exchange rate no longer matches this payment. Choose a new quote, manual rate or actual bank charge."
    is LedgerError.CorruptData -> "This record contains inconsistent data and cannot be saved."
    is LedgerError.RestoreConflict -> "This older version conflicts with the current ledger."
    LedgerError.WrongPassphraseOrDamagedFile -> "The passphrase is incorrect or the file is damaged."
    is LedgerError.CorruptFile -> "The file is damaged or unsupported."
    is LedgerError.LimitsExceeded -> "This operation exceeds the supported limits."
    is LedgerError.SchemaTooNew -> "This file needs a newer version of IOU."
    LedgerError.ForeignLedger -> "This file belongs to another ledger."
    is LedgerError.NotFound -> "This record is no longer available."
}
