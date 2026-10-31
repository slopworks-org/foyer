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
        assertTrue(coast.position.getDistance() <= 250.01f)
        assertTrue(coast.velocity.getDistance() < .01f)
        assertEquals(Offset.Zero, boundedReleaseVelocity(Offset(Float.NaN, 1f), 3000f))
    }
}
