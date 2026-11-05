package io.github.codex07.iou.io

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TextEscapingTest {

    @Test
    fun `values that a spreadsheet would run get one quote, and so does a leading quote`() {
        TextEscaping.escape("=SUM(1)") shouldBe "'=SUM(1)"
        TextEscaping.escape("=HYPERLINK(\"http://evil\",\"x\")") shouldBe "'=HYPERLINK(\"http://evil\",\"x\")"
        for (start in listOf("+", "-", "@", "\t", "\r", "\n", "＝", "＋", "－", "＠")) {
            val original = "${start}x"
            TextEscaping.escape(original) shouldBe "'$original"
            TextEscaping.unescape(TextEscaping.escape(original)) shouldBe original
        }
        TextEscaping.escape("'quoted") shouldBe "''quoted"
        TextEscaping.escape("plain = text") shouldBe "plain = text"
        TextEscaping.escape("") shouldBe ""
    }

    @Test
    fun `unescaping removes exactly one leading quote`() {
        TextEscaping.unescape("'=SUM(1)") shouldBe "=SUM(1)"
        TextEscaping.unescape("''quoted") shouldBe "'quoted"
        TextEscaping.unescape("plain") shouldBe "plain"
    }

    @Test
    fun `escaping is lossless for every string`() = runTest {
        checkAll(Arb.string(0..12)) { text -> TextEscaping.unescape(TextEscaping.escape(text)) shouldBe text }
    }
}
