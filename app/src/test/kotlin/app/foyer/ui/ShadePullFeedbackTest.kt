package app.foyer.ui

import org.junit.Assert.*
import org.junit.Test

class ShadePullFeedbackTest {
    @Test fun reversalKeepsRoundShapeUntilFullyHidden() {
        val feedback = ShadePullFeedback()
        feedback.begin()
        assertFalse(feedback.update(.9f, .1f))
        assertTrue(feedback.update(1f, .1f))
        assertFalse(feedback.update(1.2f, .1f))
        feedback.update(1.05f, .1f)
        feedback.update(0f, .1f)
        assertTrue(feedback.armed)
        assertTrue(feedback.cancelled)
        assertFalse(feedback.update(1.5f, .1f))
        feedback.begin()
        assertFalse(feedback.armed)
        assertFalse(feedback.cancelled)
        assertTrue(feedback.update(1f, .1f))
    }

    @Test fun cancelledShortPullNeverArms() {
        val feedback = ShadePullFeedback()
        feedback.begin()
        feedback.update(.5f, .1f)
        feedback.cancel()
        assertFalse(feedback.update(2f, .1f))
        assertFalse(feedback.armed)
    }
    @Test fun jitterAndHoldingDoNotCancelOrRepeatHaptic() {
        val feedback = ShadePullFeedback()
        feedback.begin()
        assertFalse(feedback.update(.5f, .1f))
        assertFalse(feedback.update(.48f, .1f))
        assertFalse(feedback.cancelled)
        assertTrue(feedback.update(1.2f, .1f))
        repeat(100) {
            assertFalse(feedback.update(1.18f, .1f))
            assertFalse(feedback.update(1.2f, .1f))
            assertFalse(feedback.cancelled)
            assertTrue(feedback.armed)
        }
    }

    @Test fun slowAccumulatedReversalCancelsFromPeak() {
        val feedback = ShadePullFeedback()
        feedback.begin()
        feedback.update(2f, .1f)
        feedback.update(1.96f, .1f)
        feedback.update(1.92f, .1f)
        assertFalse(feedback.cancelled)
        feedback.update(1.88f, .1f)
        assertTrue(feedback.cancelled)
        assertTrue(feedback.armed)
        assertFalse(feedback.update(2.5f, .1f))
        assertTrue(feedback.cancelled)
    }
    @Test fun hiddenCircleCanRestartAsSquareInSameTouch() {
        val feedback = ShadePullFeedback()
        feedback.begin()
        assertTrue(feedback.update(1.2f, .1f))
        feedback.update(.6f, .1f)
        assertTrue(feedback.armed)
        feedback.finishDismissal()
        assertFalse(feedback.update(.65f, .1f))
        assertTrue(feedback.cancelled)
        assertFalse(feedback.update(.8f, .1f))
        assertFalse(feedback.cancelled)
        assertFalse(feedback.armed)
        assertEquals(.2f, feedback.progress, .001f)
        assertTrue(feedback.update(1.7f, .1f))
        assertFalse(feedback.update(1.8f, .1f))
    }

    @Test fun downwardMovementDuringFadeWaitsForHiddenState() {
        val feedback = ShadePullFeedback()
        feedback.begin()
        feedback.update(1.2f, .1f)
        feedback.update(.5f, .1f)
        feedback.update(.8f, .1f)
        assertTrue(feedback.cancelled)
        assertTrue(feedback.armed)
        feedback.finishDismissal()
        assertFalse(feedback.cancelled)
        assertFalse(feedback.armed)
        assertEquals(.3f, feedback.progress, .001f)
    }

    @Test fun releaseDuringFadeCannotRestart() {
        val feedback = ShadePullFeedback()
        feedback.begin()
        feedback.update(1.2f, .1f)
        feedback.update(.5f, .1f)
        feedback.update(.8f, .1f)
        feedback.cancel()
        feedback.finishDismissal()
        assertTrue(feedback.cancelled)
        assertFalse(feedback.update(2f, .1f))
    }
    @Test fun fastRepullStartsNewAnimationAndEmitsHapticBeforeRelease() {
        var haptics = 0
        val feedback = ShadePullFeedback { haptics++ }
        feedback.begin()
        feedback.update(1.2f, .1f)
        val firstCycle = feedback.cycle
        feedback.update(.5f, .1f)
        feedback.update(1.6f, .1f)
        assertEquals(1, haptics)
        feedback.finishDismissal()
        assertTrue(feedback.cycle > firstCycle)
        assertTrue(feedback.armed)
        assertEquals(2, haptics)
        feedback.cancel()
        assertEquals(2, haptics)
    }
    @Test fun reversalRetractsButOrdinaryReleaseOnlyFades() {
        val feedback = ShadePullFeedback()
        feedback.begin()
        feedback.update(1.2f, .1f)
        feedback.cancel()
        assertFalse(feedback.retracting)
        feedback.begin()
        feedback.update(1.2f, .1f)
        feedback.update(.7f, .1f)
        assertTrue(feedback.retracting)
        assertTrue(feedback.armed)
        feedback.finishDismissal()
        feedback.update(.9f, .1f)
        assertFalse(feedback.retracting)
        assertFalse(feedback.armed)
    }
}
