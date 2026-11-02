# M0 Part 7: Read Models, the Fake, Contract Tests and `v0.0.1` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Read `docs/superpowers/plans/2026-11-02-m0-00-index.md` first: its locked decisions and type inventory apply here.

**Goal:** Finish Milestone 0: the `fakeDebug` app runs with every `*Api` backed by an in-memory ledger that uses the real rules, engine and ops; seeded demo data; shared contract test suites passing against the fake; a signed `v0.0.1` release.

**Architecture:** `ledger.views.LedgerViews` turns one immutable snapshot (state, revisions, generation, home currency) into every `api` read model; it is shared with M1. `fake.InMemoryLedger` holds a `StateFlow<FakeSnapshot>`, applies `WritePlan`s from `ledger.ops` under a lock (one revision per change, `ledgerSeq + 1`), and exposes `LedgerViews` flows. Eight thin `Fake*Api` classes delegate to ops and views; backup, restore and import/export are simulated with canned behaviour (spec §6.3). Hilt binds `Apis` per flavor; a `main` module exposes the eight interfaces.

**Tech Stack:** Kotlin, Coroutines/Flow, Hilt, Compose (placeholder only), JUnit 5, Kotest, Turbine, GitHub Actions.

## Global Constraints

- M0 acceptance criteria (spec §12): `fakeDebug` builds and runs; every `*Api` returns seeded data, and writes update all observers; the fake rejects invalid writes with the spec's `LedgerError`s (e.g. a 3-person ad-hoc expense → `NeedsGroup`); CI green on push; a `v0.0.1` tag produces a signed APK on a GitHub Release; `api` has no Android imports and no `Double`/`Float`.
- Seed data (spec §6.3): me, five people, two groups (one CONVERT EUR with a SEK expense, one KEEP), a merged duplicate, a few revisions, an archived unsettled person.
- The fake uses the real `ledger.rules` and `ledger.engine` (spec §6.3) — here through `ledger.ops` and `ledger.views`.
- Backup, restore and import/export are simulated with canned success, failure and preview results (spec §6.3).
- Overview (spec §4.3): people = every non-archived canonical person plus archived people with a non-zero balance; groups = every non-archived group plus archived groups where my net is non-zero; ordered by absolute approximate home-currency amount, descending; approximate totals are never stored.
- Person detail (spec §4.3): the non-group balance per currency plus, per group, the current settle-plan transfers between me and that person.
- Settle-up drafts (spec §4.6): `REPAYMENT`, tagged with the group; in KEEP groups `settlesCurrency` = the plan item's currency; person settle-up sets `settlesCurrency` to the debt's currency; drafts carry their `basis`.
- Contract tests (spec §11): one abstract suite per `*Api`, run against the fake now and the real implementation in M1; tests needing groups are tagged `groups` (spec §12, M1 AC 14).
- §4.7: replaying `EntryRevision` (latest snapshot per entity) reproduces the current entity tables exactly.

## Decisions this part makes

- **Simulated restore** in the fake restores a backup of the *current* data under a fresh generation (so `LedgerReplaced` behaves exactly as spec AC M1-15 describes); undo swaps back, again with a fresh generation. Backups remember their passphrase in memory, so a wrong passphrase returns `WrongPassphraseOrDamagedFile`.
- **Simulated import:** a file name containing `foreign` needs identity confirmation; `corrupt` → `CorruptFile`; `invalid` adds one invalid row; anything else gives a canned preview (two new rows, one conflict on the first live transfer's note, five duplicates). The canned preview is always built from the current ledger, so `confirmIdentity` and `refreshPreview` rebuild it (after checking the preview's generation, `LedgerReplaced` otherwise) and refresh flags rows whose diff changed. Commit checks generation, `ledgerSeq`, identity and invalid rows like the real thing, records an `ImportBatch`, and doesn't change ledger data.
- **Fake FX rates** are a fixed table against EUR (`FakeRates`); unknown currencies → `RateRequired`.
- **Before setup**, ledger-data flows emit nothing and writes other than setup return `NotFound("me")`.
- **The placeholder `MainActivity`** injects `LedgerApi` and shows the number of overview rows, which proves the Hilt wiring end to end. The friend replaces it.

## File map

| File | Responsibility |
|---|---|
| `main/.../ledger/views/LedgerViews.kt` | Refs, overview, people, groups, person and group detail, merge preview |
| `main/.../ledger/views/EntryViews.kt` | Activity, entry detail, edit drafts, history, split preview, settle-up drafts, summary text |
| `main/.../fake/InMemoryLedger.kt` | `FakeSnapshot`, `InMemoryLedger` |
| `main/.../fake/FakeRates.kt` | Fixed rate table |
| `main/.../fake/FakeLedgerApis.kt` | `FakeSetupApi`, `FakePeopleApi`, `FakeGroupsApi`, `FakeLedgerApi` |
| `main/.../fake/FakeServiceApis.kt` | `FakeFxApi`, `FakeBackupApi`, `FakeImportExportApi`, `FakeSettingsApi` |
| `main/.../fake/FakeApis.kt` | `FakeApis.create`, `FakeApis.seeded` |
| `main/.../fake/SeedData.kt` | Demo data, written through the ops |
| `main/.../di/ApiModule.kt` | Provides the eight interfaces from `Apis` |
| `fake/.../di/BackendModule.kt`, `real/.../di/BackendModule.kt` | Provide `Apis` per flavor |
| `main/.../MainActivity.kt` | Placeholder that observes the overview |
| `test/.../ledger/views/LedgerViewsTest.kt`, `EntryViewsTest.kt` | View tests |
| `test/.../fake/InMemoryLedgerTest.kt`, `SeedDataTest.kt`, `FakeContractTests.kt` | Fake tests (incl. the §4.7 revision-replay property over the seed) and contract runs |
| `test/.../api/contract/*.kt` | `ApiContract` base and eight abstract suites |
| `docs/ui-contributor-guide.md`, `README.md` | Run instructions, status |

`main/...` = `android/app/src/main/kotlin/io/github/codex07/iou/`; `fake/...` and `real/...` are the flavor source sets (`android/app/src/fake/kotlin/...`, `android/app/src/real/kotlin/...`); tests under `android/app/src/test/kotlin/io/github/codex07/iou/`.

---

### Task 1: `LedgerViews` (overview, people, groups, merge preview)

**Files:**
- Create: `main/.../ledger/views/LedgerViews.kt`
- Create (empty shell now, filled in Task 2): `main/.../ledger/views/EntryViews.kt`
- Test: `test/.../ledger/views/LedgerViewsTest.kt`

**Interfaces:**
- Consumes: model (Part 2), read models (Part 3), `IdentityResolver`, `LedgerEngine`, `SettlePlanner`, `FxConverter`, `RevisionDiff`, `DiffNames` (Parts 2, 4), `PeopleOps.merge`, `WriteContext` (Part 6).
- Produces: `class LedgerViews(state, revisions, generation, homeCurrency, rateToHome: (CurrencyCode) -> String?)` with public `resolver`, `balances` (lazy), `personRef(id)` (canonical), `rawRef(id)` (the record itself), `groupRef(id)`, `token(entity)`, `overview()`, `people(includeArchived)`, `person(id)`, `groups(includeArchived)`, `group(id)`, `groupPlan(groupId)`, `handleView(handle, canonical)`, `mergePreview(source, target)`; internal `meId`, `names`, `amounts(map)`, `approxHome(map)`. `rateToHome(c)` returns home units per one unit of `c` (`"1"` for the home currency) or null when unknown.

- [ ] **Step 1: Write the failing test**

`test/.../ledger/views/LedgerViewsTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.views.LedgerViewsTest"
```

Expected: compilation fails, `Unresolved reference 'LedgerViews'`.

- [ ] **Step 3: Implement**

`main/.../ledger/views/LedgerViews.kt`:

```kotlin
package io.github.codex07.iou.ledger.views

import io.github.codex07.iou.api.ActivityFilter
import io.github.codex07.iou.api.AliasView
import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.GroupDetail
import io.github.codex07.iou.api.GroupNetView
import io.github.codex07.iou.api.GroupRef
import io.github.codex07.iou.api.GroupSuggestion
import io.github.codex07.iou.api.GroupSummary
import io.github.codex07.iou.api.FlowView
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.MemberView
import io.github.codex07.iou.api.MergePreview
import io.github.codex07.iou.api.Overview
import io.github.codex07.iou.api.OverviewRow
import io.github.codex07.iou.api.PaymentHandleView
import io.github.codex07.iou.api.PersonDetail
import io.github.codex07.iou.api.PersonRef
import io.github.codex07.iou.api.PersonSummary
import io.github.codex07.iou.api.PlanItem
import io.github.codex07.iou.api.ledgerResult
import io.github.codex07.iou.ledger.engine.Balances
import io.github.codex07.iou.ledger.engine.DiffNames
import io.github.codex07.iou.ledger.engine.FxConverter
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.engine.SettlePlanner
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.ops.PeopleOps
import io.github.codex07.iou.ledger.ops.WriteContext
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant

/**
 * Every `api` read model of one ledger snapshot (spec §4.3, §6.2). Pure; shared by the fake and the
 * real implementation. Requires setup to have run (exactly one me) for anything that needs balances.
 */
class LedgerViews(
    val state: LedgerState,
    val revisions: List<EntryRevision>,
    val generation: LedgerGeneration,
    val homeCurrency: CurrencyCode,
    private val rateToHome: (CurrencyCode) -> String?,
) {
    val resolver = IdentityResolver(state)
    val balances: Balances by lazy { LedgerEngine.compute(state) }
    internal val meId: PersonId by lazy { resolver.canonical(state.me.id) }

    /** Diffs name the record a snapshot references, not the person it resolves to today. */
    internal val names = object : DiffNames {
        override fun person(id: PersonId) = rawRef(id).displayName
        override fun group(id: GroupId) = groupRef(id).name
    }

    // ---- references and helpers ----

    /** The canonical person [id] resolves to. */
    fun personRef(id: PersonId): PersonRef = rawRef(resolver.canonical(id))

    /** The person record itself (used for aliases and handle owners). */
    fun rawRef(id: PersonId): PersonRef {
        val person = state.people[id]
        return PersonRef(id, person?.displayName ?: id.value, person?.isMe == true)
    }

    fun groupRef(id: GroupId) = GroupRef(id, state.groups[id]?.name ?: id.value)

    fun token(entity: LedgerEntity) = EditToken(generation, entity.meta.version)

    internal fun amounts(map: Map<CurrencyCode, Long>): List<Money> =
        map.entries.sortedBy { it.key.code }.map { (currency, amount) -> Money(amount, currency) }

    /** Σ |amount| converted to the home currency with the latest rates; null when a rate is missing (spec §4.3). */
    internal fun approxHome(map: Map<CurrencyCode, Long>): Money? {
        var total = 0L
        for ((currency, amount) in map) {
            val rate = rateToHome(currency) ?: return null
            val magnitude = Money(if (amount < 0) -amount else amount, currency)
            val converted = when (val result = FxConverter.convert(magnitude, homeCurrency, rate)) {
                is LedgerResult.Ok -> result.value.amountMinor
                is LedgerResult.Err -> if (result.error is LedgerError.ConvertedToZero) 0L else return null
            }
            total = Math.addExact(total, converted)
        }
        return Money(total, homeCurrency)
    }

    private fun canonicalPeople(): List<Person> =
        state.people.values
            .filter { !it.isMe && !it.isDeleted && it.mergedIntoId == null }
            .sortedBy { it.displayName.lowercase() }

    // ---- overview and people ----

    fun overview(): Overview {
        val people = canonicalPeople().mapNotNull { person ->
            val net = balances.net(person.id)
            val archived = person.archivedAt != null
            if (archived && net.isEmpty()) null
            else OverviewRow.PersonRow(rawRef(person.id), amounts(net), archived, approxHome(net))
        }
        val groups = state.groups.values.filter { !it.isDeleted }.mapNotNull { group ->
            val net = balances.myGroupNet(group.id)
            val archived = group.archivedAt != null
            if (archived && net.isEmpty()) null
            else OverviewRow.GroupRow(groupRef(group.id), amounts(net), archived, approxHome(net))
        }
        val rows = (people + groups).sortedByDescending { it.approxHome?.amountMinor ?: -1L }
        return Overview(
            generation = generation,
            homeCurrency = homeCurrency,
            owedToMe = amounts(balances.owedToMe),
            iOwe = amounts(balances.iOwe),
            approxOwedToMe = approxHome(balances.owedToMe),
            approxIOwe = approxHome(balances.iOwe),
            rows = rows,
        )
    }

    fun people(includeArchived: Boolean): List<PersonSummary> =
        canonicalPeople()
            .filter { includeArchived || it.archivedAt == null }
            .map { PersonSummary(rawRef(it.id), amounts(balances.net(it.id)), it.archivedAt != null, resolver.recordsOf(it.id).size - 1) }

    fun person(id: PersonId): PersonDetail? {
        val canonical = resolver.canonical(id)
        val person = state.people[canonical] ?: return null
        val inGroups = state.groups.values.filter { !it.isDeleted }.mapNotNull { group ->
            val items = groupPlan(group.id).filter { setOf(it.from.id, it.to.id) == setOf(meId, canonical) }
            if (items.isEmpty()) null else GroupSuggestion(groupRef(group.id), items)
        }
        return PersonDetail(
            generation = generation,
            person = rawRef(canonical),
            note = person.note,
            defaultCurrency = person.defaultCurrency,
            archived = person.archivedAt != null,
            deleted = person.isDeleted,
            token = token(person),
            aliases = (resolver.recordsOf(canonical) - canonical).sortedBy { it.value }.map { aliasId ->
                val alias = state.people.getValue(aliasId)
                AliasView(rawRef(aliasId), rawRef(alias.mergedIntoId!!), token(alias))
            },
            balances = amounts(balances.net(canonical)),
            inGroups = inGroups,
            handles = resolver.handlesOf(canonical).map { handleView(it, canonical) },
            activity = activity(ActivityFilter(personId = canonical)),
            history = history(personHistoryRefs(canonical)),
        )
    }

    /** The person's records (aliases included) and every handle they own, deleted ones too (`PersonDetail.history`). */
    private fun personHistoryRefs(canonical: PersonId): List<EntityRef> {
        val records = resolver.recordsOf(canonical)
        return records.map { EntityRef(EntityType.PERSON, it.value) } +
            state.handles.values.filter { it.personId in records }.map { EntityRef(EntityType.PAYMENT_HANDLE, it.id.value) }
    }

    fun handleView(handle: PaymentHandle, canonical: PersonId) = PaymentHandleView(
        id = handle.id,
        owner = rawRef(handle.personId),
        type = handle.type,
        value = handle.value,
        label = handle.label,
        holderName = handle.holderName,
        bic = handle.bic,
        isDefault = resolver.defaultHandle(canonical, handle.type)?.id == handle.id,
        token = token(handle),
    )

    /** Previews a merge by running the merge op on a copy; nothing is written (spec §5.6). */
    fun mergePreview(source: PersonId, target: PersonId): LedgerResult<MergePreview> = ledgerResult {
        val from = state.people[source] ?: fail(LedgerError.NotFound(source.value))
        val ctx = WriteContext(state, generation, Instant.EPOCH, IdGenerator.Random)
        val plan = PeopleOps.merge(ctx, source, target, token(from)).bind().plan
        val after = LedgerViews(state.applying(plan.changes), revisions, generation, homeCurrency, rateToHome)
        MergePreview(
            source = rawRef(source),
            target = rawRef(target),
            combinedBalances = after.amounts(after.balances.net(target)),
            combinedGroupNets = state.groups.values.filter { !it.isDeleted }.mapNotNull { group ->
                val nets = after.balances.groupNet(group.id, target)
                if (nets.isEmpty()) null else GroupNetView(groupRef(group.id), after.amounts(nets))
            },
            // Involving both people isn't enough (C pays, split between them, still counts): the entry must
            // move a balance today and none after the merge.
            neutralizedEntries = state.entries
                .filter { !it.isDeleted }
                .filter { LedgerEngine.entryEffects(it, resolver).isNotEmpty() && LedgerEngine.entryEffects(it, after.resolver).isEmpty() }
                .map { activityItem(it) },
            defaultHandles = HandleType.entries.mapNotNull { type ->
                after.resolver.defaultHandle(target, type)?.let { after.handleView(it, target) }
            },
        )
    }

    // ---- groups ----

    fun groups(includeArchived: Boolean): List<GroupSummary> =
        state.groups.values
            .filter { !it.isDeleted && (includeArchived || it.archivedAt == null) }
            .sortedBy { it.name.lowercase() }
            .map { group ->
                GroupSummary(
                    group = groupRef(group.id),
                    defaultCurrency = group.defaultCurrency,
                    fxMode = group.fxMode,
                    archived = group.archivedAt != null,
                    myNets = amounts(balances.myGroupNet(group.id)),
                    activeMemberCount = resolver.canonicalMembers(group.id).count { resolver.isActiveMember(group.id, it) },
                )
            }

    fun group(id: GroupId): GroupDetail? {
        val group = state.groups[id] ?: return null
        val members = resolver.canonicalMembers(id)
            .map { MemberView(personRef(it), resolver.isActiveMember(id, it), amounts(balances.groupNet(id, it))) }
            .sortedBy { it.person.displayName.lowercase() }
        val flows = balances.flows[id].orEmpty().flatMap { (pair, perCurrency) ->
            amounts(perCurrency).map { FlowView(personRef(pair.first), personRef(pair.second), it) }
        }
        return GroupDetail(
            generation = generation,
            group = groupRef(id),
            defaultCurrency = group.defaultCurrency,
            fxMode = group.fxMode,
            archived = group.archivedAt != null,
            deleted = group.isDeleted,
            settingsLocked = state.entries.any { it.groupId == id },
            token = token(group),
            members = members,
            settlePlan = groupPlan(id),
            activity = activity(ActivityFilter(groupId = id)),
            flows = flows,
            history = history(
                listOf(EntityRef(EntityType.GROUP, id.value)) +
                    state.members.values.filter { it.groupId == id }.map { EntityRef(EntityType.GROUP_MEMBER, it.id.value) },
            ),
        )
    }

    fun groupPlan(groupId: GroupId): List<PlanItem> =
        SettlePlanner.planGroup(balances.groupNets[groupId].orEmpty())
            .map { PlanItem(generation, personRef(it.from), personRef(it.to), it.amount, groupRef(groupId)) }
}
```

