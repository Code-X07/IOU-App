package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.BeforeEach

/** Shared helpers; every suite runs against a fresh, empty ledger (no me yet). */
abstract class ApiContract {

    /** A new implementation with an empty ledger and a clock fixed at [NOW]. */
    protected abstract fun newApis(): Apis

    protected lateinit var apis: Apis

    @BeforeEach
    fun createApis() {
        apis = newApis()
    }

    protected suspend fun setUpMe(name: String = "Me"): PersonId = apis.setup.completeSetup(name, EUR).orFail()

    protected suspend fun basis(): LedgerGeneration = apis.backup.observeLedgerGeneration().first()

    protected suspend fun newPerson(name: String): PersonId = apis.people.createPerson(PersonDraft(name), basis()).orFail()

    protected suspend fun transfer(from: PersonId, to: PersonId, amount: Money, group: GroupId? = null): EntryId =
        apis.ledger.saveTransfer(TransferDraft(from, to, amount, groupId = group, occurredOn = TODAY), SaveMode.Create(basis())).orFail()

    protected fun <T> LedgerResult<T>.orFail(): T = (this as? LedgerResult.Ok)?.value ?: error("Expected Ok, was $this")

    companion object {
        val EUR = CurrencyCode("EUR")
        val SEK = CurrencyCode("SEK")
        val NOW: Instant = Instant.parse("2026-03-01T12:00:00Z")
        val TODAY: LocalDate = LocalDate.parse("2026-03-01")
        fun eur(minor: Long) = Money(minor, EUR)
        fun sek(minor: Long) = Money(minor, SEK)
    }
}
