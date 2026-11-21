package app.foyer.ui

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import app.foyer.data.HomeItem
import app.foyer.data.HomeLayout
import app.foyer.system.WidgetSpan

internal enum class ResizeEdge(val alignment: Alignment, val side: Boolean) {
    Left(Alignment.CenterStart, true), Right(Alignment.CenterEnd, true),
    Top(Alignment.TopCenter, false), Bottom(Alignment.BottomCenter, false);

    /** Signed cell offsets from the original edge. A singleton range means it cannot move. */
    fun offsets(item: HomeItem, span: WidgetSpan?): IntRange {
        if ((if (side) span?.resizeH else span?.resizeV) == false) return 0..0
        // A provider update must still allow keeping the widget's current size.
        val size = if (side) item.w else item.h
        val min = (if (side) span?.minW else span?.minH) ?: 1
        val max = if (side) span?.maxW ?: HomeLayout.COLUMNS else span?.maxH ?: 20
        val shrink = (size - min).coerceAtLeast(0)
        val grow = (max - size).coerceAtLeast(0)
        return when (this) {
            Left -> -minOf(item.x, grow)..shrink
            Right -> -shrink..minOf(HomeLayout.COLUMNS - item.x - item.w, grow)
            Top -> -minOf(item.y, grow)..shrink
            Bottom -> -shrink..grow
        }
    }

    fun move(item: HomeItem, cells: Int): HomeItem = when (this) {
        Left -> item.copy(x = item.x + cells, w = item.w - cells)
        Right -> item.copy(w = item.w + cells)
        Top -> item.copy(y = item.y + cells, h = item.h - cells)
        Bottom -> item.copy(h = item.h + cells)
    }
}

/** Both screen edges are expressed in the handle's local coordinates. */
internal fun resizeExclusion(size: IntSize, screenEdgeX: Float, left: Boolean): Rect =
    if (left) Rect(screenEdgeX, 0f, size.width.toFloat(), size.height.toFloat())
    else Rect(0f, 0f, screenEdgeX, size.height.toFloat())
