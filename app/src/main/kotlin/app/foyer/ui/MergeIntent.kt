package app.foyer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Gesture intent uses original physical icon bounds, independently of animated appearance. */
internal data class MergeIntent(val candidate: Long? = null) {
    fun update(point: Offset, icons: Map<Long, Rect>, scrolling: Boolean = false): MergeIntent {
        val retained = candidate?.takeIf { !scrolling && icons[it]?.contains(point) == true }
        val next = retained ?: icons.entries.firstOrNull { !scrolling && it.value.deflate(it.value.width * .15f).contains(point) }?.key
        return if (next == candidate) this else MergeIntent(next)
    }
}

/** Emits readiness edges, never hover or animation callbacks. Release consumes without an exit. */
internal class MergeReadiness(private val feedback: (Boolean) -> Unit) {
    fun update(previous: Long?, next: Long?) {
        if (next == previous) return
        if (previous != null) feedback(false)
        if (next != null) feedback(true)
    }
}
