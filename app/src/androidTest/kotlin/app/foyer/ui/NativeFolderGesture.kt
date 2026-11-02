package app.foyer.ui

import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset

/** Real-clock input for evidence tests, with root-to-window conversion on the UI thread. */
internal class NativeFolderGesture(
    private val activity: Activity,
    private val state: () -> LauncherState,
    private val main: (() -> Unit) -> Unit,
    private val onEvent: (Long, Int, Offset) -> Unit = { _, _, _ -> },
) {
    private var downTime = 0L

    fun send(action: Int, point: Offset) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        main {
            val window = state().rootCoords!!.localToWindow(Offset.Zero)
            val event = MotionEvent.obtain(downTime, now, action, point.x + window.x, point.y + window.y, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                activity.dispatchTouchEvent(event)
                onEvent(now, action, point)
            } finally {
                event.recycle()
            }
        }
    }

    fun begin(point: Offset, nudge: Offset = Offset(30f, 30f), afterMoveMillis: Long = 50) {
        send(MotionEvent.ACTION_DOWN, point)
        SystemClock.sleep(620)
        send(MotionEvent.ACTION_MOVE, point + nudge)
        SystemClock.sleep(afterMoveMillis)
        main { org.junit.Assert.assertNotNull("native drag started", state().drag) }
    }

    fun release(cancel: Boolean = false, settleMillis: Long = 450,
        settled: (LauncherState) -> Boolean = { it.drag == null && it.folderLanding == null }) {
        var pointer = Offset.Zero
        main { pointer = state().drag!!.pointer }
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, pointer)
        val deadline = SystemClock.uptimeMillis() + 10000
        var finished = false
        while (!finished && SystemClock.uptimeMillis() < deadline) {
            main { finished = settled(state()) }
            if (!finished) SystemClock.sleep(20)
        }
        org.junit.Assert.assertTrue("native drag settled", finished)
        SystemClock.sleep(settleMillis)
    }

    fun approach(anchor: Offset, steps: Int = 15) {
        var start = Offset.Zero
        var destination = Offset.Zero
        main { start = state().drag!!.pointer; destination = anchor - state().drag!!.anchorOffset }
        for (step in 1..steps) {
            send(MotionEvent.ACTION_MOVE, start + (destination - start) * (step.toFloat() / steps))
            SystemClock.sleep(16)
        }
    }
}
