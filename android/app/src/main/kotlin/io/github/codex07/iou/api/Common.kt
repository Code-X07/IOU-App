package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.money.MoneyParser
import java.time.LocalDate

/**
 * Proof of what an edit was based on: the ledger generation and the entity version it was read at.
 * A token from another generation → `LedgerReplaced`; a version mismatch → `StaleVersion` (spec §5.1).
 */
data class EditToken(val generation: LedgerGeneration, val version: Int)

/** A file chosen through the Storage Access Framework, as a URI string (spec §6.2). */
@JvmInline
value class DocumentRef(val uri: String)

data class PersonRef(val id: PersonId, val displayName: String, val isMe: Boolean = false)

data class GroupRef(val id: GroupId, val name: String)

data class EntityRef(val type: EntityType, val id: String)

/** How an entry draft is saved: as a new entry prepared against [Create.basis], or as an edit. */
sealed interface SaveMode {
    data class Create(val basis: LedgerGeneration) : SaveMode
    data class Update(val id: EntryId, val token: EditToken) : SaveMode
}

/** A rate from [FxApi]: [rate] quote units per one [base] unit, valid for [rateDate] (spec §4.5). */
data class FxQuote(
    val base: CurrencyCode,
    val quote: CurrencyCode,
    val rate: String,
    val rateDate: LocalDate,
    val source: FxSource,
    val isStale: Boolean,
)

/** How a draft converts its paid amount when the effect currency differs (spec §4.5). */
sealed interface FxChoice {
    /** A looked-up (`ECB_FRANKFURTER`) or user-entered (`USER_ENTERED`) rate. */
    data class Rate(val quote: FxQuote) : FxChoice

    /** The amount actually charged, in the effect currency; it becomes the effect as is. */
    data class BankActual(val charged: Money) : FxChoice

    /** Keep the entry's current snapshot (edits only). */
    data object KeepExisting : FxChoice
}

/** Pure parsing helpers for form fields. */
object Inputs {
    fun amount(text: String, currency: CurrencyCode): LedgerResult<Money> =
        MoneyParser.parse(text, currency).toLedgerResult()

    fun currency(text: String): LedgerResult<CurrencyCode> =
        CurrencyCode.parse(text)?.let { LedgerResult.Ok(it) }
            ?: LedgerResult.Err(LedgerError.UnknownCurrency(text.trim()))
}