Create `main/.../ledger/views/EntryViews.kt` with only the functions `LedgerViews.kt` needs so it compiles; Task 2 replaces the file with the full version:

```kotlin
package io.github.codex07.iou.ledger.views

import io.github.codex07.iou.api.ActivityFilter
import io.github.codex07.iou.api.ActivityItem
import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.RevisionView
import io.github.codex07.iou.ledger.model.Entry

fun LedgerViews.activity(filter: ActivityFilter): List<ActivityItem> = emptyList()

fun LedgerViews.activityItem(entry: Entry): ActivityItem = TODO("Task 2")

fun LedgerViews.history(refs: Collection<EntityRef>): List<RevisionView> = emptyList()
```

(The `TODO` lives for one task only; Task 2 Step 3 replaces this file.)

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.views.LedgerViewsTest"
```

Expected: `BUILD SUCCESSFUL`, 4 tests pass (the merge preview's example has no neutralized entry, so the `activityItem` stub isn't called).

- [ ] **Step 5: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/app/src/main/kotlin/io/github/codex07/iou/ledger/views android/app/src/test/kotlin/io/github/codex07/iou/ledger/views
git commit -m "Add LedgerViews for the overview, people, groups and merge preview"
```

---

### Task 2: Entry views (activity, detail, drafts, history, previews, settle-up)

**Files:**
- Modify: `main/.../ledger/views/EntryViews.kt` (replace the shell)
- Test: `test/.../ledger/views/EntryViewsTest.kt`

**Interfaces:**
- Consumes: Task 1, `EntryOps.splitInputsOf`, `EntryOps.previewShares` (Part 6), `RevisionDiff` (Part 4).
- Produces (extension functions on `LedgerViews`): `activity(filter)`, `activityItem(entry)`, `entry(id): EntryDetail?`, `shareViews(expense)`, `history(type, id)`, `history(refs)` (several rows merged newest first, each diffed against its own previous version), `subject(ref)`, `draftFromVersion(id, version): LedgerResult<EntryDraft>`, `splitPreview(draft, editing, now): LedgerResult<SplitPreview>`, `settleUpFromPlan(item, today): LedgerResult<SettleUpDraft>`, `settleUpWithPerson(id, basis, today): LedgerResult<List<SettleUpDraft>>`, `balanceSummaryText(id): LedgerResult<String>`; plus top-level `fxView(snapshot)`.

- [ ] **Step 1: Write the failing test**

`test/.../ledger/views/EntryViewsTest.kt`:

```kotlin
package io.github.codex07.iou.ledger.views

import io.github.codex07.iou.api.ActivityFilter
import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.EntryKind
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.FxChoice
import io.github.codex07.iou.api.FxQuote
import io.github.codex07.iou.api.HandleDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.ledger.ops.EntryOps
import io.github.codex07.iou.ledger.ops.OpsHarness
import io.github.codex07.iou.ledger.ops.PeopleOps
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.sek
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EntryViewsTest {

    private fun OpsHarness.views() = LedgerViews(state, revisions, generation, EUR) { if (it == EUR) "1" else null }

    private fun <T> LedgerResult<T>.ok(): T = (this as LedgerResult.Ok).value

    @Test
    fun `activity is newest first, filtered, and shows my balance effect`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val y = ledger.person("Y")
        val h = OpsHarness(ledger.state)
        val old = h.run { EntryOps.saveTransfer(it, TransferDraft(ME, x, eur(1_000), occurredOn = D0), SaveMode.Create(h.generation)) }.ok()
        val new = h.run { EntryOps.saveTransfer(it, TransferDraft(y, ME, eur(300), occurredOn = D0.plusDays(1)), SaveMode.Create(h.generation)) }.ok()

        h.views().activity(ActivityFilter()).map { it.entryId } shouldBe listOf(new, old)
        h.views().activity(ActivityFilter(personId = x)).map { it.entryId } shouldBe listOf(old)
        h.views().activity(ActivityFilter(kinds = setOf(EntryKind.EXPENSE))) shouldBe emptyList()
        h.views().activity(ActivityFilter()).associate { it.entryId to it.myBalanceEffect } shouldBe
            mapOf(new to eur(-300), old to eur(1_000))
    }

    @Test
    fun `the edit draft of a converted repayment keeps its settlement currency and snapshot`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        val quote = FxQuote(EUR, SEK, "11.1111111111", D0, FxSource.USER_ENTERED, false)
        val id = h.run {
            EntryOps.saveTransfer(it, TransferDraft(x, ME, eur(900), TransferKind.REPAYMENT, occurredOn = D0, settlesCurrency = SEK, fx = FxChoice.Rate(quote)), SaveMode.Create(h.generation))
        }.ok()

        val detail = h.views().entry(id)!!
        val draft = detail.editDraft as TransferDraft
        draft.settlesCurrency shouldBe SEK
        draft.fx shouldBe FxChoice.KeepExisting
        detail.fx!!.rate shouldBe "11.1111111111"
        detail.item.effect shouldBe sek(10_000)
        detail.token.version shouldBe 1
    }

    @Test
    fun `history diffs every revision against the previous one, newest first`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.transfer(ME, x, eur(4_000))
        val h = OpsHarness(ledger.state)
        val draft = h.views().entry(id)!!.editDraft as TransferDraft
        h.run { EntryOps.saveTransfer(it, draft.copy(amount = eur(4_500)), SaveMode.Update(id, h.views().entry(id)!!.token)) }

        val history = h.views().entry(id)!!.history
        history.map { it.version } shouldBe listOf(2, 1)
        history.first().action shouldBe RevisionAction.UPDATED
        history.first().changes shouldBe listOf(
            FieldChange("amount", "40.00 EUR", "45.00 EUR"),
            FieldChange("effect", "40.00 EUR", "45.00 EUR"),
        )
        h.views().draftFromVersion(id, 1).ok().let { (it as TransferDraft).amount } shouldBe eur(4_000)
    }

    @Test
    fun `a merge preview lists entries between the two records as neutralized`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val alexS = ledger.person("AlexS")
        val trip = ledger.group("Trip", members = listOf(ME, alex, alexS))
        val between = ledger.transfer(alex, alexS, eur(700), group = trip)
        ledger.transfer(ME, alex, eur(1_000))
        val h = OpsHarness(ledger.state)

        h.views().mergePreview(alexS, alex).ok().neutralizedEntries.map { it.entryId } shouldBe listOf(between)
    }

    @Test
    fun `person and group history include aliases, handles and memberships`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val alexS = ledger.person("AlexS")
        val trip = ledger.group("Trip", members = listOf(ME, alexS))
        val h = OpsHarness(ledger.state)
        val iban = HandleDraft(HandleType.IBAN, "GB82WEST12345698765432", label = "UK", isDefault = true)
        h.run { PeopleOps.addHandle(it, alexS, iban, h.generation) }
        h.run { PeopleOps.merge(it, alexS, alex, h.token(h.state.people.getValue(alexS))) }

        // Observing the alias shows the canonical person, whose history holds the alias's merge revision.
        val history = h.views().person(alexS)!!.history
        history.first().let { it.entity to it.reason } shouldBe (EntityRef(EntityType.PERSON, alexS.value) to "Merged into Alex")
        history.map { it.entity.type }.toSet() shouldBe setOf(EntityType.PERSON, EntityType.PAYMENT_HANDLE)
        history.single { it.entity.type == EntityType.PAYMENT_HANDLE }.subject shouldBe "IBAN · UK"
        h.views().group(trip)!!.history.map { it.entity.type }.toSet() shouldBe setOf(EntityType.GROUP, EntityType.GROUP_MEMBER)
    }

    @Test
    fun `split preview shows stored and new shares`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.expense(ME, eur(1_000), mapOf(ME to 500L, x to 500L), splitType = SplitType.EQUAL)
        val h = OpsHarness(ledger.state)
        val draft = h.views().entry(id)!!.editDraft as ExpenseDraft

        val same = h.views().splitPreview(draft, id, h.now).ok()
        same.recomputed shouldBe false
        same.storedShares!!.map { it.share } shouldBe listOf(eur(500), eur(500))

        val bigger = h.views().splitPreview(draft.copy(total = eur(1_001)), id, h.now).ok()
        bigger.recomputed shouldBe true
        bigger.shares.map { it.share }.sumOf { it.amountMinor } shouldBe 1_001L
    }

    @Test
    fun `settle-up drafts carry the generation and the currency being settled`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val keep = ledger.group("Flat", currency = EUR, fxMode = FxMode.KEEP, members = listOf(ME, a))
        ledger.expense(a, sek(2_000), mapOf(a to 1_000L, ME to 1_000L), group = keep)
        ledger.transfer(ME, a, eur(700))
        val h = OpsHarness(ledger.state)
        val views = h.views()

        val item = views.group(keep)!!.settlePlan.single()
        val fromPlan = views.settleUpFromPlan(item, D0).ok()
        fromPlan.basis shouldBe h.generation
        fromPlan.draft.from shouldBe ME
        fromPlan.draft.amount shouldBe sek(1_000)
        fromPlan.draft.settlesCurrency shouldBe SEK
        fromPlan.draft.kind shouldBe TransferKind.REPAYMENT
        views.settleUpFromPlan(item.copy(generation = LedgerGeneration("old")), D0) shouldBe LedgerResult.Err(LedgerError.LedgerReplaced)

        val forPerson = views.settleUpWithPerson(a, h.generation, D0).ok().single()
        forPerson.draft.from shouldBe a
        forPerson.draft.amount shouldBe eur(700)
        forPerson.draft.settlesCurrency shouldBe EUR
        views.balanceSummaryText(a) shouldBe LedgerResult.Ok("A owes you 7.00 EUR")
    }

    @Test
    fun `expense edit drafts list canonical participants once`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val alex2 = ledger.person("Alex2", mergedInto = alex)
        val bob = ledger.person("Bob")
        val trip = ledger.group("Trip", members = listOf(ME, alex, alex2, bob))
        val id = ledger.expense(ME, eur(900), mapOf(alex to 300L, alex2 to 300L, bob to 300L), group = trip, splitType = SplitType.EQUAL)
        val h = OpsHarness(ledger.state)

        (h.views().entry(id)!!.editDraft as ExpenseDraft).participants shouldBe listOf(SplitInput(alex), SplitInput(bob))
        h.views().entry(id)!!.shares.map { it.person.displayName to it.share } shouldBe listOf("Alex" to eur(600), "Bob" to eur(300))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.views.EntryViewsTest"
```

