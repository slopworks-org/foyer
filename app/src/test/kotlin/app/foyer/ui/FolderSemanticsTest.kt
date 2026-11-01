package app.foyer.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import app.foyer.data.Edits
import app.foyer.data.FolderContent
import app.foyer.data.HomeLayout
import org.junit.Assert.*
import org.junit.Test

class FolderSemanticsTest {
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
        var layout = Edits.add(HomeLayout(), FolderContent(), 0, 0)
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
