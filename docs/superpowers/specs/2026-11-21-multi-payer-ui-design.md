# Multiple payers in the UI: design

Date: 2026-11-21 · Branch: `feat/m2-ui` · Source task: M2 Task 1 (multiple payers UI integration)

## Intent

The M2 core already stores expenses paid by several people (`ExpenseDraft.payers` / `payerSplit`,
`ActivityItem.payers`, `SplitPreview.payers`). The UI still edits and shows a single payer, so editing a
multi-payer expense (the fake's "Museum tickets": Alex 50, Bea 30 EUR, EXACT) silently saves one payer.
This change moves the expense form and every "paid by" display to the payer list. It is the last
expense-side item before v0.2.0.

## Goals

- Create expenses with one payer (unchanged feel) or several, split `EQUAL` or `EXACT`.
- Edit any expense without losing or collapsing payers; preview resolved payers from `previewSplit`, and
  when editing show stored payers when `payersRecomputed` (decided separately from shares' `recomputed`).
- Show every payer and what they paid in the activity/overview/group lists and in entry detail.
- Clear messages for `NoPayers`, `PaidRemaining`, `DuplicatePayer`, and payer-side `NegativeInput`,
  `MissingInput`, `InvalidAmount`, `AmountTooLarge`.

## Non-goals / anti-goals

- No change to `ledger`, `api`, `store` or `fake`. If a contract change turns out to be needed, stop and report.
- No schedule (recurring) form in this task, though `EntryInput` changes should be reusable by it later.
- Anti-goal: any path where an edit saves fewer payers than were stored without the user removing them.
- Anti-goal: payer math in the UI. The UI sends inputs; amounts shown come from `previewSplit` / read models.
- Preserve save, edit-token, FX, stale-version, ledger-replacement and hidden-balance behaviour.

## Approaches considered

- **A. Chip selector like Participants**, split controls at 2+ payers. Least new UI; one tap turns an
  ordinary expense into a multi-payer one.
- **B. Single picker plus "Add another payer" rows** (chosen by the user). The form looks as today until a
  second payer is added; each extra payer is a row with a remove button.

## Design

### Form state (`ui/forms/EntryInput.kt`)

- `EntryFields` keeps `payer` for the transfer sender only, and gains for expenses:
  `payers: List<String>` (ordered, first = the original picker), `payerInputs: Map<String, String>`
  (EXACT amounts as typed, paid currency), `payerSplit: PayerSplit = EQUAL`.
- New expense defaults: `payers = [me]`, `payerSplit = EQUAL`.
- `draft()` builds `ExpenseDraft(payers = …, payerSplit = …)` with the primary constructor. One payer →
  one `SplitInput` with no value and `EQUAL`. Two or more with EXACT → each value via
  `Inputs.amount(text, currency)` (errors from that are naturally `InvalidAmount` / `TooManyDecimals` /
  `AmountTooLarge`). Empty payer list → `LedgerError.NoPayers` locally (the core would say the same).
  Duplicates are passed through so the core returns `DuplicatePayer`.
- `fromDraft()` reads `draft.payers` and `draft.payerSplit`, filling `payerInputs` from EXACT values as
  `Money(value, total.currency).toPlainString()`. No use of deprecated `draft.payer` anywhere in `ui`.

### Form screen (`ui/screens/EntryFormScreen.kt`)

```
Paid by
  [ You            ▾ ]
  [ Bea            ▾ ]  (remove)
  + Add another payer
  Payer split: ( Equal | Exact amount )        ← only with 2+ payers
  You · paid EUR  [ 50.00 ]                     ← EXACT only, SensitiveFormField
  Bea · paid EUR  [ 30.00 ]
```

- Row 1 is the existing `ChoiceField`; extra rows are `ChoiceField`s with a remove `TextButton`. "Add
  another payer" appends the first candidate not already a payer (disabled when none is left).
- The split chooser and amount fields appear only with 2+ payers. Removing down to one payer resets to
  `EQUAL` and drops payer inputs (a single payer carries no value).
- Payers no longer in the context's candidates stay visible with "Remove unavailable payer · <name>",
  matching participants, so a group change never silently alters payers.
- Helper text for EXACT: payer amounts are in the original currency and must add up to the total.
- Live preview gains a "Paid by" block from `preview.payers` (person + `paid`, via `MoneyText`, hidden
  aware); when editing and `preview.payersRecomputed`, a note "Saving will recalculate payer amounts" plus
  "Previously stored payers" from `storedPayers`, else "Stored payer amounts will be preserved". The
  existing shares wording is kept and driven only by `recomputed`.
- The NeedsGroup hint counts payers too.

### ViewModel (`ui/viewmodel/EntryFormViewModel.kt`)

- Small helpers: `addPayer()`, `removePayer(id)`, `setPayer(index, id)`, `setPayerSplit(split)`,
  `setPayerInput(id, text)` — all through `change {}` so dirty tracking, debounce and preview stay as is.
- `groupMembersForDraft()` includes all payers. No change to save, token, FX or stale handling.

### Displays

- `EntryPreview` (`ui/components/DashboardComponents.kt`, used by overview, activity and group detail):
  payer label from `item.payers` — "You paid", "Alex & Bea paid", "Alex, Bea & 1 other paid"; transfers keep
  using `paidBy`. Uses the existing `paid_context` string with the joined names (plural string added if needed).
- `EntryDetailScreen` (`ui/screens/ActivityScreen.kt`): for expenses, a "Paid by" section listing each
  `PayerView` with `paid` (hidden aware) and "View person" for non-me payers; transfers keep "Sender".
- Activity search (`ActivityViewModel`) matches every payer name.

### Errors (`ui/forms/LedgerErrorMessage.kt`)

- `PaidRemaining(diff)`: like `ExactRemaining` — "Payer amounts must add up to the expense total. Still to
  allocate / Over allocated: <amount>" (amount omitted when hidden or currency unknown).
- `NoPayers`, `DuplicatePayer`: keep current text. `NegativeInput` / `MissingInput`: reword to cover payers
  and participants ("Enter a positive value for each payer and participant"); naming the person needs a
  name lookup the mapper doesn't have, so the form additionally shows "Check <name>" under the error when
  the person is known. (Builder's call to keep or drop that extra line.)
- Error highlighting: payer amount fields flag on `PaidRemaining` and on input errors naming a payer.

## Testing

- `EntryInput` unit tests: draft/fromDraft round trip for one payer, EQUAL multi, EXACT multi (incl.
  0- and 3-decimal currencies); no payers → `NoPayers`.
- `FinancialFormsTest` additions on the seeded fake: create two-payer EQUAL and EXACT; EXACT not adding up →
  `PaidRemaining`; duplicate → `DuplicatePayer`; negative/blank/invalid payer amount rejected; editing
  "Museum tickets" note-only keeps both payers and `payersRecomputed == false`; removing a payer then saving
  stores one payer; single-payer flows unchanged (existing tests updated from `fields.payer`).
- `BrowseViewModelsTest`/activity: search finds the second payer.
- Run `testFakeDebugUnitTest testRealDebugUnitTest`, `lintFakeDebug lintRealDebug`,
  `assembleFakeDebug assembleRealDebug`; manual check of Museum tickets in the fake build if a device is available.

## Left to the builder

- Exact Compose layout details within the existing components and spacing.
- Whether "Add another payer" picks the first free candidate or opens the picker empty.
- Test names and grouping.
