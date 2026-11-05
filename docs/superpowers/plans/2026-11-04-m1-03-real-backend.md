# M1 Part 3: Database Files, Recovery and the Real Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Read `docs/superpowers/plans/2026-11-04-m1-00-index.md` first: its locked decisions and type inventory apply here.

**Goal:** The `real` flavor runs on the Room-backed store. Generation-named database files sit behind an atomically replaced pointer, start-up recovery runs before Room opens anything, device settings are persisted, and the M0 contract suites pass against the real backend: setup, people, groups, ledger and settings, `groups` tests included.

**Architecture:** `DbFiles` owns the database directory (ADR-0007): `iou-<uuid>.db` files, `db-state.json` (current, previous, generation) and `restore-marker.json`. `RestoreRecovery.run` deletes whatever the pointer doesn't name. `LedgerDatabases` opens the database the pointer names and is the `DatabaseProvider` the journal asks on every append, so Part 5 can swap databases under the store's lock. `RealBackend` assembles store, journal, device settings and device ID. Its start-up runs inside `store.exclusive`, so reads and writes wait for it. Hilt binds it in the `real` flavor. FX, backup and import/export stay simulated until Parts 4, 5 and 7.

**Tech Stack:** Room 2.8.1 (framework driver on the device, bundled driver in JVM tests), kotlinx.serialization, `java.nio.file` atomic moves, Hilt, JUnit 5, Kotest, Turbine.

## Global Constraints

- The pointer is only ever replaced atomically: write `db-state.json.tmp`, fsync, rename over `db-state.json`, fsync the directory (spec §7.1). First launch creates the first database and the pointer.
- The ledger generation lives in the pointer and changes only when a pointer is committed (spec §5.1, ADR-0007): an app restart keeps it.
- On every start, before Room opens a database, recovery deletes every `iou-*.db` (with its side files) and any `db-state.json.tmp` the pointer does not name. With a marker present it reports "interrupted" exactly when the pointer's `current` is not the marker's `target`, then deletes the marker (spec §7.1, "Crash recovery").
- The marker is written like the pointer (temp file, fsync, rename), so a crash never leaves half of one. A marker that can't be read anyway counts as none: it only chooses a message, so start-up never fails on it.
- The device ID is generated once per installation in `noBackupFilesDir` and never backed up, restored or exported (spec §3.1).
- Device-local settings never leave the device (spec §3.3, §9).
- The real flavor's start-up does no disk work on the caller's thread: construction returns at once, and reads and writes wait for the load.
- The M0 contract suites must pass against the real implementation (AC M1-14); with groups enabled in M1 (index, locked decision 6) that includes the `groups` tests.

## Decisions this part makes

- **Device settings are a JSON file, not DataStore.** `FileDeviceSettings` keeps them in `noBackupFilesDir/device-settings.json`: read once at start, replaced atomically on every change. DataStore saves with `File.renameTo`, which on Windows can't replace an existing file, so its second write failed in local JVM tests. A file in `noBackupFilesDir` is also outside Auto Backup by location, whatever the backup rules say (spec §9).
  - An update writes the file and then memory under `NonCancellable`. A caller cancelled once the file is written (a settings screen left mid-update) would otherwise skip the memory update, and the next update would write its change over stale settings. The IO dispatcher is a constructor parameter so a test can cancel at exactly that point.
- **All ledger files live in the app's database directory**, which is the parent of `getDatabasePath("db-state.json")`: databases, pointer, temp pointer and marker. Part 8's backup rules include that directory and exclude the temp pointer and the marker.
- **An unreadable marker is dropped without a report.** The pointer alone decides what is live, so nothing is lost; claiming "nothing changed" without knowing the target could be wrong. `deleteMarker` also deletes `restore-marker.json.tmp`, which a crash while writing the marker can leave.
- **Room opens databases by absolute path** (`DbFiles.database(name)`), the same on the device and in JVM tests.
- **No demo rates in the `real` flavor:** its approximate home-currency totals stay `null` (`RateLookup.None`) until Part 4 fills the rate cache.
- **Tests simulate an app restart** by closing a `RealBackend` and starting a new one on the same directory (`RealHarness`). `close()` waits for the start-up, cancels the backend's scope and closes the database.
- **Recovery's "interrupted" result is kept on the backend** (`RealBackend.recovery`); Part 5 surfaces it through `BackupApi`.

## File map

