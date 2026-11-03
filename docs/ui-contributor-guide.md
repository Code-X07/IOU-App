# UI contributor guide

You own everything in the `ui` package: screens, ViewModels, navigation, theming, strings, mapping
`LedgerError` to messages, `BiometricPrompt` and UI tests. The core owns everything behind the `api`
package. Screen layouts and flows are yours to design; the spec deliberately has none.

Spec: [`superpowers/specs/2026-11-02-iou-mvp-design.md`](superpowers/specs/2026-11-02-iou-mvp-design.md), especially §4 (how balances work), §6 (this contract) and §8 (errors).

## Where things are

- Contract: `android/app/src/main/kotlin/io/github/codex07/iou/api/`. Start with `LedgerApi.kt`, `ReadModels.kt` and `Drafts.kt`.
- Your code: `android/app/src/main/kotlin/io/github/codex07/iou/ui/` (create it). Import only `api`, `money` and `ledger.model`.
- Inject interfaces into ViewModels with Hilt: `@HiltViewModel class OverviewViewModel @Inject constructor(private val ledger: LedgerApi) : ViewModel()`.

## Reads

Every screen reads a `Flow` (`observeOverview()`, `observePerson(id)`, `observeGroup(id)`, `observeEntry(id)`, …). Flows re-emit after any write, so screens never refresh by hand. Read models are ready to render: balances are computed, people are canonical (merged duplicates appear once), amounts are `Money`.

Signs: a person balance is `net(me, X)`. **Positive means they owe me.** A group net is `groupNet(member)`. **Positive means the group owes them.** Overview totals are positive magnitudes.

Format money with `Money.toPlainString()` + the currency code, or your own locale formatting from `amountMinor` and `currency.fractionDigits`. Never convert to `Double`.

## Writes

Writes are `suspend` and return `LedgerResult<T>`: `Ok(value)` or `Err(LedgerError)`. Nothing is ever partially written.

- **Edits** take the `EditToken` from the read model you edited (`PersonDetail.token`, `EntryDetail.token`, …).
- **Everything else** (creates, member add/remove, settle-ups) takes a `basis`: the `LedgerGeneration` from `BackupApi.observeLedgerGeneration()` when the form opened. Settle-up drafts from the core already contain their basis.
- **When the generation changes** (a backup was restored), drop open drafts and previews and go back to the overview. Writes prepared before return `LedgerReplaced`.
- `StaleVersion`: the entity changed since you read it; reload and let the user redo the edit.

## Entries

- New transfer or expense: build a `TransferDraft`/`ExpenseDraft` and call `saveTransfer`/`saveExpense` with `SaveMode.Create(basis)`.
- Edit: start from `EntryDetail.editDraft` (it's already pre-filled correctly, including FX and merged participants), change fields, save with `SaveMode.Update(id, token)`.
- Parse typed amounts with `Inputs.amount(text, currency)` and codes with `Inputs.currency(text)`.
- Live split feedback: `previewSplit(draft, editing)`. When editing, it also returns the stored shares and whether saving would recompute them.
- Outside groups an entry involves exactly me and one other person. Anything else returns `NeedsGroup(people)`: offer to create a group from those people.

### Currencies

- If the save returns `RateRequired(from, to)`, a conversion is needed. Get a rate with `FxApi.quote(date, from, to)`. If that also returns `RateRequired`, ask the user to type a rate (`FxApi.userRate`) or the amount actually charged (`FxChoice.BankActual(charged)`). Put the choice in `draft.fx` and save again.
- In a CONVERT group the effect is in the group currency. Outside CONVERT groups, set `TransferDraft.settlesCurrency` to settle a debt in one currency with money in another.
- `FxSnapshotMismatch` on an edit means the kept rate no longer fits (different currency pair, or a bank-actual charge after the paid amount changed). Ask for a new rate.

## Merging duplicates

- `previewMerge(source, target)` shows the result; `merge(source, target, sourceToken)` takes the source's token from its `PersonDetail` before the merge.
- After a merge the source shows up in the target's `PersonDetail.aliases`, each `AliasView` with its own token. Unmerge with `unmerge(alias.person.id, alias.token)`.

## History

Every change is recorded and must be viewable (spec §1, §5.3). `EntryDetail.history` covers the entry. `PersonDetail.history` covers the person, every alias merged into them (the merge itself is the alias's revision) and their payment handles; `GroupDetail.history` covers the group and its memberships. Deleted rows are included. Each `RevisionView` says which row it belongs to (`entity`, `subject`); show the subject when it isn't the screen's own row. To revert, call `revertTo(revision.entity, revision.version, token)` with that row's token (`PersonDetail.token`, `AliasView.token`, `PaymentHandleView.token`, `GroupDetail.token`, `EntryDetail.token`).

Deleted people and groups disappear from the normal lists but must stay reachable: `observePeople(includeDeleted = true)` and `observeGroups(includeDeleted = true)` list them (`deleted = true`), so a "Recently deleted" view can open their detail, history and restore. Deleted entries come from `observeActivity(ActivityFilter(includeDeleted = true))`.

Payment handles: fill an edit form's `HandleDraft` from the `PaymentHandleView`, `isDefault` included. Leaving the default switch as shown keeps every stored flag (a merged alias's own default comes back after an unmerge); switching it on moves the default.

## Settle up

- Group detail has `settlePlan`; person detail has `inGroups`. Call `settleUpFromPlan(item)` to get a pre-filled `SettleUpDraft`, let the user adjust amount or currency, and save with `SaveMode.Create(draft.basis)`.
- `settleUpWithPerson(id, basis)` gives one draft per non-zero currency outside groups.

## Errors

`LedgerError` (in `api/LedgerError.kt`) is a sealed interface; map every case to a message. Each carries the data a good message needs, e.g. `ExactRemaining(diffMinor)`, `MemberHasBalance(groupId, personId, nets)`, `NeedsGroup(people)`. `RestoreConflict(violations)` after a revert means "this old version breaks today's rules"; offer **Open as draft** via `draftFromVersion(revision.entity, revision.version)` and open the edit form matching the `VersionDraft` (entry, person, handle or group).

## Changing the contract

The `api` package is shared ground. To change it, open a small PR that touches `api` **and** the fake together; both of us review it. Contract tests run against the fake (and later the real implementation), so a change one side can't satisfy fails CI. Don't add Android types to `api`.

## Running the fake

Available after M0 Part 7 lands (instructions added then).
