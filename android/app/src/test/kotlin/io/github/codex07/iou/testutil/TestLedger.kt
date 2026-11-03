package io.github.codex07.iou.testutil

import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.ExpenseShare
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.FxSnapshotId
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.MembershipId
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.SyncMeta
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant
import java.time.LocalDate

val EUR = CurrencyCode("EUR")
val SEK = CurrencyCode("SEK")
val USD = CurrencyCode("USD")
val JPY = CurrencyCode("JPY")
val BHD = CurrencyCode("BHD")
val IDR = CurrencyCode("IDR")

val T0: Instant = Instant.parse("2026-01-01T00:00:00Z")
val D0: LocalDate = LocalDate.parse("2026-01-01")
val ME = PersonId("person-0-me")

fun eur(minor: Long) = Money(minor, EUR)
fun sek(minor: Long) = Money(minor, SEK)
fun usd(minor: Long) = Money(minor, USD)

/** Deterministic IDs: "<prefix>-1", "<prefix>-2", … */
class SequentialIds(private val prefix: String = "id") : IdGenerator {
    private var counter = 0
    override fun next(): String = "$prefix-${++counter}"
}

fun testMeta(deleted: Boolean = false, version: Int = 1): SyncMeta =
    SyncMeta(createdAt = T0, updatedAt = T0, authorId = ME, deletedAt = if (deleted) T0 else null, version = version)

/** Builds [LedgerState]s for tests. Every builder call returns the new row's ID. */
class TestLedger {
    var state = LedgerState()
        private set

    private var counter = 0
    private fun nextId(prefix: String) = "$prefix-${++counter}"

    fun me(name: String = "Me"): PersonId {
        state = state.with(Person(ME, name, isMe = true, meta = testMeta()))
        return ME
    }

    fun person(
        name: String,
        id: String = "person-${name.lowercase()}",
        mergedInto: PersonId? = null,
        deleted: Boolean = false,
        archived: Boolean = false,
    ): PersonId {
        val personId = PersonId(id)
        state = state.with(
            Person(
                id = personId,
                displayName = name,
                mergedIntoId = mergedInto,
                archivedAt = if (archived) T0 else null,
                meta = testMeta(deleted),
            ),
        )
        return personId
    }

    fun handle(
        person: PersonId,
        type: HandleType = HandleType.IBAN,
        value: String = "DE89370400440532013000",
        isDefault: Boolean = false,
        deleted: Boolean = false,
        id: String = nextId("handle"),
    ): HandleId {
        val handleId = HandleId(id)
        state = state.with(
            PaymentHandle(handleId, person, type, value, label = "$type", isDefault = isDefault, meta = testMeta(deleted)),
        )
        return handleId
    }

    /** A group with active memberships for [members] (me first by default). */
    fun group(
        name: String,
        currency: CurrencyCode = EUR,
        fxMode: FxMode = FxMode.KEEP,
        members: List<PersonId> = listOf(ME),
        deleted: Boolean = false,
        id: String = "group-${name.lowercase()}",
    ): GroupId {
        val groupId = GroupId(id)
        state = state.with(Group(groupId, name, currency, fxMode, meta = testMeta(deleted)))
        members.forEach { member(groupId, it, deleted = deleted) }
        return groupId
    }

    fun member(group: GroupId, person: PersonId, removed: Boolean = false, deleted: Boolean = false): MembershipId {
        val membershipId = MembershipId(nextId("member"))
        state = state.with(
            GroupMember(membershipId, group, person, joinedAt = T0, removedAt = if (removed) T0 else null, meta = testMeta(deleted)),
        )
        return membershipId
    }

    fun fxSnapshot(
        base: CurrencyCode,
        quote: CurrencyCode,
        rate: String,
        source: FxSource = FxSource.USER_ENTERED,
        paidMinor: Long? = null,
        chargedMinor: Long? = null,
    ): FxSnapshotId {
        val snapshotId = FxSnapshotId(nextId("fx"))
        state = state.with(
            FxSnapshot(snapshotId, base, quote, rate, D0, source, fetchedAt = T0, paidMinor = paidMinor, chargedMinor = chargedMinor),
        )
        return snapshotId
    }

    fun transfer(
        from: PersonId,
        to: PersonId,
        amount: Money,
        group: GroupId? = null,
        effect: Money = amount,
        fx: FxSnapshotId? = null,
        kind: TransferKind = TransferKind.LOAN,
        deleted: Boolean = false,
        id: String = nextId("transfer"),
    ): EntryId {
        val entryId = EntryId(id)
        state = state.with(
            Transfer(
                id = entryId, fromPersonId = from, toPersonId = to, amount = amount, effectAmount = effect,
                fxSnapshotId = fx, kind = kind, groupId = group, occurredOn = D0, meta = testMeta(deleted),
            ),
        )
        return entryId
    }

    /** [shares] are final share amounts in the effect currency; inputs are stored as given. */
    fun expense(
        payer: PersonId,
        total: Money,
        shares: Map<PersonId, Long>,
        group: GroupId? = null,
        effectTotal: Money = total,
        fx: FxSnapshotId? = null,
        splitType: SplitType = SplitType.EXACT,
        inputs: Map<PersonId, Long?> = emptyMap(),
        deleted: Boolean = false,
        id: String = nextId("expense"),
    ): EntryId {
        val entryId = EntryId(id)
        state = state.with(
            Expense(
                id = entryId, groupId = group, payerPersonId = payer, description = "Expense $id",
                total = total, effectTotal = effectTotal, fxSnapshotId = fx, splitType = splitType,
                shares = shares.map { (person, share) -> ExpenseShare(person, share, inputs[person]) },
                occurredOn = D0, meta = testMeta(deleted),
            ),
        )
        return entryId
    }
}
