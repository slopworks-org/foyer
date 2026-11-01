package app.foyer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class MergeIntentTest {
    private val icons = mapOf(1L to Rect(0f, 0f, 100f, 100f), 2L to Rect(110f, 0f, 210f, 100f))
    @Test fun centerRecognizesThenMorphsBeforeArming() {
        val intent = MergeIntent().update(Offset(50f, 50f), icons, 1000)
        assertFalse(intent.recognized(1079)); assertTrue(intent.recognized(1080))
        assertFalse(intent.armed(1319)); assertTrue(intent.armed(1320))
        assertTrue(intent.blocksSwap(1050)) // a pre-arm center release cancels
        assertEquals(null, MergeIntent().update(Offset(5f, 50f), icons, 1000).candidate)
    }
    @Test fun originalBoundsRetainIntentAndExitRequiresSwapGrace() {
        val intent = MergeIntent().update(Offset(50f, 50f), icons, 1000)
        assertEquals(intent, intent.update(Offset(2f, 50f), icons, 1200))
        val exited = intent.update(Offset(105f, 50f), icons, 1200)
        assertNull(exited.candidate)
        assertTrue(exited.blocksSwap(1319)); assertFalse(exited.blocksSwap(1320))
        assertFalse(exited.armed(2000))
    }
    @Test fun newCandidateAndReentryRestartContinuousRecognition() {
        val a = MergeIntent().update(Offset(50f, 50f), icons, 0)
        val b = a.update(Offset(160f, 50f), icons, 300)
        assertEquals(2L, b.candidate); assertFalse(b.recognized(379))
        val again = b.update(Offset(50f, 50f), icons, 400)
        assertFalse(again.armed(719)); assertTrue(again.armed(720))
    }
    @Test fun movingFeedAndOccludingLayersCancelImmediately() {
        val intent = MergeIntent().update(Offset(50f, 50f), icons, 0)
        val scrolling = intent.update(Offset(50f, 50f), icons, 500, scrolling = true)
        assertNull(scrolling.candidate)
        val stopped = scrolling.update(Offset(50f, 50f), icons, 516)
        assertFalse(stopped.armed(835)); assertTrue(stopped.armed(836))
        assertNull(intent.update(Offset(50f, 50f), emptyMap(), 500).candidate)
    }
}
