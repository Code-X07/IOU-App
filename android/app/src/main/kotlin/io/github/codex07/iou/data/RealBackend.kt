package io.github.codex07.iou.data

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import io.github.codex07.iou.BuildConfig
import io.github.codex07.iou.api.Apis
import io.github.codex07.iou.fake.FakeImportExportApi
import io.github.codex07.iou.fx.FrankfurterClient
import io.github.codex07.iou.fx.FxService
import io.github.codex07.iou.ledger.model.IdGenerator
import io.github.codex07.iou.ledger.model.LedgerState
import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.store.DocumentStore
import io.github.codex07.iou.store.LedgerStore
import io.github.codex07.iou.store.SimpleLockGate
import io.github.codex07.iou.store.StoreFxApi
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
 * The real flavor's backend (ADR-0010): the shared store with a Room journal, the rate cache and Frankfurter,
 * backups through the database pointer, the device settings file and the device ID file. Construction
 * returns at once; [start] runs recovery, opens the database the pointer names, loads the rate cache and
 * publishes the ledger, and reads and writes wait for that.
 *
 * Import/export is still the simulation from `fake`; Parts 6 and 7 replace it.
 */
class RealBackend(
    databaseDir: File,
    deviceIdFile: File,
    settingsFile: File,
    openDatabase: (File) -> IouDatabase,
    private val frankfurter: FrankfurterClient,
    documents: DocumentStore,
    clock: Clock,
    ids: IdGenerator,
    private val scope: CoroutineScope,
    backupFormat: BackupFormat = BackupFormat(),
    appVersion: String = "",
    restoreHook: RestoreHook = RestoreHook.None,
) {
    val files = DbFiles(databaseDir)
    val databases = LedgerDatabases(files, openDatabase, ids)
    val device = FileDeviceSettings(settingsFile)
    val fx = FxService(RoomRateCache(databases), frankfurter, clock)
    val store = LedgerStore(clock, ids, DeviceIdFile.read(deviceIdFile, ids), RoomJournal(databases), fx.lookup)
    val restores = RestoreProcedure(store, files, databases, fx, ids, restoreHook)

    /** Set once [start] has run recovery. */
    @Volatile var recovery: RestoreRecovery.Result? = null
        private set

    private val interrupted = MutableStateFlow(false)

    val apis = Apis(
        setup = StoreSetupApi(store),
        people = StorePeopleApi(store),
        groups = StoreGroupsApi(store),
        ledger = StoreLedgerApi(store),
        fx = StoreFxApi(fx),
        backup = RealBackupApi(store, device, documents, backupFormat, fx, restores, interrupted, appVersion),
        importExport = FakeImportExportApi(store),
        settings = StoreSettingsApi(store, device, SimpleLockGate()),
    )

    private val started: Job = scope.launch { start() }

    private suspend fun start() = store.exclusive { _, publish ->
        val result = RestoreRecovery.run(files)
        recovery = result
        interrupted.value = result.interrupted
        val generation = databases.openCurrent()
        restores.refresh()
        fx.load()
        publish(LedgerLoader.load(databases.database()), generation)
    }

    /**
     * The daily refresh (spec §4.5): caches the latest rates for the home currency and the currencies in use.
     * False when Frankfurter was unavailable.
     */
    suspend fun refreshRates(): Boolean {
        val snapshot = store.loaded()
        return fx.refresh(currenciesInUse(snapshot.state) + snapshot.settings.homeCurrency)
    }

    /** Stops background work and closes the database; the backend is unusable afterwards. */
    suspend fun close() {
        started.join()
        scope.coroutineContext.job.cancelAndJoin()
        databases.close()
        frankfurter.close()
    }

    companion object {
        /** Paid and effect currencies of all entries, and the groups' default currencies. */
        fun currenciesInUse(state: LedgerState): Set<CurrencyCode> =
            state.transfers.values.flatMap { listOf(it.amount.currency, it.effectAmount.currency) }.toSet() +
                state.expenses.values.flatMap { listOf(it.total.currency, it.effectTotal.currency) } +
                state.groups.values.map { it.defaultCurrency }

        /** The app's backend: files in the app's private storage, the framework SQLite driver, SAF documents. */
        fun android(context: Context): RealBackend {
            val app = context.applicationContext
            return RealBackend(
                databaseDir = checkNotNull(app.getDatabasePath(DbFiles.POINTER).parentFile),
                deviceIdFile = File(app.noBackupFilesDir, "device-id"),
                settingsFile = File(app.noBackupFilesDir, "device-settings.json"),
                openDatabase = { IouDatabase.open(app, AndroidSQLiteDriver(), it.absolutePath) },
                frankfurter = FrankfurterClient.create(),
                documents = ContentDocuments(app.contentResolver),
                clock = Clock.systemUTC(),
                ids = IdGenerator.Random,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                appVersion = BuildConfig.VERSION_NAME,
            )
        }
    }
}
