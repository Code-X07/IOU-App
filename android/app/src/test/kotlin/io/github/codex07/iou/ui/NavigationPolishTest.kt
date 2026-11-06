package io.github.codex07.iou.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import io.github.codex07.iou.ui.navigation.NavigationScene
import io.github.codex07.iou.ui.navigation.PageMotion
import io.github.codex07.iou.ui.theme.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.pow

class NavigationPolishTest {
    private fun scene(tab: String = "People", routes: List<String> = emptyList(), generation: String = "a") =
        NavigationScene(tab, routes, generation)

    @Test fun `settings and its data children hide main chrome while retaining the selected tab`() {
        for (tab in listOf("Overview", "People", "Groups", "Activity", "Settlements")) {
            val main = scene(tab)
            val settings = main.copy(stack = listOf("settings:"))
            val data = settings.copy(stack = settings.stack + "form-data:operation:restore")
            assertTrue(main.showBottomBar); assertTrue(main.showAdd)
            assertFalse(settings.showBottomBar); assertFalse(settings.showAdd)
            assertFalse(data.showBottomBar); assertFalse(data.showAdd)
            assertEquals(tab, data.tab)
            assertEquals(settings, data.copy(stack = data.stack.dropLast(1)))
            assertEquals(main, settings.copy(stack = settings.stack.dropLast(1)))
        }
    }

    @Test fun `forms keep their own chrome and unrelated details keep their destination selection`() {
        assertFalse(scene(routes = listOf("form-entry:draft:expense")).showBottomBar)
        val detail = scene(routes = listOf("person:actual-id"))
        assertTrue(detail.showBottomBar); assertFalse(detail.showAdd); assertEquals("People", detail.tab)
    }

    @Test fun `bottom destinations animate once while detail pushes and pops reverse direction`() {
        val people = scene()
        assertEquals(PageMotion.TAB, PageMotion.between(people, scene("Groups")))
        val detail = people.copy(stack = listOf("person:actual-id"))
        val nested = detail.copy(stack = detail.stack + "group:real-id")
        assertEquals(PageMotion.FORWARD, PageMotion.between(people, detail))
        assertEquals(PageMotion.FORWARD, PageMotion.between(detail, nested))
        assertEquals(PageMotion.BACK, PageMotion.between(nested, detail))
        assertEquals(PageMotion.BACK, PageMotion.between(detail, people))
        assertTrue(PageMotion.FORWARD.offsetDp > 0); assertTrue(PageMotion.BACK.offsetDp < 0)
    }

    @Test fun `recomposition initial unlock and ledger replacement do not animate an outgoing page`() {
        val detail = scene(routes = listOf("person:actual-id"))
        assertEquals(PageMotion.NONE, PageMotion.between(null, detail))
        assertEquals(PageMotion.NONE, PageMotion.between(detail, detail.copy()))
        assertEquals(PageMotion.NONE, PageMotion.between(detail, scene(generation = "replacement")))
    }

    @Test fun `saved page keys survive child returns but are isolated after ledger replacement`() {
        val detail = scene(routes = listOf("person:actual-id"))
        val nested = detail.copy(stack = detail.stack + "form-person:unique:actual-id")
        assertEquals(detail.key, nested.copy(stack = nested.stack.dropLast(1)).key)
        assertNotEquals(detail.key, detail.copy(generation = "b").key)
        assertNotEquals(scene("People").key, scene("Groups").key)
    }

    @Test fun `zero system duration scale completes the installed Compose animation immediately`() = runTest {
        var frames = 0
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(++frames * 16_666_667L)
        }
        val disabled = object : MotionDurationScale { override val scaleFactor = 0f }
        withContext(clock + disabled) {
            val progress = Animatable(0f)
            progress.animateTo(1f, tween(PageMotion.FORWARD.durationMillis))
            assertEquals(1f, progress.value)
            assertTrue(frames <= 2, "Disabled animation should not consume its 280ms timeline")
        }
    }

    @Test fun `financial rows stack for small phones and large text without shrinking amounts`() {
        assertTrue(AdaptiveLayout.stackFinancialRow(280f, 1f))
        assertTrue(AdaptiveLayout.stackFinancialRow(390f, 1.5f))
        assertTrue(AdaptiveLayout.stackFinancialRow(600f, 2f))
        assertTrue(AdaptiveLayout.stackFinancialRow(288f, 1f))
        assertFalse(AdaptiveLayout.stackFinancialRow(318f, 1f))
    }

    private fun contrast(a: Color, b: Color): Double {
        fun lum(c: Color): Double {
            fun channel(x: Float) = x.toDouble().let { if (it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4) }
            return channel(c.red) * .2126 + channel(c.green) * .7152 + channel(c.blue) * .0722
        }
        return (maxOf(lum(a), lum(b)) + .05) / (minOf(lum(a), lum(b)) + .05)
    }

    @Test fun `supporting light text blue and negative labels meet ordinary text contrast on raised surfaces`() {
        for (fg in listOf(LightOnBackground, LightOnSurfaceVariant, LightSecondary, LightNegative)) {
            for (bg in listOf(LightSurface, LightBackground, LightSurface2, LightSurface3)) {
                assertTrue(contrast(fg, bg) >= 4.5, "Light supporting color contrast was ${contrast(fg, bg)}")
            }
        }
    }

    @Test fun `dark supporting labels remain readable on all established surfaces`() {
        for (fg in listOf(DarkOnBackground, DarkOnSurfaceVariant, DarkSecondary, DarkNegative)) {
            for (bg in listOf(DarkBackground, DarkSurface, DarkSurface2, DarkSurface3)) {
                assertTrue(contrast(fg, bg) >= 4.5, "Dark supporting color contrast was ${contrast(fg, bg)}")
            }
        }
    }

    @Test fun `hero supporting text and pale primary container labels have ordinary text contrast`() {
        // Hero light-mode supporting text is opaque; alpha fading made it fail at the bright end.
        assertTrue(contrast(LightBackground, LightPrimary) >= 4.5)
        assertTrue(contrast(LightBackground, Color(0xFF0E5640)) >= 4.5)
        val tint = Color(LightPrimary.red * .11f + LightBackground.red * .89f,
            LightPrimary.green * .11f + LightBackground.green * .89f,
            LightPrimary.blue * .11f + LightBackground.blue * .89f)
        assertTrue(contrast(LightOnPrimaryContainer, tint) >= 4.5)
    }
}
