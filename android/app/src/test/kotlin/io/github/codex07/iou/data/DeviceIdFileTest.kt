package io.github.codex07.iou.data

import io.github.codex07.iou.ledger.model.DeviceId
import io.github.codex07.iou.ledger.model.IdGenerator
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeviceIdFileTest {
    @Test
    fun `failed initial write does not publish an ID and retry persists it`() {
        val dir = createTempDirectory("iou-device-id").toFile()
        val file = File(dir, "device-id")
        val obstruction = File(dir, "device-id.tmp")
        obstruction.mkdir() shouldBe true
        var generated = 0
        val ids = IdGenerator { "id-${++generated}" }

        assertThrows<IOException> { DeviceIdFile.read(file, ids) }
        file.exists() shouldBe false
        obstruction.delete() shouldBe true

        DeviceIdFile.read(file, ids) shouldBe DeviceId("id-2")
        DeviceIdFile.read(file, ids) shouldBe DeviceId("id-2")
        generated shouldBe 2
    }
}