| File | Responsibility |
|---|---|
| `main/.../data/DbFiles.kt` | `DbPointer`, `RestoreMarker`, `DbFiles`, `File.writeAtomically` |
| `main/.../data/RestoreRecovery.kt` | Start-up recovery |
| `main/.../data/LedgerDatabases.kt` | `LedgerDatabases` (opens the pointer's database), `DeviceIdFile` |
| `main/.../data/FileDeviceSettings.kt` | `DeviceSettingsStore` on a JSON file |
| `main/.../data/RealBackend.kt` | Assembles the real backend; `RealBackend.android(context)` |
| `real/.../di/BackendModule.kt` | Hilt binding of `RealBackend` and its `Apis` |
| `test/.../testutil/RealHarness.kt` | Real backends in a temp directory; restart by stop and start |
| `test/.../data/DbFilesTest.kt`, `RestoreRecoveryTest.kt`, `FileDeviceSettingsTest.kt`, `RealBackendTest.kt`, `RealContractTests.kt` | Tests |

`main/...` = `android/app/src/main/kotlin/io/github/codex07/iou/`; `real/...` = `android/app/src/real/kotlin/io/github/codex07/iou/`; `test/...` = `android/app/src/test/kotlin/io/github/codex07/iou/`. Gradle commands run from `android/` (see Part 1 for `JAVA_HOME`).

---

### Task 1: Database files and start-up recovery

**Files:**
- Create: `main/.../data/DbFiles.kt`, `main/.../data/RestoreRecovery.kt`
- Test: `test/.../data/DbFilesTest.kt`, `test/.../data/RestoreRecoveryTest.kt`

**Interfaces:**
- Consumes: kotlinx.serialization (Part 2's dependency).
- Produces:
  - `@Serializable data class DbPointer(current: String, previous: String? = null, generation: String)`.
  - `@Serializable data class RestoreMarker(op: String, target: String)`, where `op` is `"restore"` or `"undo"`.
  - `class DbFiles(dir: File)` with:
    - `pointerFile`, `database(name): File`, `newDatabaseName(uuid)` (giving `"iou-<uuid>.db"`);
    - `readPointer()`, `commitPointer(pointer)`;
    - `readMarker()`, `writeMarker(marker)`, `deleteMarker()`, `deleteTempPointer()`;
    - `ledgerFiles(): Map<String, List<File>>` (files grouped by database name), `deleteDatabase(name)`;
    - constants `POINTER = "db-state.json"` and `MARKER = "restore-marker.json"`.
  - `fun File.writeAtomically(text: String)`.
  - `object RestoreRecovery { data class Result(interrupted: Boolean); fun run(files): Result }`.

- [ ] **Step 1: Write the failing tests**

`test/.../data/DbFilesTest.kt`:

```kotlin
package io.github.codex07.iou.data

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Test

class DbFilesTest {

    private val dir = createTempDirectory("iou-files").toFile()
    private val files = DbFiles(dir)

    private fun touch(name: String) = File(dir, name).writeText("x")

    @Test
    fun `a committed pointer reads back and leaves no temp file`() {
        files.readPointer() shouldBe null
        val pointer = DbPointer(current = "iou-a.db", previous = "iou-b.db", generation = "gen-1")
        files.commitPointer(pointer)
        files.commitPointer(pointer.copy(generation = "gen-2"))

        files.readPointer() shouldBe pointer.copy(generation = "gen-2")
        dir.list()!!.toList() shouldBe listOf(DbFiles.POINTER)
    }

    @Test
    fun `the marker is written, read and deleted`() {
        files.writeMarker(RestoreMarker("restore", "iou-new.db"))
        files.readMarker() shouldBe RestoreMarker("restore", "iou-new.db")
        File(dir, "${DbFiles.MARKER}.tmp").exists() shouldBe false
        files.deleteMarker()
        files.readMarker() shouldBe null
    }

    @Test
    fun `ledger files are grouped by database, and a database is deleted with its side files`() {
        listOf("iou-a.db", "iou-a.db-journal", "iou-a.db.lck", "iou-b.db-wal", "other.txt").forEach(::touch)

        files.ledgerFiles().mapValues { (_, members) -> members.map { it.name } }.let {
            it.keys shouldBe setOf("iou-a.db", "iou-b.db")
            it.getValue("iou-a.db") shouldContainExactlyInAnyOrder listOf("iou-a.db", "iou-a.db-journal", "iou-a.db.lck")
        }
        files.deleteDatabase("iou-a.db")
        dir.list()!!.toList() shouldContainExactlyInAnyOrder listOf("iou-b.db-wal", "other.txt")
    }
}
```

`test/.../data/RestoreRecoveryTest.kt`:

```kotlin
package io.github.codex07.iou.data

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Test

class RestoreRecoveryTest {

    private val dir = createTempDirectory("iou-recovery").toFile()
    private val files = DbFiles(dir)

    private fun touch(vararg names: String) = names.forEach { File(dir, it).writeText("x") }

    private fun present() = dir.list()!!.toList()

    @Test
    fun `a first launch finds nothing to do`() {
        RestoreRecovery.run(files) shouldBe RestoreRecovery.Result(interrupted = false)
        present() shouldBe emptyList()
    }

    @Test
    fun `databases the pointer does not name are deleted with their side files`() {
        files.commitPointer(DbPointer(current = "iou-live.db", previous = "iou-old.db", generation = "g"))
        touch("iou-live.db", "iou-live.db-journal", "iou-old.db", "iou-new.db", "iou-new.db-journal", "db-state.json.tmp")

        RestoreRecovery.run(files)

        present() shouldContainExactlyInAnyOrder listOf(DbFiles.POINTER, "iou-live.db", "iou-live.db-journal", "iou-old.db")
    }

    @Test
    fun `without a pointer every ledger database is left over from a crash`() {
        touch("iou-x.db", "iou-x.db.lck")
        RestoreRecovery.run(files)
        present() shouldBe emptyList()
    }

    @Test
    fun `a marker whose target is not live means the operation never committed`() {
        files.commitPointer(DbPointer(current = "iou-live.db", generation = "g"))
        touch("iou-live.db", "iou-new.db")
        files.writeMarker(RestoreMarker("restore", "iou-new.db"))

        RestoreRecovery.run(files) shouldBe RestoreRecovery.Result(interrupted = true)
        files.readMarker() shouldBe null
        present() shouldContainExactlyInAnyOrder listOf(DbFiles.POINTER, "iou-live.db")
        RestoreRecovery.run(files) shouldBe RestoreRecovery.Result(interrupted = false)
    }

    @Test
    fun `a marker whose target is live means the operation committed`() {
        files.commitPointer(DbPointer(current = "iou-new.db", previous = "iou-live.db", generation = "g2"))
        touch("iou-new.db", "iou-live.db")
        files.writeMarker(RestoreMarker("restore", "iou-new.db"))

        RestoreRecovery.run(files) shouldBe RestoreRecovery.Result(interrupted = false)
        files.readMarker() shouldBe null
    }

    @Test
    fun `a marker cut off while it was written is dropped without a report`() {
        files.commitPointer(DbPointer(current = "iou-live.db", generation = "g"))
        touch("iou-live.db")
        File(dir, DbFiles.MARKER).writeText("""{"op":"rest""")
        File(dir, "${DbFiles.MARKER}.tmp").writeText("""{"op":""")

        RestoreRecovery.run(files) shouldBe RestoreRecovery.Result(interrupted = false)
        present() shouldContainExactlyInAnyOrder listOf(DbFiles.POINTER, "iou-live.db")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.data.DbFilesTest" --tests "io.github.codex07.iou.data.RestoreRecoveryTest"`
Expected: FAIL, compilation errors `Unresolved reference 'DbFiles'`, `'RestoreRecovery'`.

- [ ] **Step 3: Write the database files**

`main/.../data/DbFiles.kt`:

```kotlin
package io.github.codex07.iou.data

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `db-state.json` (spec §7.1): the live database, the pre-restore copy, and the ledger generation. */
@Serializable
data class DbPointer(val current: String, val previous: String? = null, val generation: String)

/** `restore-marker.json` (spec §7.1): only chooses the message shown after a crash. [op] is `restore` or `undo`. */
@Serializable
data class RestoreMarker(val op: String, val target: String)

/**
 * The ledger's files in the app's database directory (spec §7.1, ADR-0007): generation-named databases
 * `iou-<uuid>.db`, the pointer, its temp file and the restore marker. The pointer is only ever replaced
 * atomically; that rename is the commit point of a restore or an undo.
 */
class DbFiles(val dir: File) {
    val pointerFile = File(dir, POINTER)
    private val tempPointer = File(dir, "$POINTER.tmp")
    private val markerFile = File(dir, MARKER)
    private val tempMarker = File(dir, "$MARKER.tmp")

    init {
        dir.mkdirs()
    }

    fun database(name: String): File = File(dir, name)

    fun newDatabaseName(uuid: String) = "iou-$uuid.db"

    fun readPointer(): DbPointer? = pointerFile.takeIf { it.exists() }?.let { json.decodeFromString(DbPointer.serializer(), it.readText()) }

    /** Write temp, fsync, rename over the pointer, fsync the directory: one atomic step. */
    fun commitPointer(pointer: DbPointer) {
        pointerFile.writeAtomically(json.encodeToString(DbPointer.serializer(), pointer))
        syncDirectory()
    }

    /** The marker, or null. One that can't be read counts as none: it only chooses a message. */
    fun readMarker(): RestoreMarker? = markerFile.takeIf { it.exists() }?.let {
        try {
            json.decodeFromString(RestoreMarker.serializer(), it.readText())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Written like the pointer (temp file, rename), so a crash never leaves half a marker. */
    fun writeMarker(marker: RestoreMarker) {
        markerFile.writeAtomically(json.encodeToString(RestoreMarker.serializer(), marker))
        syncDirectory()
    }

    /** Deletes the marker and any temp file a crash left while writing it. */
    fun deleteMarker() {
        markerFile.delete()
        tempMarker.delete()
    }

    fun deleteTempPointer() {
        tempPointer.delete()
    }

    /** Every file that belongs to a ledger database (`iou-*.db` and its side files), by the database it belongs to. */
    fun ledgerFiles(): Map<String, List<File>> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("iou-") }
        .groupBy { file -> SIDE_FILES.fold(file.name) { name, suffix -> name.removeSuffix(suffix) } }

    /** Deletes database [name] and its side files (`-journal`, `-wal`, `-shm`, `.lck`). */
    fun deleteDatabase(name: String) {
        File(dir, name).delete()
        for (suffix in SIDE_FILES) File(dir, name + suffix).delete()
    }

    /** Makes a rename durable. Directories can't be opened for sync on every platform (Windows); there it's skipped. */
    private fun syncDirectory() {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
        }
    }

    companion object {
        const val POINTER = "db-state.json"
        const val MARKER = "restore-marker.json"
        private val SIDE_FILES = listOf("-journal", "-wal", "-shm", ".lck")
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** Writes [text] to a temp file, fsyncs it and renames it over this file in one atomic step. */
fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    val temp = File(parentFile, "$name.tmp")
    temp.outputStream().use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
    Files.move(temp.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
```

- [ ] **Step 4: Write the recovery**

`main/.../data/RestoreRecovery.kt`:

```kotlin
package io.github.codex07.iou.data

/**
 * Runs on every start before Room opens a database (spec §7.1, "Crash recovery"). It deletes every
 * ledger database the pointer does not name, plus a leftover temp pointer, and reads and deletes the restore
 * marker (one it can't read is dropped: it only chooses a message).
 * Nothing is rolled back or inferred from which files exist: the pointer alone says what is live.
 */
object RestoreRecovery {

    /** [interrupted]: a restore or undo was cut off before its commit point; tell the user once that nothing changed. */
    data class Result(val interrupted: Boolean)

    fun run(files: DbFiles): Result {
        val pointer = files.readPointer()
        val keep = setOfNotNull(pointer?.current, pointer?.previous)
        files.deleteTempPointer()
        for ((database, members) in files.ledgerFiles()) {
            if (database !in keep) members.forEach { it.delete() }
        }
        val marker = files.readMarker()
        files.deleteMarker()
        return Result(interrupted = marker != null && pointer?.current != marker.target)
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.data.DbFilesTest" --tests "io.github.codex07.iou.data.RestoreRecoveryTest"`
Expected: PASS, 9 tests (3 + 6).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/io/github/codex07/iou/data android/app/src/test/kotlin/io/github/codex07/iou/data
git commit -m "Add the database pointer files and start-up recovery"
```

---

### Task 2: The real backend

**Files:**
- Create: `main/.../data/LedgerDatabases.kt`, `main/.../data/FileDeviceSettings.kt`, `main/.../data/RealBackend.kt`
- Modify: `real/.../di/BackendModule.kt` (whole file below)
- Create: `test/.../testutil/RealHarness.kt`
- Test: `test/.../data/FileDeviceSettingsTest.kt`, `test/.../data/RealBackendTest.kt`, `test/.../data/RealContractTests.kt`

**Interfaces:**
- Consumes:
  - Task 1's `DbFiles`, `DbPointer`, `RestoreRecovery`.
  - Part 2's `IouDatabase`, `DatabaseProvider`, `RoomJournal`, `LedgerLoader`, `TestDatabases`.
  - Part 1's `LedgerStore`, `RateLookup`, the `Store*Api` classes, `SimpleLockGate`, `DeviceSettingsStore`, `DeviceSettings`.
  - `FakeFxApi`, `FakeBackupApi` and `FakeImportExportApi`, as temporary simulations.
  - The M0 contract suites.
- Produces:
  - `class LedgerDatabases(files, open: (File) -> IouDatabase, ids) : DatabaseProvider` with `openCurrent(): LedgerGeneration` and `close()`.
  - `object DeviceIdFile { fun read(file, ids): DeviceId }`.
  - `class FileDeviceSettings(file) : DeviceSettingsStore`.
  - `class RealBackend(databaseDir, deviceIdFile, settingsFile, openDatabase, clock, ids, scope)` with `files`, `databases`, `device`, `store`, `recovery`, `apis` and `suspend close()`, plus `RealBackend.android(context)`.
  - Test helper `RealHarness(clock, ids)` with `root`, `databaseDir`, `start(): RealBackend` and `stop()`.

- [ ] **Step 1: Write the test harness and the failing tests**

`test/.../testutil/RealHarness.kt`:

```kotlin
package io.github.codex07.iou.testutil

import io.github.codex07.iou.data.RealBackend
import io.github.codex07.iou.ledger.model.IdGenerator
import java.io.File
import java.time.Clock
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * Real backends on the JVM, laid out like the app's private storage in one temp directory. [start] can run
 * again on the same directory after [stop], which is how tests simulate an app restart.
 */
class RealHarness(private val clock: Clock, private val ids: IdGenerator) {
    val root: File = createTempDirectory("iou-real").toFile()
    val databaseDir = File(root, "databases")
    private var running: RealBackend? = null

    fun start(): RealBackend {
        check(running == null) { "Stop the running backend first" }
        return RealBackend(
            databaseDir = databaseDir,
            deviceIdFile = File(root, "no_backup/device-id"),
            settingsFile = File(root, "no_backup/device-settings.json"),
            openDatabase = TestDatabases::file,
            clock = clock,
            ids = ids,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ).also { running = it }
    }

    fun stop() = runBlocking {
        running?.close()
        running = null
    }
}
```

`test/.../data/FileDeviceSettingsTest.kt`:

```kotlin
package io.github.codex07.iou.data

import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.store.DeviceSettings
import io.github.codex07.iou.testutil.T0
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FileDeviceSettingsTest {

    private val file = File(createTempDirectory("iou-device").toFile(), "device-settings.json")

    @Test
    fun `settings start at the spec defaults and persist across instances`() = runTest {
        val first = FileDeviceSettings(file)
        first.settings.first() shouldBe DeviceSettings()
        first.update { it.copy(appLockEnabled = true, lockTimeout = LockTimeout.IMMEDIATELY) }
        first.update { it.copy(screenProtection = true, lastManualBackupAt = T0) }

        val second = FileDeviceSettings(file)
        second.settings.first() shouldBe DeviceSettings(true, LockTimeout.IMMEDIATELY, true, T0)
        second.update { it.copy(lastManualBackupAt = null) }
        FileDeviceSettings(file).settings.first().lastManualBackupAt shouldBe null
        file.parentFile.list()!!.toList() shouldBe listOf("device-settings.json")
    }

    @Test
    fun `an update whose caller is cancelled once the file is written still updates memory`() = runTest {
        val caller = Job()
        val writes = mutableListOf<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                writes += block
            }
        }
        val store = FileDeviceSettings(file, io)
        val update = launch(caller) { store.update { it.copy(appLockEnabled = true) } }
        runCurrent()
        // The write completes, then the caller is cancelled before it resumes, as leaving a settings screen could.
        writes.single().run()
        caller.cancel()
        update.join()

        store.settings.first().appLockEnabled shouldBe true
        FileDeviceSettings(file).settings.first().appLockEnabled shouldBe true
    }
}
```

`test/.../data/RealBackendTest.kt`:

```kotlin
package io.github.codex07.iou.data

