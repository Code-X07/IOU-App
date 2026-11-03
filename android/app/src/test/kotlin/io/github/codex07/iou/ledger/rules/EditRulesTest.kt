package io.github.codex07.iou.ledger.rules

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.MergeProblem
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.testMeta
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EditRulesTest {

    private fun edit(before: LedgerState, vararg entities: LedgerEntity, action: RevisionAction = RevisionAction.UPDATED): List<LedgerError> {
        val changes = entities.map { Change(it, action) }
        return LedgerValidator.validate(before, before.applying(changes), WriteOp.Edit(changes)).map { it.error }
    }

    private fun bumped(entity: LedgerEntity, deleted: Boolean? = null): LedgerEntity = entity.withMeta(
        entity.meta.copy(
            version = entity.meta.version + 1,
            deletedAt = when (deleted) { true -> T0; false -> null; null -> entity.meta.deletedAt },
        ),
    )

    @Test
    fun `rule 2 - me cannot be removed from a group`() {
        val ledger = TestLedger()
        ledger.me()
        val group = ledger.group("Trip")
        // A second active row keeps me a member, so only the edit rule (not state invariant 2) can report this.
        ledger.member(group, ME)
        val mine = ledger.state.members.values.first { it.groupId == group && it.personId == ME }
        edit(ledger.state, mine.copy(removedAt = T0)) shouldBe listOf(LedgerError.CannotModifyMe)
    }

    @Test
    fun `rule 5 - entries of removed members are frozen, untouched ones stay valid`() {
        val ledger = TestLedger()
        ledger.me()
        val a = ledger.person("A")
        val group = ledger.group("Trip", members = listOf(ME))
        ledger.member(group, a, removed = true)
        val frozen = ledger.transfer(ME, a, eur(100), group = group)
        ledger.transfer(a, ME, eur(100), group = group)
        val state = ledger.state

        LedgerValidator.validate(state, state, WriteOp.Import).shouldBeEmpty()
        val transfer = state.transfers.getValue(frozen)
        edit(state, transfer.copy(note = "edited")) shouldBe listOf(LedgerError.MemberRemoved(group, setOf(a)))
        // Deleting it would also leave A with a non-zero net (rule 4); rule 5 reports first.
        edit(state, bumped(transfer, deleted = true)).first() shouldBe LedgerError.MemberRemoved(group, setOf(a))

        val other = ledger.person("Other")
        edit(ledger.state, ledger.state.people.getValue(other).copy(note = "unrelated")).shouldBeEmpty()
    }

    @Test
    fun `rules 6 and 7 - currency settings lock once the group has entries`() {
        val ledger = TestLedger()
        ledger.me()
        val convert = ledger.group("Convert", fxMode = FxMode.CONVERT)
        val keep = ledger.group("Keep", fxMode = FxMode.KEEP)
        val x = ledger.person("X")
        ledger.member(convert, x)
        ledger.member(keep, x)
        val state0 = ledger.state

        edit(state0, state0.groups.getValue(convert).copy(fxMode = FxMode.KEEP)).shouldBeEmpty()

        ledger.transfer(ME, x, eur(100), group = convert, deleted = true)
        ledger.transfer(ME, x, eur(100), group = keep)
        val state = ledger.state
        edit(state, state.groups.getValue(convert).copy(fxMode = FxMode.KEEP)) shouldBe
            listOf(LedgerError.GroupSettingsLocked(convert))
        edit(state, state.groups.getValue(convert).copy(defaultCurrency = SEK)) shouldBe
            listOf(LedgerError.GroupSettingsLocked(convert))
        edit(state, state.groups.getValue(keep).copy(defaultCurrency = SEK)).shouldBeEmpty()
        edit(state, state.groups.getValue(keep).copy(fxMode = FxMode.CONVERT)) shouldContain
            LedgerError.GroupSettingsLocked(keep)
    }

    @Test
    fun `rule 9 - me cannot be archived, deleted, merged or be a merge target`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        val me = ledger.state.me
        edit(ledger.state, me.copy(archivedAt = T0)) shouldBe listOf(LedgerError.CannotModifyMe)
        edit(ledger.state, bumped(me, deleted = true)) shouldContain LedgerError.CannotModifyMe
        edit(ledger.state, ledger.state.people.getValue(x).copy(mergedIntoId = ME)) shouldContain LedgerError.CannotModifyMe
    }

    @Test
    fun `rule 10 - referenced people and groups cannot be deleted, own handles and memberships do not block`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        val handle = ledger.handle(x)
        val state = ledger.state
        edit(state, bumped(state.people.getValue(x), deleted = true), bumped(state.handles.getValue(handle), deleted = true))
            .shouldBeEmpty()

        ledger.transfer(ME, x, eur(100), deleted = true)
        val referenced = ledger.state
        edit(referenced, bumped(referenced.people.getValue(x), deleted = true), bumped(referenced.handles.getValue(handle), deleted = true)) shouldContain
            LedgerError.StillReferenced(x.value)

        val ledger2 = TestLedger()
        ledger2.me()
        val y = ledger2.person("Y")
        val group = ledger2.group("Trip", members = listOf(ME, y))
        ledger2.transfer(ME, y, eur(1), group = group, deleted = true)
        val s2 = ledger2.state
        val groupDeletion = listOf(bumped(s2.groups.getValue(group), deleted = true)) +
            s2.members.values.filter { it.groupId == group }.map { bumped(it, deleted = true) }
        edit(s2, *groupDeletion.toTypedArray()) shouldContain LedgerError.StillReferenced(group.value)
        edit(s2, bumped(s2.people.getValue(y), deleted = true)) shouldContain LedgerError.StillReferenced(y.value)

        // Referenced only by a soft-deleted membership row (no entries): isolates the membership clause.
        val ledger3 = TestLedger()
        ledger3.me()
        val z = ledger3.person("Z")
        val group3 = ledger3.group("Trip", members = listOf(ME))
        ledger3.member(group3, z, deleted = true)
        val s3 = ledger3.state
        edit(s3, bumped(s3.people.getValue(z), deleted = true)) shouldBe listOf(LedgerError.StillReferenced(z.value))
    }

    @Test
    fun `rule 11 - merges need two distinct live canonical people`() {
        val ledger = TestLedger()
        ledger.me()
        val a = ledger.person("A")
        val b = ledger.person("B")
        val c = ledger.person("C", mergedInto = b)
        val gone = ledger.person("Gone", deleted = true)
        val state = ledger.state
        val source = state.people.getValue(a)

        edit(state, source.copy(mergedIntoId = b)).shouldBeEmpty()
        // A self-merge is a cycle: structure (rule 15) reports it before anything else. The merge op (Part 6)
        // checks SAME_PERSON before it builds a plan, so users see InvalidMerge(SAME_PERSON).
        edit(state, source.copy(mergedIntoId = a)) shouldBe
            listOf(LedgerError.CorruptData("merge chain of person-a loops or ends at a missing person"))
        edit(state, source.copy(mergedIntoId = c)) shouldContain LedgerError.InvalidMerge(MergeProblem.NOT_CANONICAL)
        edit(state, source.copy(mergedIntoId = gone)) shouldContain LedgerError.InvalidMerge(MergeProblem.DELETED)
        edit(state, state.people.getValue(c).copy(mergedIntoId = a)) shouldContain LedgerError.InvalidMerge(MergeProblem.NOT_CANONICAL)
    }

    @Test
    fun `rule 13 - new and edited transfers need two different people, merged self-transfers may be deleted`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val alexS = ledger.person("AlexS", mergedInto = alex)
        val group = ledger.group("Trip", members = listOf(ME, alex, alexS))
        val selfAfterMerge = ledger.transfer(alex, alexS, eur(100), group = group)
        val state = ledger.state

        val created = Transfer(
            id = EntryId("transfer-new"), fromPersonId = alex, toPersonId = alexS, amount = eur(5), effectAmount = eur(5),
            groupId = group, occurredOn = D0, meta = testMeta(),
        )
        edit(state, created, action = RevisionAction.CREATED) shouldBe listOf(LedgerError.SelfTransfer)
        edit(state, state.transfers.getValue(selfAfterMerge).copy(note = "x")) shouldBe listOf(LedgerError.SelfTransfer)
        edit(state, bumped(state.transfers.getValue(selfAfterMerge), deleted = true), action = RevisionAction.DELETED)
            .shouldBeEmpty()
    }
}
