package io.github.codex07.iou.ui.viewmodel

import app.cash.turbine.test
import io.github.codex07.iou.api.*
import io.github.codex07.iou.fake.FakeApis
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeEach fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun tearDown() { Dispatchers.resetMain() }
    private fun model(apis: Apis, ledger: LedgerApi = apis.ledger) = OverviewViewModel(
        ledger, apis.people, apis.groups, apis.setup, apis.backup, apis.fx)

    @Test fun `dashboard uses exact canonical backend read models with bounded previews`() = runTest(dispatcher) {
        val apis = FakeApis.seeded()
        val model = model(apis)
        model.state.test {
            assertEquals(OverviewUiState.Loading, awaitItem())
            val ready = awaitItem() as OverviewUiState.Ready
            assertEquals(apis.ledger.observeOverview().first(), ready.overview)
            assertEquals(apis.ledger.observeActivity().first().filterNot { it.deleted }.take(5), ready.recent)
            assertTrue(ready.people.size <= 4)
            assertEquals(ready.people.map { it.person.id }.distinct().size, ready.people.size)
            assertTrue(ready.hasArchivedOpenBalances)
            assertTrue(ready.backupReminder.due)
            val allPeople = apis.people.observePeople(includeArchived = true).first()
            ready.people.forEach { assertEquals(allPeople.first { p -> p.person.id == it.person.id }.balances, it.balances) }
            cancelAndIgnoreRemainingEvents()
        }
    }
    @Test fun `backend writes automatically update the displayed totals`() = runTest(dispatcher) {
        val apis = FakeApis.seeded()
        model(apis).state.test {
            awaitItem()
            val before = awaitItem() as OverviewUiState.Ready
            val me = (apis.setup.observeSetupState().first() as SetupState.Ready).me.id
            val other = apis.people.observePeople().first().first().person.id
            val generation = apis.backup.observeLedgerGeneration().first()
            val result = apis.ledger.saveTransfer(TransferDraft(me, other,
                Money(1250, CurrencyCode("EUR")), occurredOn = LocalDate.now()), SaveMode.Create(generation))
            assertTrue(result is LedgerResult.Ok)
            var next = awaitItem() as OverviewUiState.Ready
            while (next.overview == before.overview) next = awaitItem() as OverviewUiState.Ready
            assertEquals(apis.ledger.observeOverview().first(), next.overview)
            cancelAndIgnoreRemainingEvents()
        }
    }
    @Test fun `empty backend yields empty sections rather than invented demo balances`() = runTest(dispatcher) {
        val apis = FakeApis.create(FakeApis.store())
        apis.setup.completeSetup("New user", CurrencyCode("EUR"))
        model(apis).state.test {
            awaitItem()
            val ready = awaitItem() as OverviewUiState.Ready
            assertTrue(ready.people.isEmpty()); assertTrue(ready.groups.isEmpty()); assertTrue(ready.recent.isEmpty())
            assertTrue(ready.overview.owedToMe.isEmpty()); assertTrue(ready.overview.iOwe.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
    @Test fun `upstream failure is recoverable with retry`() = runTest(dispatcher) {
        val apis = FakeApis.seeded()
        var fail = true
        val ledger = object : LedgerApi by apis.ledger {
            override fun observeOverview() = flow {
                if (fail) error("simulated read failure")
                emitAll(apis.ledger.observeOverview())
            }
        }
        val model = model(apis, ledger)
        model.state.test {
            awaitItem()
            assertEquals(OverviewUiState.Failed, awaitItem())
            fail = false; model.retry()
            assertEquals(OverviewUiState.Loading, awaitItem())
            assertTrue(awaitItem() is OverviewUiState.Ready)
            cancelAndIgnoreRemainingEvents()
        }
    }
    @Test fun `missing approximate conversion remains unavailable rather than becoming zero`() = runTest(dispatcher) {
        val apis = FakeApis.seeded()
        val ledger = object : LedgerApi by apis.ledger {
            override fun observeOverview() = apis.ledger.observeOverview().map {
                it.copy(approxOwedToMe = null, approxIOwe = null)
            }
        }
        model(apis, ledger).state.test {
            awaitItem()
            val ready = awaitItem() as OverviewUiState.Ready
            assertNull(ready.overview.approxOwedToMe); assertNull(ready.overview.approxIOwe)
            assertEquals(apis.ledger.observeOverview().first().owedToMe, ready.overview.owedToMe)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `missing owner profile is explicit rather than an endless spinner`() = runTest(dispatcher) {
        model(FakeApis.create(FakeApis.store())).state.test {
            awaitItem()
            assertEquals(OverviewUiState.NeedsSetup, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `pull to refresh fetches rates once at a time and reports the result`() = runTest(dispatcher) {
        val apis = FakeApis.seeded()
        val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var calls = 0
        val fx = object : FxApi by apis.fx {
            override suspend fun refreshRates(): Boolean { calls++; return answer.await() }
        }
        val model = OverviewViewModel(apis.ledger, apis.people, apis.groups, apis.setup, apis.backup, fx)
        val results = mutableListOf<Boolean>()

        model.pullToRefresh { results += it }
        model.pullToRefresh { results += it }
        runCurrent()
        assertTrue(model.refreshing.value)
        assertEquals(1, calls)

        answer.complete(false)
        runCurrent()
        assertFalse(model.refreshing.value)
        assertEquals(listOf(false), results)
    }
}
