# M2 Part 5: Import, XLSX and the v0.2.0 Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Read `docs/superpowers/plans/2026-11-07-m2-00-index.md` first: its locked decisions and type inventory apply here. This part starts from the code as Parts 1–4 leave it.

**Goal:** The normalized CSV export carries schedules (`recurring_expenses`, `recurring_participants`) and the expenses' schedule links; the CSV import takes groups, memberships and schedules with their cascades and the `handled_through` / `auto_post_from` rules (spec §7.4); import undo covers them, validates the whole undo first and ties rule-4 entry steps together (spec §5.4); XLSX report and normalized workbooks are written with FastExcel (spec §7.3); a full export imports into an empty install and reproduces every row and net; the docs, the manual checks and the `v0.2.0` release close M2.

**Architecture:** The import keeps its M1 shape (reader → decoder → `ImportAnalysis` → one store write; `ImportUndo` → one store write). What M1 hard-coded for people and their handles becomes generic over **owners** (a new internal `io.Owners`: people own handles, groups own memberships), so groups cascade exactly like people. Schedules are classified as one unit with their participants; two of their columns get fixed rules instead of a choice: `handled_through` only moves forward (a forced change, shown in the new `ImportRow.forcedChanges`) and `auto_post_from` is never compared or taken. The cut-off rule and hold resumes need no import code: the import and the undo commit through `LedgerStore.writeWith`, which runs `RecurringOps.finalize` (Part 3). `ImportUndo` first builds every step, applies them all and validates once; only when that breaks a rule does it fall back to M1's step-by-step rounds, with tied entry steps tried together. XLSX lives in a new `io.LedgerWorkbook` that reuses the normalized tables and a typed `ReportLine` shared with the CSV report.

**Tech Stack:** Kotlin, Commons CSV 1.14, FastExcel 0.18.4 (writer; the reader only in tests), kotlinx.serialization, coroutines, JUnit 5, Kotest.

## Global Constraints

