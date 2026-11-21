package io.github.codex07.iou.ui.viewmodel

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.codex07.iou.api.*
import io.github.codex07.iou.ledger.model.RecurringId
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class ScheduleList { ACTIVE, HELD, DELETED }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScheduleListViewModel @Inject constructor(private val api: RecurringApi) : BrowseViewModel<List<ScheduleSummary>>() {
    private val list = MutableStateFlow<ScheduleList?>(null)
    val state = observe {
        list.filterNotNull().flatMapLatest {
            when (it) {
                ScheduleList.ACTIVE -> api.observeSchedules(onHold = false)
                ScheduleList.HELD -> api.observeSchedules(onHold = true)
                ScheduleList.DELETED -> api.observeSchedules(includeDeleted = true).map { rows -> rows.filter { row -> row.deleted } }
            }
        }
    }

    fun open(value: ScheduleList) { list.value = value }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScheduleDetailViewModel @Inject constructor(api: RecurringApi) : BrowseViewModel<ScheduleDetail>() {
    private val id = MutableStateFlow<RecurringId?>(null)
    val state = observe { id.filterNotNull().flatMapLatest(api::observeSchedule) }
    fun open(value: RecurringId) { id.value = value }
}

data class SkipReview(val schedule: RecurringId, val dates: List<LocalDate>, val token: EditToken)
data class DueAction(
    val busy: Boolean = false, val review: SkipReview? = null, val error: LedgerError? = null, val message: String? = null,
)

@HiltViewModel
class DueViewModel @Inject constructor(private val api: RecurringApi) : BrowseViewModel<List<DueOccurrence>>() {
    val state = observe(api::observeDue)
    private val mutableAction = MutableStateFlow(DueAction())
    val action = mutableAction.asStateFlow()

    fun prepareSkip(occurrence: DueOccurrence) {
        if (action.value.review != null) return
        perform {
            val detail = api.observeSchedule(occurrence.schedule).first()
            when {
                detail == null || detail.summary.deleted -> DueAction(error = LedgerError.NotFound(occurrence.schedule.value))
                detail.due.firstOrNull() != occurrence.date -> DueAction(error = LedgerError.OccurrenceNotDue(detail.summary.next))
                else -> DueAction(review = SkipReview(occurrence.schedule, detail.due, detail.token))
            }
        }
    }

    fun confirmSkip(through: LocalDate) {
        val review = action.value.review ?: return
        if (through !in review.dates) return
        perform {
            when (val result = api.skipOccurrences(review.schedule, through, review.token)) {
                is LedgerResult.Ok -> DueAction(message = "Occurrences skipped through $through.")
                is LedgerResult.Err -> DueAction(error = result.error)
            }
        }
    }

    fun dismiss() { if (!action.value.busy) mutableAction.value = DueAction() }

    private fun perform(block: suspend () -> DueAction) {
        if (action.value.busy) return
        mutableAction.value = action.value.copy(busy = true)
        viewModelScope.launch {
            mutableAction.value = try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                DueAction(message = "Unable to confirm the action. Review the current due list before trying again.")
            }
        }
    }
}
