package app.foyer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import app.foyer.data.AppKey

internal enum class DropLayer { Panel, Dock, Feed }

/** Resolve the visible layer before looking at icons, including empty panel and dock space. */
internal fun dropLayerAt(point: Offset, viewport: Rect, panel: Rect?, dock: Rect?): DropLayer? = when {
    !viewport.contains(point) -> null
    panel?.contains(point) == true -> DropLayer.Panel
    dock?.contains(point) == true -> DropLayer.Dock
    else -> DropLayer.Feed
}

internal fun folderColumns(count: Int, large: Boolean = false): Int = if (large || count >= 5) 3 else 2

/** Normalized icon centers, including the three-member triangular preview. */
internal fun folderPreviewCenters(count: Int): List<Offset> = when (count.coerceIn(0, 4)) {
    0 -> emptyList()
    1 -> listOf(Offset(.5f, .5f))
    2 -> listOf(Offset(.25f, .5f), Offset(.75f, .5f))
    3 -> listOf(Offset(.25f, .25f), Offset(.75f, .25f), Offset(.5f, .75f))
    else -> listOf(Offset(.25f, .25f), Offset(.75f, .25f), Offset(.25f, .75f), Offset(.75f, .75f))
}

/** A preview slot retains its member identity across closed and animated rendering. */
internal data class FolderPreviewMember(val index: Int, val center: Offset)

internal fun folderPreviewMembers(count: Int, large: Boolean = false): List<FolderPreviewMember> =
    if (large) (0 until minOf(count, 9)).map { index ->
        FolderPreviewMember(index, Offset((index % 3 + .5f) / 3, (index / 3 + .5f) / 3))
    } else {
        val indices = if (count <= 4) (0 until count).toList() else listOf(0, 1, 3, 4)
        folderPreviewCenters(count).zip(indices).mapNotNull { (center, index) ->
            if (index < count) FolderPreviewMember(index, center) else null
        }
    }

/** Keep the compact panel near its source while respecting the visible, keyboard-safe area. */
internal fun folderPanelRect(anchor: Rect, viewport: Rect, desired: Size): Rect {
    val width = desired.width.coerceIn(0f, viewport.width.coerceAtLeast(0f))
    val height = desired.height.coerceIn(0f, viewport.height.coerceAtLeast(0f))
    val x = (anchor.center.x - width / 2).coerceIn(viewport.left, (viewport.right - width).coerceAtLeast(viewport.left))
    val y = (anchor.center.y - height / 2).coerceIn(viewport.top, (viewport.bottom - height).coerceAtLeast(viewport.top))
    return Rect(Offset(x, y), Size(width, height))
}

/** Hit testing uses stationary slots, never the animated bounds of reordering children. */
internal fun folderInsertionIndex(point: Offset, grid: Rect, rowHeight: Float, count: Int, scroll: Float = 0f, large: Boolean = false, columns: Int = folderColumns(count, large)): Int {
    if (point.y >= grid.bottom) return count
    val column = ((point.x - grid.left) / (grid.width / columns)).toInt().coerceIn(0, columns - 1)
    val row = ((point.y - grid.top + scroll) / rowHeight).toInt().coerceAtLeast(0)
    return (row * columns + column).coerceAtMost(count)
}

internal fun folderInsertionForMember(slot: Int, sourceIndex: Int): Int =
    slot + if (sourceIndex >= 0 && slot > sourceIndex) 1 else 0

/** Preserve the dragged member's composition (and pointer ownership) as the invisible gap. */
internal fun <T> folderDragOrder(apps: List<T>, member: T?, index: Int, large: Boolean = false): List<T?> {
    val old = apps.indexOf(member)
    val insertion = (if (old < 0 && !large && apps.size == 4 && index >= 4) 2 else index).coerceIn(0, apps.size)
    return apps.toMutableList<T?>().apply {
        if (old >= 0) removeAt(old)
        add((insertion - if (old in 0 until insertion) 1 else 0).coerceIn(0, size), member)
    }
}

