package app.foyer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import app.foyer.data.AppKey
import app.foyer.data.Edits
import app.foyer.data.FolderContent
import app.foyer.data.HomeLayout
import org.junit.Assert.*
import org.junit.Test

class FolderGeometryTest {
    @Test fun offscreenPreviewDestinationsRemainUnclippedAndReversible() {
        val grid = Rect(20f, 100f, 320f, 400f)
        val anchor = Rect(300f, 600f, 350f, 650f)
        for (index in listOf(0, 1, 3, 4)) {
            val start = folderMemberStart(anchor, false, 30, index, 50f, 5f)
            val end = folderGridIcon(grid, index, 90f, 50f, 8f, scroll = 720f)
            assertEquals(-612f + index / 3 * 90f, end.top)
            assertEquals(50f, end.width)
            assertEquals(start, folderMemberFrame(start, end, 0f))
            assertEquals(end, folderMemberFrame(start, end, 1f))
            val halfway = folderMemberFrame(start, end, .5f)
            assertEquals((start.center.y + end.center.y) / 2, halfway.center.y, .001f)
            // Direction changes use the same progress, not a new clipped destination.
            assertEquals(halfway, folderMemberFrame(start, end, .5f))
        }
    }

    @Test fun previewSlotsPreserveOpenGridCellsWithoutChangingStoredOrder() {
        val expected = listOf(emptyList(), listOf(0), listOf(0, 1), listOf(0, 1, 2),
            listOf(0, 1, 3), listOf(0, 1, 3, 4), listOf(0, 1, 3, 4))
        val surface = Rect(0f, 0f, 100f, 100f)
        for (count in 0..12) {
            val members = folderPreviewMembers(count)
            assertEquals(expected[minOf(count, 6)], members.map { it.index })
            members.forEach { (index, center) ->
                assertEquals(Offset(10 + 80 * center.x, 10 + 80 * center.y),
                    folderMemberStart(surface, false, count, index, 50f, 10f).center)
                if (count >= 4) {
                    assertEquals(index % 3, if (center.x < .5f) 0 else 1)
                    assertEquals(index / 3, if (center.y < .5f) 0 else 1)
                }
            }
            assertEquals((0 until minOf(count, 9)).toList(), folderPreviewMembers(count, true).map { it.index })
        }
    }

    @Test fun edgeScrollUsesKeyboardSizedViewportAndStopsOutsidePanelLane() {
        val grid = Rect(20f, 100f, 320f, 220f)
        assertEquals(-840f, folderEdgeScroll(Offset(100f, 100f), grid, 56f, 840f), .001f)
        assertEquals(840f, folderEdgeScroll(Offset(100f, 220f), grid, 56f, 840f), .001f)
        assertEquals(420f, folderEdgeScroll(Offset(100f, 192f), grid, 56f, 840f), .001f)
        assertEquals(0f, folderEdgeScroll(Offset(100f, 160f), grid, 56f, 840f), .001f)
        assertEquals(0f, folderEdgeScroll(Offset(10f, 215f), grid, 56f, 840f), .001f)
        assertEquals(0f, folderEdgeScroll(Offset(100f, 300f), grid, 56f, 840f), .001f)
    }

    @Test fun dockOccludesScrolledFoldersAcrossItsEntireSurface() {
        val viewport = Rect(0f, 0f, 400f, 800f)
        val dock = Rect(0f, 600f, 400f, 800f)
        val hiddenFolder = Rect(100f, 550f, 250f, 750f)
        val obscuredMember = Offset(150f, 650f)
        assertTrue(hiddenFolder.contains(obscuredMember))
        assertEquals(DropLayer.Dock, dropLayerAt(obscuredMember, viewport, null, dock))
        assertEquals(DropLayer.Dock, dropLayerAt(Offset(5f, 780f), viewport, null, dock))
        assertEquals(DropLayer.Feed, dropLayerAt(Offset(150f, 575f), viewport, null, dock))
    }

