package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class IdentityResolverTest {

    @Test
    fun `canonical follows the merge chain to the end`() {
        val ledger = TestLedger()
        ledger.me()
        val smith = ledger.person("Smith")
        val alexS = ledger.person("AlexS", mergedInto = smith)
        val alex = ledger.person("Alex", mergedInto = alexS)
        val resolver = IdentityResolver(ledger.state)

        resolver.canonical(alex) shouldBe smith
        resolver.canonical(alexS) shouldBe smith
        resolver.canonical(smith) shouldBe smith
        resolver.canonical(ME) shouldBe ME
        resolver.recordsOf(smith) shouldBe setOf(smith, alexS, alex)
        resolver.recordsOf(ME) shouldBe setOf(ME)
        resolver.brokenChains.shouldBeEmpty()
    }

    @Test
    fun `unknown ids resolve to themselves`() {
        val resolver = IdentityResolver(TestLedger().state)
        resolver.canonical(PersonId("nobody")) shouldBe PersonId("nobody")
        resolver.recordsOf(PersonId("nobody")) shouldBe setOf(PersonId("nobody"))
    }

    @Test
    fun `merge cycles and dangling pointers are reported, not looped over`() {
        val ledger = TestLedger()
        ledger.me()
        ledger.person("A", mergedInto = PersonId("person-b"), deleted = true)
        ledger.person("B", mergedInto = PersonId("person-a"), deleted = true)
        val c = ledger.person("C", mergedInto = PersonId("missing"))
        val resolver = IdentityResolver(ledger.state)

        resolver.brokenChains shouldBe setOf(PersonId("person-a"), PersonId("person-b"), c)
        resolver.canonical(PersonId("person-a")) shouldBe PersonId("person-a")
        resolver.canonical(c) shouldBe c
    }

    @Test
    fun `handles are the union over all records, deleted ones excluded`() {
        val ledger = TestLedger()
        ledger.me()
        val target = ledger.person("Target")
        val alias = ledger.person("Alias", mergedInto = target)
        val own = ledger.handle(target, HandleType.REVTAG, "target", id = "handle-b")
        val aliased = ledger.handle(alias, HandleType.IBAN, id = "handle-a")
        ledger.handle(alias, HandleType.PHONE, "+46700000000", deleted = true, id = "handle-c")
        val resolver = IdentityResolver(ledger.state)

        resolver.handlesOf(target).map { it.id } shouldBe listOf(aliased, own)
    }

    @Test
    fun `the canonical record's own default wins, otherwise the lowest alias UUID`() {
        val ledger = TestLedger()
        ledger.me()
        val target = ledger.person("Target", id = "person-t")
        val aliasB = ledger.person("B", id = "person-b", mergedInto = target)
        val aliasA = ledger.person("A", id = "person-a", mergedInto = target)
        val fromB = ledger.handle(aliasB, HandleType.IBAN, isDefault = true, id = "handle-1")
        val fromA = ledger.handle(aliasA, HandleType.IBAN, isDefault = true, id = "handle-2")

        IdentityResolver(ledger.state).defaultHandle(target, HandleType.IBAN)?.id shouldBe fromA

        val own = ledger.handle(target, HandleType.IBAN, isDefault = true, id = "handle-3")
        IdentityResolver(ledger.state).defaultHandle(target, HandleType.IBAN)?.id shouldBe own
        IdentityResolver(ledger.state).defaultHandle(target, HandleType.REVTAG) shouldBe null
        IdentityResolver(ledger.state).handlesOf(target).map { it.id } shouldBe listOf(fromB, fromA, own)
    }

    @Test
    fun `a canonical person is active if any of their records has an active membership`() {
        val ledger = TestLedger()
        ledger.me()
        val target = ledger.person("Target")
        val alias = ledger.person("Alias", mergedInto = target)
        val group = ledger.group("Trip", members = listOf(ME))
        ledger.member(group, alias, removed = true)
        val resolver1 = IdentityResolver(ledger.state)

        resolver1.hasMembership(group, target) shouldBe true
        resolver1.isActiveMember(group, target) shouldBe false
        resolver1.canonicalMembers(group) shouldBe setOf(ME, target)

        ledger.member(group, target)
        val resolver2 = IdentityResolver(ledger.state)
        resolver2.isActiveMember(group, target) shouldBe true
        resolver2.memberships(group, target).size shouldBe 2
    }

    @Test
    fun `deleted memberships do not count`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("Alex")
        val group = ledger.group("Trip", members = listOf(ME))
        ledger.member(group, alex, deleted = true)

        IdentityResolver(ledger.state).hasMembership(group, alex) shouldBe false
    }
}
