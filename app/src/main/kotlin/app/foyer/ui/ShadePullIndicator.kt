package app.foyer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** A dismissed cycle stays round until hidden; a fresh downward pull can start another. */
internal class ShadePullFeedback(private val onArmed: () -> Unit = {}) {
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
    fun cancel() { active = false; cancelled = true }
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

@Composable
internal fun ShadePullIndicator(feedback: ShadePullFeedback) {
    val density = LocalDensity.current
    val safeTop = WindowInsets.safeDrawing.getTop(density).toFloat()
    val safeLeft = WindowInsets.safeDrawing.getLeft(density, androidx.compose.ui.platform.LocalLayoutDirection.current)
    val safeRight = WindowInsets.safeDrawing.getRight(density, androidx.compose.ui.platform.LocalLayoutDirection.current)
    val visible = (feedback.progress > 0f && !feedback.cancelled) || feedback.retracting
    val alpha = animateFloatAsState(if (visible) 1f else 0f, tween(120), label = "shadeAlpha")
    val rounding = remember(feedback.cycle) { Animatable(0f) }
    LaunchedEffect(feedback.cycle, feedback.armed) {
        if (feedback.armed) rounding.animateTo(1f, spring(dampingRatio = .75f, stiffness = 300f))
    }
    val travel = animateFloatAsState(if (feedback.retracting) 0f else feedback.progress.coerceIn(0f, 1.3f),
        spring(dampingRatio = .7f, stiffness = 420f), label = "shadeTravel")
    val hidden = if (feedback.retracting) travel.value <= .001f else alpha.value == 0f
    LaunchedEffect(feedback.cancelled, hidden) {
        if (feedback.cancelled && hidden) feedback.finishDismissal()
    }
    val fill = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onPrimary
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
        // Keep the whole shape below system insets, even during spring overshoot.
        val edge = safeTop + 8.dp.toPx()
        val cy = edge + side / 2 + 24.dp.toPx() * p
        val radius = side * (.2f + .3f * m)
        drawRoundRect(fill, Offset(cx - width / 2, cy - side / 2), Size(width, side),
            CornerRadius(radius), alpha = opacity)
        val angle = (62f + 40f * m) * Math.PI.toFloat() / 360f
        val arm = 12.dp.toPx()
        val half = arm * sin(angle)
        val depth = arm * cos(angle)
        val path = Path().apply {
            moveTo(cx - half, cy - depth / 2)
            lineTo(cx, cy + depth / 2)
            lineTo(cx + half, cy - depth / 2)
        }
        drawPath(path, ink, alpha = opacity,
            style = Stroke(3.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter))
    }
}
