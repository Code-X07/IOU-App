package io.github.codex07.iou.io

/**
 * Reversible formula-injection protection for exported text (spec §7.2): a value starting with `=`, `+`,
 * `-`, `@`, tab, CR, LF, full-width `＝`, `＋`, `－`, `＠` or `'` gets one `'` prefixed. Escaping a leading `'` too makes it lossless: [unescape]
 * removes exactly one leading `'`. Only own exports, whose manifest declares [NAME], are unescaped.
 */
object TextEscaping {
    const val NAME = "quote-prefix-v1"

    private val triggers = setOf('=', '+', '-', '@', '\t', '\r', '\n', '＝', '＋', '－', '＠', '\'')

    fun escape(text: String): String = if (text.isNotEmpty() && text[0] in triggers) "'$text" else text

    fun unescape(text: String): String = if (text.startsWith('\'')) text.substring(1) else text
}
