package app.foyer.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import app.foyer.data.Edits
import app.foyer.data.FolderContent
import app.foyer.data.HomeLayout
import app.foyer.data.WidgetContent
import org.junit.Assert.*
import org.junit.Test

class FolderSemanticsTest {
    @Test fun itemMenusUseTheLargeFolderSurfaceAndKeepCompactIconAnchors() {
        val cell = Rect(0f, 0f, 160f, 240f)
        val surface = Rect(6f, 46f, 154f, 194f)
        fun menu(large: Boolean, measured: Rect?) =
            itemMenuSpec(FolderContent(large = large), cell, measured, emptyList(), emptyList())

        val large = menu(true, surface)
        assertFalse(large.onIcon)
        assertEquals(surface, large.anchor)
        assertEquals(cell, menu(true, null).anchor)
        val compact = menu(false, surface)
        assertTrue(compact.onIcon)
        assertEquals(cell, compact.anchor)
        val widget = itemMenuSpec(WidgetContent(1, "provider"), cell, surface, emptyList(), emptyList())
        assertFalse(widget.onIcon)
        assertEquals(cell, widget.anchor)
    }

    @Test fun largeFolderHasMenuButNoOpenAction() {
        var menu = false
        val semantics = SemanticsConfiguration().apply {
            folderActions("Remove", null, { menu = true }, {})
        }
        assertFalse(semantics.contains(SemanticsActions.OnClick))
        assertTrue(semantics[SemanticsActions.OnLongClick].action!!.invoke())
        assertTrue(menu)
    }

    @Test fun semanticLongPressOpensMenuAndRemoveDeletesFolder() {
        var layout = Edits.add(HomeLayout(), FolderContent(listOf(app.foyer.data.AppKey("pkg", "Main", 0))), 0, 0)
        val id = layout.items.single().id
        var opened = false
        var menu = false
        val semantics = SemanticsConfiguration().apply {
            folderActions("Remove", { opened = true }, { menu = true }, { layout = Edits.remove(layout, id) })
        }
        assertTrue(semantics[SemanticsActions.OnLongClick].action!!.invoke())
        assertTrue(menu)
        assertFalse(opened)
        val remove = semantics[SemanticsActions.CustomActions].single()
        assertEquals("Remove", remove.label)
        assertTrue(remove.action())
        assertTrue(layout.items.isEmpty())
    }
}