Expected: compilation fails, `Unresolved reference 'entry'` (and the other functions not in the shell).

- [ ] **Step 3: Implement**

Replace `main/.../ledger/views/EntryViews.kt`:

```kotlin
package io.github.codex07.iou.ledger.views

import io.github.codex07.iou.api.ActivityFilter
import io.github.codex07.iou.api.ActivityItem
import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.EntryDetail
import io.github.codex07.iou.api.EntryDraft
import io.github.codex07.iou.api.EntryKind
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.FlowView
import io.github.codex07.iou.api.FxChoice
import io.github.codex07.iou.api.FxQuote
import io.github.codex07.iou.api.FxSnapshotView
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PlanItem
import io.github.codex07.iou.api.RevisionView
import io.github.codex07.iou.api.SettleUpDraft
import io.github.codex07.iou.api.ShareView
import io.github.codex07.iou.api.SplitPreview
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.api.map
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.engine.RevisionDiff
import io.github.codex07.iou.ledger.engine.SettlePlanner
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.Entry
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.ledger.ops.EntryOps
import io.github.codex07.iou.ledger.ops.WriteContext
import io.github.codex07.iou.money.Money
import java.time.Instant
import java.time.LocalDate

fun LedgerViews.activity(filter: ActivityFilter): List<ActivityItem> {
    val person = filter.personId?.let(resolver::canonical)
    val from = filter.from
    val to = filter.to
    return state.entries.asSequence()
        .filter { filter.includeDeleted || !it.isDeleted }
        .filter { kindOf(it) in filter.kinds }
        .filter { filter.groupId == null || it.groupId == filter.groupId }
        .filter { person == null || it.personIds.any { id -> resolver.canonical(id) == person } }
        .filter { from == null || !it.occurredOn.isBefore(from) }
        .filter { to == null || !it.occurredOn.isAfter(to) }
        .sortedWith(compareByDescending<Entry> { it.occurredOn }.thenByDescending { it.meta.createdAt })
        .map { activityItem(it) }
        .toList()
}

fun LedgerViews.activityItem(entry: Entry): ActivityItem = when (entry) {
    is Transfer -> ActivityItem(
        entryId = entry.id, kind = EntryKind.TRANSFER, occurredOn = entry.occurredOn, description = null,
        note = entry.note, paidBy = personRef(entry.fromPersonId), receiver = personRef(entry.toPersonId),
        participants = emptyList(), transferKind = entry.kind, category = null,
        group = entry.groupId?.let(::groupRef), paid = entry.amount, effect = entry.effectAmount,
        myBalanceEffect = myEffect(entry), deleted = entry.isDeleted,
    )
    is Expense -> ActivityItem(
        entryId = entry.id, kind = EntryKind.EXPENSE, occurredOn = entry.occurredOn, description = entry.description,
        note = entry.note, paidBy = personRef(entry.payerPersonId), receiver = null,
        participants = entry.shares.map { resolver.canonical(it.personId) }.distinct().map(::personRef),
        transferKind = null, category = entry.category,
        group = entry.groupId?.let(::groupRef), paid = entry.total, effect = entry.effectTotal,
        myBalanceEffect = myEffect(entry), deleted = entry.isDeleted,
    )
}

fun LedgerViews.entry(id: EntryId): EntryDetail? {
    val entry = state.entry(id) ?: return null
    val keep = if (entry.fxSnapshotId != null) FxChoice.KeepExisting else null
    return EntryDetail(
        generation = generation,
        item = activityItem(entry),
        token = token(entry),
        shares = (entry as? Expense)?.let { shareViews(it) }.orEmpty(),
        fx = entry.fxSnapshotId?.let { state.fxSnapshots[it] }?.let(::fxView),
        flows = LedgerEngine.entryEffects(entry, resolver).map { FlowView(personRef(it.creditor), personRef(it.debtor), it.amount) },
        editDraft = draftOf(entry, keep),
        history = history(entry.entityType, id.value),
    )
}

/** Shares per canonical person, records of one person added up. */
fun LedgerViews.shareViews(expense: Expense): List<ShareView> {
    val inputs = EntryOps.splitInputsOf(expense, resolver).associate { it.personId to it.value }
    val sums = LinkedHashMap<PersonId, Long>()
    for (share in expense.shares) {
        val person = resolver.canonical(share.personId)
        sums[person] = Math.addExact(sums[person] ?: 0L, share.shareMinor)
    }
    return sums.map { (person, sum) -> ShareView(personRef(person), Money(sum, expense.effectTotal.currency), inputs[person]) }
}

fun fxView(snapshot: FxSnapshot) = FxSnapshotView(
    base = snapshot.baseCurrency,
    quote = snapshot.quoteCurrency,
    rate = snapshot.rate,
    rateDate = snapshot.rateDate,
    source = snapshot.source,
    isStale = snapshot.isStale,
    paid = snapshot.paidMinor?.let { Money(it, snapshot.baseCurrency) },
    charged = snapshot.chargedMinor?.let { Money(it, snapshot.quoteCurrency) },
)

/** Revisions of one row, newest first, each diffed against the previous version (spec §5.3). */
fun LedgerViews.history(type: EntityType, id: String): List<RevisionView> = history(listOf(EntityRef(type, id)))

/** Revisions of several rows in one list, newest first; each is diffed against its own row's previous version. */
fun LedgerViews.history(refs: Collection<EntityRef>): List<RevisionView> {
    val wanted = refs.toSet()
    return revisions
        .filter { EntityRef(it.entityType, it.entityId) in wanted }
        .groupBy { EntityRef(it.entityType, it.entityId) }
        .flatMap { (ref, revisionsOfRow) ->
            val own = revisionsOfRow.sortedBy { it.version }
            val subject = subject(ref)
            own.mapIndexed { index, revision ->
                RevisionView(
                    entity = ref,
                    subject = subject,
                    version = revision.version,
                    action = revision.action,
                    at = revision.at,
                    author = personRef(revision.authorId),
                    deviceId = revision.deviceId,
                    reason = revision.reason,
                    changes = RevisionDiff.diff(own.getOrNull(index - 1)?.snapshot, revision.snapshot, names),
                )
            }
        }
        .sortedWith(compareByDescending<RevisionView> { it.at }.thenByDescending { it.version }.thenBy { it.entity.id })
}

/** How a row is named in a mixed history (`RevisionView.subject`). */
fun LedgerViews.subject(ref: EntityRef): String = when (val entity = state.entity(ref.type, ref.id)) {
    is Person -> entity.displayName
    is PaymentHandle -> "${entity.type.name} · ${entity.label}"
    is Group -> entity.name
    is GroupMember -> "${rawRef(entity.personId).displayName} in ${groupRef(entity.groupId).name}"
    is Transfer -> "Transfer on ${entity.occurredOn}"
    is Expense -> entity.description
    null -> ref.id
}

/** "Open as draft" (spec §5.3): the old version's fields, with its FX as a fresh choice. */
fun LedgerViews.draftFromVersion(id: EntryId, version: Int): LedgerResult<EntryDraft> {
    val old = revisions.firstOrNull { it.entityId == id.value && it.version == version }?.snapshot as? Entry
        ?: return LedgerResult.Err(LedgerError.NotFound("${id.value} v$version"))
    val fx = old.fxSnapshotId?.let { state.fxSnapshots[it] }?.let { snapshot ->
        if (snapshot.source == FxSource.BANK_ACTUAL) {
            FxChoice.BankActual(Money(snapshot.chargedMinor!!, snapshot.quoteCurrency))
        } else {
            FxChoice.Rate(FxQuote(snapshot.baseCurrency, snapshot.quoteCurrency, snapshot.rate, snapshot.rateDate, snapshot.source, snapshot.isStale))
        }
    }
    return LedgerResult.Ok(draftOf(old, fx))
}

fun LedgerViews.splitPreview(draft: ExpenseDraft, editing: EntryId?, now: Instant): LedgerResult<SplitPreview> {
    val existing = editing?.let { state.expenses[it] ?: return LedgerResult.Err(LedgerError.NotFound(it.value)) }
    val ctx = WriteContext(state, generation, now, IdGenerator.Random)
    return EntryOps.previewShares(ctx, draft, existing).map { preview ->
        SplitPreview(
            effectTotal = preview.effectTotal,
            shares = preview.shares.map { ShareView(personRef(it.personId), Money(it.shareMinor, preview.effectTotal.currency), it.inputValue) },
            storedShares = existing?.let { shareViews(it) },
            recomputed = preview.recomputed,
        )
    }
}

fun LedgerViews.settleUpFromPlan(item: PlanItem, today: LocalDate): LedgerResult<SettleUpDraft> {
    if (item.generation != generation) return LedgerResult.Err(LedgerError.LedgerReplaced)
    val group = item.group?.let { state.groups[it.id] }
    val settles = if (group != null && group.fxMode == FxMode.KEEP) item.amount.currency else null
    val draft = TransferDraft(
        from = item.from.id, to = item.to.id, amount = item.amount, kind = TransferKind.REPAYMENT,
        groupId = item.group?.id, occurredOn = today, settlesCurrency = settles,
    )
    return LedgerResult.Ok(SettleUpDraft(draft, generation))
}

fun LedgerViews.settleUpWithPerson(id: PersonId, basis: LedgerGeneration, today: LocalDate): LedgerResult<List<SettleUpDraft>> {
    if (basis != generation) return LedgerResult.Err(LedgerError.LedgerReplaced)
    val canonical = resolver.canonical(id)
    if (canonical !in state.people) return LedgerResult.Err(LedgerError.NotFound(id.value))
    val drafts = SettlePlanner.planPerson(meId, canonical, balances.net(canonical)).map {
        SettleUpDraft(
            TransferDraft(it.from, it.to, it.amount, TransferKind.REPAYMENT, occurredOn = today, settlesCurrency = it.amount.currency),
            generation,
        )
    }
    return LedgerResult.Ok(drafts)
}

fun LedgerViews.balanceSummaryText(id: PersonId): LedgerResult<String> {
    val canonical = resolver.canonical(id)
    if (canonical !in state.people) return LedgerResult.Err(LedgerError.NotFound(id.value))
    val name = personRef(canonical).displayName
    val net = amounts(balances.net(canonical))
    if (net.isEmpty()) return LedgerResult.Ok("You and $name are settled up.")
    return LedgerResult.Ok(
        net.joinToString("\n") { if (it.amountMinor > 0) "$name owes you $it" else "You owe $name ${-it}" },
    )
}

// ---- internals ----

private fun kindOf(entry: Entry) = if (entry is Transfer) EntryKind.TRANSFER else EntryKind.EXPENSE

private fun LedgerViews.myEffect(entry: Entry): Money? {
    val mine = LedgerEngine.entryEffects(entry, resolver).filter { it.creditor == meId || it.debtor == meId }
    if (mine.isEmpty()) return null
    val sum = mine.fold(0L) { acc, effect ->
        if (effect.creditor == meId) Math.addExact(acc, effect.amount.amountMinor) else Math.subtractExact(acc, effect.amount.amountMinor)
    }
    return Money(sum, entry.effect.currency)
}

/** An edit draft pre-filled as spec §4.4/§4.5 require: canonical people, `settlesCurrency` loaded. */
private fun LedgerViews.draftOf(entry: Entry, fx: FxChoice?): EntryDraft = when (entry) {
    is Transfer -> {
        val inConvertGroup = entry.groupId?.let { state.groups[it] }?.fxMode == FxMode.CONVERT
        TransferDraft(
            from = personRef(entry.fromPersonId).id,
            to = personRef(entry.toPersonId).id,
            amount = entry.amount,
            kind = entry.kind,
            method = entry.method,
            accountLabel = entry.accountLabel,
            groupId = entry.groupId,
            occurredOn = entry.occurredOn,
            note = entry.note,
            settlesCurrency = if (!inConvertGroup && entry.effectAmount.currency != entry.amount.currency) entry.effectAmount.currency else null,
            fx = fx,
        )
    }
    is Expense -> ExpenseDraft(
        payer = personRef(entry.payerPersonId).id,
        description = entry.description,
        category = entry.category,
        total = entry.total,
        groupId = entry.groupId,
        splitType = entry.splitType,
        participants = EntryOps.splitInputsOf(entry, resolver),
        occurredOn = entry.occurredOn,
        note = entry.note,
        fx = fx,
    )
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.views.*"
```

Expected: `BUILD SUCCESSFUL`; `LedgerViewsTest` (4) and `EntryViewsTest` (8) pass.

