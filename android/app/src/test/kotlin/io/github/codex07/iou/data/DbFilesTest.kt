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
