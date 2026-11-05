package io.github.codex07.iou.fake

import io.github.codex07.iou.api.ActivityFilter
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.OverviewRow
import io.github.codex07.iou.api.SetupState
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SeedDataTest {

    private val store = FakeApis.store(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("seed")).also { runBlocking { SeedData.populate(it) } }
    private val apis = FakeApis.create(store)

    @Test
    fun `the seed has me, five people, a merged duplicate and an archived unsettled person`() = runTest {
        val people = apis.people.observePeople(includeArchived = true).first()
        people.size shouldBe 5
        people.single { it.person.displayName == "Alex" }.aliasCount shouldBe 1
        people.single { it.person.displayName == "Dana" }.archived shouldBe true
        val overview = apis.ledger.observeOverview().first()
        (overview.rows.single { it is OverviewRow.PersonRow && it.person.displayName == "Dana" }).archived shouldBe true
        overview.owedToMe.shouldNotBeEmpty()
    }

    @Test
    fun `the seed has a CONVERT EUR group with a SEK expense and a KEEP group`() = runTest {
        val groups = apis.groups.observeGroups().first()
        groups.map { it.fxMode }.toSet() shouldBe setOf(FxMode.CONVERT, FxMode.KEEP)
        val trip = groups.single { it.fxMode == FxMode.CONVERT }
        trip.defaultCurrency shouldBe EUR
        val tripActivity = apis.ledger.observeActivity(ActivityFilter(groupId = trip.group.id)).first()
        tripActivity.any { it.paid.currency.code == "SEK" && it.effect.currency == EUR } shouldBe true
        apis.groups.observeGroup(trip.group.id).first()!!.settlePlan.shouldNotBeEmpty()
    }

    @Test
    fun `every api returns seeded data`() = runTest {
        val loan = apis.ledger.observeActivity().first().first { it.description == null && it.note == "Concert tickets" }
        apis.ledger.observeEntry(loan.entryId).first()!!.history.size shouldBeGreaterThan 1
        (apis.fx.quote(store.today(), EUR, SEK) is LedgerResult.Ok) shouldBe true
        apis.backup.observeBackupReminder().first().due shouldBe true
        apis.importExport.observeImportBatches().first().shouldNotBeEmpty()
        apis.settings.observeSettings().first().homeCurrency shouldBe EUR
        apis.setup.observeSetupState().first().shouldBeInstanceOf<SetupState.Ready>()
        apis.ledger.observeOverview().first().rows.size shouldBe 7
    }

    @Test
    fun `replaying the latest revision per entity reproduces the tables`() = runTest {
        val snapshot = store.loaded()
        val latest = snapshot.revisions
            .groupBy { it.entityType to it.entityId }.values
            .map { revisions -> revisions.maxBy { it.version }.snapshot }
        val state = snapshot.state
        val current: List<LedgerEntity> = state.people.values + state.handles.values + state.groups.values +
            state.members.values + state.entries
        latest.toSet() shouldBe current.toSet()
    }
}