- [ ] **Step 5: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/app/src/main/kotlin/io/github/codex07/iou/ledger/views android/app/src/test/kotlin/io/github/codex07/iou/ledger/views/EntryViewsTest.kt
git commit -m "Add entry views: activity, detail, edit drafts, history, previews and settle-up drafts"
```

---

### Task 3: `InMemoryLedger` and `FakeRates`

**Files:**
- Create: `main/.../fake/InMemoryLedger.kt`, `main/.../fake/FakeRates.kt`
- Test: `test/.../fake/InMemoryLedgerTest.kt`

**Interfaces:**
- Consumes: `WriteContext`, `WritePlan`, `Planned` (Part 6), `LedgerViews` (Tasks 1–2), `Settings`, `LockTimeout` (Part 3).
- Produces:
  - `data class FakeSnapshot(state, revisions, generation, ledgerSeq, settings, importBatches = emptyList(), lastBackupAt = null, preRestore: Pair<LedgerState, List<EntryRevision>>? = null)`.
  - `class InMemoryLedger(clock = Clock.systemUTC(), ids = IdGenerator.Random, settings = DefaultSettings)` with `deviceId`, `snapshot: StateFlow<FakeSnapshot>`, `views: Flow<LedgerViews>`, `ready: Flow<LedgerViews>` (only after setup), `currentViews()`, `now()`, `today()`, `newGeneration()`, `write(beforeSetup = false, op)`, `update(transform)`.
  - `object FakeRates { val perEur: Map<String, String>; fun rate(from, to): String? }`.

- [ ] **Step 1: Write the failing test**

`test/.../fake/InMemoryLedgerTest.kt`:

```kotlin
package io.github.codex07.iou.fake

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.ops.PeopleOps
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class InMemoryLedgerTest {

    private fun ledger() = InMemoryLedger(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("fake"))

    @Test
    fun `writes before setup are refused, setup works`() = runTest {
        val ledger = ledger()
        ledger.write { PeopleOps.createPerson(it, PersonDraft("X"), ledger.snapshot.value.generation) } shouldBe
            LedgerResult.Err(LedgerError.NotFound("me"))
        (ledger.write(beforeSetup = true) { PeopleOps.setup(it, "Me") } is LedgerResult.Ok) shouldBe true
        ledger.ready.first().state.me.displayName shouldBe "Me"
    }

    @Test
    fun `a successful write applies the plan, records one revision per change and bumps ledgerSeq`() {
        val ledger = ledger()
        ledger.write(beforeSetup = true) { PeopleOps.setup(it, "Me") }
        val seq = ledger.snapshot.value.ledgerSeq
        val id = (ledger.write { PeopleOps.createPerson(it, PersonDraft("X"), ledger.snapshot.value.generation) } as LedgerResult.Ok).value

        val snapshot = ledger.snapshot.value
        snapshot.state.people.getValue(id).displayName shouldBe "X"
        snapshot.ledgerSeq shouldBe seq + 1
        val revision = snapshot.revisions.last()
        revision.entityId shouldBe id.value
        revision.action shouldBe RevisionAction.CREATED
        revision.deviceId shouldBe ledger.deviceId
        revision.at shouldBe T0
    }

    @Test
    fun `a failed write changes nothing`() {
        val ledger = ledger()
        ledger.write(beforeSetup = true) { PeopleOps.setup(it, "Me") }
        val before = ledger.snapshot.value
        ledger.write { PeopleOps.createPerson(it, PersonDraft(" "), before.generation) } shouldBe
            LedgerResult.Err(LedgerError.InvalidField("displayName"))
        ledger.snapshot.value shouldBe before
    }

    @Test
    fun `fake rates cross through EUR`() {
        FakeRates.rate(EUR, EUR) shouldBe "1"
        FakeRates.rate(EUR, SEK) shouldBe "11.5"
        FakeRates.rate(SEK, EUR) shouldBe "0.08695652173913043478260869565217391"
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.fake.InMemoryLedgerTest"
```

Expected: compilation fails, `Unresolved reference 'InMemoryLedger'`.

- [ ] **Step 3: Implement**

`main/.../fake/FakeRates.kt`:

```kotlin
package io.github.codex07.iou.fake

import io.github.codex07.iou.ledger.engine.FxConverter
import io.github.codex07.iou.money.CurrencyCode

/** Fixed demo rates: units of each currency per 1 EUR. */
object FakeRates {
    val perEur: Map<String, String> = mapOf(
        "EUR" to "1", "SEK" to "11.5", "USD" to "1.08", "GBP" to "0.86", "NOK" to "11.7",
        "DKK" to "7.46", "CHF" to "0.94", "JPY" to "160", "PLN" to "4.3",
    )

    /** Units of [to] per one unit of [from]; null for currencies outside the table. */
    fun rate(from: CurrencyCode, to: CurrencyCode): String? {
        if (from == to) return "1"
        val fromPerEur = perEur[from.code] ?: return null
        val toPerEur = perEur[to.code] ?: return null
        return FxConverter.cross(homeToBase = fromPerEur, homeToQuote = toPerEur)
    }
}
```

`main/.../fake/InMemoryLedger.kt`:

```kotlin
package io.github.codex07.iou.fake

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.api.Settings
import io.github.codex07.iou.api.map
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.ledger.model.ImportBatch
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.RevisionId
import io.github.codex07.iou.ledger.ops.Planned
import io.github.codex07.iou.ledger.ops.WriteContext
import io.github.codex07.iou.ledger.ops.WritePlan
import io.github.codex07.iou.ledger.views.LedgerViews
import io.github.codex07.iou.money.CurrencyCode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Everything the fake holds, replaced as one value on every change. */
data class FakeSnapshot(
    val state: LedgerState,
    val revisions: List<EntryRevision>,
    val generation: LedgerGeneration,
    val ledgerSeq: Long,
    val settings: Settings,
    val importBatches: List<ImportBatch> = emptyList(),
    val lastBackupAt: Instant? = null,
    val preRestore: Pair<LedgerState, List<EntryRevision>>? = null,
)

/** The fake's store: applies validated plans from `ledger.ops` and publishes snapshots. */
class InMemoryLedger(
    val clock: Clock = Clock.systemUTC(),
    val ids: IdGenerator = IdGenerator.Random,
    settings: Settings = DefaultSettings,
) {
    val deviceId = DeviceId(ids.next())
    private val lock = Any()
    private val current = MutableStateFlow(
        FakeSnapshot(LedgerState(), emptyList(), LedgerGeneration(ids.next()), ledgerSeq = 0, settings = settings),
    )

    val snapshot: StateFlow<FakeSnapshot> = current.asStateFlow()

    val views: Flow<LedgerViews> = current.map(::viewsOf)

    /** Views once setup has run; ledger-data reads observe this. */
    val ready: Flow<LedgerViews> = views.filter { it.state.meOrNull != null }

    fun currentViews(): LedgerViews = viewsOf(current.value)

    fun now(): Instant = clock.instant()

    fun today(): LocalDate = LocalDate.now(clock)

    fun newGeneration() = LedgerGeneration(ids.next())

    /** Runs [op] against the current state; on success applies its plan atomically. */
    fun <T> write(beforeSetup: Boolean = false, op: (WriteContext) -> LedgerResult<Planned<T>>): LedgerResult<T> {
        synchronized(lock) {
            val snapshot = current.value
            if (!beforeSetup && snapshot.state.meOrNull == null) return LedgerResult.Err(LedgerError.NotFound("me"))
            val result = op(WriteContext(snapshot.state, snapshot.generation, now(), ids))
            if (result is LedgerResult.Ok && !result.value.plan.isEmpty) current.value = applied(snapshot, result.value.plan)
            return result.map { it.value }
        }
    }

    /** Non-ledger changes (settings, simulated backup and import state). */
    fun update(transform: (FakeSnapshot) -> FakeSnapshot) = synchronized(lock) {
        current.value = transform(current.value)
    }

    private fun applied(snapshot: FakeSnapshot, plan: WritePlan): FakeSnapshot {
        val at = now()
        val revisions = plan.changes.map { change ->
            val entity = change.entity
            EntryRevision(
                id = RevisionId(ids.next()), entityType = entity.entityType, entityId = entity.entityId,
                version = entity.meta.version, action = change.action, authorId = entity.meta.authorId,
                deviceId = deviceId, at = at, reason = change.reason, importBatchId = entity.meta.importBatchId,
                snapshot = entity,
            )
        }
        return snapshot.copy(
            state = snapshot.state.applying(plan.changes, plan.snapshots),
            revisions = snapshot.revisions + revisions,
            ledgerSeq = snapshot.ledgerSeq + 1,
        )
    }

    private fun viewsOf(snapshot: FakeSnapshot): LedgerViews {
        val home = snapshot.settings.homeCurrency
        return LedgerViews(snapshot.state, snapshot.revisions, snapshot.generation, home) { FakeRates.rate(it, home) }
    }

    companion object {
        val DefaultSettings = Settings(
            homeCurrency = CurrencyCode("EUR"),
            appLockEnabled = false,
            lockTimeout = LockTimeout.ONE_MINUTE,
            screenProtection = false,
            backupEncryptionDefault = true,
        )
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.fake.InMemoryLedgerTest"
```

Expected: `BUILD SUCCESSFUL`, 4 tests pass. (The §4.7 replay property runs over the seed data in Task 6.)

- [ ] **Step 5: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/app/src/main/kotlin/io/github/codex07/iou/fake android/app/src/test/kotlin/io/github/codex07/iou/fake
git commit -m "Add the in-memory ledger and fake FX rates"
```

---

### Task 4: The eight fake APIs

**Files:**
- Create: `main/.../fake/FakeLedgerApis.kt`, `main/.../fake/FakeServiceApis.kt`, `main/.../fake/FakeApis.kt`

**Interfaces:**
- Consumes: Tasks 1–3, all ops (Part 6), the `api` interfaces (Part 3).
- Produces: `FakeSetupApi`, `FakePeopleApi`, `FakeGroupsApi`, `FakeLedgerApi`, `FakeFxApi`, `FakeBackupApi`, `FakeImportExportApi`, `FakeSettingsApi`; `object FakeApis { fun create(ledger): Apis; fun seeded(clock = Clock.systemUTC()): Apis }` (`seeded` needs `SeedData` from Task 6; add it then, see Task 6 Step 3). Tested by the contract suites in Task 5.

- [ ] **Step 1: Write the ledger-backed APIs**

`main/.../fake/FakeLedgerApis.kt`:

```kotlin
package io.github.codex07.iou.fake

import io.github.codex07.iou.api.ActivityFilter
import io.github.codex07.iou.api.ActivityItem
import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.EntryDetail
import io.github.codex07.iou.api.EntryDraft
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.GroupDetail
import io.github.codex07.iou.api.GroupDraft
import io.github.codex07.iou.api.GroupSummary
import io.github.codex07.iou.api.GroupsApi
import io.github.codex07.iou.api.HandleDraft
import io.github.codex07.iou.api.LedgerApi
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.MergePreview
import io.github.codex07.iou.api.Overview
import io.github.codex07.iou.api.PeopleApi
import io.github.codex07.iou.api.PersonDetail
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.PersonSummary
import io.github.codex07.iou.api.PlanItem
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.SettleUpDraft
import io.github.codex07.iou.api.SetupApi
import io.github.codex07.iou.api.SetupState
import io.github.codex07.iou.api.SplitPreview
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.ops.EntryOps
import io.github.codex07.iou.ledger.ops.GroupOps
import io.github.codex07.iou.ledger.ops.PeopleOps
import io.github.codex07.iou.ledger.ops.RevertOps
import io.github.codex07.iou.ledger.views.activity
import io.github.codex07.iou.ledger.views.balanceSummaryText
import io.github.codex07.iou.ledger.views.draftFromVersion
import io.github.codex07.iou.ledger.views.entry
import io.github.codex07.iou.ledger.views.settleUpFromPlan
import io.github.codex07.iou.ledger.views.settleUpWithPerson
import io.github.codex07.iou.ledger.views.splitPreview
import io.github.codex07.iou.money.CurrencyCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class FakeSetupApi(private val ledger: InMemoryLedger) : SetupApi {
    override fun observeSetupState(): Flow<SetupState> = ledger.views
        .map { views -> views.state.meOrNull?.let { SetupState.Ready(views.rawRef(it.id)) } ?: SetupState.NeedsSetup }
        .distinctUntilChanged()

    override suspend fun completeSetup(displayName: String, homeCurrency: CurrencyCode): LedgerResult<PersonId> {
        val result = ledger.write(beforeSetup = true) { PeopleOps.setup(it, displayName) }
        if (result is LedgerResult.Ok) ledger.update { it.copy(settings = it.settings.copy(homeCurrency = homeCurrency)) }
        return result
    }
}

class FakePeopleApi(private val ledger: InMemoryLedger) : PeopleApi {
    override fun observePeople(includeArchived: Boolean): Flow<List<PersonSummary>> =
        ledger.ready.map { it.people(includeArchived) }.distinctUntilChanged()

    override fun observePerson(id: PersonId): Flow<PersonDetail?> = ledger.ready.map { it.person(id) }.distinctUntilChanged()

    override suspend fun createPerson(draft: PersonDraft, basis: LedgerGeneration) =
        ledger.write { PeopleOps.createPerson(it, draft, basis) }

    override suspend fun updatePerson(id: PersonId, draft: PersonDraft, token: EditToken) =
        ledger.write { PeopleOps.updatePerson(it, id, draft, token) }

    override suspend fun setPersonArchived(id: PersonId, archived: Boolean, token: EditToken) =
        ledger.write { PeopleOps.setPersonArchived(it, id, archived, token) }

    override suspend fun deletePerson(id: PersonId, token: EditToken) = ledger.write { PeopleOps.deletePerson(it, id, token) }

    override suspend fun restorePerson(id: PersonId, token: EditToken) = ledger.write { PeopleOps.restorePerson(it, id, token) }

    override suspend fun addHandle(personId: PersonId, draft: HandleDraft, basis: LedgerGeneration): LedgerResult<HandleId> =
        ledger.write { PeopleOps.addHandle(it, personId, draft, basis) }

    override suspend fun updateHandle(id: HandleId, draft: HandleDraft, token: EditToken) =
        ledger.write { PeopleOps.updateHandle(it, id, draft, token) }

    override suspend fun deleteHandle(id: HandleId, token: EditToken) = ledger.write { PeopleOps.deleteHandle(it, id, token) }

    override suspend fun previewMerge(source: PersonId, target: PersonId): LedgerResult<MergePreview> =
        ledger.currentViews().mergePreview(source, target)

    override suspend fun merge(source: PersonId, target: PersonId, sourceToken: EditToken) =
        ledger.write { PeopleOps.merge(it, source, target, sourceToken) }

    override suspend fun unmerge(person: PersonId, token: EditToken) = ledger.write { PeopleOps.unmerge(it, person, token) }

    override suspend fun balanceSummaryText(id: PersonId): LedgerResult<String> = ledger.currentViews().balanceSummaryText(id)
}

class FakeGroupsApi(private val ledger: InMemoryLedger) : GroupsApi {
    override fun observeGroups(includeArchived: Boolean): Flow<List<GroupSummary>> =
        ledger.ready.map { it.groups(includeArchived) }.distinctUntilChanged()

    override fun observeGroup(id: GroupId): Flow<GroupDetail?> = ledger.ready.map { it.group(id) }.distinctUntilChanged()

    override suspend fun createGroup(draft: GroupDraft, members: List<PersonId>, basis: LedgerGeneration): LedgerResult<GroupId> =
        ledger.write { GroupOps.createGroup(it, draft, members, basis) }

    override suspend fun updateGroup(id: GroupId, draft: GroupDraft, token: EditToken) =
        ledger.write { GroupOps.updateGroup(it, id, draft, token) }

    override suspend fun setGroupArchived(id: GroupId, archived: Boolean, token: EditToken) =
        ledger.write { GroupOps.setGroupArchived(it, id, archived, token) }

    override suspend fun deleteGroup(id: GroupId, token: EditToken) = ledger.write { GroupOps.deleteGroup(it, id, token) }

    override suspend fun restoreGroup(id: GroupId, token: EditToken) = ledger.write { GroupOps.restoreGroup(it, id, token) }

    override suspend fun addMember(groupId: GroupId, personId: PersonId, basis: LedgerGeneration) =
        ledger.write { GroupOps.addMember(it, groupId, personId, basis) }

    override suspend fun removeMember(groupId: GroupId, personId: PersonId, basis: LedgerGeneration) =
        ledger.write { GroupOps.removeMember(it, groupId, personId, basis) }
}

class FakeLedgerApi(private val ledger: InMemoryLedger) : LedgerApi {
    override fun observeOverview(): Flow<Overview> = ledger.ready.map { it.overview() }.distinctUntilChanged()

    override fun observeActivity(filter: ActivityFilter): Flow<List<ActivityItem>> =
        ledger.ready.map { it.activity(filter) }.distinctUntilChanged()

    override fun observeEntry(id: EntryId): Flow<EntryDetail?> = ledger.ready.map { it.entry(id) }.distinctUntilChanged()

    override suspend fun saveTransfer(draft: TransferDraft, mode: SaveMode): LedgerResult<EntryId> =
        ledger.write { EntryOps.saveTransfer(it, draft, mode) }

    override suspend fun saveExpense(draft: ExpenseDraft, mode: SaveMode): LedgerResult<EntryId> =
        ledger.write { EntryOps.saveExpense(it, draft, mode) }

    override suspend fun previewSplit(draft: ExpenseDraft, editing: EntryId?): LedgerResult<SplitPreview> =
        ledger.currentViews().splitPreview(draft, editing, ledger.now())

    override suspend fun settleUpFromPlan(item: PlanItem): LedgerResult<SettleUpDraft> =
        ledger.currentViews().settleUpFromPlan(item, ledger.today())

    override suspend fun settleUpWithPerson(id: PersonId, basis: LedgerGeneration): LedgerResult<List<SettleUpDraft>> =
        ledger.currentViews().settleUpWithPerson(id, basis, ledger.today())

    override suspend fun deleteEntry(id: EntryId, token: EditToken) = ledger.write { EntryOps.deleteEntry(it, id, token) }

    override suspend fun restoreEntry(id: EntryId, token: EditToken) = ledger.write { EntryOps.restoreEntry(it, id, token) }

    override suspend fun revertTo(entity: EntityRef, version: Int, token: EditToken) =
        ledger.write { RevertOps.revertTo(it, entity, version, token, ledger.snapshot.value.revisions) }

    override suspend fun draftFromVersion(id: EntryId, version: Int): LedgerResult<EntryDraft> =
        ledger.currentViews().draftFromVersion(id, version)
}
```

- [ ] **Step 2: Write the simulated service APIs**

`main/.../fake/FakeServiceApis.kt`:

```kotlin
package io.github.codex07.iou.fake

import io.github.codex07.iou.api.BackupApi
import io.github.codex07.iou.api.BackupInfo
import io.github.codex07.iou.api.BackupReminder
import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.ExportInfo
import io.github.codex07.iou.api.ExportRequest
import io.github.codex07.iou.api.ExportScope
import io.github.codex07.iou.api.ExportShape
import io.github.codex07.iou.api.FxApi
import io.github.codex07.iou.api.FxQuote
import io.github.codex07.iou.api.ImportBatchView
import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.ImportCounts
import io.github.codex07.iou.api.ImportExportApi
import io.github.codex07.iou.api.ImportIdentity
import io.github.codex07.iou.api.ImportPreview
import io.github.codex07.iou.api.ImportRow
import io.github.codex07.iou.api.ImportRowChoice
import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.api.InvalidRow
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.api.Settings
import io.github.codex07.iou.api.SettingsApi
import io.github.codex07.iou.api.UndoImportResult
import io.github.codex07.iou.api.map
import io.github.codex07.iou.ledger.engine.FxConverter
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.ImportBatch
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.ImportSource
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class FakeFxApi : FxApi {
    override suspend fun quote(date: LocalDate, from: CurrencyCode, to: CurrencyCode): LedgerResult<FxQuote> {
        val rate = FakeRates.rate(from, to) ?: return LedgerResult.Err(LedgerError.RateRequired(from, to))
        return LedgerResult.Ok(FxQuote(from, to, rate, date, FxSource.ECB_FRANKFURTER, isStale = false))
    }

    override fun userRate(date: LocalDate, from: CurrencyCode, to: CurrencyCode, rate: String): LedgerResult<FxQuote> =
        FxConverter.validateRate(rate).map { FxQuote(from, to, rate.trim(), date, FxSource.USER_ENTERED, isStale = false) }

    override fun rateFromCharged(date: LocalDate, paid: Money, charged: Money): LedgerResult<FxQuote> {
        if (paid.amountMinor <= 0 || charged.amountMinor <= 0) return LedgerResult.Err(LedgerError.InvalidAmount)
        val rate = FxConverter.deriveRate(paid, charged)
        return LedgerResult.Ok(FxQuote(paid.currency, charged.currency, rate, date, FxSource.BANK_ACTUAL, isStale = false))
    }
}

/** Simulated backups (spec §6.3): restoring brings back the current data under a fresh generation. */
class FakeBackupApi(private val ledger: InMemoryLedger) : BackupApi {
    private val passphrases = ConcurrentHashMap<String, String>()

    override fun observeLedgerGeneration(): Flow<LedgerGeneration> = ledger.snapshot.map { it.generation }.distinctUntilChanged()

    override fun observeBackupReminder(): Flow<BackupReminder> = ledger.snapshot.map { snapshot ->
        val last = snapshot.lastBackupAt
        BackupReminder(due = last == null || Duration.between(last, ledger.now()) > Duration.ofDays(30), lastBackupAt = last)
    }.distinctUntilChanged()

    override suspend fun createBackup(target: DocumentRef, passphrase: CharArray?): LedgerResult<BackupInfo> {
        val now = ledger.now()
        passphrases[target.uri] = passphrase?.concatToString().orEmpty()
        ledger.update { it.copy(lastBackupAt = now) }
        return LedgerResult.Ok(BackupInfo(encrypted = passphrase != null, createdAt = now))
    }

    override suspend fun restoreBackup(source: DocumentRef, passphrase: CharArray?): LedgerResult<Unit> {
        val stored = passphrases[source.uri] ?: return LedgerResult.Err(LedgerError.CorruptFile("No IOU backup at ${source.uri}"))
        if (stored != passphrase?.concatToString().orEmpty()) return LedgerResult.Err(LedgerError.WrongPassphraseOrDamagedFile)
        ledger.update {
            it.copy(generation = ledger.newGeneration(), ledgerSeq = it.ledgerSeq + 1, preRestore = it.state to it.revisions)
        }
        return LedgerResult.Ok(Unit)
    }

    override fun observeUndoRestoreAvailable(): Flow<Boolean> = ledger.snapshot.map { it.preRestore != null }.distinctUntilChanged()

    override suspend fun undoLastRestore(): LedgerResult<Unit> {
        var undone = false
        ledger.update { snapshot ->
            val previous = snapshot.preRestore ?: return@update snapshot
            undone = true
            snapshot.copy(
                state = previous.first, revisions = previous.second, preRestore = snapshot.state to snapshot.revisions,
                generation = ledger.newGeneration(), ledgerSeq = snapshot.ledgerSeq + 1,
            )
        }
        return if (undone) LedgerResult.Ok(Unit) else LedgerResult.Err(LedgerError.NotFound("restore"))
    }
}

/** Simulated import/export (see this plan's "Decisions"): canned previews, real staleness checks. */
class FakeImportExportApi(private val ledger: InMemoryLedger) : ImportExportApi {

    override suspend fun export(request: ExportRequest, target: DocumentRef): LedgerResult<ExportInfo> {
        if (request.shape == ExportShape.NORMALIZED && request.scope != ExportScope.FullLedger) {
            return LedgerResult.Err(LedgerError.InvalidField("scope"))
        }
        return LedgerResult.Ok(ExportInfo(rowCount = ledger.snapshot.value.state.entries.size))
    }

    override suspend fun parseImport(source: DocumentRef): LedgerResult<ImportPreview> {
        val name = source.uri.substringAfterLast('/')
        if ("corrupt" in name) return LedgerResult.Err(LedgerError.CorruptFile("Not an IOU export: $name"))
        val identity = if ("foreign" in name) ImportIdentity.NeedsConfirmation(PersonId("foreign-me")) else ImportIdentity.Same
        return LedgerResult.Ok(cannedPreview(name, identity))
    }

    override suspend fun confirmIdentity(preview: ImportPreview, accept: Boolean): LedgerResult<ImportPreview> {
        // A preview parsed before a restore belongs to the replaced ledger, whatever the answer.
        if (preview.generation != ledger.snapshot.value.generation) return LedgerResult.Err(LedgerError.LedgerReplaced)
        val pending = preview.identity as? ImportIdentity.NeedsConfirmation ?: return LedgerResult.Ok(preview)
        if (!accept) return LedgerResult.Err(LedgerError.ForeignLedger)
        return LedgerResult.Ok(cannedPreview(preview.fileName, ImportIdentity.Mapped(pending.sourceMe)))
    }

    override fun applyChoices(preview: ImportPreview, choices: ImportChoices): ImportPreview = preview

    /** Rebuilds the preview against the current ledger; rows that are new or whose diff changed are flagged. */
    override suspend fun refreshPreview(preview: ImportPreview): LedgerResult<ImportPreview> {
        if (preview.generation != ledger.snapshot.value.generation) return LedgerResult.Err(LedgerError.LedgerReplaced)
        val previous = preview.rows.associateBy { it.key }
        val fresh = cannedPreview(preview.fileName, preview.identity)
        return LedgerResult.Ok(
            fresh.copy(id = preview.id, rows = fresh.rows.map { it.copy(changedSinceLastPreview = previous[it.key]?.changes != it.changes) }),
        )
    }

    override suspend fun commitImport(preview: ImportPreview, choices: ImportChoices): LedgerResult<ImportBatchId> {
        var result: LedgerResult<ImportBatchId> = LedgerResult.Err(LedgerError.ImportPreviewStale)
        ledger.update { snapshot ->
            when {
                preview.generation != snapshot.generation -> snapshot.also { result = LedgerResult.Err(LedgerError.LedgerReplaced) }
                preview.ledgerSeq != snapshot.ledgerSeq -> snapshot.also { result = LedgerResult.Err(LedgerError.ImportPreviewStale) }
                preview.identity is ImportIdentity.NeedsConfirmation -> snapshot.also { result = LedgerResult.Err(LedgerError.ForeignLedger) }
                preview.invalidRows.isNotEmpty() && !choices.skipInvalidRows ->
                    snapshot.also { result = LedgerResult.Err(LedgerError.InvalidField("invalidRows")) }
                else -> {
                    val id = ImportBatchId(ledger.ids.next())
                    result = LedgerResult.Ok(id)
                    val batch = ImportBatch(id, ImportSource.OWN_NORMALIZED_CSV, preview.fileName, ledger.now(), preview.counts.new + choices.takeIncoming.size)
                    snapshot.copy(importBatches = snapshot.importBatches + batch, ledgerSeq = snapshot.ledgerSeq + 1)
                }
            }
        }
        return result
    }

    override fun observeImportBatches(): Flow<List<ImportBatchView>> = ledger.snapshot.map { snapshot ->
        snapshot.importBatches.sortedByDescending { it.importedAt }
            .map { ImportBatchView(it.id, it.fileName, it.importedAt, it.rowCount, it.undoneAt) }
    }.distinctUntilChanged()

    override suspend fun undoImport(id: ImportBatchId): LedgerResult<UndoImportResult> {
        var found = false
        ledger.update { snapshot ->
            found = snapshot.importBatches.any { it.id == id }
            snapshot.copy(
                importBatches = snapshot.importBatches.map { if (it.id == id && it.undoneAt == null) it.copy(undoneAt = ledger.now()) else it },
                ledgerSeq = if (found) snapshot.ledgerSeq + 1 else snapshot.ledgerSeq,
            )
        }
        return if (found) LedgerResult.Ok(UndoImportResult(emptyList())) else LedgerResult.Err(LedgerError.NotFound(id.value))
    }

    /** The canned preview of [fileName] against the current ledger; nothing but the identity question until confirmed. */
    private fun cannedPreview(fileName: String, identity: ImportIdentity): ImportPreview {
        val snapshot = ledger.snapshot.value
        val withRows = identity !is ImportIdentity.NeedsConfirmation
        val invalid = withRows && "invalid" in fileName
        val conflict = if (withRows) snapshot.state.transfers.values.firstOrNull { !it.isDeleted } else null
        val rows = listOfNotNull(conflict).map {
            ImportRow(
                key = ImportRowKey(EntityType.TRANSFER, it.id.value),
                label = "Transfer of ${it.amount} on ${it.occurredOn}",
                changes = listOf(FieldChange("note", it.note, "Imported note")),
                choice = ImportRowChoice.KeepOrTake,
                changedSinceLastPreview = false,
            )
        }
        val invalidRows = if (invalid) listOf(InvalidRow("transfers", 3, "amount has too many decimals", LedgerError.TooManyDecimals(2))) else emptyList()
        return ImportPreview(
            id = ledger.ids.next(),
            fileName = fileName,
            generation = snapshot.generation,
            ledgerSeq = snapshot.ledgerSeq,
            identity = identity,
            counts = ImportCounts(new = if (withRows) 2 else 0, conflicts = rows.size, duplicates = if (withRows) 5 else 0, invalid = invalidRows.size),
            rows = rows,
            invalidRows = invalidRows,
            balanceChanges = emptyList(),
        )
    }
}

class FakeSettingsApi(private val ledger: InMemoryLedger) : SettingsApi {
    private val lockRequired = MutableStateFlow(ledger.snapshot.value.settings.appLockEnabled)

    override fun observeSettings(): Flow<Settings> = ledger.snapshot.map { it.settings }.distinctUntilChanged()

    override suspend fun setHomeCurrency(currency: CurrencyCode) = change { it.copy(homeCurrency = currency) }

    override suspend fun setAppLockEnabled(enabled: Boolean): LedgerResult<Unit> {
        if (!enabled) lockRequired.value = false
        return change { it.copy(appLockEnabled = enabled) }
    }

    override suspend fun setLockTimeout(timeout: LockTimeout) = change { it.copy(lockTimeout = timeout) }

    override suspend fun setScreenProtection(enabled: Boolean) = change { it.copy(screenProtection = enabled) }

    override suspend fun setBackupEncryptionDefault(enabled: Boolean) = change { it.copy(backupEncryptionDefault = enabled) }

    override fun observeLockRequired(): Flow<Boolean> = lockRequired

    override fun markUnlocked() {
        lockRequired.value = false
    }

    private fun change(transform: (Settings) -> Settings): LedgerResult<Unit> {
        ledger.update { it.copy(settings = transform(it.settings)) }
        return LedgerResult.Ok(Unit)
    }
}
```

`main/.../fake/FakeApis.kt` (without `seeded`; Task 6 adds it):

```kotlin
package io.github.codex07.iou.fake

import io.github.codex07.iou.api.Apis

object FakeApis {
    fun create(ledger: InMemoryLedger): Apis = Apis(
        setup = FakeSetupApi(ledger),
        people = FakePeopleApi(ledger),
        groups = FakeGroupsApi(ledger),
        ledger = FakeLedgerApi(ledger),
        fx = FakeFxApi(),
        backup = FakeBackupApi(ledger),
        importExport = FakeImportExportApi(ledger),
        settings = FakeSettingsApi(ledger),
    )
}
```

- [ ] **Step 3: Compile and run the purity check**

```bash
./gradlew :app:compileFakeDebugKotlin :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.DomainPurityTest"
```

Expected: `BUILD SUCCESSFUL` (the fake has no Android imports and no floating point).

- [ ] **Step 4: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/app/src/main/kotlin/io/github/codex07/iou/fake
git commit -m "Add the eight fake api implementations"
```

---

### Task 5: Contract suites and the fake runs

**Files:**
- Create: `test/.../api/contract/ApiContract.kt` (base), `SetupApiContract.kt`, `PeopleApiContract.kt`, `GroupsApiContract.kt`, `LedgerApiContract.kt`, `FxApiContract.kt`, `BackupApiContract.kt`, `ImportExportApiContract.kt`, `SettingsApiContract.kt`
- Create: `test/.../fake/FakeContractTests.kt`

**Interfaces:**
- Consumes: the `api` interfaces only (suites must not import `fake`, `ledger.ops` or `ledger.views`); `FakeApis.create`, `InMemoryLedger`, `SequentialIds` (fake runs).
- Produces: `abstract class ApiContract { protected abstract fun newApis(): Apis }` (a fresh, empty ledger whose clock reads 2026-03-01T12:00Z), eight abstract suites, `ImportFixture { OWN_EXPORT, FOREIGN_EXPORT }`, and `Fake*ContractTest` subclasses. Groups tests carry `@Tag("groups")` (M1 runs the real implementation without them).

- [ ] **Step 1: Write the base and the suites**

`test/.../api/contract/ApiContract.kt`:

```kotlin
package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.BeforeEach

/** Shared helpers; every suite runs against a fresh, empty ledger (no me yet). */
abstract class ApiContract {

    /** A new implementation with an empty ledger and a clock fixed at [NOW]. */
    protected abstract fun newApis(): Apis

    protected lateinit var apis: Apis

    @BeforeEach
    fun createApis() {
        apis = newApis()
    }

    protected suspend fun setUpMe(name: String = "Me"): PersonId = apis.setup.completeSetup(name, EUR).orFail()

    protected suspend fun basis(): LedgerGeneration = apis.backup.observeLedgerGeneration().first()

    protected suspend fun newPerson(name: String): PersonId = apis.people.createPerson(PersonDraft(name), basis()).orFail()

    protected suspend fun transfer(from: PersonId, to: PersonId, amount: Money, group: GroupId? = null): EntryId =
        apis.ledger.saveTransfer(TransferDraft(from, to, amount, groupId = group, occurredOn = TODAY), SaveMode.Create(basis())).orFail()

    protected fun <T> LedgerResult<T>.orFail(): T = (this as? LedgerResult.Ok)?.value ?: error("Expected Ok, was $this")

    companion object {
        val EUR = CurrencyCode("EUR")
        val SEK = CurrencyCode("SEK")
        val NOW: Instant = Instant.parse("2026-03-01T12:00:00Z")
        val TODAY: LocalDate = LocalDate.parse("2026-03-01")
        fun eur(minor: Long) = Money(minor, EUR)
        fun sek(minor: Long) = Money(minor, SEK)
    }
}
```

`test/.../api/contract/SetupApiContract.kt`:

```kotlin
package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.SetupState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

abstract class SetupApiContract : ApiContract() {

    @Test
    fun `setup creates me once and sets the home currency`() = runTest {
        apis.setup.observeSetupState().first() shouldBe SetupState.NeedsSetup
        val me = apis.setup.completeSetup("Puneet", SEK).orFail()

        val ready = apis.setup.observeSetupState().first() as SetupState.Ready
        ready.me.id shouldBe me
        ready.me.displayName shouldBe "Puneet"
        apis.settings.observeSettings().first().homeCurrency shouldBe SEK
        apis.setup.completeSetup("Again", EUR) shouldBe LedgerResult.Err(LedgerError.CannotModifyMe)
    }
}
```

`test/.../api/contract/PeopleApiContract.kt`:

```kotlin
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
```

`test/.../api/contract/LedgerApiContract.kt`:

```kotlin
package io.github.codex07.iou.api.contract

import app.cash.turbine.test
import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.FxChoice
import io.github.codex07.iou.api.GroupDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.TransferKind
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

abstract class LedgerApiContract : ApiContract() {

    @Test
    fun `a loan and a repayment leave the rest owed, regardless of account`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        transfer(me, x, eur(5_000))
        apis.ledger.saveTransfer(
            TransferDraft(x, me, eur(2_000), TransferKind.REPAYMENT, accountLabel = "Other bank", occurredOn = TODAY),
            SaveMode.Create(basis()),
        ).orFail()

        apis.people.observePerson(x).first()!!.balances shouldBe listOf(eur(3_000))
        apis.ledger.observeOverview().first().owedToMe shouldBe listOf(eur(3_000))
    }

    @Test
    fun `writes update observers`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        apis.ledger.observeOverview().test {
            awaitItem().owedToMe shouldBe emptyList()
            transfer(me, x, eur(1_000))
            awaitItem().owedToMe shouldBe listOf(eur(1_000))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `entries outside groups involve me and exactly one other person`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val y = newPerson("Y")
        val pizza = ExpenseDraft(me, "Pizza", total = eur(3_000), participants = listOf(SplitInput(me), SplitInput(x), SplitInput(y)), occurredOn = TODAY)

        apis.ledger.saveExpense(pizza, SaveMode.Create(basis())) shouldBe LedgerResult.Err(LedgerError.NeedsGroup(setOf(me, x, y)))
        apis.ledger.saveTransfer(TransferDraft(x, y, eur(100), occurredOn = TODAY), SaveMode.Create(basis())) shouldBe
            LedgerResult.Err(LedgerError.NeedsGroup(setOf(x, y)))
    }

    @Test
    fun `an edit is diffed in history and can be reverted`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val id = transfer(me, x, eur(4_000))
        val detail = apis.ledger.observeEntry(id).first()!!
        apis.ledger.saveTransfer((detail.editDraft as TransferDraft).copy(amount = eur(4_500)), SaveMode.Update(id, detail.token)).orFail()

        val edited = apis.ledger.observeEntry(id).first()!!
        edited.history.first().changes shouldContain FieldChange("amount", "40.00 EUR", "45.00 EUR")
        apis.ledger.revertTo(EntityRef(EntityType.TRANSFER, id.value), 1, edited.token).orFail()

        val reverted = apis.ledger.observeEntry(id).first()!!
        reverted.item.paid shouldBe eur(4_000)
        reverted.history.size shouldBe 3
        reverted.history.first().reason shouldBe "Reverted to v1"
    }

    @Test
    fun `delete removes the effect at once and restore brings it back`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val id = transfer(me, x, eur(700))
        apis.ledger.deleteEntry(id, apis.ledger.observeEntry(id).first()!!.token).orFail()
        apis.people.observePerson(x).first()!!.balances shouldBe emptyList()
        apis.ledger.restoreEntry(id, apis.ledger.observeEntry(id).first()!!.token).orFail()
        apis.people.observePerson(x).first()!!.balances shouldBe listOf(eur(700))
    }

    @Test
    fun `settling up with a person zeroes the balance`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        transfer(me, x, eur(1_500))
        val draft = apis.ledger.settleUpWithPerson(x, basis()).orFail().single()
        draft.draft.from shouldBe x
        apis.ledger.saveTransfer(draft.draft, SaveMode.Create(draft.basis)).orFail()
        apis.people.observePerson(x).first()!!.balances shouldBe emptyList()
    }

    @Test
    fun `a cross-currency settlement asks for a rate, then settles the other currency`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        transfer(me, x, sek(10_000))
        val repayment = TransferDraft(x, me, eur(900), TransferKind.REPAYMENT, occurredOn = TODAY, settlesCurrency = SEK)

        apis.ledger.saveTransfer(repayment, SaveMode.Create(basis())) shouldBe LedgerResult.Err(LedgerError.RateRequired(EUR, SEK))
        val quote = apis.fx.userRate(TODAY, EUR, SEK, "11.1111111111").orFail()
        apis.ledger.saveTransfer(repayment.copy(fx = FxChoice.Rate(quote)), SaveMode.Create(basis())).orFail()
        apis.people.observePerson(x).first()!!.balances shouldBe emptyList()
    }

    @Tag("groups")
    @Test
    fun `an equal split of 100 among three sums exactly`() = runTest {
        val me = setUpMe()
        val a = newPerson("A")
        val b = newPerson("B")
        val trip = apis.groups.createGroup(GroupDraft("Trip", EUR, FxMode.KEEP), listOf(a, b), basis()).orFail()
        val id = apis.ledger.saveExpense(
            ExpenseDraft(me, "Taxi", total = eur(10_000), groupId = trip, participants = listOf(SplitInput(me), SplitInput(a), SplitInput(b)), occurredOn = TODAY),
            SaveMode.Create(basis()),
        ).orFail()

        val shares = apis.ledger.observeEntry(id).first()!!.shares.map { it.share.amountMinor }
        shares.sorted() shouldBe listOf(3_333L, 3_333L, 3_334L)
    }

    @Tag("groups")
    @Test
    fun `the dinner example gives the plan, the overview and the In groups suggestion, and the plan settles`() = runTest {
        val me = setUpMe()
        val a = newPerson("A")
        val b = newPerson("B")
        val dinner = apis.groups.createGroup(GroupDraft("Dinner", EUR, FxMode.KEEP), listOf(a, b), basis()).orFail()
        apis.ledger.saveExpense(
            ExpenseDraft(a, "Dinner", total = eur(6_000), groupId = dinner, participants = listOf(SplitInput(a), SplitInput(b), SplitInput(me)), occurredOn = TODAY),
            SaveMode.Create(basis()),
        ).orFail()

        val plan = apis.groups.observeGroup(dinner).first()!!.settlePlan
        plan.map { Triple(it.from.id, it.to.id, it.amount) }.toSet() shouldBe setOf(Triple(b, a, eur(2_000)), Triple(me, a, eur(2_000)))
        apis.ledger.observeOverview().first().iOwe shouldBe listOf(eur(2_000))
        apis.people.observePerson(a).first()!!.inGroups.single().items.single().amount shouldBe eur(2_000)

        plan.forEach { item ->
            val draft = apis.ledger.settleUpFromPlan(item).orFail()
            apis.ledger.saveTransfer(draft.draft, SaveMode.Create(draft.basis)).orFail()
        }
        apis.groups.observeGroup(dinner).first()!!.members.all { it.nets.isEmpty() } shouldBe true
    }
}
```

`test/.../api/contract/GroupsApiContract.kt`:

```kotlin
package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.GroupDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.model.FxMode
import io.kotest.matchers.shouldBe
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
}
```

`test/.../api/contract/FxApiContract.kt`:

```kotlin
package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.model.FxSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

