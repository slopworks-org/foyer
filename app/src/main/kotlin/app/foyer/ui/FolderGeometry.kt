package app.foyer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import app.foyer.data.FolderContent

/** One panel registration; hit geometry deliberately stays independent of render geometry. */
internal class FolderPanelGeometry(val id: Long) {
    var bounds: Rect? = null
    var grid: Rect? = null
    var hitGrid: Rect? = null
    var columns = 2
    var rowHeight = 1f
    var scroll = 0f
    var viewport by androidx.compose.runtime.mutableStateOf<Rect?>(null)
}

internal val LARGE_FOLDER_INSET = 12.dp
internal const val LARGE_FOLDER_ICON_SCALE = .70f

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

/** The closed preview uses the very same coordinates as FolderTile, not the whole feed cell. */
internal fun folderMemberStart(surface: Rect, large: Boolean, count: Int, index: Int, icon: Float, inset: Float): Rect {
    val size = icon * if (large) LARGE_FOLDER_ICON_SCALE else .38f
    val normalized = folderPreviewMembers(count, large).find { it.index == index }?.center ?: Offset(.5f, .5f)
    val center = if (large) {
        val padding = inset
        largeFolderCell(surface, index, padding).center
    } else {
        Offset(surface.left + inset + (surface.width - 2 * inset) * normalized.x,
            surface.top + inset + (surface.height - 2 * inset) * normalized.y)
    }
    return Rect(center - Offset(size / 2, size / 2), Size(size, size))
}

/** Resolve an occurrence to its physical closed slot, including sparse large folders. */
internal fun closedFolderSlot(folder: FolderContent, entryId: String?, surface: Rect, icon: Float, inset: Float): Rect {
    val entry = folder.entries.indexOfFirst { it.id == entryId }.coerceAtLeast(0)
    val slot = if (folder.large) folder.entries.getOrNull(entry)?.slot ?: entry else entry
    return folderMemberStart(surface, folder.large, if (folder.large) 9 else folder.entries.size, slot, icon, inset)
}

internal fun FolderPanelGeometry.memberFrame(index: Int, icon: Float, topPadding: Float): Rect? =
    grid?.let { folderGridIcon(it, index, rowHeight, icon, topPadding, scroll, columns) }

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

/** Fixed cell geometry shared by hit testing, landing centers and the visible rim. */
internal fun largeFolderCell(surface: Rect, slot: Int, inset: Float): Rect {
    val grid = surface.deflate(inset)
    val size = Size(grid.width / 3, grid.height / 3)
    return Rect(Offset(grid.left + size.width * (slot % 3), grid.top + size.height * (slot / 3)), size)
}

/** Leave the 4dp gutter, 1.5dp stroke and 2dp breathing room visible around opaque artwork. */
internal fun largeFolderGhostScale(cell: Rect, icon: Float, clearance: Float): Float =
    ((minOf(cell.width, cell.height) - clearance * 2).coerceAtLeast(0f) / icon)
        .coerceAtMost(LARGE_FOLDER_ICON_SCALE)

/** Absolute large-folder cells share the rendered grid's inset and never compress holes. */
internal fun largeFolderSlot(point: Offset, surface: Rect, inset: Float): Int {
    val grid = surface.deflate(inset)
    val cell = largeFolderCell(surface, 0, inset)
    val column = ((point.x - grid.left) / cell.width).toInt().coerceIn(0, 2)
    val row = ((point.y - grid.top) / cell.height).toInt().coerceIn(0, 2)
    return row * 3 + column
}

/** 180ms closure followed immediately by 160ms growth; no persisted singleton state. */
internal const val COLLAPSE_CLOSE_FRACTION = 180f / 340f
internal fun collapseClose(progress: Float): Float = androidx.compose.animation.core.FastOutSlowInEasing
    .transform((progress / COLLAPSE_CLOSE_FRACTION).coerceIn(0f, 1f))
internal fun collapseGrow(progress: Float): Float = androidx.compose.animation.core.FastOutSlowInEasing
    .transform(((progress - COLLAPSE_CLOSE_FRACTION) / (1f - COLLAPSE_CLOSE_FRACTION)).coerceIn(0f, 1f))

/** Incremental interpolation follows a live destination without recomputing from a stale origin.
 * A destination change never moves the current frame at zero elapsed time. Crossing the phase
 * boundary explicitly visits the centered singleton, even when a slow frame spans both phases.
 */
internal fun advanceCollapseFrame(frame: Rect, destination: Rect, previous: Float, next: Float,
    icon: Float, surface: Boolean = false): Rect {
    fun advance(from: Rect, target: Rect, before: Float, after: Float): Rect =
        if (after <= before) from else folderMemberFrame(from, target,
            ((after - before) / (1f - before).coerceAtLeast(.000001f)).coerceIn(0f, 1f))
    val singleton = if (surface) destination else folderMemberStart(destination, false, 1, 0, icon, 0f)
    val closed = advance(frame, singleton, collapseClose(previous), collapseClose(next))
    return advance(closed, destination, collapseGrow(previous), collapseGrow(next))
}

/** Resulting cell and order, including sparse absolute slots and repeated app occurrences. */
internal data class FolderDropPreview(val folderId: Long, val folder: FolderContent, val entryId: String, val slot: Int,
    val standalone: app.foyer.data.HomeItem? = null) {
    val panelOrder get() = folder.entries.map { if (it.id == entryId) null else it }
}

internal fun folderDropPreview(id: Long, folder: FolderContent, entryId: String): FolderDropPreview? {
    val slot = if (folder.large) folder.slotEntries.indexOfFirst { it?.id == entryId }
        else folder.entries.indexOfFirst { it.id == entryId }
    return if (slot < 0) null else FolderDropPreview(id, folder, entryId, slot)
}
