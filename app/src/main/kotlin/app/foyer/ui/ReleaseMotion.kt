package app.foyer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.exp

/** Bound pathological input without changing the release direction. Units are root px/second. */
internal fun boundedReleaseVelocity(velocity: Offset, maximum: Float): Offset {
    if (!velocity.x.isFinite() || !velocity.y.isFinite()) return Offset.Zero
    val speed = velocity.getDistance()
    return if (speed > maximum) velocity * (maximum / speed) else velocity
}

/** Recent linear fit, including stationary/up samples, without extrapolating acceleration. */
internal class ReleaseVelocity {
    private val samples = ArrayDeque<Pair<Long, Offset>>()
    private var lastMotion = Long.MIN_VALUE
    fun add(time: Long, position: Offset) {
        // A gap cannot establish recent motion; resume from the new observation.
        if (samples.lastOrNull()?.let { time - it.first > 80 } == true) samples.clear()
        if (samples.lastOrNull()?.second != position) lastMotion = time
        if (samples.lastOrNull()?.first == time) samples.removeLast()
        samples.addLast(time to position)
        // Keep the segment crossing the window boundary, including a sparse final move.
        while (samples.size > 2 && time - samples[1].first > 80) samples.removeFirst()
    }
    fun velocity(): Offset {
        if (samples.size < 2 || samples.last().first - lastMotion >= 80) return Offset.Zero
        val origin = samples.last().first
        val recent = samples.toMutableList()
        val boundary = origin - 80
        if (recent.first().first < boundary) {
            val (a, p) = recent[0]
            val (b, q) = recent[1]
            recent[0] = boundary to (p + (q - p) * ((boundary - a).toFloat() / (b - a)))
        }
        val meanTime = recent.map { (it.first - origin).toFloat() }.average().toFloat()
        val meanPosition = recent.fold(Offset.Zero) { sum, it -> sum + it.second } / recent.size.toFloat()
        var numerator = Offset.Zero
        var denominator = 0f
        for ((time, position) in recent) {
            val t = (time - origin) - meanTime
            numerator += (position - meanPosition) * t
            denominator += t * t
        }
        return if (denominator > 0f) numerator * (1000f / denominator) else Offset.Zero
    }
}

/** Keep the initial fling on screen, then allow capture at clipped destinations. */
internal fun releaseLandingBounds(screen: Rect, target: Offset?, progress: Float): Rect {
    if (target == null) return screen
    val expanded = Rect(minOf(screen.left, target.x), minOf(screen.top, target.y),
        maxOf(screen.right, target.x), maxOf(screen.bottom, target.y))
    return androidx.compose.ui.geometry.lerp(screen, expanded, progress.coerceIn(0f, 1f))
}

/** Attraction ramps from zero: the finger's motion remains visible before capture begins.
 * Position and velocity are carried through validation, reflow and retargeting.
 */
internal data class ReleaseMotion(
    val position: Offset,
    val velocity: Offset,
    val captureSeconds: Float = .18f,
    val age: Float = 0f,
    val settlingFrequency: Float = 14f,
) {
    fun step(target: Offset?, seconds: Float, bounds: Rect? = null): ReleaseMotion {
        var p = position
        var v = velocity
        var elapsed = 0f
        // Small integration steps keep the ramp independent of frame cadence.
        while (elapsed < seconds) {
            val dt = minOf(.002f, seconds - elapsed)
            val fraction = ((age + elapsed) / captureSeconds).coerceIn(0f, 1f)
            val frequency = settlingFrequency * fraction * fraction * (3f - 2f * fraction)
            if (target == null) {
                val decay = exp(-4f * dt)
                p += v * ((1f - decay) / 4f)
                v *= decay
            } else {
                val acceleration = (target - p) * (frequency * frequency) - v * (2f * frequency)
                p += v * dt + acceleration * (.5f * dt * dt)
                v += acceleration * dt
            }
            if (bounds != null) {
                val clamped = Offset(p.x.coerceIn(bounds.left, bounds.right), p.y.coerceIn(bounds.top, bounds.bottom))
                v = Offset(if (clamped.x != p.x) 0f else v.x, if (clamped.y != p.y) 0f else v.y)
                p = clamped
            }
            elapsed += dt
        }
        return copy(position = p, velocity = v, age = age + seconds)
    }
}

internal fun releaseCaptureSeconds(speed: Float, icon: Float): Float =
    .08f + .14f * (speed / (icon * 10f)).coerceIn(0f, 1f)

/** Scale and opacity cannot conceal the initial directional flight. */
internal fun releaseAppearanceFraction(age: Float, captureSeconds: Float, appearanceSeconds: Float = .32f): Float =
    ((age - captureSeconds) / appearanceSeconds).coerceIn(0f, 1f)

/** Quiet releases settle within the ordinary landing interval; flings retain visible momentum. */
internal fun releaseSettlingFrequency(speed: Float, icon: Float): Float =
    32f - 18f * ((speed / icon - 2f) / 3f).coerceIn(0f, 1f)

internal fun releaseAppearanceSeconds(speed: Float, icon: Float): Float =
    .28f + .04f * (speed / (icon * 5f)).coerceIn(0f, 1f)
