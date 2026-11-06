package io.github.codex07.iou.ui.theme

/** Leave amounts a full line on small phones and at large accessibility font sizes. */
object AdaptiveLayout {
    fun stackFinancialRow(availableWidthDp: Float, fontScale: Float): Boolean =
        availableWidthDp < 300f || fontScale > 1.2f
}
