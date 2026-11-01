package app.foyer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class MergeReadinessTest {
    @Test fun actualReadinessEdgesAndSilentCommit() {
        val events = mutableListOf<Boolean>()
        val readiness = MergeReadiness(events::add)
        readiness.update(null) // early hover exit
        readiness.update(1); repeat(20) { readiness.update(1) }
        readiness.update(null) // scroll, cancel, or geometry invalidation
        readiness.update(null)
        readiness.update(2)
        readiness.consume() // successful release must not feel like an exit
        readiness.update(null)
        assertEquals(listOf(true, false, true), events)
    }
    @Test fun switchDisarmsThenArmsWithoutWaitingForPreview() {
        val events = mutableListOf<Boolean>()
        val readiness = MergeReadiness(events::add)
        val icons = mapOf(1L to Rect(0f, 0f, 100f, 100f), 2L to Rect(110f, 0f, 210f, 100f))
        var intent = MergeIntent()
        fun refresh(point: Offset, scrolling: Boolean = false) {
            intent = intent.update(point, icons, scrolling)
            readiness.update(intent.candidate)
        }
        refresh(Offset(50f, 50f))
        repeat(20) { refresh(Offset(50f, 50f)) }
        refresh(Offset(160f, 50f))
        refresh(Offset(160f, 50f), scrolling = true)
        refresh(Offset(160f, 50f))
        readiness.consume()
        readiness.update(null)
        assertEquals(listOf(true, false, true, false, true), events)
    }
}
