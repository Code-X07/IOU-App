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

Every screen reads a `Flow` (`observeOverview()`, `observePerson(id)`, `observeGroup(id)`, `observeEntry(id)`, …). Read-model flows update automatically after writes that change what they observe, so screens never refresh by hand. Equal setup or settings values may be suppressed. Observe `BackupApi.observeLedgerGeneration()` separately to detect ledger replacement and invalidate drafts. Read models are ready to render: balances are computed, people are canonical (merged duplicates appear once), amounts are `Money`.

Signs: a person balance is `net(me, X)`. **Positive means they owe me.** A group net is `groupNet(member)`. **Positive means the group owes them.** Overview totals are positive magnitudes.

Format money with `Money.toPlainString()` + the currency code, or your own locale formatting from `amountMinor` and `currency.fractionDigits`. Never convert to `Double`.

## Writes

Writes are `suspend` and return `LedgerResult<T>`: `Ok(value)` or `Err(LedgerError)`. Nothing is ever partially written.

- **Edits** take the `EditToken` from the read model you edited (`PersonDetail.token`, `EntryDetail.token`, …).
- **Everything else** (creates, member add/remove, settle-ups) takes a `basis`: the `LedgerGeneration` from `BackupApi.observeLedgerGeneration()` when the form opened. Settle-up drafts from the core already contain their basis.
- **When the generation changes** (a backup was restored), drop open drafts and previews and go back to the overview. Writes prepared before return `LedgerReplaced`. Show that error only to an action or authentication that was actually interrupted (an Advanced action under review, a pending unlock); a generation change with nothing pending is silent.
- `StaleVersion`: the entity changed since you read it; reload and let the user redo the edit.
- **Import previews** hold the parsed file in memory. `DataManagementViewModel` calls `discardImport(preview)` whenever a preview is dropped without a commit (Back, discard, replacement, a newer parse, a parse cancelled mid-flight, `end()`, `onCleared`); parsing another file ends earlier previews in the real flavor (`NotFound("preview")` afterwards).
- **Restore and undo-restore outlive their screen.** A replacement changes the generation, which clears the route stack and the Data-management ViewModel. `DataManagementViewModel` therefore starts them through the singleton `LedgerReplacements` (`ui/settings/`), which runs the call in its own scope, rejects a second replacement while one runs (`running`), and publishes the final text in `outcome`. The ViewModel stays busy until its callback arrives (if it still exists). `IOUApp` shows `outcome` in the snackbar and then calls `consumeOutcome()`; the generic "ledger was replaced" text appears only for replacements nobody started from this screen.
- **Interrupted restore.** `AppSettingsViewModel.restoreInterrupted` follows `BackupApi.observeRestoreInterrupted()`. `IOUApp` (rendered only after setup and unlock) shows "The last restore was interrupted. Nothing changed." and calls `dismissRestoreInterrupted()` only when the user taps Done.
- **Document grants are not persisted.** Every picked URI is used immediately, so the screen does not call `takePersistableUriPermission`; read and write problems arrive as `FileAccessFailed`.

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

Every change is recorded and must be viewable (spec §1, §5.3). `EntryDetail.history` covers the entry. `PersonDetail.history` covers the person, every alias merged into them (the merge itself is the alias's revision) and their payment handles; `GroupDetail.history` covers the group and its memberships. Deleted rows are included. Each `RevisionView` says which row it belongs to (`entity`, `subject`); show the subject when it isn't the screen's own row. To revert, call `revertTo(revision.entity, revision.version, revision.token)`: each `RevisionView` carries the current token of its own row, which is the only token available for memberships and deleted handles. Never substitute the screen's own token (`PersonDetail.token`, `GroupDetail.token`) for another row's revision.

Deleted people and groups disappear from the normal lists but must stay reachable: `observePeople(includeDeleted = true)` and `observeGroups(includeDeleted = true)` list them (`deleted = true`), so a "Recently deleted" view can open their detail, history and restore. Deleted entries come from `observeActivity(ActivityFilter(includeDeleted = true))`.

Payment handles: fill an edit form's `HandleDraft` from the `PaymentHandleView`, `isDefault` included. Leaving the default switch as shown keeps every stored flag (a merged alias's own default comes back after an unmerge); switching it on moves the default.

## Settle up

- Group detail has `settlePlan`; person detail has `inGroups`. Call `settleUpFromPlan(item)` to get a pre-filled `SettleUpDraft`, let the user adjust amount or currency, and save with `SaveMode.Create(draft.basis)`.
- `settleUpWithPerson(id, basis)` gives one draft per non-zero currency outside groups.

## App lock and screen protection

The core runs the policy and the window flag; you build the lock screen (spec §9).

- While `SettingsApi.observeLockRequired()` is `true`, cover all content with a lock screen and show `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`. On success call `markUnlocked()`. Add `androidx.biometric` for this; it brings both the `USE_BIOMETRIC` and the legacy `USE_FINGERPRINT` permissions.
- Only offer the app-lock switch when the device has a secure lock screen (`KeyguardManager.isDeviceSecure`).
- The core locks on a cold start and after the chosen timeout in the background, following the process lifecycle. Don't track that yourself.
  `SecurityViewModel` follows that flag only: leaving the foreground covers the content (privacy) but never locks it, and `MainActivity` and `IouRoot` never touch window flags.
- Screen protection: `setScreenProtection(true)` is all you call. The core sets `FLAG_SECURE` on every activity window and follows later changes.

## Errors

`LedgerError` (in `api/LedgerError.kt`) is a sealed interface; map every case to a message. Each carries the data a good message needs, e.g. `ExactRemaining(diffMinor)`, `MemberHasBalance(groupId, personId, nets)`, `NeedsGroup(people)`. `RestoreConflict(violations)` after a revert means "this old version breaks today's rules"; offer **Open as draft** via `draftFromVersion(revision.entity, revision.version)` and open the edit form matching the `VersionDraft` (entry, person, handle or group).

Payer errors (M2): `NoPayers` (the payer list is empty), `PaidRemaining(diffMinor)` (EXACT payer amounts don't add up to the total; diff in the paid currency), `DuplicatePayer(personId)` (the same person twice in the payer list).

## Route-scoped ViewModels

`IOUApp` keeps its own navigation stack, so each stacked route gets its own `ViewModelStore` (`ui/navigation/RouteStores.kt`) and is composed under `LocalViewModelStoreOwner`. Call `hiltViewModel()` with no key inside a route; the ViewModel is cleared when the route leaves the stack, so a popped form's draft and payment fields are gone on the next visit. A nested form reaches its parent's ViewModel with `hiltViewModel(viewModelStoreOwner = routeStores.owner(parentRoute, activity))`. Root-level and tab ViewModels (Overview, settings, security, Advanced, browse session, tab lists) stay activity-scoped. Rotation keeps open routes; a ledger generation change empties the stack and clears all of them. Route ViewModels must not use `SavedStateHandle`: the route owner reuses the activity's creation extras, so a handle would be shared per class and outlive the route.

## Changing the contract

The `api` package is shared ground. To change it, open a small PR that touches `api` **and** the fake together; both of us review it. Contract tests run against the fake (and later the real implementation), so a change one side can't satisfy fails CI. Don't add Android types to `api`.

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

Each flavor's `BackendModule` also provides a `DataServices` value (`DataServices.Simulated` in the fake,
`DataServices.Real` in the real flavor). `DataManagementViewModel` and `AppSettingsViewModel` expose it, and
the backup, export, restore and import screens read it to choose file names, MIME types and wording, and to
show the canned import scenarios only when it is simulated.

Simulated parts (fake flavor only):

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
