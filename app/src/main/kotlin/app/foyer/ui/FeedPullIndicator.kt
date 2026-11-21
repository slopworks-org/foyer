package app.foyer.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A dismissed cycle stays round until hidden; a fresh pull can start another. */
internal class FeedPullFeedback(private val onArmed: () -> Unit = {}) {
    var progress by mutableFloatStateOf(0f)
        private set
    var armed by mutableStateOf(false)
        private set
    var cancelled by mutableStateOf(false)
        private set
    var retracting by mutableStateOf(false)
        private set
    var cycle by mutableIntStateOf(0)
        private set
    private var active = false
    private var hidden = false
    private var peak = 0f
    private var valley = 0f
    private var origin = 0f
    private var latest = 0f
    private var tolerance = 0f

    fun begin() {
        active = true; hidden = false; peak = 0f; valley = 0f; origin = 0f; latest = 0f
        progress = 0f; armed = false; cancelled = false; retracting = false; cycle++
    }
    fun cancel(retract: Boolean = false) {
        if (active && retract) retracting = true
        active = false
        cancelled = true
    }
    fun finishDismissal() {
        if (!cancelled) return
        hidden = true
        if (active) update(latest, tolerance)
    }
    fun update(value: Float, reversalTolerance: Float): Boolean {
        if (!active) return false
        latest = value
        tolerance = reversalTolerance
        if (cancelled) {
            valley = minOf(valley, value)
            if (!hidden || value - valley <= tolerance) return false
            origin = valley; peak = value; hidden = false; cancelled = false; armed = false; retracting = false; cycle++
        }
        progress = (value - origin).coerceAtLeast(0f)
        peak = maxOf(peak, value)
        if (peak - value > tolerance) {
            valley = value; cancelled = true; retracting = true
            return false
        }
        if (!armed && progress >= 1f) { armed = true; onArmed(); return true }
        return false
    }
}

internal enum class PullEdge(val sign: Int) { Top(1), Bottom(-1) }

internal data class PullRelease(val edge: PullEdge, val armed: Boolean) {
    fun accepts(velocityY: Float) = armed && edge.sign * velocityY > -500f
}

internal class FeedPullGesture(val threshold: Float, private val tolerance: Float, onArmed: () -> Unit) {
    val shade = FeedPullFeedback(onArmed)
    val search = FeedPullFeedback(onArmed)
    var distance = 0f
        private set
    private var owner: PullEdge? = null
    private var touching = false
    private var allowSearch = false

    fun begin(searchEnabled: Boolean) {
        owner = null
        distance = 0f
        touching = true
        allowSearch = searchEnabled
    }

    private fun feedback(edge: PullEdge) = if (edge == PullEdge.Top) shade else search

    private fun move(edge: PullEdge, delta: Float): Float {
        distance += delta
        feedback(edge).update(abs(distance) / threshold, tolerance)
        return delta
    }

    fun unwind(delta: Float): Float {
        val edge = owner ?: return 0f
        if (distance * delta >= 0f) return 0f
        return move(edge, delta.coerceIn(-abs(distance), abs(distance)))
    }

    fun overscroll(delta: Float, searchEnabled: Boolean): Float {
        if (!touching || delta == 0f) return 0f
        val edge = if (delta > 0f) PullEdge.Top else PullEdge.Bottom
        if (edge == PullEdge.Bottom && !(allowSearch && searchEnabled)) return 0f
        // Keep ownership even at zero: a short feed can expose both edges in one motion.
        if (owner != null && owner != edge) return 0f
        if (owner == null) feedback(edge).begin()
        owner = edge
        return move(edge, delta)
    }

    fun end(cancelled: Boolean = false): PullRelease? {
        val release = owner?.takeIf { distance != 0f }?.let {
            PullRelease(it, !cancelled && feedback(it).armed && !feedback(it).cancelled)
        }
        distance = 0f
        owner = null
        touching = false
        shade.cancel(retract = cancelled)
        search.cancel(retract = cancelled)
        return release
    }
}

