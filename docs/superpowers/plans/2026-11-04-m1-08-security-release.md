# M1 Part 8: Security, Checks and the v0.1.0 Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Read `docs/superpowers/plans/2026-11-04-m1-00-index.md` first: its locked decisions and type inventory apply here.

**Goal:** Close Milestone 1 with these pieces:
- the app-lock policy on the process lifecycle;
- screen protection (`FLAG_SECURE`);
- Auto Backup rules that back up only the ledger;
- a reviewed merged manifest;
- the spec §14 checks: Frankfurter live, Argon2id timing on the device, the permissions and the backup rules;
- the manual test checklist and the acceptance-criteria walkthrough;
- the signed `v0.1.0` release.

**Architecture:**
- `store.TimedLockGate` replaces `SimpleLockGate` in both flavors. It locks on a cold start when app lock is on, and again on a return after the lock timeout.
- `security.AppSecurity` is installed by `IouApplication`. It feeds the gate from `ProcessLifecycleOwner` and sets `FLAG_SECURE` on every activity window while screen protection is on.
- Auto Backup is enabled with `dataExtractionRules` (API 31+) and `fullBackupContent` (API 30). Both include only the database directory and exclude the pointer's temp file and the restore marker.
- A realDebug-only broadcast receiver times Argon2id on the device.
- `docs/manual-test-checklist.md` collects the checks that need a device, a spreadsheet or the signed APK.

**Tech Stack:** `androidx.lifecycle:lifecycle-process` 2.9.2, Android Auto Backup (`bmgr` on the emulator), `aapt2`, JUnit 5, Kotest, the release workflow from M0.

## Global Constraints

- **App lock** (spec §9): `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` is the UI's (the friend's). The core's policy, `SettingsApi.observeLockRequired()`, requires an unlock on a cold start and after a configurable time in the background (immediately, 1, 5 or 15 min; default 1 min), tracked with `ProcessLifecycleOwner`. App lock can only be enabled when the device has a secure lock screen; the UI checks.
- **Screen protection** (spec §9): a toggle, default off, that sets `FLAG_SECURE` on the single activity.
- **Auto Backup** (spec §9): enabled only for the ledger databases (the `iou-*.db` files, the pre-restore copy included) and their pointer `db-state.json`. Excluded: device-local settings, `noBackupFilesDir` (device ID), `db-state.json.tmp`, `restore-marker.json` and `restore-marker.json.tmp`. `RestoreRecovery` deletes any unreferenced database that arrives with a system restore. A restored phone keeps the me identity and gets a new device ID, and its device-local settings start at their defaults.
- **Permissions** (spec §9): no runtime permissions. Install-time normal permissions only: `INTERNET`, `USE_BIOMETRIC` (from `androidx.biometric`, once the UI adds it) and WorkManager's scheduling permissions. The merged manifest is reviewed before each release; unexpected entries are removed with `tools:node="remove"` where safe.
- **Network:** cleartext traffic is disabled; release builds never log amounts, names or payment handles.
- **Spec §14:** item 2 (Frankfurter parameters), item 3 (Argon2id at 64 MiB on a low-end device; lower the memory if a backup takes more than about 2 s), item 4 (merged permissions) and item 5 (both rule files exclude everything §9 lists).
- **Manual checklist** (spec §11, `docs/manual-test-checklist.md`): app lock on a real device, `FLAG_SECURE`, exported CSV in both modes opened in Excel and LibreOffice (XLSX in M2), and the signed APK installs and upgrades over the previous release.

## Decisions this part makes

