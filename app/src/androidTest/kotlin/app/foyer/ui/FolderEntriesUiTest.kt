package app.foyer.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.foyer.MainActivity
import app.foyer.R
import app.foyer.data.*
import app.foyer.graph
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class FolderEntriesUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun rtlFolderUsesPhysicalPreviewPanelAndDropCoordinates() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        lateinit var state: LauncherState
        rule.waitUntil(10000) { graph.apps.apps.value.isNotEmpty() }
        val app = graph.apps.apps.value.first().key
        try {
            rule.runOnUiThread {
                graph.layout.edit { Edits.add(HomeLayout(seeded = true), FolderContent(listOf(app, app)), 0, 3) }
            }
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                state = remember { LauncherState(graph, scope, rule.activity) }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { FoyerRoot(state) }
            }
            rule.waitForIdle()
            val surface = rule.runOnIdle { state.folderSurfaceBounds.getValue(1) }
            val density = rule.activity.resources.displayMetrics.density
            for (index in 0..1) {
                val expected = folderMemberStart(surface, false, 2, index, state.iconSize.value * density, 5f * density)
                val actual = rule.onNodeWithTag("folder-preview-1-$index", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertEquals(expected.center.x, actual.center.x, 1.1f)
                assertEquals(expected.center.y, actual.center.y, 1.1f)
                assertTrue(surface.contains(actual.center))
            }
            rule.runOnIdle { state.openFolder = 1 }
            rule.waitForIdle()
            val entries = (graph.layout.layout.value.items.single().content as FolderContent).entries
            rule.runOnIdle {
                assertTrue(state.folderPanelBounds!!.contains(surface.center))
                assertEquals(state.folderHitGridBounds!!.left, state.folderGridBounds!!.left, 1.1f)
                assertEquals(state.folderHitGridBounds!!.right, state.folderGridBounds!!.right, 1.1f)
                val first = state.panelMemberBounds.getValue(1L to entries[0].id).center
                val second = state.panelMemberBounds.getValue(1L to entries[1].id).center
                assertTrue(first.x < second.x)
                val session = DragSession(DragSource.New(AppContent(app)), null, Offset.Zero, second)
                assertEquals(DropTarget.Folder(1, 1), state.targetFor(session))
                state.startDrag(session)
                state.endDrag()
            }
            rule.waitUntil(5000) { state.drag == null && state.folderLanding == null }
            val committed = (graph.layout.layout.value.items.single().content as FolderContent).entries
            assertEquals(3, committed.size)
            assertEquals(entries[0], committed[0])
            assertEquals(entries[1], committed[2])
            rule.runOnIdle {
                state.openFolder = null
                graph.layout.editChange { Edits.folderChange(it, 1, FolderContent()) }
            }
            rule.waitForIdle()
            val cell = rule.runOnIdle { state.folderBounds.getValue(1) }
            rule.mainClock.autoAdvance = false
            rule.runOnUiThread { state.startDrag(DragSession(DragSource.New(AppContent(app)), null, Offset.Zero, cell.center)) }
            rule.mainClock.advanceTimeBy(64)
            val outline = rule.onNodeWithTag("folder-drop-outline-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(cell.center.x, outline.center.x, 1.1f)
            assertEquals(cell.center.y, outline.center.y, 1.1f)
            assertEquals(cell.width, outline.width, 1.1f)
        } finally {
            rule.runOnUiThread { state.cancelDrag(); graph.layout.edit { saved } }
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun repeatedAppsHaveDistinctAccessiblePositions() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        rule.waitUntil(10000) { graph.apps.apps.value.isNotEmpty() }
        val app = graph.apps.apps.value.first()
        try {
            graph.layout.edit {
                var layout = Edits.add(HomeLayout(seeded = true), FolderContent(entries = listOf(FolderEntry(app.key), FolderEntry(app.key)), large = true), 0, 1)
                layout = Edits.add(layout, FolderContent(), 2, 1)
                layout = Edits.add(layout, AppContent(app.key), 3, 1)
                Edits.add(layout, AppContent(app.key), 4, 1)
            }
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                val state = remember { LauncherState(graph, scope, rule.activity) }
                FoyerRoot(state)
            }
            rule.waitForIdle()
            for (column in 1..2) rule.onNodeWithContentDescription(
                rule.activity.getString(R.string.folder_position_label, app.label, 1, column), useUnmergedTree = true).assertExists()
            val labels = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions), useUnmergedTree = true)
                .fetchSemanticsNodes().flatMap { it.config[SemanticsActions.CustomActions].map { action -> action.label } }
            for (column in 4..5) {
                val target = rule.activity.getString(R.string.folder_position_label, app.label, 2, column)
                assertTrue(labels.contains(rule.activity.getString(R.string.folder_combine_with, target)))
            }
            val folderName = rule.activity.getString(R.string.folder_default)
            val destinationLabels = rule.onAllNodes(hasContentDescription(app.label) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions), useUnmergedTree = true)
                .fetchSemanticsNodes().first().config[SemanticsActions.CustomActions].map { it.label }
            for (column in listOf(1, 3)) {
                val target = rule.activity.getString(R.string.folder_position_label, folderName, 2, column)
                assertTrue(destinationLabels.contains(rule.activity.getString(R.string.folder_move_to, target)))
            }
            assertFalse(destinationLabels.contains(rule.activity.getString(R.string.folder_move_to, folderName)))
        } finally { rule.runOnUiThread { graph.layout.edit { saved } } }
    }

    @Test fun stationaryPointerTracksScrolledMergeTargets() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        lateinit var state: LauncherState
        rule.waitUntil(10000) { graph.apps.apps.value.isNotEmpty() }
        val app = graph.apps.apps.value.first().key
        try {
            graph.layout.edit {
                Edits.add(Edits.add(HomeLayout(seeded = true), AppContent(app), 2, 3), AppContent(app), 0, 20)
            }
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                state = remember { LauncherState(graph, scope, rule.activity) }
                FoyerRoot(state)
            }
            rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            rule.runOnIdle {
                val point = state.appIconBounds.getValue(1).center - Offset(0f, state.cellH)
                state.startDrag(DragSession(DragSource.New(AppContent(app)), null, Offset.Zero, point))
                state.moveDrag(point)
                assertNull(state.mergeReady)
                kotlinx.coroutines.runBlocking { state.feedScroll.scrollTo(state.cellH.toInt()) }
            }
            rule.mainClock.advanceTimeBy(64)
            rule.runOnIdle {
                state.refreshDragTargets()
                assertEquals(1L, state.mergeReady)
            }
            rule.mainClock.advanceTimeBy(16)
            rule.runOnIdle {
                state.refreshDragTargets()
                assertEquals(1L, state.mergeReady)
                kotlinx.coroutines.runBlocking { state.feedScroll.scrollTo((state.cellH * 2).toInt()) }
                // Two refreshes in the release frame must not reuse pre-scroll measurements.
                repeat(2) { state.refreshDragTargets(); assertNull(state.mergeReady) }
            }
            rule.mainClock.advanceTimeBy(64)
            rule.runOnIdle {
                assertNull(state.mergeReady)
                state.cancelDrag()
            }
        } finally {
            rule.runOnUiThread { state.cancelDrag(); graph.layout.edit { saved } }
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun duplicateGesturesRemovalAndAccessibleCreation() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        lateinit var state: LauncherState
        val launched = mutableListOf<AppKey>()
        val rtl = mutableStateOf(false)
        rule.waitUntil(10000) { graph.apps.apps.value.isNotEmpty() }
        val app = graph.apps.apps.value.first().key
        val a = FolderEntry(app); val b = FolderEntry(app)
        fun folder() = graph.layout.layout.value.items.first { it.id == 1L }.content as FolderContent
        fun shot(name: String) {
            val directory = File(rule.activity.getExternalFilesDir(null), "folder-entries").apply { mkdirs() }
            rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        fun drag(from: Offset, to: Offset) {
            rule.mainClock.autoAdvance = false
            rule.onRoot().performTouchInput { down(from) }
            rule.mainClock.advanceTimeBy(620)
            rule.onRoot().performTouchInput { advanceEventTime(620); moveTo(from + Offset(35f, 35f), 16) }
            rule.mainClock.advanceTimeBy(16)
            rule.onRoot().performTouchInput { moveTo(to, 240); up() }
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
        }
        try {
            rule.runOnIdle { graph.layout.edit { Edits.add(HomeLayout(seeded = true), FolderContent(entries = listOf(a, b)), 1, 2) } }
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                state = remember { LauncherState(graph, scope, object : LauncherActions by rule.activity {
                    override fun launchApp(app: AppInfo, bounds: android.graphics.Rect?) { launched += app.key }
                }) }
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl.value) LayoutDirection.Rtl else LayoutDirection.Ltr) { FoyerRoot(state) }
            }
            rule.waitForIdle()
            rule.runOnIdle { state.openFolder = 1 }
            rule.waitForIdle()
            rule.onRoot().performTouchInput { click(state.panelMemberBounds.getValue(1L to a.id).center) }
            rule.waitForIdle()
            rule.runOnIdle { state.openFolder = 1 }
            rule.waitForIdle()
            rule.onRoot().performTouchInput { click(state.panelMemberBounds.getValue(1L to b.id).center) }
            rule.waitForIdle()
            assertEquals(listOf(app, app), launched)
            rule.runOnIdle { state.openFolder = 1 }
            rule.waitForIdle()
            drag(state.panelMemberBounds.getValue(1L to b.id).center, state.panelMemberBounds.getValue(1L to a.id).center)
            assertEquals(listOf(b.id, a.id), folder().entries.map { it.id })
            shot("10-small-duplicate-reordered")
            // Actual long press and visible Remove action consume only the selected occurrence.
            val desktopMenu = rule.runOnIdle {
                state.showItemMenu(HomeItem(99, AppContent(app), 0, 0), state.panelMemberBounds.getValue(1L to b.id))
                state.menu!!.also { state.menu = null }
            }
            rule.onRoot().performTouchInput { longClick(state.panelMemberBounds.getValue(1L to b.id).center) }
            rule.runOnIdle {
                assertEquals(desktopMenu.actions.map { it.label }, state.menu!!.actions.map { it.label })
                assertEquals(desktopMenu.shortcuts, state.menu!!.shortcuts)
                state.menu!!.actions.first { it.label == R.string.action_aliases }.onClick()
                assertEquals(app, state.aliasEditor!!.key)
                state.aliasEditor = null
            }
            rule.onNodeWithText(rule.activity.getString(R.string.action_remove)).performClick()
            rule.waitForIdle()
            assertEquals(AppContent(app), graph.layout.layout.value.items.first().content)
            shot("11-small-one-copy-removed")
            rule.runOnIdle {
                state.openFolder = null
                graph.layout.edit { Edits.add(HomeLayout(seeded = true), FolderContent(entries = listOf(a.copy(slot = 0), b.copy(slot = 8)), large = true), 0, 0) }
            }
            rule.waitForIdle()
            // Mode conversion packs by design. Moving the second copy addresses its stable ID.
            val surface = state.folderSurfaceBounds.getValue(1)
            val empty = Offset(surface.right - state.folderInsetPx - (surface.width - state.folderInsetPx * 2) / 6,
                surface.bottom - state.folderInsetPx - (surface.height - state.folderInsetPx * 2) / 6)
            drag(state.memberBounds.getValue(1L to b.id).center, empty)
            assertEquals(b.id, folder().slotEntries[8]?.id)
            assertEquals(a.id, folder().slotEntries[0]?.id)
            shot("12-large-duplicate-independent-slot")
            rule.onRoot().performTouchInput { longClick(state.memberBounds.getValue(1L to a.id).center) }
            rule.onNodeWithText(rule.activity.getString(R.string.action_remove)).performClick()
            rule.waitForIdle()
            assertEquals(b.copy(slot = 8), folder().entries.single())
            assertEquals(2, graph.layout.layout.value.items.first().w)
            assertTrue(folder().large)
            assertTrue(state.folderCollapses.isEmpty())
            shot("13-large-one-copy-removed-hole-retained")
            // Equivalent accessible creation is attached to apps, without a dedicated create UI.
            rule.runOnIdle { graph.layout.edit { Edits.add(Edits.add(HomeLayout(seeded = true), AppContent(app), 0, 0), AppContent(app), 2, 2) } }
            rule.waitForIdle()
            val label = graph.apps.apps.value.first().label
            val nodes = rule.onAllNodesWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNodes()
            val action = nodes.flatMap { it.config.getOrElse(SemanticsActions.CustomActions) { emptyList() } }
                .first { it.label.startsWith(rule.activity.getString(R.string.folder_combine_with, "").trim()) }
            rule.runOnIdle { assertTrue(action.action()) }
            rule.waitUntil { graph.layout.layout.value.items.size == 1 }
            assertEquals(listOf(app, app), (graph.layout.layout.value.items.single().content as FolderContent).apps)
            shot("14-accessible-combine-two-copies")
            rule.runOnIdle {
                rtl.value = true
                graph.layout.edit { Edits.add(HomeLayout(seeded = true), AppContent(app), 0, 2) }
            }
            rule.waitForIdle()
            var anchor = Offset.Zero
            rule.mainClock.autoAdvance = false
            rule.runOnUiThread {
                anchor = state.appIconBounds.getValue(1).center
                state.startDrag(DragSession(DragSource.New(AppContent(app)), null, Offset.Zero, anchor))
                state.moveDrag(anchor)
            }
            rule.mainClock.advanceTimeBy(700)
            rule.runOnUiThread { state.refreshDragTargets() }
            rule.waitUntil(5000) {
                rule.mainClock.advanceTimeBy(16)
                rule.runOnUiThread { state.refreshDragTargets() }
                state.mergeReady == 1L
            }
            rule.mainClock.advanceTimeBy(32)
            rule.onNodeWithTag("folder-merge-preview", useUnmergedTree = true).assertExists()
            val ghost = rule.onNodeWithTag("drag-ghost", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(anchor.x, ghost.center.x, 1f)
            assertEquals(anchor.y, ghost.center.y, 1f)
            shot("15-rtl-feedback-and-ghost-physical-coordinates")
        } finally {
            rule.runOnUiThread { state.cancelDrag(); state.openFolder = null; graph.layout.edit { saved } }
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
        }
    }
}
