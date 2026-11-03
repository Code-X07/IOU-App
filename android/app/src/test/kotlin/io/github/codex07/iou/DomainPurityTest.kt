package io.github.codex07.iou

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/** Spec §2.1 and §11: Android-free domain packages, and no floating point in money paths. */
class DomainPurityTest {

    // Gradle runs Android unit tests with the module directory (android/app) as working directory.
    private val sourceRoot = File("src/main/kotlin/io/github/codex07/iou")

    private val androidFreePackages = listOf("money", "ledger", "api", "fake")
    private val noFloatingPointPackages = listOf("money", "ledger", "api", "data", "io")

    @Test
    fun `the scan runs against the real source tree`() {
        sourceRoot.resolve("money").isDirectory shouldBe true
    }

    @Test
    fun `android-free packages import nothing from Android`() {
        offenders(androidFreePackages, SourceScan::importsAndroid).shouldBeEmpty()
    }

    @Test
    fun `money paths never use Double or Float`() {
        offenders(noFloatingPointPackages, SourceScan::usesFloatingPoint).shouldBeEmpty()
    }

    @Test
    fun `the scanner flags floating point and ignores strings and comments`() {
        listOf(
            "val x: Double = 0",
            "fun f(): Float",
            "val y = 1.5",
            "val z = 2f",
            "val w = amount.toDouble()",
            "val v = 1.0e3",
        ).forEach { SourceScan.usesFloatingPoint(it) shouldBe true }

        listOf(
            "val s = \"12.34 Double\"",
            "// 1.5 Double",
            " * Float in KDoc",
            "/* 2f */",
            "Money(1234, eur)",
            "val max = 10_000_000_000_000L",
            "x.version",
        ).forEach { SourceScan.usesFloatingPoint(it) shouldBe false }
    }

    @Test
    fun `the scanner flags android imports only`() {
        SourceScan.importsAndroid("import android.os.Bundle") shouldBe true
        SourceScan.importsAndroid("import androidx.room.Dao") shouldBe true
        SourceScan.importsAndroid("import java.time.Instant") shouldBe false
        SourceScan.importsAndroid("import io.github.codex07.iou.money.Money") shouldBe false
    }

    private fun offenders(packages: List<String>, isOffending: (String) -> Boolean): List<String> =
        packages
            .map(sourceRoot::resolve)
            .filter(File::isDirectory)
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (isOffending(line)) "${file.path}:${index + 1}: ${line.trim()}" else null
                }
            }
}

internal object SourceScan {

    private val androidImport = Regex("""^\s*import\s+(android|androidx)\.""")
    private val stringLiteral = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
    private val floatingPoint = Regex(
        """\b(Double|Float)\b""" +
            """|\.to(Double|Float)\(""" +
            """|(?<![\w.])\d+\.\d+([eE][+-]?\d+)?[fF]?\b""" +
            """|(?<![\w.])\d+[fF]\b""",
    )

    fun importsAndroid(line: String): Boolean = androidImport.containsMatchIn(line)

    fun usesFloatingPoint(line: String): Boolean {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return false
        val code = line.replace(stringLiteral, "\"\"").substringBefore("//")
        return floatingPoint.containsMatchIn(code)
    }
}
