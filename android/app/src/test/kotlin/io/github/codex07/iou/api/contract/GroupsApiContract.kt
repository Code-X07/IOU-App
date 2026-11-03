package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.GroupDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.VersionDraft
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.FxMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("groups")
abstract class GroupsApiContract : ApiContract() {

    @Test
    fun `a new group contains me, and me cannot be removed`() = runTest {
        val me = setUpMe()
        val a = newPerson("A")
        val group = apis.groups.createGroup(GroupDraft("Trip", EUR), listOf(a), basis()).orFail()

        apis.groups.observeGroup(group).first()!!.members.map { it.person.id }.toSet() shouldBe setOf(me, a)
        apis.groups.removeMember(group, me, basis()) shouldBe LedgerResult.Err(LedgerError.CannotModifyMe)
    }

    @Test
    fun `members with a balance cannot be removed`() = runTest {
        val me = setUpMe()
        val a = newPerson("A")
        val group = apis.groups.createGroup(GroupDraft("Trip", EUR), listOf(a), basis()).orFail()
        transfer(me, a, eur(1_000), group)

        apis.groups.removeMember(group, a, basis()) shouldBe
            LedgerResult.Err(LedgerError.MemberHasBalance(group, a, mapOf(EUR to -1_000L)))
    }

    @Test
    fun `currency settings lock once the group has an entry`() = runTest {
        val me = setUpMe()
        val a = newPerson("A")
        val group = apis.groups.createGroup(GroupDraft("Trip", EUR, FxMode.CONVERT), listOf(a), basis()).orFail()
        transfer(me, a, eur(100), group)
        val detail = apis.groups.observeGroup(group).first()!!

        detail.settingsLocked shouldBe true
        apis.groups.updateGroup(group, GroupDraft("Trip", EUR, FxMode.KEEP), detail.token) shouldBe
            LedgerResult.Err(LedgerError.GroupSettingsLocked(group))
    }

    @Test
    fun `reverting a group past its currency lock conflicts and opens as a draft`() = runTest {
        val me = setUpMe()
        val a = newPerson("A")
        val group = apis.groups.createGroup(GroupDraft("Trip", EUR, FxMode.KEEP), listOf(a), basis()).orFail()
        apis.groups.updateGroup(group, GroupDraft("Trip", EUR, FxMode.CONVERT), apis.groups.observeGroup(group).first()!!.token).orFail()
        transfer(me, a, eur(100), group)
        val ref = EntityRef(EntityType.GROUP, group.value)

        apis.ledger.revertTo(ref, 1, apis.groups.observeGroup(group).first()!!.token)
            .shouldBeInstanceOf<LedgerResult.Err>().error.shouldBeInstanceOf<LedgerError.RestoreConflict>()
        apis.ledger.draftFromVersion(ref, 1).orFail() shouldBe VersionDraft.OfGroup(GroupDraft("Trip", EUR, FxMode.KEEP))
    }

    @Test
    fun `a deleted group can be found again and restored`() = runTest {
        setUpMe()
        val group = apis.groups.createGroup(GroupDraft("Trip", EUR), emptyList(), basis()).orFail()
        apis.groups.deleteGroup(group, apis.groups.observeGroup(group).first()!!.token).orFail()

        apis.groups.observeGroups().first() shouldBe emptyList()
        apis.groups.observeGroups(includeDeleted = true).first().single().deleted shouldBe true
        apis.groups.restoreGroup(group, apis.groups.observeGroup(group).first()!!.token).orFail()
        apis.groups.observeGroups().first().single().group.id shouldBe group
    }
}
