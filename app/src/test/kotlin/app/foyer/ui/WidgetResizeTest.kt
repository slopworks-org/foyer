package app.foyer.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import app.foyer.data.HomeItem
import app.foyer.data.WidgetContent
import app.foyer.system.WidgetSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetResizeTest {
    private val item = HomeItem(1, WidgetContent(1, "widget"), x = 0, y = 0, w = 2, h = 2)
    private val span = WidgetSpan(2, 2, 2, 2, 4, 4, resizeH = true, resizeV = true)

    @Test fun minimumSizeAtGridBoundaryHidesOnlyTheBlockedEdges() {
        assertEquals(0..0, ResizeEdge.Left.offsets(item, span, 5))
        assertEquals(0..0, ResizeEdge.Top.offsets(item, span, 5))
        assertEquals(0..1, ResizeEdge.Right.offsets(item.copy(x = 1), span, 4))
        assertEquals(0..2, ResizeEdge.Right.offsets(item, span, 5))
        assertEquals(0..2, ResizeEdge.Bottom.offsets(item, span, 5))
        assertEquals(0..0, ResizeEdge.Right.offsets(item.copy(x = 3), span, 5))
        assertEquals(-1..0, ResizeEdge.Top.offsets(item.copy(y = 1), span, 5))
    }

    @Test fun shrinkingAtMaximumSizeKeepsTheOppositeEdgesFixed() {
        val large = item.copy(w = 4, h = 4)
        for (edge in ResizeEdge.entries) {
            val offsets = edge.offsets(large, span, 5)
            val leading = edge == ResizeEdge.Left || edge == ResizeEdge.Top
            assertEquals(if (leading) 0..2 else -2..0, offsets)
            val resized = edge.move(large, if (leading) 2 else -2)
            assertEquals(if (edge == ResizeEdge.Left) 2 else 0, resized.x)
            assertEquals(if (edge == ResizeEdge.Top) 2 else 0, resized.y)
            assertEquals(if (edge.side) 2 else 4, resized.w)
            assertEquals(if (edge.side) 4 else 2, resized.h)
        }
    }

    @Test fun providerLimitsRespectDisabledAxesAndExistingWidgetSizes() {
        val fixed = span.copy(resizeH = false, maxH = 2)
        for (edge in ResizeEdge.entries) assertEquals(0..0, edge.offsets(item, fixed, 5))
        assertEquals(-3..0, ResizeEdge.Bottom.offsets(item.copy(h = 5), fixed, 5))
        assertEquals(0..3, ResizeEdge.Right.offsets(item.copy(w = 1), span, 5))
        assertEquals(0..0, ResizeEdge.Bottom.offsets(item, span.copy(resizeV = false), 5))
    }

    @Test fun exclusionExtendsFromEachBezelOnlyAcrossTheHandleHeight() {
        val handle = IntSize(48, 60)
        assertEquals(Rect(-16f, 0f, 48f, 60f), resizeExclusion(handle, -16f, left = true))
        assertEquals(Rect(0f, 0f, 64f, 60f), resizeExclusion(handle, 64f, left = false))
    }
}
