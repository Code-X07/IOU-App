package io.github.codex07.iou.ui.navigation

import io.github.codex07.iou.ledger.model.RecurringId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RecurringNotificationRouteTest {
    @Test fun `only due intents navigate and unusable schedule extras fall back to all due expenses`() {
        assertNull(RecurringNotificationRoute.fromExtras("other", "schedule-1"))
        assertNull(RecurringNotificationRoute.fromExtras(null, "schedule-1"))
        assertEquals(RecurringNotificationRoute(null), RecurringNotificationRoute.fromExtras("due", null))
        assertEquals(RecurringNotificationRoute(null), RecurringNotificationRoute.fromExtras("due", "form:other"))
        assertEquals(RecurringNotificationRoute(RecurringId("schedule-1")), RecurringNotificationRoute.fromExtras("due", "schedule-1"))
    }

    @Test fun `cold and warm due routes preserve drafts and do not duplicate the due destination`() {
        val all = RecurringNotificationRoute(null)
        val focused = RecurringNotificationRoute(RecurringId("schedule-1"))
        assertEquals(listOf("due:"), all.open(emptyList()))
        val draft = "form-entry:draft:expense"
        val warm = focused.open(listOf(draft))
        assertEquals(listOf(draft, "due:schedule-1"), warm)
        assertEquals(warm, focused.open(warm))
        assertEquals(listOf(draft, "due:"), all.open(warm))
        assertEquals(listOf(draft, "entry:row", "due:schedule-1"), focused.open(warm + "entry:row"))
    }
}
