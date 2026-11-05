package io.github.codex07.iou

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/** Spec §2.1 and §11: Android-free domain packages, and no floating point in money paths. */
class DomainPurityTest {

    // Gradle runs Android unit tests with the module directory (android/app) as working directory.
    private val sourceRoot = File("src/main/kotlin/io/github/codex07/iou")

    private val androidFreePackages = listOf("money", "ledger", "api", "store", "fake")
    private val noFloatingPointPackages = listOf("money", "ledger", "api", "store", "data", "io")

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
            "val a = text.toDoubleOrNull()",
            "val b = text.toFloatOrNull()",
            "val c = DoubleArray(3)",
            "val d: FloatArray = arr",
            "texts.map(String::toDouble)",
            "val e = 1_000.5",
            "val g = 1e3",
            "val h = 1E-3",
            "val i = \"\${amount.toDouble()}\"",
            "val j = \"total: \${total * 1.5} EUR\"",
            "val k = \"\${amounts.sumOf { it.toDouble() }}\"",
            "val l = \"\${xs.map { \"\${it.toFloat()}\" }}\"",
            "val m = \"\${f(\"}\")} \${2.5}\"",
        ).forEach { SourceScan.usesFloatingPoint(it) shouldBe true }

        listOf(
            "val s = \"12.34 Double\"",
            "val t = \"\${amount} is 1.5 Double\"",
            "val u = \"\${xs.joinToString { \"1.5 Double\" }}\"",
            "val v = \"a \\\" 1.5 Double\" // 2.5",
            "// 1.5 Double",
            " * Float in KDoc",
            "/* 2f */",
            "Money(1234, eur)",
            "val max = 10_000_000_000_000L",
            "x.version",
            "val mask = 0xFF",
            "for (i in 1..2) {}",
            "val doubled = total.times(2)",
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
    private val floatingPoint = Regex(
        """\b(Double|Float)(Array)?\b""" +
            """|\bto(Double|Float)(OrNull)?\b""" +
            """|(?<![\w.])\d[\d_]*\.\d[\d_]*([eE][+-]?\d+)?[fF]?\b""" +
            """|(?<![\w.])\d[\d_]*[eE][+-]?\d+[fF]?\b""" +
            """|(?<![\w.])\d[\d_]*[fF]\b""",
    )

    fun importsAndroid(line: String): Boolean = androidImport.containsMatchIn(line)

    fun usesFloatingPoint(line: String): Boolean {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return false
        return floatingPoint.containsMatchIn(code(line))
    }

    /**
     * The line without string text and trailing comments. Expressions interpolated into strings run, so they
     * stay, braces and nested strings included (a regex cannot balance those).
     */
    private fun code(line: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < line.length && !line.startsWith("//", i)) {
            if (line[i] == '"') {
                i = afterString(line, i + 1, out)
            } else {
                out.append(line[i++])
            }
        }
        return out.toString()
    }

    /** Skips the string whose text starts at [start], appending its interpolated code; returns the index after it. */
    private fun afterString(line: String, start: Int, out: StringBuilder): Int {
        var i = start
        while (i < line.length) {
            when {
                line[i] == '\\' -> i += 2
                line[i] == '"' -> return i + 1
                line.startsWith("\${", i) -> i = afterInterpolation(line, i + 2, out)
                else -> i++
            }
        }
        return i
    }

    /** Appends the expression starting at [start] up to its closing brace; returns the index after that brace. */
    private fun afterInterpolation(line: String, start: Int, out: StringBuilder): Int {
        out.append(' ')
        var depth = 1
        var i = start
        while (i < line.length) {
            when (line[i]) {
                '"' -> i = afterString(line, i + 1, out)
                '{' -> { depth++; out.append(line[i++]) }
                '}' -> if (--depth == 0) return i + 1 else out.append(line[i++])
                else -> out.append(line[i++])
            }
        }
        return i
    }
}