import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.api.SetupState
import io.github.codex07.iou.testutil.RealHarness
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class RealBackendTest {

    private val harness = RealHarness(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("real"))

    @AfterEach
    fun stop() = harness.stop()

    private fun <T> LedgerResult<T>.orFail(): T = (this as? LedgerResult.Ok)?.value ?: error("Expected Ok, was $this")

    @Test
    fun `the first start creates the pointer and an empty ledger`() = runTest {
        val backend = harness.start()
        backend.apis.setup.observeSetupState().first() shouldBe SetupState.NeedsSetup

        val pointer = backend.files.readPointer()!!
        pointer.previous shouldBe null
        backend.apis.backup.observeLedgerGeneration().first().value shouldBe pointer.generation
        backend.recovery shouldBe RestoreRecovery.Result(interrupted = false)
    }

    @Test
    fun `the ledger, its settings, the device settings and the generation survive a restart`() = runTest {
        val first = harness.start()
        val me = first.apis.setup.completeSetup("Me", SEK).orFail()
        val basis = first.apis.backup.observeLedgerGeneration().first()
        val x = first.apis.people.createPerson(PersonDraft("X"), basis).orFail()
        first.apis.settings.setLockTimeout(LockTimeout.FIVE_MINUTES).orFail()
        val people = first.apis.people.observePeople().first()
        val deviceId = first.store.deviceId
        harness.stop()

        val second = harness.start()
        (second.apis.setup.observeSetupState().first() as SetupState.Ready).me.id shouldBe me
        second.apis.people.observePeople().first() shouldBe people
        people.single().person.id shouldBe x
        second.apis.backup.observeLedgerGeneration().first() shouldBe basis
        val settings = second.apis.settings.observeSettings().first()
        settings.homeCurrency shouldBe SEK
        settings.lockTimeout shouldBe LockTimeout.FIVE_MINUTES
        second.store.deviceId shouldBe deviceId
    }

    @Test
    fun `a restore cut off before its commit point is reported once and its file is gone`() = runTest {
        harness.start().apis.setup.completeSetup("Me", SEK).orFail()
        harness.stop()
        val files = DbFiles(harness.databaseDir)
        File(harness.databaseDir, "iou-half-built.db").writeText("partial")
        files.writeMarker(RestoreMarker("restore", "iou-half-built.db"))

        val backend = harness.start()
        backend.apis.setup.observeSetupState().first().shouldBeInstanceOf<SetupState.Ready>()
        backend.recovery shouldBe RestoreRecovery.Result(interrupted = true)
        File(harness.databaseDir, "iou-half-built.db").exists() shouldBe false
        files.readMarker() shouldBe null
    }
}
```

`test/.../data/RealContractTests.kt`:

```kotlin
package io.github.codex07.iou.data

