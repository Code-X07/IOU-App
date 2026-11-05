package io.github.codex07.iou.testutil

import android.content.ContextWrapper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.codex07.iou.data.IouDatabase
import java.io.File

/**
 * Room on the plain JVM (index, locked decision 4): the bundled SQLite driver, and a context that only
 * resolves database paths. Every other Android call returns a default (`isReturnDefaultValues`).
 */
object TestDatabases {
    val context = object : ContextWrapper(null) {
        override fun getDatabasePath(name: String): File = File(name)
    }

    fun inMemory(): IouDatabase = IouDatabase.open(context, BundledSQLiteDriver(), file = null)

    fun file(file: File): IouDatabase = IouDatabase.open(context, BundledSQLiteDriver(), file.absolutePath)
}