/** The closed preview uses the very same coordinates as FolderTile, not the whole feed cell. */
internal fun folderMemberStart(surface: Rect, large: Boolean, count: Int, index: Int, icon: Float, inset: Float): Rect {
    val size = icon * if (large) .65f else .38f
    val normalized = folderPreviewMembers(count, large).find { it.index == index }?.center ?: Offset(.5f, .5f)
    val center = if (large) {
        val padding = inset
        Offset(surface.left + padding + (surface.width - 2 * padding) * normalized.x,
            surface.top + padding + (surface.height - 2 * padding) * normalized.y)
    } else {
        Offset(surface.left + inset + (surface.width - 2 * inset) * normalized.x,
            surface.top + inset + (surface.height - 2 * inset) * normalized.y)
    }
    return Rect(center - Offset(size / 2, size / 2), Size(size, size))
}

/** Unclipped slot geometry also works for members that the lazy grid has not composed. */
internal fun folderGridIcon(grid: Rect, index: Int, rowHeight: Float, icon: Float, topPadding: Float, scroll: Float, columns: Int = 3): Rect =
    Rect(Offset(grid.left + grid.width * (index % columns + .5f) / columns - icon / 2,
        grid.top + index / columns * rowHeight + topPadding - scroll), Size(icon, icon))

internal fun folderMemberFrame(start: Rect, end: Rect, progress: Float): Rect =
    androidx.compose.ui.geometry.lerp(start, end, progress)

/** Only scroll inside the grid's horizontal lane and the panel's visible vertical extent. */
internal fun folderEdgeScroll(pointer: Offset, grid: Rect, zone: Float, speed: Float): Float {
    if (grid.height <= 0f || pointer.x < grid.left || pointer.x >= grid.right) return 0f
    val edge = zone.coerceAtMost(grid.height / 2)
    if (edge <= 0f) return 0f
    return when {
        pointer.y < grid.top - edge || pointer.y > grid.bottom + edge -> 0f
        pointer.y < grid.top + edge -> -speed * ((grid.top + edge - pointer.y) / edge).coerceIn(0f, 1f)
        pointer.y > grid.bottom - edge -> speed * ((pointer.y - grid.bottom + edge) / edge).coerceIn(0f, 1f)
        else -> 0f
    }
}

/** Only search owns the dock keyboard lift, including its opening and closing frames. */
internal fun dockKeyboardLift(lift: Float, active: Boolean, progress: Float, target: Float, folderVisible: Boolean = false): Float =
    if (active || target > 0f || progress > 0f && !folderVisible) lift else 0f

internal data class FolderMemberVisual(val bounds: Rect, val alpha: Float)

/** Match the moving panel mask and the preview layer's interpolated grid viewport. */
internal fun folderContentClip(panel: Rect, grid: Rect, expansion: Float): Rect =
    panel.intersect(androidx.compose.ui.geometry.lerp(panel, grid, expansion))

/** Gradually adopt the destination's clipping before handing the icon back to the grid. */
internal fun folderLandingClip(frame: Rect, viewport: Rect, progress: Float): Rect {
    val visible = if (frame.overlaps(viewport)) frame.intersect(viewport) else Rect(frame.center, Size.Zero)
    return androidx.compose.ui.geometry.lerp(frame, visible, ((progress - .65f) / .35f).coerceIn(0f, 1f))
}

/** Absolute large-folder cells share the rendered grid's inset and never compress holes. */
internal fun largeFolderSlot(point: Offset, surface: Rect, inset: Float): Int {
    val grid = surface.deflate(inset)
    val column = ((point.x - grid.left) / (grid.width / 3)).toInt().coerceIn(0, 2)
    val row = ((point.y - grid.top) / (grid.height / 3)).toInt().coerceIn(0, 2)
    return row * 3 + column
}
