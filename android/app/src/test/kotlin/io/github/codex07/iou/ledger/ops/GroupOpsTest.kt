package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.GroupDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GroupOpsTest {

    @Test
    fun `a new group contains me and the canonical members`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val b = ledger.person("B")
        val bAlias = ledger.person("BAlias", mergedInto = b)
        val h = OpsHarness(ledger.state)

        val id = (h.run { GroupOps.createGroup(it, GroupDraft("Trip", EUR), listOf(a, bAlias, ME), h.generation) } as LedgerResult.Ok).value

        h.state.members.values.filter { it.groupId == id }.map { it.personId }.toSet() shouldBe setOf(ME, a, b)
        h.state.groups.getValue(id).fxMode shouldBe FxMode.CONVERT
    }

    @Test
    fun `removing members - never me, never with a balance, re-adding clears removedAt`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val group = ledger.group("Trip", members = listOf(ME, a))
        ledger.transfer(ME, a, eur(1_000), group = group)
        val h = OpsHarness(ledger.state)

        h.run { GroupOps.removeMember(it, group, ME, h.generation) } shouldBe LedgerResult.Err(LedgerError.CannotModifyMe)
        h.run { GroupOps.removeMember(it, group, a, h.generation) } shouldBe
            LedgerResult.Err(LedgerError.MemberHasBalance(group, a, mapOf(EUR to -1_000L)))

        val settled = TestLedger().apply { me() }
        val b = settled.person("B")
        val trip = settled.group("Trip", members = listOf(ME, b))
        settled.transfer(ME, b, eur(1_000), group = trip)
        settled.transfer(b, ME, eur(1_000), group = trip)
        val h2 = OpsHarness(settled.state)

        h2.run { GroupOps.removeMember(it, trip, b, h2.generation) } shouldBe LedgerResult.Ok(Unit)
        IdentityResolver(h2.state).isActiveMember(trip, b) shouldBe false
        h2.revisions.last().reason shouldBe "Removed from group"

        h2.run { GroupOps.addMember(it, trip, b, h2.generation) } shouldBe LedgerResult.Ok(Unit)
        h2.lastPlan!!.changes.single().reason shouldBe "Re-added"
        IdentityResolver(h2.state).isActiveMember(trip, b) shouldBe true
        h2.state.members.values.count { it.groupId == trip && it.personId == b } shouldBe 1

        val revisionsBefore = h2.revisions.size
        h2.run { GroupOps.addMember(it, trip, b, h2.generation) } shouldBe LedgerResult.Ok(Unit)
        h2.lastPlan!!.isEmpty shouldBe true
        h2.revisions.size shouldBe revisionsBefore
    }

    @Test
    fun `delete cascades to memberships including mine, restore brings them back together`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val group = ledger.group("Empty", members = listOf(ME, a))
        val h = OpsHarness(ledger.state)

        h.run { GroupOps.deleteGroup(it, group, h.token(h.state.groups.getValue(group))) } shouldBe LedgerResult.Ok(Unit)
        h.state.members.values.filter { it.groupId == group }.all { it.meta.deletedAt == h.now } shouldBe true

        h.now = h.now.plusSeconds(60)
        h.run { GroupOps.restoreGroup(it, group, h.token(h.state.groups.getValue(group))) } shouldBe LedgerResult.Ok(Unit)
        h.state.members.values.filter { it.groupId == group && it.isDeleted }.shouldBeEmpty()
        IdentityResolver(h.state).isActiveMember(group, ME) shouldBe true
    }

    @Test
    fun `groups with entries cannot be deleted and lock their currency settings`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val group = ledger.group("Trip", fxMode = FxMode.CONVERT, members = listOf(ME, a))
        ledger.transfer(ME, a, eur(100), group = group, deleted = true)
        val h = OpsHarness(ledger.state)
        val token = h.token(h.state.groups.getValue(group))

        h.run { GroupOps.deleteGroup(it, group, token) } shouldBe LedgerResult.Err(LedgerError.StillReferenced(group.value))
        h.run { GroupOps.updateGroup(it, group, GroupDraft("Trip", SEK, FxMode.CONVERT), token) } shouldBe
            LedgerResult.Err(LedgerError.GroupSettingsLocked(group))
        h.run { GroupOps.updateGroup(it, group, GroupDraft("Renamed", EUR, FxMode.CONVERT), token) } shouldBe LedgerResult.Ok(Unit)
        h.run { GroupOps.setGroupArchived(it, group, true, h.token(h.state.groups.getValue(group))) } shouldBe LedgerResult.Ok(Unit)
        h.run { GroupOps.addMember(it, GroupId("nope"), a, h.generation) } shouldBe LedgerResult.Err(LedgerError.NotFound("nope"))
    }
}
