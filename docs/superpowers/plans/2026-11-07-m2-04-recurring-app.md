# M2 Part 4: Recurring App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Read `docs/superpowers/plans/2026-11-07-m2-00-index.md` first: its locked decisions and type inventory apply here. This part starts from the code as Parts 1–3 leave it.

**Goal:** Schedules reach the app. They persist in Room schema 2 (same `Migration(1, 2)`), in revision snapshots and in backups; posted expenses keep `recurringId` / `recurringOn` there too. `RecurringRunner` auto-posts in both flavors, triggered after the start-up load, a restore, an import or undo, every return to the foreground, and (real flavor) a 6-hourly `recurring-post` worker. `RecurringApi` exposes schedules, the due list and posting to the UI; the fake seed has a confirm-first and an auto-post schedule; the real flavor shows "due" and "posted" notifications. Contract and store tests cover AC 27–33 and the backup half of AC 35; AC 34 is a device check. CSV export and import of schedules come in Part 5.

**Architecture:** Persistence follows the share pattern: a `recurring_expense` table with an owned `recurring_participant` table (`position` counted per role), two nullable columns on `expense`, the schedule inside its revision snapshot, and two lists in `LedgerRows`. `RecurringRunner` (in `store`) reads `LedgerViews`, takes a rate from a `QuoteSource` when an occurrence needs a conversion, posts through `RecurringOps.post` under the store's write lock, keeps failures in memory and reports each pass to a `RecurringNotices` port. Triggers that need Android (process lifecycle, WorkManager) live outside `store`. The notification texts and the "notify again?" decision are a pure `RecurringNoticePlanner`; `AndroidRecurringNotices` only draws them.

**Tech Stack:** Kotlin, Room 2.8.1 (bundled SQLite in JVM tests), kotlinx.serialization, coroutines, WorkManager (real flavor), `NotificationManagerCompat`, `ProcessLifecycleOwner`, JUnit 5, Kotest, Turbine.

## Global Constraints

- **Room schema 2 (spec §3.2):** `recurring_expense` (the schedule's columns plus sync columns; `autoPost` as INTEGER) and `recurring_participant` (key `recurringId, role, personId`; `position`, `inputValue`), created by the same hand-written `Migration(1, 2)`, which also gives `expense` the nullable `recurringId` and `recurringOn` columns (index, locked decision 3). Rows are written with `REPLACE`, never `@Upsert`.
- **Snapshots (spec §5.5):** a schedule snapshot holds its row and its participant rows; the schema-1 upcaster gives expenses `recurringId` / `recurringOn` = null. Backups carry `schedules` and `participants`; a participant row whose schedule is missing makes the backup `CorruptFile`.
- **Runner (spec §5.7):** per pass, each schedule that auto-posts, by ID: post its due occurrences oldest first while the next one is on or after `autoPostFrom` and on or before today; a conversion takes the rate for the occurrence date from the lookup chain's steps 1–3 (a stale rate is fine); any failure (`RateRequired`, a rule such as `MemberRemoved`, a lost race) stops that schedule at that occurrence, is kept in memory as its `autoPostError` and is cleared by a later pass that gets past it; other schedules still run. Held schedules are skipped, including one put on hold while its rate was being fetched. A ledger without me (before setup, or after undoing a restore onto a fresh install) posts nothing, and the pass and `redraw()` report an empty due list, which clears the notifications of a discarded ledger.
- **Triggers:** start-up load and every new generation (restore, undo-restore), every change of the import batches (import, undo), every change of the due list whatever wrote it (an archive through `PeopleApi` / `GroupsApi`, a merge, a revert), every process `ON_START`, every successful `RecurringApi` write, and the real flavor's unique periodic work `recurring-post` (6 hours, no constraints, policy `KEEP`). Date-relative reads (`observeDue`, `observeSchedules`, `observeSchedule`) also start over when a pass runs for a new day (`RecurringRunner.today`). An app-lock switch (`RecurringNotices.redraw`) is not a pass: it runs `RecurringRunner.redraw()`, which reports the current due list at once without posting or waiting for a running pass. A post re-checks under the write lock that the schedule still auto-posts (a hold started during a rate request leaves its token valid) and posts nothing otherwise.
- **Notifications (real flavor, spec §5.7):** channel "Recurring expenses due" (default importance): one per schedule with due occurrences, re-notified only when its oldest due occurrence or its failure changes, redrawn silently when app lock is switched on or off; the group summary is redrawn only when the group changes and never alerts, and when the group shrinks to one schedule the summary is removed (Android removes its children with it) and the schedule left is redrawn silently; the last notified state per schedule (including whether it was redacted) and whether a detailed posted summary may be showing in `noBackupFilesDir/recurring-notified.json` (unreadable = empty). Channel "Recurring expenses posted" (low importance): one summary per pass that posted; switching app lock on removes a detailed one. Tap → `MainActivity` with `iou.open = "due"` and, for a single schedule, `iou.schedule = <id>`. `VISIBILITY_PRIVATE`. While app lock is on, no text names people, amounts or descriptions. Without the permission nothing is shown and posting works the same; while the permission is missing or a channel is blocked nothing is recorded as shown, so everything due is drawn again once it can be. Accepted limit (review round 7): a switch turned off and on again between two passes goes unseen, so a due reminder Android removed meanwhile stays missing until its alert key changes; a block-state receiver was rejected (exported and spoofable, untested, unverified on Android 13+), so don't add one. A due notification is redrawn silently when only what it shows changes (description, amount, count, app lock), since the record keeps a digest of the drawn text next to the alert key.
- **Permissions:** the real flavor's manifest adds `POST_NOTIFICATIONS`; the UI asks for it (the friend's work, `docs/m2-ui-contract-changes.md`).
- No `ui/` file changes.

## File map

| File | Change |
|---|---|
| `main/.../data/Rows.kt`, `RowMapping.kt`, `Migrations.kt`, `IouDatabase.kt`, `RoomJournal.kt`, `LedgerRows.kt`, `SnapshotCodec.kt`, `BackupFormat.kt` | Schedule rows, expense links, schema 2 tables, snapshots, backups |
| `main/.../store/RecurringRunner.kt`, `RecurringNotices.kt` (new) | The runner, its failures, the notices port and `PostedOccurrence` |
| `main/.../api/RecurringApi.kt` (new), `api/Apis.kt`, `di/ApiModule.kt` | The interface and its wiring |
| `main/.../store/StoreRecurringApi.kt` (new) | `RecurringApi` for both flavors |
| `main/.../fake/FakeApis.kt`, `fake/SeedData.kt`, `fake/.../di/BackendModule.kt`, `real/.../di/BackendModule.kt`, `main/.../data/RealBackend.kt` | Runner and api in both flavors, seed schedules |
| `main/.../store/RecurringNoticePlanner.kt`, `main/.../data/RecurringNotifiedFile.kt`, `main/.../data/AndroidRecurringNotices.kt`, `main/.../RecurringTriggers.kt`, `main/.../IouApplication.kt` (new or changed) | Notifications and foreground triggers |
| `real/.../data/RecurringPostWorker.kt` (new), `real/AndroidManifest.xml` | The background worker and the permission |
| `test/...` | `RecurringRunnerTest`, `RecurringApiContract` (+ fake and real subclasses), `RealRecurringTest`, `RecurringNoticePlannerTest`, `RecurringNotifiedFileTest` (new); `RowMappingTest`, `MigrationTest`, `RoomJournalTest`, `BackupFormatTest`, `SeedDataTest` |
| `docs/ui-contributor-guide.md`, `docs/m2-ui-contract-changes.md`, `docs/manual-test-checklist.md` | `RecurringApi`, notifications, device checks |

Paths abbreviate `android/app/src/main/kotlin/io/github/codex07/iou/` as `main/...`, the real source set's `android/app/src/real/kotlin/io/github/codex07/iou/` as `real/...`, the fake one's as `fake/.../`, and the test root likewise. Commands run from `android/` with `JAVA_HOME` set to the MS JDK 21.

---

### Task 1: Schedules and expense links in Room, snapshots and backups

**Files:**
- Modify: `main/.../data/Rows.kt`, `main/.../data/RowMapping.kt`, `main/.../data/Migrations.kt`, `main/.../data/IouDatabase.kt`, `main/.../data/RoomJournal.kt`, `main/.../data/LedgerRows.kt`, `main/.../data/SnapshotCodec.kt`, `main/.../data/BackupFormat.kt`
- Modify (tests): `test/.../data/RowMappingTest.kt`, `test/.../data/MigrationTest.kt`, `test/.../data/RoomJournalTest.kt`, `test/.../data/BackupFormatTest.kt`
- Generated: `android/app/schemas/io.github.codex07.iou.data.IouDatabase/2.json` (commit it)

