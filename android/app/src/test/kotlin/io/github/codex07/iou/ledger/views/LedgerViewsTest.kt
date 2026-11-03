package io.github.codex07.iou.ledger.views

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.MergeProblem
import io.github.codex07.iou.api.OverviewRow
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.sek
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LedgerViewsTest {

    private fun views(state: LedgerState) =
        LedgerViews(state, emptyList(), LedgerGeneration("gen-1"), EUR) { if (it == EUR) "1" else null }

    private fun OverviewRow.label() = when (this) {
        is OverviewRow.PersonRow -> person.displayName
        is OverviewRow.GroupRow -> group.name
    }

    @Test
    fun `overview lists people and groups, hides settled archived people, and totals per currency`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        ledger.person("Settled", archived = true)
        val zed = ledger.person("Zed", archived = true)
        val a = ledger.person("A")
        val dinner = ledger.group("Dinner", members = listOf(ME, a))
        ledger.transfer(ME, x, eur(3_000))
        ledger.transfer(x, ME, sek(10_000))
        ledger.transfer(ME, zed, eur(500))
        ledger.expense(a, eur(4_000), mapOf(a to 2_000L, ME to 2_000L), group = dinner)

        val overview = views(ledger.state).overview()

        overview.owedToMe shouldBe listOf(eur(3_500))
        overview.iOwe shouldBe listOf(eur(2_000), sek(10_000))
        overview.approxOwedToMe shouldBe eur(3_500)
        overview.approxIOwe shouldBe null
        overview.rows.map { it.label() } shouldBe listOf("Dinner", "Zed", "A", "X")
        overview.rows.single { it.label() == "Zed" }.archived shouldBe true
    }

    @Test
    fun `person detail shows aliases, the canonical default handle and the In groups suggestion`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val a2 = ledger.person("A2", mergedInto = a)
        val b = ledger.person("B")
        val iban = ledger.handle(a2, HandleType.IBAN, isDefault = true)
        val dinner = ledger.group("Dinner", members = listOf(ME, a, b))
        ledger.expense(a, eur(6_000), mapOf(a to 2_000L, b to 2_000L, ME to 2_000L), group = dinner)

        val detail = views(ledger.state).person(a2)!!

        detail.person.id shouldBe a
        val alias = detail.aliases.single()
        alias.person.displayName shouldBe "A2"
        alias.mergedInto.id shouldBe a
        alias.token.version shouldBe ledger.state.people.getValue(a2).meta.version
        detail.handles.single().id shouldBe iban
        detail.handles.single().owner.displayName shouldBe "A2"
        detail.handles.single().isDefault shouldBe true
        val suggestion = detail.inGroups.single()
        suggestion.group.name shouldBe "Dinner"
        suggestion.items.map { Triple(it.from.id, it.to.id, it.amount) } shouldBe listOf(Triple(ME, a, eur(2_000)))
    }

    @Test
    fun `group detail has members, plan, flows and the settings lock`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val b = ledger.person("B")
        val dinner = ledger.group("Dinner", members = listOf(ME, a, b))
        ledger.expense(a, eur(6_000), mapOf(a to 2_000L, b to 2_000L, ME to 2_000L), group = dinner)

        val detail = views(ledger.state).group(dinner)!!

        detail.members.associate { it.person.displayName to it.nets } shouldBe
            mapOf("A" to listOf(eur(4_000)), "B" to listOf(eur(-2_000)), "Me" to listOf(eur(-2_000)))
        detail.settlePlan.map { it.from.displayName to it.amount }.toSet() shouldBe setOf("B" to eur(2_000), "Me" to eur(2_000))
        detail.flows.size shouldBe 2
        detail.settingsLocked shouldBe true
        views(ledger.state).groups(includeArchived = false).single().myNets shouldBe listOf(eur(-2_000))
    }

    @Test
    fun `deleted people and groups are listed only when asked`() {
        val ledger = TestLedger().apply { me() }
        val gone = ledger.person("Gone", deleted = true)
        ledger.person("Kept")
        val old = ledger.group("Old", deleted = true)

        views(ledger.state).people(includeArchived = false).map { it.person.displayName } shouldBe listOf("Kept")
        views(ledger.state).people(includeArchived = false, includeDeleted = true).single { it.deleted }.person.id shouldBe gone
        views(ledger.state).groups(includeArchived = false) shouldBe emptyList()
        views(ledger.state).groups(includeArchived = false, includeDeleted = true).single().let {
            it.group.id shouldBe old
            it.deleted shouldBe true
        }
        views(ledger.state).person(gone)!!.deleted shouldBe true
    }

    @Test
    fun `merge preview shows the combined balance and writes nothing`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val alexS = ledger.person("AlexS")
        val c = ledger.person("C")
        val trip = ledger.group("Trip", members = listOf(ME, alex, alexS, c))
        ledger.transfer(ME, alex, eur(1_000))
        ledger.transfer(ME, alexS, eur(500))
        // C pays 20.00 for the two Alex records: after the merge Alex owes C 20.00, so it isn't neutralized.
        ledger.expense(c, eur(2_000), mapOf(alex to 1_000L, alexS to 1_000L), group = trip)
        val views = views(ledger.state)

        val preview = (views.mergePreview(alexS, alex) as LedgerResult.Ok).value
        preview.combinedBalances shouldBe listOf(eur(1_500))
        preview.neutralizedEntries shouldBe emptyList()
        preview.target.displayName shouldBe "Alex"
        views.person(alexS)!!.balances shouldBe listOf(eur(500))
        views.mergePreview(alex, alex) shouldBe LedgerResult.Err(LedgerError.InvalidMerge(MergeProblem.SAME_PERSON))
    }
}
