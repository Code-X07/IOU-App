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