- **One lock policy for both flavors:** `store.TimedLockGate`. The fake behaves like the real app, so the friend can build the lock screen against it. `SimpleLockGate` is removed.
- **Start-up reads settings without waiting:** `DeviceSettingsStore.settings` becomes a `StateFlow`. Both implementations already held one. The cold-start lock and the first `FLAG_SECURE` decision read `settings.value`, so no frame shows before them.
- **Background and foreground** are `ProcessLifecycleOwner`'s `ON_STOP` and `ON_START`. `ON_STOP` comes 700 ms after the last activity stops, so rotations and switches between the app's own activities never count.
  - The time away is measured with `SystemClock.elapsedRealtime`, which keeps counting in deep sleep (`System.nanoTime` doesn't).
  - The timeout and the switch are read on return. "Immediately" is a timeout of zero.
  - Turning app lock on mid-session doesn't lock the open app; the M0 contract test keeps that.
- **The core sets `FLAG_SECURE` itself.** An `ActivityLifecycleCallbacks` sets it in `onActivityPreCreated` from the current setting, then follows the setting for the activity's lifetime. The UI only calls `setScreenProtection`.
- **`AppSecurity` is installed at application start** through Hilt field injection in `IouApplication`. The real backend is therefore created when the process starts, not when the first screen asks for it. Its start-up (recovery, opening the database) runs on `Dispatchers.IO` as before.
- **The placeholder shows the lock state** (`IOU (real): 0 balances, locked`), so the on-device check needs no lock screen. `androidx.biometric` and `USE_BIOMETRIC` arrive with the friend's lock screen.
- **An unreadable device settings file counts as the defaults.** It is read at application start now, and a crash there would loop. Writes are atomic, so this only happens through outside tampering.
- **Auto Backup rules:**
  - Both files include `domain="database" path="."`. The generation-named `iou-<uuid>.db` files can't be listed by name. SQLite's side files (`-wal`, `-shm`, `.lck`) travel with their database. WorkManager keeps its own database in `no_backup`.
  - Cloud backup runs only end-to-end encrypted: `disableIfNoEncryptionCapabilities="true"` on API 31+ and `requireFlags="clientSideEncryption"` on API 30. A device-to-device transfer always includes the ledger. On API 30 that takes a second include with `requireFlags="deviceToDeviceTransfer"`, because one include needs all of its flags.
  - Checked on the emulator on 2026-11-05. Its local transport reports no encryption, so the backup is refused ("Backup is not allowed"). With the encryption attribute removed, a backup, `pm clear` and restore brought back only `databases/`, without `restore-marker.json`. Then a new device ID was created and the device settings started at their defaults.
- **Merged manifest** (real release, 2026-11-05):
  - Permissions: `INTERNET`, WorkManager's `WAKE_LOCK`, `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED`, and androidx.core's signature permission `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
  - `FOREGROUND_SERVICE` is removed with `tools:node="remove"`. Only long-running workers need it, and the rate refresh never calls `setForeground`. WorkManager's `SystemForegroundService` stays declared but unused.
  - Exported components: `MainActivity`, `SystemJobService` (`BIND_JOB_SERVICE`), and WorkManager's and profileinstaller's diagnostic receivers (both `DUMP`).
- **Argon2id stays at `KdfParams.DEFAULT`** (64 MiB, t=3, p=1). On the emulator (x86_64, 4 GB), the first encryption took 2.0 s and later ones about 0.65 s per encryption or decryption. The low-end-phone check stays on the manual checklist. Backups record their parameters, so lowering the default later keeps old backups readable.
- **The timing runs from a realDebug-only receiver** (`debug.KdfTimingReceiver`), guarded by `DUMP`, which the shell has and apps don't. It exists in no release build.
- **Frankfurter, live on 2026-11-05:**
  - `latest` and dated `base`/`symbols` requests answered 200 with JSON numbers;
  - Sunday 2026-11-01 answered with `"date":"2026-10-30"`;
  - a future date answered 404;
  - an unknown currency didn't answer within 40 s (404 on 2026-11-04).
  Part 4's client treats all of these as designed: a timeout or a non-200 status is "unavailable".
- **The acceptance-criteria walkthrough uses JVM tests for backup, export and import** (the owner's decision on 2026-11-05). Those flows have no screens yet. The emulator covers what the placeholder can show: lock, `FLAG_SECURE`, Auto Backup, permissions and timing. `ContentDocuments` (SAF) stays unexercised on a device until the friend's screens exist; that is a carried item.
- **Spreadsheet samples** come from a test, `ExportSamplesTest`. It writes the seeded ledger's four CSV exports to `app/build/export-samples/`, including a person named `=HYPERLINK("http://evil","x")`.
- **Release:**
  - `v0.1.0` is tagged on `main` once Part 8 is pushed and CI is green. The version code is the workflow's run number, so it is higher than `v0.0.1`'s.
  - Local builds default to version name `0.1.0`.
  - A malformed `RELEASE_VERSION_CODE` now fails with a clear message (a Part 1 carryover).

## File map

| File | Responsibility |
|---|---|
| `main/.../store/StoreApis.kt` | Reconcile the lock gate with committed device settings on cancellation or error |
| `main/.../store/DeviceSettingsStore.kt` | `DeviceSettingsStore.settings` as `StateFlow`; `LockGate`; `TimedLockGate` (replaces `SimpleLockGate`) |
| `main/.../data/FileDeviceSettings.kt` | `StateFlow` settings; an unreadable file counts as the defaults |
| `main/.../data/RealBackend.kt`, `main/.../fake/FakeApis.kt` | Both flavors on `TimedLockGate` |
| `main/.../security/AppSecurity.kt` | Process lifecycle → lock gate; `FLAG_SECURE` on every activity |
| `main/.../IouApplication.kt`, `main/.../MainActivity.kt` | Install `AppSecurity`; the placeholder shows the lock state |
| `real/.../di/BackendModule.kt`, `fake/.../di/BackendModule.kt` | Provide `DeviceSettingsStore` and `TimedLockGate` |
| `android/app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` | Auto Backup rules for API 30 and API 31+ |
| `android/app/src/main/AndroidManifest.xml`, `android/app/src/real/AndroidManifest.xml` | Enable Auto Backup; remove `FOREGROUND_SERVICE` |
| `android/app/src/realDebug/...` | `KdfTimingReceiver` and its manifest entry |
| `docs/manual-test-checklist.md`, `docs/ui-contributor-guide.md`, `README.md` | Checklist; the UI's part of app lock; M1 status |
| `test/.../store/TimedLockGateTest.kt`, `test/.../data/AutoBackupRulesTest.kt`, `ExportSamplesTest.kt`, `FileDeviceSettingsTest.kt`, `RealBackendTest.kt` | Tests |

`main/...` = `android/app/src/main/kotlin/io/github/codex07/iou/`; `real/...`, `fake/...` and `test/...` likewise for those source sets. Gradle commands run from `android/` (see Part 1 for `JAVA_HOME`).

---

### Task 1: The app-lock policy and screen protection

**Files:**
- Modify: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`
- Modify: `main/.../store/DeviceSettingsStore.kt` (whole file below), `main/.../store/StoreApis.kt`, `main/.../data/FileDeviceSettings.kt`, `main/.../data/RealBackend.kt`, `main/.../fake/FakeApis.kt`, `main/.../MainActivity.kt`
- Modify: `main/.../IouApplication.kt`, `real/.../di/BackendModule.kt`, `fake/.../di/BackendModule.kt` (whole files below)
- Create: `main/.../security/AppSecurity.kt`
- Test: `test/.../store/TimedLockGateTest.kt`; modify `test/.../data/FileDeviceSettingsTest.kt`, `test/.../data/RealBackendTest.kt`

**Interfaces:**
- Consumes: Part 1's `DeviceSettings`, `DeviceSettingsStore`, `InMemoryDeviceSettings`, `LockGate`, `StoreSettingsApi`; Part 3's `FileDeviceSettings`, `RealBackend`, `RealHarness`; M0's `LockTimeout`, `SettingsApi`.
- Produces:
  - `DeviceSettingsStore.settings: StateFlow<DeviceSettings>` (was `Flow`).
  - `class TimedLockGate(device: DeviceSettingsStore, elapsed: () -> Duration = nanoTime) : LockGate`, with `required: StateFlow<Boolean>`, `backgrounded()`, `foregrounded()` and companion `LockTimeout.duration`. `SimpleLockGate` is gone.
  - `RealBackend(…, restoreHook, elapsed: () -> Duration = nanoTime)` with `val lock: TimedLockGate`. `RealBackend.android` passes `SystemClock.elapsedRealtime`.
  - `FakeApis.create(store, device, lock: LockGate = TimedLockGate(device))` and `FakeApis.seeded(clock, device, lock)`; `FakeApis.seeded()` still works without arguments.
  - `class security.AppSecurity @Inject constructor(lock: TimedLockGate, device: DeviceSettingsStore)` with `install(app)`.
  - Hilt bindings for `DeviceSettingsStore` and `TimedLockGate` in both flavors' `BackendModule`.

- [ ] **Step 1: Add `lifecycle-process`**

`android/gradle/libs.versions.toml`:

Replace:

```toml
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
```

with:

```toml
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-process = { module = "androidx.lifecycle:lifecycle-process", version.ref = "lifecycle" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
```

`android/app/build.gradle.kts`:

Replace:

```kotlin
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
```

with:

```kotlin
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
```

- [ ] **Step 2: Write the failing tests**

`test/.../store/TimedLockGateTest.kt`:

```kotlin
package io.github.codex07.iou.store

import io.github.codex07.iou.api.LockTimeout
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TimedLockGateTest {

    private var now = Duration.ZERO

    private fun gate(settings: DeviceSettings = DeviceSettings(appLockEnabled = true)): Pair<TimedLockGate, InMemoryDeviceSettings> {
        val device = InMemoryDeviceSettings(settings)
        return TimedLockGate(device) { now } to device
    }

    private fun TimedLockGate.away(time: Duration) {
        backgrounded()
        now += time
        foregrounded()
    }

    @Test
    fun `a cold start is locked exactly when app lock is on`() {
        gate().first.required.value shouldBe true
        gate(DeviceSettings(appLockEnabled = false)).first.required.value shouldBe false
    }

    @Test
    fun `the app locks again after the timeout in the background, not before`() {
        val (gate, _) = gate()
        gate.unlocked()
        gate.required.value shouldBe false

        gate.away(59.seconds)
        gate.required.value shouldBe false
        gate.away(1.minutes)
        gate.required.value shouldBe true
    }

    @Test
    fun `immediately locks on every return, and a start without a background never locks`() {
        val (gate, _) = gate(DeviceSettings(appLockEnabled = true, lockTimeout = LockTimeout.IMMEDIATELY))
        gate.unlocked()
        gate.foregrounded()
        gate.required.value shouldBe false

        gate.away(Duration.ZERO)
        gate.required.value shouldBe true
    }

    @Test
    fun `the timeout and the switch are read on return`() = runTest {
        val (gate, device) = gate()
        gate.unlocked()

        gate.backgrounded()
        device.update { it.copy(lockTimeout = LockTimeout.FIVE_MINUTES) }
        now += 2.minutes
        gate.foregrounded()
        gate.required.value shouldBe false

        device.update { it.copy(appLockEnabled = false) }
        gate.away(1.minutes * 60)
        gate.required.value shouldBe false
    }

    @Test
    fun `turning app lock off clears it, and turning it on mid-session doesn't lock`() = runTest {
        val (gate, device) = gate()
        device.update { it.copy(appLockEnabled = false) }
        gate.lockEnabledChanged(false)
        gate.required.value shouldBe false

        device.update { it.copy(appLockEnabled = true) }
        gate.lockEnabledChanged(true)
        gate.required.value shouldBe false
        gate.away(1.minutes)
        gate.required.value shouldBe true
    }
}
```

`test/.../data/FileDeviceSettingsTest.kt` adds a test:

Replace:

```kotlin

import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.store.DeviceSettings
import io.github.codex07.iou.testutil.T0
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createTempDirectory
```

with:

```kotlin

import io.github.codex07.iou.api.LockTimeout
import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.store.DeviceSettings
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.StoreSettingsApi
import io.github.codex07.iou.store.TimedLockGate
import io.github.codex07.iou.testutil.SequentialIds
import io.github.codex07.iou.testutil.T0
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createTempDirectory
```

Replace:

```kotlin
    }

    @Test
    fun `an update whose caller is cancelled once the file is written still updates memory`() = runTest {
```

with:

```kotlin
    }

    @Test
    fun `an unreadable file counts as the defaults`() = runTest {
        for (text in listOf("{appLockEnabled:true}", """{"lastManualBackupAt":"yesterday"}""")) {
            file.writeText(text)
            FileDeviceSettings(file).settings.first() shouldBe DeviceSettings()
        }

        val directory = File(requireNotNull(file.parentFile), "device-settings-directory")
        directory.mkdir()
        FileDeviceSettings(directory).settings.first() shouldBe DeviceSettings()
    }

    @Test
    fun `an update whose caller is cancelled once the file is written still updates memory`() = runTest {
```

Replace:

```kotlin
            }
        }
        val store = FileDeviceSettings(file, io)
        val update = launch(caller) { store.update { it.copy(appLockEnabled = true) } }
        runCurrent()
        // The write completes, then the caller is cancelled before it resumes, as leaving a settings screen could.
```

with:

```kotlin
            }
        }
        FileDeviceSettings(file).update { it.copy(appLockEnabled = true) }
        val store = FileDeviceSettings(file, io)
        val gate = TimedLockGate(store)
        val ledger = LedgerStore(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("store"), DeviceId("device-1"), initial = LedgerContent())
        val api = StoreSettingsApi(ledger, store, gate)
        gate.required.value shouldBe true
        val update = launch(caller) { api.setAppLockEnabled(false) }
        runCurrent()
        // The write completes, then the caller is cancelled before it resumes, as leaving a settings screen could.
