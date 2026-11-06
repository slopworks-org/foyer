package app.foyer.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class StagedCollapseTest {
    private val destination = Rect(20f, 80f, 120f, 180f)
    @Test fun openClosedAndSparseLargeVisitSingletonBeforeGrowth() {
        val starts = listOf(Rect(300f, 400f, 400f, 500f),
            folderMemberStart(destination, false, 2, 1, 100f, 5f),
            folderMemberStart(Rect(20f, 80f, 320f, 380f), true, 9, 8, 100f, 16f))
        for (start in starts) {
            val singleton = advanceCollapseFrame(start, destination, 0f, COLLAPSE_CLOSE_FRACTION, 100f)
            assertEquals(destination.center, singleton.center)
            assertEquals(38f, singleton.width, .001f)
            assertEquals(0f, collapseGrow(COLLAPSE_CLOSE_FRACTION), 0f)
            val growing = advanceCollapseFrame(singleton, destination, COLLAPSE_CLOSE_FRACTION, .8f, 100f)
            assertTrue(growing.width > singleton.width && growing.width < destination.width)
            assertEquals(growing, advanceCollapseFrame(start, destination, 0f, .8f, 100f))
            assertEquals(destination, advanceCollapseFrame(growing, destination, .8f, 1f, 100f))
            // Disabled motion can skip straight to completion.
            assertEquals(destination, advanceCollapseFrame(start, destination, 0f, 1f, 100f))
        }
    }
    @Test fun largeSurfaceClosesToSquareAtFormerTopLeftBeforeDisappearing() {
        val surface = Rect(20f, 80f, 320f, 380f)
        assertEquals(destination, advanceCollapseFrame(surface, destination, 0f, COLLAPSE_CLOSE_FRACTION, 100f, true))
        assertEquals(1f, collapseClose(COLLAPSE_CLOSE_FRACTION), 0f)
    }
    @Test fun liveDestinationDoesNotSnapAtZeroElapsedTime() {
        val start = Rect(300f, 400f, 400f, 500f)
        val moving = Rect(20f, 40f, 120f, 140f)
        val current = advanceCollapseFrame(start, destination, 0f, .3f, 100f)
        assertEquals(current, advanceCollapseFrame(current, moving, .3f, .3f, 100f))
        val singleton = advanceCollapseFrame(current, moving, .3f, COLLAPSE_CLOSE_FRACTION, 100f)
        assertEquals(moving.center, singleton.center)
    }
}
