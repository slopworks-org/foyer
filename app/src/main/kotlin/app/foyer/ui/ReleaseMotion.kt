package app.foyer.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.exp

/** Bound pathological input without changing the release direction. Units are root px/second. */
internal fun boundedReleaseVelocity(velocity: Offset, maximum: Float): Offset {
    if (!velocity.x.isFinite() || !velocity.y.isFinite()) return Offset.Zero
    val speed = velocity.getDistance()
    return if (speed > maximum) velocity * (maximum / speed) else velocity
}

/** Exact critically damped step: retargeting preserves both position and velocity. */
internal data class ReleaseMotion(val position: Offset, val velocity: Offset) {
    fun step(target: Offset?, seconds: Float): ReleaseMotion {
        if (target == null) {
            val decay = exp(-12f * seconds)
            return ReleaseMotion(position + velocity * ((1f - decay) / 12f), velocity * decay)
        }
        val displacement = position - target
        val impulse = velocity + displacement * 18f
        val decay = exp(-18f * seconds)
        return ReleaseMotion(target + (displacement + impulse * seconds) * decay,
            (velocity - impulse * (18f * seconds)) * decay)
    }
}