```

Replace:

```kotlin
        update.join()

        store.settings.first().appLockEnabled shouldBe true
        FileDeviceSettings(file).settings.first().appLockEnabled shouldBe true
    }
}
```

with:

```kotlin
        update.join()

        store.settings.first().appLockEnabled shouldBe false
        FileDeviceSettings(file).settings.first().appLockEnabled shouldBe false
        gate.required.value shouldBe false
    }
}
```

`test/.../data/RealBackendTest.kt` adds a test:

Replace:

```kotlin
    }

    @Test
    fun `a restore cut off before its commit point is reported once and its file is gone`() = runTest {
```

with:

```kotlin
    }

    @Test
    fun `with app lock on, the next start is locked until unlocked`() = runTest {
        val first = harness.start()
        first.apis.settings.setAppLockEnabled(true).orFail()
        first.apis.settings.observeLockRequired().first() shouldBe false
        harness.stop()

        val second = harness.start()
        second.apis.settings.observeLockRequired().first() shouldBe true
        second.apis.settings.markUnlocked()
        second.apis.settings.observeLockRequired().first() shouldBe false
    }

    @Test
    fun `a restore cut off before its commit point is reported once and its file is gone`() = runTest {
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.store.TimedLockGateTest"`
Expected: FAIL, compilation error `Unresolved reference 'TimedLockGate'`.

- [ ] **Step 4: Write the lock policy**

`main/.../store/DeviceSettingsStore.kt`:

```kotlin
package io.github.codex07.iou.store

import io.github.codex07.iou.api.LockTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Device-local settings storage: a file in the real flavor, memory in the fake and in tests. */
interface DeviceSettingsStore {
    /** Readable at once, so start-up decisions (the cold-start lock, `FLAG_SECURE`) never wait. */
    val settings: StateFlow<DeviceSettings>

    suspend fun update(transform: (DeviceSettings) -> DeviceSettings)
}

class InMemoryDeviceSettings(initial: DeviceSettings = DeviceSettings()) : DeviceSettingsStore {
    private val state = MutableStateFlow(initial)

    override val settings: StateFlow<DeviceSettings> = state.asStateFlow()

    override suspend fun update(transform: (DeviceSettings) -> DeviceSettings) = state.update(transform)
}

/** The app-lock state behind `SettingsApi.observeLockRequired` and `markUnlocked` (spec §9). */
interface LockGate {
    val required: Flow<Boolean>

    fun unlocked()

    /** Called after the app-lock setting changed; turning it on mid-session doesn't lock the open app. */
    fun lockEnabledChanged(enabled: Boolean)
}

/**
 * The app-lock policy (spec §9) for both flavors. With app lock on, the app is locked on a cold start and
 * again when it comes back after at least the lock timeout in the background; [LockTimeout.IMMEDIATELY]
 * locks on every return. An unlock or turning app lock off clears it.
 *
 * The process lifecycle calls [backgrounded] and [foregrounded] (`security.AppSecurity`). [elapsed] is a
 * monotonic clock; on Android pass `SystemClock.elapsedRealtime`, which keeps counting in deep sleep.
 */
class TimedLockGate(
    private val device: DeviceSettingsStore,
    private val elapsed: () -> Duration = { System.nanoTime().nanoseconds },
) : LockGate {
    private val state = MutableStateFlow(device.settings.value.appLockEnabled)
    @Volatile private var backgroundedAt: Duration? = null

    override val required: StateFlow<Boolean> = state.asStateFlow()

    override fun unlocked() {
        state.value = false
    }

    override fun lockEnabledChanged(enabled: Boolean) {
        if (!enabled) state.value = false
    }

    fun backgrounded() {
        backgroundedAt = elapsed()
    }

    /** Settings are read on return, so a timeout changed while in the background applies at once. */
    fun foregrounded() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        val settings = device.settings.value
        if (settings.appLockEnabled && elapsed() - since >= settings.lockTimeout.duration) state.value = true
    }

    companion object {
        val LockTimeout.duration: Duration
            get() = when (this) {
                LockTimeout.IMMEDIATELY -> Duration.ZERO
                LockTimeout.ONE_MINUTE -> 1.minutes
                LockTimeout.FIVE_MINUTES -> 5.minutes
                LockTimeout.FIFTEEN_MINUTES -> 15.minutes
            }
    }
}
```

`main/.../data/FileDeviceSettings.kt`:

Replace:

```kotlin
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
```

with:

```kotlin
import io.github.codex07.iou.store.DeviceSettingsStore
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
```

Replace:

```kotlin
/**
 * Device-local settings (spec §3.3) as a small JSON file. The app keeps it in `noBackupFilesDir`, so it never
 * leaves the device (spec §9). Read once at construction; every update replaces the file atomically.
 */
class FileDeviceSettings(
```

with:

```kotlin
/**
 * Device-local settings (spec §3.3) as a small JSON file. The app keeps it in `noBackupFilesDir`, so it never
 * leaves the device (spec §9). Read once at construction; every update replaces the file atomically. An
 * unreadable file counts as the defaults: it is read as the app starts (`AppSecurity`), and a device setting
 * must not crash-loop the app.
 */
class FileDeviceSettings(
```

Replace:

```kotlin
    private val state = MutableStateFlow(read())

    override val settings: Flow<DeviceSettings> = state.asStateFlow()

    override suspend fun update(transform: (DeviceSettings) -> DeviceSettings) = lock.withLock {
```

with:

```kotlin
    private val state = MutableStateFlow(read())

    override val settings: StateFlow<DeviceSettings> = state.asStateFlow()

    override suspend fun update(transform: (DeviceSettings) -> DeviceSettings) = lock.withLock {
```

Replace:

```kotlin
    }

    private fun read(): DeviceSettings =
        if (file.exists()) json.decodeFromString(Stored.serializer(), file.readText()).toSettings() else DeviceSettings()

    @Serializable
```

with:

```kotlin
    }

    private fun read(): DeviceSettings = if (!file.exists()) DeviceSettings() else try {
        json.decodeFromString(Stored.serializer(), file.readText()).toSettings()
    } catch (_: IllegalArgumentException) { // includes SerializationException
        DeviceSettings()
    } catch (_: DateTimeException) {
        DeviceSettings()
    } catch (_: IOException) {
        DeviceSettings()
    }

    @Serializable
```

Reconcile the gate with the actual committed setting even when the update exits by cancellation or error. A failed disable must not clear a required gate; enabling mid-session must not lock it.

`main/.../store/StoreApis.kt`:

Replace:

```kotlin

    override suspend fun setAppLockEnabled(enabled: Boolean) = ok {
        device.update { it.copy(appLockEnabled = enabled) }
        lock.lockEnabledChanged(enabled)
    }
```

with:

```kotlin

    override suspend fun setAppLockEnabled(enabled: Boolean) = ok {
        try {
            device.update { it.copy(appLockEnabled = enabled) }
        } finally {
            lock.lockEnabledChanged(device.settings.value.appLockEnabled)
        }
    }
```

- [ ] **Step 5: Put both flavors on it**

`main/.../data/RealBackend.kt`:

Replace:

```kotlin

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import io.github.codex07.iou.BuildConfig
```

with:

```kotlin

import android.content.Context
import android.os.SystemClock
import androidx.sqlite.driver.AndroidSQLiteDriver
import io.github.codex07.iou.BuildConfig
```

Replace:

```kotlin
import io.github.codex07.iou.store.DocumentStore
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.SimpleLockGate
import io.github.codex07.iou.store.StoreFxApi
import io.github.codex07.iou.store.StoreGroupsApi
```

with:

```kotlin
import io.github.codex07.iou.store.DocumentStore
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.StoreFxApi
import io.github.codex07.iou.store.StoreGroupsApi
```

Replace:

```kotlin
import io.github.codex07.iou.store.StoreSettingsApi
import io.github.codex07.iou.store.StoreSetupApi
import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
```

with:

```kotlin
import io.github.codex07.iou.store.StoreSettingsApi
import io.github.codex07.iou.store.StoreSetupApi
import io.github.codex07.iou.store.TimedLockGate
import java.io.File
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
```

Replace:

```kotlin
    appVersion: String = "",
    restoreHook: RestoreHook = RestoreHook.None,
) {
    val files = DbFiles(databaseDir)
```

with:

```kotlin
    appVersion: String = "",
    restoreHook: RestoreHook = RestoreHook.None,
    elapsed: () -> Duration = { System.nanoTime().nanoseconds },
) {
    val files = DbFiles(databaseDir)
```

Replace:

```kotlin
    val restores = RestoreProcedure(store, files, databases, fx, ids, restoreHook)

    /** Set once [start] has run recovery. */
    @Volatile var recovery: RestoreRecovery.Result? = null
```

with:

```kotlin
    val restores = RestoreProcedure(store, files, databases, fx, ids, restoreHook)

    /** The app-lock policy; `security.AppSecurity` feeds it the process lifecycle. */
    val lock = TimedLockGate(device, elapsed)

    /** Set once [start] has run recovery. */
    @Volatile var recovery: RestoreRecovery.Result? = null
```

Replace:

```kotlin
        backup = RealBackupApi(store, device, documents, backupFormat, fx, restores, interrupted, appVersion),
        importExport = RealImportExportApi(store, documents, LedgerExport(appVersion)),
        settings = StoreSettingsApi(store, device, SimpleLockGate()),
    )
```

with:

```kotlin
        backup = RealBackupApi(store, device, documents, backupFormat, fx, restores, interrupted, appVersion),
        importExport = RealImportExportApi(store, documents, LedgerExport(appVersion)),
        settings = StoreSettingsApi(store, device, lock),
    )
```

Replace:

```kotlin
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                appVersion = BuildConfig.VERSION_NAME,
            )
        }
```

with:

```kotlin
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                appVersion = BuildConfig.VERSION_NAME,
                elapsed = { SystemClock.elapsedRealtime().milliseconds },
            )
        }
```

`main/.../fake/FakeApis.kt`:

Replace:

```kotlin
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.RateLookup
import io.github.codex07.iou.store.SimpleLockGate
import io.github.codex07.iou.store.StoreGroupsApi
import io.github.codex07.iou.store.StoreLedgerApi
```

with:

```kotlin
import io.github.codex07.iou.store.LedgerContent
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.LockGate
import io.github.codex07.iou.store.RateLookup
import io.github.codex07.iou.store.StoreGroupsApi
import io.github.codex07.iou.store.StoreLedgerApi
```

Replace:

```kotlin
import io.github.codex07.iou.store.StoreSettingsApi
import io.github.codex07.iou.store.StoreSetupApi
import java.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
```

with:

```kotlin
import io.github.codex07.iou.store.StoreSettingsApi
import io.github.codex07.iou.store.StoreSetupApi
import io.github.codex07.iou.store.TimedLockGate
import java.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
```

Replace:

```kotlin
        LedgerStore(clock, ids, DeviceId(ids.next()), rates = MutableStateFlow(RateLookup(FakeRates::rate)), initial = LedgerContent())

    fun create(store: LedgerStore, device: DeviceSettingsStore = InMemoryDeviceSettings()): Apis = Apis(
        setup = StoreSetupApi(store),
        people = StorePeopleApi(store),
```

with:

```kotlin
        LedgerStore(clock, ids, DeviceId(ids.next()), rates = MutableStateFlow(RateLookup(FakeRates::rate)), initial = LedgerContent())

    fun create(
        store: LedgerStore,
        device: DeviceSettingsStore = InMemoryDeviceSettings(),
        lock: LockGate = TimedLockGate(device),
    ): Apis = Apis(
        setup = StoreSetupApi(store),
        people = StorePeopleApi(store),
```

Replace:

```kotlin
        backup = FakeBackupApi(store, device),
        importExport = FakeImportExportApi(store),
        settings = StoreSettingsApi(store, device, SimpleLockGate()),
    )

    /** The fake with demo data, as the `fake` flavor and Compose previews use it. */
    fun seeded(clock: Clock = Clock.systemUTC()): Apis = create(store(clock).also { runBlocking { SeedData.populate(it) } })
}
```

with:

```kotlin
        backup = FakeBackupApi(store, device),
        importExport = FakeImportExportApi(store),
        settings = StoreSettingsApi(store, device, lock),
    )

    /** The fake with demo data, as the `fake` flavor and Compose previews use it. */
    fun seeded(
        clock: Clock = Clock.systemUTC(),
        device: DeviceSettingsStore = InMemoryDeviceSettings(),
        lock: LockGate = TimedLockGate(device),
    ): Apis = create(store(clock).also { runBlocking { SeedData.populate(it) } }, device, lock)
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.store.*" --tests "io.github.codex07.iou.data.RealBackendTest" --tests "io.github.codex07.iou.data.FileDeviceSettingsTest" --tests "*SettingsApiContractTest"`
Expected: PASS, 26 tests. New: `TimedLockGateTest` 5, `RealBackendTest` 1 (7 in all), `FileDeviceSettingsTest` 1 (3 in all). The settings contract still passes against both backends.

- [ ] **Step 7: Install the policy on the device**

`main/.../security/AppSecurity.kt`:

```kotlin
package io.github.codex07.iou.security

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.codex07.iou.store.DeviceSettingsStore
import io.github.codex07.iou.store.TimedLockGate
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * App lock and screen protection on the device (spec §9), for both flavors. `IouApplication` calls [install]
 * once. Then:
 * - the process lifecycle drives [lock]: start is a return to the foreground, stop is going to the background.
 *   `ProcessLifecycleOwner` reports stop 700 ms after the last activity stops, so a rotation never counts;
 * - every activity's window has `FLAG_SECURE` while screen protection is on, from before its `onCreate` and
 *   following later changes of the setting.
 */
class AppSecurity @Inject constructor(private val lock: TimedLockGate, private val device: DeviceSettingsStore) {

    fun install(app: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = lock.foregrounded()

            override fun onStop(owner: LifecycleOwner) = lock.backgrounded()
        })
        app.registerActivityLifecycleCallbacks(SecureWindows())
    }

    private inner class SecureWindows : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            protect(activity, device.settings.value.screenProtection)
            (activity as? LifecycleOwner)?.lifecycleScope?.launch {
                device.settings.map { it.screenProtection }.distinctUntilChanged().collect { protect(activity, it) }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun protect(activity: Activity, enabled: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        if (enabled) activity.window.addFlags(flag) else activity.window.clearFlags(flag)
    }
}
```

`main/.../IouApplication.kt`:

```kotlin
package io.github.codex07.iou

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.codex07.iou.security.AppSecurity
import javax.inject.Inject

@HiltAndroidApp
class IouApplication : Application() {

    @Inject lateinit var security: AppSecurity

    override fun onCreate() {
        super.onCreate()
        security.install(this)
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
import io.github.codex07.iou.data.RateRefreshWorker
import io.github.codex07.iou.data.RealBackend
import io.github.codex07.iou.store.DeviceSettingsStore
import io.github.codex07.iou.store.TimedLockGate
import javax.inject.Singleton

/** real flavor: the shared store persisted in Room (ADR-0010), with the daily rate refresh scheduled. */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides
    @Singleton
    fun backend(@ApplicationContext context: Context): RealBackend =
        RealBackend.android(context).also { RateRefreshWorker.schedule(context) }

    @Provides
    @Singleton
    fun apis(backend: RealBackend): Apis = backend.apis

    @Provides
    fun device(backend: RealBackend): DeviceSettingsStore = backend.device

    @Provides
    fun lock(backend: RealBackend): TimedLockGate = backend.lock
}
```

`fake/.../di/BackendModule.kt`:

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
import io.github.codex07.iou.store.TimedLockGate
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/** fake flavor: the in-memory ledger with demo data, and the real app-lock policy. */
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
    fun apis(device: DeviceSettingsStore, lock: TimedLockGate): Apis = FakeApis.seeded(device = device, lock = lock)
}
```

`main/.../MainActivity.kt`:

Replace:

```kotlin
import dagger.hilt.android.AndroidEntryPoint
import io.github.codex07.iou.api.LedgerApi
import javax.inject.Inject