@Composable
internal fun FeedPullIndicator(
    feedback: FeedPullFeedback,
    edge: PullEdge,
    dockHeight: Dp = 0.dp,
    dockLiftPx: () -> Float = { 0f },
) {
    val density = LocalDensity.current
    val safeTop = WindowInsets.safeDrawing.getTop(density).toFloat()
    val safeLeft = WindowInsets.safeDrawing.getLeft(density, androidx.compose.ui.platform.LocalLayoutDirection.current)
    val safeRight = WindowInsets.safeDrawing.getRight(density, androidx.compose.ui.platform.LocalLayoutDirection.current)
    val visible = (feedback.progress > 0f && !feedback.cancelled) || feedback.retracting
    val alpha = animateFloatAsState(if (visible) 1f else 0f, tween(120), label = "pullAlpha")
    val rounding = remember(feedback.cycle) { Animatable(0f) }
    LaunchedEffect(feedback.cycle, feedback.armed) {
        if (feedback.armed) rounding.animateTo(1f, spring(dampingRatio = .75f, stiffness = 300f))
    }
    val travel = animateFloatAsState(if (feedback.retracting) 0f else feedback.progress.coerceIn(0f, 1.3f),
        spring(dampingRatio = .7f, stiffness = 420f), label = "pullTravel")
    val hidden = if (feedback.retracting) travel.value <= .001f else alpha.value == 0f
    LaunchedEffect(feedback.cancelled, hidden) {
        if (feedback.cancelled && hidden) feedback.finishDismissal()
    }
    val systemColors = dynamicDarkColorScheme(LocalContext.current)
    val dark = isSystemInDarkTheme()
    // Android 16 changed Back from secondary/onSecondary to container/fixed roles.
    val fill = if (Build.VERSION.SDK_INT >= 36) {
        colorResource(if (dark) android.R.color.system_secondary_container_dark else android.R.color.system_secondary_fixed_dim)
    } else {
        if (dark) systemColors.onSecondary else systemColors.secondary
    }
    val ink = if (Build.VERSION.SDK_INT >= 36) {
        colorResource(if (dark) android.R.color.system_on_secondary_container_dark else android.R.color.system_on_secondary_fixed)
    } else {
        colorResource(if (dark) android.R.color.system_accent1_100 else android.R.color.system_neutral1_900)
    }
    Canvas(Modifier.fillMaxSize()) {
        if (alpha.value < .001f) return@Canvas
        val p = travel.value.coerceAtLeast(0f)
        val opacity = alpha.value * (p / .25f).coerceIn(0f, 1f)
        val m = rounding.value.coerceIn(0f, 1f)
        val side = 48.dp.toPx()
        // Small horizontal elasticity only; height is constant, including during entry.
        val stretch = ((feedback.progress.coerceIn(0f, 1.3f) - p) * .15f).coerceIn(-.025f, .06f) * (1f - m)
        val width = side * (1f + stretch)
        val cx = safeLeft + (size.width - safeLeft - safeRight) / 2f
        val clearance = 8.dp.toPx() + 24.dp.toPx() * p
        val cy = if (edge == PullEdge.Top) safeTop + side / 2 + clearance else {
            // Start fully behind the dock, reaching the same clearance as the top pill at p = 1.
            val tucked = (side + 8.dp.toPx()) * (1f - p).coerceAtLeast(0f)
            size.height - dockHeight.toPx() - dockLiftPx() - side / 2 - clearance + tucked
        }
        val radius = side * (.2f + .3f * m)
        drawRoundRect(fill, Offset(cx - width / 2, cy - side / 2), Size(width, side),
            CornerRadius(radius), alpha = opacity)
        val angle = (62f + 40f * m) * Math.PI.toFloat() / 360f
        val arm = 12.dp.toPx()
        val half = arm * sin(angle)
        val depth = edge.sign * arm * cos(angle)
        val path = Path().apply {
            moveTo(cx - half, cy - depth / 2)
            lineTo(cx, cy + depth / 2)
            lineTo(cx + half, cy - depth / 2)
        }
        drawPath(path, ink, alpha = opacity,
            style = Stroke(3.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter))
    }
}
