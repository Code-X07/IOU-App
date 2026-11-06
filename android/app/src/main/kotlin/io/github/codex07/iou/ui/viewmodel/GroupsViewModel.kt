package io.github.codex07.iou.ui.viewmodel

import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.GroupId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

@HiltViewModel
class GroupsViewModel @Inject constructor(api: GroupsApi) : BrowseViewModel<List<GroupSummary>>() {
    val state = observe { api.observeGroups(includeArchived = true, includeDeleted = true) }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GroupDetailViewModel @Inject constructor(api: GroupsApi) : BrowseViewModel<GroupDetail>() {
    private val id = MutableStateFlow<GroupId?>(null)
    val state = observe { id.filterNotNull().flatMapLatest(api::observeGroup) }
    fun open(value: String) { id.value = GroupId(value) }
}