/** Placeholder until the UI contributor's screens land: proves the api is injected and live. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var ledger: LedgerApi

    override fun onCreate(savedInstanceState: Bundle?) {
```

with:

```kotlin
import dagger.hilt.android.AndroidEntryPoint
import io.github.codex07.iou.api.LedgerApi
import io.github.codex07.iou.api.SettingsApi
import javax.inject.Inject

/**
 * Placeholder until the UI contributor's screens land: proves the api is injected and live, and shows the
 * app-lock state for on-device checks (it has no lock screen).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var ledger: LedgerApi
    @Inject lateinit var settings: SettingsApi

    override fun onCreate(savedInstanceState: Bundle?) {
```

Replace:

```kotlin
        setContent {
            val overview by ledger.observeOverview().collectAsState(initial = null)
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding(), contentAlignment = Alignment.Center) {
                        Text("IOU (${BuildConfig.FLAVOR}): ${overview?.rows?.size ?: 0} balances")
                    }
                }
```

with:

```kotlin
        setContent {
            val overview by ledger.observeOverview().collectAsState(initial = null)
            val locked by settings.observeLockRequired().collectAsState(initial = true)
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding(), contentAlignment = Alignment.Center) {
                        Text("IOU (${BuildConfig.FLAVOR}): ${overview?.rows?.size ?: 0} balances" + if (locked) ", locked" else "")
                    }
                }
```

- [ ] **Step 8: Run all checks**

Run: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest --rerun-tasks`
Expected: PASS, 376 tests per flavor.

Run: `.\gradlew.bat lintFakeDebug lintRealDebug assembleFakeDebug assembleRealDebug`
Expected: BUILD SUCCESSFUL. Lint has no errors and 33 warnings per flavor; the new one is the "newer version available" notice for `lifecycle-process`.

- [ ] **Step 9: Check the lock and `FLAG_SECURE` on the emulator**

```bash
.\gradlew.bat installRealDebug installFakeDebug
```

```bash
adb shell am start -n io.github.codex07.iou.fake/io.github.codex07.iou.MainActivity
```

Expected: `IOU (fake): 7 balances`, without ", locked".

Start the real build once, then turn app lock and screen protection on through the settings file. Git Bash needs `MSYS_NO_PATHCONV=1` for these paths:

```bash
printf '{"appLockEnabled":true,"screenProtection":true}' > ds.json
adb push ds.json /data/local/tmp/ds.json
adb shell am force-stop io.github.codex07.iou
adb shell "run-as io.github.codex07.iou cp /data/local/tmp/ds.json no_backup/device-settings.json"
adb shell am start -n io.github.codex07.iou/.MainActivity
```

Expected:
- The placeholder shows `IOU (real): 0 balances, locked`.
- `adb shell dumpsys window windows | grep SECURE` finds the app's window, and `adb shell screencap -p /sdcard/s.png` saves a black image.
- `adb logcat -d -b crash` is empty.

Delete the file and restart (`adb shell "run-as io.github.codex07.iou rm no_backup/device-settings.json"`, `am force-stop`, `am start`). Expected: `IOU (real): 0 balances` and no `SECURE`.

The timeout path (background, wait, return) needs an unlock first, which needs the friend's lock screen. `TimedLockGateTest` covers it, and the checklist repeats it on a real phone later.

- [ ] **Step 10: Commit**

```bash
git add android/gradle/libs.versions.toml android/app/build.gradle.kts android/app/src/main/kotlin/io/github/codex07/iou android/app/src/real android/app/src/fake android/app/src/test/kotlin/io/github/codex07/iou
git commit -m "Lock the app on the process lifecycle and protect the screen"
```

Tell the friend: the lock policy runs in both flavors, so the fake now locks on a cold start when app lock is on; the lock screen calls `markUnlocked()`. The core sets `FLAG_SECURE` itself.

---

### Task 2: Auto Backup rules and the manifest review

**Files:**
- Create: `android/app/src/main/res/xml/backup_rules.xml`, `android/app/src/main/res/xml/data_extraction_rules.xml`
- Modify: `android/app/src/main/AndroidManifest.xml`, `android/app/src/real/AndroidManifest.xml` (whole file below)
- Test: `test/.../data/AutoBackupRulesTest.kt`

**Interfaces:**
- Consumes: Part 3's `DbFiles.POINTER` and `DbFiles.MARKER` (the temp files are `"${DbFiles.POINTER}.tmp"` and `"${DbFiles.MARKER}.tmp"`).
- Produces: the two rule files, and the manifest attributes `allowBackup="true"`, `dataExtractionRules`, `fullBackupContent`.

- [ ] **Step 1: Write the failing test**

`test/.../data/AutoBackupRulesTest.kt`:

```kotlin
package io.github.codex07.iou.data

import io.kotest.matchers.shouldBe
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/** Spec §9 and §14 item 5: both rule files back up exactly the ledger databases and their pointer. */
class AutoBackupRulesTest {

