package app.foyer.ui

import org.junit.Assert.*
import org.junit.Test

class FeedPullGestureTest {
    @Test fun reversalUnwindsBeforeScrollingAndKeepsTheOriginalEdgeUntilLift() {
        for (edge in PullEdge.entries) {
            var haptics = 0
            val pull = FeedPullGesture(100f, .1f) { haptics++ }
            val sign = edge.sign.toFloat()
            pull.begin(true)
            assertEquals(120f * sign, pull.overscroll(120f * sign, true), 0f)
            assertEquals(0f, pull.unwind(5f * sign), 0f)
            assertEquals(-20f * sign, pull.unwind(-20f * sign), 0f)
            assertEquals(-100f * sign, pull.unwind(-150f * sign), 0f)
            assertEquals(0f, pull.overscroll(-50f * sign, true), 0f)
            assertEquals(0f, pull.distance, 0f)
            assertEquals(1, haptics)
            (if (edge == PullEdge.Top) pull.shade else pull.search).finishDismissal()
            pull.overscroll(100f * sign, true)
            assertEquals(PullRelease(edge, true), pull.end())
            assertEquals(2, haptics)
            assertTrue(pull.shade.cancelled && pull.search.cancelled)
            assertEquals(0f, pull.overscroll(-120f * sign, true), 0f)
            pull.begin(true)
            pull.overscroll(-120f * sign, true)
            assertNotEquals(edge, pull.end()!!.edge)
        }
    }

    @Test fun releaseRequiresArmingAndRejectsVelocityBackIntoTheFeed() {
        for (edge in PullEdge.entries) {
            val sign = edge.sign.toFloat()
            val pull = FeedPullGesture(100f, .1f) {}
            fun release(distance: Float, reversal: Float = 0f): PullRelease {
                pull.begin(true)
                pull.overscroll(sign * distance, true)
                pull.unwind(-sign * reversal)
                return pull.end()!!
            }
            assertFalse(release(99f).accepts(2000f * sign))
            assertFalse(release(110f, 20f).accepts(0f))
            val armed = release(100f)
            assertTrue(armed.accepts(0f))
            assertTrue(armed.accepts(-499f * sign))
            assertFalse(armed.accepts(-500f * sign))
            pull.begin(true)
            pull.overscroll(120f * sign, true)
            assertFalse(pull.end(cancelled = true)!!.accepts(0f))
            assertTrue((if (edge == PullEdge.Top) pull.shade else pull.search).retracting)
        }
    }

    @Test fun searchMustBeEnabledAtTouchDownAndAtTheEdge() {
        val pull = FeedPullGesture(100f, .1f) {}
        pull.begin(false)
        assertEquals(0f, pull.overscroll(-120f, true), 0f)
        assertNull(pull.end())
        pull.begin(true)
        assertEquals(0f, pull.overscroll(-120f, false), 0f)
        assertEquals(120f, pull.overscroll(120f, false), 0f)
        assertEquals(PullRelease(PullEdge.Top, true), pull.end())
    }
}
