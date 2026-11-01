package app.foyer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class MergeIntentTest {
    private val icons = mapOf(1L to Rect(0f, 0f, 100f, 100f), 2L to Rect(110f, 0f, 210f, 100f))
    @Test fun centerArmsImmediatelyAndEdgesRemainSpatial() {
        val intent = MergeIntent().update(Offset(50f, 50f), icons)
        assertEquals(1L, intent.candidate)
        assertEquals(intent, intent.update(Offset(2f, 50f), icons))
        assertNull(MergeIntent().update(Offset(5f, 50f), icons).candidate)
        assertNull(intent.update(Offset(105f, 50f), icons).candidate)
    }
    @Test fun switchingAndReentryHaveNoGracePeriod() {
        val a = MergeIntent().update(Offset(50f, 50f), icons)
        val b = a.update(Offset(160f, 50f), icons)
        assertEquals(2L, b.candidate)
        assertEquals(1L, b.update(Offset(50f, 50f), icons).candidate)
    }
    @Test fun movingFeedAndOccludingLayersCancelImmediately() {
        val intent = MergeIntent().update(Offset(50f, 50f), icons)
        val scrolling = intent.update(Offset(50f, 50f), icons, scrolling = true)
        assertNull(scrolling.candidate)
        assertEquals(1L, scrolling.update(Offset(50f, 50f), icons).candidate)
        assertNull(intent.update(Offset(50f, 50f), emptyMap()).candidate)
    }
}
