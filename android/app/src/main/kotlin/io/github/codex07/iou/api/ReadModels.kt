package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.Category
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant
import java.time.LocalDate

// ---- Overview (spec §4.3) ----

/**
 * Totals are per currency and always positive magnitudes. The approximate home-currency totals use the
 * latest cached rates, are null when a rate is missing, and must be labelled approximate.
 */
data class Overview(
    val generation: LedgerGeneration,
    val homeCurrency: CurrencyCode,
    val owedToMe: List<Money>,
    val iOwe: List<Money>,
    val approxOwedToMe: Money?,
    val approxIOwe: Money?,
    /** Ordered by absolute approximate home-currency amount, descending. */
    val rows: List<OverviewRow>,
)

sealed interface OverviewRow {
    val archived: Boolean

    /** Absolute approximate value in the home currency; null when a rate is missing. */
    val approxHome: Money?

    /** [balances] = `net(me, X)` per currency: positive means X owes me. */
    data class PersonRow(
        val person: PersonRef,
        val balances: List<Money>,
        override val archived: Boolean,
        override val approxHome: Money?,
    ) : OverviewRow

    /** [myNets] = `groupNet(me)` per currency: positive means the group owes me. */
    data class GroupRow(
        val group: GroupRef,
        val myNets: List<Money>,
        override val archived: Boolean,
        override val approxHome: Money?,
    ) : OverviewRow
}

// ---- People ----

data class PersonSummary(
    val person: PersonRef,
    /** `net(me, X)` per currency, non-group only. */
    val balances: List<Money>,
    val archived: Boolean,
    val deleted: Boolean,
    /** Number of other person records merged into this one. */
    val aliasCount: Int,
)

data class PersonDetail(
    val generation: LedgerGeneration,
    val person: PersonRef,
    val note: String?,
    val defaultCurrency: CurrencyCode?,
    val archived: Boolean,
    val deleted: Boolean,
    val token: EditToken,
    /** Records merged into this person (directly or through other aliases), each with its own token. */
    val aliases: List<AliasView>,
    /** `net(me, X)` per currency, non-group only. */
    val balances: List<Money>,
    /** Per group, the current settle-plan transfers between me and this person: suggestions only. */
    val inGroups: List<GroupSuggestion>,
    val handles: List<PaymentHandleView>,
    /** Entries involving this person, newest first. */
    val activity: List<ActivityItem>,
    /**
     * Newest first: revisions of this person's record, of every record merged into it (each alias's
     * "Merged into" revision included) and of every payment handle those records own, deleted ones
     * included. [RevisionView.entity] says which row each revision belongs to.
     */
    val history: List<RevisionView>,
)

/**
 * A person record merged into the person shown. [mergedInto] is the record it points at directly (the
 * shown person, or another alias in a nested merge). [token] is the alias row's own token: pass it to
 * `PeopleApi.unmerge(person.id, token)` (ADR-0009).
 */
data class AliasView(
    val person: PersonRef,
    val mergedInto: PersonRef,
    val token: EditToken,
)

data class GroupSuggestion(val group: GroupRef, val items: List<PlanItem>)

data class PaymentHandleView(
    val id: HandleId,
    /** The record that owns the handle (may be an alias of the person shown). */
    val owner: PersonRef,
    val type: HandleType,
    val value: String,
    val label: String,
    val holderName: String?,
    val bic: String?,
    /**
     * True for the canonical person's default of this type (spec §3.2). A merged alias's own default
     * shows false here but is kept, and applies again after an unmerge.
     */
    val isDefault: Boolean,
    val token: EditToken,
)

/** What a merge would do; nothing is written (spec §5.6). */
data class MergePreview(
    val source: PersonRef,
    val target: PersonRef,
    val combinedBalances: List<Money>,
    val combinedGroupNets: List<GroupNetView>,
    /**
     * Live entries that move a balance now and none after the merge, e.g. a transfer between the two.
     * Involving both isn't enough: an expense a third person paid for both still counts after the merge.
     */
    val neutralizedEntries: List<ActivityItem>,
    /** The defaults per type that will apply after the merge. */
    val defaultHandles: List<PaymentHandleView>,
)

data class GroupNetView(val group: GroupRef, val nets: List<Money>)

// ---- Groups ----

data class GroupSummary(
    val group: GroupRef,
    val defaultCurrency: CurrencyCode,
    val fxMode: FxMode,
    val archived: Boolean,
    val deleted: Boolean,
    /** `groupNet(me)` per currency. */
    val myNets: List<Money>,
    val activeMemberCount: Int,
)

