package com.subtrack.ui.editor

import com.subtrack.domain.cycle.CycleEngine
import com.subtrack.domain.model.BillingCycleType
import com.subtrack.domain.model.Limits
import java.time.LocalDate

data class EditorCycleDecision(val date: LocalDate, val error: String? = null)

/** Used by both cycle selection and explicit recalculation. Keep the typed date on invalid input. */
fun recalculateEditorDate(anchor: LocalDate, cycle: BillingCycleType, days: Int?, today: LocalDate,
                          previous: LocalDate): EditorCycleDecision {
    if (cycle == BillingCycleType.CUSTOM_DAYS && !Limits.isValidCustomCycleDays(days)) {
        return EditorCycleDecision(previous, "自定义天数需在 1~3650 之间")
    }
    return EditorCycleDecision(CycleEngine.occurrenceAtOrAfter(anchor, cycle, days, today) ?: previous)
}