abstract class FxApiContract : ApiContract() {

    @Test
    fun `user rates are validated and bank-actual rates are derived`() = runTest {
        apis.fx.userRate(TODAY, EUR, SEK, "0") shouldBe LedgerResult.Err(LedgerError.InvalidRate("0"))
        apis.fx.userRate(TODAY, EUR, SEK, "11.5").orFail().source shouldBe FxSource.USER_ENTERED

        val bank = apis.fx.rateFromCharged(TODAY, sek(100_000), eur(8_765)).orFail()
        bank.rate shouldBe "0.08765"
        bank.source shouldBe FxSource.BANK_ACTUAL
    }

    @Test
    fun `a quote is for the pair asked`() = runTest {
        val quote = apis.fx.quote(TODAY, SEK, EUR).orFail()
        (quote.base to quote.quote) shouldBe (SEK to EUR)
    }
}
```

`test/.../api/contract/BackupApiContract.kt`:

```kotlin
package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

abstract class BackupApiContract : ApiContract() {

    /** Where the implementation can write and read a backup file. */
    protected abstract fun backupTarget(name: String): DocumentRef

    @Test
    fun `the reminder is due until a backup is made`() = runTest {
        setUpMe()
        apis.backup.observeBackupReminder().first().due shouldBe true
        apis.backup.createBackup(backupTarget("a.iou"), "correct horse".toCharArray()).orFail().encrypted shouldBe true
        apis.backup.observeBackupReminder().first().due shouldBe false
    }

