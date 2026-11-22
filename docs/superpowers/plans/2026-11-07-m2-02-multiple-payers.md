# M2 Part 2: Multiple Payers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Read `docs/superpowers/plans/2026-11-07-m2-00-index.md` first: its locked decisions and type inventory apply here. This part starts from the code as Part 1 leaves it.

**Goal:** Expenses can have several payers through the `api`. `ExpenseDraft` carries `payers` and `payerSplit`, the write path resolves them and keeps stored payer rows until an input of the payers changes (spec §4.4), read models and `previewSplit` show the payers, the fake seed has a two-payer expense, and contract tests cover AC 23, 24 and 26 in both flavors. The friend's single-payer screens keep compiling and working.

**Architecture:** `ExpenseDraft` gets `payers: List<SplitInput>` first and `payerSplit` last in its primary constructor, plus a secondary constructor with the old parameter order (`payer: PersonId` first) and a deprecated `payer` accessor (index, locked decision 1). `EntryOps.sharesFor` resolves payers next to shares with the same per-record checks, canonical combining and keep rule, each list decided on its own inputs; `SharePreview` carries both. `LedgerViews` turns payer rows into `PayerView`s per canonical person, the same way `shareViews` does for shares, and fills `ActivityItem.payers`, `SplitPreview.payers` / `storedPayers` / `payersRecomputed` and the edit draft's payers.

**Tech Stack:** Kotlin, JUnit 5, Kotest, Turbine (contract tests), coroutines test.

## Global Constraints

- **Payer inputs (spec §4.4):** `EQUAL` payers carry no value; `EXACT` values are amounts in the paid currency, each > 0, summing to `totalMinor`. Errors: `NoPayers`, `PaidRemaining(diffMinor)` (diff = total − Σ amounts, paid currency), `DuplicatePayer(personId)` (the same person record twice in the draft), `InvalidAmount` (an EXACT payer with 0), `NegativeInput`, `MissingInput`, `AmountTooLarge` (overflow while combining records of one person).
- **Checks before combining:** negatives and duplicate records are checked per draft record, then records of one merged person are combined on the canonical person (EXACT amounts added, one slot for EQUAL), exactly as for participants.
- **Keep rule (spec §4.4):** stored payer rows are replaced only when an input of the payers changes: the effect total (paid amount, paid currency, FX choice), `payerSplit`, the payers, or their amounts. Shares and payers decide this independently: changing only the participants keeps the payers, changing only the payers keeps the shares. Recomputed payer rows store canonical IDs.
- **Rules:** payer people count for rule 1 (outside groups: me and one other person across payers and shares → `NeedsGroup`) and rule 3 (`NotGroupMember`) through `Entry.personIds` (Part 1).
- **Read models (spec §6.2):** `PayerView(person, paid, inputValue)` per canonical person, records of one person added up, in stored order; `paid` in the effect currency; `inputValue` is the EXACT amount in the paid currency, null for EQUAL. `ActivityItem.paidBy` stays (first payer) next to `payers`; transfers have no payers.
- No `ui/` file changes in this part (index, locked decision 1): the single-payer constructor and the deprecated accessor keep `ui/forms/EntryInput.kt` compiling, with a deprecation warning.

## File map

| File | Change |
|---|---|
| `main/.../api/Drafts.kt` | `ExpenseDraft.payers` / `payerSplit`, single-payer secondary constructor, deprecated `payer` |
| `main/.../ledger/ops/EntryOps.kt` | `SharePreview.payers` / `payersRecomputed`; payers resolved and kept in `sharesFor`; `payerInputsOf` |
| `main/.../api/ReadModels.kt` | `PayerView`; `ActivityItem.payers`; `SplitPreview.payers` / `storedPayers` / `payersRecomputed` |
| `main/.../ledger/views/EntryViews.kt` | `payerViews`; payers in activity items, previews and edit drafts |
| `main/.../fake/SeedData.kt` | A two-payer group expense |
| `test/...` | `PayerOpsTest` (new); additions to `EntryViewsTest`, `SeedDataTest`, `LedgerApiContract` |
| `docs/ui-contributor-guide.md`, `docs/m2-ui-contract-changes.md`, spec §6.2 | Payers on drafts and read models |

Paths abbreviate `android/app/src/main/kotlin/io/github/codex07/iou/` as `main/...` and the test root likewise. Commands run from `android/` with `JAVA_HOME` set to the MS JDK 21.

---

### Task 1: Payers on the expense draft and in the write path

**Files:**
- Modify: `main/.../api/Drafts.kt`, `main/.../ledger/ops/EntryOps.kt`, `docs/ui-contributor-guide.md`
- Create: `test/.../ledger/ops/PayerOpsTest.kt`

**Interfaces:**
- Consumes (Part 1): `PayerSplit` (with `splitType`), `ExpensePayer`, `Expense.payerSplit` / `Expense.payers`, `SplitResolver.resolvePayers(split, effectTotalMinor, originalTotalMinor, inputs)`, `LedgerError.NoPayers` / `PaidRemaining` / `DuplicatePayer`, `TestLedger.expense(..., payers, payerSplit)`; `OpsHarness`, `RevertOps.revertTo`.
- Produces: `ExpenseDraft(payers: List<SplitInput>, description, category, total, groupId, splitType, participants, occurredOn, note, fx, payerSplit = PayerSplit.EQUAL)`, the secondary constructor `ExpenseDraft(payer: PersonId, description, category, total, groupId, splitType, participants, occurredOn, note, fx)`, `@Deprecated ExpenseDraft.payer`; `SharePreview(effectTotal, shares, recomputed, payers: List<ExpensePayer>, payersRecomputed: Boolean)`; `EntryOps.payerInputsOf(expense: Expense, resolver: IdentityResolver): List<SplitInput>`.
- `EntryViews.draftOf` still builds a single-payer draft through the secondary constructor until Task 2.

- [ ] **Step 1: Write the failing tests**

Create `test/.../ledger/ops/PayerOpsTest.kt`:

