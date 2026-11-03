package io.github.codex07.iou.ledger.model

enum class HandleType { IBAN, REVTAG, PHONE, OTHER }

enum class FxMode { CONVERT, KEEP }

enum class TransferKind { LOAN, REPAYMENT }

enum class TransferMethod { BANK, CASH, CARD, OTHER }

enum class Category { GROCERIES, DINING, TRANSPORT, ACCOMMODATION, ENTERTAINMENT, UTILITIES, RENT, GENERAL, OTHER }

enum class SplitType { EQUAL, PERCENT, SHARES, EXACT }

enum class FxSource { ECB_FRANKFURTER, USER_ENTERED, BANK_ACTUAL }

enum class EntityType { PERSON, PAYMENT_HANDLE, GROUP, GROUP_MEMBER, TRANSFER, EXPENSE }

enum class RevisionAction { CREATED, UPDATED, DELETED, RESTORED }

enum class ImportSource { OWN_NORMALIZED_CSV }
