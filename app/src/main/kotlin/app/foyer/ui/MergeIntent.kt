package app.foyer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Gesture intent uses original physical icon bounds, independently of animated appearance. */
internal data class MergeIntent(val candidate: Long? = null, val entered: Long = 0, val swapAfter: Long = 0) {
    val recognizedAt get() = entered + 80
    val armedAt get() = entered + 320
    fun recognized(now: Long) = candidate != null && now >= recognizedAt
    fun armed(now: Long) = candidate != null && now >= armedAt

    fun update(point: Offset, icons: Map<Long, Rect>, now: Long, scrolling: Boolean = false): MergeIntent {
        val retained = candidate?.takeIf { !scrolling && icons[it]?.contains(point) == true }
        val next = retained ?: icons.entries.firstOrNull { !scrolling && it.value.deflate(it.value.width * .15f).contains(point) }?.key
        if (next == candidate) return this
        val grace = if (recognized(now)) now + 120 else swapAfter
        return MergeIntent(next, now, grace)
    }
    fun blocksSwap(now: Long) = candidate != null || now < swapAfter
}

/** Emits readiness edges, never hover or animation callbacks. Release consumes without an exit. */
internal class MergeReadiness(private val feedback: (Boolean) -> Unit) {
    var ready: Long? = null
        private set
    fun update(next: Long?) {
        if (next == ready) return
        if (ready != null) feedback(false)
        ready = next
        if (next != null) feedback(true)
    }
    fun consume() { ready = null }
}
