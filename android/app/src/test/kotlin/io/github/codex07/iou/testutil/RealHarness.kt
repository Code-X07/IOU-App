package io.github.codex07.iou.testutil

import io.github.codex07.iou.data.BackupFormat
import io.github.codex07.iou.data.DatabaseProvider
import io.github.codex07.iou.data.KdfParams
import io.github.codex07.iou.data.RealBackend
import io.github.codex07.iou.data.RestoreStep
import io.github.codex07.iou.data.RoomRateCache
import io.github.codex07.iou.fake.FakeRates
import io.github.codex07.iou.fx.RateCache
import io.github.codex07.iou.ledger.model.IdGenerator
import java.io.File
import java.time.Clock
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * Real backends on the JVM, laid out like the app's private storage in one temp directory. [start] can run
 * again on the same directory after [stop], which is how tests simulate an app restart.
 * - [frankfurter] has published the demo rates ([FakeRates]) on [RATES_DATE], so every later date gets them too.
 * - [documents] holds picked documents as files.
 * - [onStep] runs at each restore step: throw an `Exception` to inject a failure, or an `Error` to stand in for
 *   process death.
 * - Backups use [FAST_KDF], so tests don't spend 64 MiB and seconds per key.
 */
class RealHarness(private val clock: Clock, private val ids: IdGenerator) {
    val root: File = createTempDirectory("iou-real").toFile()
    val databaseDir = File(root, "databases")
    val frankfurter = FakeFrankfurter().apply { publish(RATES_DATE, *FakeRates.perEur.toList().toTypedArray()) }
    val documents = FileDocuments(File(root, "documents"))
    @Volatile var onStep: (RestoreStep) -> Unit = {}
    private var running: RealBackend? = null

    fun start(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        rateCache: (DatabaseProvider) -> RateCache = ::RoomRateCache,
        backupFormat: BackupFormat = BackupFormat(FAST_KDF),
    ): RealBackend {
        check(running == null) { "Stop the running backend first" }
        return RealBackend(
            databaseDir = databaseDir,
            deviceIdFile = File(root, "no_backup/device-id"),
            settingsFile = File(root, "no_backup/device-settings.json"),
            openDatabase = TestDatabases::file,
            frankfurter = frankfurter.client(),
            documents = documents,
            clock = clock,
            ids = ids,
            scope = scope,
            backupFormat = backupFormat,
            appVersion = "test",
            restoreHook = { onStep(it) },
            rateCache = rateCache,
        ).also { running = it }
    }

    fun stop() = runBlocking {
        running?.close()
        running = null
    }

    /** The ledger files on disk by name, without SQLite's side files (`-wal`, `-shm`, `.lck`, `-journal`). */
    fun ledgerFileNames(): Set<String> = databaseDir.listFiles().orEmpty().map { it.name }
        .filterNot { name -> listOf("-wal", "-shm", ".lck", "-journal").any(name::endsWith) }.toSet()

    companion object {
        val RATES_DATE: LocalDate = LocalDate.parse("2025-12-31")
        val FAST_KDF = KdfParams(memoryKiB = 1_024, iterations = 1, parallelism = 1)
    }
}