    @Test fun panelPaddingAndNameOccludeUnderlyingMembers() {
        val viewport = Rect(0f, 0f, 400f, 800f)
        val panel = Rect(20f, 200f, 380f, 550f)
        val underlyingMember = Rect(100f, 490f, 180f, 540f)
        val nameArea = Offset(140f, 520f)
        assertTrue(underlyingMember.contains(nameArea))
        assertEquals(DropLayer.Panel, dropLayerAt(nameArea, viewport, panel, null))
        assertEquals(DropLayer.Panel, dropLayerAt(nameArea, viewport, panel, Rect(0f, 500f, 400f, 800f)))
        assertEquals(DropLayer.Panel, dropLayerAt(Offset(22f, 202f), viewport, panel, null))
        assertEquals(DropLayer.Feed, dropLayerAt(Offset(140f, 560f), viewport, panel, null))
        assertNull(dropLayerAt(Offset(140f, -5f), viewport, panel, null))
    }

    @Test fun dockLiftBelongsOnlyToSearchIncludingTransitionFrames() {
        assertEquals(0f, dockKeyboardLift(300f, false, 0f, 0f))
        assertEquals(0f, dockKeyboardLift(300f, false, .1f, 0f, folderVisible = true))
        assertEquals(300f, dockKeyboardLift(300f, true, 0f, 0f, folderVisible = true))
        assertEquals(300f, dockKeyboardLift(300f, false, 0f, 1f, folderVisible = true))
        assertEquals(300f, dockKeyboardLift(300f, true, 0f, 0f))
        assertEquals(300f, dockKeyboardLift(300f, false, 0f, 1f))
        assertEquals(300f, dockKeyboardLift(300f, false, .1f, 0f))
    }

    @Test fun previewsMatchMemberCounts() {
        assertEquals(emptyList<Offset>(), folderPreviewCenters(0))
        assertEquals(listOf(Offset(.5f, .5f)), folderPreviewCenters(1))
        assertEquals(listOf(Offset(.25f, .5f), Offset(.75f, .5f)), folderPreviewCenters(2))
        assertEquals(listOf(Offset(.25f, .25f), Offset(.75f, .25f), Offset(.5f, .75f)), folderPreviewCenters(3))
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

    @Test fun morphStartsAtActualPreviewIconsIncludingTriangleAndLargeGrid() {
        val surface = Rect(100f, 200f, 200f, 300f)
        val triangle = (0..2).map { folderMemberStart(surface, false, 3, it, 100f, 10f) }
        assertEquals(listOf(Offset(130f, 230f), Offset(170f, 230f), Offset(150f, 270f)), triangle.map { it.center })
        assertTrue(triangle.all { it.width == 38f })
        val large = (0..8).map { folderMemberStart(surface, true, 9, it, 100f, 10f) }
        assertEquals(surface.center, large[4].center)
        assertEquals(65f, large[4].width)
        assertTrue(large[0].center.x < large[1].center.x && large[1].center.x < large[2].center.x)
        assertTrue(large[0].center.y < large[3].center.y && large[3].center.y < large[6].center.y)
    }

    @Test fun hoverGapAndCommittedOrderAgreeForEveryMemberAndInsertionSlot() {
        val apps = (1..9).map { AppKey("pkg$it", "Main", 0) }
        val initial = Edits.add(HomeLayout(), FolderContent(apps), 0, 0)
        for (member in apps) for (index in 0..apps.size) {
            val preview = folderDragOrder(apps, member, index)
            val committed = Edits.transferApp(initial, member, sourceFolder = 1, targetFolder = 1, index = index)
            assertEquals((committed.items.first().content as FolderContent).apps, preview)
            assertEquals(apps.toSet(), preview.toSet())
        }
        assertEquals(listOf(apps[0], null, apps[1]), folderDragOrder(apps.take(2), null, 1))
    }

    @Test fun stationarySlotsIncludePaddingAndTitleWithoutFollowingAnimatedMembers() {
        val grid = Rect(20f, 100f, 320f, 300f)
        assertEquals(0, folderInsertionIndex(Offset(0f, 90f), grid, 100f, 5))
        assertEquals(2, folderInsertionIndex(Offset(310f, 120f), grid, 100f, 5))
        assertEquals(3, folderInsertionIndex(Offset(30f, 220f), grid, 100f, 5))
        assertEquals(5, folderInsertionIndex(Offset(150f, 350f), grid, 100f, 5))
        assertEquals(0, folderInsertionIndex(grid.center, grid, 100f, 0))
        assertEquals(7, folderInsertionIndex(Offset(150f, 120f), grid, 100f, 20, scroll = 200f))
        assertEquals(3, folderInsertionForMember(2, 0))
        assertEquals(0, folderInsertionForMember(0, 2))
        assertEquals(1, folderInsertionForMember(1, -1))
    }
}
