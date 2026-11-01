package app.foyer.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class ReleaseMotionTest {
    @Test fun captureRampIsShortEvenForFastFlings() {
        for (speed in listOf(0f, 100f, 500f, 1000f, 1800f)) {
            assertTrue(releaseCaptureSeconds(speed, 100f) in .04f.. .10f)
            assertTrue(releaseSettlingFrequency(speed, 100f) >= 24f)
        }
    }

    @Test fun resumedMovementDiscardsStaleDirection() {
        for (direction in listOf(-1f, 1f)) {
            val tracker = ReleaseVelocity()
            tracker.add(0, Offset.Zero)
            tracker.add(1000, Offset(200f, 0f))
            tracker.add(1016, Offset(200f + direction * 16f, 0f))
            tracker.add(1032, Offset(200f + direction * 32f, 0f))
            tracker.add(1034, Offset(200f + direction * 32f, 0f))
            assertTrue(tracker.velocity().x * direction > 900f)
        }
    }

    @Test fun clippedAndMovingDestinationsRemainReachable() {
        val screen = androidx.compose.ui.geometry.Rect(56f, 56f, 944f, 944f)
        for (target in listOf(Offset(300f, 30f), Offset(300f, -200f), Offset(1100f, 500f))) {
            var motion = ReleaseMotion(Offset(500f, 500f), Offset(1200f, -400f))
            repeat(180) { frame ->
                val destination = if (frame < 10) Offset(500f, 500f) else target
                val progress = releaseAppearanceFraction(frame * .016f, motion.captureSeconds)
                motion = motion.step(destination, .016f, releaseLandingBounds(screen, destination, progress))
            }
            assertTrue((motion.position - target).getDistance() < .5f)
            assertTrue(motion.velocity.getDistance() < 5f)
        }
        assertEquals(screen, releaseLandingBounds(screen, Offset(0f, 0f), 0f))
    }

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
        assertTrue("short directional flight remains visible", motion.position.x - start.x > icon * .2f)
        assertTrue("lateral inertia stays below half an icon", motion.position.x - start.x < icon * .5f)
        val before = motion
        motion = motion.step(start - Offset(200f, 100f), .000001f)
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
        assertEquals(0f, releaseAppearanceFraction(.032f, capture), 0f)
        assertEquals(0f, releaseAppearanceFraction(capture, capture), 0f)
        assertEquals(1f, releaseAppearanceFraction(capture + .32f, capture), .0001f)
    }
    @Test fun ordinaryLandingSettlesInDesignedIntervalAndDelayRetainsCurrentMotion() {
        val icon = 100f
        for (speed in listOf(0f, 100f, 200f, 1000f)) {
            val velocity = Offset(speed, 0f)
            val capture = releaseCaptureSeconds(speed, icon)
            var motion = ReleaseMotion(Offset(300f, 400f), velocity, capture,
                settlingFrequency = releaseSettlingFrequency(speed, icon))
            val target = Offset(420f, 400f)
            var elapsed = 0f
            while (elapsed < 3f) {
                motion = motion.step(target, .001f)
                elapsed += .001f
                if (elapsed >= capture + releaseAppearanceSeconds(speed, icon) && (motion.position - target).getDistance() < .75f && motion.velocity.getDistance() < 15f) break
            }
            println("Landing speed=$speed px/s, duration=${elapsed * 1000} ms")
            if (speed <= 200f) assertTrue("ordinary landing: $elapsed", elapsed in .28f.. .42f)
            else assertTrue("fast landing captures promptly: $elapsed", elapsed < .55f)
        }
        var motion = ReleaseMotion(Offset.Zero, Offset(1000f, 0f))
        repeat(30) { motion = motion.step(null, .016f) }
        val atValidation = motion
        motion = motion.step(Offset(0f, 100f), .000001f)
        assertTrue(atValidation.position.x > 200f)
        assertTrue((motion.position - atValidation.position).getDistance() < .01f)
        assertTrue((motion.velocity - atValidation.velocity).getDistance() < 1f)
    }

    @Test fun sparseFinalMoveIsNotMistakenForAHoldOnUp() {
        val tracker = ReleaseVelocity()
        tracker.add(0, Offset.Zero)
        tracker.add(80, Offset(40f, 0f))
        tracker.add(82, Offset(40f, 0f))
        assertTrue(tracker.velocity().x > 450f)
    }
}
