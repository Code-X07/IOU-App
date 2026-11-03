package io.github.codex07.iou.ledger.engine

import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.testMeta
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RevisionDiffTest {

    private val names = object : DiffNames {
        override fun person(id: PersonId) = if (id == ME) "Me" else id.value.removePrefix("person-").replaceFirstChar { it.uppercase() }
        override fun group(id: GroupId) = id.value
    }

    @Test
    fun `an amount edit shows old and new money and ignores sync columns`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        val id = ledger.transfer(ME, x, eur(4_000))
        val v1 = ledger.state.transfers.getValue(id)
        val v2 = v1.copy(amount = eur(4_500), effectAmount = eur(4_500), meta = testMeta(version = 2))

        RevisionDiff.diff(v1, v2, names) shouldBe listOf(
            FieldChange("amount", "40.00 EUR", "45.00 EUR"),
            FieldChange("effect", "40.00 EUR", "45.00 EUR"),
        )
    }

    @Test
    fun `shares are diffed per person`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("alex")
        val id = ledger.expense(ME, eur(10_000), mapOf(ME to 5_000L, alex to 5_000L), inputs = mapOf(ME to 5_000L, alex to 5_000L))
        val v1 = ledger.state.expenses.getValue(id)
        val v2 = v1.copy(
            shares = v1.shares.map { s ->
                val amount = if (s.personId == ME) 2_500L else 7_500L
                s.copy(shareMinor = amount, inputValue = amount)
            },
        )

        RevisionDiff.diff(v1, v2, names) shouldContainExactlyInAnyOrder listOf(
            FieldChange("share[Me]", "50.00 EUR", "25.00 EUR"),
            FieldChange("input[Me]", "5000", "2500"),
            FieldChange("share[Alex]", "50.00 EUR", "75.00 EUR"),
            FieldChange("input[Alex]", "5000", "7500"),
        )
    }

    @Test
    fun `deletion shows as a state change`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        val id = ledger.transfer(ME, x, eur(100))
        val v1 = ledger.state.transfers.getValue(id)
        val v2 = v1.copy(meta = v1.meta.copy(deletedAt = T0, version = 2))

        RevisionDiff.diff(v1, v2) shouldBe listOf(FieldChange("state", "active", "deleted"))
    }

    @Test
    fun `a first revision lists every set field as new`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("alex")
        val person = ledger.state.people.getValue(alex)

        val diff = RevisionDiff.diff(null, person, names)
        diff shouldContain FieldChange("displayName", null, "alex")
        diff shouldContain FieldChange("state", null, "active")
        diff.none { it.after == null } shouldBe true
    }

    @Test
    fun `a merge shows the target by name`() {
        val ledger = TestLedger()
        ledger.me()
        val alex = ledger.person("alex")
        val bob = ledger.person("bob")
        val v1 = ledger.state.people.getValue(alex)
        val v2 = v1.copy(mergedIntoId = bob, meta = testMeta(version = 2))

        RevisionDiff.diff(v1, v2, names) shouldBe listOf(FieldChange("mergedInto", null, "Bob"))
    }

    @Test
    fun `people with the same name are told apart by ID`() {
        val ledger = TestLedger()
        ledger.me()
        val alex1 = ledger.person("alex1")
        val alex2 = ledger.person("alex2")
        val sameName = object : DiffNames {
            override fun person(id: PersonId) = if (id == ME) "Me" else "Alex"
            override fun group(id: GroupId) = id.value
        }
        val id = ledger.expense(ME, eur(10_000), mapOf(alex1 to 5_000L, alex2 to 5_000L), inputs = mapOf(alex1 to 5_000L, alex2 to 5_000L))
        val v1 = ledger.state.expenses.getValue(id)
        val v2 = v1.copy(
            shares = v1.shares.map { s ->
                val amount = if (s.personId == alex1) 2_500L else 7_500L
                s.copy(shareMinor = amount, inputValue = amount)
            },
        )

        RevisionDiff.diff(v1, v2, sameName).filter { it.field == "share[Alex]" }.map { it.before to it.after } shouldContainExactlyInAnyOrder
            listOf("50.00 EUR" to "25.00 EUR", "50.00 EUR" to "75.00 EUR")

        val transfer = ledger.transfer(ME, alex1, eur(100))
        val t1 = ledger.state.transfers.getValue(transfer)
        val t2 = t1.copy(toPersonId = alex2, meta = testMeta(version = 2))
        RevisionDiff.diff(t1, t2, sameName) shouldBe listOf(FieldChange("to", "Alex (${alex1.value})", "Alex (${alex2.value})"))
    }
}
