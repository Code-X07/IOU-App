package io.github.codex07.iou.ledger.views

import io.github.codex07.iou.api.ActivityFilter
import io.github.codex07.iou.api.ActivityItem
import io.github.codex07.iou.api.EntityRef
import io.github.codex07.iou.api.RevisionView
import io.github.codex07.iou.ledger.model.Entry

fun LedgerViews.activity(filter: ActivityFilter): List<ActivityItem> = emptyList()

fun LedgerViews.activityItem(entry: Entry): ActivityItem = TODO("Task 2")

fun LedgerViews.history(refs: Collection<EntityRef>): List<RevisionView> = emptyList()