    private data class Rule(val tag: String, val domain: String, val path: String, val flags: String = "")

    private val ledgerOnly = listOf(
        Rule("include", "database", "."),
        Rule("exclude", "database", "${DbFiles.POINTER}.tmp"),
        Rule("exclude", "database", DbFiles.MARKER),
        Rule("exclude", "database", "${DbFiles.MARKER}.tmp"),
    )

    private fun xml(name: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/res/xml/$name.xml")).documentElement

    private fun Element.rules(): List<Rule> = children().filter { it.tagName in setOf("include", "exclude") }
        .map { Rule(it.tagName, it.getAttribute("domain"), it.getAttribute("path"), it.getAttribute("requireFlags")) }

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    @Test
    fun `API 31 and later back up the ledger only, to the cloud only when it is encrypted`() {
        val root = xml("data_extraction_rules")
        val sections = root.children().associateBy { it.tagName }
        sections.keys shouldBe setOf("cloud-backup", "device-transfer")
        sections.getValue("cloud-backup").getAttribute("disableIfNoEncryptionCapabilities") shouldBe "true"
        sections.getValue("cloud-backup").rules() shouldBe ledgerOnly
        sections.getValue("device-transfer").rules() shouldBe ledgerOnly
    }

    @Test
    fun `API 30 backs up the ledger only, into encrypted backups and device transfers`() {
        xml("backup_rules").rules() shouldBe listOf(
            Rule("include", "database", ".", "clientSideEncryption"),
            Rule("include", "database", ".", "deviceToDeviceTransfer"),
        ) + ledgerOnly.drop(1)
    }

    @Test
    fun `the manifest enables Auto Backup with both rule files`() {
        val app = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml")).documentElement.getElementsByTagName("application").item(0) as Element
        fun attr(name: String) = app.getAttributeNS("http://schemas.android.com/apk/res/android", name)
        attr("allowBackup") shouldBe "true"
        attr("dataExtractionRules") shouldBe "@xml/data_extraction_rules"
        attr("fullBackupContent") shouldBe "@xml/backup_rules"
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.data.AutoBackupRulesTest"`
Expected: FAIL, 3 tests. The two rule tests fail with `FileNotFoundException`; the manifest test fails because `allowBackup` is `"false"`.

- [ ] **Step 3: Write the rules and enable them**

`android/app/src/main/res/xml/backup_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Auto Backup on API 30 (spec §9): only the ledger databases and their pointer, and only into an
     end-to-end encrypted cloud backup or a device-to-device transfer. API 31+ reads data_extraction_rules.xml.
     Device-local settings and the device ID live in noBackupFilesDir, which is never backed up. -->
<full-backup-content>
    <include domain="database" path="." requireFlags="clientSideEncryption" />
    <include domain="database" path="." requireFlags="deviceToDeviceTransfer" />
    <exclude domain="database" path="db-state.json.tmp" />
    <exclude domain="database" path="restore-marker.json" />
    <exclude domain="database" path="restore-marker.json.tmp" />
</full-backup-content>
```

`android/app/src/main/res/xml/data_extraction_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Auto Backup and device transfer on API 31+ (spec §9): only the ledger databases and their pointer. Cloud
     backup runs only where it is end-to-end encrypted. The same rules for API 30 are in backup_rules.xml. -->
<data-extraction-rules>
    <cloud-backup disableIfNoEncryptionCapabilities="true">
        <include domain="database" path="." />
        <exclude domain="database" path="db-state.json.tmp" />
        <exclude domain="database" path="restore-marker.json" />
        <exclude domain="database" path="restore-marker.json.tmp" />
    </cloud-backup>
    <device-transfer>
        <include domain="database" path="." />
        <exclude domain="database" path="db-state.json.tmp" />
        <exclude domain="database" path="restore-marker.json" />
        <exclude domain="database" path="restore-marker.json.tmp" />
    </device-transfer>
</data-extraction-rules>
```

`android/app/src/main/AndroidManifest.xml`:

Replace:

```xml
    <application
        android:name=".IouApplication"
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
```

with:

```xml
    <application
        android:name=".IouApplication"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:label="@string/app_name"
        android:supportsRtl="true"
```

`android/app/src/real/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- real flavor only: Frankfurter rates (spec §4.5). The fake flavor never touches the network.
     WorkManager also merges WAKE_LOCK, ACCESS_NETWORK_STATE and RECEIVE_BOOT_COMPLETED (spec §9 allows them) and
     FOREGROUND_SERVICE, which only long-running workers need; the rate refresh never runs in the foreground. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" tools:node="remove" />
</manifest>
```

- [ ] **Step 4: Run all checks**

Run: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest --rerun-tasks`
Expected: PASS, 379 tests per flavor.

Run separately, in order: `.\gradlew.bat assembleFakeDebug assembleRealDebug assembleRealRelease`, then `.\gradlew.bat lintFakeDebug lintRealDebug`. Keeping release assembly separate avoids the observed cross-variant Hilt/lint generated-file race.
Expected: BUILD SUCCESSFUL. Lint has no errors and 32 warnings per flavor. The `DataExtractionRules` warning (a Part 1 carryover) is gone.

- [ ] **Step 5: Review the merged release manifest**

```bash
"$ANDROID_HOME/build-tools/<latest>/aapt2" dump permissions app/build/outputs/apk/real/release/app-real-release-unsigned.apk
```

Expected, exactly:
- `INTERNET`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED` as `uses-permission`;
- `io.github.codex07.iou.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` as a declared `permission` and a `uses-permission`.

`FOREGROUND_SERVICE` is gone.

In `app/build/intermediates/merged_manifest/realRelease/processRealReleaseMainManifest/AndroidManifest.xml`, the exported components are:
- `MainActivity`;
- `androidx.work.impl.background.systemjob.SystemJobService`, with `BIND_JOB_SERVICE`;
- `androidx.work.impl.diagnostics.DiagnosticsReceiver` and `androidx.profileinstaller.ProfileInstallReceiver`, both with `DUMP`.

The application element has `allowBackup="true"`, both rule files and `usesCleartextTraffic="false"`.

- [ ] **Step 6: Check Auto Backup on the emulator**

Install and start the real debug build, then press Home: `bmgr` skips a force-stopped app. Record the initial enabled state with `adb shell bmgr enabled` and note the transport marked `*` in `adb shell bmgr list transports` before changing either. Then:

```bash
adb shell bmgr enable true
adb shell bmgr transport com.android.localtransport/.LocalTransport
adb shell bmgr backupnow io.github.codex07.iou
```

Historical shell output was `Package io.github.codex07.iou with result: Backup is not allowed`. The currently verified emulator instead reports `Transport rejected package` (`-1002`). A generic transport error alone is not proof: verify the installed strict APK contains the exact approved encryption-required XML resources, then perform the single-attribute A/B below on the same disposable image and transport. The strict run must reject and the otherwise identical relaxed run must succeed.

Use a separate fresh disposable test image and task-generated synthetic ledger data for this check. To check the contents, save the exact approved XML bytes and their hash, then use a local build only (never commit it): remove `disableIfNoEncryptionCapabilities="true"` from `data_extraction_rules.xml`, run `installRealDebug`, start the app and press Home. Then, only on that disposable image:

```bash
adb shell "run-as io.github.codex07.iou sh -c 'echo {} > databases/restore-marker.json; echo x > files/other.txt'"
adb shell bmgr backupnow io.github.codex07.iou
adb shell pm clear io.github.codex07.iou
adb shell bmgr restore 1 io.github.codex07.iou
adb shell "run-as io.github.codex07.iou ls -R ."
```

Expected:
- the backup reports `Success`;
- after the restore, `databases/` holds the `iou-<uuid>.db` files and `db-state.json`, but no `restore-marker.json`;
- `files/` and `no_backup/` are empty.

The next start shows `IOU (real): 0 balances` and creates a new `no_backup/device-id`.

Restore the saved exact approved XML bytes and verify their hash, then rebuild and reinstall the strict APK. Do not use `git checkout` on an uncommitted approved rule file: it would restore the pre-task rules. Restore both the noted transport and initial enabled state (`bmgr enabled` records it before the test).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/AndroidManifest.xml android/app/src/main/res android/app/src/real/AndroidManifest.xml android/app/src/test/kotlin/io/github/codex07/iou/data/AutoBackupRulesTest.kt
git commit -m "Back up only the ledger with Auto Backup and drop the foreground-service permission"
```

---

### Task 3: The spec §14 checks on the device and the network

**Files:**
- Create: `android/app/src/realDebug/kotlin/io/github/codex07/iou/debug/KdfTimingReceiver.kt`, `android/app/src/realDebug/AndroidManifest.xml`

**Interfaces:**
- Consumes: Part 5's `BackupCipher.encrypt`/`decrypt` and `KdfParams.DEFAULT`.
- Produces: `debug.KdfTimingReceiver` (realDebug only), which logs one timing line under tag `IouKdf`.

- [ ] **Step 1: Write the timing receiver**

`android/app/src/realDebug/kotlin/io/github/codex07/iou/debug/KdfTimingReceiver.kt`:

```kotlin
package io.github.codex07.iou.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.github.codex07.iou.data.BackupCipher
import io.github.codex07.iou.data.KdfParams
import kotlin.concurrent.thread

/**
 * realDebug only (spec §14 item 3): times one backup encryption and decryption with the spec's Argon2id
 * parameters on the device; nearly all of it is the key derivation. Run
 * `adb shell am broadcast -n io.github.codex07.iou/.debug.KdfTimingReceiver` and read logcat tag `IouKdf`.
 * The shell holds `DUMP`, which the manifest entry requires; apps don't.
 */
class KdfTimingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "kdf-timing") {
            try {
                val params = KdfParams.DEFAULT
                val passphrase = "timing only".toCharArray()
                val started = SystemClock.elapsedRealtime()
                val file = BackupCipher.encrypt(ByteArray(1024), passphrase, params)
                val encrypted = SystemClock.elapsedRealtime()
                BackupCipher.decrypt(file, passphrase, maxPlainBytes = 4096)
                val decrypted = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "Argon2id ${params.memoryKiB} KiB, t=${params.iterations}, p=${params.parallelism}: " +
                        "encrypt ${encrypted - started} ms, decrypt ${decrypted - encrypted} ms",
                )
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "IouKdf"
    }
}
```

`android/app/src/realDebug/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- realDebug only: the on-device Argon2id timing check (spec §14 item 3). Never in a release build. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application>
        <receiver
            android:name=".debug.KdfTimingReceiver"
            android:exported="true"
            android:permission="android.permission.DUMP" />
    </application>
</manifest>
```

- [ ] **Step 2: Run all checks**

Run: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest --rerun-tasks`
Expected: PASS, 379 tests per flavor (no new tests).

Run separately, in order: `.\gradlew.bat assembleFakeDebug assembleRealDebug assembleRealRelease`, then `.\gradlew.bat lintFakeDebug lintRealDebug`. Keeping release assembly separate avoids the observed cross-variant Hilt/lint generated-file race.
Expected: BUILD SUCCESSFUL. Lint has no errors and 32 warnings per flavor. The release merged manifest has no `KdfTimingReceiver`.

- [ ] **Step 3: Time Argon2id on the emulator (§14 item 3)**

```bash
.\gradlew.bat installRealDebug
```

```bash
adb shell am broadcast -n io.github.codex07.iou/.debug.KdfTimingReceiver
```

Run the broadcast three times, about 6 s apart, then read `adb logcat -d -s IouKdf`.

Expected: three lines such as `Argon2id 65536 KiB, t=3, p=1: encrypt 638 ms, decrypt 644 ms`. On 2026-11-05 the first took 2.0 s (warm-up) and the others about 0.65 s. If a warm encryption on the emulator takes more than 2 s, stop and report: a phone would be slower still. A low-end phone is on the manual checklist.

- [ ] **Step 4: Check Frankfurter live (§14 item 2)**

```bash
curl -s -m 40 "https://api.frankfurter.dev/v1/latest?base=EUR&symbols=SEK%2CUSD"
```

```bash
curl -s -m 40 "https://api.frankfurter.dev/v1/2026-11-01?base=EUR&symbols=SEK"
```

```bash
curl -s -m 40 -w " [%{http_code}]" "https://api.frankfurter.dev/v1/2099-01-01?base=EUR&symbols=SEK"
```

Expected:
- the first answers `{"amount":1.0,"base":"EUR","date":…,"rates":{"SEK":…,"USD":…}}` with JSON numbers;
- the second answers with `"date":"2026-10-30"`;
- the third answers `{"message":"not found"} [404]`.

If the API answers differently (other parameter names, rates as strings, a new error shape), stop and report: Part 4's `FrankfurterClient` and `FakeFrankfurter` would need the same change.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/realDebug
git commit -m "Time Argon2id on the device from a debug-only receiver"
```

---

### Task 4: The checklist, the walkthrough and the v0.1.0 release

**Files:**
- Create: `docs/manual-test-checklist.md`, `test/.../data/ExportSamplesTest.kt`
- Modify: `android/app/build.gradle.kts`, `docs/ui-contributor-guide.md`, `README.md`, `docs/superpowers/plans/2026-11-04-m1-00-index.md`

**Interfaces:**
- Consumes: everything in M1; Part 5's `RealHarness`; Part 6's export api; `SeedData`.
- Produces: `ExportSamplesTest`, which writes `app/build/export-samples/{normalized,normalized-excel}.zip` and `{report,report-excel}.csv`. Also the checklist, and the `v0.1.0` GitHub release.

- [ ] **Step 1: Write the export samples test**

`test/.../data/ExportSamplesTest.kt`:

```kotlin
package io.github.codex07.iou.data

import io.github.codex07.iou.api.DocumentRef
import io.github.codex07.iou.api.ExportFormat
import io.github.codex07.iou.api.ExportRequest
import io.github.codex07.iou.api.ExportShape
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.api.PersonDraft
import io.github.codex07.iou.fake.SeedData
import io.github.codex07.iou.testutil.RealHarness
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

/**
 * Writes the seeded ledger's exports to `app/build/export-samples` for the manual checklist (spec §11), which
 * opens them in Excel and LibreOffice. A person named like a formula shows whether text stays text.
 */
class ExportSamplesTest {