**Interfaces:**
- Consumes (Parts 1, 3): `Migrations.MIGRATION_1_2` / `expense1To2`, `PayerRow`, `LedgerRows.payers`, `RecurringExpense`, `RecurringParticipant`, `Expense.recurringId` / `recurringOn`, `RecurringOps.save` / `post`.
- Produces: `RecurringExpenseRow`, `RecurringParticipantRow`; `ExpenseRow.recurringId` / `recurringOn`; `RecurringExpense.toRow()`, `RecurringExpense.participantRows()`, `RecurringExpenseRow.toSchedule(participants)`; `LedgerDao.upsertSchedules` / `deleteParticipants` / `insertParticipants` / `schedules()` / `participants()`; `LedgerRows.schedules`, `LedgerRows.participants`; `SnapshotCodec` encodes and decodes schedules (Part 3's temporary refusal goes).

- [ ] **Step 1: Write the failing tests**

In `test/.../data/RowMappingTest.kt`, add the imports `io.github.codex07.iou.ledger.model.ParticipantRole`, `io.github.codex07.iou.ledger.model.RecurrenceUnit`, `io.github.codex07.iou.ledger.model.RecurringExpense`, `io.github.codex07.iou.ledger.model.RecurringId`, `io.github.codex07.iou.ledger.model.RecurringParticipant`, `io.github.codex07.iou.testutil.D0` and `io.github.codex07.iou.testutil.testMeta` (skip any already there), and add before the last closing brace:

```kotlin

    @Test
    fun `a schedule maps to its rows and back with each role's order kept, and so does an expense's link`() {
        val schedule = RecurringExpense(
            id = RecurringId("r-1"), groupId = GroupId("g-1"), description = "Rent", category = Category.RENT, note = "Flat",
            total = Money(400, JPY), payerSplit = PayerSplit.EXACT, splitType = SplitType.SHARES,
            participants = listOf(
                RecurringParticipant(ParticipantRole.PAYER, PersonId("p-2"), 300),
                RecurringParticipant(ParticipantRole.PAYER, PersonId("p-1"), 100),
                RecurringParticipant(ParticipantRole.SHARE, PersonId("p-1"), 2),
                RecurringParticipant(ParticipantRole.SHARE, ME, 1),
            ),
            unit = RecurrenceUnit.WEEK, interval = 2, startOn = D0, endOn = D0.plusDays(70), handledThrough = D0.plusDays(14),
            autoPostFrom = D0, autoPost = true, meta = testMeta(),
        )

        schedule.participantRows().map { it.role to it.position } shouldBe listOf("PAYER" to 0, "PAYER" to 1, "SHARE" to 0, "SHARE" to 1)
        schedule.toRow().toSchedule(schedule.participantRows().reversed()) shouldBe schedule
        SnapshotCodec.decode(SnapshotCodec.encode(schedule)) shouldBe schedule

        val posted = expense.copy(recurringId = RecurringId("r-1"), recurringOn = D0)
        posted.toRow().toExpense(posted.shareRows(), posted.payerRows()) shouldBe posted
        SnapshotCodec.decode(SnapshotCodec.encode(posted)) shouldBe posted
    }
```

In `test/.../data/MigrationTest.kt`, a version-1 expense row has no link columns. Replace (Part 1's line):

```kotlin
        return JsonObject(row - "payerSplit" + ("payerPersonId" to JsonPrimitive(payer)))
```

with:

```kotlin
        return JsonObject(row - "payerSplit" - "recurringId" - "recurringOn" + ("payerPersonId" to JsonPrimitive(payer)))
```

In `test/.../data/RoomJournalTest.kt`, add the imports `io.github.codex07.iou.api.RecurringDraft`, `io.github.codex07.iou.api.ScheduleSaveMode`, `io.github.codex07.iou.ledger.model.RecurrenceUnit` and `io.github.codex07.iou.ledger.ops.RecurringOps` (skip any already there), and add before the last closing brace:

```kotlin

    @Test
    fun `a schedule and its posted expense load back, and a template edit replaces the participant rows`() = runTest {
        val store = store()
        store.write(beforeSetup = true) { PeopleOps.setup(it, "Me") }
        val x = (store.write { PeopleOps.createPerson(it, PersonDraft("X"), it.generation) } as LedgerResult.Ok).value
        val me = store.loaded().state.me.id
        val draft = RecurringDraft(
            payers = listOf(SplitInput(me)), description = "Rent", total = eur(90_000),
            participants = listOf(SplitInput(me), SplitInput(x)), unit = RecurrenceUnit.MONTH, startOn = D0,
        )
        val id = (store.write { RecurringOps.save(it, draft, ScheduleSaveMode.Create(it.generation)) } as LedgerResult.Ok).value
        val rent = ExpenseDraft(me, "Rent", total = eur(90_000), participants = listOf(SplitInput(me), SplitInput(x)), occurredOn = D0)
        (store.write { RecurringOps.post(it, id, D0, rent, EditToken(it.generation, 1)) } is LedgerResult.Ok) shouldBe true
        store.write { RecurringOps.save(it, draft.copy(payers = listOf(SplitInput(x))), ScheduleSaveMode.Update(id, EditToken(it.generation, 2))) }

        val loaded = LedgerLoader.load(db)
        loaded shouldBe store.loaded().content
        loaded.state.schedules.getValue(id).payerRows.single().personId shouldBe x
        loaded.state.expenses.values.single().recurringOn shouldBe D0
    }
```

In `test/.../data/BackupFormatTest.kt` (package `data`, so `RecurringParticipantRow` needs no import), add before the last closing brace:

```kotlin

    @Test
    fun `a participant row of a missing schedule is rejected`() {
        val orphan = rows.copy(participants = rows.participants + RecurringParticipantRow("gone", "SHARE", "p-1", 0, null))

        format.read(format.write(orphan, "0.1.0", T0, null), null).error()::class shouldBe LedgerError.CorruptFile::class
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.data.RowMappingTest" --tests "io.github.codex07.iou.data.RoomJournalTest" --tests "io.github.codex07.iou.data.BackupFormatTest"`
Expected: FAIL, compilation errors `Unresolved reference 'participantRows'`, `'toSchedule'`, `'RecurringParticipantRow'`, `'participants'` on `LedgerRows`.

- [ ] **Step 3: Add the rows and their mapping**

In `main/.../data/Rows.kt`, give `ExpenseRow` its link. In `data class ExpenseRow`, replace its last parameter line:

```kotlin
    val importBatchId: String?,
)

/** Owned by its expense (spec §3.2); [position] keeps the expense's share order. */
```

with:

```kotlin
    val importBatchId: String?,
    val recurringId: String? = null,
    val recurringOn: String? = null,
)

/** Owned by its expense (spec §3.2); [position] keeps the expense's share order. */
```

and append at the end of the file:

```kotlin

/** A schedule (spec §3.2, §5.7); its template rows are [RecurringParticipantRow]s. */
@Serializable
@Entity(tableName = "recurring_expense")
data class RecurringExpenseRow(
    @PrimaryKey val id: String,
    val groupId: String?,
    val description: String,
    val category: String?,
    val note: String?,
    val totalMinor: Long,
    val currency: String,
    val payerSplit: String,
    val splitType: String,
    val unit: String,
    val interval: Int,
    val startOn: String,
    val endOn: String?,
    val handledThrough: String?,
    val autoPostFrom: String,
    val autoPost: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val authorId: String,
    val deletedAt: String?,
    val version: Int,
    val importBatchId: String?,
)

/** Owned by its schedule like [ShareRow]; [position] keeps the order within its [role] (`PAYER` or `SHARE`). */
@Serializable
@Entity(tableName = "recurring_participant", primaryKeys = ["recurringId", "role", "personId"])
data class RecurringParticipantRow(
    val recurringId: String,
    val role: String,
    val personId: String,
    val position: Int,
    val inputValue: Long?,
)
```

In `main/.../data/RowMapping.kt`, add the imports `io.github.codex07.iou.ledger.model.ParticipantRole`, `io.github.codex07.iou.ledger.model.RecurrenceUnit`, `io.github.codex07.iou.ledger.model.RecurringExpense`, `io.github.codex07.iou.ledger.model.RecurringId` and `io.github.codex07.iou.ledger.model.RecurringParticipant`. In `Expense.toRow()`, replace:

```kotlin
        it.createdAt, it.updatedAt, it.authorId, it.deletedAt, it.version, it.importBatchId)
}

fun Expense.shareRows(): List<ShareRow> =
```

with:

```kotlin
        it.createdAt, it.updatedAt, it.authorId, it.deletedAt, it.version, it.importBatchId,
        recurringId?.value, recurringOn?.text())
}

fun Expense.shareRows(): List<ShareRow> =
```

in `ExpenseRow.toExpense`, replace:

```kotlin
    shares = shares.sortedBy { it.position }.map { ExpenseShare(PersonId(it.personId), it.shareMinor, it.inputValue) },
    occurredOn = occurredOn.date(), note = note, meta = Meta(createdAt, updatedAt, authorId, deletedAt, version, importBatchId).toMeta(),
)
```

with:

```kotlin
    shares = shares.sortedBy { it.position }.map { ExpenseShare(PersonId(it.personId), it.shareMinor, it.inputValue) },
    occurredOn = occurredOn.date(), note = note, meta = Meta(createdAt, updatedAt, authorId, deletedAt, version, importBatchId).toMeta(),
    recurringId = recurringId?.let(::RecurringId), recurringOn = recurringOn?.date(),
)
```

and add after `ExpenseRow.toExpense`:

```kotlin

fun RecurringExpense.toRow(): RecurringExpenseRow = meta.columns().let {
    RecurringExpenseRow(id.value, groupId?.value, description, category?.name, note, total.amountMinor, total.currency.code,
        payerSplit.name, splitType.name, unit.name, interval, startOn.text(), endOn?.text(), handledThrough?.text(),
        autoPostFrom.text(), autoPost, it.createdAt, it.updatedAt, it.authorId, it.deletedAt, it.version, it.importBatchId)
}

/** The template rows; [RecurringParticipantRow.position] counts within each role. */
fun RecurringExpense.participantRows(): List<RecurringParticipantRow> =
    listOf(payerRows, shareRows).flatMap { rows ->
        rows.mapIndexed { position, row -> RecurringParticipantRow(id.value, row.role.name, row.personId.value, position, row.inputValue) }
    }

/** [participants] are this schedule's template rows, in any order; payers come first, each role in position order. */
fun RecurringExpenseRow.toSchedule(participants: List<RecurringParticipantRow>) = RecurringExpense(
    id = RecurringId(id), groupId = groupId?.let(::GroupId), description = description, category = category?.let(Category::valueOf),
    note = note, total = Money(totalMinor, CurrencyCode(currency)), payerSplit = PayerSplit.valueOf(payerSplit),
    splitType = SplitType.valueOf(splitType),
    participants = participants
        .sortedWith(compareBy({ ParticipantRole.valueOf(it.role) }, { it.position }))
        .map { RecurringParticipant(ParticipantRole.valueOf(it.role), PersonId(it.personId), it.inputValue) },
    unit = RecurrenceUnit.valueOf(unit), interval = interval, startOn = startOn.date(), endOn = endOn?.date(),
    handledThrough = handledThrough?.date(), autoPostFrom = autoPostFrom.date(), autoPost = autoPost,
    meta = Meta(createdAt, updatedAt, authorId, deletedAt, version, importBatchId).toMeta(),
)
```

`ParticipantRole.PAYER` is declared before `SHARE`, so sorting by role puts payers first.

- [ ] **Step 4: Grow the migration, the DAO and the journal**

In `main/.../data/Migrations.kt`, give the rebuilt `expense` its link columns. In `MIGRATION_1_2`, replace (Part 1's text):

```kotlin
                    "`importBatchId` TEXT, PRIMARY KEY(`id`))",
            )
```

with:

```kotlin
                    "`importBatchId` TEXT, `recurringId` TEXT, `recurringOn` TEXT, PRIMARY KEY(`id`))",
            )
```

The `INSERT INTO expense_new` lists only the old columns, so both new ones start as NULL. Then replace:

```kotlin
            connection.execSQL("ALTER TABLE `expense_new` RENAME TO `expense`")
        }
```

with:

```kotlin
            connection.execSQL("ALTER TABLE `expense_new` RENAME TO `expense`")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `recurring_expense` (`id` TEXT NOT NULL, `groupId` TEXT, `description` TEXT NOT NULL, " +
                    "`category` TEXT, `note` TEXT, `totalMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, " +
                    "`payerSplit` TEXT NOT NULL, `splitType` TEXT NOT NULL, `unit` TEXT NOT NULL, `interval` INTEGER NOT NULL, " +
                    "`startOn` TEXT NOT NULL, `endOn` TEXT, `handledThrough` TEXT, `autoPostFrom` TEXT NOT NULL, " +
                    "`autoPost` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, `authorId` TEXT NOT NULL, " +
                    "`deletedAt` TEXT, `version` INTEGER NOT NULL, `importBatchId` TEXT, PRIMARY KEY(`id`))",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `recurring_participant` (`recurringId` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                    "`personId` TEXT NOT NULL, `position` INTEGER NOT NULL, `inputValue` INTEGER, " +
                    "PRIMARY KEY(`recurringId`, `role`, `personId`))",
            )
        }
```

and in `expense1To2`, replace:

```kotlin
        return JsonObject(expense - "payerPersonId" + ("payerSplit" to JsonPrimitive("EQUAL"))) to payer
```

with:

```kotlin
        val row = expense - "payerPersonId" +
            mapOf("payerSplit" to JsonPrimitive("EQUAL"), "recurringId" to JsonNull, "recurringOn" to JsonNull)
        return JsonObject(row) to payer
```

Update the KDoc of `MIGRATION_1_2` to say it also creates the two recurring tables and adds the expense link columns (spec §3.2).

In `main/.../data/IouDatabase.kt`, add to `LedgerDao` after Part 1's `@Insert suspend fun insertPayers(rows: List<PayerRow>)`:

```kotlin
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSchedules(rows: List<RecurringExpenseRow>)
    @Query("DELETE FROM recurring_participant WHERE recurringId = :recurringId") suspend fun deleteParticipants(recurringId: String)
    @Insert suspend fun insertParticipants(rows: List<RecurringParticipantRow>)
```

after Part 1's `@Query("SELECT * FROM expense_payer ORDER BY expenseId, position") suspend fun payers(): List<PayerRow>`:

```kotlin
    @Query("SELECT * FROM recurring_expense") suspend fun schedules(): List<RecurringExpenseRow>
    @Query("SELECT * FROM recurring_participant ORDER BY recurringId, role, position")
    suspend fun participants(): List<RecurringParticipantRow>
```

and replace (Part 1's entity list):

```kotlin
        ShareRow::class, PayerRow::class, FxSnapshotRow::class, RevisionRow::class, ImportBatchRow::class, AppSettingRow::class,
        FxRateRow::class,
    ],
```

with:

```kotlin
        ShareRow::class, PayerRow::class, FxSnapshotRow::class, RevisionRow::class, ImportBatchRow::class, AppSettingRow::class,
        FxRateRow::class, RecurringExpenseRow::class, RecurringParticipantRow::class,
    ],
```

In `main/.../data/RoomJournal.kt`, add the import `io.github.codex07.iou.ledger.model.RecurringExpense` and replace:

```kotlin
            dao.insertRevisions(entry.revisions.map { it.toRow() })
```

with:

```kotlin
            val schedules = entities.filterIsInstance<RecurringExpense>()
            dao.upsertSchedules(schedules.map { it.toRow() })
            for (schedule in schedules) {
                dao.deleteParticipants(schedule.id.value)
                dao.insertParticipants(schedule.participantRows())
            }
            dao.insertRevisions(entry.revisions.map { it.toRow() })
```

- [ ] **Step 5: Carry schedules in `LedgerRows`, snapshots and backups**

In `main/.../data/LedgerRows.kt`, add the import `io.github.codex07.iou.ledger.model.RecurringId`, then replace (Part 1's fields):

```kotlin
    val payers: List<PayerRow> = emptyList(),
    val fxSnapshots: List<FxSnapshotRow> = emptyList(),
```

with:

```kotlin
    val payers: List<PayerRow> = emptyList(),
    val schedules: List<RecurringExpenseRow> = emptyList(),
    val participants: List<RecurringParticipantRow> = emptyList(),
    val fxSnapshots: List<FxSnapshotRow> = emptyList(),
```

replace:

```kotlin
        val payersByExpense = payers.groupBy { it.expenseId }
```

with:

```kotlin
        val payersByExpense = payers.groupBy { it.expenseId }
        val participantsBySchedule = participants.groupBy { it.recurringId }
```

replace:

```kotlin
            fxSnapshots = fxSnapshots.associate { FxSnapshotId(it.id) to it.toSnapshot() },
        )
```

with:

```kotlin
            fxSnapshots = fxSnapshots.associate { FxSnapshotId(it.id) to it.toSnapshot() },
            schedules = schedules.associate { RecurringId(it.id) to it.toSchedule(participantsBySchedule[it.id].orEmpty()) },
        )
```

replace:

```kotlin
        unique("expense_payer", payers) { it.expenseId to it.personId }
```

with:

```kotlin
        unique("expense_payer", payers) { it.expenseId to it.personId }
        unique("recurring_expense", schedules) { it.id }
        unique("recurring_participant", participants) { Triple(it.recurringId, it.role, it.personId) }
```

replace:

```kotlin
        dao.insertPayers(payers)
```

with:

```kotlin
        dao.insertPayers(payers)
        dao.upsertSchedules(schedules)
        dao.insertParticipants(participants)
```

replace:

```kotlin
            payers = dao.payers(),