data class GroupDetail(
    val generation: LedgerGeneration,
    val group: GroupRef,
    val defaultCurrency: CurrencyCode,
    val fxMode: FxMode,
    val archived: Boolean,
    val deleted: Boolean,
    /** True once the group has any entry, deleted ones included (rules 6–7). */
    val settingsLocked: Boolean,
    val token: EditToken,
    val members: List<MemberView>,
    val settlePlan: List<PlanItem>,
    val activity: List<ActivityItem>,
    val flows: List<FlowView>,
    /** Newest first: revisions of the group and of every membership row in it, deleted ones included. */
    val history: List<RevisionView>,
)

data class MemberView(
    val person: PersonRef,
    val active: Boolean,
    /** `groupNet(member)` per currency: positive means the group owes them. */
    val nets: List<Money>,
)

/** Gross "who paid for whom" within a group; informational only (spec §4.3). */
data class FlowView(val paidBy: PersonRef, val paidFor: PersonRef, val amount: Money)

/** One suggested transfer: [from] pays [to]. Built against [generation] (spec §4.6, §6.2). */
data class PlanItem(
    val generation: LedgerGeneration,
    val from: PersonRef,
    val to: PersonRef,
    val amount: Money,
    /** Null for a person settle-up outside groups. */
    val group: GroupRef?,
)

// ---- Activity and entries ----

enum class EntryKind { TRANSFER, EXPENSE }

data class ActivityItem(
    val entryId: EntryId,
    val kind: EntryKind,
    val occurredOn: LocalDate,
    /** Expense description; null for transfers. */
    val description: String?,
    val note: String?,
    /** Expense payer or transfer sender (canonical). */
    val paidBy: PersonRef,
    /** Transfer receiver (canonical); null for expenses. */
    val receiver: PersonRef?,
    /** Expense share holders (canonical, once each); empty for transfers. */
    val participants: List<PersonRef>,
    val transferKind: TransferKind?,
    val category: Category?,
    val group: GroupRef?,
    val paid: Money,
    val effect: Money,
    /**
     * How this entry moves my position: for a non-group entry, the change of `net(me, other)`; for a
     * group entry, the change of `groupNet(me)`. Null when it doesn't touch me.
     */
    val myBalanceEffect: Money?,
    val deleted: Boolean,
)

/** All fields are optional filters; the list is newest first (date, then creation time). */
data class ActivityFilter(
    val personId: PersonId? = null,
    val groupId: GroupId? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val kinds: Set<EntryKind> = EntryKind.entries.toSet(),
    val includeDeleted: Boolean = false,
)

data class EntryDetail(
    val generation: LedgerGeneration,
    val item: ActivityItem,
    val token: EditToken,
    /** Expense shares as stored, per canonical person; empty for transfers. */
    val shares: List<ShareView>,
    val fx: FxSnapshotView?,
    val flows: List<FlowView>,
    /** Pre-filled draft for editing this entry (spec §4.4, §4.5). Save with `SaveMode.Update`. */
    val editDraft: EntryDraft,
    /** Revisions newest first, each with its diff to the previous one. */
    val history: List<RevisionView>,
)

data class ShareView(val person: PersonRef, val share: Money, val inputValue: Long?)

data class FxSnapshotView(
    val base: CurrencyCode,
    val quote: CurrencyCode,
    val rate: String,
    val rateDate: LocalDate,
    val source: FxSource,
    val isStale: Boolean,
    /** Bank-actual snapshots only: the paid and charged amounts the rate came from. */
    val paid: Money?,
    val charged: Money?,
)

/** One revision of [entity]; [subject] names that row for display ("Alex", "IBAN · Main", "Alex in Trip"). */
data class RevisionView(
    val entity: EntityRef,
    val subject: String,
    val version: Int,
    val action: RevisionAction,
    val at: Instant,
    val author: PersonRef,
    val deviceId: DeviceId,
    val reason: String?,
    /** Field-level diff to the previous revision; shares diffed per person. */
    val changes: List<FieldChange>,
    /**
     * The current token of the row [entity] names, for `revertTo(entity, version, token)`. A membership or a
     * deleted handle has no other view carrying it. Null only if the row no longer exists.
     */
    val token: EditToken?,
)

/** Shares a draft would get; [storedShares] is set when editing, so both can be shown (spec §4.4). */
data class SplitPreview(
    val effectTotal: Money,
    val shares: List<ShareView>,
    val storedShares: List<ShareView>?,
    /** False when saving would keep the stored shares unchanged. */
    val recomputed: Boolean,
)
