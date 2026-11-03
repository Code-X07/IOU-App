package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.HandleDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.OverviewRow
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.ledger.model.HandleType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

abstract class PeopleApiContract : ApiContract() {

    @Test
    fun `created people are listed and updates need a current token`() = runTest {
        setUpMe()
        val x = newPerson("X")
        apis.people.observePeople().first().map { it.person.displayName } shouldBe listOf("X")

        val token = apis.people.observePerson(x).first()!!.token
        apis.people.updatePerson(x, PersonDraft("Xavier"), token).orFail()
        apis.people.observePerson(x).first()!!.person.displayName shouldBe "Xavier"
        apis.people.updatePerson(x, PersonDraft("Old"), token).shouldBeInstanceOf<LedgerResult.Err>()
            .error.shouldBeInstanceOf<LedgerError.StaleVersion>()
    }

    @Test
    fun `archived people with a balance stay in the overview`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        transfer(me, x, eur(1_000))
        apis.people.setPersonArchived(x, true, apis.people.observePerson(x).first()!!.token).orFail()

        val row = apis.ledger.observeOverview().first().rows.single() as OverviewRow.PersonRow
        row.archived shouldBe true
        row.balances shouldBe listOf(eur(1_000))
        apis.people.observePeople().first() shouldBe emptyList()
    }

    @Test
    fun `only unreferenced people can be deleted`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val y = newPerson("Y")
        transfer(me, x, eur(100))

        apis.people.deletePerson(x, apis.people.observePerson(x).first()!!.token) shouldBe
            LedgerResult.Err(LedgerError.StillReferenced(x.value))
        apis.people.deletePerson(y, apis.people.observePerson(y).first()!!.token).orFail()
        apis.people.observePeople().first().map { it.person.id } shouldBe listOf(x)
    }

    @Test
    fun `a deleted person can be found again and restored`() = runTest {
        setUpMe()
        val y = newPerson("Y")
        apis.people.deletePerson(y, apis.people.observePerson(y).first()!!.token).orFail()

        apis.people.observePeople().first() shouldBe emptyList()
        val found = apis.people.observePeople(includeDeleted = true).first().single()
        found.person.id shouldBe y
        found.deleted shouldBe true
        apis.people.restorePerson(y, apis.people.observePerson(y).first()!!.token).orFail()
        apis.people.observePeople().first().map { it.person.id } shouldBe listOf(y)
    }

    @Test
    fun `editing handles as shown after a merge keeps the alias's default for unmerge`() = runTest {
        setUpMe()
        val alex = newPerson("Alex")
        val alexS = newPerson("Alex S.")
        apis.people.addHandle(alex, HandleDraft(HandleType.IBAN, "DE89370400440532013000", "Main", isDefault = true), basis()).orFail()
        apis.people.addHandle(alexS, HandleDraft(HandleType.IBAN, "GB82WEST12345698765432", "UK", isDefault = true), basis()).orFail()
        apis.people.merge(alexS, alex, apis.people.observePerson(alexS).first()!!.token).orFail()

        // Rename every handle, everything else as the view shows it.
        apis.people.observePerson(alex).first()!!.handles.forEach {
            apis.people.updateHandle(it.id, HandleDraft(it.type, it.value, "${it.label}!", it.holderName, it.bic, it.isDefault), it.token).orFail()
        }
        apis.people.observePerson(alex).first()!!.handles.single { it.isDefault }.label shouldBe "Main!"

        val alias = apis.people.observePerson(alex).first()!!.aliases.single()
        apis.people.unmerge(alias.person.id, alias.token).orFail()
        apis.people.observePerson(alexS).first()!!.handles.single().isDefault shouldBe true
        apis.people.observePerson(alex).first()!!.handles.single().isDefault shouldBe true
    }

    @Test
    fun `handles are normalized and validated`() = runTest {
        setUpMe()
        val x = newPerson("X")
        apis.people.addHandle(x, HandleDraft(HandleType.IBAN, "de89 3704 0044 0532 0130 00", "Bank", isDefault = true), basis()).orFail()
        apis.people.observePerson(x).first()!!.handles.single().value shouldBe "DE89370400440532013000"
        apis.people.addHandle(x, HandleDraft(HandleType.IBAN, "DE00 0000", "Bad"), basis()) shouldBe
            LedgerResult.Err(LedgerError.InvalidIban("DE00 0000"))
    }

    @Test
    fun `merge combines balances and unmerge splits them again`() = runTest {
        val me = setUpMe()
        val alex = newPerson("Alex")
        val alexS = newPerson("Alex S.")
        transfer(me, alex, eur(1_000))
        transfer(me, alexS, eur(500))

        apis.people.previewMerge(alexS, alex).orFail().combinedBalances shouldBe listOf(eur(1_500))
        apis.people.merge(alexS, alex, apis.people.observePerson(alexS).first()!!.token).orFail()
        val merged = apis.people.observePerson(alex).first()!!
        merged.balances shouldBe listOf(eur(1_500))
        apis.people.observePerson(alexS).first()!!.person.id shouldBe alex

        val alias = merged.aliases.single()
        alias.person.id shouldBe alexS
        alias.mergedInto.id shouldBe alex
        apis.people.unmerge(alias.person.id, alias.token).orFail()
        apis.people.observePerson(alex).first()!!.balances shouldBe listOf(eur(1_000))
        apis.people.observePerson(alexS).first()!!.balances shouldBe listOf(eur(500))
        apis.people.balanceSummaryText(alexS).orFail() shouldBe "Alex S. owes you 5.00 EUR"
    }
}
