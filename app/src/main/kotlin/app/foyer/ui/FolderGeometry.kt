package app.foyer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

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