```kotlin
package io.github.codex07.iou.ledger.ops

import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Payers through the write path (spec §4.4): resolved from the draft, kept as stored until an input of the payers changes. */
class PayerOpsTest {

    private fun ok(result: LedgerResult<EntryId>): EntryId = (result as LedgerResult.Ok).value

    /** A €100 dinner in a group of me, A and B: A paid €60 and B €40 (EXACT), shared EQUAL by [participants]. */
    private fun dinner(trip: GroupId, a: PersonId, b: PersonId, participants: List<SplitInput>) =
        ExpenseDraft(
            listOf(SplitInput(a, 6_000), SplitInput(b, 4_000)), "Dinner", total = eur(10_000), groupId = trip,
            participants = participants, occurredOn = D0, payerSplit = PayerSplit.EXACT,
        )

    @Test
    fun `a draft's payers are stored as given for EXACT and divided for EQUAL`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val b = ledger.person("B")
        val trip = ledger.group("Trip", members = listOf(ME, a, b))
        val h = OpsHarness(ledger.state)
        val draft = dinner(trip, a, b, listOf(SplitInput(ME), SplitInput(a), SplitInput(b)))

        val id = ok(h.run { EntryOps.saveExpense(it, draft, SaveMode.Create(h.generation)) })
        val saved = h.state.expenses.getValue(id)
        saved.payerSplit shouldBe PayerSplit.EXACT
        saved.payers shouldBe listOf(ExpensePayer(a, 6_000, 6_000), ExpensePayer(b, 4_000, 4_000))
        // ME has the lowest ID, so ME's share takes the extra cent: 33.34, 33.33, 33.33.
        val balances = LedgerEngine.compute(h.state)
        balances.groupNet(trip, ME) shouldBe mapOf(EUR to -3_334L)
        balances.groupNet(trip, a) shouldBe mapOf(EUR to 2_667L)
        balances.groupNet(trip, b) shouldBe mapOf(EUR to 667L)

        val equal = draft.copy(payerSplit = PayerSplit.EQUAL, payers = listOf(SplitInput(a), SplitInput(b)))
        val second = ok(h.run { EntryOps.saveExpense(it, equal, SaveMode.Create(h.generation)) })
        h.state.expenses.getValue(second).payers shouldBe listOf(ExpensePayer(a, 5_000), ExpensePayer(b, 5_000))
    }

    @Test
    fun `payer errors name the payer side, and the rules see every payer`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val b = ledger.person("B")
        val x = ledger.person("X")
        val trip = ledger.group("Trip", members = listOf(ME, a, b))
        val h = OpsHarness(ledger.state)
        val draft = dinner(trip, a, b, listOf(SplitInput(a), SplitInput(b)))
        fun save(
            payers: List<SplitInput>,
            split: PayerSplit = PayerSplit.EXACT,
            group: GroupId? = trip,
            participants: List<SplitInput> = draft.participants,
        ) = h.run {
            EntryOps.saveExpense(
                it, draft.copy(payers = payers, payerSplit = split, groupId = group, participants = participants), SaveMode.Create(h.generation),
            )
        }

        save(listOf(SplitInput(a, 6_000), SplitInput(b, 3_000))) shouldBe LedgerResult.Err(LedgerError.PaidRemaining(1_000))
        save(listOf(SplitInput(a, 10_000), SplitInput(b, 0))) shouldBe LedgerResult.Err(LedgerError.InvalidAmount)
        save(listOf(SplitInput(a, 11_000), SplitInput(b, -1_000))) shouldBe LedgerResult.Err(LedgerError.NegativeInput(b))
        save(listOf(SplitInput(a, 6_000), SplitInput(b))) shouldBe LedgerResult.Err(LedgerError.MissingInput(b))
        save(emptyList()) shouldBe LedgerResult.Err(LedgerError.NoPayers)
        save(listOf(SplitInput(a), SplitInput(a)), PayerSplit.EQUAL) shouldBe LedgerResult.Err(LedgerError.DuplicatePayer(a))
        save(listOf(SplitInput(a), SplitInput(x)), PayerSplit.EQUAL) shouldBe
            LedgerResult.Err(LedgerError.NotGroupMember(trip, setOf(x)))
        // Outside groups: me and exactly one other person across payers and shares (rule 1).
        save(listOf(SplitInput(ME), SplitInput(x)), PayerSplit.EQUAL, group = null, participants = listOf(SplitInput(ME), SplitInput(a))) shouldBe
            LedgerResult.Err(LedgerError.NeedsGroup(setOf(ME, a, x)))
    }

    @Test
    fun `stored payers are kept until an input of the payers changes, independently of the shares`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val b = ledger.person("B")
        val trip = ledger.group("Trip", members = listOf(ME, a, b))
        val h = OpsHarness(ledger.state)
        val draft = dinner(trip, a, b, listOf(SplitInput(a), SplitInput(b)))
        val id = ok(h.run { EntryOps.saveExpense(it, draft, SaveMode.Create(h.generation)) })
        fun stored() = h.state.expenses.getValue(id)
        fun edit(changed: ExpenseDraft) = h.run { EntryOps.saveExpense(it, changed, SaveMode.Update(id, h.token(stored()))) }
        fun preview(changed: ExpenseDraft) = (EntryOps.previewShares(h.ctx(), changed, stored()) as LedgerResult.Ok).value
        val payers = stored().payers

        preview(draft.copy(note = "ferry")).payersRecomputed shouldBe false
        edit(draft.copy(note = "ferry")) shouldBe LedgerResult.Ok(id)
        stored().payers shouldBe payers

        // New participants recompute the shares only.
        val withMe = draft.copy(participants = listOf(SplitInput(ME), SplitInput(a), SplitInput(b)))
        preview(withMe).let { it.recomputed to it.payersRecomputed } shouldBe (true to false)
        edit(withMe) shouldBe LedgerResult.Ok(id)
        stored().payers shouldBe payers
        stored().shares.size shouldBe 3

        // New payer amounts recompute the payers only.
        val shares = stored().shares
        val swapped = withMe.copy(payers = listOf(SplitInput(a, 4_000), SplitInput(b, 6_000)))
        preview(swapped).let { it.recomputed to it.payersRecomputed } shouldBe (false to true)
        edit(swapped) shouldBe LedgerResult.Ok(id)
        stored().payers shouldBe listOf(ExpensePayer(a, 4_000, 4_000), ExpensePayer(b, 6_000, 6_000))
        stored().shares shouldBe shares

        // A new total recomputes both.
        val bigger = swapped.copy(total = eur(12_000), payerSplit = PayerSplit.EQUAL, payers = listOf(SplitInput(a), SplitInput(b)))
        edit(bigger) shouldBe LedgerResult.Ok(id)
        stored().payers shouldBe listOf(ExpensePayer(a, 6_000), ExpensePayer(b, 6_000))
        stored().shares.sumOf { it.shareMinor } shouldBe 12_000L
        // EXACT amounts that no longer add up to the new total are refused.
        edit(swapped.copy(total = eur(13_000))) shouldBe LedgerResult.Err(LedgerError.PaidRemaining(3_000))
    }

    @Test
    fun `merged payer records stay as stored until the payers change, then combine on the canonical person`() {
        val ledger = TestLedger().apply { me() }
        val alexA = ledger.person("AlexA")
        val alexB = ledger.person("AlexB")
        val bob = ledger.person("Bob")
        val trip = ledger.group("Trip", members = listOf(ME, alexA, alexB, bob))
        val id = ledger.expense(
            alexA, eur(9_000), mapOf(ME to 3_000L, alexA to 3_000L, bob to 3_000L), group = trip, splitType = SplitType.EQUAL,
            payers = listOf(ExpensePayer(alexA, 4_000, 4_000), ExpensePayer(alexB, 2_000, 2_000), ExpensePayer(bob, 3_000, 3_000)),
            payerSplit = PayerSplit.EXACT,
        )
        val h = OpsHarness(ledger.state.with(ledger.state.people.getValue(alexB).copy(mergedIntoId = alexA)))
        val stored = h.state.expenses.getValue(id)
        val resolver = IdentityResolver(h.state)

        val payerInputs = EntryOps.payerInputsOf(stored, resolver)
        payerInputs shouldBe listOf(SplitInput(alexA, 6_000), SplitInput(bob, 3_000))
        val draft = ExpenseDraft(
            payerInputs, stored.description, total = eur(9_000), groupId = trip, splitType = SplitType.EQUAL,
            participants = EntryOps.splitInputsOf(stored, resolver), occurredOn = D0, payerSplit = PayerSplit.EXACT,
        )

        h.run { EntryOps.saveExpense(it, draft.copy(note = "ferry"), SaveMode.Update(id, h.token(h.state.expenses.getValue(id)))) } shouldBe
            LedgerResult.Ok(id)
        h.state.expenses.getValue(id).payers shouldBe stored.payers

        val changed = draft.copy(payers = listOf(SplitInput(alexA, 5_000), SplitInput(bob, 4_000)))
        h.run { EntryOps.saveExpense(it, changed, SaveMode.Update(id, h.token(h.state.expenses.getValue(id)))) } shouldBe
            LedgerResult.Ok(id)
        h.state.expenses.getValue(id).payers shouldBe listOf(ExpensePayer(alexA, 5_000, 5_000), ExpensePayer(bob, 4_000, 4_000))
    }

    @Test
    fun `reverting a two-payer expense to its single-payer version gives one payer again`() {
        val ledger = TestLedger().apply { me() }
        val a = ledger.person("A")
        val trip = ledger.group("Trip", members = listOf(ME, a))
        val h = OpsHarness(ledger.state)
        val single = ExpenseDraft(ME, "Dinner", total = eur(1_000), groupId = trip, participants = listOf(SplitInput(ME), SplitInput(a)), occurredOn = D0)
        val id = ok(h.run { EntryOps.saveExpense(it, single, SaveMode.Create(h.generation)) })
        val both = single.copy(payers = listOf(SplitInput(ME, 400), SplitInput(a, 600)), payerSplit = PayerSplit.EXACT)
        h.run { EntryOps.saveExpense(it, both, SaveMode.Update(id, h.token(h.state.expenses.getValue(id)))) } shouldBe LedgerResult.Ok(id)
        LedgerEngine.compute(h.state).groupNet(trip, a) shouldBe mapOf(EUR to 100L)

        h.run { RevertOps.revertTo(it, EntityRef(EntityType.EXPENSE, id.value), 1, h.token(h.state.expenses.getValue(id)), h.revisions) } shouldBe
            LedgerResult.Ok(Unit)

        val reverted = h.state.expenses.getValue(id)
        reverted.payerSplit shouldBe PayerSplit.EQUAL
        reverted.payers shouldBe listOf(ExpensePayer(ME, 1_000))
        LedgerEngine.compute(h.state).groupNet(trip, a) shouldBe mapOf(EUR to -500L)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.ops.PayerOpsTest"`
Expected: FAIL, compilation errors: no `ExpenseDraft` constructor takes a `List<SplitInput>` first, `Unresolved reference 'payerSplit'` on the draft, `'payersRecomputed'`, `'payerInputsOf'`.

- [ ] **Step 3: Give the draft its payers**

In `main/.../api/Drafts.kt`, add the import `io.github.codex07.iou.ledger.model.PayerSplit`, and replace:

```kotlin
/**
 * An expense to create or save. [participants] carry the split inputs (see [SplitInput]); EXACT inputs
 * are in [total]'s currency. [fx] is needed in a CONVERT group when [total]'s currency differs from the
 * group currency.
 */
data class ExpenseDraft(
    val payer: PersonId,
    val description: String,
    val category: Category? = null,
    val total: Money,
    val groupId: GroupId? = null,
    val splitType: SplitType = SplitType.EQUAL,
    val participants: List<SplitInput>,
    val occurredOn: LocalDate,
    val note: String? = null,
    val fx: FxChoice? = null,
) : EntryDraft
```

with:

```kotlin
/**
 * An expense to create or save. [payers] and [participants] carry split inputs (see [SplitInput]); EXACT
 * values of both are in [total]'s currency. [payerSplit] divides the total among the payers (spec §4.4):
 * `EQUAL` with no values, or `EXACT` amounts that are each greater than 0 and add up to [total]. A single
 * payer is one entry with no value. [fx] is needed in a CONVERT group when [total]'s currency differs from
 * the group currency.
 */
data class ExpenseDraft(
    val payers: List<SplitInput>,
    val description: String,
    val category: Category? = null,
    val total: Money,
    val groupId: GroupId? = null,
    val splitType: SplitType = SplitType.EQUAL,
    val participants: List<SplitInput>,
    val occurredOn: LocalDate,
    val note: String? = null,
    val fx: FxChoice? = null,
    val payerSplit: PayerSplit = PayerSplit.EQUAL,
) : EntryDraft {

    /** A single-payer draft with the parameters in their pre-M2 order: [payer] paid the whole total. */
    constructor(
        payer: PersonId,
        description: String,
        category: Category? = null,
        total: Money,
        groupId: GroupId? = null,
        splitType: SplitType = SplitType.EQUAL,
        participants: List<SplitInput>,
        occurredOn: LocalDate,
        note: String? = null,
        fx: FxChoice? = null,
    ) : this(listOf(SplitInput(payer)), description, category, total, groupId, splitType, participants, occurredOn, note, fx, PayerSplit.EQUAL)

    /**
     * The first payer, kept so single-payer forms still compile. A draft rebuilt from it alone has one payer,
     * so saving it replaces every other payer of the expense.
     */
    @Deprecated("An expense can have several payers: use payers and payerSplit.", ReplaceWith("payers.first().personId"))
    val payer: PersonId get() = payers.first().personId
}
```

Every existing call (`ExpenseDraft(me, "Pizza", …)`, the seed, the friend's `EntryInput`) passes a `PersonId` first, or `payer =` by name, and so resolves to the secondary constructor unchanged. `copy(…)` exists for the primary constructor's properties only, which no existing caller changes `payer` through.

- [ ] **Step 4: Resolve and keep payers in the write path**

In `main/.../ledger/ops/EntryOps.kt`, replace:

```kotlin
/** What saving an expense draft would store (no write). */
data class SharePreview(val effectTotal: Money, val shares: List<ExpenseShare>, val recomputed: Boolean)
```

with:

```kotlin
/**
 * What saving an expense draft would store (no write). [recomputed] and [payersRecomputed] are false when the
 * stored shares, or the stored payer rows, would be kept as they are (spec §4.4).
 */
data class SharePreview(
    val effectTotal: Money,
    val shares: List<ExpenseShare>,
    val recomputed: Boolean,
    val payers: List<ExpensePayer>,
    val payersRecomputed: Boolean,
)
```

In `saveExpense`, replace (Part 1's single payer):

```kotlin
            payers = ExpensePayer.single(
                storedPerson(IdentityResolver(ctx.state), draft.payer, existing?.payers?.singleOrNull()?.personId),
                preview.effectTotal.amountMinor,
            ),
```

with:

```kotlin
            payerSplit = draft.payerSplit,
            payers = preview.payers,
```

Replace:

```kotlin
    /** The shares a save would store, for `previewSplit` (spec §4.4). Writes nothing. */
```

with:

```kotlin
    /** The shares and payers a save would store, for `previewSplit` (spec §4.4). Writes nothing. */
```

After `fun splitInputsOf(...)`, add:

```kotlin

    /** The stored payers as an edit draft shows them: canonical people, records of one person combined (spec §4.4). */
    fun payerInputsOf(expense: Expense, resolver: IdentityResolver): List<SplitInput> =
        combineInputs(expense.payers.map { SplitInput(it.personId, it.inputValue) }, expense.payerSplit.splitType, resolver)
```

Replace the whole function `private fun ResultScope.sharesFor(...)` (from its signature line through its closing `}`, just before `private fun ResultScope.resolveFx`) with:

```kotlin
    private fun ResultScope.sharesFor(ctx: WriteContext, draft: ExpenseDraft, existing: Expense?): Pair<FxResolution, SharePreview> {
        checkAmount(draft.total)
        val required = requiredEffectCurrency(ctx.state, draft.groupId, draft.total.currency, settlesCurrency = null)
        val fx = resolveFx(ctx, draft.total, required, draft.fx, existing, draft.occurredOn)
        val resolver = IdentityResolver(ctx.state)
        // The effect total is an input of both lists: the paid amount, its currency and the FX choice.
        val sameEffect = existing != null &&
            (draft.fx == null || draft.fx == FxChoice.KeepExisting) &&
            draft.total == existing.total &&
            fx.effect == existing.effectTotal

        val inputs = checkedInputs(draft.participants, draft.splitType, resolver, LedgerError::DuplicateParticipant)
        val keep = existing != null && sameEffect &&
            draft.splitType == existing.splitType &&
            inputs.toSet() == splitInputsOf(existing, resolver).toSet()
        val shares = if (keep) {
            existing!!.shares
        } else {
            SplitResolver.resolve(draft.splitType, fx.effect.amountMinor, draft.total.amountMinor, inputs).bind()
        }

        // Payers follow the same steps on their own inputs, and are kept or recomputed on their own (spec §4.4).
        val payerInputs = checkedInputs(draft.payers, draft.payerSplit.splitType, resolver, LedgerError::DuplicatePayer)
        val keepPayers = existing != null && sameEffect &&
            draft.payerSplit == existing.payerSplit &&
            payerInputs.toSet() == payerInputsOf(existing, resolver).toSet()
        val payers = if (keepPayers) {
            existing!!.payers
        } else {
            SplitResolver.resolvePayers(draft.payerSplit, fx.effect.amountMinor, draft.total.amountMinor, payerInputs).bind()
        }
        return fx to SharePreview(fx.effect, shares, recomputed = !keep, payers = payers, payersRecomputed = !keepPayers)
    }

    /**
     * Draft inputs (participants, payers, schedule template rows) checked per record, then combined per canonical
     * person (spec §4.4). Negatives are rejected per record first: combining records of one person could hide
     * one. The same record twice is a malformed draft ([duplicate]); only distinct records of one merged person
     * combine. Combining is exact arithmetic, so an overflow there is `AmountTooLarge` (index decision 4).
     */
    internal fun ResultScope.checkedInputs(
        inputs: List<SplitInput>,
        type: SplitType,
        resolver: IdentityResolver,
        duplicate: (PersonId) -> LedgerError,
    ): List<SplitInput> {
        inputs.firstOrNull { (it.value ?: 0L) < 0L }?.let { fail(LedgerError.NegativeInput(it.personId)) }
        inputs.groupingBy { it.personId }.eachCount().entries.firstOrNull { it.value > 1 }?.let { fail(duplicate(it.key)) }
        return try {
            combineInputs(inputs, type, resolver)
        } catch (overflow: ArithmeticException) {
            fail(LedgerError.AmountTooLarge)
        }
    }
```

`storedPerson` is still used by `saveTransfer`. An empty payer list never matches the stored rows, so it reaches `resolvePayers`, which returns `NoPayers`. The share checks run first, so a draft wrong on both sides reports the share error.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.ops.PayerOpsTest" --tests "io.github.codex07.iou.ledger.ops.EntryOpsTest"`
Expected: PASS (5 new tests; `EntryOpsTest` unchanged).

Then run the full JVM suite of both flavors: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest`
Expected: PASS. The compiler warns that `ui/forms/EntryInput.kt` uses the deprecated `payer`; that warning is expected and stays until the friend moves the form to `payers`.

- [ ] **Step 6: Update the UI guide and commit**

In `docs/ui-contributor-guide.md`, section "Entries", replace:

```markdown
- New transfer or expense: build a `TransferDraft`/`ExpenseDraft` and call `saveTransfer`/`saveExpense` with `SaveMode.Create(basis)`.
```

with:

```markdown
- New transfer or expense: build a `TransferDraft`/`ExpenseDraft` and call `saveTransfer`/`saveExpense` with `SaveMode.Create(basis)`.
- Payers: `ExpenseDraft.payers` is a list of `SplitInput`, like `participants`, divided by `payerSplit`: `EQUAL` (no values) or `EXACT` (each payer's amount in the paid currency, each > 0, adding up to the total). One payer is one entry with no value. Errors on the payer side: `NoPayers`, `PaidRemaining(diffMinor)`, `DuplicatePayer(personId)`, and `NegativeInput` / `MissingInput` / `InvalidAmount` / `AmountTooLarge` naming a payer. The old constructor `ExpenseDraft(payer, description, …)` still works and makes one payer; `draft.payer` (deprecated) is the first payer only, so a form that edits through it saves one payer.
```

```bash
git add android/app/src docs/ui-contributor-guide.md
git commit -m "Take several payers on expense drafts and keep stored payer rows until their inputs change"
```

---

### Task 2: Payers in read models, previews and edit drafts

**Files:**
- Modify: `main/.../api/ReadModels.kt`, `main/.../ledger/views/EntryViews.kt`, `docs/ui-contributor-guide.md`, `docs/superpowers/specs/2026-11-02-iou-mvp-design.md` (§6.2)
- Modify (tests): `test/.../ledger/views/EntryViewsTest.kt`

**Interfaces:**
- Consumes: `ExpenseDraft.payers` / `payerSplit`, `SharePreview.payers` / `payersRecomputed`, `EntryOps.payerInputsOf`, `EntryOps.combineInputs` (Task 1); `LedgerViews.personRef`, `LedgerViews.resolver`.
- Produces: `data class PayerView(person: PersonRef, paid: Money, inputValue: Long?)`; `ActivityItem.payers: List<PayerView>` (after `paidBy`); `SplitPreview.payers: List<PayerView>`, `SplitPreview.storedPayers: List<PayerView>?`, `SplitPreview.payersRecomputed: Boolean` (after `recomputed`); `LedgerViews.payerViews(expense: Expense): List<PayerView>`. `EntryDetail` has no payer list of its own: `EntryDetail.item.payers` is it.

- [ ] **Step 1: Write the failing tests**

In `test/.../ledger/views/EntryViewsTest.kt`, add the imports `io.github.codex07.iou.ledger.model.ExpensePayer` and `io.github.codex07.iou.ledger.model.PayerSplit`, and add these tests before the last closing brace:

```kotlin

    @Test
    fun `expense read models show each canonical payer once with what they paid`() {
        val ledger = TestLedger().apply { me() }
        val alex = ledger.person("Alex")
        val alex2 = ledger.person("Alex2", mergedInto = alex)
        val bob = ledger.person("Bob")
        val trip = ledger.group("Trip", members = listOf(ME, alex, alex2, bob))
        val id = ledger.expense(
            alex, eur(9_000), mapOf(ME to 3_000L, alex to 3_000L, bob to 3_000L), group = trip, splitType = SplitType.EQUAL,
            payers = listOf(ExpensePayer(alex, 3_000, 3_000), ExpensePayer(bob, 4_000, 4_000), ExpensePayer(alex2, 2_000, 2_000)),
            payerSplit = PayerSplit.EXACT,
        )
        val loan = ledger.transfer(ME, bob, eur(500))
        val h = OpsHarness(ledger.state)

        val detail = h.views().entry(id)!!
        detail.item.paidBy.displayName shouldBe "Alex"
        detail.item.payers.map { Triple(it.person.displayName, it.paid, it.inputValue) } shouldBe listOf(
            Triple("Alex", eur(5_000), 5_000L),
            Triple("Bob", eur(4_000), 4_000L),
        )
        val draft = detail.editDraft as ExpenseDraft
        draft.payerSplit shouldBe PayerSplit.EXACT
        draft.payers shouldBe listOf(SplitInput(alex, 5_000), SplitInput(bob, 4_000))
        h.views().entry(loan)!!.item.payers shouldBe emptyList()
    }

    @Test
    fun `split preview shows stored and new payers`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.expense(
            ME, eur(1_000), mapOf(ME to 500L, x to 500L), splitType = SplitType.EQUAL,
            payers = listOf(ExpensePayer(ME, 300, 300), ExpensePayer(x, 700, 700)), payerSplit = PayerSplit.EXACT,
        )
        val h = OpsHarness(ledger.state)
        val draft = h.views().entry(id)!!.editDraft as ExpenseDraft

        val same = h.views().splitPreview(draft, id, h.now).ok()
        same.payersRecomputed shouldBe false
        same.payers shouldBe same.storedPayers
        same.payers.map { it.paid } shouldBe listOf(eur(300), eur(700))

        val equal = h.views().splitPreview(
            draft.copy(payerSplit = PayerSplit.EQUAL, payers = listOf(SplitInput(ME), SplitInput(x))), id, h.now,
        ).ok()
        equal.payersRecomputed shouldBe true
        equal.recomputed shouldBe false
        equal.payers.map { it.paid to it.inputValue } shouldBe listOf(eur(500) to null, eur(500) to null)
        equal.storedPayers!!.map { it.paid } shouldBe listOf(eur(300), eur(700))

        h.views().splitPreview(draft.copy(payers = listOf(SplitInput(ME, 300), SplitInput(x, 600))), id, h.now) shouldBe
            LedgerResult.Err(LedgerError.PaidRemaining(100))
    }

    @Test
    fun `old versions open as drafts with the payers they had`() {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val h = OpsHarness(ledger.state)
        val single = ExpenseDraft(ME, "Lunch", total = eur(1_000), participants = listOf(SplitInput(ME), SplitInput(x)), occurredOn = D0)
        val id = h.run { EntryOps.saveExpense(it, single, SaveMode.Create(h.generation)) }.ok()
        val both = single.copy(payers = listOf(SplitInput(ME, 300), SplitInput(x, 700)), payerSplit = PayerSplit.EXACT)
        h.run { EntryOps.saveExpense(it, both, SaveMode.Update(id, h.token(h.state.expenses.getValue(id)))) }.ok()

        val ref = EntityRef(EntityType.EXPENSE, id.value)
        h.views().draftFromVersion(ref, 1).ok() shouldBe VersionDraft.OfEntry(single)
        h.views().draftFromVersion(ref, 2).ok() shouldBe VersionDraft.OfEntry(both)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.views.EntryViewsTest"`
Expected: FAIL, compilation errors `Unresolved reference 'payers'` on `ActivityItem`, `'payersRecomputed'` and `'storedPayers'` on `SplitPreview`.

- [ ] **Step 3: Add the payer read models**

In `main/.../api/ReadModels.kt`, replace:

```kotlin
    /** Expense payer or transfer sender (canonical). */
    val paidBy: PersonRef,
```

with:

```kotlin
    /**
     * Expense: the first payer; transfer: the sender (canonical). Kept for single-payer screens: an expense
     * may have several payers, listed in [payers].
     */
    val paidBy: PersonRef,
    /** Expense payers per canonical person, in stored order, with what each paid; empty for transfers. */
    val payers: List<PayerView>,
```

replace:

```kotlin
data class ShareView(val person: PersonRef, val share: Money, val inputValue: Long?)
```

with:

```kotlin
data class ShareView(val person: PersonRef, val share: Money, val inputValue: Long?)

/**
 * One payer of an expense (spec §6.2), records of one merged person added up. [paid] is in the effect
 * currency; [inputValue] is the EXACT amount in the paid currency, null for EQUAL payers.
 */
data class PayerView(val person: PersonRef, val paid: Money, val inputValue: Long?)
```

and replace:

```kotlin
/** Shares a draft would get; [storedShares] is set when editing, so both can be shown (spec §4.4). */
data class SplitPreview(
    val effectTotal: Money,
    val shares: List<ShareView>,
    val storedShares: List<ShareView>?,
    /** False when saving would keep the stored shares unchanged. */
    val recomputed: Boolean,
)
```

with:

```kotlin
/**
 * Shares and payers a draft would get; [storedShares] and [storedPayers] are set when editing, so both can be
 * shown (spec §4.4). Shares and payers are kept or recomputed independently.
 */
data class SplitPreview(
    val effectTotal: Money,
    val shares: List<ShareView>,
    val storedShares: List<ShareView>?,
    /** False when saving would keep the stored shares unchanged. */
    val recomputed: Boolean,
    val payers: List<PayerView>,
    val storedPayers: List<PayerView>?,
    /** False when saving would keep the stored payer rows unchanged. */
    val payersRecomputed: Boolean,
)
```

- [ ] **Step 4: Fill them in the views**

In `main/.../ledger/views/EntryViews.kt`, add the imports `io.github.codex07.iou.api.PayerView`, `io.github.codex07.iou.ledger.model.ExpensePayer`, `io.github.codex07.iou.ledger.model.PayerSplit`, `io.github.codex07.iou.ledger.model.SplitInput` and `io.github.codex07.iou.money.CurrencyCode`.

In `activityItem`, replace:

```kotlin
        note = entry.note, paidBy = personRef(entry.fromPersonId), receiver = personRef(entry.toPersonId),
```

with:

```kotlin
        note = entry.note, paidBy = personRef(entry.fromPersonId), payers = emptyList(), receiver = personRef(entry.toPersonId),
```

and replace (Part 1's line):

```kotlin
        note = entry.note, paidBy = personRef(entry.payers.first().personId), receiver = null,
```

with:

```kotlin
        note = entry.note, paidBy = personRef(entry.payers.first().personId), payers = payerViews(entry), receiver = null,
```

After `fun LedgerViews.shareViews(...)`, add:

```kotlin

/** Payers per canonical person, records of one person added up, in stored order (spec §4.4, §6.2). */
fun LedgerViews.payerViews(expense: Expense): List<PayerView> =
    payerViews(expense.payers, expense.payerSplit, expense.effectTotal.currency)

private fun LedgerViews.payerViews(payers: List<ExpensePayer>, split: PayerSplit, currency: CurrencyCode): List<PayerView> {
    val inputs = EntryOps.combineInputs(payers.map { SplitInput(it.personId, it.inputValue) }, split.splitType, resolver)
        .associate { it.personId to it.value }
    val sums = LinkedHashMap<PersonId, Long>()
    for (payer in payers) {
        val person = resolver.canonical(payer.personId)
        sums[person] = Math.addExact(sums[person] ?: 0L, payer.paidMinor)
    }
    return sums.map { (person, sum) -> PayerView(personRef(person), Money(sum, currency), inputs[person]) }
}
```

In `splitPreview`, replace:

```kotlin
            storedShares = stored,
            recomputed = preview.recomputed,
        )
```

with:

```kotlin
            storedShares = stored,
            recomputed = preview.recomputed,
            // Kept payers are the stored rows with the stored payerSplit, which equals the draft's when they are kept.
            payers = payerViews(preview.payers, draft.payerSplit, preview.effectTotal.currency),
            storedPayers = existing?.let { payerViews(it) },
            payersRecomputed = preview.payersRecomputed,
        )
```

In `draftOf`, replace the expense branch (Part 1's version):

```kotlin
    is Expense -> ExpenseDraft(
        payer = personRef(entry.payers.first().personId).id,
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
```

with:

```kotlin
    is Expense -> ExpenseDraft(
        payers = EntryOps.payerInputsOf(entry, resolver),
        description = entry.description,
        category = entry.category,
        total = entry.total,
        groupId = entry.groupId,
        splitType = entry.splitType,
        participants = EntryOps.splitInputsOf(entry, resolver),
        occurredOn = entry.occurredOn,
        note = entry.note,
        fx = fx,
        payerSplit = entry.payerSplit,
    )
```

`draftFromVersion` goes through `draftOf`, so an old single-payer version (including one upcast from schema 1) opens with one `EQUAL` payer. Group detail lists its expenses as `ActivityItem`s, so they carry payers too.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.ledger.views.EntryViewsTest"`
Expected: PASS, the 3 new tests and every existing one.

Then: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Update the docs and commit**

In `docs/ui-contributor-guide.md`, section "Entries", replace:

```markdown
- Live split feedback: `previewSplit(draft, editing)`. When editing, it also returns the stored shares and whether saving would recompute them.
```

with:

```markdown
- Live split feedback: `previewSplit(draft, editing)` returns the shares and the payers the draft would get. When editing, it also returns the stored ones (`storedShares`, `storedPayers`) and whether saving would recompute each list (`recomputed`, `payersRecomputed`); the two are decided separately.
- "Paid by": `ActivityItem.payers` lists each payer once (`PayerView`: person, `paid` in the effect currency, and the EXACT amount in the paid currency as `inputValue`); transfers have none. `paidBy` is the first payer, for screens that still show one name. The edit draft carries `payers` and `payerSplit`.
```

In `docs/superpowers/specs/2026-11-02-iou-mvp-design.md`, §6.2, replace:

```markdown
Entry read models replace `paidBy` with `payers: List<PayerView(person, paid: Money)>` (in the effect currency), and `previewSplit` returns the resolved payers next to the shares.
```

with:

```markdown
Entry read models gain `payers: List<PayerView(person, paid: Money, inputValue)>` on `ActivityItem` (per canonical person, `paid` in the effect currency, `inputValue` the EXACT amount in the paid currency); `paidBy` stays as the first payer for single-payer screens. `previewSplit` returns the resolved payers next to the shares, with the stored ones and a `payersRecomputed` flag when editing. Until the UI moves to `payers`, `ExpenseDraft` keeps a single-payer constructor in its old parameter order and a deprecated `payer` accessor.
```

```bash
git add android/app/src docs/ui-contributor-guide.md docs/superpowers/specs/2026-11-02-iou-mvp-design.md
git commit -m "Show expense payers in activity, previews and edit drafts"
```

---

### Task 3: A two-payer expense in the fake, contract tests for AC 23, 24 and 26

**Files:**
- Modify: `main/.../fake/SeedData.kt`, `docs/m2-ui-contract-changes.md`
- Modify (tests): `test/.../fake/SeedDataTest.kt`, `test/.../api/contract/LedgerApiContract.kt`

**Interfaces:**
- Consumes: `ExpenseDraft` primary constructor, `PayerSplit`, `ActivityItem.payers`, `SplitPreview.payers` (Tasks 1–2); `ApiContract` helpers (`setUpMe`, `newPerson`, `basis`, `eur`, `sek`, `TODAY`), `FxApi.userRate`.
- Produces: the seeded "Museum tickets" expense (Lisbon trip, €80, Alex €50 and Bea €30 EXACT, EQUAL over the four members). The contract tests run against both flavors through the existing `FakeLedgerApiContractTest` and `RealLedgerApiContractTest`.

- [ ] **Step 1: Write the failing tests**

In `test/.../fake/SeedDataTest.kt`, add the import `io.github.codex07.iou.money.Money` and this test after `the seed has a CONVERT EUR group with a SEK expense and a KEEP group`:

```kotlin

    @Test
    fun `the seed has a group expense with two payers`() = runTest {
        val museum = apis.ledger.observeActivity().first().single { it.description == "Museum tickets" }
        museum.payers.map { it.person.displayName to it.paid } shouldBe listOf("Alex" to Money(5_000, EUR), "Bea" to Money(3_000, EUR))
    }
```

In `test/.../api/contract/LedgerApiContract.kt`, add the import `io.github.codex07.iou.ledger.model.PayerSplit`, and add these tests before the last closing brace:

```kotlin

    @Tag("groups")
    @Test
    fun `two payers in a group give nets of what each paid minus their share`() = runTest {
        setUpMe()
        val alice = newPerson("Alice")
        val bob = newPerson("Bob")
        val carol = newPerson("Carol")
        val group = apis.groups.createGroup(GroupDraft("Dinner", EUR, FxMode.KEEP), listOf(alice, bob, carol), basis()).orFail()
        val draft = ExpenseDraft(
            listOf(SplitInput(alice, 6_000), SplitInput(bob, 4_000)), "Dinner", total = eur(10_000), groupId = group,
            participants = listOf(SplitInput(alice), SplitInput(bob), SplitInput(carol)), occurredOn = TODAY,
            payerSplit = PayerSplit.EXACT,
        )
        val id = apis.ledger.saveExpense(draft, SaveMode.Create(basis())).orFail()

        val detail = apis.ledger.observeEntry(id).first()!!
        detail.item.payers.map { it.person.id to it.paid } shouldBe listOf(alice to eur(6_000), bob to eur(4_000))
        // Who gets the extra cent of 100 / 3 depends on the generated IDs, so the expected nets come from the
        // stored shares: Alice +26.66 or +26.67, Bob +6.67 or +6.66, Carol −33.33 or −33.34 (AC 23).
        val share = detail.shares.associate { it.person.id to it.share.amountMinor }
        val paid = mapOf(alice to 6_000L, bob to 4_000L, carol to 0L)
        val nets = apis.groups.observeGroup(group).first()!!.members.associate { it.person.id to it.nets }
        for (person in listOf(alice, bob, carol)) {
            nets.getValue(person) shouldBe listOf(eur(paid.getValue(person) - share.getValue(person)))
        }
        detail.flows.map { Triple(it.paidBy.id, it.paidFor.id, it.amount) }.toSet() shouldBe setOf(
            Triple(alice, carol, eur(6_000 - share.getValue(alice))),
            Triple(bob, carol, eur(4_000 - share.getValue(bob))),
        )

        apis.ledger.saveExpense(draft.copy(payers = listOf(SplitInput(alice, 6_000), SplitInput(bob, 3_000))), SaveMode.Create(basis())) shouldBe
            LedgerResult.Err(LedgerError.PaidRemaining(1_000))
        val equal = apis.ledger.previewSplit(draft.copy(payerSplit = PayerSplit.EQUAL, payers = listOf(SplitInput(alice), SplitInput(bob)))).orFail()
        equal.payers.map { it.paid } shouldBe listOf(eur(5_000), eur(5_000))
    }

    @Test
    fun `outside groups me and the other person can both pay, but a third payer needs a group`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val y = newPerson("Y")
        val lunch = ExpenseDraft(
            listOf(SplitInput(me, 3_000), SplitInput(x, 7_000)), "Lunch", total = eur(10_000),
            participants = listOf(SplitInput(me), SplitInput(x)), occurredOn = TODAY, payerSplit = PayerSplit.EXACT,
        )
        apis.ledger.saveExpense(lunch, SaveMode.Create(basis())).orFail()

        // I paid 30 of my 50: I owe X 20.00 (AC 24).
        apis.people.observePerson(x).first()!!.balances shouldBe listOf(eur(-2_000))
        apis.ledger.observeOverview().first().iOwe shouldBe listOf(eur(2_000))
        val third = lunch.copy(payers = listOf(SplitInput(me, 3_000), SplitInput(x, 4_000), SplitInput(y, 3_000)))
        apis.ledger.saveExpense(third, SaveMode.Create(basis())) shouldBe LedgerResult.Err(LedgerError.NeedsGroup(setOf(me, x, y)))
    }

    @Tag("groups")
    @Test
    fun `exact payers of a converted expense add up to the converted total, and a note edit keeps payers and shares`() = runTest {
        val me = setUpMe()
        val a = newPerson("A")
        val b = newPerson("B")
        val flat = apis.groups.createGroup(GroupDraft("Flat", EUR, FxMode.CONVERT), listOf(a, b), basis()).orFail()
        val rate = apis.fx.userRate(TODAY, SEK, EUR, "0.08765").orFail()
        val id = apis.ledger.saveExpense(
            ExpenseDraft(
                listOf(SplitInput(a, 60_000), SplitInput(b, 40_000)), "Furniture", total = sek(100_000), groupId = flat,
                participants = listOf(SplitInput(me), SplitInput(a), SplitInput(b)), occurredOn = TODAY,
                fx = FxChoice.Rate(rate), payerSplit = PayerSplit.EXACT,
            ),
            SaveMode.Create(basis()),
        ).orFail()

        // 1 000 SEK at 0.08765 is €87.65; A's 600 SEK and B's 400 SEK are the weights (AC 26).
        val before = apis.ledger.observeEntry(id).first()!!
        before.item.effect shouldBe eur(8_765)
        before.item.payers.map { Triple(it.person.id, it.paid, it.inputValue) } shouldBe listOf(
            Triple(a, eur(5_259), 60_000L),
            Triple(b, eur(3_506), 40_000L),
        )
        before.shares.sumOf { it.share.amountMinor } shouldBe 8_765L

        apis.ledger.saveExpense((before.editDraft as ExpenseDraft).copy(note = "Sofa"), SaveMode.Update(id, before.token)).orFail()

        val after = apis.ledger.observeEntry(id).first()!!
        after.item.note shouldBe "Sofa"
        after.item.payers shouldBe before.item.payers
        after.shares shouldBe before.shares
        after.history.first().changes.filter { it.field.startsWith("paid") || it.field.startsWith("share") } shouldBe emptyList()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.fake.SeedDataTest" --tests "io.github.codex07.iou.fake.FakeLedgerApiContractTest"`
Expected: FAIL in `SeedDataTest` only (`the seed has a group expense with two payers`: no "Museum tickets"); the three contract tests already pass against the fake, because Tasks 1–2 built the behaviour they check. If a contract test fails here, stop and report it: it points at a defect in Task 1 or 2, not at missing seed data.

- [ ] **Step 3: Seed the two-payer expense**

In `main/.../fake/SeedData.kt`, add the import `io.github.codex07.iou.ledger.model.PayerSplit`, and replace:

```kotlin
        expense(ExpenseDraft(chen, "Groceries", Category.GROCERIES, Money(6_350, eur), trip, SplitType.SHARES,
            listOf(SplitInput(me, 1), SplitInput(alex, 2), SplitInput(chen, 1)), today.minusDays(18)))
```

with:

```kotlin
        expense(ExpenseDraft(chen, "Groceries", Category.GROCERIES, Money(6_350, eur), trip, SplitType.SHARES,
            listOf(SplitInput(me, 1), SplitInput(alex, 2), SplitInput(chen, 1)), today.minusDays(18)))
        // Two payers: Alex and Bea bought the museum tickets together.
        expense(
            ExpenseDraft(
                listOf(SplitInput(alex, 5_000), SplitInput(bea, 3_000)), "Museum tickets", Category.ENTERTAINMENT, Money(8_000, eur), trip,
                SplitType.EQUAL, everyone, today.minusDays(17), payerSplit = PayerSplit.EXACT,
            ),
        )
```

The seed only adds an entry to an existing group, so the counts other tests take from the seed (overview rows, people, groups) don't change; tests that count entries derive the number from the state.

One test does depend on every seeded expense having one payer: Part 1's version-1 backup test in `test/.../data/BackupFormatTest.kt` turns the seeded backup into a version-1 document, and a version-1 backup can't hold a two-payer expense. Leave that expense and its rows out of the version-1 document. Replace the whole test `a version-1 backup reads as the same ledger with one payer per expense` with (add the import `io.github.codex07.iou.ledger.model.EntryId`):

```kotlin
    @Test
    fun `a version-1 backup reads as the same ledger with one payer per expense`() {
        val written = format.write(rows, "0.1.0", T0, null)
        val content = (format.read(written, null) as LedgerResult.Ok).value.content
        val document = Json.parseToJsonElement(written.toString(Charsets.UTF_8)).jsonObject
        val v2Rows = document.getValue("rows").jsonObject
        val payerRows = v2Rows.getValue("payers").jsonArray.map { it.jsonObject }
        // A version-1 backup holds single-payer expenses only: the seed's two-payer expense and its rows stay out.
        val multi = payerRows.groupBy { it.getValue("expenseId") }.filterValues { it.size > 1 }.keys
        val payerOf = payerRows.associate { it.getValue("expenseId") to it.getValue("personId") }
        fun JsonObject.kept(key: String) = getValue(key) !in multi
        fun table(name: String, key: String) = JsonArray(v2Rows.getValue(name).jsonArray.map { it.jsonObject }.filter { it.kept(key) })
        val v1Expenses = v2Rows.getValue("expenses").jsonArray.map { it.jsonObject }.filter { it.kept("id") }.map { expense ->
            JsonObject(expense - "payerSplit" + ("payerPersonId" to payerOf.getValue(expense.getValue("id"))))
        }
        val v1Rows = v2Rows - "payers" + mapOf(
            "expenses" to JsonArray(v1Expenses),
            "shares" to table("shares", "expenseId"),
            "revisions" to table("revisions", "entityId"),
        )
        val v1 = JsonObject(document + mapOf("schemaVersion" to JsonPrimitive(1), "rows" to JsonObject(v1Rows)))

        val read = format.read(v1.toString().toByteArray(), null)

        val left = multi.map { EntryId(it.jsonPrimitive.content) }.toSet()
        left.size shouldBe 1
        (read as LedgerResult.Ok).value.content shouldBe content.copy(
            state = content.state.copy(expenses = content.state.expenses - left),
            revisions = content.revisions.filter { EntryId(it.entityId) !in left },
        )
        read.value.rows.payers.size shouldBe content.state.expenses.size - 1
    }
```

It needs the import `kotlinx.serialization.json.jsonPrimitive` as well; revision rows name their entity in `entityId` (`RevisionRow`).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.fake.SeedDataTest" --tests "io.github.codex07.iou.fake.FakeLedgerApiContractTest"`
Expected: PASS.

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.data.RealLedgerApiContractTest" --tests "io.github.codex07.iou.data.BackupFormatTest"`
Expected: PASS, the same three contract tests against the real flavor, and the backup tests with the two-payer seed.

- [ ] **Step 5: Run the full check**

Run the full check (index, locked decision 9): both JVM suites, the three assembles, both lints. Expected: every test passes in both flavors (Part 1's total plus 5 in `PayerOpsTest`, 3 in `EntryViewsTest`, 1 in `SeedDataTest` and 3 contract tests), lint 0 errors. The deprecation warning from `ui/forms/EntryInput.kt` is expected.

- [ ] **Step 6: Update the friend's summary and commit**

In `docs/m2-ui-contract-changes.md`, section "Multiple payers", replace:

```markdown
- `ExpenseDraft.payer` goes away. Instead: `payerSplit` (`EQUAL` or `EXACT`) and `payers: List<SplitInput>`, the same shape as `participants`. A single payer = one entry, no value.
```

with:

```markdown
- `ExpenseDraft` has `payerSplit` (`EQUAL` or `EXACT`) and `payers: List<SplitInput>`, the same shape as `participants`. A single payer = one entry, no value. Until the form moves over, the old constructor `ExpenseDraft(payer, description, …)` still works and `draft.payer` (deprecated) returns the first payer. Editing a several-payer expense through `draft.payer` saves one payer, so the form should switch to `payers` before such expenses appear in real data (the fake already has one: "Museum tickets" in the Lisbon trip).
```

and replace:

```markdown
- Read models: `paidBy` becomes `payers: List<PayerView(person, paid)>`. `previewSplit` also returns the resolved payers. Flows stay as they are.
```

with:

```markdown
- Read models: `ActivityItem.payers: List<PayerView(person, paid, inputValue)>` next to `paidBy`, which stays as the first payer. `previewSplit` also returns the resolved payers, and when editing the stored ones and `payersRecomputed`. Flows stay as they are.
```

```bash
git add android/app/src docs/m2-ui-contract-changes.md
git commit -m "Seed a two-payer expense and cover multiple payers in the contract tests"
```
