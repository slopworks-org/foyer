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

/** Normalized icon centers, including the three-member triangular preview. */
internal fun folderPreviewCenters(count: Int): List<Offset> = when (count.coerceIn(0, 4)) {
    0 -> emptyList()
    1 -> listOf(Offset(.5f, .5f))
    2 -> listOf(Offset(.25f, .5f), Offset(.75f, .5f))
    3 -> listOf(Offset(.5f, .25f), Offset(.25f, .75f), Offset(.75f, .75f))
    else -> listOf(Offset(.25f, .25f), Offset(.75f, .25f), Offset(.25f, .75f), Offset(.75f, .75f))
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
internal fun folderInsertionIndex(point: Offset, grid: Rect, rowHeight: Float, count: Int, scroll: Float = 0f): Int {
    if (point.y >= grid.bottom) return count
    val column = ((point.x - grid.left) / (grid.width / 3)).toInt().coerceIn(0, 2)
    val row = ((point.y - grid.top + scroll) / rowHeight).toInt().coerceAtLeast(0)
    return (row * 3 + column).coerceAtMost(count)
}

internal fun folderInsertionForMember(slot: Int, sourceIndex: Int): Int =
    slot + if (sourceIndex >= 0 && slot > sourceIndex) 1 else 0

/** Preserve the dragged member's composition (and pointer ownership) as the invisible gap. */
internal fun folderDragOrder(apps: List<AppKey>, member: AppKey?, index: Int): List<AppKey?> {
    val old = apps.indexOf(member)
    val insertion = index.coerceIn(0, apps.size)
    return apps.toMutableList<AppKey?>().apply {
        if (old >= 0) removeAt(old)
        add((insertion - if (old in 0 until insertion) 1 else 0).coerceIn(0, size), member)
    }
}

/** The closed preview uses the very same coordinates as FolderTile, not the whole feed cell. */
internal fun folderMemberStart(surface: Rect, large: Boolean, count: Int, index: Int, icon: Float, inset: Float): Rect {
    val size = icon * if (large) .65f else .38f
    val center = if (large) {
        val padding = inset * 1.6f
        Offset(surface.left + padding + (surface.width - 2 * padding) * (index % 3 + .5f) / 3,
            surface.top + padding + (surface.height - 2 * padding) * (index / 3 + .5f) / 3)
    } else {
        val normalized = folderPreviewCenters(count).getOrNull(index) ?: Offset(.5f, .5f)
        Offset(surface.left + inset + (surface.width - 2 * inset) * normalized.x,
            surface.top + inset + (surface.height - 2 * inset) * normalized.y)
    }
    return Rect(center - Offset(size / 2, size / 2), Size(size, size))
}