```

with:

```kotlin
            payers = dao.payers(),
            schedules = dao.schedules(),
            participants = dao.participants(),
```

and replace:

```kotlin
                payers = state.expenses.values.flatMap { it.payerRows() },
```

with:

```kotlin
                payers = state.expenses.values.flatMap { it.payerRows() },
                schedules = state.schedules.values.map { it.toRow() },
                participants = state.schedules.values.flatMap { it.participantRows() },
```

In `main/.../data/SnapshotCodec.kt`, replace (Part 1's field):

```kotlin
        val payers: List<PayerRow>? = null,
    )
```

with:

```kotlin
        val payers: List<PayerRow>? = null,
        val schedule: RecurringExpenseRow? = null,
        val participants: List<RecurringParticipantRow>? = null,
    )
```

replace Part 3's temporary branch:

```kotlin
            // Persisted from M2 Part 4; until then no schedule can reach the real flavor's journal.
            is RecurringExpense -> throw UnsupportedOperationException("Schedule snapshots are stored from M2 Part 4")
```

with:

```kotlin
            is RecurringExpense -> Snapshot(CURRENT_SCHEMA, schedule = entity.toRow(), participants = entity.participantRows())
```

and after the line (Part 1's)

```kotlin
            ?: snapshot.expense?.toExpense(snapshot.shares.orEmpty(), snapshot.payers.orEmpty())
```

add:

```kotlin
            ?: snapshot.schedule?.toSchedule(snapshot.participants.orEmpty())
```

The schema-1 upcaster goes through `Migrations.expense1To2`, which now also writes the null link fields; schema-1 snapshots never held a schedule.

In `main/.../data/BackupFormat.kt`, replace (Part 1's check):

```kotlin
            require(document.rows.payers.all { it.expenseId in expenseIds }) { "Payer references a missing expense" }
```

with:

```kotlin
            require(document.rows.payers.all { it.expenseId in expenseIds }) { "Payer references a missing expense" }
            val scheduleIds = document.rows.schedules.map { it.id }.toSet()
            require(document.rows.participants.all { it.recurringId in scheduleIds }) { "Participant references a missing schedule" }
```

A version-1 backup has neither list, so both stay empty when it is read.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.data.*"`
Expected: PASS. `MigrationTest` opening the migrated database proves the new SQL matches the generated schema 2 (Room throws `Migration didn't properly handle: recurring_expense` otherwise). Check that `android/app/schemas/io.github.codex07.iou.data.IouDatabase/2.json` now lists `recurring_expense`, `recurring_participant` and the two `expense` columns.

Then: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add android/app/src android/app/schemas
git commit -m "Store schedules and posted-expense links in Room schema 2, snapshots and backups"
```

---

### Task 2: The runner

**Files:**
- Create: `main/.../store/RecurringNotices.kt`, `main/.../store/RecurringRunner.kt`, `test/.../store/RecurringRunnerTest.kt`

**Interfaces:**
- Consumes: `Recurrence`, `RecurringOps.post` / `save` / `skip`, `LedgerViews.occurrenceDraft` / `dueOccurrences` (Part 3); `EntryOps.requiredEffectCurrency`; `QuoteSource`; `LedgerStore.currentViews` / `write` / `today` / `snapshot` / `ready`; `StoreGroupsApi.setGroupArchived` (a test).
- Produces: `data class PostedOccurrence(schedule: RecurringId, description: String, occurrence: LocalDate, amount: Money)`; `fun interface RecurringNotices { suspend fun afterRun(due: List<DueOccurrence>, posted: List<PostedOccurrence>); val redraw: Flow<Unit> (default empty) }` with `RecurringNotices.None`; `class RecurringRunner(store, quotes, notices = None)` with `run(): List<PostedOccurrence>`, `request()`, `redraw()`, `start(scope): Job`, `today: StateFlow<LocalDate>`, `errors: StateFlow<Map<RecurringId, RecurringRunner.Failure>>`, `RecurringRunner.Failure(occurrence, error)`, `RecurringRunner.withErrors(due, failures)`.

- [ ] **Step 1: Write the failing tests**

Create `test/.../store/RecurringRunnerTest.kt`:

```kotlin
package io.github.codex07.iou.store

import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.FxQuote
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.ledger.ops.RecurringOps
import io.github.codex07.iou.ledger.views.dueOccurrences
import io.github.codex07.iou.ledger.views.occurrenceDraft
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.sek
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Auto-posting and its triggers (spec §5.7, AC 29–32). */
@OptIn(ExperimentalCoroutinesApi::class)
class RecurringRunnerTest {

    private fun d(text: String) = LocalDate.parse(text)

    private fun store(state: LedgerState, today: String) = LedgerStore(
        Clock.fixed(Instant.parse("${today}T10:00:00Z"), ZoneOffset.UTC), SequentialIds("run"), DeviceId("device-test"),
        initial = LedgerContent(state),
    )

    private val noRates = QuoteSource { _, from, to -> LedgerResult.Err(LedgerError.RateRequired(from, to)) }

    @Test
    fun `due occurrences of an auto-post schedule are posted oldest first, and only once`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(1_000), listOf(ME, x), unit = RecurrenceUnit.WEEK, autoPost = true)
        val store = store(ledger.state, "2026-01-15")
        val runner = RecurringRunner(store, noRates)

        runner.run().map { it.occurrence } shouldBe listOf(d("2026-01-01"), d("2026-01-08"), d("2026-01-15"))
        store.loaded().state.expenses.values.mapNotNull { it.recurringOn }.sorted() shouldBe
            listOf(d("2026-01-01"), d("2026-01-08"), d("2026-01-15"))
        runner.run() shouldBe emptyList()
        store.loaded().state.schedules.getValue(id).handledThrough shouldBe d("2026-01-15")
    }

    @Test
    fun `a manual post racing the runner leaves exactly one expense for the occurrence`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val id = ledger.schedule(ME, eur(1_000), listOf(ME, x), autoPost = true)
        val store = store(ledger.state, "2026-01-01")
        val prepared = (store.currentViews().occurrenceDraft(id) as LedgerResult.Ok).value

        RecurringRunner(store, noRates).run().size shouldBe 1

        store.write { RecurringOps.post(it, id, prepared.occurrence, prepared.draft, prepared.token) } shouldBe
            LedgerResult.Err(LedgerError.StaleVersion(id.value, 1, 2))
        store.loaded().state.expenses.values.count { it.recurringOn == D0 } shouldBe 1
    }

    @Test
    fun `a converted occurrence posts at its date's rate, and without one it stays due while others post`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", currency = EUR, fxMode = FxMode.CONVERT, members = listOf(ME, x))
        val sekRent = ledger.schedule(ME, sek(100_000), listOf(ME, x), group = flat, autoPost = true)
        val eurRent = ledger.schedule(ME, eur(1_000), listOf(ME, x), autoPost = true)
        val store = store(ledger.state, "2026-01-01")

        val offline = RecurringRunner(store, noRates)
        offline.run().map { it.schedule } shouldBe listOf(eurRent)
        offline.errors.value shouldBe mapOf(sekRent to RecurringRunner.Failure(D0, LedgerError.RateRequired(SEK, EUR)))
        RecurringRunner.withErrors(store.currentViews().dueOccurrences(D0), offline.errors.value).single().autoPostError shouldBe
            LedgerError.RateRequired(SEK, EUR)

        val asked = mutableListOf<LocalDate>()
        val online = RecurringRunner(
            store,
            QuoteSource { date, from, to ->
                asked += date
                LedgerResult.Ok(FxQuote(from, to, "0.08765", date, FxSource.ECB_FRANKFURTER, isStale = true))
            },
        )
        online.run().map { it.schedule } shouldBe listOf(sekRent)
        asked shouldBe listOf(D0)
        store.loaded().state.expenses.values.single { it.recurringId == sekRent }.effectTotal shouldBe eur(8_765)
        online.errors.value shouldBe emptyMap()
    }

    @Test
    fun `a schedule whose participant left the group stays due with MemberRemoved, and held schedules are skipped`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", members = listOf(ME))
        ledger.member(flat, x, removed = true)
        val left = ledger.schedule(ME, eur(1_000), listOf(ME, x), group = flat, autoPost = true)
        val trip = ledger.group("Trip", members = listOf(ME, x), archived = true)
        ledger.schedule(ME, eur(1_000), listOf(ME, x), group = trip, autoPost = true)
        val runner = RecurringRunner(store(ledger.state, "2026-01-01"), noRates)

        runner.run() shouldBe emptyList()
        runner.errors.value.keys shouldBe setOf(left)
        runner.errors.value.getValue(left).error shouldBe LedgerError.MemberRemoved(flat, setOf(x))
    }

    @Test
    fun `a backlog blocks auto-post until it is handled, then the runner posts today's date`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val store = store(ledger.state, "2026-03-01")
        val runner = RecurringRunner(store, noRates)
        val draft = RecurringDraft(
            payers = listOf(SplitInput(ME)), description = "Rent", total = eur(90_000),
            participants = listOf(SplitInput(ME), SplitInput(x)), unit = RecurrenceUnit.MONTH, startOn = d("2025-12-01"), autoPost = true,
        )
        val id = (store.write { RecurringOps.save(it, draft, ScheduleSaveMode.Create(it.generation)) } as LedgerResult.Ok).value

        runner.run() shouldBe emptyList()
        store.currentViews().dueOccurrences(d("2026-03-01")).map { it.date to it.confirmToResume } shouldBe listOf(
            d("2025-12-01") to true, d("2026-01-01") to true, d("2026-02-01") to true, d("2026-03-01") to true,
        )

        store.write { RecurringOps.skip(it, id, d("2026-02-01"), EditToken(it.generation, 1)) } shouldBe LedgerResult.Ok(Unit)
        runner.run().map { it.occurrence } shouldBe listOf(d("2026-03-01"))
    }

    @Test
    fun `a write outside RecurringApi that changes the due list asks for a pass, which reports the new list`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", members = listOf(ME, x))
        val rent = ledger.schedule(ME, eur(90_000), listOf(ME, x), group = flat)
        val store = store(ledger.state, "2026-01-01")
        val reported = mutableListOf<List<RecurringId>>()
        val runner = RecurringRunner(store, noRates, RecurringNotices { due, _ -> reported += due.map { it.schedule } })
        runner.start(backgroundScope)
        runCurrent()
        reported.last() shouldBe listOf(rent)

        StoreGroupsApi(store).setGroupArchived(flat, true, EditToken(store.loaded().generation, 1)) shouldBe LedgerResult.Ok(Unit)
        runCurrent()
        reported.last() shouldBe emptyList()
    }

    @Test
    fun `a schedule put on hold while its rate is fetched is not posted`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", currency = EUR, fxMode = FxMode.CONVERT, members = listOf(ME, x))
        val rent = ledger.schedule(ME, sek(100_000), listOf(ME, x), group = flat, autoPost = true)
        val store = store(ledger.state, "2026-01-01")
        val runner = RecurringRunner(
            store,
            QuoteSource { date, from, to ->
                // The user archives the group while the request is out.
                StoreGroupsApi(store).setGroupArchived(flat, true, EditToken(store.loaded().generation, 1)) shouldBe LedgerResult.Ok(Unit)
                LedgerResult.Ok(FxQuote(from, to, "0.08765", date, FxSource.ECB_FRANKFURTER, isStale = true))
            },
        )

        runner.run() shouldBe emptyList()
        store.loaded().state.expenses.values.none { it.recurringId == rent } shouldBe true
        runner.errors.value shouldBe emptyMap()
    }

    @Test
    fun `a redraw signal reports the due list at once, while a pass still waits for a rate`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        val flat = ledger.group("Flat", currency = EUR, fxMode = FxMode.CONVERT, members = listOf(ME, x))
        val rent = ledger.schedule(ME, sek(100_000), listOf(ME, x), group = flat, autoPost = true)
        val store = store(ledger.state, "2026-01-01")
        val network = CompletableDeferred<Unit>()
        val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val reports = mutableListOf<List<RecurringId>>()
        val notices = object : RecurringNotices {
            override suspend fun afterRun(due: List<DueOccurrence>, posted: List<PostedOccurrence>) {
                reports += due.map { it.schedule }
            }

            override val redraw: Flow<Unit> = signals
        }
        val runner = RecurringRunner(
            store,
            QuoteSource { _, from, to ->
                network.await()
                LedgerResult.Err(LedgerError.RateRequired(from, to))
            },
            notices,
        )
        runner.start(backgroundScope)
        runCurrent()
        reports shouldBe emptyList()

        signals.tryEmit(Unit)
        runCurrent()
        reports shouldBe listOf(listOf(rent))

        network.complete(Unit)
        runCurrent()
        runner.errors.value.keys shouldBe setOf(rent)
    }

    @Test
    fun `a ledger without me, as after undoing a restore onto a fresh install, reports an empty due list`() = runTest {
        val reported = mutableListOf<Pair<List<DueOccurrence>, List<PostedOccurrence>>>()
        val runner = RecurringRunner(store(LedgerState(), "2026-01-01"), noRates, RecurringNotices { due, posted -> reported += due to posted })

        runner.run() shouldBe emptyList()
        runner.redraw()

        reported shouldBe List(2) { emptyList<DueOccurrence>() to emptyList<PostedOccurrence>() }
    }

    @Test
    fun `every pass moves today`() = runTest {
        val clock = MovingClock(Instant.parse("2026-11-30T10:00:00Z"))
        val store = LedgerStore(clock, SequentialIds("run"), DeviceId("device-test"), initial = LedgerContent(TestLedger().apply { me() }.state))
        val runner = RecurringRunner(store, noRates)
        runner.today.value shouldBe d("2026-11-30")

        clock.at = Instant.parse("2026-12-01T10:00:00Z")
        runner.run()
        runner.today.value shouldBe d("2026-12-01")
    }

    /** A clock the test moves (UTC). */
    private class MovingClock(var at: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = at
    }
}
```

The runner and `LedgerStore` without a journal never leave the test dispatcher, so `runCurrent()` runs every requested pass to the end, or to where a test's quote source waits.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.store.RecurringRunnerTest"`
Expected: FAIL, compilation error `Unresolved reference 'RecurringRunner'`.

