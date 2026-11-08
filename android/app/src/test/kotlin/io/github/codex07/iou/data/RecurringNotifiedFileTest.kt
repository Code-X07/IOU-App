package io.github.codex07.iou.data

import io.github.codex07.iou.store.NotifiedRecord
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The device-local record of what was last notified (spec §5.7): replaced atomically, unreadable = empty. */
class RecurringNotifiedFileTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `it round-trips, and a missing or unreadable file reads as empty`() {
        val file = RecurringNotifiedFile(File(dir, "recurring-notified.json"))
        file.read() shouldBe NotifiedRecord()

        val record = NotifiedRecord(mapOf("rent" to "2026-03-01|null|false|open"), detailedPosted = true)
        file.write(record)
        file.read() shouldBe record

        File(dir, "recurring-notified.json").writeText("not json")
        file.read() shouldBe NotifiedRecord()
    }
}
