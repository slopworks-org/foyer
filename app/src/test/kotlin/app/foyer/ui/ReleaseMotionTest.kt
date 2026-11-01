package app.foyer.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class ReleaseMotionTest {
    @Test fun releaseDirectionAndDerivativeSurviveValidationAndRetargeting() {
        for (velocity in listOf(Offset.Zero, Offset(100f, 50f), Offset(-2000f, 1600f))) {
            val release = ReleaseMotion(Offset(300f, 400f), velocity)
            val dt = .00001f
            val pending = release.step(null, dt)
            assertEquals(velocity.x, ((pending.position - release.position) / dt).x, 3f)
            assertEquals(velocity.y, ((pending.position - release.position) / dt).y, 3f)
            val validated = pending.step(Offset(100f, 100f), dt)
            assertEquals(pending.velocity.x, ((validated.position - pending.position) / dt).x, 5f)
            assertEquals(pending.velocity.y, ((validated.position - pending.position) / dt).y, 5f)
            var motion = release
            repeat(180) { motion = motion.step(if (it < 10) null else Offset(100f, 100f), 1f / 60) }
            assertTrue((motion.position - Offset(100f, 100f)).getDistance() < .01f)
            assertTrue(motion.velocity.getDistance() < .01f)
        }
    }

    @Test fun fastFlingIsBoundedAndCoastingCannotRunAway() {
        val bounded = boundedReleaseVelocity(Offset(-30000f, 40000f), 3000f)
        assertEquals(3000f, bounded.getDistance(), .01f)
        assertEquals(-.75f, bounded.x / bounded.y, .001f)
        val coast = ReleaseMotion(Offset.Zero, bounded).step(null, 10f)
        assertTrue(coast.position.getDistance() <= 750.1f)
        assertTrue(coast.velocity.getDistance() < .01f)
        assertEquals(Offset.Zero, boundedReleaseVelocity(Offset(Float.NaN, 1f), 3000f))
    }

    @Test fun fastMotionTravelsBeforeCaptureAndRetargetingRemainsContinuous() {
        val icon = 100f
        val start = Offset(500f, 500f)
        val v = Offset(1000f, 0f)
        var motion = ReleaseMotion(start, v, releaseCaptureSeconds(v.getDistance(), icon))
        val target = start + Offset(0f, 180f)
        repeat(6) { motion = motion.step(target, .016f) }
        assertTrue("first 96ms must show at least half an icon of lateral travel", motion.position.x - start.x > icon * .5f)
        assertTrue("no immediate velocity collapse", motion.velocity.x > 200f)
        val before = motion
        motion = motion.step(start - Offset(200f, 100f), .00001f)
        assertTrue((motion.position - before.position).getDistance() < .02f)
        assertTrue((motion.velocity - before.velocity).getDistance() < 1f)
        repeat(100) { motion = motion.step(target, .016f) }
        assertTrue((motion.position - target).getDistance() < .1f)
    }

    @Test fun speedControlsTravelAndCaptureTimeAndEdgesStayBounded() {
        val start = Offset(500f, 500f)
        fun travel(speed: Float): Float {
            var motion = ReleaseMotion(start, Offset(speed, 0f), releaseCaptureSeconds(speed, 100f))
            repeat(6) { motion = motion.step(start, .016f) }
            return motion.position.x - start.x
        }
        assertTrue(travel(1000f) > travel(100f) * 5f)
        assertTrue(releaseCaptureSeconds(1000f, 100f) > releaseCaptureSeconds(100f, 100f))
        val bounds = androidx.compose.ui.geometry.Rect(50f, 50f, 950f, 950f)
        var motion = ReleaseMotion(Offset(940f, 60f), Offset(1800f, -1800f))
        repeat(90) {
            motion = motion.step(start, .016f, bounds)
            assertTrue(motion.position.x in 50f..950f && motion.position.y in 50f..950f)
        }
        assertTrue((motion.position - start).getDistance() < 1f)
    }

    @Test fun recentPointerFitHandlesFastUpAndHold() {
        val tracker = ReleaseVelocity()
        for (i in 0..6) tracker.add(i * 16L, Offset(i * 16f, -i * 8f))
        assertEquals(1000f, tracker.velocity().x, .1f)
        tracker.add(112, Offset(112f, -56f)) // movement in final up sample
        assertEquals(1000f, tracker.velocity().x, .1f)
        tracker.add(128, Offset(112f, -56f))
        assertTrue(tracker.velocity().x in 700f..1000f)
        tracker.add(350, Offset(112f, -56f))
        assertEquals(Offset.Zero, tracker.velocity())
    }
    @Test fun appearanceWaitsForDirectionalTravel() {
        val capture = releaseCaptureSeconds(1000f, 100f)
        assertEquals(0f, releaseAppearanceFraction(.096f, capture), 0f)
        assertEquals(0f, releaseAppearanceFraction(capture, capture), 0f)
        assertEquals(1f, releaseAppearanceFraction(capture + .32f, capture), .0001f)
    }
    @Test fun sparseFinalMoveIsNotMistakenForAHoldOnUp() {
        val tracker = ReleaseVelocity()
        tracker.add(0, Offset.Zero)
        tracker.add(80, Offset(40f, 0f))
        tracker.add(82, Offset(40f, 0f))
        assertTrue(tracker.velocity().x > 450f)
    }
}