- [ ] **Step 3: Add the notices port**

Create `main/.../store/RecurringNotices.kt`:

```kotlin
package io.github.codex07.iou.store

import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.money.Money
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** One occurrence the runner posted: what a "posted" notification lists (spec §5.7). */
data class PostedOccurrence(
    val schedule: RecurringId,
    val description: String,
    val occurrence: LocalDate,
    /** The posted amount, as paid. */
    val amount: Money,
)

/**
 * Where the runner reports after every pass (spec §5.7 Notifications): [due] is the whole due list with failures
 * attached, [posted] what this pass posted. The real flavor shows Android notifications; the fake shows none.
 */
fun interface RecurringNotices {
    suspend fun afterRun(due: List<DueOccurrence>, posted: List<PostedOccurrence>)

    /**
     * Emits when what is drawn must change although the ledger did not (app lock switched on or off); the runner
     * reports again at once on each emission ([RecurringRunner.redraw]), without a pass.
     */
    val redraw: Flow<Unit> get() = emptyFlow()

    companion object {
        val None = RecurringNotices { _, _ -> }
    }
}
```

- [ ] **Step 4: Write the runner**

Create `main/.../store/RecurringRunner.kt`:

```kotlin
package io.github.codex07.iou.store

import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.FxChoice
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.OccurrenceDraft
import io.github.codex07.iou.api.map
import io.github.codex07.iou.ledger.engine.Recurrence
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.ops.EntryOps
import io.github.codex07.iou.ledger.ops.Planned
import io.github.codex07.iou.ledger.ops.RecurringOps
import io.github.codex07.iou.ledger.ops.WritePlan
import io.github.codex07.iou.ledger.views.LedgerViews
import io.github.codex07.iou.ledger.views.dueOccurrences
import io.github.codex07.iou.ledger.views.occurrenceDraft
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Auto-posting (spec §5.7), the same in both flavors (index, locked decision 8). A pass posts the due occurrences of
 * every schedule that auto-posts, by schedule ID and oldest first, through `RecurringOps.post` under the store's write
 * lock, but only while the next occurrence is on or after the schedule's `autoPostFrom`: a backlog waits for the
 * user. A failure stops that schedule at that occurrence and is kept in [errors] (memory only) until a later pass
 * gets past it; the other schedules still run. Every pass ends by reporting to [notices].
 */
class RecurringRunner(
    private val store: LedgerStore,
    private val quotes: QuoteSource,
    private val notices: RecurringNotices = RecurringNotices.None,
) {
    /** Where a schedule's last pass stopped, and why: the due list's `autoPostError` for that occurrence. */
    data class Failure(val occurrence: LocalDate, val error: LedgerError)

    private val pass = Mutex()
    private val report = Mutex()
    private val failures = MutableStateFlow<Map<RecurringId, Failure>>(emptyMap())
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val day = MutableStateFlow(store.today())

    val errors: StateFlow<Map<RecurringId, Failure>> = failures.asStateFlow()

    /**
     * The day the last pass ran for (the store's today until the first pass). Date-relative reads follow it, so a
     * pass on a new day refreshes the due list and schedule read models even when it writes nothing.
     */
    val today: StateFlow<LocalDate> = day.asStateFlow()

    /**
     * One pass; waits for the start-up load. Returns what it posted. Passes never overlap. A ledger without me (before
     * setup, or after undoing a restore onto a fresh install) posts nothing but still reports an empty due list, so
     * notifications of a discarded ledger go away (spec §5.7).
     */
    suspend fun run(): List<PostedOccurrence> = pass.withLock {
        val start = store.currentViews()
        if (start.state.meOrNull == null) {
            failures.value = emptyMap()
            report.withLock { notices.afterRun(emptyList(), emptyList()) }
            return@withLock emptyList()
        }
        val today = store.today()
        day.value = today
        val posted = mutableListOf<PostedOccurrence>()
        val failed = LinkedHashMap<RecurringId, Failure>()
        val ids = start.state.schedules.values
            .filter { Recurrence.autoPosts(it, start.state, start.resolver) }
            .map { it.id }
            .sortedBy { it.value }
        for (id in ids) runSchedule(id, today, posted)?.let { failed[id] = it }
        failures.value = failed
        report.withLock { notices.afterRun(withErrors(store.currentViews().dueOccurrences(today), failed), posted) }
        posted
    }

    /**
     * Reports the current due list, with the last failures, at once: no posting, and no waiting for a pass, which can
     * sit on rate requests. Used for [RecurringNotices.redraw], so an app-lock switch redacts within moments (spec
     * §5.7). Reports never overlap; a pass already running reports again when it ends.
     */
    suspend fun redraw() {
        report.withLock {
            val views = store.currentViews()
            val due = if (views.state.meOrNull == null) emptyList() else withErrors(views.dueOccurrences(store.today()), failures.value)
            notices.afterRun(due, emptyList())
        }
    }

    /** Asks for a pass soon; requests made while one is waiting fold into it. Needs [start] to have run. */
    fun request() {
        requests.trySend(Unit)
    }

    /**
     * Runs requested passes in [scope], and requests one for every new ledger generation (the start-up load, a restore,
     * an undo of a restore), every change of the import batches (an import, an import undo), every change of the due
     * list, whatever wrote it (an archive or unarchive through `PeopleApi` / `GroupsApi`, a merge, a revert, a post or
     * skip). Every [RecurringNotices.redraw] runs [redraw] instead of a pass. A pass that posts changes the due list
     * and so is followed by one more, which finds nothing left to post.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        launch {
            for (signal in requests) {
                try {
                    run()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed write (e.g. a full disk) leaves the ledger as it was; the next trigger tries again.
                }
            }
        }
        launch { store.ready.map { it.dueOccurrences(store.today()) }.distinctUntilChanged().collect { request() } }
        launch {
            notices.redraw.collect {
                try {
                    redraw()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Drawing failed (e.g. the notified file can't be written); the next pass reports again.
                }
            }
        }
        store.snapshot.map { it.generation to it.importBatches }.distinctUntilChanged().collect { request() }
    }

    /** Posts one schedule's due occurrences; returns where it stopped on a failure, or null. */
    private suspend fun runSchedule(id: RecurringId, today: LocalDate, posted: MutableList<PostedOccurrence>): Failure? {
        while (true) {
            val views = store.currentViews()
            val schedule = views.state.schedules[id] ?: return null
            if (!Recurrence.autoPosts(schedule, views.state, views.resolver)) return null
            val next = Recurrence.next(schedule, views.state) ?: return null
            if (next.isAfter(today) || next.isBefore(schedule.autoPostFrom)) return null
            val prepared = when (val result = views.occurrenceDraft(id)) {
                is LedgerResult.Ok -> result.value
                is LedgerResult.Err -> return Failure(next, result.error)
            }
            val draft = when (val result = withRate(views, prepared)) {
                is LedgerResult.Ok -> result.value
                is LedgerResult.Err -> return Failure(next, result.error)
            }
            val result = store.write<EntryId?> { ctx ->
                // A hold can start while the rate is fetched and leaves the schedule's version alone, so the token
                // still matches: check again under the write lock, and post nothing when it no longer auto-posts.
                val live = ctx.state.schedules[id]
                if (live == null || !Recurrence.autoPosts(live, ctx.state)) LedgerResult.Ok(Planned(null, WritePlan.Empty))
                else RecurringOps.post(ctx, id, prepared.occurrence, draft, prepared.token)
            }
            when (result) {
                is LedgerResult.Ok -> if (result.value == null) return null else {
                    posted += PostedOccurrence(id, schedule.description, prepared.occurrence, draft.total)
                }
                is LedgerResult.Err -> return Failure(next, result.error)
            }
        }
    }

    /** Spec §5.7 FX: a conversion takes the rate for the occurrence date through the lookup chain; a stale one is fine. */
    private suspend fun withRate(views: LedgerViews, prepared: OccurrenceDraft): LedgerResult<ExpenseDraft> {
        val draft = prepared.draft
        val required = EntryOps.requiredEffectCurrency(views.state, draft.groupId, draft.total.currency, settlesCurrency = null)
        if (required == draft.total.currency) return LedgerResult.Ok(draft)
        return quotes.quote(prepared.occurrence, draft.total.currency, required).map { draft.copy(fx = FxChoice.Rate(it)) }
    }

    companion object {
        /** [due] with each schedule's failure attached to the occurrence its last pass stopped at. */
        fun withErrors(due: List<DueOccurrence>, failures: Map<RecurringId, Failure>): List<DueOccurrence> = due.map { item ->
            val failure = failures[item.schedule]
            if (failure != null && failure.occurrence == item.date) item.copy(autoPostError = failure.error) else item
        }
    }
}
```

A lost race shows as `StaleVersion`; the other writer's post moved the schedule on, so that failure names an occurrence that is no longer due and `withErrors` attaches it to nothing.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.store.RecurringRunnerTest"`
Expected: PASS, 10 tests.

- [ ] **Step 6: Commit**

```bash
git add android/app/src
git commit -m "Auto-post due occurrences with a runner shared by both flavors"
```

---

### Task 3: `RecurringApi`, both flavors, the fake seed and the contract tests

**Files:**
- Create: `main/.../api/RecurringApi.kt`, `main/.../store/StoreRecurringApi.kt`, `test/.../api/contract/RecurringApiContract.kt`, `test/.../data/RealRecurringTest.kt`, `test/.../store/StoreRecurringApiTest.kt`
- Modify: `main/.../api/Apis.kt`, `main/.../di/ApiModule.kt`, `main/.../fake/FakeApis.kt`, `main/.../fake/SeedData.kt`, `fake/.../di/BackendModule.kt`, `real/.../di/BackendModule.kt`, `main/.../data/RealBackend.kt`, `docs/ui-contributor-guide.md`, `docs/m2-ui-contract-changes.md`
- Modify (tests): `test/.../fake/FakeContractTests.kt`, `test/.../data/RealContractTests.kt`, `test/.../fake/SeedDataTest.kt`

**Interfaces:**
- Consumes: Part 3's views and ops, `RecurringRunner` (Task 2), `FakeRates.quote`, `FxService` (a `QuoteSource`).
- Produces: `interface RecurringApi` (`observeSchedules(includeDeleted = false, onHold: Boolean? = null)`, `observeSchedule(id)`, `observeDue()`, `saveSchedule(draft, mode)`, `deleteSchedule(id, token)`, `restoreSchedule(id, token)`, `occurrenceDraft(id)`, `postOccurrence(id, occurrence, draft, token)`, `skipOccurrences(id, through, token)`); `Apis.recurring`; `StoreRecurringApi(store, runner)`; `FakeApis.runner(store)`, `FakeApis.seededStore(...)`, `FakeApis.create(..., runner)`; `RealBackend.runner`; Hilt bindings for `RecurringRunner` in both flavors.

- [ ] **Step 1: Write the failing tests**

Create `test/.../api/contract/RecurringApiContract.kt`:

```kotlin
package io.github.codex07.iou.api.contract

import io.github.codex07.iou.api.GroupDraft
import io.github.codex07.iou.api.GroupRef
import io.github.codex07.iou.api.HoldReason
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleDetail
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.SplitInput
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** `RecurringApi` in both flavors (spec §5.7, AC 27, 28, 31, 33). Today is [TODAY], 1 March 2026. */
abstract class RecurringApiContract : ApiContract() {

    private fun d(text: String) = LocalDate.parse(text)

    /** Monthly confirm-first rent from 1 February: February and March are due today. */
    private fun rent(me: PersonId, other: PersonId) = RecurringDraft(
        payers = listOf(SplitInput(me)), description = "Rent", total = eur(90_000),
        participants = listOf(SplitInput(me), SplitInput(other)), unit = RecurrenceUnit.MONTH, startOn = d("2026-02-01"),
    )

    private suspend fun save(draft: RecurringDraft): RecurringId = apis.recurring.saveSchedule(draft, ScheduleSaveMode.Create(basis())).orFail()

    private suspend fun detail(id: RecurringId): ScheduleDetail = apis.recurring.observeSchedule(id).first()!!

    private suspend fun dueDates() = apis.recurring.observeDue().first().map { it.date }

    @Test
    fun `a monthly schedule from 31 January clamps to month ends and stops at endOn`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val id = save(rent(me, x).copy(startOn = d("2026-01-31"), endOn = d("2026-04-30")))
        suspend fun skip(through: LocalDate) = apis.recurring.skipOccurrences(id, through, detail(id).token).orFail()

        dueDates() shouldBe listOf(d("2026-01-31"), d("2026-02-28"))
        skip(d("2026-02-28"))
        detail(id).summary.next shouldBe d("2026-03-31")
        skip(d("2026-03-31"))
        detail(id).summary.next shouldBe d("2026-04-30")
        skip(d("2026-04-30"))
        detail(id).summary.next shouldBe null
        apis.recurring.occurrenceDraft(id) shouldBe LedgerResult.Err(LedgerError.OccurrenceNotDue(null))
    }

    @Test
    fun `due dates are posted or skipped oldest first, and a posted expense keeps its link`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val id = save(rent(me, x))
        dueDates() shouldBe listOf(d("2026-02-01"), d("2026-03-01"))

        val february = apis.recurring.occurrenceDraft(id).orFail()
        february.occurrence shouldBe d("2026-02-01")
        apis.recurring.postOccurrence(id, d("2026-03-01"), february.draft, february.token) shouldBe
            LedgerResult.Err(LedgerError.OccurrenceNotDue(d("2026-02-01")))
        val posted = apis.recurring.postOccurrence(id, february.occurrence, february.draft.copy(total = eur(95_000)), february.token).orFail()

        val item = apis.ledger.observeEntry(posted).first()!!.item
        item.paid shouldBe eur(95_000)
        (item.recurringId to item.recurringOn) shouldBe (id to d("2026-02-01"))
        detail(id).editDraft.total shouldBe eur(90_000)
        apis.recurring.skipOccurrences(id, d("2026-03-01"), detail(id).token).orFail()
        dueDates() shouldBe emptyList()
    }

    @Tag("groups")
    @Test
    fun `archiving a schedule's group holds it, and what a schedule references can't be deleted`() = runTest {
        val me = setUpMe()
        val a = newPerson("A")
        val y = newPerson("Y")
        val flat = apis.groups.createGroup(GroupDraft("Flat", EUR, FxMode.KEEP), listOf(a), basis()).orFail()
        val id = save(rent(me, a).copy(groupId = flat))
        save(rent(me, y))
        suspend fun groupToken() = apis.groups.observeGroup(flat).first()!!.token

        apis.groups.setGroupArchived(flat, true, groupToken()).orFail()
        apis.recurring.observeSchedules(onHold = true).first().map { it.id } shouldBe listOf(id)
        apis.recurring.observeSchedules(onHold = false).first().map { it.id }.contains(id) shouldBe false
        detail(id).summary.holds shouldBe listOf(HoldReason.GroupArchived(GroupRef(flat, "Flat")))
        apis.recurring.observeDue().first().none { it.schedule == id } shouldBe true

        apis.groups.setGroupArchived(flat, false, groupToken()).orFail()
        apis.recurring.observeDue().first().filter { it.schedule == id }.map { it.date } shouldBe listOf(d("2026-02-01"), d("2026-03-01"))
        detail(id).history.first().reason shouldBe "Resumed: Flat unarchived"

        apis.people.deletePerson(y, apis.people.observePerson(y).first()!!.token) shouldBe
            LedgerResult.Err(LedgerError.StillReferenced(y.value))
        apis.groups.deleteGroup(flat, groupToken()) shouldBe LedgerResult.Err(LedgerError.StillReferenced(flat.value))
    }

    @Test
    fun `an edit changes later occurrences only, and a deleted schedule keeps its posted expenses`() = runTest {
        val me = setUpMe()
        val x = newPerson("X")
        val id = save(rent(me, x).copy(startOn = d("2026-01-01")))
        val january = apis.recurring.occurrenceDraft(id).orFail()
        val posted = apis.recurring.postOccurrence(id, january.occurrence, january.draft, january.token).orFail()
        apis.recurring.skipOccurrences(id, d("2026-02-01"), detail(id).token).orFail()

        apis.recurring.saveSchedule(
            rent(me, x).copy(startOn = d("2026-01-05"), total = eur(99_000)), ScheduleSaveMode.Update(id, detail(id).token),
        ).orFail()
        detail(id).summary.next shouldBe d("2026-02-05")
        apis.ledger.observeEntry(posted).first()!!.item.paid shouldBe eur(90_000)

        apis.recurring.deleteSchedule(id, detail(id).token).orFail()
        apis.ledger.observeEntry(posted).first()!!.item.deleted shouldBe false
        dueDates() shouldBe emptyList()
        apis.recurring.restoreSchedule(id, detail(id).token).orFail()
        dueDates() shouldBe listOf(d("2026-02-05"))
    }
}
```

In `test/.../fake/FakeContractTests.kt`, add the import `io.github.codex07.iou.api.contract.RecurringApiContract` and the class:

```kotlin
class FakeRecurringApiContractTest : RecurringApiContract() { override fun newApis() = fakeApis() }
```

In `test/.../data/RealContractTests.kt`, add the import `io.github.codex07.iou.api.contract.RecurringApiContract` and the class:

```kotlin

class RealRecurringApiContractTest : RecurringApiContract() {
    private val harness = harness()
    override fun newApis() = harness.start().apis
    @AfterEach fun stop() = harness.stop()
}
```

Create `test/.../data/RealRecurringTest.kt`:

```kotlin
package io.github.codex07.iou.data

import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.api.contract.ApiContract
import io.github.codex07.iou.ledger.model.RecurrenceUnit
import io.github.codex07.iou.ledger.model.SplitInput
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.testutil.RealHarness
import io.github.codex07.iou.testutil.SequentialIds
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Schedules survive a restart of the real backend (AC 28: "neither comes back after a restart"). */
class RealRecurringTest {

    private val harness = RealHarness(Clock.fixed(ApiContract.NOW, ZoneOffset.UTC), SequentialIds("recurring"))

    @AfterEach
    fun stop() = harness.stop()

    @Test
    fun `posted and skipped occurrences stay handled after a restart`() = runTest {
        val apis = harness.start().apis
        val me = (apis.setup.completeSetup("Me", CurrencyCode("EUR")) as LedgerResult.Ok).value
        val x = (apis.people.createPerson(PersonDraft("X"), apis.backup.observeLedgerGeneration().first()) as LedgerResult.Ok).value
        val draft = RecurringDraft(
            payers = listOf(SplitInput(me)), description = "Rent", total = Money(90_000, CurrencyCode("EUR")),
            participants = listOf(SplitInput(me), SplitInput(x)), unit = RecurrenceUnit.MONTH, startOn = LocalDate.parse("2026-02-01"),
        )
        val id = (apis.recurring.saveSchedule(draft, ScheduleSaveMode.Create(apis.backup.observeLedgerGeneration().first())) as LedgerResult.Ok).value
        val february = (apis.recurring.occurrenceDraft(id) as LedgerResult.Ok).value
        apis.recurring.postOccurrence(id, february.occurrence, february.draft, february.token)
        val token = apis.recurring.observeSchedule(id).first()!!.token
        apis.recurring.skipOccurrences(id, LocalDate.parse("2026-03-01"), token) shouldBe LedgerResult.Ok(Unit)
        harness.stop()

        val again = harness.start().apis
        again.recurring.observeDue().first() shouldBe emptyList()
        again.recurring.observeSchedule(id).first()!!.handledThrough shouldBe LocalDate.parse("2026-03-01")
    }
}
```

In `test/.../fake/SeedDataTest.kt`, include schedules in the replay test: replace:

```kotlin
        val current: List<LedgerEntity> = state.people.values + state.handles.values + state.groups.values +
            state.members.values + state.entries
```

with:

```kotlin
        val current: List<LedgerEntity> = state.people.values + state.handles.values + state.groups.values +
            state.members.values + state.entries + state.schedules.values
```

and add after `the seed has a group expense with two payers` (Part 2):

```kotlin

    @Test
    fun `the seed has a confirm-first schedule with one date due and an auto-post schedule`() = runTest {
        val schedules = apis.recurring.observeSchedules().first()
        schedules.map { it.description to it.autoPost }.toSet() shouldBe setOf("Rent" to false, "Streaming" to true)
        apis.recurring.observeDue().first().map { it.description } shouldBe listOf("Rent")
    }
```

Create `test/.../store/StoreRecurringApiTest.kt`:

```kotlin
package io.github.codex07.iou.store

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Date-relative reads follow the runner's passes (spec §5.7). */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreRecurringApiTest {

    /** A clock the test moves (UTC). */
    private class MovingClock(var at: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = at
    }

    @Test
    fun `an open due list shows a new day's occurrence after a pass that writes nothing`() = runTest {
        val ledger = TestLedger().apply { me() }
        val x = ledger.person("X")
        ledger.schedule(ME, eur(90_000), listOf(ME, x), startOn = LocalDate.parse("2026-12-01"))
        val clock = MovingClock(Instant.parse("2026-11-30T10:00:00Z"))
        val store = LedgerStore(clock, SequentialIds("api"), DeviceId("device-test"), initial = LedgerContent(ledger.state))
        val runner = RecurringRunner(store, QuoteSource { _, from, to -> LedgerResult.Err(LedgerError.RateRequired(from, to)) })
        val seen = mutableListOf<List<LocalDate>>()
        backgroundScope.launch { StoreRecurringApi(store, runner).observeDue().collect { due -> seen += due.map { it.date } } }
        runCurrent()
        seen.last() shouldBe emptyList()

        clock.at = Instant.parse("2026-12-01T10:00:00Z")
        runner.run() shouldBe emptyList()
        runCurrent()
        seen.last() shouldBe listOf(LocalDate.parse("2026-12-01"))
    }
}
```

The schedule is confirm-first, so the pass writes nothing and the ledger flow does not emit; only the runner's new day restarts the read.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.fake.FakeRecurringApiContractTest"`
Expected: FAIL, compilation error `Unresolved reference 'recurring'` on `Apis`.

- [ ] **Step 3: Add the interface**

Create `main/.../api/RecurringApi.kt`:

```kotlin
package io.github.codex07.iou.api

import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.RecurringId
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * Recurring expenses (spec §5.7, §6.2). A schedule either posts by itself (`autoPost`) or lists its due dates for the
 * user to post or skip, oldest first; out of order → `OccurrenceNotDue(next)`. Revert and "Open as draft" of a schedule
 * go through `LedgerApi.revertTo` / `draftFromVersion` (entity type `RECURRING_EXPENSE`), as for other entities.
 */
interface RecurringApi {

    /** Schedules by description. [onHold]: true lists only held schedules (their own screen), false only active ones, null both. */
    fun observeSchedules(includeDeleted: Boolean = false, onHold: Boolean? = null): Flow<List<ScheduleSummary>>

    fun observeSchedule(id: RecurringId): Flow<ScheduleDetail?>

    /** Every due occurrence of every live schedule not on hold, oldest first, with `autoPostError` where a pass stopped. */
    fun observeDue(): Flow<List<DueOccurrence>>

    /** Errors: field and split errors as for expenses, `InvalidField("interval")`, `InvalidField("endOn")`, and any rule. */
    suspend fun saveSchedule(draft: RecurringDraft, mode: ScheduleSaveMode): LedgerResult<RecurringId>

    /** Keeps the posted expenses. */
    suspend fun deleteSchedule(id: RecurringId, token: EditToken): LedgerResult<Unit>

    /** Brings its occurrences after the handled date back; an auto-post schedule waits for them to be confirmed. */
    suspend fun restoreSchedule(id: RecurringId, token: EditToken): LedgerResult<Unit>

    /** The draft for the next occurrence: `NotFound` for a deleted schedule, `OccurrenceNotDue(null)` once it ended. */
    suspend fun occurrenceDraft(id: RecurringId): LedgerResult<OccurrenceDraft>

    /** Posts [occurrence], which must be the next one, as [draft] (edited freely); [token] is the schedule's. */
    suspend fun postOccurrence(id: RecurringId, occurrence: LocalDate, draft: ExpenseDraft, token: EditToken): LedgerResult<EntryId>

    /** Skips every occurrence from the next one through [through], which must be an occurrence date. */
    suspend fun skipOccurrences(id: RecurringId, through: LocalDate, token: EditToken): LedgerResult<Unit>
}
```

In `main/.../api/Apis.kt`, replace:

```kotlin
/** All eight interfaces of one implementation; used by contract tests and Compose previews. */
data class Apis(
```

with:

```kotlin
/** All nine interfaces of one implementation; used by contract tests and Compose previews. */
data class Apis(
```

and replace:

```kotlin
    val settings: SettingsApi,
)
```

with:

```kotlin
    val settings: SettingsApi,
    val recurring: RecurringApi,
)
```

In `main/.../di/ApiModule.kt`, add the import `io.github.codex07.iou.api.RecurringApi`, change "eight" to "nine" in its KDoc, and add after the `settings` provider:

```kotlin
    @Provides fun recurring(apis: Apis): RecurringApi = apis.recurring
```

- [ ] **Step 4: Implement it on the store**

Create `main/.../store/StoreRecurringApi.kt`:

```kotlin
package io.github.codex07.iou.store

import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.ExpenseDraft
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.OccurrenceDraft
import io.github.codex07.iou.api.RecurringApi
import io.github.codex07.iou.api.RecurringDraft
import io.github.codex07.iou.api.ScheduleDetail
import io.github.codex07.iou.api.ScheduleSaveMode
import io.github.codex07.iou.api.ScheduleSummary
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.ops.Planned
import io.github.codex07.iou.ledger.ops.RecurringOps
import io.github.codex07.iou.ledger.ops.WriteContext
import io.github.codex07.iou.ledger.views.LedgerViews
import io.github.codex07.iou.ledger.views.dueOccurrences
import io.github.codex07.iou.ledger.views.occurrenceDraft
import io.github.codex07.iou.ledger.views.schedule
import io.github.codex07.iou.ledger.views.schedules
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

/**
 * [RecurringApi] for both flavors. Dates are relative to the store's today (device zone), taken on every emission;
 * the date-relative reads also start over whenever a runner pass runs for a new day ([RecurringRunner.today]), so
 * an open due list catches up with the pass even when that pass writes nothing (spec §5.7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreRecurringApi(private val store: LedgerStore, private val runner: RecurringRunner) : RecurringApi {

    override fun observeSchedules(includeDeleted: Boolean, onHold: Boolean?): Flow<List<ScheduleSummary>> =
        dated { it.schedules(includeDeleted, onHold, store.today()) }

    override fun observeSchedule(id: RecurringId): Flow<ScheduleDetail?> = dated { it.schedule(id, store.today()) }

    override fun observeDue(): Flow<List<DueOccurrence>> =
        combine(dated { it.dueOccurrences(store.today()) }, runner.errors) { due, errors -> RecurringRunner.withErrors(due, errors) }

    private fun <T> dated(project: (LedgerViews) -> T): Flow<T> = runner.today.flatMapLatest { store.read(project) }

    override suspend fun saveSchedule(draft: RecurringDraft, mode: ScheduleSaveMode): LedgerResult<RecurringId> =
        written { RecurringOps.save(it, draft, mode) }

    override suspend fun deleteSchedule(id: RecurringId, token: EditToken): LedgerResult<Unit> =
        written { RecurringOps.delete(it, id, token) }

    override suspend fun restoreSchedule(id: RecurringId, token: EditToken): LedgerResult<Unit> =
        written { RecurringOps.restore(it, id, token) }

    override suspend fun occurrenceDraft(id: RecurringId): LedgerResult<OccurrenceDraft> = store.currentViews().occurrenceDraft(id)

    override suspend fun postOccurrence(id: RecurringId, occurrence: LocalDate, draft: ExpenseDraft, token: EditToken): LedgerResult<EntryId> =
        written { RecurringOps.post(it, id, occurrence, draft, token) }

    override suspend fun skipOccurrences(id: RecurringId, through: LocalDate, token: EditToken): LedgerResult<Unit> =
        written { RecurringOps.skip(it, id, through, token) }

    /** A schedule write; a successful one asks for a runner pass, which also brings notifications up to date (spec §5.7). */
    private suspend fun <T> written(op: (WriteContext) -> LedgerResult<Planned<T>>): LedgerResult<T> =
        store.write(op = op).also { if (it is LedgerResult.Ok) runner.request() }
}
```

- [ ] **Step 5: Wire both flavors and seed schedules**

In `main/.../fake/FakeApis.kt`, add the imports `io.github.codex07.iou.store.QuoteSource`, `io.github.codex07.iou.store.RecurringRunner` and `io.github.codex07.iou.store.StoreRecurringApi`. Replace:

```kotlin
    fun create(
        store: LedgerStore,
        device: DeviceSettingsStore = InMemoryDeviceSettings(),
        lock: LockGate = TimedLockGate(device),
    ): Apis = Apis(
```

with:

```kotlin
    /** The fake's runner: demo rates, no notifications (spec §6.3). */
    fun runner(store: LedgerStore): RecurringRunner = RecurringRunner(store, QuoteSource { date, from, to -> FakeRates.quote(date, from, to) })

    fun create(
        store: LedgerStore,
        device: DeviceSettingsStore = InMemoryDeviceSettings(),
        lock: LockGate = TimedLockGate(device),
        runner: RecurringRunner = runner(store),
    ): Apis = Apis(
```

replace:

```kotlin
        settings = StoreSettingsApi(store, device, lock),
    )
```

with:

```kotlin
        settings = StoreSettingsApi(store, device, lock),
        recurring = StoreRecurringApi(store, runner),
    )
```

and replace:

```kotlin
    ): Apis = create(store(clock, zone = zone).also { runBlocking { SeedData.populate(it) } }, device, lock)
```

with:

```kotlin
    ): Apis = create(seededStore(clock, zone), device, lock)

    /** The in-memory store with demo data. */
    fun seededStore(clock: Clock = Clock.systemUTC(), zone: () -> ZoneId = { clock.zone }): LedgerStore =
        store(clock, zone = zone).also { runBlocking { SeedData.populate(it) } }
```

Replace the whole of `fake/.../di/BackendModule.kt` (path `android/app/src/fake/kotlin/io/github/codex07/iou/di/BackendModule.kt`) with:

```kotlin
package io.github.codex07.iou.di

import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.fake.FakeApis
import io.github.codex07.iou.store.DeviceSettingsStore
import io.github.codex07.iou.store.InMemoryDeviceSettings
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.RecurringRunner
import io.github.codex07.iou.store.TimedLockGate
import io.github.codex07.iou.ui.settings.DataServices
import java.time.ZoneId
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/** fake flavor: the in-memory ledger with demo data, the shared runner (no worker, no notifications), the real app-lock policy. */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides
    @Singleton
    fun device(): DeviceSettingsStore = InMemoryDeviceSettings()

    @Provides
    @Singleton
    fun lock(device: DeviceSettingsStore): TimedLockGate = TimedLockGate(device) { SystemClock.elapsedRealtime().milliseconds }

    @Provides
    @Singleton
    fun store(): LedgerStore = FakeApis.seededStore(zone = { ZoneId.systemDefault() })

    @Provides
    @Singleton
    fun runner(store: LedgerStore): RecurringRunner = FakeApis.runner(store)

    @Provides
    @Singleton
    fun apis(store: LedgerStore, device: DeviceSettingsStore, lock: TimedLockGate, runner: RecurringRunner): Apis =
        FakeApis.create(store, device, lock, runner)

    @Provides
    fun dataServices(): DataServices = DataServices.Simulated
}
```

In `main/.../data/RealBackend.kt`, add the imports `io.github.codex07.iou.store.RecurringRunner` and `io.github.codex07.iou.store.StoreRecurringApi`. Replace:

```kotlin
    val restores = RestoreProcedure(store, files, databases, fx, ids, restoreHook)
```

with:

```kotlin
    val restores = RestoreProcedure(store, files, databases, fx, ids, restoreHook)

    /** Auto-posting (spec §5.7) with rates from the lookup chain; `RecurringTriggers` and the worker drive it. */
    val runner = RecurringRunner(store, fx)
```

replace:

```kotlin
        settings = StoreSettingsApi(store, device, lock),
    )
```

with:

```kotlin
        settings = StoreSettingsApi(store, device, lock),
        recurring = StoreRecurringApi(store, runner),
    )
```

and let the daily refresh cover template currencies too. Replace:

```kotlin
                state.groups.values.map { it.defaultCurrency }
```

with:

```kotlin
                state.groups.values.map { it.defaultCurrency } +
                state.schedules.values.map { it.total.currency }
```

In `real/.../di/BackendModule.kt` (path `android/app/src/real/kotlin/io/github/codex07/iou/di/BackendModule.kt`), add the import `io.github.codex07.iou.store.RecurringRunner` and the provider:

```kotlin

    @Provides
    fun runner(backend: RealBackend): RecurringRunner = backend.runner
```

In `main/.../fake/SeedData.kt`, add the imports `io.github.codex07.iou.api.RecurringDraft`, `io.github.codex07.iou.api.ScheduleSaveMode`, `io.github.codex07.iou.ledger.model.RecurrenceUnit` and `io.github.codex07.iou.ledger.ops.RecurringOps`, and replace:

```kotlin
        transfer(bea, chen, Money(500_000, sek), daysAgo = 2, kind = TransferKind.REPAYMENT, group = flat)
```

with:

```kotlin
        transfer(bea, chen, Money(500_000, sek), daysAgo = 2, kind = TransferKind.REPAYMENT, group = flat)

        // Schedules: the flat's monthly rent waits for confirmation (one date due), a subscription posts by itself.
        suspend fun schedule(draft: RecurringDraft) =
            ok(store.write { RecurringOps.save(it, draft, ScheduleSaveMode.Create(it.generation)) })
        schedule(
            RecurringDraft(
                listOf(SplitInput(chen)), "Rent", Category.RENT, Money(1_500_000, sek), flat,
                participants = flatmates, unit = RecurrenceUnit.MONTH, startOn = today.minusDays(2),
            ),
        )
        schedule(
            RecurringDraft(
                listOf(SplitInput(me)), "Streaming", Category.ENTERTAINMENT, Money(1_299, eur),
                participants = listOf(SplitInput(me), SplitInput(erik)), unit = RecurrenceUnit.MONTH,
                startOn = today.plusDays(3), autoPost = true,
            ),
        )
```

The rent's next date after the one due is a month later, so exactly one date is due on any day the seed runs.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.fake.*" --tests "io.github.codex07.iou.store.StoreRecurringApiTest"`
Expected: PASS, the four recurring contract tests, the seed tests and `StoreRecurringApiTest` included.

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.data.RealRecurringApiContractTest" --tests "io.github.codex07.iou.data.RealRecurringTest" --tests "io.github.codex07.iou.data.BackupFormatTest" --tests "io.github.codex07.iou.data.RoomJournalTest"`
Expected: PASS. The seeded backup and journal round trips now carry the two schedules and their revisions.

Then: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Update the docs and commit**

In `docs/ui-contributor-guide.md`, add after the "Settle up" section:

```markdown
## Recurring expenses

- `RecurringApi` (spec §5.7): `observeSchedules(includeDeleted, onHold)`, `observeSchedule(id)`, `observeDue()`, `saveSchedule(draft, ScheduleSaveMode.Create(basis) | Update(id, token))`, `deleteSchedule` / `restoreSchedule`, `occurrenceDraft(id)`, `postOccurrence(id, occurrence, draft, token)`, `skipOccurrences(id, through, token)`.
- A `RecurringDraft` is an expense template (payers, participants, split, total, group, description, category, note) plus `unit` (`WEEK`, `MONTH`, `YEAR`), `interval` (1–99), `startOn`, optional `endOn` and `autoPost`. Edit forms start from `ScheduleDetail.editDraft`; show `ScheduleSummary.next` before saving, because an edit never skips a date by itself.
- Held schedules (`ScheduleSummary.holds` not empty: an archived group or person) belong on their own screen: `observeSchedules(onHold = true)`; the main list uses `onHold = false`. Held schedules have nothing due.
- The due list is oldest first. Post with `occurrenceDraft` → let the user change anything → `postOccurrence` with that occurrence and token; or skip through a date. Out of order → `OccurrenceNotDue(nextDue)`. `confirmToResume` marks an auto-post schedule whose older dates must be posted or skipped before it posts again ("Confirm these to resume auto-posting"); `autoPostError` says why an auto-post stopped (e.g. `RateRequired`, `MemberRemoved`).
- Posted expenses are normal expenses; `ActivityItem.recurringId` / `recurringOn` link them to their schedule. Schedule history reverts through `LedgerApi.revertTo`; "Open as draft" gives `VersionDraft.OfSchedule`.
- Notifications (real build only): ask for `POST_NOTIFICATIONS` the first time a schedule is saved (Android 13+). A tap opens `MainActivity` with the extra `iou.open = "due"`, plus `iou.schedule = <schedule ID>` when the notification is about one schedule; open the due list (both on launch and in `onNewIntent`).
```

In `docs/m2-ui-contract-changes.md`, section "Notifications (real build only)", replace:

```markdown
- UI work: ask for the `POST_NOTIFICATIONS` permission the first time a schedule is saved (Android 13+), and handle the `iou.open` extra on `MainActivity` (value `"due"`, optionally a schedule ID) by opening the due list.
```

with:

```markdown
- UI work: ask for the `POST_NOTIFICATIONS` permission the first time a schedule is saved (Android 13+), and handle the `iou.open` extra on `MainActivity` (value `"due"`; `iou.schedule` carries the schedule ID when the notification is about one schedule) by opening the due list.
```

```bash
git add android/app/src docs/ui-contributor-guide.md docs/m2-ui-contract-changes.md
git commit -m "Expose schedules through RecurringApi in both flavors and seed two schedules"
```

---

### Task 4: Notifications, the background worker and the foreground trigger

**Files:**
- Create: `main/.../store/RecurringNoticePlanner.kt`, `main/.../data/RecurringNotifiedFile.kt`, `main/.../data/AndroidRecurringNotices.kt`, `main/.../RecurringTriggers.kt`, `real/.../data/RecurringPostWorker.kt`, `test/.../store/RecurringNoticePlannerTest.kt`, `test/.../data/RecurringNotifiedFileTest.kt`
- Modify: `main/.../IouApplication.kt`, `main/.../data/RealBackend.kt`, `real/.../di/BackendModule.kt`, `android/app/src/real/AndroidManifest.xml`, `docs/manual-test-checklist.md`