    @Test
    fun `a restore and its undo each change the generation, and older tokens and bases stay invalid`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val id = transfer(me, x, eur(1_000))
        val target = backupTarget("b.iou")
        apis.backup.createBackup(target, "pass-12345".toCharArray()).orFail()
        val before = basis()
        val token = apis.ledger.observeEntry(id).first()!!.token

        apis.backup.restoreBackup(target, "pass-12345".toCharArray()).orFail()
        val afterRestore = basis()
        afterRestore shouldNotBe before
        apis.ledger.deleteEntry(id, token) shouldBe LedgerResult.Err(LedgerError.LedgerReplaced)
        apis.people.createPerson(PersonDraft("Y"), before) shouldBe LedgerResult.Err(LedgerError.LedgerReplaced)

        apis.backup.observeUndoRestoreAvailable().first() shouldBe true
        apis.backup.undoLastRestore().orFail()
        basis() shouldNotBe afterRestore
        basis() shouldNotBe before
        apis.ledger.deleteEntry(id, token) shouldBe LedgerResult.Err(LedgerError.LedgerReplaced)
    }

    @Test
    fun `a wrong passphrase changes nothing`() = runTest {
        setUpMe()
        val target = backupTarget("c.iou")
        apis.backup.createBackup(target, "pass-12345".toCharArray()).orFail()
        val before = basis()
        apis.backup.restoreBackup(target, "wrong-pass".toCharArray()) shouldBe LedgerResult.Err(LedgerError.WrongPassphraseOrDamagedFile)
        basis() shouldBe before
    }
}
```

`test/.../api/contract/ImportExportApiContract.kt`:

```kotlin
package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.ExportFormat
import io.github.codex07.iou.api.ExportRequest
import io.github.codex07.iou.api.ExportScope
import io.github.codex07.iou.api.ExportShape
import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.ImportIdentity
import io.github.codex07.iou.api.ImportPreview
import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * [OWN_EXPORT] is an export of this install's ledger in which the first transfer a test creates has the
 * note "Imported note" (so it shows as a note conflict); [FOREIGN_EXPORT] comes from another install.
 */
