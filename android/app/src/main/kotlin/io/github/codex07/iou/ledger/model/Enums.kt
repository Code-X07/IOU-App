package io.github.codex07.iou.ledger.model

enum class HandleType { IBAN, REVTAG, PHONE, OTHER }

enum class FxMode { CONVERT, KEEP }

enum class TransferKind { LOAN, REPAYMENT }

enum class TransferMethod { BANK, CASH, CARD, OTHER }

enum class Category { GROCERIES, DINING, TRANSPORT, ACCOMMODATION, ENTERTAINMENT, UTILITIES, RENT, GENERAL, OTHER }

enum class SplitType { EQUAL, PERCENT, SHARES, EXACT }

/** How an expense's payer rows divide its total (spec §4.4): evenly, or by exact amounts in the paid currency. */
enum class PayerSplit {
    EQUAL,
    EXACT;

    /** The split type [io.github.codex07.iou.ledger.engine.SplitResolver] resolves payers with. */
    val splitType: SplitType get() = if (this == EQUAL) SplitType.EQUAL else SplitType.EXACT
}

enum class FxSource { ECB_FRANKFURTER, USER_ENTERED, BANK_ACTUAL }

enum class EntityType { PERSON, PAYMENT_HANDLE, GROUP, GROUP_MEMBER, TRANSFER, EXPENSE }

enum class RevisionAction { CREATED, UPDATED, DELETED, RESTORED }

enum class ImportSource { OWN_NORMALIZED_CSV }