**Interfaces:**
- Consumes: `RecurringNotices`, `PostedOccurrence`, `RecurringRunner.start` / `request` / `run` (Task 2), `RealBackend.runner` (Task 3), `RateRefreshWorker.Backend` (the existing Hilt entry point), `File.writeAtomically` (`data/DbFiles.kt`), `DeviceSettingsStore` / `DeviceSettings`; the serialization plugin (`@Serializable`).
- Produces: `RecurringNoticePlanner.plan(due, posted, locked, last: NotifiedRecord): NoticePlan` with `NoticePlan(show: List<DueNotice>, cancel: Set<RecurringId>, summary: Int?, posted: PostedNotice?, cancelPosted: Boolean, notified: NotifiedRecord)`, `NotifiedRecord(due: Map<String, String>, detailedPosted: Boolean)`, `DueNotice(schedule, title, text, silent)`, `PostedNotice(title, text)`; `RecurringNotifiedFile(file)` with `read(): NotifiedRecord` / `write(record)`; `AndroidRecurringNotices(context, notified, settings: StateFlow<DeviceSettings>)` with `redraw` on app-lock changes; `RecurringTriggers` (Hilt-injected, `install(scope)`); `RecurringPostWorker` (`NAME = "recurring-post"`, `schedule(context)`); `RealBackend(..., notices: (DeviceSettingsStore) -> RecurringNotices)`.

- [ ] **Step 1: Write the failing tests**

Create `test/.../store/RecurringNoticePlannerTest.kt`:

```kotlin
package io.github.codex07.iou.store

import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.LocalDate
import org.junit.jupiter.api.Test

/** What the notifier shows, re-shows, cancels and records (spec §5.7 Notifications, AC 34 logic). */
class RecurringNoticePlannerTest {

    private fun d(text: String) = LocalDate.parse(text)
    private val rent = RecurringId("rent")
    private val gym = RecurringId("gym")

    private fun due(schedule: RecurringId, date: String, autoPost: Boolean = false, waits: Boolean = false, error: LedgerError? = null) =
        DueOccurrence(schedule, if (schedule == rent) "Rent" else "Gym", d(date), eur(90_000), null, autoPost, waits, error)

    @Test
    fun `a schedule is notified once per oldest due occurrence, and cancelled when nothing is due`() {
        val first = RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01"), due(rent, "2026-04-01")), emptyList(), false, NotifiedRecord())
        first.show.map { it.schedule } shouldBe listOf(rent)
        first.show.single().text shouldContain "2 due"
        first.show.single().text shouldContain "2026-03-01"

        val again = RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01"), due(rent, "2026-04-01")), emptyList(), false, first.notified)
        again.show shouldBe emptyList()

        // An edit (or an import, or a restore) that changes what is shown redraws it, silently.
        val edited = RecurringNoticePlanner.plan(
            listOf(due(rent, "2026-03-01").copy(amount = eur(95_000)), due(rent, "2026-04-01")), emptyList(), false, again.notified,
        )
        edited.show.single().text shouldContain "950.00 EUR"
        edited.show.single().silent shouldBe true
        val oneMore = RecurringNoticePlanner.plan(
            listOf(due(rent, "2026-03-01"), due(rent, "2026-04-01"), due(rent, "2026-05-01")), emptyList(), false, again.notified,
        )
        oneMore.show.single().text shouldContain "3 due"
        oneMore.show.single().silent shouldBe true

        val moved = RecurringNoticePlanner.plan(listOf(due(rent, "2026-04-01")), emptyList(), false, again.notified)
        moved.show.map { it.schedule } shouldBe listOf(rent)

        val failed = RecurringNoticePlanner.plan(listOf(due(rent, "2026-04-01", true, error = LedgerError.RateRequired(SEK, EUR))), emptyList(), false, moved.notified)
        failed.show.single().text shouldContain "exchange rate"

        val done = RecurringNoticePlanner.plan(emptyList(), emptyList(), false, failed.notified)
        done.cancel shouldBe setOf(rent)
        done.notified shouldBe NotifiedRecord()
    }

    @Test
    fun `a backlog says why auto-post waits, and posted occurrences get one summary`() {
        val posted = listOf(PostedOccurrence(gym, "Gym", d("2026-03-01"), eur(4_000)))
        val plan = RecurringNoticePlanner.plan(listOf(due(rent, "2026-02-01", autoPost = true, waits = true)), posted, false, NotifiedRecord())

        plan.show.single().text shouldContain "Older dates need confirmation"
        plan.posted!!.text shouldContain "Gym 40.00 EUR, 2026-03-01"
        RecurringNoticePlanner.plan(emptyList(), emptyList(), false, NotifiedRecord()).posted shouldBe null
    }

    @Test
    fun `while app lock is on, texts name no description or amount`() {
        val posted = listOf(PostedOccurrence(gym, "Gym", d("2026-03-01"), eur(4_000)), PostedOccurrence(gym, "Gym", d("2026-03-08"), eur(4_000)))
        val plan = RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01"), due(rent, "2026-04-01")), posted, true, NotifiedRecord())

        val notice = plan.show.single()
        "${notice.title} ${notice.text}" shouldNotContain "Rent"
        "${notice.title} ${notice.text}" shouldNotContain "900.00"
        notice.text shouldBe "2 recurring expenses are due"
        plan.posted!!.text shouldBe "2 recurring expenses were posted"
    }

    @Test
    fun `switching app lock on redraws shown notices silently and redacted, and takes a detailed posted summary away`() {
        val posted = listOf(PostedOccurrence(gym, "Gym", d("2026-03-01"), eur(4_000)))
        val open = RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01")), posted, false, NotifiedRecord())
        open.notified.detailedPosted shouldBe true

        val locked = RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01")), emptyList(), true, open.notified)
        val notice = locked.show.single()
        notice.silent shouldBe true
        "${notice.title} ${notice.text}" shouldNotContain "Rent"
        locked.cancelPosted shouldBe true
        locked.notified.detailedPosted shouldBe false

        val again = RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01")), emptyList(), true, locked.notified)
        again.show shouldBe emptyList()
        again.cancelPosted shouldBe false

        val newDate = RecurringNoticePlanner.plan(listOf(due(rent, "2026-04-01")), emptyList(), true, again.notified)
        newDate.show.single().silent shouldBe false
    }

    @Test
    fun `the group summary is drawn only when the group changes`() {
        val both = listOf(due(rent, "2026-03-01"), due(gym, "2026-03-01"))
        val first = RecurringNoticePlanner.plan(both, emptyList(), false, NotifiedRecord())
        first.summary shouldBe 2

        val unchanged = RecurringNoticePlanner.plan(both, emptyList(), false, first.notified)
        unchanged.show shouldBe emptyList()
        unchanged.summary shouldBe null

        val one = RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01")), emptyList(), false, unchanged.notified)
        one.cancel shouldBe setOf(gym)
        one.summary shouldBe 0
        // Removing the summary removes rent's notification too, so rent is drawn again, silently, and only once.
        one.show.map { it.schedule to it.silent } shouldBe listOf(rent to true)
        RecurringNoticePlanner.plan(listOf(due(rent, "2026-03-01")), emptyList(), false, one.notified).show shouldBe emptyList()
    }
}
```

Create `test/.../data/RecurringNotifiedFileTest.kt`:

```kotlin
package io.github.codex07.iou.data

import io.github.codex07.iou.store.NotifiedRecord
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The device-local record of what was last notified (spec §5.7): replaced atomically, unreadable = empty. */
class RecurringNotifiedFileTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `it round-trips, and a missing or unreadable file reads as empty`() {
        val file = RecurringNotifiedFile(File(dir, "recurring-notified.json"))
        file.read() shouldBe NotifiedRecord()

        val record = NotifiedRecord(mapOf("rent" to "2026-03-01|null|false|open"), detailedPosted = true)
        file.write(record)
        file.read() shouldBe record

        File(dir, "recurring-notified.json").writeText("not json")
        file.read() shouldBe NotifiedRecord()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.store.RecurringNoticePlannerTest" --tests "io.github.codex07.iou.data.RecurringNotifiedFileTest"`
Expected: FAIL, compilation errors `Unresolved reference 'RecurringNoticePlanner'` and `'RecurringNotifiedFile'`.

- [ ] **Step 3: Plan notifications (pure)**

Create `main/.../store/RecurringNoticePlanner.kt`:

```kotlin
package io.github.codex07.iou.store

import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.ledger.model.RecurringId

/** One "due" notification: about [schedule], drawn with [title] and [text]; [silent] when only its redaction changed. */
data class DueNotice(val schedule: RecurringId, val title: String, val text: String, val silent: Boolean = false)

/** The "posted" summary of one runner pass. */
data class PostedNotice(val title: String, val text: String)

/**
 * What the notifier last drew (spec §5.7), the content of the notified file: per schedule ID the key of its due
 * notification (what it showed, and whether it was redacted), and whether a "posted" summary with details may still
 * be showing.
 */
data class NotifiedRecord(val due: Map<String, String> = emptyMap(), val detailedPosted: Boolean = false)

/**
 * What the notifier changes after a pass: due notifications to show (new, changed, or redrawn for a change of app
 * lock), schedules whose notification goes because nothing is due any more, the due group's [summary] (draw it for
 * that many schedules, 0 = remove it, null = leave it as it is), the posted summary, whether to remove a detailed
 * posted summary, and the new [notified] record.
 */
data class NoticePlan(
    val show: List<DueNotice>,
    val cancel: Set<RecurringId>,
    val summary: Int?,
    val posted: PostedNotice?,
    val cancelPosted: Boolean,
    val notified: NotifiedRecord,
)

/**
 * The notification logic of spec §5.7, without Android: one due notification per schedule with due occurrences,
 * shown again only when its oldest due occurrence, failure or backlog state changes, and redrawn silently when app
 * lock is switched on or off; texts that name no person, amount or description while app lock is on, and no detailed
 * posted summary left showing once it is on. The group summary is redrawn only when the group changes. When the
 * group shrinks to one schedule its summary is removed, and Android removes the summary's children with it, so the
 * schedule left is redrawn silently.
 */
object RecurringNoticePlanner {

    fun plan(due: List<DueOccurrence>, posted: List<PostedOccurrence>, locked: Boolean, last: NotifiedRecord): NoticePlan {
        val bySchedule = due.groupBy { it.schedule }.toSortedMap(compareBy { it.value })
        val notices = bySchedule.mapValues { (id, items) -> dueNotice(id, items, locked) }
        // "<alert key>|<digest of what is drawn>": a change of the alert key alerts; a change of only what is drawn
        // (app lock, description, amount, count, after an edit, an import or a restore) redraws silently.
        val keys = bySchedule.mapValues { (id, items) -> "${key(items.first())}|${digest(notices.getValue(id))}" }
        // Removing the group summary takes the group's notifications with it, unchanged ones included.
        val ungrouped = last.due.size > 1 && bySchedule.size == 1
        val show = bySchedule
            .filter { (id, _) -> ungrouped || last.due[id.value] != keys.getValue(id) }
            .map { (id, items) ->
                val before = last.due[id.value]?.substringBeforeLast('|')
                notices.getValue(id).copy(silent = before == key(items.first()))
            }
        val cancel = last.due.keys.map(::RecurringId).filter { it !in bySchedule }.toSet()
        val summary = when {
            bySchedule.size <= 1 -> 0
            show.isNotEmpty() || cancel.isNotEmpty() -> bySchedule.size
            else -> null
        }
        val postedNotice = posted.takeIf { it.isNotEmpty() }?.let { postedNotice(it, locked) }
        val cancelPosted = postedNotice == null && locked && last.detailedPosted
        val detailed = if (postedNotice != null) !locked else last.detailedPosted && !cancelPosted
        return NoticePlan(show, cancel, summary, postedNotice, cancelPosted, NotifiedRecord(keys.mapKeys { it.key.value }, detailed))
    }

    /** What makes a due notification worth showing again: its oldest occurrence, its failure, its backlog state. */
    private fun key(oldest: DueOccurrence): String =
        "${oldest.date}|${oldest.autoPostError?.let { it::class.simpleName }}|${oldest.confirmToResume}"

    /** A short, stable digest of a notice's title and text (`String.hashCode` is fixed by the Java spec). */
    private fun digest(notice: DueNotice): String = Integer.toHexString("${notice.title}\n${notice.text}".hashCode())

    private fun dueNotice(id: RecurringId, items: List<DueOccurrence>, locked: Boolean): DueNotice {
        val oldest = items.first()
        if (locked) {
            val text = if (items.size == 1) "1 recurring expense is due" else "${items.size} recurring expenses are due"
            return DueNotice(id, "Recurring expenses due", text)
        }
        val why = oldest.autoPostError?.let(::reason)
            ?: "Older dates need confirmation before auto-posting continues.".takeIf { oldest.confirmToResume }
        val text = "${items.size} due, oldest ${oldest.date}: ${oldest.amount}" + (why?.let { ". $it" } ?: "")
        return DueNotice(id, oldest.description, text)
    }

    private fun postedNotice(posted: List<PostedOccurrence>, locked: Boolean): PostedNotice {
        if (locked) {
            val text = if (posted.size == 1) "1 recurring expense was posted" else "${posted.size} recurring expenses were posted"
            return PostedNotice("Recurring expenses posted", text)
        }
        return PostedNotice("Recurring expenses posted", posted.joinToString("; ") { "${it.description} ${it.amount}, ${it.occurrence}" })
    }

    private fun reason(error: LedgerError): String = when (error) {
        is LedgerError.RateRequired -> "An exchange rate is needed."
        is LedgerError.MemberRemoved -> "A participant was removed from the group."
        else -> "It could not be posted automatically."
    }
}
```

