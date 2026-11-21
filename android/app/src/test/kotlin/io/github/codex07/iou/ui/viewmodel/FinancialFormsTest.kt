package io.github.codex07.iou.ui.viewmodel

import io.github.codex07.iou.api.*
import io.github.codex07.iou.fake.FakeApis
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.money.*
import io.github.codex07.iou.ui.forms.*
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FinancialFormsTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeEach fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun cleanup() { Dispatchers.resetMain() }
    private val eur = CurrencyCode("EUR")
    private fun metadata(a: Apis, people: PeopleApi = a.people) = MetadataFormViewModel(people, a.groups, a.ledger, a.backup)
    private fun entry(a: Apis, ledger: LedgerApi = a.ledger, fx: FxApi = a.fx) = EntryFormViewModel(ledger, a.people, a.groups, a.setup, fx, a.backup)
    private suspend fun me(a: Apis) = (a.setup.observeSetupState().first() as SetupState.Ready).me.id
    private suspend fun other(a: Apis) = a.people.observePeople().first().first().person.id
    private suspend fun basis(a: Apis) = a.backup.observeLedgerGeneration().first()
    private suspend fun group(a: Apis, mode: FxMode = FxMode.KEEP): GroupId =
        (a.groups.createGroup(GroupDraft("Forms test", eur, mode), a.people.observePeople().first().take(2).map { it.person.id }, basis(a)) as LedgerResult.Ok).value
    private suspend fun expenseFields(a: Apis, group: GroupId? = null): EntryFields {
        val self = me(a).value; val friend = other(a).value
        return EntryFields(amount = "12,50", currency = "EUR", description = "Native form test", payers = listOf(self),
            participants = listOf(self, friend), group = group?.value, date = LocalDate.of(2026, 11, 4).toString())
    }

    @Test fun `contact creation validates names and saves once during an outstanding request`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val gate = CompletableDeferred<Unit>(); var calls = 0
        val api = object : PeopleApi by a.people {
            override suspend fun createPerson(draft: PersonDraft, basis: LedgerGeneration): LedgerResult<PersonId> {
                calls++; gate.await(); return a.people.createPerson(draft, basis)
            }
        }
        val vm = metadata(a, api); vm.open(MetadataKind.PERSON); runCurrent()
        vm.save(); runCurrent(); assertTrue(vm.status.value.error is LedgerError.InvalidField)
        vm.change { it.copy(name = "New contact", currency = "KWD", note = "A note") }
        vm.save(); vm.save(); runCurrent()
        assertEquals(1, calls); assertTrue(vm.status.value.busy)
        vm.requestLeave(); assertFalse(vm.status.value.exit)
        gate.complete(Unit); runCurrent()
        val id = PersonId(vm.status.value.completedId!!)
        val person = a.people.observePerson(id).first()!!
        assertEquals("New contact", person.person.displayName); assertEquals(CurrencyCode("KWD"), person.defaultCurrency)
        vm.save(); runCurrent(); assertEquals(1, calls)
        vm.end()
    }

    @Test fun `contact edit retains token and stale edits require an explicit reload`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val person = other(a)
        val vm = metadata(a); vm.open(MetadataKind.PERSON, person.value); runCurrent()
        vm.change { it.copy(name = "My unfinished edit") }
        val detail = a.people.observePerson(person).first()!!
        a.people.updatePerson(person, PersonDraft("Updated elsewhere"), detail.token)
        vm.save(); runCurrent()
        assertTrue(vm.status.value.error is LedgerError.StaleVersion)
        assertEquals("Updated elsewhere", a.people.observePerson(person).first()!!.person.displayName)
        assertEquals("My unfinished edit", vm.state.value.fields.name)
        vm.reviewLatest(); runCurrent()
        assertEquals("Updated elsewhere", vm.state.value.fields.name); assertFalse(vm.status.value.dirty)
        vm.end()
    }

    @Test fun `group creation includes me once and metadata edit preserves memberships`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val person = other(a)
        val vm = metadata(a); vm.open(MetadataKind.GROUP, members = setOf(person)); runCurrent()
        vm.change { it.copy(name = "New native group", currency = "SEK", fxMode = FxMode.KEEP) }
        vm.save(); runCurrent()
        val id = GroupId(vm.status.value.completedId!!)
        val created = a.groups.observeGroup(id).first()!!
        assertEquals(setOf(me(a), person), created.members.map { it.person.id }.toSet())
        assertEquals(1, created.members.count { it.person.isMe }); assertEquals(FxMode.KEEP, created.fxMode)
        val edit = metadata(a); edit.open(MetadataKind.GROUP, id.value); runCurrent()
        edit.change { it.copy(name = "Renamed group") }; edit.save(); runCurrent()
        assertEquals(created.members, a.groups.observeGroup(id).first()!!.members)
        assertEquals("Renamed group", a.groups.observeGroup(id).first()!!.group.name)
        vm.end(); edit.end()
    }

    @Test fun `invalid group currency and locked conversion settings are rejected`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = metadata(a)
        vm.open(MetadataKind.GROUP); runCurrent()
        vm.save(); runCurrent(); assertEquals("name", (vm.status.value.error as LedgerError.InvalidField).field)
        vm.change { it.copy(name = "Currency test", currency = "ZZZ") }; vm.save(); runCurrent()
        assertTrue(vm.status.value.error is LedgerError.UnknownCurrency)
        val locked = a.groups.observeGroups().first().first { it.fxMode == FxMode.CONVERT }
        val edit = metadata(a); edit.open(MetadataKind.GROUP, locked.group.id.value); runCurrent()
        assertTrue(edit.state.value.locked)
        edit.change { it.copy(fxMode = FxMode.KEEP) }; edit.save(); runCurrent()
        assertTrue(edit.status.value.error is LedgerError.GroupSettingsLocked)
        vm.end(); edit.end()
    }

    @Test fun `handle validation and alias default flags are preserved on edit`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val alex = a.people.observePeople().first().first { it.aliasCount > 0 }.person.id
        val alias = (a.people.createPerson(PersonDraft("Alias with handle"), basis(a)) as LedgerResult.Ok).value
        a.people.addHandle(alias, HandleDraft(HandleType.IBAN, "GB82WEST12345698765432", "Alias bank", isDefault = true), basis(a))
        val aliasDetail = a.people.observePerson(alias).first()!!
        a.people.merge(alias, alex, aliasDetail.token)
        val detail = a.people.observePerson(alex).first()!!
        val aliasHandle = detail.handles.first { it.owner.id != alex }
        val vm = metadata(a); vm.open(MetadataKind.HANDLE, aliasHandle.id.value, alex.value); runCurrent()
        assertEquals(aliasHandle.isDefault, vm.state.value.fields.defaultHandle)
        vm.change { it.copy(label = "Changed label") }; vm.save(); runCurrent()
        val after = a.people.observePerson(alex).first()!!.handles.first { it.id == aliasHandle.id }
        assertEquals(aliasHandle.isDefault, after.isDefault); assertEquals(aliasHandle.owner, after.owner)
        val add = metadata(a); add.open(MetadataKind.HANDLE, owner = alex.value); runCurrent()
        add.change { it.copy(handleType = HandleType.IBAN, value = "invalid") }; add.save(); runCurrent()
        assertTrue(add.status.value.error is LedgerError.InvalidIban)
        add.change { it.copy(value = "DE89370400440532013000", defaultHandle = true) }; add.save(); runCurrent()
        assertEquals("label", (add.status.value.error as LedgerError.InvalidField).field)
        add.change { it.copy(label = "New bank account") }; add.save(); runCurrent()
        assertNotNull(add.status.value.completedId)
        vm.end(); add.end()
    }

    @Test fun `decimal comma and zero and three decimal currencies retain exact minor units`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val fields = expenseFields(a)
        val draft = (EntryInput.draft(fields) as LedgerResult.Ok).value as ExpenseDraft
        assertEquals(Money(1250, eur), draft.total)
        val jpy = (EntryInput.draft(fields.copy(currency = "JPY", amount = "123")) as LedgerResult.Ok).value as ExpenseDraft
        assertEquals(123L, jpy.total.amountMinor)
        val kwd = (EntryInput.draft(fields.copy(currency = "KWD", amount = "12,345")) as LedgerResult.Ok).value as ExpenseDraft
        assertEquals(12345L, kwd.total.amountMinor)
        assertTrue(EntryInput.draft(fields.copy(currency = "JPY", amount = "1.1")).errorOrNull() is LedgerError.TooManyDecimals)
        assertTrue(EntryInput.draft(fields.copy(amount = "1,234.50")).errorOrNull() is LedgerError.InvalidAmount)
        assertEquals(1250L, EntryInput.percentage("12,50", other(a)).getOrNull())
    }

    @Test fun `all four expense splits save with exactly the backend preview allocations`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val group = group(a)
        for (split in SplitType.entries) {
            val vm = entry(a); vm.open(); runCurrent()
            val base = expenseFields(a, group)
            val first = base.participants[0]; val second = base.participants[1]
            val inputs = when (split) {
                SplitType.EQUAL -> emptyMap()
                SplitType.PERCENT -> mapOf(first to "60", second to "40")
                SplitType.SHARES -> mapOf(first to "2", second to "1")
                SplitType.EXACT -> mapOf(first to "7,50", second to "5")
            }
            vm.change { base.copy(split = split, inputs = inputs) }
            advanceTimeBy(300); runCurrent()
            val preview = vm.state.value.preview
            assertNotNull(preview, "No preview for $split: ${vm.state.value.previewError}")
            vm.save(); runCurrent()
            assertNull(vm.status.value.error)
            val saved = a.ledger.observeEntry(EntryId(vm.status.value.completedId!!)).first()!!
            assertEquals(preview!!.shares, saved.shares); assertEquals(Money(1250, eur), saved.item.paid)
            vm.finish()
        }
    }

    @Test fun `invalid percentage exact and share weights cannot save`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val fields = expenseFields(a)
        val vm = entry(a); vm.open(); runCurrent()
        val first = fields.participants[0]; val second = fields.participants[1]
        vm.change { fields.copy(split = SplitType.PERCENT, inputs = mapOf(first to "40", second to "40")) }
        vm.save(); runCurrent(); assertTrue(vm.status.value.error is LedgerError.PercentNotHundred)
        vm.change { fields.copy(split = SplitType.EXACT, inputs = mapOf(first to "1", second to "1")) }
        vm.save(); runCurrent(); assertEquals(1050L, (vm.status.value.error as LedgerError.ExactRemaining).diffMinor)
        vm.change { fields.copy(split = SplitType.SHARES, inputs = mapOf(first to "1000001", second to "1")) }
        vm.save(); runCurrent(); assertTrue(vm.status.value.error is LedgerError.WeightOutOfRange)
        vm.finish()
    }

    @Test fun `multi person personal expense returns NeedsGroup and resumes the untouched draft after creation`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val base = expenseFields(a)
        val third = a.people.observePeople().first()[1].person.id.value
        val vm = entry(a); vm.open(); runCurrent()
        vm.change { base.copy(participants = base.participants + third, note = "Keep this note") }
        vm.save(); runCurrent(); assertTrue(vm.status.value.error is LedgerError.NeedsGroup)
        val before = vm.state.value.fields
        val groupForm = metadata(a); groupForm.open(MetadataKind.GROUP, members = vm.groupMembersForDraft()); runCurrent()
        groupForm.change { it.copy(name = "From unfinished expense", fxMode = FxMode.KEEP) }; groupForm.save(); runCurrent()
        vm.useCreatedGroup(groupForm.status.value.completedId!!); runCurrent()
        assertEquals(before.amount, vm.state.value.fields.amount); assertEquals(before.note, vm.state.value.fields.note)
        assertEquals(before.participants, vm.state.value.fields.participants)
        vm.save(); runCurrent(); assertNotNull(vm.status.value.completedId)
        vm.finish(); groupForm.end()
    }

    @Test fun `missing FX requires manual rate and bank actual saves exact charged amount`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val group = group(a, FxMode.CONVERT); val base = expenseFields(a, group).copy(currency = "INR", amount = "100")
        val vm = entry(a); vm.open(); runCurrent(); vm.change { base }
        vm.save(); runCurrent(); assertTrue(vm.status.value.error is LedgerError.RateRequired)
        vm.change { it.copy(fxMode = FxInputMode.MANUAL, rate = "0.01") }; vm.save(); runCurrent()
        val saved = a.ledger.observeEntry(EntryId(vm.status.value.completedId!!)).first()!!
        assertEquals(Money(100, eur), saved.item.effect); assertEquals(FxSource.USER_ENTERED, saved.fx!!.source)
        val bank = entry(a); bank.open(); runCurrent()
        bank.change { base.copy(fxMode = FxInputMode.BANK, charged = "1,23") }; bank.save(); runCurrent()
        val charged = a.ledger.observeEntry(EntryId(bank.status.value.completedId!!)).first()!!
        assertEquals(Money(123, eur), charged.item.effect); assertEquals(FxSource.BANK_ACTUAL, charged.fx!!.source)
        vm.finish(); bank.finish()
    }

    @Test fun `loan directions and partial repayments use transfer API and show backend remaining balance`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val self = me(a); val friend = other(a)
        for ((from, to) in listOf(self to friend, friend to self)) {
            val vm = entry(a); vm.open("loan"); runCurrent()
            vm.change { it.copy(amount = "10", currency = "EUR", payer = from.value, receiver = to.value) }
            vm.save(); runCurrent()
            val saved = a.ledger.observeEntry(EntryId(vm.status.value.completedId!!)).first()!!
            assertEquals(from, saved.item.paidBy.id); assertEquals(to, saved.item.receiver!!.id)
            assertEquals(TransferKind.LOAN, saved.item.transferKind); vm.finish()
        }
        val repayment = entry(a); repayment.open("repayment"); runCurrent()
        repayment.change { it.copy(amount = "2.50", payer = friend.value, receiver = self.value) }; repayment.save(); runCurrent()
        assertNotNull(repayment.status.value.completedId)
        assertEquals(a.people.observePerson(friend).first()!!.balances, repayment.state.value.remaining)
        val invalid = entry(a); invalid.open("repayment"); runCurrent()
        invalid.change { it.copy(amount = "1", receiver = self.value) }; invalid.save(); runCurrent()
        assertEquals(LedgerError.SelfTransfer, invalid.status.value.error)
        repayment.finish(); invalid.finish()
    }


    @Test fun `cross currency repayment uses settlesCurrency and actual bank charge`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = entry(a); vm.open("repayment"); runCurrent()
        val friend = other(a); val self = me(a)
        vm.change { it.copy(amount = "9", currency = "EUR", payer = friend.value, receiver = self.value,
            settles = "SEK", fxMode = FxInputMode.BANK, charged = "100") }
        vm.save(); runCurrent()
        val saved = a.ledger.observeEntry(EntryId(vm.status.value.completedId!!)).first()!!
        assertEquals(Money(10000, CurrencyCode("SEK")), saved.item.effect)
        assertEquals("SEK", (saved.editDraft as TransferDraft).settlesCurrency!!.code)
        vm.finish()
    }

    @Test fun `note only edits preserve stored shares FX and entry identity`() = runTest(dispatcher) {
        val a = FakeApis.seeded()
        val item = a.ledger.observeActivity().first().first { it.paid.currency != it.effect.currency }
        val before = a.ledger.observeEntry(item.entryId).first()!!
        val count = a.ledger.observeActivity().first().size
        val vm = entry(a); vm.open(entryId = item.entryId.value); runCurrent()
        assertEquals(FxInputMode.SAVED, vm.state.value.fields.fxMode)
        vm.change { it.copy(note = "Note-only native edit") }; advanceTimeBy(300); runCurrent()
        assertEquals(false, vm.state.value.preview!!.recomputed)
        vm.save(); runCurrent()
        assertEquals(item.entryId.value, vm.status.value.completedId)
        val after = a.ledger.observeEntry(item.entryId).first()!!
        assertEquals(before.shares, after.shares); assertEquals(before.fx, after.fx)
        assertEquals(count, a.ledger.observeActivity().first().size)
        assertTrue(after.history.size > before.history.size)
        vm.finish()
    }

    @Test fun `stale transaction edit cannot overwrite and reload takes the latest token`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val item = a.ledger.observeActivity().first().first { it.kind == EntryKind.EXPENSE }
        val before = a.ledger.observeEntry(item.entryId).first()!!
        val vm = entry(a); vm.open(entryId = item.entryId.value); runCurrent()
        vm.change { it.copy(note = "Old token edit") }
        a.ledger.saveExpense((before.editDraft as ExpenseDraft).copy(note = "Written elsewhere"), SaveMode.Update(item.entryId, before.token))
        vm.save(); runCurrent(); assertTrue(vm.status.value.error is LedgerError.StaleVersion)
        assertEquals("Written elsewhere", a.ledger.observeEntry(item.entryId).first()!!.item.note)
        vm.reviewLatest(); runCurrent(); assertEquals("Written elsewhere", vm.state.value.fields.note)
        vm.change { it.copy(note = "Reviewed edit") }; vm.save(); runCurrent()
        assertNotNull(vm.status.value.completedId); vm.finish()
    }

    @Test fun `bank actual amount edits require a replacement snapshot`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val group = group(a, FxMode.CONVERT)
        val original = entry(a); original.open(); runCurrent()
        val fields = expenseFields(a, group).copy(currency = "INR", amount = "100", fxMode = FxInputMode.BANK, charged = "1")
        original.change { fields }; original.save(); runCurrent()
        val id = original.status.value.completedId!!
        val edit = entry(a); edit.open(entryId = id); runCurrent(); edit.change { it.copy(amount = "200") }
        edit.save(); runCurrent(); assertTrue(edit.status.value.error is LedgerError.FxSnapshotMismatch)
        assertEquals(Money(100, eur), a.ledger.observeEntry(EntryId(id)).first()!!.item.effect)
        edit.change { it.copy(fxMode = FxInputMode.BANK, charged = "2.25") }; edit.save(); runCurrent()
        assertEquals(Money(225, eur), a.ledger.observeEntry(EntryId(id)).first()!!.item.effect)
        original.finish(); edit.finish()
    }

    @Test fun `ledger replacement invalidates dirty drafts cancels preview and prevents saves`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val metadata = metadata(a); metadata.open(MetadataKind.PERSON); runCurrent()
        metadata.change { it.copy(name = "Must not save") }
        val vm = entry(a); vm.open(); runCurrent(); val fields = expenseFields(a); vm.change { fields }
        a.backup.createBackup(DocumentRef("memory-backup"), null)
        a.backup.restoreBackup(DocumentRef("memory-backup"), null); runCurrent()
        assertTrue(metadata.status.value.invalidated); assertTrue(vm.status.value.invalidated)
        assertEquals(LedgerError.LedgerReplaced, vm.status.value.error)
        assertNull(vm.state.value.preview)
        metadata.save(); vm.save(); runCurrent()
        assertNull(metadata.status.value.completedId); assertNull(vm.status.value.completedId)
        metadata.requestLeave(); assertTrue(metadata.status.value.exit)
        metadata.end(); vm.finish()
    }

    @Test fun `clean cancel exits and dirty back requires keep editing or discard`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val clean = metadata(a); clean.open(MetadataKind.PERSON); runCurrent()
        clean.requestLeave(); assertTrue(clean.status.value.exit); assertFalse(clean.status.value.discard)
        val dirty = entry(a); dirty.open(); runCurrent(); val original = dirty.state.value.fields
        dirty.change { it.copy(amount = "100") }; dirty.requestLeave()
        assertTrue(dirty.status.value.discard); assertFalse(dirty.status.value.exit)
        dirty.keepEditing(); assertFalse(dirty.status.value.discard); assertEquals("100", dirty.state.value.fields.amount)
        dirty.change { original }; assertFalse(dirty.status.value.dirty)
        dirty.change { it.copy(note = "Unfinished") }; dirty.requestLeave(); dirty.discard()
        assertTrue(dirty.status.value.exit); assertFalse(dirty.status.value.discard)
        clean.end(); dirty.finish()
    }

    @Test fun `preview requests debounce and ignore late results from canceled drafts`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); var calls = 0
        val api = object : LedgerApi by a.ledger {
            override suspend fun previewSplit(draft: ExpenseDraft, editing: EntryId?): LedgerResult<SplitPreview> {
                calls++
                if (draft.total.amountMinor == 1000L) withContext(NonCancellable) { delay(1000) }
                return a.ledger.previewSplit(draft, editing)
            }
        }
        val vm = entry(a, api); vm.open(); runCurrent(); val base = expenseFields(a)
        vm.change { base.copy(amount = "9") }; vm.change { base.copy(amount = "10") }
        advanceTimeBy(300); runCurrent(); assertEquals(1, calls)
        vm.change { it.copy(amount = "20") }; advanceTimeBy(300); runCurrent()
        assertEquals(Money(2000, eur), vm.state.value.preview!!.effectTotal)
        advanceTimeBy(1000); runCurrent()
        assertEquals(Money(2000, eur), vm.state.value.preview!!.effectTotal)
        assertEquals(2, calls); vm.finish()
    }

    @Test fun `stale available quotes require explicit acceptance`() = runTest(dispatcher) {
        val a = FakeApis.seeded()
        val staleFx = object : FxApi by a.fx {
            override suspend fun quote(date: LocalDate, from: CurrencyCode, to: CurrencyCode) = a.fx.quote(date, from, to).map { it.copy(isStale = true) }
        }
        val vm = entry(a, fx = staleFx); vm.open(); runCurrent()
        val group = group(a, FxMode.CONVERT); runCurrent()
        val base = expenseFields(a, group).copy(currency = "SEK", amount = "100")
        vm.change { base }; vm.save(); runCurrent()
        assertEquals("staleRate", (vm.status.value.error as LedgerError.InvalidField).field)
        vm.change { it.copy(acceptStale = true) }; vm.save(); runCurrent()
        assertNotNull(vm.status.value.completedId); vm.finish()
    }

    @Test fun `entry save taps enqueue a single mutation while FX or a write is pending`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val gate = CompletableDeferred<Unit>(); var calls = 0
        val api = object : LedgerApi by a.ledger {
            override suspend fun saveExpense(draft: ExpenseDraft, mode: SaveMode): LedgerResult<EntryId> {
                calls++; gate.await(); return a.ledger.saveExpense(draft, mode)
            }
        }
        val vm = entry(a, api); vm.open(); runCurrent(); val fields = expenseFields(a)
        vm.change { fields }; vm.save(); vm.save(); runCurrent(); assertEquals(1, calls)
        assertTrue(vm.status.value.busy)
        gate.complete(Unit); runCurrent(); assertNotNull(vm.status.value.completedId)
        vm.save(); runCurrent(); assertEquals(1, calls); vm.finish()
    }

    @Test fun `cross currency transfer edit preserves method account effect and snapshot`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val self = me(a); val friend = other(a)
        val original = entry(a); original.open("repayment"); runCurrent()
        original.change { it.copy(amount = "9", currency = "EUR", payer = friend.value, receiver = self.value,
            settles = "SEK", fxMode = FxInputMode.BANK, charged = "100", method = TransferMethod.CARD, account = "Travel card") }
        original.save(); runCurrent(); val id = EntryId(original.status.value.completedId!!)
        val before = a.ledger.observeEntry(id).first()!!
        val edit = entry(a); edit.open(entryId = id.value); runCurrent(); edit.change { it.copy(note = "Edited note") }
        edit.save(); runCurrent()
        val after = a.ledger.observeEntry(id).first()!!
        assertEquals(before.fx, after.fx); assertEquals(before.item.effect, after.item.effect)
        val transfer = after.editDraft as TransferDraft
        assertEquals(TransferMethod.CARD, transfer.method); assertEquals("Travel card", transfer.accountLabel)
        assertEquals(CurrencyCode("SEK"), transfer.settlesCurrency); assertEquals(id.value, edit.status.value.completedId)
        original.finish(); edit.finish()
    }

    @Test fun `post merge note edit preserves authoritative stored shares instead of redistributing`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val self = me(a); val alex = other(a)
        val alias = (a.people.createPerson(PersonDraft("Second identity"), basis(a)) as LedgerResult.Ok).value
        val g = (a.groups.createGroup(GroupDraft("Merged split", eur, FxMode.KEEP), listOf(alex, alias), basis(a)) as LedgerResult.Ok).value
        val id = (a.ledger.saveExpense(ExpenseDraft(self, "Before merge", total = Money(9000, eur), groupId = g,
            participants = listOf(SplitInput(self), SplitInput(alex), SplitInput(alias)), occurredOn = LocalDate.now()), SaveMode.Create(basis(a))) as LedgerResult.Ok).value
        a.people.merge(alias, alex, a.people.observePerson(alias).first()!!.token)
        val before = a.ledger.observeEntry(id).first()!!
        val vm = entry(a); vm.open(entryId = id.value); runCurrent(); vm.change { it.copy(note = "Keep stored shares") }
        advanceTimeBy(300); runCurrent(); assertEquals(false, vm.state.value.preview!!.recomputed)
        vm.save(); runCurrent()
        assertEquals(before.shares, a.ledger.observeEntry(id).first()!!.shares)
        vm.finish()
    }

    @Test fun `invalid manual rates bank charges and zero amounts remain rejected`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val g = group(a, FxMode.CONVERT); val base = expenseFields(a, g).copy(currency = "INR", amount = "100")
        val vm = entry(a); vm.open(); runCurrent()
        vm.change { base.copy(fxMode = FxInputMode.MANUAL, rate = "0") }; vm.save(); runCurrent()
        assertTrue(vm.status.value.error is LedgerError.InvalidRate)
        vm.change { base.copy(fxMode = FxInputMode.BANK, charged = "0") }; vm.save(); runCurrent()
        assertNotNull(vm.status.value.error); assertNull(vm.status.value.completedId)
        vm.change { base.copy(amount = "0") }; vm.save(); runCurrent()
        assertEquals(LedgerError.InvalidAmount, vm.status.value.error); vm.finish()
    }

    @Test fun `person edit through a merged alias writes the canonical person`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val canonical = a.people.observePeople().first().first { it.aliasCount > 0 }.person.id
        val alias = a.people.observePerson(canonical).first()!!.aliases.first().person.id
        val vm = metadata(a); vm.open(MetadataKind.PERSON, alias.value); runCurrent()
        vm.change { it.copy(name = "Canonical rename") }; vm.save(); runCurrent()
        assertEquals(canonical.value, vm.status.value.completedId)
        assertEquals("Canonical rename", a.people.observePerson(canonical).first()!!.person.displayName)
        vm.end()
    }

    @Test fun `transfer activity filter includes the backend loan and repayment kinds`() = runTest(dispatcher) {
        val a = FakeApis.seeded()
        // EntryKind is enforced by the backend filter, subtype filtering must not exclude either kind.
        val backend = a.ledger.observeActivity(ActivityQuery(type = ActivityType.TRANSFER).backendFilter()).first()
        assertTrue(backend.any { it.transferKind == TransferKind.LOAN })
        assertTrue(backend.any { it.transferKind == TransferKind.REPAYMENT })
        assertTrue(backend.all { ActivityQuery(type = ActivityType.TRANSFER).matches(it) })
    }

    @Test fun `expense save and edit roundtrip zero and three decimal currencies`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val base = expenseFields(a)
        for ((code, text, minor) in listOf(Triple("JPY", "123", 123L), Triple("KWD", "12,345", 12345L))) {
            val vm = entry(a); vm.open(); runCurrent(); vm.change { base.copy(currency = code, amount = text) }
            vm.save(); runCurrent(); val id = EntryId(vm.status.value.completedId!!)
            assertEquals(Money(minor, CurrencyCode(code)), a.ledger.observeEntry(id).first()!!.item.paid)
            val edit = entry(a); edit.open(entryId = id.value); runCurrent()
            assertEquals(Money(minor, CurrencyCode(code)).toPlainString(), edit.state.value.fields.amount)
            vm.finish(); edit.finish()
        }
        assertTrue(EntryInput.draft(base.copy(date = "not a date")).errorOrNull() is LedgerError.InvalidField)
    }
}
