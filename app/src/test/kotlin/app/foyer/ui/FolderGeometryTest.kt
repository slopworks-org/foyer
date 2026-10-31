package app.foyer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class FolderGeometryTest {
    @Test fun previewsMatchMemberCounts() {
        assertEquals(emptyList<Offset>(), folderPreviewCenters(0))
        assertEquals(listOf(Offset(.5f, .5f)), folderPreviewCenters(1))
        assertEquals(listOf(Offset(.25f, .5f), Offset(.75f, .5f)), folderPreviewCenters(2))
        assertEquals(listOf(Offset(.5f, .25f), Offset(.25f, .75f), Offset(.75f, .75f)), folderPreviewCenters(3))
        assertEquals(listOf(Offset(.25f, .25f), Offset(.75f, .25f), Offset(.25f, .75f), Offset(.75f, .75f)), folderPreviewCenters(4))
        assertEquals(folderPreviewCenters(4), folderPreviewCenters(20))
    }

    @Test fun panelFollowsAnchorAndClampsAtEveryEdge() {
        val viewport = Rect(12f, 24f, 388f, 600f)
        for (point in listOf(Offset(0f, 0f), Offset(400f, 0f), Offset(0f, 800f), Offset(400f, 800f))) {
            val panel = folderPanelRect(Rect(point, Size(50f, 50f)), viewport, Size(300f, 240f))
            assertTrue(panel.left >= viewport.left && panel.right <= viewport.right)
            assertTrue(panel.top >= viewport.top && panel.bottom <= viewport.bottom)
        }
        val upper = folderPanelRect(Rect(100f, 100f, 150f, 150f), viewport, Size(200f, 100f))
        val lower = folderPanelRect(Rect(100f, 400f, 150f, 450f), viewport, Size(200f, 100f))
        assertEquals(300f, lower.top - upper.top)
    }

    @Test fun keyboardAndLongContentsFitVisibleArea() {
        val viewport = Rect(12f, 24f, 388f, 260f)
        assertEquals(viewport, folderPanelRect(Rect(300f, 500f, 350f, 550f), viewport, Size(500f, 2000f)))
    }
}
