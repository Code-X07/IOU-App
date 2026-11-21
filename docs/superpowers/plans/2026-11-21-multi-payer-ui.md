# Multiple Payers UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the expense form and every "paid by" display from the single payer to `ExpenseDraft.payers` / `payerSplit` and `ActivityItem.payers`.

**Architecture:** Expense payers become their own fields on `EntryFields` (`payers`, `payerInputs`, `payerSplit`); `payer` stays as the transfer sender. `EntryInput` turns them into `SplitInput`s and back; the ViewModel gets small payer helpers routed through `change {}`; the screen shows one picker plus "Add another payer" rows. Displays read `ActivityItem.payers` through one pure label helper.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, Hilt, JUnit 5, kotlinx-coroutines-test, fake backend (`FakeApis.seeded()`).

**Spec:** `docs/superpowers/specs/2026-11-21-multi-payer-ui-design.md`

## Global Constraints

- Change only the `ui` package (and its tests). No changes to `ledger`, `api`, `store`, `fake`, `data`. If one seems needed, stop and report.
- UI code imports only `api`, `money`, `ledger.model`. No `Double`/`Float` for money; no payer arithmetic in the UI.
- No use of deprecated `ExpenseDraft.payer` anywhere in `ui` (transfer `TransferDraft.from` is unaffected).
- Single payer = one `SplitInput(person)` with no value and `PayerSplit.EQUAL`.
- Payer EXACT amounts are in the paid (total's) currency; parse with `Inputs.amount(text, currency)`.
- Amount inputs and displayed amounts respect hidden mode (`SensitiveFormField`, `MoneyText(…, hidden)`).
- Keep save, edit-token, FX, stale-version and ledger-replacement behaviour untouched.
- Commit messages: one plain imperative sentence, no type prefix, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Commit locally only; never push.
- Run Gradle from `android/` with JDK 21.

## Review Focus

- Editing "Museum tickets" and changing only the total: EXACT payers (50 + 30) no longer add up → `PaidRemaining` shown, nothing saved. (Task 2 test.)
- Changing currency to JPY while EXACT payer inputs hold "50.00" → `TooManyDecimals`, not a silent rounding. (Task 1 test.)
- Switching the group so an existing payer is not a member → the payer stays in `fields.payers`; save returns a membership error rather than dropping them. (Task 2 test.)
- "Open as draft" of an old multi-payer version → form loads all payers (`openRecovery` goes through `fromDraft`). (Task 1 round-trip test covers `fromDraft`; Task 2 test covers the Museum edit path.)
- Hidden balances: payer amounts in the preview and entry detail stay hidden. (Manual check, Task 6.)

---

### Task 1: Payer fields in `EntryInput`

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/codex07/iou/ui/forms/EntryInput.kt`
- Modify: `android/app/src/main/kotlin/io/github/codex07/iou/ui/viewmodel/EntryFormViewModel.kt` (only `load` default fields and `groupMembersForDraft`)
- Modify: `android/app/src/test/kotlin/io/github/codex07/iou/ui/viewmodel/FinancialFormsTest.kt` (`expenseFields` helper)
- Create: `android/app/src/test/kotlin/io/github/codex07/iou/ui/forms/EntryInputTest.kt`

**Interfaces:**
- Produces on `EntryFields`: `payers: List<String> = emptyList()`, `payerInputs: Map<String, String> = emptyMap()`, `payerSplit: PayerSplit = PayerSplit.EQUAL`. `payer` keeps its type and is now documented as "transfer sender".
- `EntryInput.draft(fields)` for expenses uses the primary `ExpenseDraft(payers = …, …, payerSplit = …)` constructor.
- `EntryInput.fromDraft(ExpenseDraft)` fills `payers`, `payerInputs`, `payerSplit` (and leaves `payer = ""`).

- [ ] **Step 1: Write failing tests in `EntryInputTest`** (plain JUnit, `eur = CurrencyCode("EUR")`, ids `"a"`, `"b"`)

```kotlin
private fun base() = EntryFields(amount = "80", currency = "EUR", description = "Museum", payers = listOf("a"),
    participants = listOf("a", "b"), date = "2026-11-04")
private fun ok(f: EntryFields) = (EntryInput.draft(f) as LedgerResult.Ok).value as ExpenseDraft

@Test fun `one payer becomes one valueless equal input`() {
    val d = ok(base().copy(payerSplit = PayerSplit.EXACT, payerInputs = mapOf("a" to "80")))
    assertEquals(listOf(SplitInput(PersonId("a"))), d.payers); assertEquals(PayerSplit.EQUAL, d.payerSplit)
}
@Test fun `equal payers carry no values`() {
    val d = ok(base().copy(payers = listOf("a", "b")))
    assertEquals(listOf(SplitInput(PersonId("a")), SplitInput(PersonId("b"))), d.payers); assertEquals(PayerSplit.EQUAL, d.payerSplit)
}
@Test fun `exact payers parse amounts in the paid currency`() {
    val d = ok(base().copy(payers = listOf("a", "b"), payerSplit = PayerSplit.EXACT, payerInputs = mapOf("a" to "50", "b" to "30,00")))
    assertEquals(listOf(SplitInput(PersonId("a"), 5_000), SplitInput(PersonId("b"), 3_000)), d.payers)
}
@Test fun `no payers is NoPayers`() =
    assertEquals(LedgerResult.Err(LedgerError.NoPayers), EntryInput.draft(base().copy(payers = emptyList())))
@Test fun `exact payer amounts respect the currency decimals`() {
    val r = EntryInput.draft(base().copy(amount = "80", currency = "JPY", payers = listOf("a", "b"),
        payerSplit = PayerSplit.EXACT, payerInputs = mapOf("a" to "50.00", "b" to "30")))
    assertTrue((r as LedgerResult.Err).error is LedgerError.TooManyDecimals)
}
@Test fun `blank exact payer amount is rejected`() {
    val r = EntryInput.draft(base().copy(payers = listOf("a", "b"), payerSplit = PayerSplit.EXACT, payerInputs = mapOf("a" to "80")))
    assertTrue(r is LedgerResult.Err)
}
@Test fun `fromDraft round trips multi payer exact drafts`() {
    val f = base().copy(payers = listOf("a", "b"), payerSplit = PayerSplit.EXACT, payerInputs = mapOf("a" to "50.00", "b" to "30.00"))
    val back = EntryInput.fromDraft(ok(f))
    assertEquals(f.payers, back.payers); assertEquals(PayerSplit.EXACT, back.payerSplit); assertEquals(f.payerInputs, back.payerInputs)
}
@Test fun `fromDraft keeps a single payer`() =
    assertEquals(listOf("a"), EntryInput.fromDraft(ok(base())).payers)
```

Check the exact `LedgerResult.Err` property name in `api/LedgerResult.kt` and adapt `.error` if it differs; also check the blank-amount error type that `Inputs.amount("")` returns and assert that exact type.

- [ ] **Step 2: Run, expect compile failure / FAIL**

Run (from `android/`): `./gradlew testFakeDebugUnitTest --tests "*EntryInputTest*"`
Expected: compilation error on `payers` / `payerInputs` / `payerSplit`.

- [ ] **Step 3: Implement in `EntryInput.kt`**

- Add the three fields to `EntryFields`.
- In `draft()`: move `ensure(fields.payer.isNotBlank())` into the transfer branch. In the expense branch: `ensure(fields.payers.isNotEmpty()) { LedgerError.NoPayers }`; `val split = if (fields.payers.size > 1) fields.payerSplit else PayerSplit.EQUAL`; map each payer id to `SplitInput(PersonId(id), if (split == PayerSplit.EXACT) Inputs.amount(fields.payerInputs[id].orEmpty(), currency).bind().amountMinor else null)`; build with named args `ExpenseDraft(payers = …, description = …, …, payerSplit = split)`.
- In `fromDraft()` for `ExpenseDraft`: `payers = draft.payers.map { it.personId.value }`, `payerSplit = draft.payerSplit`, `payerInputs = draft.payers.mapNotNull { p -> p.value?.let { p.personId.value to Money(it, draft.total.currency).toPlainString() } }.toMap()`. Remove `payer = draft.payer.value`.

- [ ] **Step 4: Update callers so everything compiles**

- `EntryFormViewModel.load`: default fields also set `payers = listOf(me!!.id.value)` (keep `payer = me` for transfers).
- `groupMembersForDraft()`: include `fields.payers`.
- `FinancialFormsTest.expenseFields`: replace `payer = self` with `payers = listOf(self)`.
- `EntryFormScreen.kt` still compiles (it reads `fields.payer`); its UI is replaced in Task 3.

- [ ] **Step 5: Run tests, expect PASS**

Run: `./gradlew testFakeDebugUnitTest --tests "*EntryInputTest*" --tests "*FinancialFormsTest*" --tests "*AdvancedManagementTest*"`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/github/codex07/iou/ui android/app/src/test/kotlin/io/github/codex07/iou/ui
git commit -m "Build expense drafts from a payer list instead of a single payer"
```

---

### Task 2: Payer helpers in `EntryFormViewModel` and form-level tests

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/codex07/iou/ui/viewmodel/EntryFormViewModel.kt`
- Modify: `android/app/src/test/kotlin/io/github/codex07/iou/ui/viewmodel/FinancialFormsTest.kt`

**Interfaces:**
- Consumes: Task 1 fields.
- Produces (all no-ops when not `editable`, all via `change {}`):
  - `fun addPayer()` — appends the first id from `payerCandidates()` not already in `payers`; no-op if none.
  - `fun removePayer(id: String)` — removes it and its input; when one payer remains, sets `payerSplit = EQUAL` and `payerInputs = emptyMap()`.
  - `fun setPayer(index: Int, id: String)` — replaces the payer at `index`, moving its typed input to the new id.
  - `fun setPayerSplit(split: PayerSplit)`
  - `fun setPayerInput(id: String, text: String)`
  - `fun payerCandidates(): List<String>` — active members of the selected group, or all people outside groups (same rule the screen uses for `candidates`).

- [ ] **Step 1: Write failing tests in `FinancialFormsTest`**

Helper: `private suspend fun museum(a: Apis) = a.ledger.observeActivity().first().first { it.description == "Museum tickets" }`.

```kotlin
@Test fun `two payer equal and exact expenses save with backend payer amounts`()
// for split in PayerSplit.entries: open(), change { expenseFields(a, group).copy(payers = listOf(self, friend)) },
// setPayerSplit(split); for EXACT setPayerInput(self, "7,50"); setPayerInput(friend, "5")
// advanceTimeBy(300); runCurrent(); preview = state.preview!!; assertEquals(2, preview.payers.size)
// save(); runCurrent(); assertNull(status.error)
// saved = observeEntry(completedId).item; assertEquals(preview.payers, saved.payers)
// EXACT: assertEquals(listOf(750L, 500L), saved.payers.map { it.paid.amountMinor })

@Test fun `payer amounts that do not add up cannot save`()
// EXACT with "1" and "1" on 12,50 → save → assertEquals(1050L, (error as LedgerError.PaidRemaining).diffMinor)

@Test fun `duplicate payers are rejected`()
// change { …copy(payers = listOf(self, self)) } → save → error is LedgerError.DuplicatePayer

@Test fun `negative or invalid payer amounts are rejected`()
// EXACT, setPayerInput(self, "-1") → save → error != null and completedId == null; repeat with "abc"

@Test fun `museum tickets note edit keeps both payers`()
// m = museum(a); before = observeEntry(m.entryId)!!; open(entryId = m.entryId.value); runCurrent()
// assertEquals(before.item.payers.map { it.person.id.value }, state.fields.payers); assertEquals(PayerSplit.EXACT, state.fields.payerSplit)
// change { it.copy(note = "Payer note edit") }; advanceTimeBy(300); runCurrent()
// assertEquals(false, preview!!.payersRecomputed)
// save(); runCurrent(); assertEquals(before.item.payers, observeEntry(m.entryId)!!.item.payers)

@Test fun `museum tickets total change without payer change reports PaidRemaining`()
// open Museum; change { it.copy(amount = "90") }; save(); runCurrent()
// assertTrue(error is LedgerError.PaidRemaining); entry payers unchanged

@Test fun `removing a payer saves one payer and resets the payer split`()
// open Museum; removePayer(second id); assertEquals(PayerSplit.EQUAL, fields.payerSplit); assertTrue(fields.payerInputs.isEmpty())
// advanceTimeBy(300); runCurrent(); assertEquals(true, preview!!.payersRecomputed)
// save(); runCurrent(); assertEquals(1, observeEntry(m.entryId)!!.item.payers.size)

@Test fun `changing group keeps payers that are not members`()
// open(), change { expenseFields(a, group).copy(payers = listOf(self, friend)) }
// chooseGroup(<a group friend is not in, created with only another person>); assertEquals(listOf(self, friend), fields.payers)
// save(); runCurrent(); assertNotNull(error); assertNull(completedId)

@Test fun `addPayer picks the next free candidate and setPayer moves its input`()
// open(); addPayer(); assertEquals(2, fields.payers.size); assertNotEquals(fields.payers[0], fields.payers[1])
// setPayerSplit(EXACT); setPayerInput(fields.payers[1], "3"); setPayer(1, other id); assertEquals("3", fields.payerInputs[other id])
```

Write these as full tests in the file's existing style (`runTest(dispatcher)`, `vm.open(); runCurrent()`, `vm.finish()` at the end).

- [ ] **Step 2: Run, expect FAIL** — `./gradlew testFakeDebugUnitTest --tests "*FinancialFormsTest*"` → unresolved `setPayerSplit` etc.

- [ ] **Step 3: Implement the helpers** listed in Interfaces in `EntryFormViewModel.kt`.

- [ ] **Step 4: Run, expect PASS** — `./gradlew testFakeDebugUnitTest testRealDebugUnitTest --tests "*FinancialFormsTest*"`.

- [ ] **Step 5: Commit** — `git commit -m "Let the expense form add, remove and split several payers"` (with the Co-Authored-By line).

---

### Task 3: Payer rows and payer preview on `EntryFormScreen`

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/codex07/iou/ui/screens/EntryFormScreen.kt`

**Interfaces:**
- Consumes: Task 2 helpers, `SplitPreview.payers`, `storedPayers`, `payersRecomputed`.

- [ ] **Step 1: Replace the expense payer picker** (transfers keep `ChoiceField("Sender", fields.payer, …)` unchanged).

Expense layout, under `BrowseHeading("Paid by")`:
- For each `(index, id)` in `fields.payers`: `ChoiceField("Payer", id, options, { vm.setPayer(index, it) }, enabled)` where `options = peopleOptions` minus ids used by other rows. Rows with index > 0 get `TextButton({ vm.removePayer(id) }) { Text("Remove payer") }`. Keep the label `"Payer"` so `ChoiceField`'s "Unavailable selection" text still applies.
- Payers not in `candidates`: `TextButton("Remove unavailable payer · <name or "Former member">")`, as for participants.
- `TextButton({ vm.addPayer() }, enabled = enabled && vm.payerCandidates().any { it !in fields.payers }) { Text("Add another payer") }`.
- With `fields.payers.size > 1`: `ChoiceField("Payer split", fields.payerSplit.name, listOf("EQUAL" to "Equal", "EXACT" to "Exact amount"), { vm.setPayerSplit(PayerSplit.valueOf(it)) }, enabled)`.
- With EXACT: per payer `SensitiveFormField("$name · paid ${fields.currency}", fields.payerInputs[id].orEmpty(), { vm.setPayerInput(id, it) }, hidden, enabled, error = status.error is LedgerError.PaidRemaining || payerError(status.error) == id)`, followed by `Text("Payer amounts are entered in the original currency and must add up to the expense total.", style = bodySmall)`. `payerError` returns the person id of `NegativeInput`/`MissingInput`/`DuplicatePayer` when it is in `fields.payers`.
- Names: `"You"` for me, else `displayName`, as in `peopleOptions`.
- Under the form error, when the error names a person (`NegativeInput`, `MissingInput`, `DuplicatePayer`, `DuplicateParticipant`) shown on this form, add `Text("Check ${name}.")` only if that error is shown here; find where `FormScaffold` shows `status.error` and add the line next to the payer section instead if the scaffold can't take it.

- [ ] **Step 2: NeedsGroup hint** — the distinct-people check uses `fields.participants + fields.payers` for expenses and `fields.payer + fields.receiver` for transfers.

- [ ] **Step 3: Preview** — inside `state.preview?.let`, after shares and before "Expense effect total":
  - `Text("Paid by", titleSmall)`, each `preview.payers` as a `Row` of name + `MoneyText(payer.paid, hidden, …)` like shares.
  - When editing: `Text(if (preview.payersRecomputed) "Saving will recalculate payer amounts." else "Stored payer amounts will be preserved.")`; if recomputed, `"Previously stored payers"` + `preview.storedPayers.orEmpty()` rows.
  - Existing shares wording stays driven by `preview.recomputed` only.

- [ ] **Step 4: Build and lint** — `./gradlew assembleFakeDebug lintFakeDebug` → BUILD SUCCESSFUL, no new lint errors.

- [ ] **Step 5: Commit** — `git commit -m "Show payer rows and resolved payer amounts on the expense form"`.

---

### Task 4: Payer error messages

**Files:**
- Modify: `android/app/src/main/kotlin/io/github/codex07/iou/ui/forms/LedgerErrorMessage.kt`
- Create: `android/app/src/test/kotlin/io/github/codex07/iou/ui/forms/PayerErrorMessageTest.kt`

- [ ] **Step 1: Failing tests**

```kotlin
private val eur = CurrencyCode("EUR")
@Test fun `paid remaining names the amount still to allocate`() {
    val m = LedgerError.PaidRemaining(1050).userMessage(currency = eur)
    assertTrue(m.startsWith("Payer amounts must add up to the expense total.")); assertTrue(m.contains("Still to allocate"))
}
@Test fun `paid remaining over allocation`() = assertTrue(LedgerError.PaidRemaining(-200).userMessage(currency = eur).contains("Over allocated"))
@Test fun `paid remaining hides the amount when balances are hidden`() =
    assertEquals("Payer amounts must add up to the expense total.", LedgerError.PaidRemaining(1050).userMessage(hidden = true, currency = eur))
@Test fun `input errors cover payers and participants`() {
    assertEquals("Enter a positive value for each payer and participant.", LedgerError.NegativeInput(PersonId("a")).userMessage())
    assertEquals("Enter a value for each payer and participant.", LedgerError.MissingInput(PersonId("a")).userMessage())
}
```

Assert the `MoneyFormatter.format(Money(1050, eur), magnitude = true)` text too if the formatter's output is stable in tests (see `MoneyFormatterTest`).

- [ ] **Step 2: Run, expect FAIL** — `./gradlew testFakeDebugUnitTest --tests "*PayerErrorMessageTest*"`.

- [ ] **Step 3: Implement** — `PaidRemaining` mirrors the `ExactRemaining` branch with the payer sentence; `NegativeInput` → `"Enter a positive value for each payer and participant."`; `MissingInput` → `"Enter a value for each payer and participant."`. `NoPayers`, `DuplicatePayer` unchanged. Check `FinancialFormsTest`/other tests for assertions on the old texts and update them.

- [ ] **Step 4: Run, expect PASS** — `./gradlew testFakeDebugUnitTest --tests "*PayerErrorMessageTest*" --tests "*FinancialFormsTest*"`.

- [ ] **Step 5: Commit** — `git commit -m "Explain payer totals and payer inputs in form errors"`.

---

### Task 5: Every payer in lists, entry detail and activity search

**Files:**
- Create: `android/app/src/main/kotlin/io/github/codex07/iou/ui/format/PayerFormat.kt`
- Create: `android/app/src/test/kotlin/io/github/codex07/iou/ui/format/PayerFormatTest.kt`
- Modify: `android/app/src/main/kotlin/io/github/codex07/iou/ui/components/DashboardComponents.kt` (`EntryPreview`)
- Modify: `android/app/src/main/kotlin/io/github/codex07/iou/ui/screens/ActivityScreen.kt` (`EntryDetailScreen`)
- Modify: `android/app/src/main/kotlin/io/github/codex07/iou/ui/viewmodel/ActivityViewModel.kt` (`matches`)
- Modify: `android/app/src/test/kotlin/io/github/codex07/iou/ui/viewmodel/BrowseViewModelsTest.kt`

**Interfaces:**
- Produces: `object PayerFormat { fun names(item: ActivityItem, you: String): String }` — expenses use `item.payers` (fall back to `paidBy` if empty); transfers use `paidBy`. Each name is `you` when `isMe`, else `displayName`. 1 → `"A"`; 2 → `"A & B"`; 3 → `"A, B & 1 other"`; 4+ → `"A, B & N others"` (as approved in the spec).

- [ ] **Step 1: Failing tests** — `PayerFormatTest` builds `ActivityItem`s from the seeded fake (`FakeApis.seeded()`, Museum tickets → `"Alex & Bea"`; a seeded transfer → its sender) plus a `copy(payers = …)` of Museum with 1, 3 and 5 `PayerView`s to pin `"You"`, `"Alex, Bea & 1 other"`, `"Alex, Bea & 3 others"`. Look up the exact seed display names before writing the expected strings.
  In `BrowseViewModelsTest`: save an expense where a second payer is **not** a participant, then assert `ActivityQuery(text = <that payer's name>).matches(item)` is true.

- [ ] **Step 2: Run, expect FAIL** — `./gradlew testFakeDebugUnitTest --tests "*PayerFormatTest*" --tests "*BrowseViewModelsTest*"`.

- [ ] **Step 3: Implement**
  - `PayerFormat.names` as specified.
  - `EntryPreview`: `val payer = PayerFormat.names(item, stringResource(R.string.you))`; keep the `paid_context` string.
  - `EntryDetailScreen`: for expenses replace the single `InfoLine("Payer", …)` + "View payer" with a `"Paid by"` sub-heading and, per `item.payers`, the name, `MoneyText(payer.paid, hidden)` and "View person" for non-me payers. Transfers keep `InfoLine("Sender", …)` + "View payer" unchanged.
  - `ActivityQuery.matches`: add `item.payers.map { it.person.displayName }` to the searched names.

- [ ] **Step 4: Run, expect PASS** — same command as Step 2, then `./gradlew assembleFakeDebug`.

- [ ] **Step 5: Commit** — `git commit -m "Show every payer and what they paid in lists and entry detail"`.

---

### Task 6: Full verification

- [ ] **Step 1:** `grep -rn "\.payer\b" android/app/src/main/kotlin/io/github/codex07/iou/ui` → only transfer uses of `EntryFields.payer`; no `ExpenseDraft.payer`. No deprecation warnings for `payer` in the build output.
- [ ] **Step 2:** From `android/`: `./gradlew testFakeDebugUnitTest testRealDebugUnitTest lintFakeDebug lintRealDebug assembleFakeDebug assembleRealDebug` → BUILD SUCCESSFUL (DomainPurityTest included).
- [ ] **Step 3 (if a device/emulator is connected):** `./gradlew installFakeDebug`; check Museum tickets in activity ("Alex & Bea paid"), entry detail (both amounts), Lisbon group list; edit note → both payers kept; add a single-payer expense; turn on hidden balances and confirm payer amounts are hidden. If no device, say so in the report.
- [ ] **Step 4:** Tick the "Payers on the expense form" item in `docs/m2-ui-contract-changes.md` and commit: `git commit -m "Mark the payer form as done in the M2 UI to-do list"`.
