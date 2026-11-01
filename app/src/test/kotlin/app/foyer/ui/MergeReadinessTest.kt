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
    @Test fun switchDisarmsBeforeFreshDwellAndPreviewCompletion() {
        val events = mutableListOf<Pair<Long, Boolean>>()
        var now = 0L
        val readiness = MergeReadiness { events += now to it }
        val icons = mapOf(1L to Rect(0f, 0f, 100f, 100f), 2L to Rect(110f, 0f, 210f, 100f))
        var intent = MergeIntent().update(Offset(50f, 50f), icons, now)
        fun refresh(time: Long, point: Offset, complete: Boolean = true, scrolling: Boolean = false) {
            now = time
            val next = intent.update(point, icons, now, scrolling)
            if (next.candidate != intent.candidate) readiness.update(null)
            intent = next
            readiness.update(next.candidate?.takeIf { next.armed(now) && complete })
        }
        refresh(320, Offset(50f, 50f), complete = false) // dwell alone is insufficient
        refresh(350, Offset(50f, 50f))
        refresh(360, Offset(160f, 50f))
        refresh(679, Offset(160f, 50f)) // stale old preview cannot arm new candidate
        refresh(680, Offset(160f, 50f))
        refresh(700, Offset(160f, 50f), scrolling = true)
        refresh(720, Offset(160f, 50f))
        assertEquals(listOf(350L to true, 360L to false, 680L to true, 700L to false), events)
    }
}
