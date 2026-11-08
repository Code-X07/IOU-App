package io.github.codex07.iou.ledger.model

/** The whole ledger as one immutable value. Soft-deleted rows are included. */
data class LedgerState(
    val people: Map<PersonId, Person> = emptyMap(),
    val handles: Map<HandleId, PaymentHandle> = emptyMap(),
    val groups: Map<GroupId, Group> = emptyMap(),
    val members: Map<MembershipId, GroupMember> = emptyMap(),
    val transfers: Map<EntryId, Transfer> = emptyMap(),
    val expenses: Map<EntryId, Expense> = emptyMap(),
    val fxSnapshots: Map<FxSnapshotId, FxSnapshot> = emptyMap(),
    val schedules: Map<RecurringId, RecurringExpense> = emptyMap(),
) {
    /** The single me person. Throws if the state is corrupt; validators check that first (rule 15). */
    val me: Person get() = people.values.single { it.isMe }

    val meOrNull: Person? get() = people.values.singleOrNull { it.isMe }

    val entries: List<Entry> get() = transfers.values + expenses.values

    fun entry(id: EntryId): Entry? = transfers[id] ?: expenses[id]

    fun entity(type: EntityType, id: String): LedgerEntity? = when (type) {
        EntityType.PERSON -> people[PersonId(id)]
        EntityType.PAYMENT_HANDLE -> handles[HandleId(id)]
        EntityType.GROUP -> groups[GroupId(id)]
        EntityType.GROUP_MEMBER -> members[MembershipId(id)]
        EntityType.TRANSFER -> transfers[EntryId(id)]
        EntityType.EXPENSE -> expenses[EntryId(id)]
        EntityType.RECURRING_EXPENSE -> schedules[RecurringId(id)]
    }

    /** Inserts or replaces [entity] in its table. */
    fun with(entity: LedgerEntity): LedgerState = when (entity) {
        is Person -> copy(people = people + (entity.id to entity))
        is PaymentHandle -> copy(handles = handles + (entity.id to entity))
        is Group -> copy(groups = groups + (entity.id to entity))
        is GroupMember -> copy(members = members + (entity.id to entity))
        is Transfer -> copy(transfers = transfers + (entity.id to entity))
        is Expense -> copy(expenses = expenses + (entity.id to entity))
        is RecurringExpense -> copy(schedules = schedules + (entity.id to entity))
    }

    fun with(snapshot: FxSnapshot): LedgerState = copy(fxSnapshots = fxSnapshots + (snapshot.id to snapshot))

    /** The state a write would produce. */
    fun applying(changes: List<Change>, snapshots: List<FxSnapshot> = emptyList()): LedgerState {
        val withSnapshots = snapshots.fold(this) { state, snapshot -> state.with(snapshot) }
        return changes.fold(withSnapshots) { state, change -> state.with(change.entity) }
    }
}
