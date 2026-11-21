package io.github.codex07.iou.ui.format

import io.github.codex07.iou.api.*
import io.github.codex07.iou.fake.FakeApis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PayerFormatTest {
    @Test fun `expense payers are named in stored order and the viewer is shown as you`() = runTest {
        val apis = FakeApis.seeded()
        val all = apis.ledger.observeActivity().first()
        val museum = all.first { it.description == "Museum tickets" }
        assertEquals("Alex & Bea", PayerFormat.names(museum, "You"))
        val people = apis.people.observePeople(true, true).first().map { it.person }
        val me = all.first { it.paidBy.isMe }.paidBy
        val (alex, bea, chen, dana, erik) = listOf("Alex", "Bea", "Chen", "Dana", "Erik").map { name -> people.first { it.displayName == name } }
        val paid = museum.paid
        assertEquals("You", PayerFormat.names(museum.copy(payers = listOf(PayerView(me, paid, null))), "You"))
        assertEquals("Alex, Bea & 1 other", PayerFormat.names(museum.copy(payers = listOf(alex, bea, chen).map { PayerView(it, paid, null) }), "You"))
        assertEquals("Alex, Bea & 3 others", PayerFormat.names(museum.copy(payers = listOf(alex, bea, chen, dana, erik).map { PayerView(it, paid, null) }), "You"))
    }

    @Test fun `transfers are named by their sender`() = runTest {
        val apis = FakeApis.seeded()
        val all = apis.ledger.observeActivity().first()
        val repayment = all.first { it.kind == EntryKind.TRANSFER && it.paidBy.displayName == "Alex" }
        assertEquals("Alex", PayerFormat.names(repayment, "You"))
        val fromMe = all.first { it.kind == EntryKind.TRANSFER && it.paidBy.isMe }
        assertEquals("You", PayerFormat.names(fromMe, "You"))
    }
}