    private val harness = RealHarness(Clock.fixed(T0, ZoneOffset.UTC), SequentialIds("samples"))

    private val samples = mapOf(
        "normalized.zip" to ExportRequest(ExportShape.NORMALIZED, ExportFormat.CSV),
        "normalized-excel.zip" to ExportRequest(ExportShape.NORMALIZED, ExportFormat.CSV, excelFriendly = true),
        "report.csv" to ExportRequest(ExportShape.REPORT, ExportFormat.CSV),
        "report-excel.csv" to ExportRequest(ExportShape.REPORT, ExportFormat.CSV, excelFriendly = true),
    )

    @AfterEach
    fun stop() = harness.stop()

    @Test
    fun `sample exports are written for the manual checklist`() = runTest {
        val backend = harness.start()
        SeedData.populate(backend.store)
        val basis = backend.apis.backup.observeLedgerGeneration().first()
        backend.apis.people.createPerson(PersonDraft(FORMULA), basis).shouldBeInstanceOf<LedgerResult.Ok<*>>()

        val out = File("build/export-samples").apply { deleteRecursively(); mkdirs() }
        for ((name, request) in samples) {
            val ref = DocumentRef(name)
            backend.apis.importExport.export(request, ref).shouldBeInstanceOf<LedgerResult.Ok<*>>()
            harness.documents.file(ref).copyTo(File(out, name))
        }
        out.list()!!.sorted() shouldBe samples.keys.sorted()
    }

    private companion object {
        const val FORMULA = """=HYPERLINK("http://evil","x")"""
    }
}
```

Run: `.\gradlew.bat testFakeDebugUnitTest --tests "io.github.codex07.iou.data.ExportSamplesTest"`
Expected: PASS, 1 test, and the four files exist in `app/build/export-samples/`. The test uses only existing code, so it passes at once.

- [ ] **Step 2: Make the version code error clear**

`android/app/build.gradle.kts`:

Replace:

```kotlin
        minSdk = 30
        targetSdk = 36
        versionCode = System.getenv("RELEASE_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("RELEASE_VERSION_NAME") ?: "0.0.1"
    }
```

with:

```kotlin
        minSdk = 30
        targetSdk = 36
        versionCode = System.getenv("RELEASE_VERSION_CODE")?.let { code ->
            code.toIntOrNull()?.takeIf { it > 0 } ?: error("RELEASE_VERSION_CODE must be a positive whole number, was '$code'")
        } ?: 1
        versionName = System.getenv("RELEASE_VERSION_NAME") ?: "0.1.0"
    }
```

- [ ] **Step 3: Write the checklist and update the docs**

`docs/manual-test-checklist.md`:

````markdown
# Manual test checklist

Run these before each release (spec §11). They cover what JVM tests can't: the merged manifest, the device's
backup service, timing on a phone, real spreadsheets and the signed APK. Use the emulator (AVD
`Medium_Phone_API_37.0`) unless a step names a real phone. Gradle commands run from `android/` with
`JAVA_HOME` set to JDK 21; `adb` is in the SDK's `platform-tools`. In Git Bash, set `MSYS_NO_PATHCONV=1`
before `adb shell` commands with paths.

The real debug and release builds share the application ID `io.github.codex07.iou` but use different signing
keys. Use separate fresh, disposable test images for debug checks and signed-release checks. Never uninstall,
clear, restore or change the signing identity of an app profile containing user data. Any destructive backup
or restore check must use task-generated synthetic data on a disposable image. Record the initial backup
service state and transport, and restore both after testing.

## 1. Merged manifest (spec §9, §14 item 4)

- [ ] Build the release APK and list its permissions:

  ```bash
  ./gradlew assembleRealRelease
  "$ANDROID_HOME/build-tools/<latest>/aapt2" dump permissions app/build/outputs/apk/real/release/app-real-release-unsigned.apk
  ```

  Expected, and nothing else:
  - `INTERNET` (declared by the real flavor);
  - `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` (WorkManager);
  - `io.github.codex07.iou.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a signature permission that
    `androidx.core` declares for the app's own receivers;
  - `USE_BIOMETRIC`, once the UI adds `androidx.biometric`.

  `FOREGROUND_SERVICE` must be absent; `src/real/AndroidManifest.xml` removes it. For anything new, find
  its source in `app/build/outputs/logs/manifest-merger-real-release-report.txt`. Remove it with
  `tools:node="remove"` if the app doesn't need it.
