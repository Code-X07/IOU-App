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