import io.github.codex07.iou.api.contract.ApiContract
import io.github.codex07.iou.api.contract.GroupsApiContract
import io.github.codex07.iou.api.contract.LedgerApiContract
import io.github.codex07.iou.api.contract.PeopleApiContract
import io.github.codex07.iou.api.contract.SettingsApiContract
import io.github.codex07.iou.api.contract.SetupApiContract
import io.github.codex07.iou.testutil.RealHarness
import io.github.codex07.iou.testutil.SequentialIds
import java.time.Clock
import java.time.ZoneOffset
import org.junit.jupiter.api.AfterEach

// The M0 contract suites against the real backend on Room (spec §11, AC M1-14), `groups` tests included
// (index, locked decision 6). FX, backup and import/export join in Parts 4, 5 and 7.

private fun harness() = RealHarness(Clock.fixed(ApiContract.NOW, ZoneOffset.UTC), SequentialIds("real"))

class RealSetupApiContractTest : SetupApiContract() {
    private val harness = harness()
    override fun newApis() = harness.start().apis
    @AfterEach fun stop() = harness.stop()
}

class RealPeopleApiContractTest : PeopleApiContract() {
    private val harness = harness()
    override fun newApis() = harness.start().apis
    @AfterEach fun stop() = harness.stop()
}

