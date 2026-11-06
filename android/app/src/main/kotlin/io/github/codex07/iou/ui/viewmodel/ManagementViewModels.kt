package io.github.codex07.iou.ui.viewmodel

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.*
import io.github.codex07.iou.ui.forms.SafeFormViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class MergeState(val source: PersonDetail? = null, val candidates: List<PersonSummary> = emptyList(),
                      val target: PersonId? = null, val preview: MergePreview? = null, val previewBusy: Boolean = false)
@HiltViewModel
class MergeViewModel @Inject constructor(private val people: PeopleApi, backup: BackupApi) : SafeFormViewModel(backup) {
    private val mutableState = MutableStateFlow(MergeState())
    val state = mutableState.asStateFlow()
    private var previewJob: Job? = null
    private var revision = 0
    fun open(id: String) = initialize {
        val source = people.observePerson(PersonId(id)).first()
        if (source == null || source.deleted || source.person.isMe) { fail(LedgerError.NotFound(id)); return@initialize }
        requireGeneration(source.token)
        mutableState.value = MergeState(source, people.observePeople(includeArchived = true).first().filter { it.person.id != source.person.id })
    }
    fun select(id: String) {
        if (!editable) return
        previewJob?.cancel(); val version = ++revision
        mutableState.update { it.copy(target = PersonId(id), preview = null, previewBusy = true) }
        changed(false)
        previewJob = viewModelScope.launch {
            try {
                val source = state.value.source ?: return@launch
                // Capture the source token with the preview, not at the later confirmation tap.
                val latest = people.observePerson(source.person.id).first() ?: run { fail(LedgerError.NotFound(source.person.id.value)); return@launch }
                if (latest.person.id != source.person.id) { fail(LedgerError.InvalidMerge(MergeProblem.NOT_CANONICAL)); return@launch }
                when (val result = people.previewMerge(latest.person.id, PersonId(id))) {
                    is LedgerResult.Err -> if (version == revision) fail(result.error)
                    is LedgerResult.Ok -> if (version == revision && !status.value.invalidated) mutableState.update { it.copy(source = latest, preview = result.value) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (version == revision) fail(LedgerError.InvalidField("mergePreview")) }
            finally { if (version == revision) mutableState.update { it.copy(previewBusy = false) } }
        }
    }
    fun merge() {
        val preview = state.value.preview ?: return
        val token = state.value.source?.token ?: return
        submit {
            when (val latest = people.previewMerge(preview.source.id, preview.target.id)) {
                is LedgerResult.Err -> latest
                is LedgerResult.Ok -> if (latest.value != preview) LedgerResult.Err(LedgerError.InvalidField("mergePreviewChanged"))
                    else people.merge(preview.source.id, preview.target.id, token).map { preview.target.id.value }
            }
        }
    }
    override fun cancelPending() { revision++; previewJob?.cancel(); mutableState.update { it.copy(preview = null, previewBusy = false) } }
}

data class MembersPage(val group: GroupDetail, val candidates: List<PersonSummary>)
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MembersViewModel @Inject constructor(groups: GroupsApi, people: PeopleApi) : BrowseViewModel<MembersPage>() {
    private val id = MutableStateFlow<GroupId?>(null)
    val state = observe { id.filterNotNull().flatMapLatest { group ->
        combine(groups.observeGroup(group), people.observePeople(includeArchived = true)) { detail, contacts ->
            detail?.let { MembersPage(it, contacts.filter { p -> it.members.none { m -> m.active && m.person.id == p.person.id } }) }
        }
    } }
    fun open(value: String) { id.value = GroupId(value) }
}
