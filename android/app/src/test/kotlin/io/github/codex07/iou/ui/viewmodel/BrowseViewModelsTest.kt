package io.github.codex07.iou.ui.viewmodel

import app.cash.turbine.test
import io.github.codex07.iou.api.*
import io.github.codex07.iou.fake.FakeApis
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeEach fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun cleanup() { Dispatchers.resetMain() }

    @Test fun `people list preserves backend currencies archived records and canonical identities`() = runTest(dispatcher) {
        val apis = FakeApis.seeded()
        PeopleViewModel(apis.people).state.test {
            assertEquals(BrowseState.Loading, awaitItem())
            val rows = (awaitItem() as BrowseState.Ready).value
            assertEquals(apis.people.observePeople(true, true).first(), rows)
            assertEquals(rows.size, rows.map { it.person.id }.distinct().size)
            assertTrue(rows.any { it.archived })
            assertTrue(rows.any { it.aliasCount > 0 })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `person detail resolves merged aliases and includes settled memberships without activity`() = runTest(dispatcher) {
        val apis = FakeApis.seeded()
        val alex = apis.people.observePeople().first().first { it.aliasCount > 0 }
        val original = apis.people.observePerson(alex.person.id).first()!!
        val basis = apis.backup.observeLedgerGeneration().first()
        val newGroup = (apis.groups.createGroup(GroupDraft("Settled empty group", CurrencyCode("EUR")),
            listOf(alex.person.id), basis) as LedgerResult.Ok).value
        val vm = PersonDetailViewModel(apis.people, apis.groups)
        vm.open(original.aliases.first().person.id.value)
        vm.state.test {
            assertEquals(BrowseState.Loading, awaitItem())
            val page = (awaitItem() as BrowseState.Ready).value
            assertEquals(alex.person.id, page.detail.person.id)
            assertEquals(original.balances, page.detail.balances)
            assertTrue(page.relatedGroups.any { it.group.id == newGroup })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `group detail exposes exact backend positions suggestions and canonical members`() = runTest(dispatcher) {
        val apis = FakeApis.seeded()
        val groups = apis.groups.observeGroups(true).first()
        assertTrue(groups.map { it.fxMode }.containsAll(FxMode.entries))
        val group = groups.first()
        val vm = GroupDetailViewModel(apis.groups)
        vm.open(group.group.id.value)
        vm.state.test {
            awaitItem()
            val detail = (awaitItem() as BrowseState.Ready).value
            assertEquals(apis.groups.observeGroup(group.group.id).first(), detail)
            assertEquals(detail.members.size, detail.members.map { it.person.id }.distinct().size)
            assertEquals(apis.groups.observeGroup(group.group.id).first()!!.settlePlan, detail.settlePlan)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `activity query delegates person group date and kind constraints to backend`() = runTest(dispatcher) {
        val apis = FakeApis.seeded()
        val person = apis.people.observePeople().first().first().person.id
        val group = apis.groups.observeGroups().first().first().group.id
        val today = LocalDate.of(2026, 11, 4)
        val query = ActivityQuery(type = ActivityType.REPAYMENT, person = person, group = group, days = 7)
        val backend = query.backendFilter(today)
        assertEquals(person, backend.personId); assertEquals(group, backend.groupId)
        assertEquals(today.minusDays(6), backend.from); assertEquals(today, backend.to)
        assertEquals(setOf(EntryKind.TRANSFER), backend.kinds)
        assertFalse(backend.includeDeleted)
    }

    @Test fun `activity distinguishes expenses loans repayments search and deleted records`() = runTest(dispatcher) {
        val apis = FakeApis.seeded()
        val all = apis.ledger.observeActivity().first()
        val vm = ActivityViewModel(apis.ledger, apis.people, apis.groups)
        vm.state.test {
            awaitItem()
            assertEquals(all, (awaitItem() as BrowseState.Ready).value.entries)
            vm.filter(ActivityQuery(type = ActivityType.EXPENSE))
            val expenses = (awaitItem() as BrowseState.Ready).value.entries
            assertEquals(all.filter { it.kind == EntryKind.EXPENSE }, expenses)
            vm.filter(ActivityQuery(type = ActivityType.LOAN))
            val loans = (awaitItem() as BrowseState.Ready).value.entries
            assertEquals(all.filter { it.kind == EntryKind.TRANSFER && it.transferKind == TransferKind.LOAN }, loans)
            assertFalse(ActivityQuery(type = ActivityType.REPAYMENT).matches(loans.first()))
            vm.filter(ActivityQuery(text = "no such record 897543"))
            assertTrue((awaitItem() as BrowseState.Ready).value.entries.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        val entry = all.first()
        val before = apis.ledger.observeEntry(entry.entryId).first()!!
        assertTrue(apis.ledger.deleteEntry(entry.entryId, before.token) is LedgerResult.Ok)
        vm.filter(ActivityQuery(deleted = true))
        vm.state.test {
            var value = awaitItem()
            while (value !is BrowseState.Ready || value.value.entries.none { it.deleted }) value = awaitItem()
            assertEquals(listOf(entry.entryId), value.value.entries.map { it.entryId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `entry detail preserves stored shares fx and historical revisions`() = runTest(dispatcher) {
        val apis = FakeApis.seeded()
        val entry = apis.ledger.observeActivity().first().first { it.paid.currency != it.effect.currency }
        val vm = EntryDetailViewModel(apis.ledger)
        vm.open(entry.entryId.value)
        vm.state.test {
            awaitItem()
            val detail = (awaitItem() as BrowseState.Ready).value
            assertEquals(apis.ledger.observeEntry(entry.entryId).first(), detail)
            assertNotNull(detail.fx)
            assertTrue(detail.shares.isNotEmpty())
            assertTrue(detail.history.isNotEmpty())
            assertTrue(apis.ledger.deleteEntry(entry.entryId, detail.token) is LedgerResult.Ok)
            val deleted = (awaitItem() as BrowseState.Ready).value
            assertTrue(deleted.item.deleted)
            assertTrue(deleted.history.size > detail.history.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `activity search finds a payer who is not a participant`() = runTest(dispatcher) {
        val apis = FakeApis.seeded()
        val people = apis.people.observePeople().first().map { it.person }
        val me = apis.ledger.observeActivity().first().first { it.paidBy.isMe }.paidBy.id
        val chen = people.first { it.displayName == "Chen" }.id
        val erik = people.first { it.displayName == "Erik" }.id
        val trip = (apis.groups.createGroup(GroupDraft("Payer search", CurrencyCode("EUR"), FxMode.KEEP), listOf(chen, erik),
            apis.backup.observeLedgerGeneration().first()) as LedgerResult.Ok).value
        val id = (apis.ledger.saveExpense(ExpenseDraft(
            payers = listOf(SplitInput(me), SplitInput(erik)), description = "Snacks", total = Money(3_000, CurrencyCode("EUR")),
            groupId = trip, participants = listOf(SplitInput(me), SplitInput(chen)), occurredOn = LocalDate.of(2026, 11, 4),
        ), SaveMode.Create(apis.backup.observeLedgerGeneration().first())) as LedgerResult.Ok).value
        val item = apis.ledger.observeActivity().first().first { it.entryId == id }
        assertFalse(item.participants.any { it.displayName == "Erik" })
        assertTrue(ActivityQuery(text = "Erik").matches(item))
    }

    @Test fun `unknown detail produces missing and failed read can retry`() = runTest(dispatcher) {
        val apis = FakeApis.seeded()
        val missing = EntryDetailViewModel(apis.ledger)
        missing.open("unknown")
        missing.state.test {
            awaitItem(); assertEquals(BrowseState.Missing, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        var fail = true
        val broken = object : PeopleApi by apis.people {
            override fun observePeople(includeArchived: Boolean, includeDeleted: Boolean) = flow {
                if (fail) error("Read failed")
                emitAll(apis.people.observePeople(includeArchived, includeDeleted))
            }
        }
        val vm = PeopleViewModel(broken)
        vm.state.test {
            awaitItem(); assertEquals(BrowseState.Failed, awaitItem())
            fail = false; vm.retry()
            assertEquals(BrowseState.Loading, awaitItem())
            assertTrue(awaitItem() is BrowseState.Ready)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