class RealGroupsApiContractTest : GroupsApiContract() {
    private val harness = harness()
    override fun newApis() = harness.start().apis
    @AfterEach fun stop() = harness.stop()
}

class RealLedgerApiContractTest : LedgerApiContract() {
    private val harness = harness()
    override fun newApis() = harness.start().apis
    @AfterEach fun stop() = harness.stop()
}

class RealSettingsApiContractTest : SettingsApiContract() {
    private val harness = harness()
    override fun newApis() = harness.start().apis
    @AfterEach fun stop() = harness.stop()
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.data.*"`
Expected: FAIL, compilation errors `Unresolved reference 'RealBackend'`, `'FileDeviceSettings'`.

- [ ] **Step 3: Write the database holder and the device ID**

`main/.../data/LedgerDatabases.kt`:

```kotlin
package io.github.codex07.iou.data

import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.ledger.model.LedgerGeneration
import java.io.File

/**
 * Holds the open database named by the pointer (ADR-0007). [RoomJournal] asks it for the live database
 * on every append, so the restore (Part 5) can swap databases under the store's write lock.
 */
class LedgerDatabases(
    val files: DbFiles,
    private val open: (File) -> IouDatabase,
    private val ids: IdGenerator,
) : DatabaseProvider {
    @Volatile private var live: IouDatabase? = null

    /**
     * Opens the database the pointer names and returns the pointer's generation. On first launch it commits
     * a pointer to a new database first (Room creates the file on first use).
     */
    fun openCurrent(): LedgerGeneration {
        val pointer = files.readPointer()
            ?: DbPointer(current = files.newDatabaseName(ids.next()), generation = ids.next()).also(files::commitPointer)
        live = open(files.database(pointer.current))
        return LedgerGeneration(pointer.generation)
    }

    override fun database(): IouDatabase = checkNotNull(live) { "No ledger database is open" }

    fun close() {
        live?.close()
        live = null
    }
}

/** The installation's device ID (spec §3.1): created once in `noBackupFilesDir`, never backed up or restored. */
object DeviceIdFile {
    fun read(file: File, ids: IdGenerator): DeviceId {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(ids.next())
        }
        return DeviceId(file.readText().trim())
    }
}
```

- [ ] **Step 4: Write the device settings file**

`main/.../data/FileDeviceSettings.kt`:

```kotlin
package io.github.codex07.iou.data

import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.store.DeviceSettings
import io.github.codex07.iou.store.DeviceSettingsStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Device-local settings (spec §3.3) as a small JSON file. The app keeps it in `noBackupFilesDir`, so it never
 * leaves the device (spec §9). Read once at construction; every update replaces the file atomically.
 */
class FileDeviceSettings(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DeviceSettingsStore {
    private val lock = Mutex()
    private val state = MutableStateFlow(read())

    override val settings: Flow<DeviceSettings> = state.asStateFlow()

    override suspend fun update(transform: (DeviceSettings) -> DeviceSettings) = lock.withLock {
        val next = transform(state.value)
        if (next == state.value) return@withLock
        // Not cancellable: a caller cancelled once the file is written would otherwise skip the memory update,
        // and the next update would then write its change over stale settings.
        withContext(NonCancellable + io) {
            file.writeAtomically(json.encodeToString(Stored.serializer(), Stored.of(next)))
            state.value = next
        }
    }

    private fun read(): DeviceSettings =
        if (file.exists()) json.decodeFromString(Stored.serializer(), file.readText()).toSettings() else DeviceSettings()

    @Serializable
    private data class Stored(
        val appLockEnabled: Boolean = false,
        val lockTimeout: String = LockTimeout.ONE_MINUTE.name,
        val screenProtection: Boolean = false,
        val lastManualBackupAt: String? = null,
    ) {
        fun toSettings() = DeviceSettings(
            appLockEnabled = appLockEnabled,
            lockTimeout = LockTimeout.entries.firstOrNull { it.name == lockTimeout } ?: LockTimeout.ONE_MINUTE,
            screenProtection = screenProtection,
            lastManualBackupAt = lastManualBackupAt?.let(Instant::parse),
        )

        companion object {
            fun of(settings: DeviceSettings) = Stored(
                settings.appLockEnabled, settings.lockTimeout.name, settings.screenProtection, settings.lastManualBackupAt?.toString(),
            )
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
```

- [ ] **Step 5: Write the backend and bind it**

`main/.../data/RealBackend.kt`:

```kotlin
package io.github.codex07.iou.data

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.fake.FakeBackupApi
import io.github.codex07.iou.fake.FakeFxApi
import io.github.codex07.iou.fake.FakeImportExportApi
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.RateLookup
import io.github.codex07.iou.store.SimpleLockGate
import io.github.codex07.iou.store.StoreGroupsApi
import io.github.codex07.iou.store.StoreLedgerApi
import io.github.codex07.iou.store.StorePeopleApi
import io.github.codex07.iou.store.StoreSettingsApi
import io.github.codex07.iou.store.StoreSetupApi
import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The real flavor's backend (ADR-0010): the shared store with a Room journal, the device settings file and
 * the device ID file. Construction returns at once; [start] runs recovery, opens the database the pointer
 * names and publishes it, and reads and writes wait for that.
 *
 * FX, backup and import/export are still the simulations from `fake`; Parts 4, 5 and 7 replace them.
 */
class RealBackend(
    databaseDir: File,
    deviceIdFile: File,
    settingsFile: File,
    openDatabase: (File) -> IouDatabase,
    clock: Clock,
    ids: IdGenerator,
    private val scope: CoroutineScope,
) {
    val files = DbFiles(databaseDir)
    val databases = LedgerDatabases(files, openDatabase, ids)
    val device = FileDeviceSettings(settingsFile)
    val store = LedgerStore(clock, ids, DeviceIdFile.read(deviceIdFile, ids), RoomJournal(databases), MutableStateFlow(RateLookup.None))

    /** Set once [start] has run recovery. */
    @Volatile var recovery: RestoreRecovery.Result? = null
        private set

    val apis = Apis(
        setup = StoreSetupApi(store),
        people = StorePeopleApi(store),
        groups = StoreGroupsApi(store),
        ledger = StoreLedgerApi(store),
        fx = FakeFxApi(),
        backup = FakeBackupApi(store, device),
        importExport = FakeImportExportApi(store),
        settings = StoreSettingsApi(store, device, SimpleLockGate()),
    )

    private val started: Job = scope.launch { start() }

    private suspend fun start() = store.exclusive { _, publish ->
        recovery = RestoreRecovery.run(files)
        val generation = databases.openCurrent()
        publish(LedgerLoader.load(databases.database()), generation)
    }

    /** Stops background work and closes the database; the backend is unusable afterwards. */
    suspend fun close() {
        started.join()
        scope.coroutineContext.job.cancelAndJoin()
        databases.close()
    }

    companion object {
        /** The app's backend: files in the app's private storage, the framework SQLite driver. */
        fun android(context: Context): RealBackend {
            val app = context.applicationContext
            return RealBackend(
                databaseDir = checkNotNull(app.getDatabasePath(DbFiles.POINTER).parentFile),
                deviceIdFile = File(app.noBackupFilesDir, "device-id"),
                settingsFile = File(app.noBackupFilesDir, "device-settings.json"),
                openDatabase = { IouDatabase.open(app, AndroidSQLiteDriver(), it.absolutePath) },
                clock = Clock.systemUTC(),
                ids = IdGenerator.Random,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
        }
    }
}
```

`real/.../di/BackendModule.kt`:

```kotlin
package io.github.codex07.iou.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.data.RealBackend
import javax.inject.Singleton

/** real flavor: the shared store persisted in Room (ADR-0010). */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides
    @Singleton
    fun backend(@ApplicationContext context: Context): RealBackend = RealBackend.android(context)

    @Provides
    @Singleton
    fun apis(backend: RealBackend): Apis = backend.apis
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.data.*"`
Expected: PASS. `RealContractTests` adds 24 tests (setup 1, people 7, groups 5, ledger 9, settings 2), `RealBackendTest` 3 and `FileDeviceSettingsTest` 2.

Run: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest --rerun-tasks`
Expected: PASS, 261 tests per flavor.

Run: `.\gradlew.bat lintFakeDebug lintRealDebug assembleFakeDebug assembleRealDebug`
Expected: BUILD SUCCESSFUL; lint reports no errors.

- [ ] **Step 7: Check the real flavor on the emulator**

Boot the AVD and install the real debug build:

```bash
emulator -avd Medium_Phone_API_37.0 -no-snapshot-load
```

```bash
.\gradlew.bat installRealDebug
```

```bash
adb shell am start -n io.github.codex07.iou/.MainActivity
```

If an earlier build with a higher version code is installed (e.g. the signed `v0.0.1` release), `installRealDebug` fails with `INSTALL_FAILED_VERSION_DOWNGRADE`; run `adb uninstall io.github.codex07.iou` first. The emulator's copy holds no data worth keeping, since M0's real flavor was in-memory.

Expected:
- The placeholder shows `IOU (real): 0 balances` and the app doesn't crash.
- `adb shell run-as io.github.codex07.iou ls databases no_backup` lists `db-state.json` and one `iou-<uuid>.db`. While the app runs, that database has `-wal`, `-shm` and `.lck` side files. `no_backup` holds `device-id`.
- Force-stop and start the app again. It still starts, `databases/db-state.json` names the same database with the same generation, and no second database appears.
- `adb logcat -d -b crash` is empty.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/kotlin/io/github/codex07/iou/data android/app/src/real android/app/src/test/kotlin/io/github/codex07/iou
git commit -m "Run the real flavor on the Room-backed store"
```