`Money.toString()` gives "900.00 EUR", which is the amount format the texts use.

- [ ] **Step 4: Keep the notified record, draw the notifications, add the triggers and the worker**

Create `main/.../data/RecurringNotifiedFile.kt`:

```kotlin
package io.github.codex07.iou.data

import io.github.codex07.iou.store.NotifiedRecord
import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the notifier last drew (spec §5.7): `noBackupFilesDir/recurring-notified.json`, device-local, never backed
 * up, replaced atomically. Unreadable reads as empty, which at worst repeats a notification.
 */
class RecurringNotifiedFile(private val file: File) {

    fun read(): NotifiedRecord = if (!file.exists()) NotifiedRecord() else try {
        Json.decodeFromString(Stored.serializer(), file.readText()).let { NotifiedRecord(it.due, it.detailedPosted) }
    } catch (_: IllegalArgumentException) { // includes SerializationException
        NotifiedRecord()
    } catch (_: IOException) {
        NotifiedRecord()
    }

    fun write(record: NotifiedRecord) {
        file.parentFile?.mkdirs()
        file.writeAtomically(Json.encodeToString(Stored.serializer(), Stored(record.due, record.detailedPosted)))
    }

    @Serializable
    private data class Stored(val due: Map<String, String> = emptyMap(), val detailedPosted: Boolean = false)
}
```

Create `main/.../data/AndroidRecurringNotices.kt`:

```kotlin
package io.github.codex07.iou.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.codex07.iou.MainActivity
import io.github.codex07.iou.api.DueOccurrence
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.store.DeviceSettings
import io.github.codex07.iou.store.NotifiedRecord
import io.github.codex07.iou.store.PostedOccurrence
import io.github.codex07.iou.store.RecurringNoticePlanner
import io.github.codex07.iou.store.RecurringNotices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The real flavor's notifications (spec §5.7): "due" per schedule, one "posted" summary per pass, redacted while app
 * lock is on ([settings]). Switching app lock on or off makes the runner report at once ([redraw]), which redraws
 * the due notifications silently and, when it goes on, removes a detailed posted summary. Without the notification
 * permission, or on a blocked channel, nothing is shown and nothing is recorded as shown, so what is due appears again
 * once it can be drawn.
 */
class AndroidRecurringNotices(
    private val context: Context,
    private val notified: RecurringNotifiedFile,
    private val settings: StateFlow<DeviceSettings>,
) : RecurringNotices {

    override val redraw: Flow<Unit> = settings.map { it.appLockEnabled }.distinctUntilChanged().drop(1).map { }

    override suspend fun afterRun(due: List<DueOccurrence>, posted: List<PostedOccurrence>) = withContext(Dispatchers.IO) {
        val manager = NotificationManagerCompat.from(context)
        val last = notified.read()
        val plan = RecurringNoticePlanner.plan(due, posted, settings.value.appLockEnabled, last)
        plan.cancel.forEach { manager.cancel(it.value, DUE_ID) }
        // Before the redraws: removing the summary removes its children too, and the planner redraws the one left.
        if (plan.summary == 0) manager.cancel(GROUP_SUMMARY_TAG, DUE_ID)
        if (plan.cancelPosted) manager.cancel(POSTED_TAG, POSTED_ID)
        // While nothing can be shown, nothing is recorded as shown: Android may remove what was showing (a revoked
        // permission, a blocked channel), so the first pass that can draw again shows everything that is due.
        val undrawn = NotifiedRecord()
        if (!manager.areNotificationsEnabled()) {
            notified.write(undrawn)
            return@withContext
        }
        createChannels(manager)
        fun blocked(channel: String) = manager.getNotificationChannelCompat(channel)?.importance == NotificationManagerCompat.IMPORTANCE_NONE
        val dueBlocked = blocked(DUE_CHANNEL)
        val postedBlocked = blocked(POSTED_CHANNEL)
        try {
            if (!dueBlocked) {
                val single = plan.notified.due.size == 1
                for (notice in plan.show) {
                    val schedule = if (single) notice.schedule else null
                    manager.notify(notice.schedule.value, DUE_ID, build(DUE_CHANNEL, notice.title, notice.text, schedule, notice.silent))
                }
                plan.summary?.takeIf { it > 1 }?.let { manager.notify(GROUP_SUMMARY_TAG, DUE_ID, summary(it)) }
            }
            if (!postedBlocked) {
                plan.posted?.let { manager.notify(POSTED_TAG, POSTED_ID, build(POSTED_CHANNEL, it.title, it.text, null, silent = false)) }
            }
            notified.write(
                NotifiedRecord(
                    due = if (dueBlocked) emptyMap() else plan.notified.due,
                    detailedPosted = !postedBlocked && plan.notified.detailedPosted,
                ),
            )
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the post: record nothing as shown.
            notified.write(undrawn)
        }
    }

    private fun build(channel: String, title: String, text: String, schedule: RecurringId?, silent: Boolean) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setGroup(if (channel == DUE_CHANNEL) DUE_GROUP else null)
            .setSilent(silent)
            .setContentIntent(openDue(schedule))
            .build()

    /** The due group's summary. It never alerts: only a new or changed due notification does. */
    private fun summary(count: Int) = NotificationCompat.Builder(context, DUE_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle("Recurring expenses due")
        .setContentText("$count schedules have due dates")
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setGroup(DUE_GROUP)
        .setGroupSummary(true)
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        .setSilent(true)
        .setAutoCancel(true)
        .setContentIntent(openDue(null))
        .build()

    /** Opens the due list: `iou.open = "due"`, plus the schedule when the notification is about one. */
    private fun openDue(schedule: RecurringId?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN, OPEN_DUE)
        schedule?.let { intent.putExtra(EXTRA_SCHEDULE, it.value) }
        val request = schedule?.value?.hashCode() ?: 0
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createChannels(manager: NotificationManagerCompat) {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(DUE_CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName("Recurring expenses due").build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(POSTED_CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Recurring expenses posted").setSound(null, null).build(),
        )
    }

    companion object {
        const val EXTRA_OPEN = "iou.open"
        const val OPEN_DUE = "due"
        const val EXTRA_SCHEDULE = "iou.schedule"
        private const val DUE_CHANNEL = "recurring-due"
        private const val POSTED_CHANNEL = "recurring-posted"
        private const val DUE_GROUP = "io.github.codex07.iou.DUE"
        private const val GROUP_SUMMARY_TAG = "due-summary"
        private const val POSTED_TAG = "posted"
        private const val DUE_ID = 1
        private const val POSTED_ID = 2
    }
}
```

`NotificationCompat.Builder.setSilent` (androidx.core 1.5+, the project has 1.16) keeps a redraw that only changes the redaction from alerting again; on the summary it sets the group to alert through its children only.

In `main/.../data/RealBackend.kt`, add the imports `io.github.codex07.iou.store.DeviceSettingsStore` and `io.github.codex07.iou.store.RecurringNotices`. Add a constructor parameter after `rateCache`:

```kotlin
    notices: (DeviceSettingsStore) -> RecurringNotices = { RecurringNotices.None },
```

replace Task 3's:

```kotlin
    val runner = RecurringRunner(store, fx)
```

with:

```kotlin
    val runner = RecurringRunner(store, fx, notices(device))
```

and in `android(context)`, add after `zone = { ZoneId.systemDefault() },`:

```kotlin
                notices = { device ->
                    AndroidRecurringNotices(app, RecurringNotifiedFile(File(app.noBackupFilesDir, "recurring-notified.json")), device.settings)
                },
```

Create `main/.../RecurringTriggers.kt`:

```kotlin
package io.github.codex07.iou

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.codex07.iou.store.RecurringRunner
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The foreground triggers of auto-posting (spec §5.7), in both flavors: the runner's own triggers (start-up load,
 * restores, imports) start with the app, and every return to the foreground asks for a pass. Runs off the main thread.
 */
class RecurringTriggers @Inject constructor(private val runner: RecurringRunner) {

    fun install(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
        runner.start(scope)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = runner.request()
        })
    }
}
```

In `main/.../IouApplication.kt`, replace:

```kotlin
    @Inject lateinit var security: AppSecurity

    override fun onCreate() {
        super.onCreate()
        security.install(this)
    }
```

with:

```kotlin
    @Inject lateinit var security: AppSecurity

    @Inject lateinit var recurring: RecurringTriggers

    override fun onCreate() {
        super.onCreate()
        security.install(this)
        recurring.install()
    }
```

Create `real/.../data/RecurringPostWorker.kt` (path `android/app/src/real/kotlin/io/github/codex07/iou/data/RecurringPostWorker.kt`):

```kotlin
package io.github.codex07.iou.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit

/**
 * Background auto-posting (spec §5.7): every 6 hours, no constraints, one runner pass (which also updates the
 * notifications). It waits for the start-up load like every caller and for a restore through the store's lock.
 * Failures are kept as each schedule's `autoPostError`, so the work itself always succeeds.
 */
class RecurringPostWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, RateRefreshWorker.Backend::class.java).backend().runner.run()
        return Result.success()
    }

    companion object {
        const val NAME = "recurring-post"

        /** Schedules the pass once; an existing schedule is kept, so app starts don't reset its timing. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RecurringPostWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
```

In `real/.../di/BackendModule.kt`, add the import `io.github.codex07.iou.data.RecurringPostWorker` and replace:

```kotlin
        RealBackend.android(context).also { RateRefreshWorker.schedule(context) }
```

with:

```kotlin
        RealBackend.android(context).also {
            RateRefreshWorker.schedule(context)
            RecurringPostWorker.schedule(context)
        }
```

In `android/app/src/real/AndroidManifest.xml`, replace:

```xml
    <uses-permission android:name="android.permission.INTERNET" />
```

with:

```xml
    <uses-permission android:name="android.permission.INTERNET" />
    <!-- Recurring-expense notifications (spec §5.7); a runtime permission from Android 13 that the UI asks for. -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- [ ] **Step 5: Run the tests and the full check**

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.store.RecurringNoticePlannerTest" --tests "io.github.codex07.iou.data.RecurringNotifiedFileTest"`
Expected: PASS, 6 tests.

Run the full check (index, locked decision 9): both JVM suites, the three assembles, both lints. Expected: all tests pass in both flavors, lint 0 errors. If lint reports `NotificationPermission` or `MissingPermission` on `AndroidRecurringNotices`, the `areNotificationsEnabled()` check and the `SecurityException` catch are the intended handling: add `@SuppressLint("MissingPermission")` on `afterRun` with a comment saying so, and nothing else.

- [ ] **Step 6: Add the device checks and commit**

In `docs/manual-test-checklist.md`, append:

```markdown

## 9. Recurring expenses on the device (spec §5.7, AC 29 and 34)

Use the disposable `IOU_Integration_Review` emulator (see the local project notes), real debug build. Items 1–7 need the schedule screens (the friend's work, `docs/m2-ui-contract-changes.md`). Smoke check done on 2026-11-08 at `89a6d0d`: the app starts with no crash, the `RecurringPostWorker` job is registered, and its first run at enqueue gives no crash.

1. Create an auto-post schedule whose first date is today, close the app, open it again: the expense is posted once; reopening posts nothing more (AC 29).
2. Grant notifications. With the app closed, run the worker on demand: `adb shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler io.github.codex07.iou <job id>` (the id is on the `#RecurringPostWorker#` line of `adb shell dumpsys jobscheduler`). WorkManager delays a forced run before the 6-hour period is up (logcat: "Delaying execution ... before schedule"), so move the emulator clock forward 6 hours first. An auto-post date that came due is posted and one "Recurring expenses posted" notification appears.
3. A confirm-first schedule that becomes due shows one "due" notification; running the worker again does not repeat it; posting the date in the app removes it.
4. Turn app lock on and repeat 2–3: neither notification names a person, an amount or a description. Then, with a detailed due and a detailed posted notification showing (lock off), switch app lock on in the app: the due notification is redrawn without its description or amount and makes no sound, and the posted one is removed.
5. Deny or revoke the notification permission and run the worker: the date is still posted, nothing is shown.
6. Tap a due notification: `MainActivity` opens with `iou.open = "due"` (and `iou.schedule` for one schedule); `adb shell dumpsys activity activities | grep iou.open` shows the extra if the UI does not yet handle it.
7. With two confirm-first schedules due (two due notifications and their group, which alerted once), run the worker twice more and bring the app to the foreground: no further sound or vibration, and the notifications stay as they were (AC 34). Then post every due occurrence of one of them in the app: its notification goes, and the other schedule's notification is still shown (silently redrawn, without the group).

- [ ] Result recorded:
```

```bash
git add android/app/src docs/manual-test-checklist.md
git commit -m "Show recurring-expense notifications and post in the background every six hours"
```
