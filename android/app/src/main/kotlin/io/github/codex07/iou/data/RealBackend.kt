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
