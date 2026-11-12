package app.foyer.data

import org.junit.Assert.*
import org.junit.Test

class DisplacementTest {
    private fun icon(id: Long, x: Int, y: Int = 0, dock: Boolean = false) =
        HomeItem(id, AppContent(AppKey("pkg$id", "Main", 0)), x, y, dock = dock)

    @Test fun occupiedCellUsesNearestHoleInsteadOfSource() {
        val source = icon(1, 0, 2)
        val target = icon(2, 2)
        val before = HomeLayout(listOf(source, target), nextId = 3)
        val after = Edits.displace(before, source.copy(x = 2, y = 0))!!
        assertEquals(target.copy(x = 1), after.items[1])
        assertEquals(source.copy(x = 2, y = 0), after.items[0])
    }

    @Test fun widgetDisplacesIconsAndWholeWidgetsWithoutOverlap() {
        val moving = HomeItem(1, WidgetContent(10, "provider"), 0, 2, 2, 2)
        val other = HomeItem(2, WidgetContent(20, "provider"), 0, 0, 2, 1)
        val before = HomeLayout(listOf(moving, other, icon(3, 0, 1), icon(4, 1, 1)), nextId = 5)
        val after = Edits.displace(before, moving.copy(y = 0))!!
        assertEquals(before.items.map { it.id }, after.items.map { it.id })
        assertEquals(2, after.items[1].w)
        after.items.forEach { item ->
            assertTrue(after.items.none { it.id != item.id && it.overlaps(item.x, item.y, item.w, item.h) })
        }
    }

    @Test fun fullGridRejectsNewItemWithoutChangingAnything() {
        val before = HomeLayout((0 until HomeLayout.COLUMNS).map { icon(it + 1L, it) }, nextId = 10)
        assertNull(Edits.displace(before, icon(10, 0)))
        assertEquals((0 until HomeLayout.COLUMNS).toList(), before.items.map { it.x })
    }

    @Test fun fullDockRejectsIncomingFeedIcon() {
        val source = icon(10, 0)
        val before = HomeLayout((0 until HomeLayout.DOCK_SLOTS).map { icon(it + 1L, it, dock = true) } + source)
        assertNull(Edits.displace(before, source.copy(dock = true)))
    }

    @Test fun fragmentedSpaceCannotSplitDisplacedWidget() {
        val widget = HomeItem(1, WidgetContent(10, "provider"), 0, 0, 2, 1)
        val before = HomeLayout(listOf(widget, icon(2, 2), icon(3, 4)), nextId = 4)
        assertNull(Edits.displace(before, icon(4, 0)))
    }
}