- **Normalized shape (spec §7.2):** schema 2 tables in order `people, payment_handles, groups, group_members, fx_snapshots, transfers, recurring_expenses, recurring_participants, expenses, expense_shares, expense_payers`. `recurring_participants` has `recurring_id, role, position, person_id, input_value` (`position` counted per role). `expenses` gains `recurring_id, recurring_on` after `note`. A schema-1 file has exactly the schema-1 tables, and its expenses have no schedule link.
- **Classification (spec §7.4):** by UUID, new / duplicate / conflict; sync columns ignored except the deleted state. Groups own memberships the way people own handles: the same cascades, the same "deleted with / restored with <owner>" preview rows, the same `deletedAt` 1 ns before the owner's for owned rows the file shows deleted apart. When the import deletes an owner, an owned row already deleted here that the file shows deleted with it (even as a duplicate) gets the owner's new `deletedAt` and is listed "deleted with <owner>"; undo gives it back its own. Under an owner kept deleted, an existing owned row the file shows active (or deleted with the owner) also gets the owner's local `deletedAt`, as a new one does, and one the file shows deleted apart is moved 1 ns before it; this covers duplicates too, whose `deletedAt` is never compared. Only kept and duplicate rows give the file's `deletedAt` (`keptDeletedAt`): a skipped invalid owner or owned row changes no grouping.
- **Schedules (spec §7.4):** a `recurring_expenses` row and its participants are one unit. `handled_through` is never a choice: the import writes the later of local and incoming, whatever the row's choice (a forced change in the preview); an older incoming value is ignored. `auto_post_from` is never compared or taken: a new schedule gets the import day, an existing one keeps its local value, and the cut-off rule (Part 3's `finalize`) sets it to the import day whenever the import writes a schedule that auto-posts afterwards.
- **Commit order (spec §7.5 step 6):** creates and updates: people, payment handles, groups, memberships, FX snapshots, schedules, entries; then deletions: entries, schedules, owned rows deleted on their own, then owners with their cascade.
- **Blame (spec §7.5 step 3):** people, handles, groups and memberships answer first (incoming active entries and schedules left out); entries and schedules answer for the rest. A new row (person, group or schedule) is left out together with the rows that reference it.
- **Undo (spec §5.4):** order entries, schedules, owned rows on their own, owners with their cascade. Every step the protection leaves is applied and the result validated once; only if that breaks a state invariant does undo go step by step, retrying waiting steps, then trying entry steps tied by a common removed member of their group together. An expense the batch created is deleted without raising its schedule's `handledThrough`. An owned row the batch created deleted into the cascade of an owner it didn't create is moved to the undo time, out of that cascade.
- **XLSX (spec §7.3):** report workbook = `Summary`, `Report`, one sheet per group in scope (member nets and settle plan); real date cells; amounts numeric with the currency's decimals. Normalized workbook = one sheet per normalized table; `*_minor`, `position`, `version`, `interval` and `input_value` numeric, decimal money columns numeric, `*_on` and `rate_date` date cells, booleans boolean. String cells get the `quote-prefix-v1` escape.
- **`api` (index, locked decision 1):** `ImportRow` gains `forcedChanges: List<FieldChange> = emptyList()` as its last property; no new `ImportRowChoice` subtype (the friend's `when` over it is exhaustive). No `ui/` file changes.
- **Release:** `v0.2.0` is tagged only with the owner's approval, after the whole-milestone review and a green CI run on the exact `main` commit.

## File map

| File | Change |
|---|---|
| `main/.../io/NormalizedTables.kt` | `recurringExpenses`, `recurringParticipants`, `ParticipantLine`, expense links, schema tables |
| `main/.../io/ImportDecoder.kt` | Groups, memberships, schedules, entries in groups, expense links |
| `main/.../io/Owners.kt` (new) | Owner of a handle or membership, rows an owner owns, `rowKey` |
| `main/.../io/ImportAnalysis.kt` | Owners generic; groups, memberships, schedules; forced `handled_through`; group nets in balance changes; `plan(..., today)` |
| `main/.../io/ImportUndo.kt` | Groups, memberships, schedules; whole set first; tied steps |
| `main/.../io/LedgerExport.kt` | `ReportLine`, `reportLines` |
| `main/.../io/LedgerWorkbook.kt` (new) | XLSX report and normalized workbooks |
| `main/.../api/TransferModels.kt` | `ImportRow.forcedChanges`; KDoc of `ImportCounts`, `BalanceChange` |
| `main/.../data/RealImportExportApi.kt` | `today` to the plan; XLSX exports |
| `main/.../store/ExportRules.kt` | XLSX allowed |
| `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts` | FastExcel; version name 0.2.0 |
| `test/.../testutil/ImportBench.kt` (new) | Store + real import without Room |
| `test/...` | `GroupImportTest`, `ScheduleImportTest`, `ImportUndoTest`, `LedgerWorkbookTest`, `RoundTripTest` (new); `NormalizedExportTest`, `ImportReaderTest`, `RealExportTest`, `ImportExportApiContract`, `ExportSamplesTest` |
| `docs/...` | Spec §5.4, §7.3–7.5, §14; `ui-contributor-guide.md`; `m2-ui-contract-changes.md`; `manual-test-checklist.md`; README; index type inventory |

Paths abbreviate `android/app/src/main/kotlin/io/github/codex07/iou/` as `main/...` and the test root likewise. Commands run from `android/` with `JAVA_HOME` set to the MS JDK 21 (`state.md`).

---

### Task 1: Schedules and expense links in the normalized export

**Files:**
- Modify: `main/.../io/NormalizedTables.kt`, `main/.../io/ImportDecoder.kt`
- Modify (tests): `test/.../io/NormalizedExportTest.kt`, `test/.../io/ImportReaderTest.kt`, `test/.../data/RealExportTest.kt`

**Interfaces:**
- Consumes: Part 1's `NormalizedTables` (`SCHEMA_VERSION = 2`, `expensePayers`, `tablesOf`, `CsvTable.legacyTextColumns`, `ImportFile.schemaVersion`); Part 3's `RecurringExpense`, `RecurringParticipant`, `ParticipantRole`, `RecurrenceUnit`, `Expense.recurringId` / `recurringOn`, `LedgerState.schedules`, `TestLedger.schedule(...)`, `TestLedger.expense(..., recurring, recurringOn)`.
- Produces: `NormalizedTables.recurringExpenses`, `NormalizedTables.recurringParticipants`, `data class ParticipantLine(schedule: RecurringExpense, position: Int, participant: RecurringParticipant)`; `expenses.csv` columns `recurring_id`, `recurring_on`; the decoder reads them (schema 2) and `RowReader.optionalDate(column)`. Schedules are decoded in Task 3; until then their tables are read and counted but not imported.

- [ ] **Step 1: Write the failing tests**

In `test/.../io/NormalizedExportTest.kt`, replace (Part 1's list):

```kotlin
            "transfers.csv", "expenses.csv", "expense_shares.csv", "expense_payers.csv", "manifest.json",
```

with:

```kotlin
            "transfers.csv", "recurring_expenses.csv", "recurring_participants.csv", "expenses.csv", "expense_shares.csv",
            "expense_payers.csv", "manifest.json",
```

replace:

```kotlin
                "transfers" to 2, "expenses" to 0, "expense_shares" to 0, "expense_payers" to 0,
```

with:

```kotlin
                "transfers" to 2, "recurring_expenses" to 0, "recurring_participants" to 0, "expenses" to 0, "expense_shares" to 0,
                "expense_payers" to 0,
```

and, in Part 1's test `expenses write their payer split and payer rows`, replace:

```kotlin
            "effect_currency,fx_snapshot_id,split_type,occurred_on,note,version,created_at,updated_at,author_id,deleted_at"
```

with:

```kotlin
            "effect_currency,fx_snapshot_id,split_type,occurred_on,note,recurring_id,recurring_on,version,created_at,updated_at," +
            "author_id,deleted_at"
```

Add this test to `NormalizedExportTest` (with the imports `io.kotest.matchers.string.shouldContain` and `java.time.LocalDate` where missing):

```kotlin
    @Test
    fun `schedules write their template and participants, and posted expenses their link`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        val rent = ledger.schedule(
            x, eur(90_000), listOf(ME, x), id = "rent", handledThrough = LocalDate.parse("2026-02-01"),
            autoPost = true, autoPostFrom = LocalDate.parse("2026-03-01"),
        )
        ledger.expense(
            x, eur(90_000), mapOf(ME to 45_000L, x to 45_000L), id = "e-1", recurring = rent,
            recurringOn = LocalDate.parse("2026-02-01"),
        )
        val out = java.io.ByteArrayOutputStream()
        LedgerExport("0.1.0").normalized(ledger.state, T0, excelFriendly = false, out)
        val entries = io.github.codex07.iou.testutil.Zips.read(out.toByteArray())

        entries.getValue("recurring_expenses.csv") shouldBe
            "id,group_id,description,category,note,total_minor,total,currency,payer_split,split_type,unit,interval,start_on," +
            "end_on,handled_through,auto_post_from,auto_post,version,created_at,updated_at,author_id,deleted_at\r\n" +
            "rent,,Schedule rent,,,90000,900.00,EUR,EQUAL,EQUAL,MONTH,1,2026-01-01,,2026-02-01,2026-03-01,true,1," +
            "2026-01-01T00:00:00Z,2026-01-01T00:00:00Z,person-0-me,\r\n"
        entries.getValue("recurring_participants.csv") shouldBe
            "recurring_id,role,position,person_id,input_value\r\n" +
            "rent,PAYER,0,person-x,\r\n" +
            "rent,SHARE,0,person-0-me,\r\n" +
            "rent,SHARE,1,person-x,\r\n"
        entries.getValue("expenses.csv").lines()[1] shouldContain ",rent,2026-02-01,1,"
    }
```

`TestLedger.schedule` names its description `Schedule <id>` and starts on `D0` (2026-01-01) by default (Part 3, Task 1).

In `test/.../io/ImportReaderTest.kt`, in Part 1's test `a schema-1 export reads with one EQUAL payer per expense`, replace:

```kotlin
            LedgerError.CorruptFile("Missing expense_payers.csv")
```

with:

```kotlin
            LedgerError.CorruptFile("Missing recurring_expenses.csv")
```

(the reader checks the schema's tables in file order, and `recurring_expenses` now comes first among the schema-2 additions). Add this test to `ImportReaderTest` (with the import `io.github.codex07.iou.testutil.D0` where missing):

```kotlin
    @Test
    fun `posted expenses keep their schedule link`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        val rent = ledger.schedule(x, eur(1_000), listOf(ME, x), id = "rent")
        ledger.expense(x, eur(1_000), mapOf(ME to 500L, x to 500L), id = "e-1", recurring = rent, recurringOn = D0)

        val expense = decode(export(ledger.state)).ledger.expenses.single().record

        expense shouldBe ledger.state.expenses.values.single()
        expense.recurringId shouldBe rent
        expense.recurringOn shouldBe D0
    }
```

In `test/.../data/RealExportTest.kt`, replace (Part 1's sum):

```kotlin
            state.expenses.values.sumOf { it.payers.size }
```

with:

```kotlin
            state.expenses.values.sumOf { it.payers.size } + state.schedules.size + state.schedules.values.sumOf { it.participants.size }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.io.*" --tests "io.github.codex07.iou.data.RealExportTest"`
Expected: FAIL: no `recurring_expenses.csv`, the expenses header has no link columns, and the decoded expense has no `recurringId`.

- [ ] **Step 3: Add the tables and the link columns**

In `main/.../io/NormalizedTables.kt`, add the imports `io.github.codex07.iou.ledger.model.ParticipantRole`, `io.github.codex07.iou.ledger.model.RecurringExpense` and `io.github.codex07.iou.ledger.model.RecurringParticipant`. After `data class PayerLine(...)`, add:

```kotlin

/** A schedule's payer or participant with its schedule and its position among the rows of its role. */
data class ParticipantLine(val schedule: RecurringExpense, val position: Int, val participant: RecurringParticipant)
```

Replace the `SCHEMA_VERSION` declaration with its KDoc:

```kotlin
    /** 2 from M2: `expense_payers` and `expenses.payer_split` replace `expenses.payer_person_id`. */
    const val SCHEMA_VERSION = 2
```

with:

```kotlin
    /**
     * 2 from M2: `expense_payers` and `expenses.payer_split` replace `expenses.payer_person_id`; schedules come in
     * `recurring_expenses` and `recurring_participants`, and expenses name the occurrence they fill
     * (`recurring_id`, `recurring_on`).
     */
    const val SCHEMA_VERSION = 2
```

In the `expenses` table, replace:

```kotlin
            Column("note", text = true) { it.note },
        ) + sync(),
        legacyTextColumns = setOf(SCHEMA_1_PAYER),
```

with:

```kotlin
            Column("note", text = true) { it.note },
            Column("recurring_id", text = true) { it.recurringId?.value },
            Column("recurring_on") { it.recurringOn?.toString() },
        ) + sync(),
        legacyTextColumns = setOf(SCHEMA_1_PAYER),
```

Add after the `transfers` table:

```kotlin

    val recurringExpenses = CsvTable(
        "recurring_expenses",
        listOf<Column<RecurringExpense>>(
            Column("id", text = true) { it.id.value },
            Column("group_id", text = true) { it.groupId?.value },
            Column("description", text = true) { it.description },
            Column("category") { it.category?.name },
            Column("note", text = true) { it.note },
        ) + money("total", "currency") { it.total } + listOf(
            Column("payer_split") { it.payerSplit.name },
            Column("split_type") { it.splitType.name },
            Column("unit") { it.unit.name },
            Column("interval") { it.interval.toString() },
            Column("start_on") { it.startOn.toString() },
            Column("end_on") { it.endOn?.toString() },
            Column("handled_through") { it.handledThrough?.toString() },
            Column("auto_post_from") { it.autoPostFrom.toString() },
            Column("auto_post") { it.autoPost.toString() },
        ) + sync(),
    ) { it.schedules.values.toList() }

    /** PAYER rows, then SHARE rows of each schedule, `position` counted per role (as in Room, Part 4). */
    val recurringParticipants = CsvTable(
        "recurring_participants",
        listOf<Column<ParticipantLine>>(
            Column("recurring_id", text = true) { it.schedule.id.value },
            Column("role") { it.participant.role.name },
            Column("position") { it.position.toString() },
            Column("person_id", text = true) { it.participant.personId.value },
            Column("input_value") { it.participant.inputValue?.toString() },
        ),
    ) { state ->
        state.schedules.values.flatMap { schedule ->
            ParticipantRole.entries.flatMap { role ->
                schedule.participants.filter { it.role == role }.mapIndexed { i, row -> ParticipantLine(schedule, i, row) }
            }
        }
    }
```

Replace (Part 1's list and `tablesOf`):

```kotlin
    val all: List<CsvTable<*>> =
        listOf(people, paymentHandles, groups, groupMembers, fxSnapshots, transfers, expenses, expenseShares, expensePayers)

    /** The tables a file of [schemaVersion] holds: every one of them, and no others (spec §7.4). */
    fun tablesOf(schemaVersion: Int): List<CsvTable<*>> = if (schemaVersion >= 2) all else all - expensePayers
```

with:

```kotlin
    val all: List<CsvTable<*>> = listOf(
        people, paymentHandles, groups, groupMembers, fxSnapshots, transfers, recurringExpenses, recurringParticipants,
        expenses, expenseShares, expensePayers,
    )

    /** The tables a file of [schemaVersion] holds: every one of them, and no others (spec §7.4). */
    fun tablesOf(schemaVersion: Int): List<CsvTable<*>> =
        if (schemaVersion >= 2) all else all - setOf(expensePayers, recurringExpenses, recurringParticipants)
```

The reader already takes the schema's tables from `tablesOf` and ignores the others (Part 1, Task 4), so a schema-1 file needs no recurring tables and a schema-2 file must have them.

- [ ] **Step 4: Decode the link**

In `main/.../io/ImportDecoder.kt`, add the import `io.github.codex07.iou.ledger.model.RecurringId`. The file ends with the `return Expense(...)` of `fun expense(...)`, the end of `RowReader` and the end of `ImportDecoder`. Replace those last lines:

```kotlin
                note = optional("note"),
                meta = meta(),
            )
        }
    }
}
```

with:

```kotlin
                note = optional("note"),
                meta = meta(),
                recurringId = if (schemaVersion < 2) null else optional("recurring_id")?.let(::RecurringId),
                recurringOn = if (schemaVersion < 2) null else optionalDate("recurring_on"),
            )
        }

        fun optionalDate(column: String): LocalDate? = optional(column)?.let { date(column) }
    }
}
```

(`transfer()` ends with the same first three lines followed by a blank line and `fun expense(`; change only the block at the end of the file.) An imported link to a schedule that is neither local nor imported breaks rule 15 and makes the expense an invalid row; Task 3 imports the schedules themselves.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.io.*" --tests "io.github.codex07.iou.data.RealExportTest"`
Expected: PASS.

Then: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest`
Expected: PASS. The M1 import tests export and re-import ledgers without schedules, which now carry two empty tables.

- [ ] **Step 6: Commit**

```bash
git add android/app/src
git commit -m "Export schedules, their participants and the expenses' schedule links"
```

---
### Task 2: Groups and memberships import with their cascade

**Files:**
- Create: `main/.../io/Owners.kt`, `test/.../testutil/ImportBench.kt`, `test/.../io/GroupImportTest.kt`
- Modify: `main/.../io/ImportDecoder.kt`, `main/.../io/ImportAnalysis.kt`, `main/.../data/RealImportExportApi.kt`, `main/.../api/TransferModels.kt`
- Modify (tests): `test/.../io/ImportReaderTest.kt`

**Interfaces:**
- Consumes: Task 1's tables and `optionalDate`; Part 3's `LedgerState.entity(type, id)`, `WriteContext.today`, `RecurringOps.finalize` in `LedgerStore.writeWith`; `IdentityResolver`, `LedgerEngine.compute` (`pairwise`, `groupNets`, `net`, `myGroupNet`).
- Produces:
  - `IncomingLedger(people, handles, fxSnapshots, transfers, expenses, groups, members)` (the two new lists last, default empty); `ImportDecoder.GROUPS_IN_M2` is gone.
  - `internal val LedgerEntity.rowKey: ImportRowKey`; `internal object Owners { ownerOf(entity): ImportRowKey?; owned(state, owner): List<LedgerEntity> }`.
  - `ImportAnalysis.groups`, `ImportAnalysis.members`; `ImportAnalysis.plan(take, batch, now, today: LocalDate)`; `balanceChanges` also lists my net per group, labelled `"<group name> (group)"`.
  - test: `class ImportBench(initial: LedgerState, now: Instant = T0 + 1 day)` with `store`, `io`, `groups`, `people`, `ledger`, `state`, `today`, `basis()`, `token(entity)`, `export(source, name, edit)`, `preview(source, name, edit)`, `commit(preview, choices)`, `import(source, choices)`, `undo(batch)` and the member extension `LedgerResult<T>.ok()`.

- [ ] **Step 1: Write the test bench and the failing tests**

Create `test/.../testutil/ImportBench.kt`:

```kotlin
package io.github.codex07.iou.testutil

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.EditToken
import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.ImportIdentity
import io.github.codex07.iou.api.ImportPreview
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.UndoImportResult
import io.github.codex07.iou.data.RealImportExportApi
import io.github.codex07.iou.io.LedgerExport
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerGeneration
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.RevisionId
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.StoreGroupsApi
import io.github.codex07.iou.store.StoreLedgerApi
import io.github.codex07.iou.store.StorePeopleApi
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking

/**
 * One ledger in a [LedgerStore] without persistence or runner, with the real import, export and undo on top
 * (spec §7.4, §7.5, §5.4). Every row of [initial] gets a CREATED revision, as a ledger built through the api
 * would have, so undo can read the pre-import state of the rows an import changed. The clock stands at [now]
 * (UTC); [today] is its date. Writes commit through `LedgerStore.writeWith`, so the cut-off rule and hold
 * resumes apply as in the app.
 */
class ImportBench(initial: LedgerState, val now: Instant = T0.plusSeconds(86_400)) {
    val documents = FileDocuments(createTempDirectory("iou-import").toFile())
    val store = LedgerStore(
        Clock.fixed(now, ZoneOffset.UTC), SequentialIds("bench"), DeviceId("bench"),
        initial = LedgerContent(initial, revisions = created(initial)),
    )
    val io = RealImportExportApi(store, documents, LedgerExport("test"))
    val groups = StoreGroupsApi(store)
    val people = StorePeopleApi(store)
    val ledger = StoreLedgerApi(store)
    val today: LocalDate get() = store.today()

    val state: LedgerState get() = runBlocking { store.loaded().state }

    fun <T> LedgerResult<T>.ok(): T = (this as? LedgerResult.Ok)?.value ?: error("Expected Ok, was $this")

    suspend fun basis(): LedgerGeneration = store.loaded().generation

    suspend fun token(entity: LedgerEntity): EditToken = EditToken(basis(), entity.meta.version)

    /** [source] as a normalized export, changed by [edit], stored as the document [name]. */
    fun export(source: LedgerState, name: String = "export.zip", edit: (ByteArray) -> ByteArray = { it }): DocumentRef {
        val bytes = ByteArrayOutputStream().also { LedgerExport("test").normalized(source, T0, excelFriendly = false, it) }.toByteArray()
        val ref = DocumentRef(name)
        documents.file(ref).also { requireNotNull(it.parentFile).mkdirs() }.writeBytes(edit(bytes))
        return ref
    }

    /** The preview of [source]'s export; an export from another identity is confirmed as the user's own. */
    suspend fun preview(source: LedgerState, name: String = "export.zip", edit: (ByteArray) -> ByteArray = { it }): ImportPreview {
        val preview = io.parseImport(export(source, name, edit)).ok()
        return if (preview.identity is ImportIdentity.NeedsConfirmation) io.confirmIdentity(preview, accept = true).ok() else preview
    }

    suspend fun commit(preview: ImportPreview, choices: ImportChoices = ImportChoices()): ImportBatchId =
        io.commitImport(io.applyChoices(preview, choices), choices).ok()

    suspend fun import(source: LedgerState, choices: ImportChoices = ImportChoices()): ImportBatchId = commit(preview(source), choices)

    suspend fun undo(batch: ImportBatchId): UndoImportResult = io.undoImport(batch).ok()

    private companion object {
        fun created(state: LedgerState): List<EntryRevision> =
            (state.people.values + state.handles.values + state.groups.values + state.members.values + state.entries + state.schedules.values)
                .mapIndexed { i, entity ->
                    EntryRevision(
                        id = RevisionId("initial-$i"), entityType = entity.entityType, entityId = entity.entityId,
                        version = entity.meta.version, action = RevisionAction.CREATED, authorId = ME, deviceId = DeviceId("bench"),
                        at = T0, snapshot = entity,
                    )
                }
    }
}
```

Create `test/.../io/GroupImportTest.kt`:

```kotlin
package io.github.codex07.iou.io

import io.github.codex07.iou.api.BalanceChange
import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.ImportRowChoice
import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.testutil.ImportBench
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Group rows through the import (spec §7.4, AC M2-14, 16, 19). */
class GroupImportTest {

    private fun empty(): LedgerState = TestLedger().apply { me() }.state

    /**
     * Trip (KEEP EUR): me and Bea active, Al removed. I paid €100 for Al and Al paid it back, so Al's net is 0;
     * Bea paid €60 split with me.
     */
    private fun trip(): LedgerState = TestLedger().run {
        me()
        val al = person("Al")
        val bea = person("Bea")
        val trip = group("Trip", members = listOf(ME, bea))
        member(trip, al, removed = true)
        expense(ME, eur(10_000), mapOf(al to 10_000L), group = trip, id = "e-al")
        transfer(al, ME, eur(10_000), group = trip, kind = TransferKind.REPAYMENT, id = "t-al")
        expense(bea, eur(6_000), mapOf(ME to 3_000L, bea to 3_000L), group = trip, id = "e-bea")
        state
    }

    @Test
    fun `a group with a removed member and their historical entries imports as is and reproduces every net`() = runTest {
        val source = trip()
        val bench = ImportBench(empty())

        val preview = bench.preview(source)

        preview.invalidRows shouldBe emptyList()
        preview.balanceChanges shouldContain BalanceChange("Trip (group)", emptyList(), listOf(eur(-3_000)))
        bench.commit(preview)
        LedgerEngine.compute(bench.state).groupNets shouldBe LedgerEngine.compute(source).groupNets
        LedgerEngine.compute(bench.state).pairwise shouldBe LedgerEngine.compute(source).pairwise
        bench.state.members.values.single { it.personId.value == "person-al" }.removedAt shouldBe
            source.members.values.single { it.personId.value == "person-al" }.removedAt
    }

    @Test
    fun `a deleted group comes with its memberships under one local deletedAt, and restoring it brings them back`() = runTest {
        val source = TestLedger().run {
            me()
            val x = person("X")
            group("Old", members = listOf(ME, x), deleted = true)
            state
        }
        val bench = ImportBench(empty())

        bench.import(source)

        val old = bench.state.groups.getValue(GroupId("group-old"))
        val members = bench.state.members.values.filter { it.groupId == old.id }
        members.size shouldBe 2
        members.map { it.meta.deletedAt }.toSet() shouldBe setOf(old.meta.deletedAt!!)
        with(bench) { groups.restoreGroup(old.id, token(old)).ok() }
        bench.state.members.values.filter { it.groupId == old.id }.all { !it.isDeleted } shouldBe true
    }

    @Test
    fun `taking a group deleted in the export shows its memberships as deleted with it and deletes them together`() = runTest {
        val local = TestLedger().run {
            me()
            val x = person("X")
            group("Flat", members = listOf(ME, x))
            state
        }
        val source = TestLedger().run {
            me()
            val x = person("X")
            group("Flat", members = listOf(ME, x), deleted = true)
            state
        }
        val bench = ImportBench(local)
        val flat = ImportRowKey(EntityType.GROUP, "group-flat")
        val preview = bench.preview(source)
        val take = ImportChoices(takeIncoming = setOf(flat))

        preview.rows.single { it.key == flat }.choice shouldBe ImportRowChoice.KeepOrTake
        val taken = bench.io.applyChoices(preview, take)
        taken.rows.filter { it.key.type == EntityType.GROUP_MEMBER }.map { it.choice }.toSet() shouldBe
            setOf(ImportRowChoice.DeletedWith("Flat"))
        taken.rows.count { it.key.type == EntityType.GROUP_MEMBER } shouldBe 2

        bench.commit(preview, take)

        val group = bench.state.groups.getValue(GroupId("group-flat"))
        group.isDeleted shouldBe true
        bench.state.members.values.filter { it.groupId == group.id }.map { it.meta.deletedAt }.toSet() shouldBe setOf(group.meta.deletedAt!!)
        bench.store.loaded().revisions.filter { it.entityType == EntityType.GROUP_MEMBER && it.action == RevisionAction.DELETED }
            .map { it.reason } shouldBe List(2) { "Import conflict resolved from export.zip (source v1) with Flat" }
    }

    @Test
    fun `taking a person's deletion moves a handle deleted here earlier into their cascade when the export deleted them together`() = runTest {
        val x = PersonId("person-x")
        val handleId = HandleId("handle-x")
        val handleKey = ImportRowKey(EntityType.PAYMENT_HANDLE, handleId.value)
        val local = TestLedger().run {
            me()
            handle(person("X"), deleted = true, id = handleId.value)
            state
        }.let { state ->
            val handle = state.handles.getValue(handleId)
            state.with(handle.copy(meta = handle.meta.copy(deletedAt = T0.minusSeconds(3_600))))
        }
        val source = TestLedger().run {
            me()
            handle(person("X", deleted = true), deleted = true, id = handleId.value)
            state
        }
        val bench = ImportBench(local)
        val take = ImportChoices(takeIncoming = setOf(ImportRowKey(EntityType.PERSON, x.value)))
        val preview = bench.preview(source)

        bench.io.applyChoices(preview, take).rows.single { it.key == handleKey }.choice shouldBe ImportRowChoice.DeletedWith("X")
        bench.commit(preview, take)

        val person = bench.state.people.getValue(x)
        person.meta.deletedAt shouldBe bench.now
        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe bench.now
        with(bench) { people.restorePerson(x, token(person)).ok() }
        bench.state.handles.getValue(handleId).isDeleted shouldBe false
    }

    @Test
    fun `a handle the export shows active joins the cascade of a person kept deleted here`() = runTest {
        val x = PersonId("person-x")
        val handleId = HandleId("handle-x")
        val handleKey = ImportRowKey(EntityType.PAYMENT_HANDLE, handleId.value)
        val local = TestLedger().run {
            me()
            handle(person("X", deleted = true), deleted = true, id = handleId.value)
            state
        }.let { state ->
            val handle = state.handles.getValue(handleId)
            state.with(handle.copy(meta = handle.meta.copy(deletedAt = T0.minusSeconds(3_600))))
        }
        val source = TestLedger().run {
            me()
            handle(person("X"), id = handleId.value)
            state
        }
        val bench = ImportBench(local)
        val preview = bench.preview(source)

        preview.rows.single { it.key == handleKey }.choice shouldBe ImportRowChoice.DeletedWith("X")
        bench.commit(preview)

        val person = bench.state.people.getValue(x)
        person.meta.deletedAt shouldBe T0
        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe T0
        with(bench) { people.restorePerson(x, token(person)).ok() }
        bench.state.handles.getValue(handleId).isDeleted shouldBe false
    }

    @Test
    fun `a duplicate handle the export shows deleted apart leaves the cascade of a person kept deleted here`() = runTest {
        val x = PersonId("person-x")
        val handleId = HandleId("handle-x")
        val local = TestLedger().run {
            me()
            handle(person("X", deleted = true), deleted = true, id = handleId.value)
            state
        }
        val source = local.let { state ->
            val handle = state.handles.getValue(handleId)
            state.with(handle.copy(meta = handle.meta.copy(deletedAt = T0.minusSeconds(3_600))))
        }
        val bench = ImportBench(local)

        bench.import(source)

        val person = bench.state.people.getValue(x)
        person.meta.deletedAt shouldBe T0
        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe T0.minusNanos(1)
        with(bench) { people.restorePerson(x, token(person)).ok() }
        bench.state.handles.getValue(handleId).isDeleted shouldBe true
    }

    @Test
    fun `a skipped invalid handle keeps its own deletedAt under a person kept deleted`() = runTest {
        val x = PersonId("person-x")
        val handleId = HandleId("handle-x")
        val earlier = T0.minusSeconds(3_600)
        val local = TestLedger().run {
            me()
            handle(person("X", deleted = true), deleted = true, id = handleId.value)
            state
        }.let { state ->
            val handle = state.handles.getValue(handleId)
            state.with(handle.copy(meta = handle.meta.copy(deletedAt = earlier)))
        }
        val source = TestLedger().run {
            me()
            // Right shape, wrong mod-97 checksum.
            handle(person("X"), value = "DE00000000000000000000", id = handleId.value)
            state
        }
        val bench = ImportBench(local)
        val preview = bench.preview(source)

        preview.invalidRows.size shouldBe 1
        bench.commit(preview, ImportChoices(skipInvalidRows = true))

        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe earlier
        with(bench) { people.restorePerson(x, token(state.people.getValue(x))).ok() }
        bench.state.handles.getValue(handleId).isDeleted shouldBe true
    }

    @Test
    fun `a skipped invalid person row leaves its handle in the local cascade`() = runTest {
        val x = PersonId("person-x")
        val handleId = HandleId("handle-x")
        val local = TestLedger().run {
            me()
            handle(person("X", deleted = true), deleted = true, id = handleId.value)
            state
        }
        val source = TestLedger().run {
            me()
            // Active with a blank name: rejected, so its missing deletedAt says nothing about the handle's grouping.
            handle(person(" ", id = x.value), deleted = true, id = handleId.value)
            state
        }
        val bench = ImportBench(local)
        val preview = bench.preview(source)

        preview.invalidRows.size shouldBe 1
        bench.commit(preview, ImportChoices(skipInvalidRows = true))

        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe T0
        with(bench) { people.restorePerson(x, token(state.people.getValue(x))).ok() }
        bench.state.handles.getValue(handleId).isDeleted shouldBe false
    }

    @Test
    fun `a group entry naming someone without a membership is an invalid row`() = runTest {
        val source = TestLedger().run {
            me()
            val x = person("X")
            val stranger = person("Stranger")
            val trip = group("Trip", members = listOf(ME, x))
            expense(ME, eur(1_000), mapOf(stranger to 1_000L), group = trip, id = "e-1")
            state
        }
        val bench = ImportBench(empty())

        val invalid = bench.preview(source).invalidRows

        invalid.map { it.table to it.reason.substringBefore(':') } shouldBe listOf("expenses" to "breaks rule 3")
    }
}
```

The membership rows of the third test are conflicts of their own (the file shows them deleted with the group), so they are listed before the choice too; taking the group turns their choice into "deleted with Flat". The revision reasons come from the group's delete cascade: `reasonConflict` of the group, then " with Flat". In the fourth test the handle was deleted an hour before its person here, but the export shows both deleted at once: the handle is a duplicate (only its `deletedAt` differs, and sync columns are not compared), yet deleting the person moves it into the person's cascade (`joinsCascade`, `Writer.regroup`), so restoring the person brings it back as in the source. In the fifth test the person stays deleted (no choice taken) and the export shows the handle active: the handle takes the person's local `deletedAt` (`Writer.regroup`), so it comes back with the person. In the sixth test both rows are duplicates (only the handle's `deletedAt` differs), yet the export shows the handle deleted before the person: under the person kept deleted it moves 1 ns before the person's `deletedAt`, so restoring the person leaves it deleted. In the seventh test the export's handle fails its IBAN check and the invalid row is skipped: it writes nothing, and the file's `deletedAt` for it is dropped before `plan` (`keptDeletedAt`), so the local handle stays outside the person's cascade. In the eighth test the export's person has a blank name and is skipped as invalid: without its row the file gives no owner time, so the duplicate handle is left where it is, in the local person's cascade.

In `test/.../io/ImportReaderTest.kt`, replace the test `an export decodes to the same records, except group rows until M2` (from its `@Test` line through its closing brace) with:

```kotlin
    @Test
    fun `an export decodes to the same records`() {
        val state = FakeApis.store(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("seed"))
            .also { runBlocking { SeedData.populate(it) } }
            .let { runBlocking { it.loaded().state } }

        for (excel in listOf(false, true)) {
            val decoded = decode(export(state, excel))
            decoded.ledger.people.map { it.record } shouldContainExactlyInAnyOrder state.people.values
            decoded.ledger.handles.map { it.record } shouldContainExactlyInAnyOrder state.handles.values
            decoded.ledger.groups.map { it.record } shouldContainExactlyInAnyOrder state.groups.values
            decoded.ledger.members.map { it.record } shouldContainExactlyInAnyOrder state.members.values
            decoded.ledger.fxSnapshots.map { it.record } shouldContainExactlyInAnyOrder state.fxSnapshots.values
            decoded.ledger.transfers.map { it.record } shouldContainExactlyInAnyOrder state.transfers.values
            decoded.ledger.expenses.map { it.record } shouldContainExactlyInAnyOrder state.expenses.values
            decoded.invalid shouldBe emptyList()
        }
    }
```

If Part 4 changed how this test builds the seeded store (for example through `FakeApis.seededStore(...)`), keep that line as Part 4 left it; only the assertions change.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.io.*"`
Expected: FAIL: `decoded.ledger.groups` and `ImportBench` don't compile against the current decoder and analysis, and group rows are still invalid ("group rows are imported from M2 on").

- [ ] **Step 3: Owners**

Create `main/.../io/Owners.kt`:

```kotlin
package io.github.codex07.iou.io

import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PaymentHandle

/** A row's key, as import previews and undo results name it. */
internal val LedgerEntity.rowKey: ImportRowKey get() = ImportRowKey(entityType, entityId)

/**
 * Owners and the rows they own (spec §5.2): a person owns their payment handles, a group its memberships.
 * Import (§7.4) and import undo (§5.4) move owned rows together with their owner through the cascade.
 */
internal object Owners {

    /** The owner of a payment handle (its person) or a membership (its group); null for any other row. */
    fun ownerOf(entity: LedgerEntity): ImportRowKey? = when (entity) {
        is PaymentHandle -> ImportRowKey(EntityType.PERSON, entity.personId.value)
        is GroupMember -> ImportRowKey(EntityType.GROUP, entity.groupId.value)
        else -> null
    }

    /** Every row [owner] owns in [state], deleted ones included. */
    fun owned(state: LedgerState, owner: ImportRowKey): List<LedgerEntity> = when (owner.type) {
        EntityType.PERSON -> state.handles.values.filter { it.personId.value == owner.id }
        EntityType.GROUP -> state.members.values.filter { it.groupId.value == owner.id }
        else -> emptyList()
    }
}
```

- [ ] **Step 4: Decode groups, memberships and entries in groups**

Replace the whole of `main/.../io/ImportDecoder.kt` with:

```kotlin
package io.github.codex07.iou.io

import io.github.codex07.iou.api.InvalidRow
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.toLedgerError
import io.github.codex07.iou.ledger.engine.FxConverter
import io.github.codex07.iou.ledger.model.Category
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.ExpenseShare
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.FxSnapshotId
import io.github.codex07.iou.ledger.model.FxSource
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.HandleType
import io.github.codex07.iou.ledger.model.MembershipId
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.SyncMeta
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.ledger.model.TransferMethod
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import io.github.codex07.iou.money.MoneyParseResult
import io.github.codex07.iou.money.MoneyParser
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** An incoming record, the row it came from and the version it had in the source. */
data class Incoming<out T>(val table: String, val row: Int, val record: T, val sourceVersion: Int)

/** Every record of an import that decoded. Expenses carry their shares and payers, in `position` order. */
data class IncomingLedger(
    val people: List<Incoming<Person>> = emptyList(),
    val handles: List<Incoming<PaymentHandle>> = emptyList(),
    val fxSnapshots: List<Incoming<FxSnapshot>> = emptyList(),
    val transfers: List<Incoming<Transfer>> = emptyList(),
    val expenses: List<Incoming<Expense>> = emptyList(),
    val groups: List<Incoming<Group>> = emptyList(),
    val members: List<Incoming<GroupMember>> = emptyList(),
)

/** The decoded records and the rows that didn't decode. */
data class DecodedImport(val ledger: IncomingLedger, val invalid: List<InvalidRow>)

/**
 * Turns the rows of an [ImportFile] into records (spec §7.5 step 2–3, per row). Every problem becomes an
 * [InvalidRow] with its table, row number and reason; nothing is thrown. Amounts are parsed from their
 * decimal strings with [MoneyParser] and must match their `*_minor` column. An owned row (share, payer) that
 * fails is reported on its own row, and its owner is left out.
 */
object ImportDecoder {

    private class RowError(val reason: String, val error: LedgerError?) : Exception(reason)

    private class OwnedRowErrors(val rows: List<InvalidRow>) : Exception()

    fun decode(file: ImportFile): DecodedImport {
        val invalid = mutableListOf<InvalidRow>()

        fun <T> table(table: CsvTable<*>, id: (CsvRow) -> String, decode: RowReader.() -> T): List<Incoming<T>> {
            val seen = mutableSetOf<String>()
            return file.rows(table).mapNotNull { row ->
                try {
                    val reader = RowReader(row)
                    val rowId = id(row)
                    if (!seen.add(rowId)) throw RowError("duplicate id $rowId", LedgerError.CorruptData("duplicate id $rowId"))
                    Incoming(table.name, row.number, reader.decode(), reader.sourceVersion())
                } catch (e: OwnedRowErrors) {
                    invalid += e.rows
                    null
                } catch (e: RowError) {
                    invalid += InvalidRow(table.name, row.number, e.reason, e.error)
                    null
                }
            }
        }

        /** [owned]'s rows by their owner's ID; rows whose owner isn't in [owners] are invalid (spec §7.4). */
        fun ownedRows(owned: CsvTable<*>, ownerColumn: String, owners: CsvTable<*>, orphan: String, reason: String): Map<String, List<CsvRow>> {
            val byOwner = file.rows(owned).groupBy { it.cells[ownerColumn].orEmpty() }
            val ownerIds = file.rows(owners).map { it.cells["id"].orEmpty() }.toSet()
            for ((ownerId, rows) in byOwner) {
                if (ownerId !in ownerIds) {
                    rows.forEach { invalid += InvalidRow(owned.name, it.number, reason, LedgerError.CorruptData(orphan)) }
                }
            }
            return byOwner
        }

        val id = { row: CsvRow -> row.cells["id"].orEmpty() }
        val people = table(NormalizedTables.people, id) { person() }
        val handles = table(NormalizedTables.paymentHandles, id) { handle() }
        val groups = table(NormalizedTables.groups, id) { group() }
        val members = table(NormalizedTables.groupMembers, id) { member() }
        val snapshots = table(NormalizedTables.fxSnapshots, id) { snapshot() }
        val transfers = table(NormalizedTables.transfers, id) { transfer() }

        val shareRows = ownedRows(
            NormalizedTables.expenseShares, "expense_id", NormalizedTables.expenses, "orphan share", "share of an expense that isn't in the file",
        )
        val payerRows = ownedRows(
            NormalizedTables.expensePayers, "expense_id", NormalizedTables.expenses, "orphan payer", "payer of an expense that isn't in the file",
        )
        val expenses = table(NormalizedTables.expenses, id) {
            val expenseId = cells["id"].orEmpty()
            expense(shareRows[expenseId].orEmpty(), payerRows[expenseId].orEmpty(), file.schemaVersion)
        }
        return DecodedImport(IncomingLedger(people, handles, snapshots, transfers, expenses, groups, members), invalid)
    }

    /** Reads typed values from one row; every failure is a [RowError] naming the column. */
    private class RowReader(row: CsvRow) {
        val cells = row.cells

        private fun fail(column: String, problem: String, error: LedgerError? = LedgerError.InvalidField(column)): Nothing =
            throw RowError("$column: $problem", error)

        fun optional(column: String): String? = cells[column]?.takeIf { it.isNotEmpty() }

        fun required(column: String): String = optional(column) ?: fail(column, "missing")

        fun long(column: String): Long = required(column).toLongOrNull() ?: fail(column, "not a whole number")

        fun int(column: String): Int = required(column).toIntOrNull() ?: fail(column, "not a whole number")

        fun optionalLong(column: String): Long? = optional(column)?.let { it.toLongOrNull() ?: fail(column, "not a whole number") }

        fun bool(column: String): Boolean = when (required(column)) {
            "true" -> true
            "false" -> false
            else -> fail(column, "not true or false")
        }

        fun instant(column: String): Instant = parseInstant(column, required(column))

        fun optionalInstant(column: String): Instant? = optional(column)?.let { parseInstant(column, it) }

        private fun parseInstant(column: String, text: String): Instant =
            try { Instant.parse(text) } catch (_: DateTimeParseException) { fail(column, "not an ISO-8601 instant") }

        fun date(column: String): LocalDate =
            try { LocalDate.parse(required(column)) } catch (_: DateTimeParseException) { fail(column, "not a yyyy-MM-dd date") }

        fun optionalDate(column: String): LocalDate? = optional(column)?.let { date(column) }

        fun currency(column: String): CurrencyCode = required(column).let { code ->
            CurrencyCode.parse(code) ?: fail(column, "unknown currency $code", LedgerError.UnknownCurrency(code))
        }

        fun optionalCurrency(column: String): CurrencyCode? = optional(column)?.let { currency(column) }

        inline fun <reified E : Enum<E>> enum(column: String): E = required(column).let { name ->
            enumValues<E>().firstOrNull { it.name == name } ?: fail(column, "unknown value $name")
        }

        inline fun <reified E : Enum<E>> optionalEnum(column: String): E? = optional(column)?.let { enum<E>(column) }

        /** `<prefix>` as a decimal string in [currencyColumn], checked against `<prefix>_minor`. */
        fun money(prefix: String, currencyColumn: String): Money = amount(prefix, required(prefix), currency(currencyColumn), long("${prefix}_minor"))

        fun amount(column: String, text: String, currency: CurrencyCode, minor: Long): Money {
            val money = when (val parsed = MoneyParser.parse(text, currency)) {
                is MoneyParseResult.Ok -> parsed.money
                is MoneyParseResult.Err -> fail(column, "invalid amount $text", parsed.error.toLedgerError())
            }
            if (money.amountMinor != minor) fail(column, "$text doesn't match ${column}_minor $minor", LedgerError.CorruptData("$column mismatch"))
            return money
        }

        fun meta(): SyncMeta = SyncMeta(
            createdAt = instant("created_at"),
            updatedAt = instant("updated_at"),
            authorId = PersonId(required("author_id")),
            deletedAt = optionalInstant("deleted_at"),
            version = sourceVersion(),
        )

        fun sourceVersion(): Int = cells["version"]?.let { text ->
            text.toIntOrNull()?.takeIf { it >= 1 } ?: fail("version", "not a positive whole number")
        } ?: 1

        /** Reads each of [rows] with [decode]; a failing row is reported on its own row into [errors] and left out. */
        fun <T> owned(table: CsvTable<*>, rows: List<CsvRow>, errors: MutableList<InvalidRow>, decode: RowReader.() -> T): List<T> =
            rows.mapNotNull { row ->
                try {
                    RowReader(row).decode()
                } catch (e: RowError) {
                    errors += InvalidRow(table.name, row.number, e.reason, e.error)
                    null
                }
            }

        fun person() = Person(
            id = PersonId(required("id")),
            displayName = required("display_name"),
            isMe = bool("is_me"),
            note = optional("note"),
            defaultCurrency = optionalCurrency("default_currency"),
            archivedAt = optionalInstant("archived_at"),
            mergedIntoId = optional("merged_into_id")?.let(::PersonId),
            meta = meta(),
        )

        fun handle() = PaymentHandle(
            id = HandleId(required("id")),
            personId = PersonId(required("person_id")),
            type = enum<HandleType>("type"),
            value = required("value"),
            holderName = optional("holder_name"),
            bic = optional("bic"),
            label = required("label"),
            isDefault = bool("is_default"),
            meta = meta(),
        )

        fun group() = Group(
            id = GroupId(required("id")),
            name = required("name"),
            defaultCurrency = currency("default_currency"),
            fxMode = enum<FxMode>("fx_mode"),
            archivedAt = optionalInstant("archived_at"),
            meta = meta(),
        )

        fun member() = GroupMember(
            id = MembershipId(required("id")),
            groupId = GroupId(required("group_id")),
            personId = PersonId(required("person_id")),
            joinedAt = instant("joined_at"),
            removedAt = optionalInstant("removed_at"),
            meta = meta(),
        )

        fun snapshot(): FxSnapshot {
            val rate = required("rate")
            if (FxConverter.validateRate(rate) is LedgerResult.Err) fail("rate", "invalid rate $rate", LedgerError.InvalidRate(rate))
            return FxSnapshot(
                id = FxSnapshotId(required("id")),
                baseCurrency = currency("base_currency"),
                quoteCurrency = currency("quote_currency"),
                rate = rate,
                rateDate = date("rate_date"),
                source = enum<FxSource>("source"),
                isStale = bool("is_stale"),
                fetchedAt = instant("fetched_at"),
                paidMinor = optionalLong("paid_minor"),
                chargedMinor = optionalLong("charged_minor"),
            )
        }

        fun transfer() = Transfer(
            id = EntryId(required("id")),
            fromPersonId = PersonId(required("from_person_id")),
            toPersonId = PersonId(required("to_person_id")),
            amount = money("amount", "currency"),
            effectAmount = money("effect_amount", "effect_currency"),
            fxSnapshotId = optional("fx_snapshot_id")?.let(::FxSnapshotId),
            kind = enum<TransferKind>("kind"),
            method = enum<TransferMethod>("method"),
            accountLabel = optional("account_label"),
            groupId = optional("group_id")?.let(::GroupId),
            occurredOn = date("occurred_on"),
            note = optional("note"),
            meta = meta(),
        )

        /**
         * An expense with its owned rows. A schema-1 file names one payer in `payer_person_id`, which becomes one
         * EQUAL payer paying the effect total, and has no schedule link (spec §7.4); a schema-2 file has
         * `payer_split`, `expense_payers` rows and `recurring_id` / `recurring_on`.
         */
        fun expense(shareRows: List<CsvRow>, payerRows: List<CsvRow>, schemaVersion: Int): Expense {
            val effectTotal = money("effect_total", "effect_currency")
            val errors = mutableListOf<InvalidRow>()
            val payers = if (schemaVersion < 2) {
                ExpensePayer.single(PersonId(required(NormalizedTables.SCHEMA_1_PAYER)), effectTotal.amountMinor)
            } else {
                owned(NormalizedTables.expensePayers, payerRows, errors) {
                    long("position") to ExpensePayer(
                        personId = PersonId(required("person_id")),
                        paidMinor = amount("paid", required("paid"), effectTotal.currency, long("paid_minor")).amountMinor,
                        inputValue = optionalLong("input_value"),
                    )
                }.sortedBy { it.first }.map { it.second }
            }
            val shares = owned(NormalizedTables.expenseShares, shareRows, errors) {
                long("position") to ExpenseShare(
                    personId = PersonId(required("person_id")),
                    shareMinor = amount("share", required("share"), effectTotal.currency, long("share_minor")).amountMinor,
                    inputValue = optionalLong("input_value"),
                )
            }.sortedBy { it.first }.map { it.second }
            if (errors.isNotEmpty()) throw OwnedRowErrors(errors)
            return Expense(
                id = EntryId(required("id")),
                groupId = optional("group_id")?.let(::GroupId),
                payerSplit = if (schemaVersion < 2) PayerSplit.EQUAL else enum<PayerSplit>("payer_split"),
                payers = payers,
                description = required("description"),
                category = optionalEnum<Category>("category"),
                total = money("total", "currency"),
                effectTotal = effectTotal,
                fxSnapshotId = optional("fx_snapshot_id")?.let(::FxSnapshotId),
                splitType = enum<SplitType>("split_type"),
                shares = shares,
                occurredOn = date("occurred_on"),
                note = optional("note"),
                meta = meta(),
                recurringId = if (schemaVersion < 2) null else optional("recurring_id")?.let(::RecurringId),
                recurringOn = if (schemaVersion < 2) null else optionalDate("recurring_on"),
            )
        }
    }
}
```

What changed against the code Task 1 leaves: `GROUPS_IN_M2` and `noGroup()` are gone; groups and memberships decode; transfers and expenses keep their `group_id`; the orphan checks for shares and payers share `ownedRows`, and the per-row decoding of shares and payers shares `owned` (reasons and row numbers as before). Check the `Expense` and `Transfer` parameter names against `Entities.kt` as Parts 1 and 3 left it; the existing `ImportReaderTest` expectations for orphan and bad share or payer rows must pass unchanged.

- [ ] **Step 5: Owners in the analysis, groups and memberships**

Replace the whole of `main/.../io/ImportAnalysis.kt` with:

```kotlin
package io.github.codex07.iou.io

import io.github.codex07.iou.api.BalanceChange
import io.github.codex07.iou.api.ImportCounts
import io.github.codex07.iou.api.ImportRow
import io.github.codex07.iou.api.ImportRowChoice
import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.api.InvalidRow
import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.RuleViolation
import io.github.codex07.iou.ledger.engine.DiffNames
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.engine.RevisionDiff
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.Entry
import io.github.codex07.iou.ledger.model.Expense
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.FxSnapshot
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.GroupMember
import io.github.codex07.iou.ledger.model.ImportBatchId
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PaymentHandle
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.model.SyncMeta
import io.github.codex07.iou.ledger.model.Transfer
import io.github.codex07.iou.ledger.rules.FieldRules
import io.github.codex07.iou.ledger.rules.LedgerValidator
import io.github.codex07.iou.ledger.rules.WriteOp
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.time.Instant
import java.time.LocalDate

/** How an incoming row compares with the local ledger (spec §7.4). */
enum class Classification { NEW, DUPLICATE, CONFLICT }

/** An incoming record after identity mapping, the local row with its ID (if any) and how they compare. */
data class Classified<T : LedgerEntity>(val incoming: Incoming<T>, val local: T?, val kind: Classification) {
    val record: T get() = incoming.record
    val key: ImportRowKey get() = ImportRowKey(record.entityType, record.entityId)
}

/**
 * What an import would do to one local ledger (spec §7.4, §7.5 steps 2–4): identity mapping, classification
 * by UUID, row validation with attribution, and, for a set of choices, the preview rows, the balance changes
 * and the plan the commit writes. Owned rows follow their owner through the §5.2 cascade: people own their
 * payment handles, groups their memberships ([Owners]).
 */
class ImportAnalysis private constructor(
    val state: LedgerState,
    val fileName: String,
    private val mappedFrom: PersonId?,
    val people: List<Classified<Person>>,
    val handles: List<Classified<PaymentHandle>>,
    val groups: List<Classified<Group>>,
    val members: List<Classified<GroupMember>>,
    val snapshots: List<Incoming<FxSnapshot>>,
    val transfers: List<Classified<Transfer>>,
    val expenses: List<Classified<Expense>>,
    val invalid: List<InvalidRow>,
    private val duplicates: Int,
    private val mappedKeys: Set<ImportRowKey>,
    /** When the file shows each kept or duplicate owner and owned row (people, groups, handles, memberships) deleted. */
    private val sourceDeletedAt: Map<ImportRowKey, Instant?>,
) {
    private val owners: List<Classified<*>> get() = people + groups
    private val entries: List<Classified<out Entry>> get() = transfers + expenses
    private val rows: List<Classified<*>> get() = people + handles + groups + members + entries

    val counts: ImportCounts
        get() = ImportCounts(
            new = rows.count { it.kind == Classification.NEW } + snapshots.size,
            conflicts = rows.count { it.kind == Classification.CONFLICT },
            duplicates = duplicates,
            invalid = invalid.size,
        )

    // ---- Owners and their cascades ----

    private enum class OwnerAction { NONE, CREATE_DELETED, DELETE, RESTORE }

    private fun Classified<*>.action(take: Set<ImportRowKey>): OwnerAction {
        val taken = kind == Classification.CONFLICT && key in take
        return when {
            kind == Classification.NEW && record.isDeleted -> OwnerAction.CREATE_DELETED
            taken && local!!.isDeleted != record.isDeleted -> if (record.isDeleted) OwnerAction.DELETE else OwnerAction.RESTORE
            else -> OwnerAction.NONE
        }
    }

    private fun owner(key: ImportRowKey): Classified<*>? = owners.firstOrNull { it.key == key }

    /** An owner's name as the file has it, else as the ledger has it. */
    private fun ownerName(key: ImportRowKey): String = when (val record = owner(key)?.record ?: state.entity(key.type, key.id)) {
        is Person -> record.displayName
        is Group -> record.name
        else -> key.id
    }

    /** How an owned [row]'s deleted state is decided: by its owner's cascade, or (null) by its own row. */
    private fun forcedBy(row: LedgerEntity, local: LedgerEntity?, take: Set<ImportRowKey>): ImportRowChoice? {
        val ownerKey = Owners.ownerOf(row) ?: return null
        val owner = owner(ownerKey)
        val name = ownerName(ownerKey)
        return when (owner?.action(take)) {
            OwnerAction.CREATE_DELETED, OwnerAction.DELETE -> ImportRowChoice.DeletedWith(name)
            OwnerAction.RESTORE ->
                if (local != null && local.meta.deletedAt == owner.local!!.meta.deletedAt) ImportRowChoice.RestoredWith(name) else null
            // The owner stays deleted locally: its owned rows end deleted too.
            else -> if (state.entity(ownerKey.type, ownerKey.id)?.isDeleted == true) ImportRowChoice.DeletedWith(name) else null
        }
    }

    // ---- Preview ----

    /** Conflicts with their field diffs, and every row whose outcome a cascade forces (local rows included). */
    fun previewRows(take: Set<ImportRowKey>): List<ImportRow> {
        val shown = mutableListOf<ImportRow>()
        for (row in rows) {
            val forced = forcedBy(row.record, row.local, take)
            if (row.kind == Classification.CONFLICT || forced != null) {
                shown += ImportRow(row.key, label(row.record), diff(row.local, row.record), forced ?: ImportRowChoice.KeepOrTake, changedSinceLastPreview = false)
            }
        }
        val listed = shown.map { it.key }.toSet()
        for (owner in owners) {
            val action = owner.action(take)
            val name = ownerName(owner.key)
            val local = Owners.owned(state, owner.key)
            val touched = when (action) {
                OwnerAction.DELETE -> local.filter { !it.isDeleted || joinsCascade(it, owner.key) }.map { it to ImportRowChoice.DeletedWith(name) }
                OwnerAction.RESTORE -> local.filter { it.meta.deletedAt == owner.local!!.meta.deletedAt }.map { it to ImportRowChoice.RestoredWith(name) }
                else -> emptyList()
            }
            for ((entity, choice) in touched) {
                if (entity.rowKey in listed) continue
                val after = entity.withMeta(entity.meta.copy(deletedAt = if (action == OwnerAction.DELETE) Instant.EPOCH else null))
                shown += ImportRow(entity.rowKey, label(entity), diff(entity, after), choice, changedSinceLastPreview = false)
            }
        }
        return shown
    }

    /**
     * Non-group balances with each person, then my net in each group, before and after the import with [take],
     * where they differ. A group's line is labelled "<name> (group)".
     */
    fun balanceChanges(take: Set<ImportRowKey>): List<BalanceChange> {
        val before = LedgerEngine.compute(state)
        val afterState = state.applying(plan(take, PREVIEW_BATCH, Instant.EPOCH, PREVIEW_DAY).changes)
        val after = LedgerEngine.compute(afterState)
        val people = (before.pairwise.keys + after.pairwise.keys).mapNotNull { person ->
            val was = before.net(person)
            val now = after.net(person)
            if (was == now) return@mapNotNull null
            BalanceChange(afterState.people[person]?.displayName ?: person.value, was.toMoney(), now.toMoney())
        }.sortedBy { it.counterpart }
        val groups = (before.groupNets.keys + after.groupNets.keys).mapNotNull { group ->
            val was = before.myGroupNet(group)
            val now = after.myGroupNet(group)
            if (was == now) return@mapNotNull null
            BalanceChange("${afterState.groups[group]?.name ?: group.value} (group)", was.toMoney(), now.toMoney())
        }.sortedBy { it.counterpart }
        return people + groups
    }

    private fun Map<CurrencyCode, Long>.toMoney() = entries.sortedBy { it.key.code }.map { Money(it.value, it.key) }

    private fun label(entity: LedgerEntity): String = when (entity) {
        is Person -> "Person ${entity.displayName}"
        is PaymentHandle -> "${entity.type.name} ${entity.label} of ${names.person(entity.personId)}"
        is Group -> "Group ${entity.name}"
        is GroupMember -> "Membership of ${names.person(entity.personId)} in ${names.group(entity.groupId)}"
        is Transfer -> "Transfer of ${entity.amount} on ${entity.occurredOn}"
        is Expense -> "Expense ${entity.description} on ${entity.occurredOn}"
        else -> "${entity.entityType} ${entity.entityId}"
    }

    private fun diff(local: LedgerEntity?, incoming: LedgerEntity): List<FieldChange> = RevisionDiff.diff(local, incoming, names)

    private val names = object : DiffNames {
        override fun person(id: PersonId) = ownerName(ImportRowKey(EntityType.PERSON, id.value))
        override fun group(id: GroupId) = ownerName(ImportRowKey(EntityType.GROUP, id.value))
    }

    // ---- Commit plan ----

    /**
     * The rows the commit writes for [take] (spec §7.5 step 6): creates and updates in dependency order (people,
     * handles, groups, memberships, entries), then deletions (entries, owned rows on their own, owners with their
     * cascade). Invalid rows are left out. Rows get [batch] as their `importBatchId` and a reason naming the file
     * and source version. [today] is the import day (Task 3 gives it to new schedules).
     */
    fun plan(take: Set<ImportRowKey>, batch: ImportBatchId, now: Instant, today: LocalDate): PlanResult {
        val writer = Writer(state, batch, now)
        val ownerActions = owners.associate { it.key to it.action(take) }
        val ownedDeletions = mutableListOf<Pair<Classified<*>, Instant>>()

        fun writeOwners(rows: List<Classified<*>>) {
            for (row in rows) {
                when (row.kind) {
                    Classification.NEW -> writer.create(row.record, reasonNew(row))
                    Classification.CONFLICT -> if (row.key in take) writer.update(row, reasonConflict(row))
                    Classification.DUPLICATE -> Unit
                }
                if (ownerActions[row.key] == OwnerAction.RESTORE) {
                    val restoredAt = row.local!!.meta.deletedAt
                    writer.restore(row.key, reasonConflict(row))
                    Owners.owned(state, row.key).filter { it.meta.deletedAt == restoredAt }.forEach {
                        writer.restore(it.rowKey, "${reasonConflict(row)} with ${ownerName(row.key)}")
                    }
                }
            }
        }

        /**
         * [owned], deleted here under an owner the import leaves deleted (at [ownerAt]), grouped as the file shows it
         * (spec §7.4): with the owner it takes [ownerAt], so it comes back with the owner; [apart] it stays apart, or
         * moves 1 ns before the owner if it was deleted with the owner here.
         */
        fun groupUnderKeptOwner(owned: LedgerEntity, apart: Boolean, ownerKey: ImportRowKey, ownerAt: Instant, reason: String) {
            if (!apart) writer.regroup(owned.rowKey, ownerAt, "$reason with ${ownerName(ownerKey)}")
            else if (owned.meta.deletedAt == ownerAt) writer.regroup(owned.rowKey, ownerAt.minusNanos(1), "$reason, deleted in the source")
        }

        fun writeOwned(rows: List<Classified<*>>) {
            for (row in rows) {
                val ownerKey = checkNotNull(Owners.ownerOf(row.record))
                val ownerAction = ownerActions[ownerKey] ?: OwnerAction.NONE
                val ownerLocal = state.entity(ownerKey.type, ownerKey.id)
                when (row.kind) {
                    Classification.NEW -> {
                        writer.create(row.record, reasonNew(row))
                        when {
                            ownerAction == OwnerAction.CREATE_DELETED || ownerAction == OwnerAction.DELETE ->
                                if (deletedApart(row)) ownedDeletions += row to now.minusNanos(1)
                            // Under an owner that stays deleted, it goes with the owner unless the file shows it deleted
                            // apart; then it is deleted on its own, just before the owner, so restoring the owner skips it.
                            ownerAction == OwnerAction.NONE && ownerLocal?.isDeleted == true ->
                                ownedDeletions += row to ownerLocal.meta.deletedAt!!.let { if (deletedApart(row)) it.minusNanos(1) else it }
                            row.record.isDeleted -> ownedDeletions += row to now
                        }
                    }
                    Classification.CONFLICT -> {
                        if (row.key in take) writer.update(row, reasonConflict(row))
                        val local = row.local!!
                        when {
                            // Its deleted state isn't a choice here ("deleted with <owner>"), whether or not it is taken.
                            ownerAction == OwnerAction.DELETE -> if (deletedApart(row)) ownedDeletions += row to now.minusNanos(1)
                            // Under an owner kept deleted (so the row is deleted here too) it takes the owner's deletedAt,
                            // so it comes back with the owner, unless the file shows it deleted apart: then it stays apart,
                            // or moves 1 ns before the owner if it was deleted with the owner here (spec §7.4).
                            ownerAction == OwnerAction.NONE && ownerLocal?.isDeleted == true ->
                                groupUnderKeptOwner(local, deletedApart(row), ownerKey, ownerLocal.meta.deletedAt!!, reasonConflict(row))
                            row.key in take && forcedBy(row.record, local, take) == null && local.isDeleted != row.record.isDeleted ->
                                if (row.record.isDeleted) ownedDeletions += row to now else writer.restore(row.key, reasonConflict(row))
                        }
                    }
                    Classification.DUPLICATE -> Unit
                }
            }
        }

        writeOwners(people)
        writeOwned(handles)
        writeOwners(groups)
        writeOwned(members)
        // Duplicates are not classified rows (business fields and deleted state match, and `deletedAt` is never
        // compared), but under an owner kept deleted the file's `deletedAt` still decides which cascade they are in.
        // Only owners in the map are visited, so the rows of a rejected (skipped) owner stay as they are.
        val classifiedKeys = rows.map { it.key }.toSet()
        for (ownerKey in sourceDeletedAt.keys.filter { it.type == EntityType.PERSON || it.type == EntityType.GROUP }) {
            if ((ownerActions[ownerKey] ?: OwnerAction.NONE) != OwnerAction.NONE) continue
            val ownerAt = state.entity(ownerKey.type, ownerKey.id)?.meta?.deletedAt ?: continue
            for (owned in Owners.owned(state, ownerKey)) {
                if (owned.rowKey in classifiedKeys || owned.rowKey !in sourceDeletedAt) continue
                val apart = sourceDeletedAt[owned.rowKey] != sourceDeletedAt[ownerKey]
                groupUnderKeptOwner(owned, apart, ownerKey, ownerAt, "Import from $fileName")
            }
        }
        val snapshots = this.snapshots.map { it.record }
        val entryDeletions = mutableListOf<Classified<*>>()
        for (row in entries) {
            when (row.kind) {
                Classification.NEW -> {
                    writer.create(row.record, reasonNew(row))
                    if (row.record.isDeleted) entryDeletions += row
                }
                Classification.CONFLICT -> if (row.key in take) {
                    writer.update(row, reasonConflict(row))
                    val local = row.local!!
                    if (local.isDeleted != row.record.isDeleted) {
                        if (row.record.isDeleted) entryDeletions += row else writer.restore(row.key, reasonConflict(row))
                    }
                }
                Classification.DUPLICATE -> Unit
            }
        }
        for (row in entryDeletions) writer.delete(row.key, now, reasonDeleted(row))
        for ((row, at) in ownedDeletions) writer.delete(row.key, at, reasonDeleted(row))
        for (row in owners) {
            val action = ownerActions.getValue(row.key)
            if (action != OwnerAction.CREATE_DELETED && action != OwnerAction.DELETE) continue
            val reason = if (action == OwnerAction.DELETE) reasonConflict(row) else reasonDeleted(row)
            writer.delete(row.key, now, reason)
            Owners.owned(writer.current, row.key).filter { !it.isDeleted }.forEach {
                writer.delete(it.rowKey, now, "$reason with ${ownerName(row.key)}")
            }
            // Owned rows deleted here earlier, on their own, that the file shows deleted with the owner join its
            // cascade, so restoring the owner brings back what went together in the source.
            if (action == OwnerAction.DELETE) {
                Owners.owned(state, row.key).filter { joinsCascade(it, row.key) }.forEach {
                    writer.regroup(it.rowKey, now, "$reason with ${ownerName(row.key)}")
                }
            }
        }
        return PlanResult(writer.changes, snapshots, writer.current.applyingSnapshots(snapshots))
    }

    /** The changes, the new FX snapshots, and the state they produce. */
    data class PlanResult(val changes: List<Change>, val snapshots: List<FxSnapshot>, val state: LedgerState) {
        val rowCount: Int get() = changes.map { it.entity.entityType to it.entity.entityId }.distinct().size + snapshots.size
    }

    private fun LedgerState.applyingSnapshots(snapshots: List<FxSnapshot>) = snapshots.fold(this) { s, snapshot -> s.with(snapshot) }

    /**
     * An owned row the file shows deleted at another time than its owner as the file shows it (the owner's row
     * counts even when it duplicates the local one): it is deleted on its own, so restoring the owner later doesn't
     * bring it back (spec §7.4).
     */
    private fun deletedApart(row: Classified<*>) =
        row.record.isDeleted && row.record.meta.deletedAt != sourceDeletedAt[Owners.ownerOf(row.record)]

    /**
     * An owned row already deleted locally that the file shows deleted together with its owner [ownerKey] (same
     * `deletedAt` in the file, even where the row duplicates the local one). When the import deletes the owner, the
     * row gets the owner's new `deletedAt` (spec §7.4); the preview lists it as "deleted with <owner>".
     */
    private fun joinsCascade(owned: LedgerEntity, ownerKey: ImportRowKey): Boolean {
        val ownerAt = sourceDeletedAt[ownerKey] ?: return false
        return owned.isDeleted && sourceDeletedAt[owned.rowKey] == ownerAt
    }

    private fun mapping(row: Classified<*>) = if (mappedFrom != null && row.key in mappedKeys) ", source identity ${mappedFrom.value} mapped to me" else ""

    private fun reasonNew(row: Classified<*>) = "Imported from $fileName (source v${row.incoming.sourceVersion})${mapping(row)}"

    private fun reasonDeleted(row: Classified<*>) = "${reasonNew(row)}, deleted in the source"

    private fun reasonConflict(row: Classified<*>) = "Import conflict resolved from $fileName (source v${row.incoming.sourceVersion})${mapping(row)}"

    /** Writes rows against a running state, so later rows see earlier ones. */
    private class Writer(var current: LedgerState, private val batch: ImportBatchId, private val now: Instant) {
        val changes = mutableListOf<Change>()
        private val me = current.me.id

        private fun write(entity: LedgerEntity, action: RevisionAction, reason: String) {
            changes += Change(entity, action, reason)
            current = current.with(entity)
        }

        private fun next(entity: LedgerEntity, deletedAt: Instant?): SyncMeta =
            entity.meta.copy(updatedAt = now, authorId = me, version = entity.meta.version + 1, deletedAt = deletedAt, importBatchId = batch)

        private fun find(key: ImportRowKey): LedgerEntity = checkNotNull(current.entity(key.type, key.id)) { "No ${key.type} ${key.id}" }

        /** Version 1, active; a row deleted in the source is deleted afterwards. */
        fun create(record: LedgerEntity, reason: String) =
            write(record.withMeta(SyncMeta(createdAt = now, updatedAt = now, authorId = me, importBatchId = batch)), RevisionAction.CREATED, reason)

        /** [record]'s business fields (by default the incoming ones) on the local row; the deleted state changes separately. */
        fun update(row: Classified<*>, reason: String, record: LedgerEntity = row.record) {
            val local = find(row.key)
            if (local.withMeta(NEUTRAL) == record.withMeta(NEUTRAL)) return
            write(record.withMeta(next(local, local.meta.deletedAt)), RevisionAction.UPDATED, reason)
        }

        fun delete(key: ImportRowKey, at: Instant, reason: String) {
            val entity = find(key)
            if (!entity.isDeleted) write(entity.withMeta(next(entity, at)), RevisionAction.DELETED, reason)
        }

        fun restore(key: ImportRowKey, reason: String) {
            val entity = find(key)
            if (entity.isDeleted) write(entity.withMeta(next(entity, null)), RevisionAction.RESTORED, reason)
        }

        /** Moves an already deleted row's `deletedAt` to [at]: its owner's, to join the cascade, or 1 ns before it, to leave it. */
        fun regroup(key: ImportRowKey, at: Instant, reason: String) {
            val entity = find(key)
            if (entity.isDeleted && entity.meta.deletedAt != at) write(entity.withMeta(next(entity, at)), RevisionAction.UPDATED, reason)
        }
    }

    companion object {
        private val PREVIEW_BATCH = ImportBatchId("preview")
        private val PREVIEW_DAY: LocalDate = LocalDate.EPOCH
        private val NEUTRAL = SyncMeta(Instant.EPOCH, Instant.EPOCH, PersonId(""))

        /** Business fields and deleted state equal: the sync columns are ignored (spec §7.4). */
        private fun same(a: LedgerEntity, b: LedgerEntity) = a.withMeta(NEUTRAL) == b.withMeta(NEUTRAL) && a.isDeleted == b.isDeleted

        private fun personKey(id: PersonId) = ImportRowKey(EntityType.PERSON, id.value)

        private fun groupKey(id: GroupId) = ImportRowKey(EntityType.GROUP, id.value)

        /** The rows [record] references by ID, as keys. */
        private fun references(record: LedgerEntity): Set<ImportRowKey> = when (record) {
            is Person -> setOfNotNull(record.mergedIntoId?.let(::personKey))
            is PaymentHandle -> setOf(personKey(record.personId))
            is GroupMember -> setOf(personKey(record.personId), groupKey(record.groupId))
            is Transfer -> setOfNotNull(personKey(record.fromPersonId), personKey(record.toPersonId), record.groupId?.let(::groupKey))
            is Expense -> record.personIds.map(::personKey).toSet() + setOfNotNull(record.groupId?.let(::groupKey))
            else -> emptySet()
        }

        /**
         * Analyses [decoded] against [state]. With [mapFrom], every reference to that source me becomes the local
         * me and the source me's own row is dropped (spec §7.4, identity mapping).
         */
        fun build(state: LedgerState, fileName: String, decoded: DecodedImport, mapFrom: PersonId?): ImportAnalysis {
            val me = state.me.id
            val invalid = decoded.invalid.toMutableList()
            val mappedKeys = mutableSetOf<ImportRowKey>()
            fun mapped(id: PersonId) = if (id == mapFrom) me else id
            fun <T : LedgerEntity> Incoming<T>.mapped(transform: (T) -> T): Incoming<T> {
                val after = transform(record)
                if (after != record) mappedKeys += ImportRowKey(record.entityType, record.entityId)
                return copy(record = after)
            }

            val incoming = decoded.ledger
            val people = incoming.people.filter { mapFrom == null || it.record.id != mapFrom }
                .map { row -> row.mapped { it.copy(mergedIntoId = it.mergedIntoId?.let(::mapped)) } }
            val handles = incoming.handles.map { row -> row.mapped { it.copy(personId = mapped(it.personId)) } }
            val groups = incoming.groups
            val members = incoming.members.map { row -> row.mapped { it.copy(personId = mapped(it.personId)) } }
            val sourceDeletedAt = (people.map { it.record } + handles.map { it.record } + groups.map { it.record } + members.map { it.record })
                .associate { it.rowKey to it.meta.deletedAt }
            val transfers = incoming.transfers.map { row -> row.mapped { it.copy(fromPersonId = mapped(it.fromPersonId), toPersonId = mapped(it.toPersonId)) } }
            val expenses = incoming.expenses.map { row ->
                row.mapped { e ->
                    e.copy(
                        payers = e.payers.map { it.copy(personId = mapped(it.personId)) },
                        shares = e.shares.map { it.copy(personId = mapped(it.personId)) },
                    )
                }
            }

            var duplicates = 0
            val duplicateKeys = mutableSetOf<ImportRowKey>()
            fun <T : LedgerEntity> classify(rows: List<Incoming<T>>, local: (T) -> T?): List<Classified<T>> = rows.mapNotNull { row ->
                val existing = local(row.record)
                when {
                    existing == null -> Classified(row, null, Classification.NEW)
                    same(existing, row.record) -> {
                        duplicates++
                        duplicateKeys += row.record.rowKey
                        null
                    }
                    else -> Classified(row, existing, Classification.CONFLICT)
                }
            }

            // FX snapshots are immutable: unknown → new, identical → duplicate, different → invalid (spec §7.4).
            val rejectedSnapshots = mutableSetOf<String>()
            val newSnapshots = incoming.fxSnapshots.filter { row ->
                val existing = state.fxSnapshots[row.record.id]
                when {
                    existing == null -> true
                    existing == row.record -> { duplicates++; false }
                    else -> {
                        rejectedSnapshots += row.record.id.value
                        invalid += InvalidRow(row.table, row.row, "differs from the local FX snapshot with the same ID", LedgerError.CorruptData("FX snapshot ${row.record.id.value} differs"))
                        false
                    }
                }
            }

            var classifiedPeople = classify(people) { state.people[it.id] }
            var classifiedHandles = classify(handles) { state.handles[it.id] }
            var classifiedGroups = classify(groups) { state.groups[it.id] }
            var classifiedMembers = classify(members) { state.members[it.id] }
            var classifiedTransfers = classify(transfers) { state.transfers[it.id] }
            var classifiedExpenses = classify(expenses) { state.expenses[it.id] }

            fun reject(row: Classified<*>, reason: String, error: LedgerError?) {
                invalid += InvalidRow(row.incoming.table, row.incoming.row, reason, error)
            }

            // Entries that would be written with a rejected snapshot, and rows that fail their own field checks.
            fun <T : LedgerEntity> List<Classified<T>>.checked(): List<Classified<T>> = filter { row ->
                val entry = row.record as? Entry
                if (entry?.fxSnapshotId?.value in rejectedSnapshots) {
                    reject(row, "references an FX snapshot that differs from the local copy", LedgerError.FxSnapshotMismatch(entry?.id))
                    return@filter false
                }
                val field = if (row.record.isDeleted) null else FieldRules.check(listOf(row.record)).firstOrNull()
                if (field != null) reject(row, describe(field), field.error)
                field == null
            }
            classifiedPeople = classifiedPeople.checked()
            classifiedHandles = classifiedHandles.checked()
            classifiedGroups = classifiedGroups.checked()
            classifiedMembers = classifiedMembers.checked()
            classifiedTransfers = classifiedTransfers.checked()
            classifiedExpenses = classifiedExpenses.checked()

            // State invariants (spec §3.4) over the complete state the import would produce with every conflict
            // taken, cascades included. Only when it breaks one are rows blamed: a row is to blame for a violation
            // that goes away without it. Leaving out a new row (a person or a group) would leave the rows that name
            // it dangling, and the validator then reports only that broken structure (rule 15), hiding every other
            // violation; so a new row is left out together with those rows, and is to blame only for a violation
            // that stays while just those rows are left out. People, handles, groups and memberships answer first,
            // for the violations that also remain with the incoming active entries left out; incoming deletions stay
            // in, as they can be what makes a deletion valid (a person deleted together with their transfers).
            // Entries answer for the rest. Each round repeats until nothing is broken or no row is to blame, and
            // both passes run again while either blames a row, since one broken rule (above all a dangling
            // reference) can hide others.
            val rejected = mutableSetOf<ImportRowKey>()
            fun <T : LedgerEntity> List<Classified<T>>.kept() = filter { it.key !in rejected }
            fun violations(withActiveEntries: Boolean, without: Set<ImportRowKey> = emptySet()): Set<RuleViolation> {
                fun <T : LedgerEntity> List<Classified<T>>.taken(entries: Boolean) =
                    kept().filter { it.key !in without && (!entries || withActiveEntries || it.record.isDeleted) }
                val rows = ImportAnalysis(
                    state, fileName, mapFrom, classifiedPeople.taken(false), classifiedHandles.taken(false),
                    classifiedGroups.taken(false), classifiedMembers.taken(false), newSnapshots,
                    classifiedTransfers.taken(true), classifiedExpenses.taken(true), emptyList(), 0, emptySet(), sourceDeletedAt,
                )
                val take = rows.rows.filter { it.kind == Classification.CONFLICT }.map { it.key }.toSet()
                val after = rows.plan(take, PREVIEW_BATCH, Instant.EPOCH, PREVIEW_DAY).state
                return LedgerValidator.validate(state, after, WriteOp.Import).toSet()
            }
            fun keptRows(): List<Classified<*>> = classifiedPeople.kept() + classifiedHandles.kept() + classifiedGroups.kept() +
                classifiedMembers.kept() + classifiedTransfers.kept() + classifiedExpenses.kept()
            // The kept rows that name [row] if it is new, directly or through other new rows.
            fun dependents(row: Classified<*>): Set<ImportRowKey> {
                val rows = keptRows()
                val gone = mutableSetOf<ImportRowKey>()
                val found = mutableSetOf<ImportRowKey>()
                var next: List<Classified<*>> = listOf(row)
                while (next.isNotEmpty()) {
                    gone += next.filter { it.local == null }.map { it.key }
                    next = rows.filter { it.key != row.key && it.key !in found && references(it.record).any(gone::contains) }
                    found += next.map { it.key }
                }
                return found
            }
            fun blame(candidates: List<Classified<*>>, broken: () -> Set<RuleViolation>, without: (Set<ImportRowKey>) -> Set<RuleViolation>): Boolean {
                var any = false
                var found = broken()
                while (found.isNotEmpty()) {
                    val blamed = candidates.filter { it.key !in rejected }.filter { row ->
                        val others = dependents(row)
                        val stays = if (others.isEmpty()) found else found intersect without(others)
                        (stays - without(others + row.key)).firstOrNull()?.let { reject(row, describe(it), it.error) } != null
                    }
                    if (blamed.isEmpty()) return any
                    any = true
                    rejected += blamed.map { it.key }
                    found = broken()
                }
                return any
            }
            while (violations(withActiveEntries = true).isNotEmpty()) {
                val owners = blame(
                    classifiedPeople + classifiedHandles + classifiedGroups + classifiedMembers,
                    broken = { violations(withActiveEntries = false) intersect violations(withActiveEntries = true) },
                    without = { violations(withActiveEntries = false, without = it) },
                )
                val entries = blame(
                    classifiedTransfers + classifiedExpenses,
                    broken = { violations(withActiveEntries = true) },
                    without = { violations(withActiveEntries = true, without = it) },
                )
                if (!owners && !entries) break
            }
            classifiedPeople = classifiedPeople.kept()
            classifiedHandles = classifiedHandles.kept()
            classifiedGroups = classifiedGroups.kept()
            classifiedMembers = classifiedMembers.kept()
            classifiedTransfers = classifiedTransfers.kept()
            classifiedExpenses = classifiedExpenses.kept()

            // The file's deletedAt counts only for a duplicate or a kept row: a row rejected as invalid (and skipped),
            // owner or owned, must not move a local row into or out of a cascade (spec §7.4, §7.5). Without its
            // owner's time, an owned row the file shows deleted counts as deleted apart from it.
            val kept = (classifiedPeople + classifiedHandles + classifiedGroups + classifiedMembers).map { it.key }.toSet() + duplicateKeys
            val keptDeletedAt = sourceDeletedAt.filterKeys { it in kept }

            return ImportAnalysis(
                state, fileName, mapFrom, classifiedPeople, classifiedHandles, classifiedGroups, classifiedMembers, newSnapshots,
                classifiedTransfers, classifiedExpenses, invalid.sortedWith(compareBy({ it.table }, { it.rowNumber })), duplicates,
                mappedKeys, keptDeletedAt,
            )
        }

        private fun describe(violation: RuleViolation) =
            if (violation.rule == 0) "invalid field: ${violation.error}" else "breaks rule ${violation.rule}: ${violation.error}"
    }
}
```

What changed against M1 (with Part 1's payer mapping): the person-and-handle logic now works on any owner through `Owners` (`OwnerAction`, `forcedBy`, `deletedApart`, the restore and delete cascades); groups and memberships are classified, checked, blamed and written in the order of spec §7.5; `Writer` finds rows by key through `LedgerState.entity` and `update` takes the record to write (Task 3 passes a merged schedule); a new group is left out together with what names it, like a new person; balance changes include my group nets; and `plan` takes the import day. `Expense.personIds` (Part 1) covers payers and shares in `references`; Task 3 adds the schedule link.

- [ ] **Step 6: Pass the import day, and document the group lines**

In `main/.../data/RealImportExportApi.kt`, replace:

```kotlin
                    val plan = analysis.plan(choices.takeIncoming, id, context.now)
```

with:

```kotlin
                    val plan = analysis.plan(choices.takeIncoming, id, context.now, context.today)
```

In `main/.../api/TransferModels.kt`, replace:

```kotlin
/** Balance with a person (or my net in a group) before and after the import, per currency. */
```

with:

```kotlin
/**
 * Non-group balance with a person, or my net in a group ([counterpart] "<group name> (group)"), before and after
 * the import, per currency. People come first, then groups, each by name.
 */
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.io.*" --tests "io.github.codex07.iou.data.RealImportTest"`
Expected: PASS: the nine group tests, the updated reader test, and every M1 import test unchanged.

Then: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest`
Expected: PASS. A real-flavor contract test that imports an own export of a ledger with groups now finds its group rows as duplicates instead of invalid rows; if one asserted the old invalid rows, update it to expect none and say so in the report.

- [ ] **Step 8: Commit**

```bash
git add android/app/src
git commit -m "Import groups and memberships with their cascade"
```

---

### Task 3: Schedules import with their participants

**Files:**
- Create: `test/.../io/ScheduleImportTest.kt`
- Modify: `main/.../io/ImportDecoder.kt`, `main/.../io/ImportAnalysis.kt`, `main/.../api/TransferModels.kt`, `docs/ui-contributor-guide.md`
- Modify (tests): `test/.../io/ImportReaderTest.kt`

**Interfaces:**
- Consumes: Task 1's `recurringExpenses` / `recurringParticipants`; Task 2's decoder (`ownedRows`, `owned`, `int`, `optionalDate`), `ImportAnalysis` (`Writer.update(row, reason, record)`, `references`, `plan(..., today)`), `ImportBench`; Part 3's `RecurringExpense.personIds`, `FieldRules` for schedules, rule 18, `RevisionDiff`'s `handledThrough` field, `finalize` (cut-off on every committed schedule change).
- Produces: `IncomingLedger.schedules` (last, default empty); `Classification.FORWARD`; `ImportAnalysis.schedules`; `ImportRow.forcedChanges: List<FieldChange> = emptyList()`; revision reason "Imported progress from <file> (source v<N>): handled through <date>" for a `handled_through` the import moves forward on its own.

- [ ] **Step 1: Write the failing tests**

Create `test/.../io/ScheduleImportTest.kt`:

```kotlin
package io.github.codex07.iou.io

import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.ImportIdentity
import io.github.codex07.iou.api.ImportRowChoice
import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.FieldChange
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.ImportBench
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.github.codex07.iou.testutil.testMeta
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Schedules through the import: one unit with their participants, `handled_through` forward only (spec §7.4, AC M2-35). */
class ScheduleImportTest {

    private fun d(text: String) = LocalDate.parse(text)

    private val rent = RecurringId("rent")
    private val key = ImportRowKey(EntityType.RECURRING_EXPENSE, "rent")

    /** Me and X; a monthly rent [total] from 1 January paid by me and shared by both, handled through [handled]. */
    private fun ledger(
        handled: LocalDate?,
        total: Long = 90_000,
        autoPost: Boolean = false,
        autoPostFrom: LocalDate = D0,
    ): LedgerState = TestLedger().run {
        me()
        val x = person("X")
        schedule(ME, eur(total), listOf(ME, x), id = "rent", handledThrough = handled, autoPost = autoPost, autoPostFrom = autoPostFrom)
        state
    }

    @Test
    fun `an older incoming handled date never rewinds the local one`() = runTest {
        val bench = ImportBench(ledger(d("2026-11-01")))

        val preview = bench.preview(ledger(d("2026-10-01")))
        bench.commit(preview)

        preview.rows shouldBe emptyList()
        bench.state.schedules.getValue(rent).handledThrough shouldBe d("2026-11-01")
    }

    @Test
    fun `a later incoming handled date moves the local one forward as a forced change, with nothing to choose`() = runTest {
        val bench = ImportBench(ledger(d("2026-11-01")))

        val preview = bench.preview(ledger(d("2026-12-01")))
        val row = preview.rows.single()
        bench.commit(preview)

        row.key shouldBe key
        row.forcedChanges shouldBe listOf(FieldChange("handledThrough", "2026-11-01", "2026-12-01"))
        row.changes shouldBe row.forcedChanges
        preview.counts.conflicts shouldBe 1
        bench.state.schedules.getValue(rent).handledThrough shouldBe d("2026-12-01")
        bench.store.loaded().revisions.last { it.entityId == "rent" }.reason shouldBe
            "Imported progress from export.zip (source v1): handled through 2026-12-01"
    }

    @Test
    fun `taking a changed template keeps the later handled date and makes an auto-post schedule wait from the import day`() = runTest {
        val bench = ImportBench(ledger(d("2026-11-01"), autoPost = true))
        val take = ImportChoices(takeIncoming = setOf(key))

        val preview = bench.preview(ledger(d("2026-10-01"), total = 95_000, autoPost = true, autoPostFrom = d("2026-10-15")))
        val row = preview.rows.single()
        bench.commit(preview, take)

        row.choice shouldBe ImportRowChoice.KeepOrTake
        row.forcedChanges shouldBe emptyList()
        row.changes shouldContain FieldChange("total", "900.00 EUR", "950.00 EUR")
        row.changes.none { it.field == "handledThrough" } shouldBe true
        val schedule = bench.state.schedules.getValue(rent)
        schedule.total shouldBe eur(95_000)
        schedule.handledThrough shouldBe d("2026-11-01")
        schedule.autoPostFrom shouldBe bench.today
    }

    @Test
    fun `a new schedule gets the import day as autoPostFrom, whatever the file says`() = runTest {
        val bench = ImportBench(TestLedger().apply { me(); person("X") }.state)

        bench.import(ledger(null, autoPostFrom = d("2025-06-01")))

        bench.state.schedules.getValue(rent).autoPostFrom shouldBe bench.today
    }

    @Test
    fun `two live expenses for the same occurrence are invalid rows`() = runTest {
        val source = TestLedger().run {
            me()
            val x = person("X")
            val id = schedule(ME, eur(90_000), listOf(ME, x), id = "rent")
            for (expense in listOf("e-a", "e-b")) {
                expense(ME, eur(90_000), mapOf(ME to 45_000L, x to 45_000L), id = expense, recurring = id, recurringOn = d("2026-02-01"))
            }
            state
        }
        val bench = ImportBench(TestLedger().apply { me(); person("X") }.state)

        val invalid = bench.preview(source).invalidRows

        invalid.map { it.table } shouldBe listOf("expenses", "expenses")
        invalid.all { it.reason.startsWith("breaks rule 18") } shouldBe true
    }

    @Test
    fun `participants of a schedule from another install are mapped to the local me`() = runTest {
        val localMe = PersonId("local-me")
        val bench = ImportBench(
            LedgerState()
                .with(Person(localMe, "Me", isMe = true, meta = testMeta()))
                .with(Person(PersonId("person-x"), "X", meta = testMeta())),
        )

        val preview = bench.preview(ledger(null))
        bench.commit(preview)

        preview.identity shouldBe ImportIdentity.Mapped(ME)
        bench.state.schedules.getValue(rent).personIds shouldBe setOf(localMe, PersonId("person-x"))
    }
}
```

`TestLedger.schedule` gives the schedule `testMeta()` (version 1, `T0`), and the bench clock stands one day after `T0`, so `bench.today` is 2 January 2026. `Money.toString()` is `"900.00 EUR"`, which `RevisionDiff` uses for `total`.

In `test/.../io/ImportReaderTest.kt`, in Task 2's test `an export decodes to the same records`, replace:

```kotlin
            decoded.ledger.expenses.map { it.record } shouldContainExactlyInAnyOrder state.expenses.values
            decoded.invalid shouldBe emptyList()
```

with:

```kotlin
            decoded.ledger.expenses.map { it.record } shouldContainExactlyInAnyOrder state.expenses.values
            decoded.ledger.schedules.map { it.record } shouldContainExactlyInAnyOrder state.schedules.values
            decoded.invalid shouldBe emptyList()
```

and add this test (with the import `io.github.codex07.iou.api.InvalidRow` where missing):

```kotlin
    @Test
    fun `schedules decode with their participants in role and position order, and orphans are reported`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        ledger.schedule(x, eur(1_000), listOf(ME, x), id = "rent")
        val file = export(ledger.state)

        decode(file).ledger.schedules.single().record shouldBe ledger.state.schedules.values.single()

        val orphan = Zips.write(
            Zips.read(file).also { entries ->
                entries["recurring_participants.csv"] = entries.getValue("recurring_participants.csv") + "gone,SHARE,0,person-x,\r\n"
                entries["manifest.json"] = entries.getValue("manifest.json").replace("\"recurring_participants\":3", "\"recurring_participants\":4")
            },
        )
        decode(orphan).invalid shouldBe listOf(
            InvalidRow("recurring_participants", 5, "participant of a schedule that isn't in the file", LedgerError.CorruptData("orphan participant")),
        )

        val badRole = decode(Zips.edit(file, "recurring_participants.csv") { it.replace("rent,PAYER,0", "rent,BOSS,0") })
        badRole.ledger.schedules shouldBe emptyList()
        badRole.invalid shouldBe listOf(InvalidRow("recurring_participants", 2, "role: unknown value BOSS", LedgerError.InvalidField("role")))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.io.*"`
Expected: FAIL, compilation errors `Unresolved reference 'schedules'` (on `IncomingLedger`) and `'forcedChanges'`.

- [ ] **Step 3: Decode schedules**

In `main/.../io/ImportDecoder.kt`, add the imports `io.github.codex07.iou.ledger.model.ParticipantRole`, `io.github.codex07.iou.ledger.model.RecurrenceUnit`, `io.github.codex07.iou.ledger.model.RecurringExpense` and `io.github.codex07.iou.ledger.model.RecurringParticipant`. Replace:

```kotlin
    val members: List<Incoming<GroupMember>> = emptyList(),
)
```

with:

```kotlin
    val members: List<Incoming<GroupMember>> = emptyList(),
    /** Schedules with their participants: PAYER rows, then SHARE rows, each in `position` order. */
    val schedules: List<Incoming<RecurringExpense>> = emptyList(),
)
```

Replace:

```kotlin
        return DecodedImport(IncomingLedger(people, handles, snapshots, transfers, expenses, groups, members), invalid)
```

with:

```kotlin
        val participantRows = ownedRows(
            NormalizedTables.recurringParticipants, "recurring_id", NormalizedTables.recurringExpenses, "orphan participant",
            "participant of a schedule that isn't in the file",
        )
        val schedules = table(NormalizedTables.recurringExpenses, id) { schedule(participantRows[cells["id"].orEmpty()].orEmpty()) }
        return DecodedImport(IncomingLedger(people, handles, snapshots, transfers, expenses, groups, members, schedules), invalid)
```

Add the schedule reader after `transfer()`: replace:

```kotlin
            note = optional("note"),
            meta = meta(),
        )

        /**
         * An expense with its owned rows.
```

with:

```kotlin
            note = optional("note"),
            meta = meta(),
        )

        /**
         * A schedule with its participant rows (spec §7.2); a bad participant row is reported on its own row. A
         * schedule with an `interval` outside 1..99 or an `end_on` before `start_on` is invalid even when deleted,
         * as in a backup: views still compute dates for deleted schedules and `Recurrence` requires a valid interval.
         */
        fun schedule(participantRows: List<CsvRow>): RecurringExpense {
            val errors = mutableListOf<InvalidRow>()
            val participants = owned(NormalizedTables.recurringParticipants, participantRows, errors) {
                val role = enum<ParticipantRole>("role")
                Triple(role, long("position"), RecurringParticipant(role, PersonId(required("person_id")), optionalLong("input_value")))
            }.sortedWith(compareBy<Triple<ParticipantRole, Long, RecurringParticipant>>({ it.first }, { it.second })).map { it.third }
            if (errors.isNotEmpty()) throw OwnedRowErrors(errors)
            val interval = int("interval")
            if (interval !in 1..99) fail("interval", "must be 1 to 99")
            val startOn = date("start_on")
            val endOn = optionalDate("end_on")
            if (endOn != null && endOn < startOn) fail("end_on", "is before start_on")
            return RecurringExpense(
                id = RecurringId(required("id")),
                groupId = optional("group_id")?.let(::GroupId),
                description = required("description"),
                category = optionalEnum<Category>("category"),
                note = optional("note"),
                total = money("total", "currency"),
                payerSplit = enum<PayerSplit>("payer_split"),
                splitType = enum<SplitType>("split_type"),
                participants = participants,
                unit = enum<RecurrenceUnit>("unit"),
                interval = interval,
                startOn = startOn,
                endOn = endOn,
                handledThrough = optionalDate("handled_through"),
                autoPostFrom = date("auto_post_from"),
                autoPost = bool("auto_post"),
                meta = meta(),
            )
        }

        /**
         * An expense with its owned rows.
```

`ParticipantRole` orders `PAYER` before `SHARE`, which is the order `RecurringExpense.participants` keeps (Part 3).

A schedule with such an `interval` or `end_on` is reported as an invalid row (`interval: must be 1 to 99`, `end_on: is before start_on`) even when it is deleted, because the ledger rules skip deleted schedules but views still compute their dates and `Recurrence.occurrence` requires `interval >= 1`; this is the same guarantee as the backup format (`BackupFormat.kt`). Add this test to `ImportReaderTest`, after the schedules decoding test:

```kotlin
    @Test
    fun `a schedule with an interval outside 1 to 99 or an end before its start is invalid, even when deleted`() {
        val ledger = TestLedger()
        ledger.me()
        val x = ledger.person("X")
        ledger.schedule(ME, eur(1_000), listOf(ME, x), id = "gone", deleted = true)
        ledger.schedule(ME, eur(1_000), listOf(ME, x), id = "live", startOn = D0.plusDays(10))
        val parsed = (reader.read(export(ledger.state)) as LedgerResult.Ok).value
        val rows = parsed.rows(NormalizedTables.recurringExpenses)
        fun withCells(id: String, vararg cells: Pair<String, String>) = parsed.copy(
            tables = parsed.tables + (
                NormalizedTables.recurringExpenses.name to rows.map { if (it.cells["id"] == id) it.copy(cells = it.cells + cells) else it }
                ),
        )

        val interval = ImportDecoder.decode(withCells("gone", "interval" to "0"))
        interval.ledger.schedules.map { it.record.id.value } shouldBe listOf("live")
        interval.invalid.map { it.table to it.reason } shouldBe listOf("recurring_expenses" to "interval: must be 1 to 99")

        val end = ImportDecoder.decode(withCells("live", "end_on" to D0.toString()))
        end.ledger.schedules.map { it.record.id.value } shouldBe listOf("gone")
        end.invalid.map { it.reason } shouldBe listOf("end_on: is before start_on")
    }
```

- [ ] **Step 4: Classify and write schedules**

In `main/.../io/ImportAnalysis.kt`, add the imports `io.github.codex07.iou.ledger.model.RecurringExpense`. Replace:

```kotlin
/** How an incoming row compares with the local ledger (spec §7.4). */
enum class Classification { NEW, DUPLICATE, CONFLICT }
```

with:

```kotlin
/**
 * How an incoming row compares with the local ledger (spec §7.4). [FORWARD]: a schedule that is a duplicate except
 * for a later `handled_through`, which the import applies without a choice.
 */
enum class Classification { NEW, DUPLICATE, CONFLICT, FORWARD }
```

Replace:

```kotlin
    val expenses: List<Classified<Expense>>,
    val invalid: List<InvalidRow>,
```

with:

```kotlin
    val expenses: List<Classified<Expense>>,
    val schedules: List<Classified<RecurringExpense>>,
    val invalid: List<InvalidRow>,
```

Replace:

```kotlin
    private val rows: List<Classified<*>> get() = people + handles + groups + members + entries
```

with:

```kotlin
    private val rows: List<Classified<*>> get() = people + handles + groups + members + entries + schedules
```

and:

```kotlin
            conflicts = rows.count { it.kind == Classification.CONFLICT },
```

with:

```kotlin
            conflicts = rows.count { it.kind == Classification.CONFLICT || it.kind == Classification.FORWARD },
```

In `previewRows`, replace:

```kotlin
            val forced = forcedBy(row.record, row.local, take)
            if (row.kind == Classification.CONFLICT || forced != null) {
                shown += ImportRow(row.key, label(row.record), diff(row.local, row.record), forced ?: ImportRowChoice.KeepOrTake, changedSinceLastPreview = false)
            }
```

with:

```kotlin
            val forced = forcedBy(row.record, row.local, take)
            if (row.kind == Classification.CONFLICT || row.kind == Classification.FORWARD || forced != null) {
                shown += ImportRow(
                    row.key, label(row.record), diff(row.local, written(row)), forced ?: ImportRowChoice.KeepOrTake,
                    changedSinceLastPreview = false, forcedChanges = forcedChanges(row),
                )
            }
```

and add after `previewRows`:

```kotlin

    /** What taking [row] writes: for a schedule, the incoming template with the later handled date and the local `autoPostFrom`. */
    private fun written(row: Classified<*>): LedgerEntity {
        val local = row.local as? RecurringExpense ?: return row.record
        return taken(local, row.record as RecurringExpense)
    }

    /** The changes the import makes to [row] whatever its choice: a schedule's later `handledThrough` (spec §7.4). */
    private fun forcedChanges(row: Classified<*>): List<FieldChange> {
        val local = row.local as? RecurringExpense ?: return emptyList()
        if (!movesForward(local, row.record as RecurringExpense)) return emptyList()
        return diff(local, written(row)).filter { it.field == "handledThrough" }
    }
```

In `label`, replace:

```kotlin
        is Expense -> "Expense ${entity.description} on ${entity.occurredOn}"
```

with:

```kotlin
        is Expense -> "Expense ${entity.description} on ${entity.occurredOn}"
        is RecurringExpense -> "Schedule ${entity.description}"
```

Delete the `else -> "${entity.entityType} ${entity.entityId}"` branch of `label`: with `RecurringExpense` every `LedgerEntity` is covered, and the compiler flags the `else` as redundant.

`FORWARD` now has to be handled by every `when (row.kind)` in `plan`: replace each of the three branches `Classification.DUPLICATE -> Unit` (in `writeOwners`, in `writeOwned` and in the entries loop) with `Classification.DUPLICATE, Classification.FORWARD -> Unit`. Only schedules are ever `FORWARD`.

Write the schedules after the FX snapshots and before the entries: replace:

```kotlin
        val snapshots = this.snapshots.map { it.record }
        val entryDeletions = mutableListOf<Classified<*>>()
```

with:

```kotlin
        val snapshots = this.snapshots.map { it.record }
        // Schedules are one unit with their participants. handled_through only moves forward, whatever the choice;
        // auto_post_from is local: a new schedule gets the import day, the cut-off rule (finalize) does the rest.
        val scheduleDeletions = mutableListOf<Pair<Classified<*>, String>>()
        for (row in schedules) {
            val local = row.local
            when (row.kind) {
                Classification.NEW -> {
                    writer.create(row.record.copy(autoPostFrom = today), reasonNew(row))
                    if (row.record.isDeleted) scheduleDeletions += row to reasonDeleted(row)
                }
                Classification.CONFLICT -> if (row.key in take) {
                    writer.update(row, reasonConflict(row), taken(local!!, row.record))
                    if (local.isDeleted != row.record.isDeleted) {
                        if (row.record.isDeleted) scheduleDeletions += row to reasonConflict(row) else writer.restore(row.key, reasonConflict(row))
                    }
                } else if (movesForward(local!!, row.record)) {
                    writer.update(row, reasonForward(row), local.copy(handledThrough = row.record.handledThrough))
                }
                Classification.FORWARD -> writer.update(row, reasonForward(row), local!!.copy(handledThrough = row.record.handledThrough))
                Classification.DUPLICATE -> Unit
            }
        }
        val entryDeletions = mutableListOf<Classified<*>>()
```

and replace:

```kotlin
        for (row in entryDeletions) writer.delete(row.key, now, reasonDeleted(row))
```

with:

```kotlin
        for (row in entryDeletions) writer.delete(row.key, now, reasonDeleted(row))
        for ((row, reason) in scheduleDeletions) writer.delete(row.key, now, reason)
```

Bring the KDoc of `plan` in line with the order of spec §7.5 step 6: replace:

```kotlin
     * handles, groups, memberships, entries), then deletions (entries, owned rows on their own, owners with their
     * cascade). Invalid rows are left out. Rows get [batch] as their `importBatchId` and a reason naming the file
     * and source version. [today] is the import day (Task 3 gives it to new schedules).
```

with:

```kotlin
     * handles, groups, memberships, FX snapshots, schedules, entries), then deletions (entries, schedules, owned
     * rows on their own, owners with their cascade). Invalid rows are left out. Rows get [batch] as their
     * `importBatchId` and a reason naming the file and source version. [today] is the import day, a new schedule's
     * `autoPostFrom`.
```

Add after `reasonConflict`:

```kotlin

    private fun reasonForward(row: Classified<*>) =
        "Imported progress from $fileName (source v${row.incoming.sourceVersion}): handled through ${(row.record as RecurringExpense).handledThrough}${mapping(row)}"
```

In the companion, add after `same`:

```kotlin

        /** A schedule as the import compares it: `handled_through` and `auto_post_from` have rules of their own (spec §7.4). */
        private fun RecurringExpense.comparable() = copy(handledThrough = null, autoPostFrom = LocalDate.MIN)

        /** True when [incoming] handled a later occurrence than [local]. */
        private fun movesForward(local: RecurringExpense, incoming: RecurringExpense): Boolean {
            val later = incoming.handledThrough ?: return false
            return local.handledThrough?.let { later > it } ?: true
        }

        /** The incoming template on the local schedule: the later handled date, the local `autoPostFrom`. */
        private fun taken(local: RecurringExpense, incoming: RecurringExpense): RecurringExpense = incoming.copy(
            handledThrough = if (movesForward(local, incoming)) incoming.handledThrough else local.handledThrough,
            autoPostFrom = local.autoPostFrom,
        )
```

Give `references` the schedule links: replace:

```kotlin
            is Expense -> record.personIds.map(::personKey).toSet() + setOfNotNull(record.groupId?.let(::groupKey))
```

with:

```kotlin
            is Expense -> record.personIds.map(::personKey).toSet() + setOfNotNull(
                record.groupId?.let(::groupKey),
                record.recurringId?.let { ImportRowKey(EntityType.RECURRING_EXPENSE, it.value) },
            )
            is RecurringExpense -> record.personIds.map(::personKey).toSet() + setOfNotNull(record.groupId?.let(::groupKey))
```

In `build`, map and classify the schedules. Replace:

```kotlin
            var duplicates = 0
```

with:

```kotlin
            val schedules = incoming.schedules.map { row ->
                row.mapped { s -> s.copy(participants = s.participants.map { it.copy(personId = mapped(it.personId)) }) }
            }

            var duplicates = 0
```

replace:

```kotlin
            var classifiedExpenses = classify(expenses) { state.expenses[it.id] }
```

with:

```kotlin
            var classifiedExpenses = classify(expenses) { state.expenses[it.id] }
            var classifiedSchedules = schedules.mapNotNull { row ->
                val local = state.schedules[row.record.id]
                when {
                    local == null -> Classified(row, null, Classification.NEW)
                    !same(local.comparable(), row.record.comparable()) -> Classified(row, local, Classification.CONFLICT)
                    movesForward(local, row.record) -> Classified(row, local, Classification.FORWARD)
                    else -> { duplicates++; null }
                }
            }
```

replace:

```kotlin
            classifiedExpenses = classifiedExpenses.checked()

```

with:

```kotlin
            classifiedExpenses = classifiedExpenses.checked()
            classifiedSchedules = classifiedSchedules.checked()

```

(the first of the two occurrences, after the `checked()` helper; the second, after the blame loop, reads `classifiedExpenses = classifiedExpenses.kept()`). In `violations`, replace:

```kotlin
                    classifiedTransfers.taken(true), classifiedExpenses.taken(true), emptyList(), 0, emptySet(), sourceDeletedAt,
```

with:

```kotlin
                    classifiedTransfers.taken(true), classifiedExpenses.taken(true), classifiedSchedules.taken(true),
                    emptyList(), 0, emptySet(), sourceDeletedAt,
```

replace:

```kotlin
                classifiedMembers.kept() + classifiedTransfers.kept() + classifiedExpenses.kept()
```

with:

```kotlin
                classifiedMembers.kept() + classifiedTransfers.kept() + classifiedExpenses.kept() + classifiedSchedules.kept()
```

replace:

```kotlin
                    classifiedTransfers + classifiedExpenses,
```

with:

```kotlin
                    classifiedTransfers + classifiedExpenses + classifiedSchedules,
```

replace:

```kotlin
            classifiedExpenses = classifiedExpenses.kept()
```

with:

```kotlin
            classifiedExpenses = classifiedExpenses.kept()
            classifiedSchedules = classifiedSchedules.kept()
```

and the final constructor call:

```kotlin
                classifiedTransfers, classifiedExpenses, invalid.sortedWith(compareBy({ it.table }, { it.rowNumber })), duplicates,
```

with:

```kotlin
                classifiedTransfers, classifiedExpenses, classifiedSchedules, invalid.sortedWith(compareBy({ it.table }, { it.rowNumber })), duplicates,
```

In the blame comment above `val rejected`, replace `Leaving out a new row (a person or a group)` with `Leaving out a new row (a person, a group or a schedule)` and `Entries answer for the rest.` with `Entries and schedules answer for the rest.` Schedules are left out in the owners pass while active, like entries (`taken(true)`): an active schedule naming a person the import deletes is the schedule's fault, not the person's.

- [ ] **Step 5: `forcedChanges` in the api**

In `main/.../api/TransferModels.kt`, replace:

```kotlin
    /** Set by `refreshPreview` for rows that are new or changed since the previous preview. */
    val changedSinceLastPreview: Boolean,
)
```

with:

```kotlin
    /** Set by `refreshPreview` for rows that are new or changed since the previous preview. */
    val changedSinceLastPreview: Boolean,
    /**
     * Changes the import makes whatever the choice (spec §7.4): a schedule's `handledThrough` moving forward. They
     * also appear in [changes]. A row whose [changes] are all forced has nothing to choose; show it like a cascade row.
     */
    val forcedChanges: List<FieldChange> = emptyList(),
)
```

and replace:

```kotlin
data class ImportCounts(val new: Int, val conflicts: Int, val duplicates: Int, val invalid: Int)
```

with:

```kotlin
/** [conflicts] also counts schedules whose only difference is a later `handledThrough` (applied without a choice). */
data class ImportCounts(val new: Int, val conflicts: Int, val duplicates: Int, val invalid: Int)
```

In `docs/ui-contributor-guide.md`, in the import section (where `ImportRow` and its choices are described), add one paragraph: "`ImportRow.forcedChanges` lists changes the import makes whatever the user picks: a schedule whose copy in the file handled a later date moves forward to it. They are part of `changes` too. A row whose changes are all forced has nothing to choose (show it like a "deleted with" row); a schedule conflict can have both a choice and a forced `handledThrough`. Balance changes now also list my net per group, labelled "<group> (group)"."

- [ ] **Step 6: Run the tests to verify they pass**

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.io.*" --tests "io.github.codex07.iou.data.RealImportTest"`
Expected: PASS: six schedule tests, the reader tests, and the group and M1 import tests unchanged.

Then: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest`
Expected: PASS. (Undo of an import that wrote schedules comes in Task 4; until then such an undo leaves the schedules as the import left them.)

- [ ] **Step 7: Commit**

```bash
git add android/app/src docs/ui-contributor-guide.md
git commit -m "Import schedules with their participants, handled-through forward only"
```

---

### Task 4: Import undo for groups, memberships and schedules, the whole set first

**Files:**
- Create: `test/.../io/ImportUndoTest.kt`
- Modify: `main/.../io/ImportUndo.kt`

**Interfaces:**
- Consumes: Task 2's `Owners`, `rowKey`, `ImportBench`; Task 3's schedule import; `LedgerState.entity`, `LedgerState.entry`, `IdentityResolver.hasMembership` / `isActiveMember`, `LedgerValidator.validate(..., WriteOp.UndoImport)`, `Recurrence.next` (Part 3).
- Produces: `ImportUndo.plan(state, revisions, batch, now): Result` with the same signature and `Result(changes, skipped)` as in M1; it now covers every row type an import writes. `RealImportExportApi.undoImport` and the store's `finalize` are unchanged.

- [ ] **Step 1: Write the failing tests**

Create `test/.../io/ImportUndoTest.kt`:

```kotlin
package io.github.codex07.iou.io

import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.api.SaveMode
import io.github.codex07.iou.api.SkippedRow
import io.github.codex07.iou.api.TransferDraft
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.engine.Recurrence
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.EntryId
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.HandleId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.TransferKind
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.ImportBench
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.T0
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Undo of imports that wrote groups, memberships and schedules (spec §5.4, AC M2-16, 19, 35). */
class ImportUndoTest {

    private fun d(text: String) = LocalDate.parse(text)

    private fun empty(): LedgerState = TestLedger().apply { me() }.state

    private val trip = GroupId("group-trip")
    private val al = PersonId("person-al")

    /**
     * Trip (KEEP EUR): me and Bea active, Al removed. I paid €100 for Al and Al paid it back, so Al's net is 0;
     * Bea paid €60 split with me.
     */
    private fun trip(): LedgerState = TestLedger().run {
        me()
        val al = person("Al")
        val bea = person("Bea")
        val trip = group("Trip", members = listOf(ME, bea))
        member(trip, al, removed = true)
        expense(ME, eur(10_000), mapOf(al to 10_000L), group = trip, id = "e-al")
        transfer(al, ME, eur(10_000), group = trip, kind = TransferKind.REPAYMENT, id = "t-al")
        expense(bea, eur(6_000), mapOf(ME to 3_000L, bea to 3_000L), group = trip, id = "e-bea")
        state
    }

    private fun LedgerState.allNetsZero(): Boolean {
        val balances = LedgerEngine.compute(this)
        return balances.pairwise.values.all { it.isEmpty() } && balances.groupNets.values.all { nets -> nets.values.all { it.isEmpty() } }
    }

    @Test
    fun `undoing an unchanged import of a group with a removed member removes everything and reports nothing`() = runTest {
        val bench = ImportBench(empty())
        val batch = bench.import(trip())

        val result = bench.undo(batch)

        result.skipped shouldBe emptyList()
        val state = bench.state
        state.groups.values.all { it.isDeleted } shouldBe true
        state.members.values.all { it.isDeleted } shouldBe true
        state.entries.all { it.isDeleted } shouldBe true
        state.people.values.filter { !it.isMe }.all { it.isDeleted } shouldBe true
        state.allNetsZero() shouldBe true
    }

    @Test
    fun `a group the undone import created comes back with its memberships when restored`() = runTest {
        val local = TestLedger().run {
            me()
            person("X")
            state
        }
        val source = TestLedger().run {
            me()
            val x = person("X")
            group("Flat", members = listOf(ME, x))
            state
        }
        val bench = ImportBench(local)
        val batch = bench.import(source)

        bench.undo(batch).skipped shouldBe emptyList()

        val flat = bench.state.groups.getValue(GroupId("group-flat"))
        val members = bench.state.members.values.filter { it.groupId == flat.id }
        flat.isDeleted shouldBe true
        members.map { it.meta.deletedAt }.toSet() shouldBe setOf(flat.meta.deletedAt!!)
        with(bench) { groups.restoreGroup(flat.id, token(flat)).ok() }
        bench.state.members.values.filter { it.groupId == flat.id }.all { !it.isDeleted } shouldBe true
    }

    @Test
    fun `undoing an import that brought November's expense and progress makes November due again`() = runTest {
        val rent = RecurringId("rent")
        fun ledger(handled: LocalDate, november: Boolean) = TestLedger().run {
            me()
            val x = person("X")
            schedule(ME, eur(90_000), listOf(ME, x), id = "rent", handledThrough = handled)
            if (november) {
                expense(
                    ME, eur(90_000), mapOf(ME to 45_000L, x to 45_000L), id = "e-nov", occurredOn = d("2026-11-01"),
                    recurring = rent, recurringOn = d("2026-11-01"),
                )
            }
            state
        }
        val bench = ImportBench(ledger(d("2026-10-01"), november = false))
        val batch = bench.import(ledger(d("2026-11-01"), november = true))
        bench.state.schedules.getValue(rent).handledThrough shouldBe d("2026-11-01")

        val result = bench.undo(batch)

        result.skipped shouldBe emptyList()
        bench.state.expenses.getValue(EntryId("e-nov")).isDeleted shouldBe true
        val schedule = bench.state.schedules.getValue(rent)
        schedule.handledThrough shouldBe d("2026-10-01")
        Recurrence.next(schedule, bench.state) shouldBe d("2026-11-01")
    }

    @Test
    fun `when the whole undo breaks a rule, entries tied by a removed member still go back together`() = runTest {
        val bench = ImportBench(empty())
        val batch = bench.import(trip())
        // A transfer added after the import keeps the group and Bea alive: deleting the group would break rules 3 and 10, deleting Bea rule 10.
        with(bench) {
            ledger.saveTransfer(TransferDraft(ME, PersonId("person-bea"), eur(500), groupId = trip, occurredOn = D0), SaveMode.Create(basis())).ok()
        }

        val result = bench.undo(batch)

        val state = bench.state
        listOf("e-al", "t-al", "e-bea").all { state.entry(EntryId(it))!!.isDeleted } shouldBe true
        result.skipped shouldContain SkippedRow(ImportRowKey(EntityType.GROUP, trip.value), "would break rule 3")
        result.skipped shouldContain SkippedRow(ImportRowKey(EntityType.PERSON, "person-bea"), "would break rule 10")
        state.groups.getValue(trip).isDeleted shouldBe false
        LedgerEngine.compute(state).groupNet(trip, al) shouldBe emptyMap()
    }

    @Test
    fun `undoing an import that restored a person and a handle deleted apart deletes them apart again`() = runTest {
        val x = PersonId("person-x")
        val handleId = HandleId("handle-x")
        val earlier = T0.minusSeconds(3_600)
        val local = TestLedger().run {
            me()
            handle(person("X", deleted = true), deleted = true, id = handleId.value)
            state
        }.let { state ->
            val handle = state.handles.getValue(handleId)
            state.with(handle.copy(meta = handle.meta.copy(deletedAt = earlier)))
        }
        val source = TestLedger().run {
            me()
            handle(person("X"), id = handleId.value)
            state
        }
        val bench = ImportBench(local)
        val take = ImportChoices(takeIncoming = setOf(ImportRowKey(EntityType.PERSON, x.value), ImportRowKey(EntityType.PAYMENT_HANDLE, handleId.value)))
        val batch = bench.import(source, take)
        bench.state.handles.getValue(handleId).isDeleted shouldBe false

        bench.undo(batch).skipped shouldBe emptyList()

        bench.state.people.getValue(x).meta.deletedAt shouldBe T0
        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe earlier
        with(bench) { people.restorePerson(x, token(state.people.getValue(x))).ok() }
        bench.state.people.getValue(x).isDeleted shouldBe false
        bench.state.handles.getValue(handleId).isDeleted shouldBe true
    }

    @Test
    fun `undoing an import that moved an earlier deleted handle into its person's cascade gives the handle its own deletedAt back`() = runTest {
        val x = PersonId("person-x")
        val handleId = HandleId("handle-x")
        val earlier = T0.minusSeconds(3_600)
        val local = TestLedger().run {
            me()
            handle(person("X"), deleted = true, id = handleId.value)
            state
        }.let { state ->
            val handle = state.handles.getValue(handleId)
            state.with(handle.copy(meta = handle.meta.copy(deletedAt = earlier)))
        }
        val source = TestLedger().run {
            me()
            handle(person("X", deleted = true), deleted = true, id = handleId.value)
            state
        }
        val bench = ImportBench(local)
        val batch = bench.import(source, ImportChoices(takeIncoming = setOf(ImportRowKey(EntityType.PERSON, x.value))))
        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe bench.now

        bench.undo(batch).skipped shouldBe emptyList()

        bench.state.people.getValue(x).isDeleted shouldBe false
        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe earlier
    }

    @Test
    fun `undoing an import that created a handle under a person kept deleted moves it out of the person's cascade`() = runTest {
        val x = PersonId("person-x")
        val handleId = HandleId("handle-x")
        val local = TestLedger().run {
            me()
            person("X", deleted = true)
            state
        }
        val source = TestLedger().run {
            me()
            handle(person("X", deleted = true), deleted = true, id = handleId.value)
            state
        }
        val bench = ImportBench(local)
        val batch = bench.import(source)
        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe T0

        bench.undo(batch).skipped shouldBe emptyList()

        bench.state.handles.getValue(handleId).meta.deletedAt shouldBe bench.now
        with(bench) { people.restorePerson(x, token(state.people.getValue(x))).ok() }
        bench.state.handles.getValue(handleId).isDeleted shouldBe true
    }
}
```

In the fifth test the handle was deleted an hour before its person. The import restores the person (its cascade takes no handle, since none shares its `deletedAt`) and the handle by its own choice; undo deletes the handle on its own and then the person, each with its own pre-import `deletedAt`, so restoring the person leaves the handle deleted. In the last test the export shows the handle deleted together with its person, so the import moved it into the person's cascade; undo restores the person with that cascade but gives the handle back its own pre-import `deletedAt`, so it stays deleted as before the import. In the seventh test the import created the handle deleted, in the cascade of a person it left deleted; undo can't delete it again, so it moves it to the undo time, out of the person's cascade.

In the fourth test, deleting Al's expense alone leaves Al at +€100 and deleting the repayment alone at −€100 (rule 4), so neither applies on its own; tried together they bring Al back to 0. The group and its memberships stay with "would break rule 3" (the validator reports the lowest broken rule first: the new transfer would have no membership left, and also sit in a deleted group, rule 10); Al (still named by an active membership) and Bea (named by the new transfer) stay with "would break rule 10".

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.io.ImportUndoTest"`
Expected: FAIL: the M1 undo touches no group, membership or schedule rows (the first three tests find them active), and it has no tied steps (the fourth test finds `e-al` and `t-al` active); it deletes the restored person and handle with the undo time, so the fifth test finds both at `now`; and it restores an owner's whole cascade, so the sixth test finds the handle the import moved into the person's cascade active; and it leaves a row created deleted where it is, so the last test finds the handle back with the person.

- [ ] **Step 3: Rewrite the undo**

Replace the whole of `main/.../io/ImportUndo.kt` with:

```kotlin
package io.github.codex07.iou.io

import io.github.codex07.iou.api.ImportRowKey
import io.github.codex07.iou.api.RuleViolation
import io.github.codex07.iou.api.SkippedRow
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.model.Change
import io.github.codex07.iou.ledger.model.EntityType
import io.github.codex07.iou.ledger.model.Entry
import io.github.codex07.iou.ledger.model.EntryRevision
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.ImportBatch
import io.github.codex07.iou.ledger.model.LedgerEntity
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RevisionAction
import io.github.codex07.iou.ledger.rules.LedgerValidator
import io.github.codex07.iou.ledger.rules.WriteOp
import java.time.Instant

/**
 * Undo of one import batch (spec §5.4), best-effort. It reads what the batch did from the revisions tagged
 * with its ID, and works out from the data alone which owned rows changed together with their owner ([Owners]).
 *
 * Order: entries, schedules, owned rows (handles, memberships) undone on their own, then owners (groups,
 * people) with their cascade. A row is undone only if its current version is still the one the import wrote;
 * an owner is skipped when a row its cascade would touch isn't one the import changed through it, unchanged
 * since. Every step the protection leaves is applied and the result is checked once against the state
 * invariants; when it holds, that is the undo. Only otherwise does undo go step by step: a step that would
 * break an invariant waits and is tried again after the others; when a whole round applies nothing, waiting
 * entry steps tied by a removed member of their group are tried together; what still breaks one is skipped.
 * Expenses the batch created are deleted directly, not through the `api` delete, so their schedule's
 * `handledThrough` is not raised and their occurrences are due again.
 */
object ImportUndo {

    data class Result(val changes: List<Change>, val skipped: List<SkippedRow>)

    private class Touched(val revisions: List<EntryRevision>, val pre: LedgerEntity?) {
        val created: Boolean get() = revisions.first().action == RevisionAction.CREATED
        val lastVersion: Int get() = revisions.last().version
        fun did(action: RevisionAction) = revisions.any { it.action == action }
    }

    /** One step of the undo: the rows it puts back and their changes. Steps never share a row. */
    private class Step(val keys: List<ImportRowKey>, val changes: List<Change>)

    fun plan(state: LedgerState, revisions: List<EntryRevision>, batch: ImportBatch, now: Instant): Result {
        if (batch.undoneAt != null) return Result(emptyList(), emptyList())
        val reason = "Undo import ${batch.fileName}"
        val me = state.me.id
        val touched: Map<ImportRowKey, Touched> = revisions.groupBy { ImportRowKey(it.entityType, it.entityId) }
            .mapNotNull { (key, history) ->
                val ofBatch = history.filter { it.importBatchId == batch.id }
                if (ofBatch.isEmpty()) return@mapNotNull null
                val pre = history.lastOrNull { it.version < ofBatch.first().version }?.snapshot
                key to Touched(ofBatch, pre)
            }.toMap()
        fun keys(vararg types: EntityType) = touched.keys.filter { it.type in types }

        // Each step is built against the state the steps before it produce, as if every one of them applies.
        var built = state
        val steps = mutableListOf<Step>()
        val skipped = mutableListOf<SkippedRow>()

        fun entity(key: ImportRowKey): LedgerEntity? = built.entity(key.type, key.id)

        fun unchanged(key: ImportRowKey): Boolean = entity(key)?.meta?.version == touched[key]?.lastVersion

        fun change(entity: LedgerEntity, target: LedgerEntity, deletedAt: Instant?): Change {
            val action = when {
                entity.isDeleted && deletedAt == null -> RevisionAction.RESTORED
                !entity.isDeleted && deletedAt != null -> RevisionAction.DELETED
                else -> RevisionAction.UPDATED
            }
            val meta = entity.meta.copy(updatedAt = now, authorId = me, version = entity.meta.version + 1, deletedAt = deletedAt, importBatchId = null)
            return Change(target.withMeta(meta), action, reason)
        }

        fun add(keys: List<ImportRowKey>, changes: List<Change>) {
            if (changes.isEmpty()) return
            steps += Step(keys, changes)
            built = built.applying(changes)
        }

        /** The undo of one row on its own: delete what it created, put back what it changed. */
        fun ownUndo(key: ImportRowKey): Change? {
            val row = touched.getValue(key)
            val entity = entity(key) ?: return null
            return if (row.created) {
                if (!entity.isDeleted) return change(entity, entity, now)
                // Created deleted into the cascade of an owner this import didn't create (an owner kept deleted):
                // it moves out, to the undo time, so restoring the owner later doesn't bring back an undone row.
                val ownerKey = Owners.ownerOf(entity) ?: return null
                val owner = built.entity(ownerKey.type, ownerKey.id) ?: return null
                val inCascade = touched[ownerKey]?.created != true && owner.meta.deletedAt == entity.meta.deletedAt
                if (inCascade) change(entity, entity, now) else null
            } else {
                val pre = row.pre ?: return null
                // A row deleted before the import gets its own pre-import deletedAt back, whether the import restored
                // it or moved it into an owner's cascade, so it is apart from (or together with) its owner's cascade
                // exactly as before the import (spec §5.2).
                val deletedAt = if (pre.isDeleted) pre.meta.deletedAt else null
                change(entity, pre, deletedAt).takeIf { it.entity.meta.deletedAt != entity.meta.deletedAt || pre.withMeta(entity.meta) != entity }
            }
        }

        /** [entity] with its business fields from before the batch, unless the batch created it. */
        fun beforeImport(entity: LedgerEntity): LedgerEntity = touched[entity.rowKey]?.takeIf { !it.created }?.pre ?: entity

        // Entries, then schedules, each on its own.
        for (key in keys(EntityType.TRANSFER, EntityType.EXPENSE) + keys(EntityType.RECURRING_EXPENSE)) {
            if (!unchanged(key)) {
                skipped += SkippedRow(key, "changed since the import")
                continue
            }
            ownUndo(key)?.let { add(listOf(key), listOf(it)) }
        }

        // Owned rows that changed with their owner go back with the owner's cascade; the rest go back on their own.
        fun changedWithOwner(owned: LedgerEntity, row: Touched): Boolean {
            val ownerKey = Owners.ownerOf(owned) ?: return false
            val owner = touched[ownerKey] ?: return false
            val ownerEntity = state.entity(ownerKey.type, ownerKey.id) ?: return false
            return when {
                owner.created -> row.created
                owner.did(RevisionAction.RESTORED) && !ownerEntity.isDeleted ->
                    row.created || (row.did(RevisionAction.RESTORED) && row.pre?.meta?.deletedAt == owner.pre?.meta?.deletedAt)
                owner.did(RevisionAction.DELETED) && ownerEntity.isDeleted -> owned.meta.deletedAt == ownerEntity.meta.deletedAt
                else -> false
            }
        }
        val withOwner = mutableSetOf<ImportRowKey>()
        for (key in keys(EntityType.PAYMENT_HANDLE, EntityType.GROUP_MEMBER)) {
            val owned = entity(key) ?: continue
            if (changedWithOwner(owned, touched.getValue(key))) {
                withOwner += key
                continue
            }
            if (!unchanged(key)) {
                skipped += SkippedRow(key, "changed since the import")
                continue
            }
            ownUndo(key)?.let { add(listOf(key), listOf(it)) }
        }

        // Owners, with their cascade.
        for (key in keys(EntityType.GROUP) + keys(EntityType.PERSON)) {
            val owner = entity(key) ?: continue
            val row = touched.getValue(key)
            val deleting = !owner.isDeleted && (row.created || row.did(RevisionAction.RESTORED))
            val restoring = owner.isDeleted && !row.created && row.did(RevisionAction.DELETED)
            val cascade = when {
                deleting -> Owners.owned(built, key).filter { !it.isDeleted }
                restoring -> Owners.owned(built, key).filter { it.meta.deletedAt == owner.meta.deletedAt && touched[it.rowKey]?.created != true }
                else -> emptyList()
            }
            val cascadeKeys = cascade.map { it.rowKey }
            if (!unchanged(key)) {
                skipped += SkippedRow(key, "changed since the import")
                cascadeKeys.forEach { skipped += SkippedRow(it, "its owner changed since the import") }
                continue
            }
            if (cascade.any { it.rowKey !in withOwner || !unchanged(it.rowKey) }) {
                skipped += SkippedRow(key, "owns rows changed or added since the import")
                cascadeKeys.forEach { skipped += SkippedRow(it, "owns rows changed or added since the import") }
                continue
            }
            // An owner the import created is deleted now; one it restored gets its pre-import deletedAt back, and its
            // cascade shares it, so a later restore brings back what went together and nothing deleted apart.
            val deletedAt = if (row.created) now else row.pre?.meta?.deletedAt ?: now
            val changes = when {
                deleting -> (listOf(owner) + cascade).map { change(it, beforeImport(it), deletedAt) }
                // An owned row deleted before the import that the import moved into this cascade stays deleted, at
                // its own pre-import deletedAt; the rest come back with the owner.
                restoring -> (listOf(owner) + cascade).map { entity ->
                    val pre = touched[entity.rowKey]?.pre?.takeIf { entity !== owner && it.isDeleted }
                    change(entity, beforeImport(entity), pre?.meta?.deletedAt)
                }
                row.created -> emptyList()
                else -> listOfNotNull(ownUndo(key))
            }
            add(listOf(key) + cascadeKeys, changes)
        }

        /** The state invariants [after] breaks that [before] didn't. */
        fun broken(before: LedgerState, after: LedgerState): List<RuleViolation> {
            val existing = LedgerValidator.validate(before, before, WriteOp.UndoImport).toSet()
            return LedgerValidator.validate(before, after, WriteOp.UndoImport).filter { it !in existing }
        }

        // The whole undo first. An unchanged import always passes: it gives back the pre-import state.
        if (broken(state, built).isEmpty()) return Result(steps.flatMap { it.changes }, skipped)

        // Step by step. A waiting step's rows changed in no other step, so it still applies as built.
        var current = state
        val changes = mutableListOf<Change>()
        fun apply(step: Step): List<Int> {
            val after = current.applying(step.changes)
            val rules = broken(current, after).map { it.rule }
            if (rules.isEmpty()) {
                current = after
                changes += step.changes
            }
            return rules
        }
        var waiting = steps.filter { apply(it).isNotEmpty() }
        while (waiting.isNotEmpty()) {
            val still = waiting.filter { apply(it).isNotEmpty() }
            if (still.size < waiting.size) {
                waiting = still
                continue
            }
            val joined = tied(still, current).filter { cluster ->
                apply(Step(cluster.flatMap { it.keys }, cluster.flatMap { it.changes })).isEmpty()
            }
            if (joined.isEmpty()) break
            val done = joined.flatten().toSet()
            waiting = still.filter { it !in done }
        }
        for (step in waiting) {
            val rule = apply(step).firstOrNull() ?: continue
            step.keys.forEach { skipped += SkippedRow(it, "would break rule $rule") }
        }
        return Result(changes, skipped)
    }

    /**
     * The waiting entry steps that are only valid together (spec §5.4): entries of one group that reference a common
     * member who is removed there, joined transitively, since rule 4 judges a removed member's entries only together.
     * Only clusters of two or more steps are returned.
     */
    private fun tied(waiting: List<Step>, state: LedgerState): List<List<Step>> {
        val resolver = IdentityResolver(state)
        fun removedMembers(step: Step): Set<Pair<GroupId, PersonId>> {
            val target = step.changes.singleOrNull()?.entity as? Entry ?: return emptySet()
            return listOfNotNull(target, state.entry(target.id)).flatMap { entry ->
                val group = entry.groupId ?: return@flatMap emptyList()
                entry.personIds.map(resolver::canonical)
                    .filter { resolver.hasMembership(group, it) && !resolver.isActiveMember(group, it) }
                    .map { group to it }
            }.toSet()
        }
        val clusters = mutableListOf<Pair<MutableList<Step>, MutableSet<Pair<GroupId, PersonId>>>>()
        for (step in waiting) {
            val tags = removedMembers(step)
            if (tags.isEmpty()) continue
            val joined = clusters.filter { (_, members) -> members.any(tags::contains) }
            val merged = mutableListOf(step) to tags.toMutableSet()
            for ((steps, members) in joined) {
                merged.first += steps
                merged.second += members
            }
            clusters.removeAll(joined)
            clusters += merged
        }
        return clusters.map { it.first }.filter { it.size > 1 }
    }
}
```

What changed against M1: rows are keyed by `ImportRowKey` and found through `LedgerState.entity`; schedules get their own step after the entries (a schedule the import created is deleted, one it changed or moved forward gets its pre-import template, `handledThrough` and deleted state back; the cut-off rule in `finalize` then sets `autoPostFrom` where it auto-posts); memberships join handles and groups join people through `Owners`; all steps are built first and the whole set is validated once; and the step-by-step fallback tries tied entry steps together. A row or owner the import restored is deleted again with its own pre-import `deletedAt`, not the undo time (M1 used `now`, which made an owner and a handle deleted apart share a `deletedAt` after the undo, so restoring the owner brought the handle back too); an owner's cascade shares the owner's. Restoring an owner the import deleted gives a handle or membership that was already deleted before the import (and that the import moved into the cascade) its pre-import `deletedAt` back instead of restoring it. `IdentityResolver.hasMembership` and `isActiveMember` take a canonical person, which `resolver.canonical` gives.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.io.*" --tests "io.github.codex07.iou.data.RealImportTest"`
Expected: PASS: seven undo tests, and every M1 undo test (`undo of a moved transfer and its old counterparty's deletion puts both back`, `undo of an import that restored a person and their handle with new values puts the old values back`, `undo deletes what the import created and the balances are back`, `undo skips an owner whose owned rows changed since the import`) unchanged.

Then: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src
git commit -m "Undo imports of groups, memberships and schedules, validating the whole undo first"
```

---

### Task 5: XLSX report and normalized workbooks

**Files:**
- Create: `main/.../io/LedgerWorkbook.kt`, `test/.../io/LedgerWorkbookTest.kt`
- Modify: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`, `main/.../io/LedgerExport.kt`, `main/.../store/ExportRules.kt`, `main/.../data/RealImportExportApi.kt`
- Modify (tests): `test/.../api/contract/ImportExportApiContract.kt`, `test/.../data/ExportSamplesTest.kt`

**Interfaces:**
- Consumes: `NormalizedTables.all`, `CsvTable.header` / `cells` / `rows`, `TextEscaping.escape`, `LedgerExport.scopeError` / `REPORT_HEADER` / `paidBy` (Part 1), `LedgerEngine.compute`, `SettlePlanner.planGroup`, `IdentityResolver.canonicalMembers` / `hasMembership`, `CurrencyCode.fractionDigits`.
- Produces: `data class ReportLine(date, type, description, group, paidBy, from, to, amount, myShare, effectForMe, account, note)` with `cells()`; `LedgerExport.reportLines(state, scope, from, to): LedgerResult<List<ReportLine>>`; `class LedgerWorkbook(export: LedgerExport)` with `normalized(state, out): Int` and `report(state, scope, from, to, out): LedgerResult<Int>`; internal `LedgerWorkbook.cellKind(header, column)`, `LedgerWorkbook.sheetName(name, used)`; `RealImportExportApi(..., workbooks: LedgerWorkbook = LedgerWorkbook(exporter))` (last parameter); `ExportRules` accepts XLSX.

- [ ] **Step 1: Add FastExcel**

In `android/gradle/libs.versions.toml`, under `[versions]` after `commonsCsv = "1.14.1"`, add:

```toml
fastexcel = "0.18.4"
```

and under `[libraries]` after the `commons-csv` line:

```toml
fastexcel = { module = "org.dhatim:fastexcel", version.ref = "fastexcel" }
fastexcel-reader = { module = "org.dhatim:fastexcel-reader", version.ref = "fastexcel" }
```

In `android/app/build.gradle.kts`, after `implementation(libs.commons.csv)`, add `implementation(libs.fastexcel)`, and next to the other `testImplementation(...)` lines add `testImplementation(libs.fastexcel.reader)` (the reader only checks the workbooks in JVM tests). The release build has `isMinifyEnabled = false`, so no keep rules are needed. If an `assemble*` task later fails on duplicate `META-INF` files from the new jars, add the named paths to `packaging { resources { excludes += ... } }` and say so in the report.

- [ ] **Step 2: Write the failing tests**

Create `test/.../io/LedgerWorkbookTest.kt`:

```kotlin
package io.github.codex07.iou.io

import io.github.codex07.iou.api.ExportScope
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.testutil.D0
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.TestLedger
import io.github.codex07.iou.testutil.eur
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.stream.Collectors
import org.dhatim.fastexcel.reader.CellType
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.dhatim.fastexcel.reader.Row
import org.junit.jupiter.api.Test

/** XLSX workbooks (spec §7.3), read back with FastExcel's reader. */
class LedgerWorkbookTest {

    private val workbook = LedgerWorkbook(LedgerExport("test"))

    /** Me, X and Y in Trip (KEEP EUR); X paid €100 split with me, a €30 loan to Y outside groups, and a schedule. */
    private val state: LedgerState = TestLedger().run {
        me()
        val x = person("X")
        val y = person("Y")
        val trip = group("Trip", members = listOf(ME, x))
        expense(
            x, eur(10_000), mapOf(ME to 5_000L, x to 5_000L), group = trip, id = "e-1", payerSplit = PayerSplit.EXACT,
            payers = listOf(ExpensePayer(x, 6_000, 6_000), ExpensePayer(ME, 4_000, 4_000)),
        )
        transfer(ME, y, eur(3_000), id = "t-1")
        schedule(ME, eur(90_000), listOf(ME, y), id = "rent", handledThrough = LocalDate.parse("2026-02-01"))
        state
    }.let { s -> s.with(s.expenses.values.single().copy(description = "=SUM(1)")) }

    private fun sheets(bytes: ByteArray): Map<String, List<Row>> =
        ReadableWorkbook(ByteArrayInputStream(bytes)).use { book ->
            book.sheets.collect(Collectors.toList()).associate { it.name to it.read() }
        }

    private fun Row.text(i: Int): String? = if (i < cellCount) getCell(i)?.text?.takeIf { it.isNotEmpty() } else null

    private fun Row.number(i: Int): BigDecimal = getCell(i).asNumber()

    private fun Row.date(i: Int): LocalDate = getCell(i).asDate().toLocalDate()

    @Test
    fun `the normalized workbook has one sheet per table with typed cells`() {
        val out = ByteArrayOutputStream()

        val rows = workbook.normalized(state, out)
        val sheets = sheets(out.toByteArray())

        sheets.keys.toList() shouldBe NormalizedTables.all.map { it.name }
        rows shouldBe sheets.values.sumOf { it.size - 1 }
        val transfers = sheets.getValue("transfers")
        val header = transfers[0].let { row -> (0 until row.cellCount).map { row.text(it) } }
        header shouldBe NormalizedTables.transfers.header
        val loan = transfers[1]
        loan.number(header.indexOf("amount_minor")).compareTo(BigDecimal(3_000)) shouldBe 0
        loan.number(header.indexOf("amount")).compareTo(BigDecimal("30.00")) shouldBe 0
        loan.date(header.indexOf("occurred_on")) shouldBe D0
        val people = sheets.getValue("people")
        val isMe = people[0].let { row -> (0 until row.cellCount).first { row.text(it) == "is_me" } }
        people.drop(1).map { it.getCell(isMe).type }.toSet() shouldBe setOf(CellType.BOOLEAN)
        val schedules = sheets.getValue("recurring_expenses")
        val scheduleHeader = schedules[0].let { row -> (0 until row.cellCount).map { row.text(it) } }
        schedules[1].date(scheduleHeader.indexOf("handled_through")) shouldBe LocalDate.parse("2026-02-01")
    }

    @Test
    fun `the report workbook has the summary, the report rows and a sheet per group`() {
        val out = ByteArrayOutputStream()

        val result = workbook.report(state, ExportScope.FullLedger, null, null, out)
        val sheets = sheets(out.toByteArray())

        result shouldBe LedgerResult.Ok(2)
        sheets.keys.toList() shouldBe listOf("Summary", "Report", "Trip")
        val report = sheets.getValue("Report")
        (0 until report[0].cellCount).map { report[0].text(it) } shouldBe LedgerExport.REPORT_HEADER
        val expense = report.drop(1).single { it.text(1) == "expense" }
        expense.date(0) shouldBe D0
        expense.text(2) shouldBe "'=SUM(1)"
        expense.text(4) shouldBe "X 60.00; Me 40.00"
        expense.number(7).compareTo(BigDecimal("100.00")) shouldBe 0
        expense.number(10).compareTo(BigDecimal("-10.00")) shouldBe 0
        val summary = sheets.getValue("Summary").drop(1).map { listOf(it.text(0), it.text(1), it.text(2)) to it.number(3) }
        summary.map { it.first } shouldBe listOf(listOf("person", "Y", "EUR"), listOf("group", "Trip", "EUR"))
        summary.map { it.second.setScale(2).toPlainString() } shouldBe listOf("30.00", "-10.00")
        val trip = sheets.getValue("Trip")
        trip.drop(1).take(2).map { it.text(0) to it.number(3).setScale(2).toPlainString() } shouldBe
            listOf("Me" to "-10.00", "X" to "10.00")
        trip.last().let { listOf(it.text(0), it.text(1), it.text(2)) } shouldBe listOf("Me", "X", "EUR")
        trip.last().number(3).setScale(2).toPlainString() shouldBe "10.00"
    }

    @Test
    fun `group sheet names are valid and unique`() {
        val used = mutableSetOf("summary", "report")

        LedgerWorkbook.sheetName("a/b:c", used) shouldBe "a b c"
        LedgerWorkbook.sheetName("Report", used) shouldBe "Report (2)"
        LedgerWorkbook.sheetName("'quoted'", used) shouldBe "quoted"
        LedgerWorkbook.sheetName("x".repeat(40), used) shouldBe "x".repeat(31)
        LedgerWorkbook.sheetName("x".repeat(40), used) shouldBe "x".repeat(27) + " (2)"
        LedgerWorkbook.sheetName("???", used) shouldBe "Group"
    }
}
```

The expense: X paid €60 and I paid €40 of €100 shared 50/50, so my contribution is −€10 (my share €50 minus my €40): `balance_effect_for_me` is −10.00, my net in Trip −10.00 and X's +10.00, and the settle plan has one line, me paying X €10. The loan of €30 to Y gives a non-group balance of +30.00 with Y. The report has two rows; schedules are not report rows (spec §7.2). The sheet order of the report workbook is fixed: `Summary`, `Report`, then the groups by name.

In `test/.../api/contract/ImportExportApiContract.kt`, replace the test `XLSX waits for M2 and a date range can't end before it starts` (from its `@Test` line through its closing brace) with:

```kotlin
    @Test
    fun `XLSX exports both shapes and a date range can't end before it starts`() = runTest {
        setUpMe()
        apis.importExport.export(ExportRequest(ExportShape.REPORT, ExportFormat.XLSX), exportTarget()).shouldBeInstanceOf<LedgerResult.Ok<*>>()
        apis.importExport.export(ExportRequest(ExportShape.NORMALIZED, ExportFormat.XLSX), exportTarget()).shouldBeInstanceOf<LedgerResult.Ok<*>>()
        val reversed = ExportRequest(ExportShape.REPORT, ExportFormat.XLSX, from = TODAY, to = TODAY.minusDays(1))
        apis.importExport.export(reversed, exportTarget()) shouldBe LedgerResult.Err(LedgerError.InvalidField("to"))
    }
```

In `test/.../data/ExportSamplesTest.kt`, replace:

```kotlin
        "report-excel.csv" to ExportRequest(ExportShape.REPORT, ExportFormat.CSV, excelFriendly = true),
    )
```

with:

```kotlin
        "report-excel.csv" to ExportRequest(ExportShape.REPORT, ExportFormat.CSV, excelFriendly = true),
        "report.xlsx" to ExportRequest(ExportShape.REPORT, ExportFormat.XLSX),
        "normalized.xlsx" to ExportRequest(ExportShape.NORMALIZED, ExportFormat.XLSX),
    )
```

If any other test expects `InvalidField("format")` for an XLSX request (search the test tree for `InvalidField("format")`), change it to expect success in the same way. In `data/RealExportTest.kt` (`bad requests are refused before anything is written`), delete the line asserting `InvalidField("format")` for the XLSX report request; the reversed-range and unknown-group lines stay. The `ui/` tests that expect it (`DataManagementTest`, `ExportFields`) belong to the UI developer and still pass because `ui/settings/DataModels.kt` rejects XLSX on its own.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.io.LedgerWorkbookTest" --tests "io.github.codex07.iou.data.RealContractTests"`
Expected: FAIL: `LedgerWorkbook` doesn't exist, and the contract test gets `InvalidField("format")`.

- [ ] **Step 4: Typed report rows**

In `main/.../io/LedgerExport.kt`, add after the `ExportManifest` class:

```kotlin

/** One row of the report shape (spec §7.2), typed: the CSV writes it as text, the workbook as typed cells. */
data class ReportLine(
    val date: LocalDate,
    val type: String,
    val description: String?,
    val group: String?,
    val paidBy: String?,
    val from: String?,
    val to: String?,
    val amount: Money,
    /** In the effect currency. */
    val myShare: Money?,
    /** In the effect currency; positive means owed to me. */
    val effectForMe: Money,
    val account: String?,
    val note: String?,
) {
    /** The CSV cells in [LedgerExport.REPORT_HEADER] order, text escaped (`quote-prefix-v1`). */
    fun cells(): List<String?> = listOf(
        date.toString(), type, description?.let(TextEscaping::escape), group?.let(TextEscaping::escape),
        paidBy?.let(TextEscaping::escape), from?.let(TextEscaping::escape), to?.let(TextEscaping::escape),
        amount.toPlainString(), amount.currency.code, myShare?.toPlainString(), effectForMe.toPlainString(),
        effectForMe.currency.code, account?.let(TextEscaping::escape), note?.let(TextEscaping::escape),
    )
}
```

Replace the whole `report` function (with its KDoc, from `/**` above `fun report(` through its closing brace) with:

```kotlin
    /** The report as CSV: [reportLines] under [REPORT_HEADER]. Not re-importable. Errors: `NotFound` for the scope. */
    fun report(
        state: LedgerState,
        scope: ExportScope,
        from: LocalDate?,
        to: LocalDate?,
        excelFriendly: Boolean,
        out: OutputStream,
    ): LedgerResult<Int> {
        val lines = when (val result = reportLines(state, scope, from, to)) {
            is LedgerResult.Err -> return result
            is LedgerResult.Ok -> result.value
        }
        csv(out, excelFriendly) { printer ->
            printer.printRecord(REPORT_HEADER)
            for (line in lines) printer.printRecord(line.cells())
        }
        return LedgerResult.Ok(lines.size)
    }

    /**
     * The report's rows (spec §7.2): one per active entry in [scope] and the inclusive date range, by date, then ID.
     * Names are canonical. Errors: `NotFound` for a group or person that doesn't exist.
     */
    fun reportLines(state: LedgerState, scope: ExportScope, from: LocalDate?, to: LocalDate?): LedgerResult<List<ReportLine>> {
        scopeError(state, scope)?.let { return LedgerResult.Err(it) }
        val me = state.meOrNull?.id ?: return LedgerResult.Ok(emptyList())
        val resolver = IdentityResolver(state)
        val entries = state.entries
            .filter { !it.isDeleted }
            .filter { (from == null || it.occurredOn >= from) && (to == null || it.occurredOn <= to) }
            .filter { it.inScope(scope, resolver) }
            .sortedWith(compareBy({ it.occurredOn }, { it.id.value }))
        return LedgerResult.Ok(entries.map { reportLine(it, me, state, resolver) })
    }
```

Replace the whole `reportRow` function (from `private fun reportRow(` through its closing brace) with:

```kotlin
    private fun reportLine(entry: Entry, me: PersonId, state: LedgerState, resolver: IdentityResolver): ReportLine {
        fun name(id: PersonId) = state.people[resolver.canonical(id)]?.displayName
        val effectCurrency = entry.effect.currency
        val effectForMe = LedgerEngine.entryEffects(entry, resolver).sumOf { effect ->
            when (me) {
                effect.creditor -> effect.amount.amountMinor
                effect.debtor -> -effect.amount.amountMinor
                else -> 0L
            }
        }
        val transfer = entry as? Transfer
        val expense = entry as? Expense
        val myShare = expense?.shares?.filter { resolver.canonical(it.personId) == me }?.sumOf { it.shareMinor }
        return ReportLine(
            date = entry.occurredOn,
            type = transfer?.kind?.name?.lowercase() ?: "expense",
            description = expense?.description,
            group = entry.groupId?.let { state.groups[it]?.name },
            paidBy = expense?.let { paidBy(it) { id -> name(id) } },
            from = transfer?.let { name(it.fromPersonId) },
            to = transfer?.let { name(it.toPersonId) },
            amount = entry.paid,
            myShare = myShare?.let { Money(it, effectCurrency) },
            effectForMe = Money(effectForMe, effectCurrency),
            account = transfer?.accountLabel,
            note = entry.note,
        )
    }
```

The CSV output is byte for byte what it was: the same cells, in the same order, with the same escaping. `ReportExportTest` proves it unchanged.

- [ ] **Step 5: The workbooks**

Create `main/.../io/LedgerWorkbook.kt`:

```kotlin
package io.github.codex07.iou.io

import io.github.codex07.iou.api.ExportScope
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.engine.Balances
import io.github.codex07.iou.ledger.engine.IdentityResolver
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.engine.SettlePlanner
import io.github.codex07.iou.ledger.model.Group
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.money.Money
import java.io.OutputStream
import java.math.BigDecimal
import java.time.LocalDate
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet

/**
 * XLSX export with FastExcel (spec §7.3). The report workbook has a `Summary` sheet (my non-group balance with each
 * person and my net per group in scope, per currency), a `Report` sheet (the rows of the CSV report) and one sheet
 * per group in scope with its member nets and settle plan. The normalized workbook has one sheet per normalized
 * table with every row. Dates are date cells, amounts numeric cells with the currency's decimals, and string cells
 * get the `quote-prefix-v1` escape like the CSV. Neither is re-importable.
 */
class LedgerWorkbook(private val export: LedgerExport) {

    /** One sheet per table of the normalized shape (spec §7.2). Returns the number of data rows. */
    fun normalized(state: LedgerState, out: OutputStream): Int {
        val workbook = Workbook(out, APP, VERSION)
        val rows = NormalizedTables.all.sumOf { csv -> tableSheet(workbook.newWorksheet(csv.name), csv, state) }
        workbook.finish()
        return rows
    }

    /**
     * The report workbook for [scope] and the date range (which applies to the `Report` sheet; balances are current).
     * A person scope's group sheets are the groups that person belongs to. Errors: `NotFound` for the scope.
     */
    fun report(state: LedgerState, scope: ExportScope, from: LocalDate?, to: LocalDate?, out: OutputStream): LedgerResult<Int> {
        val lines = when (val result = export.reportLines(state, scope, from, to)) {
            is LedgerResult.Err -> return result
            is LedgerResult.Ok -> result.value
        }
        val balances = state.meOrNull?.let { LedgerEngine.compute(state) }
        val resolver = IdentityResolver(state)
        val groups = state.groups.values
            .filter { !it.isDeleted }
            .filter { group ->
                when (scope) {
                    ExportScope.FullLedger -> true
                    is ExportScope.Group -> group.id == scope.id
                    is ExportScope.Person -> resolver.hasMembership(group.id, resolver.canonical(scope.id))
                }
            }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id.value }))
        val workbook = Workbook(out, APP, VERSION)
        summarySheet(workbook.newWorksheet("Summary"), state, scope, balances, resolver, groups)
        reportSheet(workbook.newWorksheet("Report"), lines)
        val used = mutableSetOf("summary", "report")
        for (group in groups) groupSheet(workbook.newWorksheet(sheetName(group.name, used)), state, group, balances, resolver)
        workbook.finish()
        return LedgerResult.Ok(lines.size)
    }

    private fun <T> tableSheet(sheet: Worksheet, table: CsvTable<T>, state: LedgerState): Int {
        val columns = table.header
        val kinds = columns.map { cellKind(columns, it) }
        header(sheet, 0, columns)
        val records = table.rows(state)
        records.forEachIndexed { i, record ->
            table.cells(record).forEachIndexed { c, cell -> if (cell != null) typed(sheet, i + 1, c, kinds[c], cell) }
        }
        return records.size
    }

    /** [cell] as the normalized CSV has it (text already escaped), written as a cell of [kind]. */
    private fun typed(sheet: Worksheet, r: Int, c: Int, kind: CellKind, cell: String) {
        when (kind) {
            CellKind.INTEGER -> sheet.value(r, c, cell.toLong())
            CellKind.DECIMAL -> {
                val number = BigDecimal(cell)
                sheet.value(r, c, number)
                sheet.style(r, c).format(decimals(number.scale())).set()
            }
            CellKind.DATE -> date(sheet, r, c, LocalDate.parse(cell))
            CellKind.BOOLEAN -> sheet.value(r, c, cell == "true")
            CellKind.TEXT -> sheet.value(r, c, cell)
        }
    }

    private fun summarySheet(
        sheet: Worksheet,
        state: LedgerState,
        scope: ExportScope,
        balances: Balances?,
        resolver: IdentityResolver,
        groups: List<Group>,
    ) {
        header(sheet, 0, listOf("kind", "name", "currency", "balance"))
        balances ?: return
        var r = 1
        val people = balances.pairwise.keys
            .filter { scope is ExportScope.FullLedger || (scope is ExportScope.Person && it == resolver.canonical(scope.id)) }
            .sortedWith(compareBy({ name(state, it).lowercase() }, { it.value }))
        for (person in people) {
            for (amount in amounts(balances.net(person))) line(sheet, r++, "person", name(state, person), amount)
        }
        for (group in groups) {
            for (amount in amounts(balances.myGroupNet(group.id))) line(sheet, r++, "group", group.name, amount)
        }
    }

    private fun line(sheet: Worksheet, r: Int, kind: String, name: String, amount: Money) {
        sheet.value(r, 0, kind)
        sheet.value(r, 1, TextEscaping.escape(name))
        sheet.value(r, 2, amount.currency.code)
        money(sheet, r, 3, amount)
    }

    private fun reportSheet(sheet: Worksheet, lines: List<ReportLine>) {
        header(sheet, 0, LedgerExport.REPORT_HEADER)
        lines.forEachIndexed { i, line ->
            val r = i + 1
            date(sheet, r, 0, line.date)
            sheet.value(r, 1, line.type)
            text(sheet, r, 2, line.description)
            text(sheet, r, 3, line.group)
            text(sheet, r, 4, line.paidBy)
            text(sheet, r, 5, line.from)
            text(sheet, r, 6, line.to)
            money(sheet, r, 7, line.amount)
            sheet.value(r, 8, line.amount.currency.code)
            line.myShare?.let { money(sheet, r, 9, it) }
            money(sheet, r, 10, line.effectForMe)
            sheet.value(r, 11, line.effectForMe.currency.code)
            text(sheet, r, 12, line.account)
            text(sheet, r, 13, line.note)
        }
    }

    /** Member nets (positive: the group owes them), then the settle plan. */
    private fun groupSheet(sheet: Worksheet, state: LedgerState, group: Group, balances: Balances?, resolver: IdentityResolver) {
        header(sheet, 0, listOf("member", "active", "currency", "net"))
        var r = 1
        val members = resolver.canonicalMembers(group.id).sortedWith(compareBy({ name(state, it).lowercase() }, { it.value }))
        for (member in members) {
            val nets = balances?.let { amounts(it.groupNet(group.id, member)) }.orEmpty()
            for (net in nets.ifEmpty { listOf(null) }) {
                sheet.value(r, 0, TextEscaping.escape(name(state, member)))
                sheet.value(r, 1, resolver.isActiveMember(group.id, member))
                if (net != null) {
                    sheet.value(r, 2, net.currency.code)
                    money(sheet, r, 3, net)
                }
                r++
            }
        }
        r++
        header(sheet, r++, listOf("from", "to", "currency", "amount"))
        for (item in SettlePlanner.planGroup(balances?.groupNets?.get(group.id).orEmpty())) {
            sheet.value(r, 0, TextEscaping.escape(name(state, item.from)))
            sheet.value(r, 1, TextEscaping.escape(name(state, item.to)))
            sheet.value(r, 2, item.amount.currency.code)
            money(sheet, r, 3, item.amount)
            r++
        }
    }

    private fun header(sheet: Worksheet, r: Int, names: List<String>) = names.forEachIndexed { c, name -> sheet.value(r, c, name) }

    private fun text(sheet: Worksheet, r: Int, c: Int, value: String?) {
        if (value != null) sheet.value(r, c, TextEscaping.escape(value))
    }

    private fun date(sheet: Worksheet, r: Int, c: Int, date: LocalDate) {
        sheet.value(r, c, date)
        sheet.style(r, c).format("yyyy-mm-dd").set()
    }

    private fun money(sheet: Worksheet, r: Int, c: Int, amount: Money) {
        val digits = amount.currency.fractionDigits
        sheet.value(r, c, BigDecimal.valueOf(amount.amountMinor, digits))
        sheet.style(r, c).format(decimals(digits)).set()
    }

    private fun name(state: LedgerState, id: PersonId) = state.people[id]?.displayName ?: id.value

    private fun amounts(map: Map<CurrencyCode, Long>) = map.entries.sortedBy { it.key.code }.map { Money(it.value, it.key) }

    internal enum class CellKind { TEXT, INTEGER, DECIMAL, DATE, BOOLEAN }

    internal companion object {
        private const val APP = "IOU"
        /** FastExcel wants an application version of the form `XX.YYYY`. */
        private const val VERSION = "1.0"
        private val INTEGER_COLUMNS = setOf("position", "version", "interval", "input_value")
        private val DATE_COLUMNS = setOf("rate_date", "handled_through", "auto_post_from")
        private val BOOLEAN_COLUMNS = setOf("is_me", "is_default", "is_stale", "auto_post")

        private fun decimals(digits: Int) = if (digits <= 0) "0" else "0." + "0".repeat(digits)

        /** How a normalized column is written: minor amounts and counters as integers, `x` next to `x_minor` as a decimal. */
        fun cellKind(header: List<String>, column: String): CellKind = when {
            column.endsWith("_minor") || column in INTEGER_COLUMNS -> CellKind.INTEGER
            "${column}_minor" in header -> CellKind.DECIMAL
            column.endsWith("_on") || column in DATE_COLUMNS -> CellKind.DATE
            column in BOOLEAN_COLUMNS -> CellKind.BOOLEAN
            else -> CellKind.TEXT
        }

        /**
         * A valid sheet name for [name] that is not in [used] (compared ignoring case), which it joins: no `[]:*?/\`,
         * no quote at either end, at most 31 characters, "Group" when nothing is left, " (n)" when taken.
         */
        fun sheetName(name: String, used: MutableSet<String>): String {
            val base = name.replace(Regex("""[\[\]:*?/\\]"""), " ").trim().trim('\'').trim().ifEmpty { "Group" }
            var candidate = base.take(31)
            var n = 1
            while (!used.add(candidate.lowercase())) {
                n++
                val suffix = " ($n)"
                candidate = base.take(31 - suffix.length).trimEnd() + suffix
            }
            return candidate
        }
    }
}
```

If the pinned FastExcel version names a method differently (`Worksheet.value` overloads for `Number`, `Boolean`, `String` and `LocalDate`, `style(r, c).format(...).set()`, `Workbook.finish()`), use the equivalent call and say so in the report. `finish()` completes the workbook without closing the stream; the caller's `use` closes it.

- [ ] **Step 6: Allow XLSX and route it**

In `main/.../store/ExportRules.kt`, remove the import `io.github.codex07.iou.api.ExportFormat`, the KDoc bullet ``- `InvalidField("format")`: XLSX arrives in M2 with FastExcel.`` and the line:

```kotlin
        request.format == ExportFormat.XLSX -> LedgerError.InvalidField("format")
```

and change the object's KDoc to `/** Export request rules both flavors share (spec §7.2, §7.3); both formats take both shapes. */`.

In `main/.../data/RealImportExportApi.kt`, add the imports `io.github.codex07.iou.api.ExportFormat` and `io.github.codex07.iou.io.LedgerWorkbook`, add a last constructor parameter:

```kotlin
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val workbooks: LedgerWorkbook = LedgerWorkbook(exporter),
) : ImportExportApi {
```

(replacing the line `    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,` and the `) : ImportExportApi {` after it), and in `export` replace:

```kotlin
                    when (request.shape) {
                        ExportShape.NORMALIZED -> LedgerResult.Ok(exporter.normalized(state, now, request.excelFriendly, out))
                        ExportShape.REPORT -> exporter.report(state, request.scope, request.from, request.to, request.excelFriendly, out)
                    }
```

with:

```kotlin
                    when (request.format) {
                        ExportFormat.CSV -> when (request.shape) {
                            ExportShape.NORMALIZED -> LedgerResult.Ok(exporter.normalized(state, now, request.excelFriendly, out))
                            ExportShape.REPORT -> exporter.report(state, request.scope, request.from, request.to, request.excelFriendly, out)
                        }
                        // excelFriendly only shapes CSV text; a workbook needs no BOM or delimiter.
                        ExportFormat.XLSX -> when (request.shape) {
                            ExportShape.NORMALIZED -> LedgerResult.Ok(workbooks.normalized(state, out))
                            ExportShape.REPORT -> workbooks.report(state, request.scope, request.from, request.to, out)
                        }
                    }
```

The fake's `export` only checks `ExportRules` and counts entries, so it accepts XLSX with no other change.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest`
Expected: PASS, including `LedgerWorkbookTest` (3 tests), both flavors' contract test for XLSX, `ReportExportTest` unchanged, and `ExportSamplesTest`, which now also writes `report.xlsx` and `normalized.xlsx` to `app/build/export-samples/`.

Then: `.\gradlew.bat assembleFakeDebug assembleRealDebug assembleRealRelease`
Expected: BUILD SUCCESSFUL (FastExcel is on the app's runtime classpath).

- [ ] **Step 8: Commit**

```bash
git add android/gradle/libs.versions.toml android/app/build.gradle.kts android/app/src
git commit -m "Export XLSX report and normalized workbooks with FastExcel"
```

---

### Task 6: The full round trip, the docs and the manual checks

**Files:**
- Create: `test/.../testutil/SampleLedgers.kt`, `test/.../io/RoundTripTest.kt`, `test/.../data/RealImportRoundTripTest.kt`
- Modify: `docs/superpowers/specs/2026-11-02-iou-mvp-design.md` (§7.2–7.5), `docs/ui-contributor-guide.md`, `docs/m2-ui-contract-changes.md`, `docs/manual-test-checklist.md`

**Interfaces:**
- Consumes: everything Tasks 1–5 produce; `RealHarness`, `ImportBench`, `TestLedger`.
- Produces: `object SampleLedgers { val LOCAL_ME: PersonId; fun full(): LedgerState; fun localMe(): LedgerState }`; tests for AC 16 (the full version), 19, 20 and 35 end to end; the docs as the code now is.

- [ ] **Step 1: A ledger with everything an export carries**

Create `test/.../testutil/SampleLedgers.kt`:

```kotlin
package io.github.codex07.iou.testutil

import io.github.codex07.iou.ledger.model.ExpensePayer
import io.github.codex07.iou.ledger.model.FxMode
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PayerSplit
import io.github.codex07.iou.ledger.model.Person
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.ledger.model.RecurringId
import io.github.codex07.iou.ledger.model.SplitType
import io.github.codex07.iou.ledger.model.TransferKind
import java.time.LocalDate

/** Ledgers for import and export tests. */
object SampleLedgers {

    /** The me of an install other than the one [full] comes from. */
    val LOCAL_ME = PersonId("local-me")

    /** A fresh install: only its own me. */
    fun localMe(): LedgerState = LedgerState().with(Person(LOCAL_ME, "Me", isMe = true, meta = testMeta()))

    /**
     * Every kind of row a normalized export carries (spec §7.2): people with an alias and handles (one deleted on
     * its own), a CONVERT group with an FX-converted expense, a two-payer expense and a removed member whose
     * expense and repayment cancel out, a deleted group with its memberships, a non-group transfer, a confirm-first
     * schedule with a posted expense, and an auto-post group schedule that starts next year.
     */
    fun full(): LedgerState = TestLedger().run {
        me()
        val alex = person("Alex")
        person("Alex2", mergedInto = alex)
        val bea = person("Bea")
        val cy = person("Cy")
        handle(bea, isDefault = true)
        handle(bea, type = io.github.codex07.iou.ledger.model.HandleType.REVTAG, value = "bea", deleted = true)
        val trip = group("Trip", fxMode = FxMode.CONVERT, members = listOf(ME, alex, bea))
        member(trip, cy, removed = true)
        val sekEur = fxSnapshot(SEK, EUR, "0.09")
        expense(alex, sek(100_000), mapOf(ME to 4_500L, alex to 4_500L), group = trip, effectTotal = eur(9_000), fx = sekEur, splitType = SplitType.EQUAL)
        expense(
            alex, eur(10_000), mapOf(ME to 5_000L, bea to 5_000L), group = trip, payerSplit = PayerSplit.EXACT,
            payers = listOf(ExpensePayer(alex, 6_000, 6_000), ExpensePayer(bea, 4_000, 4_000)),
        )
        expense(ME, eur(2_000), mapOf(cy to 2_000L), group = trip, splitType = SplitType.EQUAL)
        transfer(cy, ME, eur(2_000), group = trip, kind = TransferKind.REPAYMENT)
        group("Old", members = listOf(ME, bea), deleted = true)
        transfer(ME, bea, eur(3_000))
        val rent = schedule(ME, eur(90_000), listOf(ME, bea), id = "rent", handledThrough = LocalDate.parse("2026-02-01"))
        expense(
            ME, eur(90_000), mapOf(ME to 45_000L, bea to 45_000L), splitType = SplitType.EQUAL, id = "rent-feb",
            occurredOn = LocalDate.parse("2026-02-01"), recurring = rent, recurringOn = LocalDate.parse("2026-02-01"),
        )
        schedule(ME, eur(3_000), listOf(ME, alex), group = trip, id = "gym", autoPost = true, startOn = LocalDate.parse("2027-01-01"))
        check(RecurringId("rent") in state.schedules)
        state
    }
}
```

The converted expense is 1 000.00 SEK at 0.09 EUR/SEK, exactly €90.00, so rule 17 holds; its EQUAL shares store no inputs (rule 0). Cy's expense and repayment cancel, so Cy's group net is 0 (rule 4).

- [ ] **Step 2: Write the round-trip tests**

Create `test/.../io/RoundTripTest.kt`:

```kotlin
package io.github.codex07.iou.io

import io.github.codex07.iou.api.ImportIdentity
import io.github.codex07.iou.ledger.engine.LedgerEngine
import io.github.codex07.iou.ledger.model.GroupId
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.ledger.model.PersonId
import io.github.codex07.iou.testutil.ImportBench
import io.github.codex07.iou.testutil.ME
import io.github.codex07.iou.testutil.SampleLedgers
import io.github.codex07.iou.testutil.SampleLedgers.LOCAL_ME
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** A full export into an empty install of another identity, and its undo (spec §7.4, §5.4, AC M2-16, 19, 35). */
class RoundTripTest {

    private fun PersonId.local() = if (this == ME) LOCAL_ME else this

    private fun LedgerState.rowKeys(deleted: Boolean) =
        (people.values.filter { !it.isMe } + handles.values + groups.values + members.values + entries + schedules.values)
            .filter { it.isDeleted == deleted }.map { it.rowKey }.toSet()

    @Test
    fun `a full export imports into an empty install, reproduces every row and net, and undoes to nothing`() = runTest {
        val source = SampleLedgers.full()
        val bench = ImportBench(SampleLedgers.localMe())

        val preview = bench.preview(source)
        val batch = bench.commit(preview)
        val state = bench.state

        preview.identity shouldBe ImportIdentity.Mapped(ME)
        preview.invalidRows shouldBe emptyList()
        state.rowKeys(deleted = false) shouldBe source.rowKeys(deleted = false)
        state.rowKeys(deleted = true) shouldBe source.rowKeys(deleted = true)
        state.fxSnapshots shouldBe source.fxSnapshots
        for (expense in state.expenses.values) {
            val original = source.expenses.getValue(expense.id)
            expense.payers shouldBe original.payers.map { it.copy(personId = it.personId.local()) }
            expense.shares shouldBe original.shares.map { it.copy(personId = it.personId.local()) }
            expense.recurringId shouldBe original.recurringId
            expense.recurringOn shouldBe original.recurringOn
        }
        for (schedule in state.schedules.values) {
            val original = source.schedules.getValue(schedule.id)
            schedule.copy(autoPostFrom = original.autoPostFrom, meta = original.meta) shouldBe
                original.copy(participants = original.participants.map { it.copy(personId = it.personId.local()) })
        }
        val old = state.groups.getValue(GroupId("group-old"))
        state.members.values.filter { it.groupId == old.id }.map { it.meta.deletedAt }.toSet() shouldBe setOf(old.meta.deletedAt!!)
        val before = LedgerEngine.compute(source)
        val after = LedgerEngine.compute(state)
        after.pairwise shouldBe before.pairwise
        after.groupNets shouldBe before.groupNets.mapValues { (_, nets) -> nets.mapKeys { it.key.local() } }

        val undo = bench.undo(batch)

        undo.skipped shouldBe emptyList()
        bench.state.rowKeys(deleted = false) shouldBe emptySet()
        val undone = LedgerEngine.compute(bench.state)
        undone.pairwise.values.all { it.isEmpty() } shouldBe true
        undone.groupNets.values.all { nets -> nets.values.all { it.isEmpty() } } shouldBe true
    }

    @Test
    fun `importing the same export twice writes nothing the second time`() = runTest {
        val source = SampleLedgers.full()
        val bench = ImportBench(SampleLedgers.localMe())
        bench.import(source)
        val once = bench.state

        val again = bench.preview(source, name = "again.zip")
        bench.commit(again)

        again.rows shouldBe emptyList()
        again.counts.new shouldBe 0
        again.counts.conflicts shouldBe 0
        bench.state shouldBe once
    }
}
```

The second test also proves that the schedules' local `autoPostFrom` and the mapped me compare as duplicates: nothing is new, nothing conflicts, and the state is unchanged (the `ImportBatch` is still written, spec §7.5 step 6).

Create `test/.../data/RealImportRoundTripTest.kt`:

```kotlin
package io.github.codex07.iou.data

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.ImportChoices
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.io.LedgerExport
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.RealHarness
import io.github.codex07.iou.testutil.SampleLedgers
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The real flavor stores what a full import wrote (Room schema 2, Part 4) and loads it back after a restart. */
class RealImportRoundTripTest {

    private val harness = RealHarness(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("a"))

    @AfterEach
    fun stop() = harness.stop()

    private fun <T> LedgerResult<T>.orFail(): T = (this as? LedgerResult.Ok)?.value ?: error("Expected Ok, was $this")

    @Test
    fun `imported groups, payers and schedules survive a restart`() = runTest {
        val backend = harness.start()
        backend.apis.setup.completeSetup("Me", EUR).orFail()
        val ref = DocumentRef("full.zip")
        val bytes = ByteArrayOutputStream().also { LedgerExport("test").normalized(SampleLedgers.full(), T0, excelFriendly = false, it) }.toByteArray()
        harness.documents.file(ref).also { requireNotNull(it.parentFile).mkdirs() }.writeBytes(bytes)
        val api = backend.apis.importExport

        val preview = api.confirmIdentity(api.parseImport(ref).orFail(), accept = true).orFail()
        api.commitImport(preview, ImportChoices()).orFail()
        val imported = backend.store.loaded().state
        harness.stop()
        val restarted = harness.start()

        preview.invalidRows shouldBe emptyList()
        restarted.store.loaded().state shouldBe imported
        imported.schedules.keys.map { it.value }.toSet() shouldBe setOf("rent", "gym")
    }
}
```

Nothing is due at `T0` (1 January 2026): rent is confirm-first and handled through February, and the auto-post schedule starts in 2027, so the real backend's runner writes nothing after the import.

- [ ] **Step 3: Run the tests**

Run: `.\gradlew.bat testRealDebugUnitTest --tests "io.github.codex07.iou.io.RoundTripTest" --tests "io.github.codex07.iou.data.RealImportRoundTripTest"`
Expected: PASS (3 tests). These tests are written after the code they exercise; if one fails, the failure is a defect in Tasks 1–5 (or in the sample ledger): fix it there, mirror the fix into this plan, and report it.

- [ ] **Step 4: The spec**

In `docs/superpowers/specs/2026-11-02-iou-mvp-design.md`:

§7.2, in the request rules bullet, replace:

```markdown
XLSX gives `InvalidField("format")` until M2; 
```

with:

```markdown
XLSX takes both shapes (M1 builds gave `InvalidField("format")`); 
```

§7.3, replace:

```markdown
- Not built in M1: an XLSX request gives `InvalidField("format")`. FastExcel-on-Android is validated with it in M2 (§14).
```

with:

```markdown
- Built in M2 with FastExcel (`io.LedgerWorkbook`); M1 builds gave `InvalidField("format")`. FastExcel on Android is checked on the device (§14).
- **Scope details:** the date range applies to the `Report` sheet; `Summary` shows current balances in scope (for a person scope, that person's non-group balance; for a group scope, my net there), and the group sheets are the non-deleted groups in scope (for a person scope, the groups they belong to). Sheet names drop `[]:*?/\` and edge quotes, are cut to 31 characters and get " (2)", " (3)" … when taken.
```

and replace:

```markdown
- **Normalized workbook:** one sheet per table with integer `amount_minor` columns.
```

with:

```markdown
- **Normalized workbook:** one sheet per table, in the order and with the columns of §7.2: `*_minor`, `position`, `version`, `interval` and `input_value` as integers, a decimal money column (one with a `*_minor` partner) as a number, `*_on`, `rate_date`, `handled_through` and `auto_post_from` as dates, booleans as booleans, everything else as text.
```

§7.4, in the bullet that starts `  - **`handled_through` only moves forward.**`, add at its end: ` A schedule whose only difference is a later `handled_through` is listed in the preview with that forced change and nothing to choose (`ImportRow.forcedChanges`, counted with the conflicts).` In the bullet that starts `  - **`auto_post_from` is local state:**`, add at its end: ` A new schedule gets the import day as its `auto_post_from`, whether or not it auto-posts.` Replace the whole bullet that starts `- **Group rows wait for M2.**` with:

```markdown
- **Group rows (M2).** Groups and memberships import like every other row, and groups cascade to their memberships as people do to their handles (§5.2), with the same forced "deleted with" / "restored with" rows. In M1 builds they were invalid rows ("group rows are imported from M2 on").
```

§7.5 step 3.3, replace:

```markdown
A new person is left out together with the rows that name them
```

with:

```markdown
A new person, group or schedule is left out together with the rows that name it
```

and replace:

```markdown
People and handles answer first, for the violations that remain when the incoming active entries are left out too (incoming deletions stay in); then entries answer for the rest.
```

with:

```markdown
People, payment handles, groups and memberships answer first, for the violations that remain when the incoming active entries and schedules are left out too (incoming deletions stay in); then entries and schedules answer for the rest.
```

§7.5 step 5, replace `the resulting balance changes.` with `the resulting balance changes (non-group balances with each person, then my net per group).`

Check each replaced text against the spec before editing (grep it); where the wording differs, apply the same meaning to the wording that is there.

- [ ] **Step 5: The UI docs**

In `docs/ui-contributor-guide.md`, in the export section, replace the statement that XLSX is not available (search for `XLSX`) with: "`ExportFormat.XLSX` takes both shapes: the report workbook (Summary, Report, one sheet per group) and the normalized workbook (one sheet per table). `excelFriendly` only applies to CSV." Keep Task 3's paragraph on `forcedChanges` and group balance lines.

In `docs/m2-ui-contract-changes.md`, add before `## Fake data`:

```markdown
## Import and export

- Group rows now import (groups, memberships, group entries), with the same "deleted with / restored with <owner>" rows as people and their handles; the owner is now a group or a person.
- Schedules import too. A schedule whose copy in the file is further along moves forward by itself: its row has `forcedChanges` (also in `changes`) and nothing to choose. Please show such rows like the "deleted with" rows.
- `balanceChanges` now also lists my net per group, labelled "<group> (group)", after the people.
- XLSX export works for both shapes: the Format field can offer it (and drop the "XLSX arrives later" line).
```

- [ ] **Step 6: The manual checks**

In `docs/manual-test-checklist.md`, section 6, replace:

```markdown
- [ ] XLSX export arrives in M2 (spec §7.3); check it then.
```

with:

```markdown
- [ ] XLSX (spec §7.3, AC M2-15): `ExportSamplesTest` also writes `report.xlsx` and `normalized.xlsx`. Open both in
  Excel and in LibreOffice Calc: they open without a repair prompt; dates are real dates (sortable, shown as
  `yyyy-mm-dd`); amounts are numbers with the currency's decimals; `Summary`, `Report` and one sheet per group
  exist; the person `=HYPERLINK("http://evil","x")` shows as text; the normalized workbook has one sheet per table
  with numeric `*_minor` columns.
```

Add a new section at the end of the file:

```markdown
## 10. M2 import, XLSX on the device and the upgrade (spec §7.3–7.5, §14 item 1, AC M2-15, 16, 25)

Use a disposable emulator instance (see `state.md`, "Devices and native QA") for the upgrade and the import, not
the owner's long-lived AVD.

- [ ] Install `v0.1.0` (the released APK), set up, add a person, a group with a removed member whose net is 0, and
  a few expenses; create an unencrypted backup and a normalized export. Install the `v0.2.0` candidate over it
  (`adb install -r`): the app starts, every balance and group net is unchanged, and every expense shows one payer
  (AC 25).
- [ ] On the upgraded app, export XLSX in both shapes (if the UI offers XLSX; otherwise export through a debug
  build of the export screen or note it as waiting for the UI). Pull the files and open them in Excel and
  LibreOffice: this is the FastExcel-on-Android check of spec §14 item 1.
- [ ] Restore the `v0.1.0` backup and import the `v0.1.0` normalized export into a freshly set-up install (another
  identity): the identity prompt appears, nothing is invalid, every net matches (AC 16, 25). Undo the import: it
  reports nothing skipped and every net is 0.
- [ ] Export the upgraded ledger with a schedule (normalized CSV) and import it back into the same install: every
  row is a duplicate and nothing changes.
```

- [ ] **Step 7: Run the full check and commit**

Run the full check (index, locked decision 9): both JVM suites, the three assembles, both lints. Expected: all tests pass, lint 0 errors.

```bash
git add android/app/src docs
git commit -m "Prove the full export-import round trip and document M2 import and XLSX"
```

---

### Task 7: The v0.2.0 release

**Files:**
- Modify: `android/app/build.gradle.kts`, `README.md`, `docs/superpowers/specs/2026-11-02-iou-mvp-design.md` (§14 item 1), `docs/manual-test-checklist.md` (results)

**Interfaces:**
- Consumes: the whole of M2 (Parts 1–5), reviewed.
- Produces: the signed `v0.2.0` GitHub release.

- [ ] **Step 1: The whole-milestone review**

The orchestrator reviews all of M2 against spec §12 Milestone 2 (ACs 1–35) and the five part plans, as CLAUDE.md requires; fixes go to Sonnet subagents at medium effort and get their own commits. Do not start the next steps until the review has no open finding.

- [ ] **Step 2: Version name, README and the open item**

In `android/app/build.gradle.kts`, replace:

```kotlin
        versionName = System.getenv("RELEASE_VERSION_NAME") ?: "0.1.0"
```

with:

```kotlin
        versionName = System.getenv("RELEASE_VERSION_NAME") ?: "0.2.0"
```

(the release workflow sets `RELEASE_VERSION_NAME` from the tag; the default names local builds).

In `README.md`, replace the status paragraph (the line that starts `> **Status:**`) with:

```markdown
> **Status:** Milestone 2 is complete (v0.2.0): groups with splits, settle plans and archiving, expenses paid by several people, recurring expenses that post by themselves or wait for confirmation (with notifications in the real build), CSV import of groups and schedules with undo, XLSX export, on top of Milestone 1's ledger on Room, history and revert, merges, FX, encrypted backups, CSV export and import, app lock and screen protection. The screens for payers and schedules come from the UI contributor. Some on-device checks are still open (see the [manual test checklist](docs/manual-test-checklist.md)). There is no Compose instrumentation suite yet.
```

Adjust the sentence on the screens to what the UI contributor has merged by then (check `ui/` for payer and schedule screens).

Run checklist section 10 on the emulator. When the XLSX files from the device open correctly, in the spec §14 replace `1. **Open (M2):** confirm the FastExcel writer runs on Android (core library desugaring if needed).` with `1. **Done (M2):** the FastExcel writer runs on Android (minSdk 30, no desugaring needed): workbooks exported on the emulator open in Excel and LibreOffice.` (with the date of the check). Record the results of sections 6 and 10 in the checklist as earlier sections do.

- [ ] **Step 3: Full check, commit and push**

Run the full check (index, locked decision 9). Expected: all tests pass, lint 0 errors.

```bash
git add android/app/build.gradle.kts README.md docs
git commit -m "Prepare v0.2.0"
git push origin main
```

- [ ] **Step 4: Release v0.2.0**

Only with the owner's approval, and once CI is green on this exact `main` commit (the owner checks CI; don't poll it), tag and push:

```bash
git tag v0.2.0
git push origin v0.2.0
```

Expected: the Release workflow passes and the GitHub release `IOU v0.2.0` has `iou-v0.2.0.apk`. Then follow checklist section 7: check the certificate, install `v0.1.0`, upgrade to `v0.2.0` over it and start the app.

---

## Self-review against the spec

- §7.2 normalized shape, schema 2 tables and order, `recurring_participants` columns, expense links: Task 1. Schema-1 files without the new tables: Task 1 (reader), Part 1.
- §7.3 XLSX report and normalized workbooks, scopes, escaping: Task 5; scope details in the spec: Task 6.
- §7.4 classification of groups and memberships with cascades, forced owned rows, `deletedAt` 1 ns apart: Task 2. Schedules as units, `handled_through` forward only and forced, `auto_post_from` local, identity mapping of participants: Task 3. FX snapshot rules and schema-1 imports: unchanged from M1 and Part 1.
- §7.5 blame with groups and schedules, commit order, rule 18 on import (AC 35): Tasks 2–3. One store write with the cut-off rule through `finalize`: Part 3, exercised in Task 3.
- §5.4 undo order, owners and owned rows through `Owners`, schedules reverted with `handledThrough`, created expenses deleted without a raise, whole set first, tied steps: Task 4.
- AC 14 (group variant of the cascade preview), 16, 19, 20 (conflicts of shares and FX snapshots stay as in M1 and Part 1; the group path now reaches them), 35 (import half): Tasks 2–6. AC 15 and §14 item 1: Task 6 checklist, Task 7 device check. AC 25 on the device: Task 6 checklist, Task 7.