enum class ImportFixture { OWN_EXPORT, FOREIGN_EXPORT }

abstract class ImportExportApiContract : ApiContract() {

    protected abstract fun importFile(fixture: ImportFixture): DocumentRef
    protected abstract fun exportTarget(): DocumentRef
    protected abstract fun backupTarget(name: String): DocumentRef

    private fun ImportPreview.row(id: EntryId) = rows.single { it.key == ImportRowKey(EntityType.TRANSFER, id.value) }

    @Test
    fun `the normalized shape is always the full ledger`() = runTest {
        val me = setUpMe()
        val request = ExportRequest(ExportShape.NORMALIZED, ExportFormat.CSV, scope = ExportScope.Person(me))
        apis.importExport.export(request, exportTarget()) shouldBe LedgerResult.Err(LedgerError.InvalidField("scope"))
    }

    @Test
    fun `a foreign export needs confirmation and declining rejects it`() = runTest {
        setUpMe()
        val preview = apis.importExport.parseImport(importFile(ImportFixture.FOREIGN_EXPORT)).orFail()
        preview.identity.shouldBeInstanceOf<ImportIdentity.NeedsConfirmation>()
        apis.importExport.confirmIdentity(preview, accept = false) shouldBe LedgerResult.Err(LedgerError.ForeignLedger)
    }

    @Test
    fun `confirming a foreign preview parsed before a restore is refused`() = runTest {
        setUpMe()
        val preview = apis.importExport.parseImport(importFile(ImportFixture.FOREIGN_EXPORT)).orFail()
        val target = backupTarget("before-confirm.iou")
        apis.backup.createBackup(target, null).orFail()
        apis.backup.restoreBackup(target, null).orFail()

        apis.importExport.confirmIdentity(preview, accept = true) shouldBe LedgerResult.Err(LedgerError.LedgerReplaced)
    }

    @Test
    fun `refreshing rebuilds conflicts against the current ledger and flags what changed`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val id = apis.ledger.saveTransfer(TransferDraft(me, x, eur(1_000), occurredOn = TODAY, note = "A"), SaveMode.Create(basis())).orFail()
        val preview = apis.importExport.parseImport(importFile(ImportFixture.OWN_EXPORT)).orFail()
        preview.row(id).changes.single { it.field == "note" }.before shouldBe "A"

        val detail = apis.ledger.observeEntry(id).first()!!
        apis.ledger.saveTransfer((detail.editDraft as TransferDraft).copy(note = "B"), SaveMode.Update(id, detail.token)).orFail()
        val refreshed = apis.importExport.refreshPreview(preview).orFail()

        val row = refreshed.row(id)
        row.changes.single { it.field == "note" }.before shouldBe "B"
        row.changedSinceLastPreview shouldBe true
        apis.importExport.commitImport(refreshed, ImportChoices()).orFail()
    }

    @Test
    fun `any write after parsing makes the preview stale until refreshed`() = runTest {
        setUpMe()
        val preview = apis.importExport.parseImport(importFile(ImportFixture.OWN_EXPORT)).orFail()
        newPerson("Late")

        apis.importExport.commitImport(preview, ImportChoices()) shouldBe LedgerResult.Err(LedgerError.ImportPreviewStale)
        val refreshed = apis.importExport.refreshPreview(preview).orFail()
        val batch = apis.importExport.commitImport(refreshed, ImportChoices()).orFail()
        apis.importExport.observeImportBatches().first().first().id shouldBe batch
        apis.importExport.undoImport(batch).orFail().complete shouldBe true
    }
}
```

`test/.../api/contract/SettingsApiContract.kt`:

```kotlin
package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.LockTimeout
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

abstract class SettingsApiContract : ApiContract() {

    @Test
    fun `settings start with the spec defaults and changes are observed`() = runTest {
        val settings = apis.settings.observeSettings().first()
        settings.backupEncryptionDefault shouldBe true
        settings.screenProtection shouldBe false
        settings.lockTimeout shouldBe LockTimeout.ONE_MINUTE

        apis.settings.setHomeCurrency(SEK).orFail()
        apis.settings.setLockTimeout(LockTimeout.FIVE_MINUTES).orFail()
        apis.settings.observeSettings().first().homeCurrency shouldBe SEK
        apis.settings.observeSettings().first().lockTimeout shouldBe LockTimeout.FIVE_MINUTES
    }

    @Test
    fun `no lock is required while app lock is off, and unlocking clears it`() = runTest {
        apis.settings.observeLockRequired().first() shouldBe false
        apis.settings.setAppLockEnabled(true).orFail()
        apis.settings.markUnlocked()
        apis.settings.observeLockRequired().first() shouldBe false
    }
}
```

- [ ] **Step 2: Write the fake runs**

`test/.../fake/FakeContractTests.kt`:

```kotlin
package io.github.codex07.iou.fake

import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.contract.ApiContract
import io.github.codex07.iou.api.contract.BackupApiContract
import io.github.codex07.iou.api.contract.FxApiContract
import io.github.codex07.iou.api.contract.GroupsApiContract
import io.github.codex07.iou.api.contract.ImportExportApiContract
import io.github.codex07.iou.api.contract.ImportFixture
import io.github.codex07.iou.api.contract.LedgerApiContract
import io.github.codex07.iou.api.contract.PeopleApiContract
import io.github.codex07.iou.api.contract.SettingsApiContract
import io.github.codex07.iou.api.contract.SetupApiContract
import io.github.codex07.iou.testutil.SequentialIds
import java.time.Clock
import java.time.ZoneOffset

private fun fakeApis(): Apis =
    FakeApis.create(InMemoryLedger(Clock.fixed(ApiContract.NOW, ZoneOffset.UTC), SequentialIds("fake")))

class FakeSetupApiContractTest : SetupApiContract() { override fun newApis() = fakeApis() }
class FakePeopleApiContractTest : PeopleApiContract() { override fun newApis() = fakeApis() }
class FakeGroupsApiContractTest : GroupsApiContract() { override fun newApis() = fakeApis() }
class FakeLedgerApiContractTest : LedgerApiContract() { override fun newApis() = fakeApis() }
class FakeFxApiContractTest : FxApiContract() { override fun newApis() = fakeApis() }
class FakeSettingsApiContractTest : SettingsApiContract() { override fun newApis() = fakeApis() }

class FakeBackupApiContractTest : BackupApiContract() {
    override fun newApis() = fakeApis()
    override fun backupTarget(name: String) = DocumentRef("fake://backups/$name")
}

class FakeImportExportApiContractTest : ImportExportApiContract() {
    override fun newApis() = fakeApis()
    override fun importFile(fixture: ImportFixture) = DocumentRef(
        when (fixture) {
            ImportFixture.OWN_EXPORT -> "fake://imports/own-export.zip"
            ImportFixture.FOREIGN_EXPORT -> "fake://imports/foreign-export.zip"
        },
    )
    override fun exportTarget() = DocumentRef("fake://exports/out.zip")
    override fun backupTarget(name: String) = DocumentRef("fake://backups/$name")
}
```

- [ ] **Step 3: Run the contract suites**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.fake.Fake*ContractTest"
```

Expected: `BUILD SUCCESSFUL`; all eight fake contract classes pass (1 + 5 + 3 + 9 + 2 + 2 + 3 + 3 tests). A failure here is a real fake/spec mismatch: fix the fake, ops or views, not the suite, unless the suite contradicts the spec.

