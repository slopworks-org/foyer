package app.foyer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback

/** Callbacks for something you can tap, long-press for a menu, and then drag away. */
class ItemGestureCallbacks(
    val onTap: (windowBounds: android.graphics.Rect) -> Unit,
    val onLongPress: (rootBounds: Rect) -> Unit,
    /** [pointer] = where the finger went down, [bounds] = the item, both in root px. */
    val onDragStart: (pointer: Offset, bounds: Rect) -> Unit,
    val onDrag: (pointer: Offset) -> Unit,
    val onDragEnd: () -> Unit,
    val onDragCancel: () -> Unit,
)

/**
 * Tap / long-press / long-press-then-drag, all in root coordinates. Nothing is consumed
 * before the long press fires, so the feed can still scroll and the search swipe still works.
 */
fun Modifier.itemGestures(
    callbacks: ItemGestureCallbacks,
    onPressedChange: (Boolean) -> Unit = {},
): Modifier = composed {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val cb by rememberUpdatedState(callbacks)
    val haptics = LocalHapticFeedback.current
    this
        .onGloballyPositioned { coords = it }
        .pointerInput(Unit) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                onPressedChange(true)
                // 1 = tap, 2 = cancelled, null = long press
                val outcome: Int? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var r = 0
                    while (r == 0) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        r = when {
                            c == null -> 2
                            c.changedToUpIgnoreConsumed() -> if (c.isConsumed) 2 else 1
                            c.isConsumed || (c.position - down.position).getDistance() > slop -> 2
                            else -> 0
                        }
                    }
                    r
                }
                onPressedChange(false)
                val lc = coords ?: return@awaitEachGesture
                when (outcome) {
                    1 -> {
                        currentEvent.changes.forEach { it.consume() }
                        val b = lc.boundsInWindow()
                        cb.onTap(android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
                    }
                    2 -> Unit
                    else -> {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        cb.onLongPress(lc.boundsInRoot())
                        var dragging = false
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                c.consume()
                                if (!c.pressed) break
                                val now = coords ?: lc
                                if (!dragging && (c.position - down.position).getDistance() > slop) {
                                    dragging = true
                                    cb.onDragStart(now.localToRoot(down.position), now.boundsInRoot())
                                }
                                if (dragging) cb.onDrag(now.localToRoot(c.position))
                            }
                            if (dragging) {
                                dragging = false
                                cb.onDragEnd()
                            }
                        } finally {
                            if (dragging) cb.onDragCancel()
                        }
                    }
                }
            }
        }
}

/**
 * Vertical swipe that follows the finger. [direction] -1 reacts to upward swipes only,
 * +1 to downward ones. Gives way to anything below that already took the gesture (lists).
 */
fun Modifier.verticalSwipe(
    enabled: () -> Boolean,
    direction: Int,
    onStart: () -> Unit,
    onDelta: (Float) -> Unit,
    onEnd: (velocity: Float) -> Unit,
): Modifier = composed {
    // The swiped panel usually moves with the finger, so local positions barely change.
    // Track the finger on screen instead, or the drag (and its velocity) gets eaten.
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    this
        .onGloballyPositioned { coords = it }
        .pointerInput(Unit) {
            fun screenY(local: Offset) = coords?.takeIf { it.isAttached }?.localToRoot(local)?.y ?: local.y
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!enabled()) return@awaitEachGesture
                var overSlop = 0f
                val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                    if (over * direction > 0) {
                        overSlop = over
                        change.consume()
                    }
                } ?: return@awaitEachGesture
                val tracker = VelocityTracker()
                var lastY = screenY(start.position)
                tracker.addPosition(start.uptimeMillis, Offset(0f, lastY))
                onStart()
                onDelta(overSlop)
                // Once the swipe is ours, every movement counts, including the one that
                // arrives together with lifting the finger.
                var completed = false
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == start.id } ?: break
                    val y = screenY(c.position)
                    tracker.addPosition(c.uptimeMillis, Offset(0f, y))
                    if (y != lastY) onDelta(y - lastY)
                    lastY = y
                    c.consume()
                    if (!c.pressed) {
                        completed = true
                        break
                    }
                }
                onEnd(if (completed) tracker.calculateVelocity().y else 0f)
            }
        }
}