- [ ] In `app/build/intermediates/merged_manifest/realRelease/processRealReleaseMainManifest/AndroidManifest.xml`,
  the exported components are `MainActivity`, WorkManager's `SystemJobService` (`BIND_JOB_SERVICE`), and
  `DiagnosticsReceiver` and `ProfileInstallReceiver` (both `DUMP`). There is no `KdfTimingReceiver`; it is
  realDebug only.
- [ ] The application has `allowBackup="true"`, `fullBackupContent="@xml/backup_rules"`,
  `dataExtractionRules="@xml/data_extraction_rules"` and `usesCleartextTraffic="false"`.

## 2. App lock and screen protection (spec §9)

Until the UI has a lock screen, the placeholder shows the lock state (", locked"). Set the device settings
through the file on a fresh disposable image containing only test data:

- [ ] Install realDebug (`./gradlew installRealDebug`) and start it once. Then write the settings and restart:

  ```bash
  printf '{"appLockEnabled":true,"screenProtection":true}' > ds.json
  adb push ds.json /data/local/tmp/ds.json
  adb shell am force-stop io.github.codex07.iou
  adb shell "run-as io.github.codex07.iou cp /data/local/tmp/ds.json no_backup/device-settings.json"
  adb shell am start -n io.github.codex07.iou/.MainActivity
  ```

  The screen shows `IOU (real): 0 balances, locked`. `adb shell dumpsys window windows | grep SECURE` finds
  the window, and `adb shell screencap` gives a black app surface.
- [ ] Delete `no_backup/device-settings.json` with `run-as` and restart: no lock, no `FLAG_SECURE`.
- [ ] **On a real phone, once the UI's lock screen exists:** turn app lock on, and on return after the timeout
  `BiometricPrompt` (fingerprint or device credential) covers everything. Under the timeout there is no
  prompt; with "immediately" every return prompts. With screen protection on, the Recents thumbnail is
  blank and screenshots are refused.

## 3. Auto Backup (spec §9, §14 item 5)

The strict rules allow cloud backup only when the transport provides the required client-side encryption.
An unsuccessful `bmgr` result alone does not prove that policy worked: confirm the APK's rule resource and use
the local-only A/B check below. On the Android 37 image checked on 2026-11-05, the strict attempt returned
`Transport rejected package` (`-1002`), rather than the older plan's literal `Backup is not allowed`.

- [ ] Use a new disposable test image with task-generated synthetic ledger data. Before changing backup
  settings, record `adb shell bmgr enabled` and the current transport from `adb shell bmgr list transports`
  (marked `*`). Start realDebug, then press Home and run:

  ```bash
  adb shell bmgr enable true
  adb shell bmgr transport com.android.localtransport/.LocalTransport
  adb shell bmgr backupnow io.github.codex07.iou
  ```

  Confirm the strict APK's `data_extraction_rules` resource has
  `disableIfNoEncryptionCapabilities="true"` with
  `"$ANDROID_HOME/build-tools/<latest>/aapt2" dump xmltree app/build/outputs/apk/real/debug/app-real-debug.apk --file res/xml/data_extraction_rules.xml`.
  Record the actual result and check
  the backup log. The Android 37 result above was corroborated by a local-only A/B build: with only that
  attribute removed, the same package and transport backed up successfully (185,344 bytes); `aapt2` dumps
  confirmed the attribute was the only policy difference. A transport error by itself is not a pass.
- [ ] Check backup contents with a local build only, never committed, on the disposable image and synthetic
  data: save the approved XML and its hash,
  remove `disableIfNoEncryptionCapabilities="true"` from `data_extraction_rules.xml`, build and install,
  start the app, then press Home. Confirm with `aapt2` that only that attribute differs from the strict APK.
  Put a stray `restore-marker.json` and `files/other.txt` next to the ledger:

  ```bash
  adb shell "run-as io.github.codex07.iou sh -c 'echo {} > databases/restore-marker.json; echo x > files/other.txt'"
  adb shell bmgr backupnow io.github.codex07.iou      # expected: Success
  adb shell pm clear io.github.codex07.iou
  adb shell bmgr restore 1 io.github.codex07.iou
  adb shell "run-as io.github.codex07.iou ls -R ."
  ```

  Only `databases/` comes back: the `iou-*.db` files and `db-state.json`, without `restore-marker.json`.
  `files/` and `no_backup/` are empty. The next start shows the ledger and creates a new `device-id`; device
  settings start at their defaults. `pm clear` in this step is restricted to this task-generated synthetic
  app data; never run it against an existing user profile.
- [ ] Restore the exact approved XML bytes and verify their saved hash. Rebuild and reinstall the strict APK,
  then restore the recorded state: `bmgr transport <the noted original transport>` and
  `bmgr enable <the recorded enabled state>`.

## 4. Argon2id on the device (spec §14 item 3)

- [ ] With realDebug installed:

  ```bash
  adb shell am broadcast -n io.github.codex07.iou/.debug.KdfTimingReceiver
  adb logcat -d -s IouKdf
  ```

  Run it three times; the first run includes warm-up. On the isolated Android 37 emulator checked on
  2026-11-05, encryption/decryption times were 784/816 ms, 658/636 ms and 641/623 ms. These are emulator
  measurements; record the current device's actual timings rather than reusing them.
- [ ] **On the slowest real phone available:** run the same broadcast. If an encryption takes more than
  about 2 s after warm-up, lower `KdfParams.DEFAULT.memoryKiB`. Backups record their parameters in the
  header, so older backups still open.

## 5. Frankfurter (spec §14 item 2)

- [ ] `curl "https://api.frankfurter.dev/v1/latest?base=EUR&symbols=SEK%2CUSD"` answers 200 with JSON-number
  rates and the latest working day as `date`.
- [ ] `curl "https://api.frankfurter.dev/v1/2026-11-01?base=EUR&symbols=SEK"` (a Sunday) answers with
  `"date":"2026-10-30"`.
- [ ] A future date (`/v1/2099-01-01?base=EUR&symbols=SEK`) answers 404 `{"message":"not found"}`. The app
  treats any answer except 200, and any timeout, as unavailable (spec §4.5).

## 6. Exports in spreadsheets (spec §7.2)

- [ ] Run `./gradlew testFakeDebugUnitTest --tests "*ExportSamplesTest"`. It writes `normalized.zip`,
  `normalized-excel.zip`, `report.csv` and `report-excel.csv` to `app/build/export-samples/`.
- [ ] Open each CSV, and `people.csv` from each zip, in Excel and in LibreOffice Calc:
  - import plain files as comma-separated UTF-8 and Excel-friendly files as UTF-8 with BOM and `;` delimiters;
    confirm columns split correctly in both apps. Excel installations whose default separator is comma may
    mis-split a semicolon CSV when opened directly; use **Data > From Text/CSV** and select semicolon;
  - the person `=HYPERLINK("http://evil","x")` shows as text, not as a link or a formula. The leading `'` is
    the reversible escape; Excel may hide it;
  - amounts read as plain decimals.

  In the 2026-11-05 native checks, explicit imports in Excel 16 and LibreOffice Calc 26.8 split the columns
  correctly, kept amounts numeric, and showed no formulas or hyperlinks. Excel 16's default open path did
  not split the semicolon files correctly; use the explicit import procedure above. These results cover
  those versions and the tested files.
- [ ] XLSX export arrives in M2 (spec §7.3); check it then.

## 7. Signed release APK (spec §2.4, §10)

- [ ] After the release workflow, download the APK from the GitHub release and check its certificate:
  `apksigner verify --print-certs iou-vX.Y.Z.apk` shows `CN=IOU` and SHA-256
  `328199658371ad458470be6ec4970597e1763ac4b4921277161dbd79bdf7b1c5`.
- [ ] Use a separate fresh, empty disposable upgrade image. Install the previous release's APK and start it;
  if checking persisted ledger data, seed only synthetic test data. Then run
  `adb install -r iou-vX.Y.Z.apk`. The upgrade succeeds without uninstalling, and the app starts and shows
  the same test ledger. Keep debug and signed-release builds on separate images because they use different
  signing keys.
````

`docs/ui-contributor-guide.md`:

Replace:

```markdown
## Reads

Every screen reads a `Flow` (`observeOverview()`, `observePerson(id)`, `observeGroup(id)`, `observeEntry(id)`, …). Flows re-emit after any write, so screens never refresh by hand. Read models are ready to render: balances are computed, people are canonical (merged duplicates appear once), amounts are `Money`.

Signs: a person balance is `net(me, X)`. **Positive means they owe me.** A group net is `groupNet(member)`. **Positive means the group owes them.** Overview totals are positive magnitudes.
```

with:

```markdown
## Reads

Every screen reads a `Flow` (`observeOverview()`, `observePerson(id)`, `observeGroup(id)`, `observeEntry(id)`, …). Read-model flows update automatically after writes that change what they observe, so screens never refresh by hand. Equal setup or settings values may be suppressed. Observe `BackupApi.observeLedgerGeneration()` separately to detect ledger replacement and invalidate drafts. Read models are ready to render: balances are computed, people are canonical (merged duplicates appear once), amounts are `Money`.

Signs: a person balance is `net(me, X)`. **Positive means they owe me.** A group net is `groupNet(member)`. **Positive means the group owes them.** Overview totals are positive magnitudes.
```

