package io.github.codex07.iou.ui.viewmodel

import app.cash.turbine.test
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
class AdvancedManagementTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeEach fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun cleanup() { Dispatchers.resetMain() }
    private val eur = CurrencyCode("EUR")
    private fun operations(a: Apis, people: PeopleApi = a.people, ledger: LedgerApi = a.ledger) = AdvancedViewModel(people, a.groups, ledger, a.backup)
    private fun entry(a: Apis) = EntryFormViewModel(a.ledger, a.people, a.groups, a.setup, a.fx, a.backup)
    private fun metadata(a: Apis) = MetadataFormViewModel(a.people, a.groups, a.ledger, a.backup)
    private suspend fun basis(a: Apis) = a.backup.observeLedgerGeneration().first()
    private suspend fun me(a: Apis) = (a.setup.observeSetupState().first() as SetupState.Ready).me.id
    private suspend fun person(a: Apis, name: String = "New person") = (a.people.createPerson(PersonDraft(name), basis(a)) as LedgerResult.Ok).value
    private suspend fun group(a: Apis, member: PersonId) = (a.groups.createGroup(GroupDraft("New group", eur, FxMode.KEEP), listOf(member), basis(a)) as LedgerResult.Ok).value
    private suspend fun prepared(vm: AdvancedViewModel): PreparedForm = vm.form(vm.state.value.openRoute!!.substringAfter(':'))!!

    @Test fun `settlements directory uses backend personal drafts and full group plans`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val vm = SettlementsViewModel(a.people, a.groups, a.ledger, a.backup)
        vm.state.test {
            assertEquals(BrowseState.Loading, awaitItem())
            val page = (awaitItem() as BrowseState.Ready).value
            assertTrue(page.personal.isNotEmpty()); assertTrue(page.groups.any { it.settlePlan.isNotEmpty() })
            page.personal.forEach { assertTrue(it.draft in (a.ledger.settleUpWithPerson(it.person.id, basis(a)) as LedgerResult.Ok).value) }
            page.groups.forEach { assertEquals(a.groups.observeGroup(it.group.id).first()!!.settlePlan, it.settlePlan) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `personal preparation retains sender recipient amount and basis`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val other = a.people.observePeople().first().first { it.balances.any { b -> b.amountMinor != 0L } }.person
        val draft = (a.ledger.settleUpWithPerson(other.id, basis(a)) as LedgerResult.Ok).value.first()
        val vm = operations(a); runCurrent(); vm.preparePersonal(PersonalSettlement(other, draft)); runCurrent()
        assertEquals(draft, (prepared(vm) as PreparedForm.Repayment).draft)
        val form = entry(a); form.openSettlement(draft); runCurrent()
        assertEquals(draft.draft.from.value, form.state.value.fields.payer)
        assertEquals(draft.draft.to.value, form.state.value.fields.receiver)
        assertEquals(draft.draft.amount.toPlainString(), form.state.value.fields.amount)
        form.finish()
    }

    @Test fun `group plan supports partial repayment between other members`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val self = me(a)
        val payer = person(a, "Other payer"); val recipient = person(a, "Other participant"); val g = group(a, payer)
        a.groups.addMember(g, recipient, basis(a))
        a.ledger.saveExpense(ExpenseDraft(payer, "Other members expense", total = Money(3000, eur), groupId = g,
            participants = listOf(SplitInput(payer), SplitInput(recipient)), occurredOn = LocalDate.now()), SaveMode.Create(basis(a)))
        val plan = a.groups.observeGroup(g).first()!!.settlePlan.first { it.from.id != self && it.to.id != self }
        val vm = operations(a); runCurrent(); vm.preparePlan(plan); runCurrent()
        val draft = (prepared(vm) as PreparedForm.Repayment).draft
        assertEquals(plan.from.id, draft.draft.from); assertEquals(plan.to.id, draft.draft.to)
        val form = entry(a); form.openSettlement(draft); runCurrent(); form.change { it.copy(amount = "1") }; form.save(); runCurrent()
        val saved = a.ledger.observeEntry(EntryId(form.status.value.completedId!!)).first()!!
        assertEquals(plan.from.id, saved.item.paidBy.id); assertEquals(plan.to.id, saved.item.receiver!!.id)
        assertEquals(plan.group, saved.item.group); assertEquals(TransferKind.REPAYMENT, saved.item.transferKind)
        assertEquals(100L, saved.item.paid.amountMinor)
        assertEquals(setOf(plan.from.id, plan.to.id), form.state.value.remainingMembers.map { it.person.id }.toSet())
        assertTrue(form.state.value.remainingMembers.none { it.person.isMe })
        assertNotEquals(plan, a.groups.observeGroup(plan.group!!.id).first()!!.settlePlan.firstOrNull { it.from.id == plan.from.id && it.to.id == plan.to.id })
        form.finish()
    }

    @Test fun `missing and changed same generation plans are rejected without opening a draft`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val plan = a.groups.observeGroup(a.groups.observeGroups().first().first().group.id).first()!!.settlePlan.first()
        val vm = operations(a); runCurrent()
        vm.preparePlan(plan.copy(amount = Money(plan.amount.amountMinor + 1, plan.amount.currency))); runCurrent()
        assertEquals("settlementPlan", (vm.state.value.error as LedgerError.InvalidField).field)
        assertNull(vm.state.value.openRoute)
        vm.dismiss(); vm.preparePlan(plan.copy(group = GroupRef(GroupId("missing"), "Missing"))); runCurrent()
        assertNotNull(vm.state.value.error)
    }

    @Test fun `old settlement basis invalidates a prefilled repayment`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val other = a.people.observePeople().first().first().person.id
        val draft = (a.ledger.settleUpWithPerson(other, basis(a)) as LedgerResult.Ok).value.first()
        a.backup.createBackup(DocumentRef("snapshot"), null); a.backup.restoreBackup(DocumentRef("snapshot"), null)
        val form = entry(a); form.openSettlement(draft); runCurrent()
        assertTrue(form.status.value.invalidated); form.save(); runCurrent(); assertNull(form.status.value.completedId)
        val vm = operations(a); runCurrent()
        vm.preparePersonal(PersonalSettlement(a.people.observePerson(other).first()!!.person, draft)); runCurrent()
        assertEquals(LedgerError.LedgerReplaced, vm.state.value.error); form.finish()
    }

    @Test fun `merge preview is backend data and merged aliases unmerge using their own token`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val source = person(a, "Merge source"); val target = person(a, "Merge target")
        a.people.addHandle(source, HandleDraft(HandleType.PHONE, "+37061234567", "Mobile", isDefault = true), basis(a))
        val vm = MergeViewModel(a.people, a.backup); vm.open(source.value); runCurrent(); vm.select(target.value); runCurrent()
        assertEquals((a.people.previewMerge(source, target) as LedgerResult.Ok).value, vm.state.value.preview)
        vm.merge(); vm.merge(); runCurrent(); assertEquals(target.value, vm.status.value.completedId)
        val merged = a.people.observePerson(target).first()!!; val alias = merged.aliases.first { it.person.id == source }
        val ops = operations(a); runCurrent(); ops.request(AdvancedAction.Unmerge(alias)); ops.confirm(); runCurrent()
        assertNull(ops.state.value.error); assertEquals(source, a.people.observePerson(source).first()!!.person.id)
        assertTrue(a.people.observePerson(source).first()!!.handles.any { it.value == "+37061234567" }); vm.end()
    }

    @Test fun `merge refuses a changed financial preview and stale source token`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val source = person(a, "Source"); val target = person(a, "Target")
        val vm = MergeViewModel(a.people, a.backup); vm.open(source.value); runCurrent(); vm.select(target.value); runCurrent()
        a.ledger.saveTransfer(TransferDraft(me(a), source, Money(500, eur), occurredOn = LocalDate.now()), SaveMode.Create(basis(a)))
        vm.merge(); runCurrent(); assertEquals("mergePreviewChanged", (vm.status.value.error as LedgerError.InvalidField).field)
        assertEquals(source, a.people.observePerson(source).first()!!.person.id)
        vm.select(target.value); runCurrent()
        val detail = a.people.observePerson(source).first()!!
        a.people.updatePerson(source, PersonDraft("Updated source"), detail.token)
        vm.merge(); runCurrent()
        assertNotNull(vm.status.value.error); assertNull(vm.status.value.completedId); vm.end()
    }

    @Test fun `group members add remove readd and cannot remove a debtor`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val member = person(a); val g = group(a, member)
        val ops = operations(a); runCurrent()
        ops.request(AdvancedAction.Membership(g, member, basis(a), true, "Member")); ops.confirm(); runCurrent()
        assertFalse(a.groups.observeGroup(g).first()!!.members.first { it.person.id == member }.active)
        ops.dismiss(); ops.request(AdvancedAction.Membership(g, member, basis(a), false, "Member")); ops.confirm(); runCurrent()
        assertTrue(a.groups.observeGroup(g).first()!!.members.first { it.person.id == member }.active)
        val newcomer = person(a, "Added member")
        ops.request(AdvancedAction.Membership(g, newcomer, basis(a), false, "New member")); ops.confirm(); runCurrent()
        assertTrue(a.groups.observeGroup(g).first()!!.members.any { it.person.id == newcomer && it.active })
        a.ledger.saveTransfer(TransferDraft(me(a), member, Money(500, eur), groupId = g, occurredOn = LocalDate.now()), SaveMode.Create(basis(a)))
        ops.dismiss(); ops.request(AdvancedAction.Membership(g, member, basis(a), true, "Member")); ops.confirm(); runCurrent()
        assertTrue(ops.state.value.error is LedgerError.MemberHasBalance)
        assertEquals(a.groups.observeGroup(g).first()!!.members.first { it.person.id == member }.nets,
            (ops.state.value.error as LedgerError.MemberHasBalance).nets.map { Money(it.value, it.key) })
    }

    @Test fun `archive with debt preserves totals and referenced deletion offers archive`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val p = a.people.observePeople().first().first { it.balances.isNotEmpty() }.person.id
        val before = a.ledger.observeOverview().first(); val detail = a.people.observePerson(p).first()!!
        val ops = operations(a); runCurrent()
        ops.request(AdvancedAction.Lifecycle(EntityRef(EntityType.PERSON, p.value), detail.token, LifeOperation.DELETE, "Contact")); ops.confirm(); runCurrent()
        assertTrue(ops.state.value.error is LedgerError.StillReferenced)
        ops.archiveInstead(); ops.confirm(); runCurrent()
        assertTrue(a.people.observePerson(p).first()!!.archived)
        assertEquals(before.owedToMe, a.ledger.observeOverview().first().owedToMe)
        assertEquals(before.iOwe, a.ledger.observeOverview().first().iOwe)
        val latest = a.people.observePerson(p).first()!!
        ops.request(AdvancedAction.Lifecycle(EntityRef(EntityType.PERSON, p.value), latest.token, LifeOperation.UNARCHIVE, "Contact")); ops.confirm(); runCurrent()
        assertFalse(a.people.observePerson(p).first()!!.archived)
    }

    @Test fun `person group and payment handle lifecycle keeps history and owner cascades`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val p = person(a); val g = group(a, p)
        val handle = (a.people.addHandle(p, HandleDraft(HandleType.PHONE, "+37061111111", "Mobile"), basis(a)) as LedgerResult.Ok).value
        val ops = operations(a); runCurrent()
        val detail = a.people.observePerson(p).first()!!; val h = detail.handles.first()
        ops.request(AdvancedAction.Lifecycle(EntityRef(EntityType.PAYMENT_HANDLE, handle.value), h.token, LifeOperation.DELETE, "Handle")); ops.confirm(); runCurrent()
        assertTrue(a.people.observePerson(p).first()!!.handles.isEmpty())
        assertTrue(a.people.observePerson(p).first()!!.history.any { it.entity.id == handle.value && it.action == RevisionAction.DELETED })
        for (operation in listOf(LifeOperation.ARCHIVE, LifeOperation.UNARCHIVE, LifeOperation.DELETE, LifeOperation.RESTORE)) {
            ops.dismiss(); val group = a.groups.observeGroup(g).first()!!
            ops.request(AdvancedAction.Lifecycle(EntityRef(EntityType.GROUP, g.value), group.token, operation, "Group")); ops.confirm(); runCurrent()
            assertNull(ops.state.value.error)
        }
        // Remove a reference-free separate contact; group memberships are valid references for p.
        val free = person(a, "Unreferenced")
        for (operation in listOf(LifeOperation.DELETE, LifeOperation.RESTORE)) {
            ops.dismiss(); val contact = a.people.observePerson(free).first()!!
            ops.request(AdvancedAction.Lifecycle(EntityRef(EntityType.PERSON, free.value), contact.token, operation, "Contact")); ops.confirm(); runCurrent()
            assertNull(ops.state.value.error)
        }
        assertFalse(a.people.observePerson(free).first()!!.deleted)
    }

    @Test fun `transaction soft delete removes effects and restore returns backend totals`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val item = a.ledger.observeActivity().first().first()
        val before = a.ledger.observeOverview().first(); val original = a.ledger.observeEntry(item.entryId).first()!!
        val ops = operations(a); runCurrent()
        val type = if (item.kind == EntryKind.EXPENSE) EntityType.EXPENSE else EntityType.TRANSFER
        ops.request(AdvancedAction.Lifecycle(EntityRef(type, item.entryId.value), original.token, LifeOperation.DELETE, "Transaction")); ops.confirm(); runCurrent()
        val deleted = a.ledger.observeEntry(item.entryId).first()!!
        assertTrue(deleted.item.deleted); assertTrue(a.ledger.observeActivity(ActivityFilter(includeDeleted = true)).first().any { it.entryId == item.entryId && it.deleted })
        ops.request(AdvancedAction.Lifecycle(EntityRef(type, item.entryId.value), deleted.token, LifeOperation.RESTORE, "Transaction")); ops.confirm(); runCurrent()
        val restored = a.ledger.observeEntry(item.entryId).first()!!
        assertFalse(restored.item.deleted); assertTrue(restored.history.size > original.history.size)
        val after = a.ledger.observeOverview().first(); assertEquals(before.owedToMe, after.owedToMe); assertEquals(before.iOwe, after.iOwe)
    }

    @Test fun `alias and handle revisions use their own tokens rather than parent tokens`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val target = person(a, "Target"); val source = person(a, "Alias original")
        val handle = (a.people.addHandle(source, HandleDraft(HandleType.PHONE, "+37061234567", "Original label"), basis(a)) as LedgerResult.Ok).value
        val h = a.people.observePerson(source).first()!!.handles.first()
        a.people.updateHandle(handle, HandleDraft(h.type, h.value, "Updated label"), h.token)
        a.people.merge(source, target, a.people.observePerson(source).first()!!.token)
        val parent = a.people.observePerson(target).first()!!
        val handleRevision = parent.history.first { it.entity.id == handle.value && it.version == 1 }
        val handleAction = parent.restoreAction(handleRevision)!!
        assertEquals(parent.handles.first { it.id == handle }.token, handleAction.token)
        val aliasRevision = parent.history.first { it.entity.id == source.value && it.version == 1 }
        assertEquals(parent.aliases.first { it.person.id == source }.token, parent.restoreAction(aliasRevision)!!.token)
        val ops = operations(a); runCurrent(); ops.request(handleAction); ops.confirm(); runCurrent()
        assertNull(ops.state.value.error)
        assertEquals("Original label", a.people.observePerson(target).first()!!.handles.first { it.id == handle }.label)
        assertEquals(target, a.people.observePerson(source).first()!!.person.id)
    }

    @Test fun `group RestoreConflict opens historical draft with captured current token and corrected settings`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val p = person(a); val g = group(a, p)
        val first = a.groups.observeGroup(g).first()!!
        a.groups.updateGroup(g, GroupDraft("Locked current name", eur, FxMode.CONVERT), first.token)
        a.ledger.saveTransfer(TransferDraft(me(a), p, Money(100, eur), groupId = g, occurredOn = LocalDate.now()), SaveMode.Create(basis(a)))
        val current = a.groups.observeGroup(g).first()!!
        val ops = operations(a); runCurrent(); ops.request(current.restoreAction(current.history.first { it.entity.type == EntityType.GROUP && it.version == 1 })!!)
        ops.confirm(); runCurrent(); assertTrue(ops.state.value.error is LedgerError.RestoreConflict)
        ops.openAsDraft(); runCurrent()
        val recovery = (prepared(ops) as PreparedForm.Recovery).draft
        assertEquals(current.token, recovery.token); assertTrue(recovery.draft is VersionDraft.OfGroup)
        val form = metadata(a); form.openRecovery(recovery); runCurrent()
        assertTrue(form.state.value.fromHistory); assertEquals(FxMode.KEEP, form.state.value.fields.fxMode)
        form.useCurrentCurrencySettings(); form.save(); runCurrent()
        assertEquals(g.value, form.status.value.completedId)
        assertEquals(FxMode.CONVERT, a.groups.observeGroup(g).first()!!.fxMode)
        assertEquals(first.group.name, a.groups.observeGroup(g).first()!!.group.name); form.end()
    }

    @Test fun `entry RestoreConflict draft can correct removed participants without duplicating entry`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val self = me(a); val removed = person(a); val g = group(a, removed)
        val old = ExpenseDraft(self, "Historical expense", total = Money(3000, eur), groupId = g, splitType = SplitType.EXACT,
            participants = listOf(SplitInput(self, 3000), SplitInput(removed, 0)), occurredOn = LocalDate.now())
        val id = (a.ledger.saveExpense(old, SaveMode.Create(basis(a))) as LedgerResult.Ok).value
        val before = a.ledger.observeEntry(id).first()!!
        a.ledger.saveExpense(old.copy(participants = listOf(SplitInput(self, 3000))), SaveMode.Update(id, before.token))
        a.groups.removeMember(g, removed, basis(a))
        val current = a.ledger.observeEntry(id).first()!!
        val ops = operations(a); runCurrent(); ops.request(current.restoreAction(current.history.first { it.version == 1 })!!); ops.confirm(); runCurrent()
        assertTrue(ops.state.value.error is LedgerError.RestoreConflict)
        ops.openAsDraft(); runCurrent(); val recovery = (prepared(ops) as PreparedForm.Recovery).draft
        val form = entry(a); form.openRecovery(recovery); runCurrent()
        assertTrue(removed.value in form.state.value.fields.participants)
        form.change { it.copy(participants = listOf(self.value), inputs = mapOf(self.value to "30")) }; form.save(); runCurrent()
        assertEquals(id.value, form.status.value.completedId); assertEquals(listOf(self), a.ledger.observeEntry(id).first()!!.shares.map { it.person.id }); form.finish()
    }

    @Test fun `stale confirmations never retry and require a new action from latest data`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val p = person(a); val before = a.people.observePerson(p).first()!!
        val ops = operations(a); runCurrent()
        ops.request(AdvancedAction.Lifecycle(EntityRef(EntityType.PERSON, p.value), before.token, LifeOperation.ARCHIVE, "Person"))
        a.people.updatePerson(p, PersonDraft("Changed elsewhere"), before.token)
        ops.confirm(); runCurrent(); assertTrue(ops.state.value.error is LedgerError.StaleVersion)
        ops.confirm(); runCurrent(); assertFalse(a.people.observePerson(p).first()!!.archived)
        ops.dismiss(); val latest = a.people.observePerson(p).first()!!
        ops.request(AdvancedAction.Lifecycle(EntityRef(EntityType.PERSON, p.value), latest.token, LifeOperation.ARCHIVE, "Person")); ops.confirm(); runCurrent()
        assertTrue(a.people.observePerson(p).first()!!.archived)
    }

    @Test fun `ledger replacement drops confirmations and prepared drafts`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val p = person(a); val person = a.people.observePerson(p).first()!!
        val ops = operations(a); runCurrent()
        ops.request(AdvancedAction.Lifecycle(EntityRef(EntityType.PERSON, p.value), person.token, LifeOperation.DELETE, "Contact"))
        a.backup.createBackup(DocumentRef("snapshot"), null); a.backup.restoreBackup(DocumentRef("snapshot"), null); runCurrent()
        assertNull(ops.state.value.pending); assertEquals(LedgerError.LedgerReplaced, ops.state.value.error)
        ops.confirm(); runCurrent(); assertFalse(a.people.observePerson(p).first()!!.deleted)
    }

    @Test fun `duplicate confirmation taps invoke a single backend mutation`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val p = person(a); var calls = 0; val gate = CompletableDeferred<Unit>()
        val api = object : PeopleApi by a.people {
            override suspend fun setPersonArchived(id: PersonId, archived: Boolean, token: EditToken): LedgerResult<Unit> {
                calls++; gate.await(); return a.people.setPersonArchived(id, archived, token)
            }
        }
        val ops = operations(a, api); runCurrent(); val detail = a.people.observePerson(p).first()!!
        ops.request(AdvancedAction.Lifecycle(EntityRef(EntityType.PERSON, p.value), detail.token, LifeOperation.ARCHIVE, "Contact"))
        ops.confirm(); ops.confirm(); runCurrent(); assertEquals(1, calls); assertTrue(ops.state.value.busy)
        ops.dismiss(); assertNotNull(ops.state.value.pending)
        gate.complete(Unit); runCurrent(); assertTrue(a.people.observePerson(p).first()!!.archived)
    }

    @Test fun `financial errors and formatted historical values remain masked`() = runTest(dispatcher) {
        val amount = Money(987654321, eur)
        assertEquals("••••", io.github.codex07.iou.ui.format.MoneyFormatter.format(amount, hidden = true))
        assertFalse(LedgerError.ExactRemaining(987654321).userMessage(true, eur).contains("987"))
        assertFalse(LedgerError.MemberHasBalance(GroupId("g"), PersonId("p"), mapOf(eur to 987654321)).userMessage(true).contains("987"))
        val conflict = LedgerError.RestoreConflict(listOf(RuleViolation(0, LedgerError.ExactRemaining(987654321))))
        conflict.violations.forEach { assertFalse(it.error.userMessage(true, eur).contains("987")) }
    }

    @Test fun `empty ledger has no fabricated personal or group settlement suggestions`() = runTest(dispatcher) {
        val a = FakeApis.create(FakeApis.store())
        a.setup.completeSetup("Me", eur)
        SettlementsViewModel(a.people, a.groups, a.ledger, a.backup).state.test {
            awaitItem(); val page = (awaitItem() as BrowseState.Ready).value
            assertTrue(page.personal.isEmpty()); assertTrue(page.groups.isEmpty()); cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `prepared routes are cleared when the ledger generation changes`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val other = a.people.observePeople().first().first().person
        val draft = (a.ledger.settleUpWithPerson(other.id, basis(a)) as LedgerResult.Ok).value.first()
        val ops = operations(a); runCurrent(); ops.preparePersonal(PersonalSettlement(other, draft)); runCurrent()
        val key = ops.state.value.openRoute!!.substringAfter(':'); assertNotNull(ops.form(key))
        a.backup.createBackup(DocumentRef("replace"), null); a.backup.restoreBackup(DocumentRef("replace"), null); runCurrent()
        assertNull(ops.form(key)); assertNull(ops.state.value.openRoute)
    }

    @Test fun `membership history does not substitute the group token and deleted handles have no invented token`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val p = person(a); val g = group(a, p)
        val group = a.groups.observeGroup(g).first()!!
        val membership = group.history.first { it.entity.type == EntityType.GROUP_MEMBER }
        assertNull(group.restoreAction(membership))
        val h = (a.people.addHandle(p, HandleDraft(HandleType.PHONE, "+37061234567", "Mobile"), basis(a)) as LedgerResult.Ok).value
        val before = a.people.observePerson(p).first()!!
        a.people.deleteHandle(h, before.handles.first().token)
        val after = a.people.observePerson(p).first()!!
        assertNull(after.restoreAction(after.history.first { it.entity.id == h.value }))
    }

    @Test fun `historical FX draft uses its own rate instead of the current entry snapshot`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val p = person(a); val self = me(a)
        val g = (a.groups.createGroup(GroupDraft("FX history", eur, FxMode.CONVERT), listOf(p), basis(a)) as LedgerResult.Ok).value
        val sek = CurrencyCode("SEK"); val date = LocalDate.now()
        val rate = (a.fx.userRate(date, sek, eur, "0.01") as LedgerResult.Ok).value
        val old = ExpenseDraft(self, "Historical FX", total = Money(10000, sek), groupId = g,
            participants = listOf(SplitInput(self), SplitInput(p)), occurredOn = date, fx = FxChoice.Rate(rate))
        val id = (a.ledger.saveExpense(old, SaveMode.Create(basis(a))) as LedgerResult.Ok).value
        val first = a.ledger.observeEntry(id).first()!!
        val freshRate = (a.fx.userRate(date, sek, eur, "0.02") as LedgerResult.Ok).value
        a.ledger.saveExpense(old.copy(total = Money(20000, sek), fx = FxChoice.Rate(freshRate)), SaveMode.Update(id, first.token))
        val current = a.ledger.observeEntry(id).first()!!
        val historical = (a.ledger.draftFromVersion(EntityRef(EntityType.EXPENSE, id.value), 1) as LedgerResult.Ok).value
        val vm = entry(a); vm.openRecovery(RecoveryDraft(EntityRef(EntityType.EXPENSE, id.value), current.token, historical)); runCurrent()
        assertEquals("0.01", vm.state.value.storedFx!!.rate)
        vm.save(); runCurrent(); assertEquals(id.value, vm.status.value.completedId)
        assertEquals(Money(100, eur), a.ledger.observeEntry(id).first()!!.item.effect); vm.finish()
    }

    @Test fun `opening recovery cannot silently replace the captured token with a newer one`() = runTest(dispatcher) {
        val a = FakeApis.seeded(); val p = person(a); val detail = a.people.observePerson(p).first()!!
        val oldDraft = (a.ledger.draftFromVersion(EntityRef(EntityType.PERSON, p.value), 1) as LedgerResult.Ok).value
        a.people.updatePerson(p, PersonDraft("Newer current value"), detail.token)
        val vm = metadata(a); vm.openRecovery(RecoveryDraft(EntityRef(EntityType.PERSON, p.value), detail.token, oldDraft)); runCurrent()
        vm.save(); runCurrent(); assertTrue(vm.status.value.error is LedgerError.StaleVersion)
        assertEquals("Newer current value", a.people.observePerson(p).first()!!.person.displayName); vm.end()
    }
}
