package io.github.codex07.iou.data

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/** Spec §2.2: only the `data` package touches the DAO, so every ledger write goes through the journal. */
class DataAccessTest {

    private val sourceRoot = File("src")

    @Test
    fun `nothing outside data uses LedgerDao`() {
        sourceRoot.isDirectory shouldBe true
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.contains("/main/kotlin/io/github/codex07/iou/data/") }
            .filterNot { it.invariantSeparatorsPath.contains("/test/kotlin/io/github/codex07/iou/data/") }
            .filter { file -> file.readLines().any { "LedgerDao" in it || "ledgerDao()" in it } }
            .map { it.invariantSeparatorsPath }
            .toList()
        offenders.shouldBeEmpty()
    }
}