Replace:

```markdown
- `settleUpWithPerson(id, basis)` gives one draft per non-zero currency outside groups.

## Errors
```

with:

```markdown
- `settleUpWithPerson(id, basis)` gives one draft per non-zero currency outside groups.

## App lock and screen protection

The core runs the policy and the window flag; you build the lock screen (spec §9).

- While `SettingsApi.observeLockRequired()` is `true`, cover all content with a lock screen and show `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`. On success call `markUnlocked()`. Add `androidx.biometric` for this; it brings the `USE_BIOMETRIC` permission.
- Only offer the app-lock switch when the device has a secure lock screen (`KeyguardManager.isDeviceSecure`).
- The core locks on a cold start and after the chosen timeout in the background, following the process lifecycle. Don't track that yourself.
- Screen protection: `setScreenProtection(true)` is all you call. The core sets `FLAG_SECURE` on every activity window and follows later changes.

## Errors
```

`README.md`:

Replace:

```markdown
An Android app for tracking shared expenses and IOUs in a friend group. It replaces "who owes whom" threads in WhatsApp with exact, auditable balances.

> **Status:** Milestone 0 done: the core ↔ UI contract and an in-memory fake with demo data. The real (Room) implementation comes in Milestone 1.

## What it does (MVP)
```

with:

```markdown
An Android app for tracking shared expenses and IOUs in a friend group. It replaces "who owes whom" threads in WhatsApp with exact, auditable balances.

> **Status:** Milestone 1 implementation complete (v0.1.0). The real flavor is a working ledger on Room, with groups, history and revert, merges, FX, encrypted backups, CSV export and import, app lock and screen protection. The screens come from the UI contributor. Importing group rows and XLSX export come in Milestone 2.

## What it does (MVP)
```

Replace:

```markdown
- [MVP design spec (milestones 0–2)](docs/superpowers/specs/2026-11-02-iou-mvp-design.md)
- [UI contributor guide](docs/ui-contributor-guide.md)

## Tech
```

with:

```markdown
- [MVP design spec (milestones 0–2)](docs/superpowers/specs/2026-11-02-iou-mvp-design.md)
- [UI contributor guide](docs/ui-contributor-guide.md)
- [Manual test checklist](docs/manual-test-checklist.md), run before each release

## Tech
```

- [ ] **Step 4: Run all checks**

Run: `.\gradlew.bat testFakeDebugUnitTest testRealDebugUnitTest --rerun-tasks`
Expected: PASS, 380 tests per flavor, 0 failures and 0 skipped.

Run separately, in order: `.\gradlew.bat assembleFakeDebug assembleRealDebug assembleRealRelease`, then `.\gradlew.bat lintFakeDebug lintRealDebug`. Keeping release assembly separate avoids the observed cross-variant Hilt/lint generated-file race.
Expected: BUILD SUCCESSFUL. Lint has no errors and 32 warnings per flavor; describe it that way, not as warning-free.

- [ ] **Step 5: Walk through the acceptance criteria**

Every M1 criterion (index, ACs 1–21) has tests that prove it. Each criterion's tests must be in the Step 4 run and pass. In each flavor's `app/build/test-results/test<Flavor>DebugUnitTest/`, check that every class named below has its XML file with `failures="0"` and `errors="0"`. Contract suites run as `Fake…ContractTest` and `Real…ContractTest`.

| AC | Proved by |
|---|---|
| 1 | `LedgerApiContract` "a loan and a repayment leave the rest owed, regardless of account"; `LedgerEngineTest` |
| 2 | `LedgerViewsTest` "overview … totals per currency"; `RealBackendTest` "the rate refresh … fills the approximate totals"; `Overview.approxOwedToMe` and `approxIOwe` are documented as approximate |
| 3 | `LedgerApiContract` "entries outside groups involve me and exactly one other person"; `EntryOpsTest` "outside groups an entry needs me and exactly one other person" |
| 4 | `LedgerApiContract` "an edit is diffed in history and can be reverted" (`40.00 EUR` → `45.00 EUR`); `RevertOpsTest` |
| 5 | `LedgerApiContract` "delete removes the effect at once and restore brings it back" |
| 6 | `PeopleApiContract` "merge combines balances and unmerge splits them again"; `MergeOpsTest` "unmerging a middle record takes its own aliases along" |
| 7 | `RealBackupTest` "backup, wipe and restore give back the same ledger, and the device ID stays the installation's own"; `BackupFormatTest` |
| 8 | `RealBackupTest` "a wrong passphrase or an invalid ledger writes nothing", "a failure before the commit point keeps the database and the ledger settings", "a pointer that can't be written abandons the restore", "undo brings back the state before the restore …", "an undo that fails before the commit point changes nothing"; `BackupFormatTest` "cached rates that don't map or aren't valid are rejected"; `BackupApiContract` "a wrong passphrase changes nothing" |
| 9 | `ImportReaderTest` "text a spreadsheet would run comes back exactly"; `TextEscapingTest` |
| 10 | `RealImportTest` "a conflict shows its diff …" and "a transfer deleted in the export shows active to deleted …" |
| 11 | `RealImportTest` "an export from another install is mapped to the local me after confirmation"; `ImportExportApiContract` "a foreign export needs confirmation and declining rejects it" |
| 12 | `RestoreCrashTest` (both tests); `RealBackupTest` "a restore that fails after the commit point is complete and is not rolled back" and "an undo that fails after the commit point is complete and is not rolled back"; `RealBackendTest` "a restore cut off before its commit point is reported once …"; `RestoreRecoveryTest` |
| 13 | `RealBackupTest` "the reminder is due after 30 days without a manual backup"; `BackupApiContract` "the reminder is due until a backup is made" |
| 14 | `RealContractTests.kt`: all eight suites against the real backend, `groups` tests included (index, locked decision 6) |
| 15 | `BackupApiContract` "a restore and its undo each change the generation, and older tokens and bases stay invalid"; `ImportExportApiContract` "confirming a foreign preview parsed before a restore is refused" |
| 16 | `RealImportTest` "deleted people merged into each other are invalid rows", "a person deleted together with the transfers involving them is one valid change", "a new person is not blamed for an invalid deletion that leaving them out would hide" and "a deleted transfer naming an unknown person does not hide an invalid deletion" |
| 17 | `EntryOpsTest` "changing the paid currency while keeping the old snapshot is refused" |
| 18 | `EntryOpsTest` "a cross-currency repayment settles SEK with EUR and keeps or drops its snapshot on edit"; `LedgerApiContract` "a cross-currency settlement asks for a rate, then settles the other currency" |
| 19 | `EntryOpsTest` "a bank-actual charge is kept on note and date edits and refused when the paid amount changes" |
| 20 | `RealImportTest` "effects that their FX snapshot doesn't explain are invalid rows" |
| 21 | `RealImportTest` "a handle added after the preview makes the commit stale, and the refresh shows it in the cascade", "a handle the export shows deleted before its person stays deleted when the person is restored" and "a new handle the export shows deleted apart from its locally deleted person stays deleted when the person is restored" |

On the device, Tasks 1–3 checked what the placeholder can show. Backup, export and import have no screens yet, so on-device runs of ACs 7–13 and 15–21 wait for the friend's screens (owner's decision, 2026-11-05).

- [ ] **Step 6: Run the manual checklist**

Go through `docs/manual-test-checklist.md` sections 1–6 on the emulator. Sections 1–5 repeat Tasks 2 and 3. For section 6, the owner opens the samples in Excel and LibreOffice. Leave the steps marked "real phone" or "once the UI's lock screen exists" for later, and list them in the report.

- [ ] **Step 7: Mark Milestone 1 done in the index**

In `docs/superpowers/plans/2026-11-04-m1-00-index.md`:
- set all eight implementation rows' status to "done";
- add `> **Status:** Milestone 1 implementation complete (v0.1.0).` under the title;
- under **Carried open items**, add: "On-device runs of backup, export and import (ACs 7–13 and 15–21) and `ContentDocuments` wait for the friend's screens. Argon2id timing on a low-end phone and app lock on a real phone stay on the manual checklist."

- [ ] **Step 8: Commit and push**

```bash
git add README.md docs android/app/build.gradle.kts android/app/src/test/kotlin/io/github/codex07/iou/data/ExportSamplesTest.kt
git commit -m "Add the manual test checklist and export samples, and prepare v0.1.0"
```

Push `main` immediately after every commit, as the owner explicitly requires for this repository. Check `gh run list` and `git fetch` for remote changes. The owner already approved the M1 pushes and v0.1.0 tag; the controller closes the whole-Part8 review and waits for green CI on the latest pushed commit before tagging.

- [ ] **Step 9: Release v0.1.0**

With the owner's approval, tag the pushed commit and push the tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

Expected: the Release workflow passes and the GitHub release `IOU v0.1.0` has `iou-v0.1.0.apk`. Then follow checklist section 7: check the certificate, install `v0.0.1`, upgrade to `v0.1.0` over it and start the app.

Tell the friend (the owner relays it). M1 is released, with:
- the lock policy in both flavors (the fake locks on a cold start when app lock is on);
- `FLAG_SECURE` handled by the core;
- the `ui-contributor-guide` section "App lock and screen protection";
- the API changes listed in the index's locked decision 13.