- [ ] **Step 4: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/app/src/test/kotlin/io/github/codex07/iou/api/contract android/app/src/test/kotlin/io/github/codex07/iou/fake/FakeContractTests.kt
git commit -m "Add the api contract suites and run them against the fake"
```

---

### Task 6: Seed data

**Files:**
- Create: `main/.../fake/SeedData.kt`
- Modify: `main/.../fake/FakeApis.kt` (add `seeded`)
- Modify: `test/.../fake/InMemoryLedgerTest.kt` (re-enable the replay test)
- Test: `test/.../fake/SeedDataTest.kt`

**Interfaces:**
- Consumes: Tasks 3–4, all ops.
- Produces: `object SeedData { fun populate(ledger: InMemoryLedger) }` (writes everything through the ops, so every row is validated and has revisions) and `FakeApis.seeded(clock = Clock.systemUTC()): Apis`.

- [ ] **Step 1: Write the failing test**

`test/.../fake/SeedDataTest.kt` (M0 AC 1: every `*Api` returns seeded data):

```kotlin
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SeedDataTest {

    private val ledger = InMemoryLedger(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("seed")).also(SeedData::populate)
    private val apis = FakeApis.create(ledger)

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
        (apis.fx.quote(ledger.today(), EUR, SEK) is LedgerResult.Ok) shouldBe true
        apis.backup.observeBackupReminder().first().due shouldBe true
        apis.importExport.observeImportBatches().first().shouldNotBeEmpty()
        apis.settings.observeSettings().first().homeCurrency shouldBe EUR
        apis.setup.observeSetupState().first().shouldBeInstanceOf<SetupState.Ready>()
        apis.ledger.observeOverview().first().rows.size shouldBe 7
    }

    @Test
    fun `replaying the latest revision per entity reproduces the tables`() {
        val snapshot = ledger.snapshot.value
        val latest = snapshot.revisions
            .groupBy { it.entityType to it.entityId }.values
            .map { revisions -> revisions.maxBy { it.version }.snapshot }
        val state = snapshot.state
        val current: List<LedgerEntity> = state.people.values + state.handles.values + state.groups.values +
            state.members.values + state.entries
        latest.toSet() shouldBe current.toSet()
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.fake.SeedDataTest"
```

Expected: compilation fails, `Unresolved reference 'SeedData'`.

- [ ] **Step 3: Implement**

`main/.../fake/SeedData.kt`:

```kotlin
package io.github.codex07.iou.fake

import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.FxChoice
import io.github.codex07.iou.api.FxQuote
import io.github.codex07.iou.api.GroupDraft
import io.github.codex07.iou.api.HandleDraft
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.ledger.model.Category
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.ImportBatch
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.ImportSource
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.ledger.ops.EntryOps
import io.github.codex07.iou.ledger.ops.GroupOps
import io.github.codex07.iou.ledger.ops.PeopleOps
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Duration

/**
 * Demo data for the fake (spec §6.3), written through the real ops so every row is validated and has
 * revisions: me, five people (one merged duplicate, one archived with an open balance), handles,
 * a CONVERT EUR trip with a SEK expense, and a KEEP flat with SEK and EUR expenses.
 */
object SeedData {

    fun populate(ledger: InMemoryLedger) {
        val today = ledger.today()
        val eur = CurrencyCode("EUR")
        val sek = CurrencyCode("SEK")

        fun <T> ok(result: LedgerResult<T>): T = when (result) {
            is LedgerResult.Ok -> result.value
            is LedgerResult.Err -> error("Seed write failed: ${result.error}")
        }
        fun basis() = ledger.snapshot.value.generation
        fun token(entity: LedgerEntity) = EditToken(basis(), entity.meta.version)
        fun state() = ledger.snapshot.value.state
        fun person(name: String): PersonId = ok(ledger.write { PeopleOps.createPerson(it, PersonDraft(name), basis()) })
        fun transfer(from: PersonId, to: PersonId, amount: Money, daysAgo: Long, kind: TransferKind = TransferKind.LOAN, group: GroupId? = null, note: String? = null) =
            ok(ledger.write {
                EntryOps.saveTransfer(it, TransferDraft(from, to, amount, kind, groupId = group, occurredOn = today.minusDays(daysAgo), note = note), SaveMode.Create(basis()))
            })
        fun expense(draft: ExpenseDraft) = ok(ledger.write { EntryOps.saveExpense(it, draft, SaveMode.Create(basis())) })

        val me = ok(ledger.write(beforeSetup = true) { PeopleOps.setup(it, "Me") })
        val alex = person("Alex")
        val alexDuplicate = person("Alex S.")
        val bea = person("Bea")
        val chen = person("Chen")
        val dana = person("Dana")
        val erik = person("Erik")

        ok(ledger.write { PeopleOps.addHandle(it, alex, HandleDraft(HandleType.IBAN, "DE89 3704 0044 0532 0130 00", "Main account", holderName = "Alex Example", isDefault = true), basis()) })
        ok(ledger.write { PeopleOps.addHandle(it, alex, HandleDraft(HandleType.REVTAG, "@alexexample", "Revolut", isDefault = true), basis()) })
        ok(ledger.write { PeopleOps.addHandle(it, bea, HandleDraft(HandleType.PHONE, "+46 70 123 45 67", "Mobile"), basis()) })

        // Alex: a loan edited once, a partial repayment, and a duplicate record merged into Alex.
        val loan = transfer(me, alex, Money(5_000, eur), daysAgo = 30, note = "Concert tickets")
        val loanRow = state().transfers.getValue(loan)
        ok(ledger.write {
            EntryOps.saveTransfer(it, TransferDraft(me, alex, Money(4_500, eur), occurredOn = loanRow.occurredOn, note = "Concert tickets"), SaveMode.Update(loan, token(loanRow)))
        })
        transfer(alex, me, Money(2_000, eur), daysAgo = 10, kind = TransferKind.REPAYMENT)
        transfer(me, alexDuplicate, Money(500, eur), daysAgo = 25)
        ok(ledger.write { PeopleOps.merge(it, alexDuplicate, alex, token(state().people.getValue(alexDuplicate))) })

        // Erik: an ad-hoc dinner he paid.
        expense(ExpenseDraft(erik, "Dinner", Category.DINING, Money(6_000, eur), participants = listOf(SplitInput(me), SplitInput(erik)), occurredOn = today.minusDays(3)))

        // Dana: archived with an open balance.
        transfer(me, dana, Money(2_000, eur), daysAgo = 60)
        ok(ledger.write { PeopleOps.setPersonArchived(it, dana, true, token(state().people.getValue(dana))) })

        // Group 1: CONVERT EUR trip with a SEK expense.
        val trip = ok(ledger.write { GroupOps.createGroup(it, GroupDraft("Lisbon trip", eur, FxMode.CONVERT), listOf(alex, bea, chen), basis()) })
        val everyone = listOf(SplitInput(me), SplitInput(alex), SplitInput(bea), SplitInput(chen))
        expense(ExpenseDraft(me, "Apartment", Category.ACCOMMODATION, Money(48_000, eur), trip, SplitType.EQUAL, everyone, today.minusDays(20)))
        val sekToEur = FakeRates.rate(sek, eur)!!
        expense(
            ExpenseDraft(bea, "Airport taxi", Category.TRANSPORT, Money(120_000, sek), trip, SplitType.EQUAL, everyone, today.minusDays(19),
                fx = FxChoice.Rate(FxQuote(sek, eur, sekToEur, today.minusDays(19), FxSource.ECB_FRANKFURTER, isStale = false))),
        )
        expense(ExpenseDraft(chen, "Groceries", Category.GROCERIES, Money(6_350, eur), trip, SplitType.SHARES,
            listOf(SplitInput(me, 1), SplitInput(alex, 2), SplitInput(chen, 1)), today.minusDays(18)))

        // Group 2: KEEP flat with SEK rent and a EUR purchase.
        val flat = ok(ledger.write { GroupOps.createGroup(it, GroupDraft("Flat", sek, FxMode.KEEP), listOf(bea, chen), basis()) })
        val flatmates = listOf(SplitInput(me), SplitInput(bea), SplitInput(chen))
        expense(ExpenseDraft(chen, "Rent", Category.RENT, Money(1_500_000, sek), flat, SplitType.EQUAL, flatmates, today.minusDays(5)))
        expense(ExpenseDraft(me, "Vacuum cleaner", Category.UTILITIES, Money(12_000, eur), flat, SplitType.EXACT,
            listOf(SplitInput(me, 4_000), SplitInput(bea, 4_000), SplitInput(chen, 4_000)), today.minusDays(4)))
        transfer(bea, chen, Money(500_000, sek), daysAgo = 2, kind = TransferKind.REPAYMENT, group = flat)

        // One earlier import, so the import history isn't empty.
        ledger.update {
            it.copy(importBatches = listOf(
                ImportBatch(ImportBatchId(ledger.ids.next()), ImportSource.OWN_NORMALIZED_CSV, "iou-export-2026-10.zip", ledger.now().minus(Duration.ofDays(15)), rowCount = 42),
            ))
        }
    }
}
```

Add to `object FakeApis` in `FakeApis.kt` (with `import java.time.Clock`):

```kotlin
    /** The fake with demo data, as the `fake` flavor and Compose previews use it. */
    fun seeded(clock: Clock = Clock.systemUTC()): Apis = create(InMemoryLedger(clock).also(SeedData::populate))
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :app:testFakeDebugUnitTest --tests "io.github.codex07.iou.fake.*"
```

Expected: `BUILD SUCCESSFUL`; `SeedDataTest` (4, the replay property included), `InMemoryLedgerTest` (4) and the contract runs pass. The overview has 7 rows: Alex, Bea, Chen, Erik, Dana (archived, unsettled) and the two groups. If a seed write fails, the message names the `LedgerError`: fix the seed data, not the rules.

- [ ] **Step 5: Commit**

```bash
cd /d/Workspace/IOU-App
git add android/app/src/main/kotlin/io/github/codex07/iou/fake android/app/src/test/kotlin/io/github/codex07/iou/fake
git commit -m "Add seed data for the fake"
```

---

### Task 7: Hilt wiring per flavor and the placeholder screen

**Files:**
- Create: `main/.../di/ApiModule.kt`, `android/app/src/fake/kotlin/io/github/codex07/iou/di/BackendModule.kt`, `android/app/src/real/kotlin/io/github/codex07/iou/di/BackendModule.kt`
- Modify: `main/.../MainActivity.kt`

**Interfaces:**
- Consumes: `Apis`, `FakeApis`, `InMemoryLedger`.
- Produces: Hilt bindings for `Apis` (per flavor) and for each of the eight interfaces (shared), so ViewModels inject e.g. `LedgerApi` directly.

- [ ] **Step 1: Write the modules**

`main/.../di/ApiModule.kt`:

```kotlin
package io.github.codex07.iou.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.api.BackupApi
import io.github.codex07.iou.api.FxApi
import io.github.codex07.iou.api.GroupsApi
import io.github.codex07.iou.api.ImportExportApi
import io.github.codex07.iou.api.LedgerApi
import io.github.codex07.iou.api.PeopleApi
import io.github.codex07.iou.api.SettingsApi
import io.github.codex07.iou.api.SetupApi

/** Exposes the eight interfaces of whichever `Apis` the flavor's BackendModule provides. */
@Module
@InstallIn(SingletonComponent::class)
object ApiModule {
    @Provides fun setup(apis: Apis): SetupApi = apis.setup
    @Provides fun people(apis: Apis): PeopleApi = apis.people
    @Provides fun groups(apis: Apis): GroupsApi = apis.groups
    @Provides fun ledger(apis: Apis): LedgerApi = apis.ledger
    @Provides fun fx(apis: Apis): FxApi = apis.fx
    @Provides fun backup(apis: Apis): BackupApi = apis.backup
    @Provides fun importExport(apis: Apis): ImportExportApi = apis.importExport
    @Provides fun settings(apis: Apis): SettingsApi = apis.settings
}
```

`android/app/src/fake/kotlin/io/github/codex07/iou/di/BackendModule.kt`:

```kotlin
package io.github.codex07.iou.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.fake.FakeApis
import javax.inject.Singleton

/** fake flavor: the in-memory ledger with demo data. */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides
    @Singleton
    fun apis(): Apis = FakeApis.seeded()
}
```

`android/app/src/real/kotlin/io/github/codex07/iou/di/BackendModule.kt`:

```kotlin
package io.github.codex07.iou.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.fake.FakeApis
import io.github.codex07.iou.fake.InMemoryLedger
import javax.inject.Singleton

/** real flavor in M0: the same in-memory ledger, empty (index, locked decision 8). M1 binds Room here. */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides
    @Singleton
    fun apis(): Apis = FakeApis.create(InMemoryLedger())
}
```

- [ ] **Step 2: Make the placeholder observe the overview**

Replace `main/.../MainActivity.kt`:

```kotlin
package io.github.codex07.iou

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import io.github.codex07.iou.api.LedgerApi
import javax.inject.Inject

/** Placeholder until the UI contributor's screens land: proves the api is injected and live. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var ledger: LedgerApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val overview by ledger.observeOverview().collectAsState(initial = null)
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding(), contentAlignment = Alignment.Center) {
                        Text("IOU (${BuildConfig.FLAVOR}): ${overview?.rows?.size ?: 0} balances")
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Build, run both flavors**

```bash
./gradlew lintFakeDebug testFakeDebugUnitTest testRealDebugUnitTest assembleFakeDebug assembleRealDebug
./gradlew installFakeDebug installRealDebug
adb shell am start -n io.github.codex07.iou.fake/io.github.codex07.iou.MainActivity
```

Expected: `BUILD SUCCESSFUL`; the fake app shows `IOU (fake): 7 balances` (the seed's overview rows, as `SeedDataTest` asserts). The real app shows `IOU (real): 0 balances` (no setup yet, so the overview never emits).

- [ ] **Step 4: Commit and push**

```bash
cd /d/Workspace/IOU-App
git add android/app/src
git commit -m "Wire the fake and real backends through Hilt and show the overview in the placeholder"
git push origin main
```

Expected: CI green.

---

### Task 8: Guide, README and the `v0.0.1` release

**Files:**
- Modify: `docs/ui-contributor-guide.md` (replace the "Running the fake" section)
- Modify: `README.md` (status line)
- Modify: `docs/superpowers/plans/2026-11-02-m0-00-index.md` (mark M0 done)

**Interfaces:**
- Consumes: everything above, the release workflow and secrets (Part 1, Tasks 6–7).
- Produces: M0 acceptance criteria 1–4 met; `v0.0.1` on GitHub Releases.

- [ ] **Step 1: Write the run instructions**

In `docs/ui-contributor-guide.md`, replace the section `## Running the fake` with:

````markdown
## Running the fake

1. Open `android/` in Android Studio. In *Build Variants*, pick **fakeDebug**.
2. Run on an emulator or phone. "IOU (fake)" installs next to the real app (`.fake` suffix).
3. Or from a terminal in `android/`:

   ```bash
   ./gradlew installFakeDebug
   ```

The fake keeps everything in memory and starts with demo data every launch: me, Alex (with a merged
duplicate "Alex S." and two payment handles), Bea, Chen, Dana (archived but still owing), Erik (an
ad-hoc dinner), the CONVERT EUR group "Lisbon trip" (with a SEK taxi converted to EUR) and the KEEP
group "Flat" (SEK rent plus a EUR purchase). It uses the real rules, balances and settle plans, so
errors and numbers are the real ones.

Simulated parts:

- **Backup:** any target works; restoring needs the same passphrase used for that backup, and brings the
  current data back under a new ledger generation (so you can test the `LedgerReplaced` flow). Undo works.
- **Import:** a file whose name contains `foreign` asks for identity confirmation; `corrupt` fails;
  `invalid` adds an invalid row; anything else gives a canned preview with one conflict. Committing
  records an import batch but doesn't change data. Any write after parsing makes the preview stale.
- **FX:** fixed demo rates against EUR (SEK, USD, GBP, NOK, DKK, CHF, JPY, PLN); other currencies return
  `RateRequired`, so you can test manual rate entry.

For Compose previews and UI tests, build the same thing in code:

```kotlin
val apis = FakeApis.seeded()
```
````

- [ ] **Step 2: Update the README status**

In `README.md`, replace the status line with:

```markdown
> **Status:** Milestone 0 done: the core ↔ UI contract and an in-memory fake with demo data. The real (Room) implementation comes in Milestone 1.
```

- [ ] **Step 3: Mark M0 done in the index**

In `docs/superpowers/plans/2026-11-02-m0-00-index.md`, add under the parts table:

```markdown
**Status:** all seven parts executed; M0 acceptance criteria met with `v0.0.1`.
```

- [ ] **Step 4: Commit, push, wait for green CI**

```bash
cd /d/Workspace/IOU-App
git add docs/ui-contributor-guide.md README.md docs/superpowers/plans/2026-11-02-m0-00-index.md
git commit -m "Document running the fake and mark Milestone 0 done"
git push origin main
gh run watch --repo Code-X07/IOU-App --exit-status $(gh run list --repo Code-X07/IOU-App --workflow CI --commit "$(git rev-parse HEAD)" --limit 1 --json databaseId --jq '.[0].databaseId')
```

Expected: CI passes.

- [ ] **Step 5 (owner confirms): Tag and release `v0.0.1`**

Requires the secrets from Part 1, Task 7.

```bash
git tag v0.0.1
git push origin v0.0.1
gh run watch --repo Code-X07/IOU-App --exit-status $(gh run list --repo Code-X07/IOU-App --workflow Release --commit "$(git rev-parse HEAD)" --limit 1 --json databaseId --jq '.[0].databaseId')
gh release view v0.0.1 --repo Code-X07/IOU-App
```

Expected: the release lists `iou-v0.0.1.apk`; "Verify the signature" printed `CN=IOU`. Install it on the emulator (`gh release download v0.0.1 --repo Code-X07/IOU-App --dir <scratch dir>` then `adb install -r <apk>`, after `adb uninstall io.github.codex07.iou` if a debug build is installed): "IOU" opens and shows `IOU (real): 0 balances`.

- [ ] **Step 6 (owner): Tell the friend**

Send the link to `docs/ui-contributor-guide.md`: the fake is runnable.

---

## Part 7 done when (= M0 done)

1. `fakeDebug` builds and runs; every `*Api` returns seeded data, and writes update all observers (`SeedDataTest`, `LedgerApiContract.writes update observers`).
2. The fake rejects invalid writes with the spec's errors (`LedgerApiContract.entries outside groups involve me and exactly one other person` and the other suites).
3. CI is green on push; `v0.0.1` produced a signed APK on a GitHub Release.
4. `DomainPurityTest` passes over `money`, `ledger`, `api` and `fake`.
