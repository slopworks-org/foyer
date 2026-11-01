package app.foyer.ui

import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.foyer.MainActivity
import app.foyer.data.*
import app.foyer.graph
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Restores the exact model through LayoutStore even when a visual assertion fails. */
class FolderDropTargetTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun plannedCellsRenderAndCommitByOccurrence() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        var light by mutableStateOf(false)
        var validationGate: kotlinx.coroutines.CompletableDeferred<Set<AppKey>>? = null
        lateinit var state: LauncherState
        rule.waitUntil(10000) { graph.apps.apps.value.isNotEmpty() }
        val app = graph.apps.apps.value.first()
        try {
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                state = remember { LauncherState(graph, scope, rule.activity) { keys ->
                    validationGate?.await() ?: graph.apps.missingFolderApps(keys)
                } }
                val configuration = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (light) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
                }
                CompositionLocalProvider(LocalConfiguration provides configuration) { FoyerRoot(state) }
            }
            rule.mainClock.advanceTimeBy(800)
            rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            fun folder() = graph.layout.layout.value.items.first { it.id == 1L }.content as FolderContent
            fun fixture(count: Int, large: Boolean = false) {
                rule.runOnIdle {
                    state.cancelDrag(); state.folderLanding?.let(state::finishLanding); state.openFolder = null
                    graph.layout.edit { Edits.add(HomeLayout(seeded = true),
                        FolderContent(entries = (0 until count).map { FolderEntry(app.key, "e$it", if (large) { if (count == 2) it * 7 else it } else null) }, large = large), 1, 2) }
                }
                rule.mainClock.advanceTimeBy(800)
                rule.waitForIdle()
            }
            fun capture(name: String) {
                for (isLight in listOf(false, true)) {
                    rule.runOnIdle { light = isLight }
                    rule.mainClock.advanceTimeBy(800)
                    rule.waitForIdle()
                    val file = File(rule.activity.getExternalFilesDir(null), "drop-$name-${if (isLight) "light" else "dark"}.png")
                    // Allow SurfaceFlinger to latch the themed frame; the Compose clock is paused.
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    android.os.SystemClock.sleep(100)
                    val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    screenshot.recycle()
                }
            }
            fun preview(): FolderDropPreview = rule.runOnIdle { state.folderDropPreview(state.drag!!)!! }
            fun marker(p: FolderDropPreview) = rule.onNodeWithTag("folder-drop-1-${p.slot}", useUnmergedTree = true)
            for (count in listOf(2, 4, 24)) {
                fixture(count)
                rule.runOnIdle { state.openFolder = 1 }
                rule.mainClock.advanceTimeBy(800)
                rule.waitForIdle()
                if (count == 24) rule.onNodeWithTag("folder-grid-1", useUnmergedTree = true).performScrollToIndex(23)
                rule.runOnIdle {
                    val grid = state.folderHitGridBounds!!
                    state.startDrag(DragSession(DragSource.New(AppContent(app.key)), app.icon, Offset.Zero, Offset(grid.right - 2, grid.bottom + 8)))
                }
                rule.mainClock.advanceTimeBy(800)
                rule.waitForIdle()
                val p = preview()
                if (count == 4) assertEquals(2, p.slot)
                marker(p).assertExists()
                val destinationFrame = marker(p).fetchSemanticsNode().boundsInRoot
                capture("external-$count")
                rule.runOnIdle { state.endDrag() }
                rule.waitUntil(10000) { rule.mainClock.advanceTimeBy(100); state.drag == null }
                rule.mainClock.advanceTimeBy(800)
                rule.waitForIdle()
                val incoming = folder().entries.single { it.id !in (0 until count).map { i -> "e$i" } }
                assertEquals(p.slot, folder().entries.indexOfFirst { it.id == incoming.id })
                val committedFrame = rule.runOnIdle { state.panelMemberBounds.getValue(1L to incoming.id) }
                assertEquals(destinationFrame.center.x, committedFrame.center.x, 1.1f)
                assertEquals(destinationFrame.top, committedFrame.top, 1.1f)
                rule.onAllNodesWithTag("folder-drop-1-${p.slot}", useUnmergedTree = true).assertCountEquals(0)
            }
            for (large in listOf(false, true)) {
                fixture(0, large)
                if (!large) rule.runOnIdle { state.openFolder = 1 }
                rule.mainClock.advanceTimeBy(800)
                rule.runOnIdle {
                    val point = if (large) state.folderSurfaceBounds.getValue(1).center else state.folderHitGridBounds!!.center
                    state.startDrag(DragSession(DragSource.New(AppContent(app.key)), app.icon, Offset.Zero, point))
                }
                rule.mainClock.advanceTimeBy(800)
                val p = preview()
                assertEquals(AppContent(app.key), p.standalone?.content)
                val markerBounds = rule.onNodeWithTag("folder-drop-outline-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val expected = rule.runOnIdle { state.gridCoords!!.localToRoot(Offset(state.cellW, state.cellH * 2)) }
                assertEquals(expected.x, markerBounds.left, 1.1f)
                assertEquals(expected.y, markerBounds.top, 1.1f)
                assertEquals(state.cellW, markerBounds.width, 1.1f)
                capture("empty-${if (large) "large" else "small"}")
                rule.runOnIdle { state.endDrag() }
                rule.waitUntil(10000) { rule.mainClock.advanceTimeBy(100); state.drag == null }
                assertEquals(AppContent(app.key), graph.layout.layout.value.items.single().content)
            }
            for (outcome in listOf("accept", "reject", "cancel")) {
                fixture(24)
                rule.runOnIdle { state.openFolder = 1 }
                rule.mainClock.advanceTimeBy(800)
                val before = graph.layout.layout.value
                val gate = kotlinx.coroutines.CompletableDeferred<Set<AppKey>>()
                rule.runOnIdle {
                    validationGate = gate
                    val grid = state.folderGridBounds!!
                    state.startDrag(DragSession(DragSource.New(AppContent(app.key)), app.icon, Offset.Zero,
                        Offset(grid.center.x, grid.bottom - 3f)))
                    state.endDrag()
                }
                rule.mainClock.advanceTimeBy(32)
                val pending = rule.runOnIdle { state.folderDropPreview(state.drag!!, includeReleased = true)!! }
                val releaseScroll = state.folderGridScroll
                rule.mainClock.advanceTimeBy(600)
                rule.runOnIdle {
                    assertEquals(releaseScroll, state.folderGridScroll, .1f)
                    assertSame(pending, state.folderDropPreview(state.drag!!, includeReleased = true))
                    if (outcome == "cancel") state.cancelDrag()
                    gate.complete(if (outcome == "reject") setOf(app.key) else emptySet())
                    validationGate = null
                }
                rule.waitUntil(10000) { rule.mainClock.advanceTimeBy(100); state.drag == null }
                if (outcome == "accept") {
                    val incoming = folder().entries.single { it.id !in (0 until 24).map { i -> "e$i" } }
                    assertEquals(pending.slot, folder().entries.indexOf(incoming))
                } else assertEquals(before, graph.layout.layout.value)
            }
            fixture(4)
            rule.runOnIdle { state.openFolder = 1 }
            rule.mainClock.advanceTimeBy(800)
            rule.waitForIdle()
            rule.runOnIdle {
                val grid = state.folderHitGridBounds!!
                state.startDrag(DragSession(DragSource.Member(1, app.key, "e0"), app.icon, Offset.Zero, Offset(grid.right - 2, grid.bottom - 2)))
            }
            rule.mainClock.advanceTimeBy(800)
            rule.waitForIdle()
            val reordered = preview()
            marker(reordered).assertExists(); capture("reorder")
            val held = rule.runOnIdle { state.drag!!.pointer }
            rule.runOnIdle { state.moveDrag(Offset(state.rootCoords!!.size.width / 2f, state.rootCoords!!.size.height - 20f)) }
            rule.mainClock.advanceTimeBy(800)
            assertNull(rule.runOnIdle { state.folderDropPreview(state.drag!!) })
            capture("panel-exit")
            rule.runOnIdle { state.moveDrag(held) }
            rule.mainClock.advanceTimeBy(800)
            marker(preview()).assertExists()
            rule.runOnIdle { state.endDrag() }
            rule.waitUntil(10000) { rule.mainClock.advanceTimeBy(100); state.drag == null }
            assertEquals("e0", folder().entries[reordered.slot].id)
            for (slot in listOf(4, 7)) {
                fixture(2, true)
                rule.runOnIdle {
                    val surface = state.folderSurfaceBounds.getValue(1)
                    val grid = surface.deflate(state.folderInsetPx)
                    val point = Offset(grid.left + grid.width * (slot % 3 + .5f) / 3, grid.top + grid.height * (slot / 3 + .5f) / 3)
                    state.startDrag(DragSession(DragSource.Member(1, app.key, "e0"), app.icon, Offset.Zero, point))
                }
                rule.mainClock.advanceTimeBy(800)
                rule.waitForIdle()
                val p = preview(); assertEquals(slot, p.slot); marker(p).assertExists(); capture("large-$slot")
                rule.runOnIdle { state.endDrag() }
                rule.waitUntil(10000) { rule.mainClock.advanceTimeBy(100); state.drag == null }
                assertEquals("e0", folder().slotEntries[slot]?.id)
            }
            fixture(2, true)
            rule.runOnIdle {
                graph.layout.edit { Edits.add(it, FolderContent(entries = listOf(
                    FolderEntry(app.key, "overlap-0"), FolderEntry(app.key, "overlap-1"))), 3, 2) }
                state.openFolder = 2
            }
            rule.mainClock.advanceTimeBy(800)
            rule.runOnIdle {
                val panel = state.folderPanelBounds!!
                val surface = state.folderSurfaceBounds.getValue(1)
                val exposed = (0..8).filter { folder().slotEntries[it] == null }.flatMap { slot ->
                    val cell = largeFolderCell(surface, slot, state.folderInsetPx)
                    if (!cell.overlaps(panel)) emptyList() else listOf(.1f, .5f, .9f).flatMap { x ->
                        listOf(.1f, .5f, .9f).map { y -> Offset(cell.left + cell.width * x, cell.top + cell.height * y) }
                    }.filterNot(panel::contains)
                }.first()
                state.startDrag(DragSession(DragSource.Member(2, app.key, "overlap-0"), app.icon, Offset.Zero, exposed))
            }
            rule.mainClock.advanceTimeBy(800)
            assertEquals(1L, preview().folderId)
            capture("large-behind-panel")
            // Contrast over artwork unlike Calendar, centered and offset within the same slot.
            val solidApp = graph.apps.apps.value.first { it.key != app.key }
            fixture(2, true)
            for (offset in listOf(0f, .28f)) {
                rule.runOnIdle {
                    state.cancelDrag(); state.folderLanding?.let(state::finishLanding)
                    val cell = largeFolderCell(state.folderSurfaceBounds.getValue(1), 4, state.folderInsetPx)
                    state.startDrag(DragSession(DragSource.New(AppContent(solidApp.key)), solidApp.icon,
                        Offset.Zero, cell.center + Offset(cell.width * offset, 0f)))
                }
                rule.mainClock.advanceTimeBy(800)
                marker(preview()).assertExists()
                capture("large-artwork-${if (offset == 0f) "center" else "offset"}")
            }
            fixture(2, true)
            rule.runOnIdle {
                graph.layout.edit { Edits.add(it, AppContent(app.key), 0, 30) }
            }
            rule.mainClock.advanceTimeBy(800)
            rule.runOnIdle {
                val surface = state.folderSurfaceBounds.getValue(1)
                val cell = largeFolderCell(surface, 1, state.folderInsetPx)
                val top = state.viewportCoords!!.boundsInRoot().top
                state.feedScroll.dispatchRawDelta(cell.center.y - top)
            }
            rule.mainClock.advanceTimeBy(800)
            rule.runOnIdle {
                val surface = state.folderSurfaceBounds.getValue(1)
                val cell = largeFolderCell(surface, 1, state.folderInsetPx)
                val top = state.viewportCoords!!.boundsInRoot().top
                assertTrue(cell.top < top)
                assertTrue(cell.bottom > top)
                state.startDrag(DragSession(DragSource.New(AppContent(solidApp.key)), solidApp.icon,
                    Offset(0f, -600f), Offset(cell.center.x, top + 603f)))
            }
            // Keep the pointer outside the auto-scroll zone while its icon anchor targets the clipped cell.
            rule.mainClock.advanceTimeBy(800)
            assertEquals(1, preview().slot)
            capture("large-clipped-top")
            assertEquals(1, preview().slot)
            rule.runOnIdle {
                state.cancelDrag(); state.folderLanding?.let(state::finishLanding)
                state.feedScroll.dispatchRawDelta(-state.feedScroll.value.toFloat())
            }
            fixture(2, true)
            rule.runOnIdle {
                val grid = state.folderSurfaceBounds.getValue(1).deflate(state.folderInsetPx)
                state.startDrag(DragSession(DragSource.New(AppContent(app.key)), app.icon, Offset.Zero,
                    Offset(grid.left + grid.width / 6, grid.top + grid.height / 6)))
            }
            rule.mainClock.advanceTimeBy(800)
            rule.waitForIdle()
            assertNull(rule.runOnIdle { state.folderDropPreview(state.drag!!) })
            capture("large-rejected")
            rule.runOnIdle { state.cancelDrag() }
            rule.mainClock.advanceTimeBy(800)
            rule.waitForIdle()
            rule.onAllNodesWithTag("folder-drop-1-0", useUnmergedTree = true).assertCountEquals(0)
            fixture(9, true)
            rule.runOnIdle {
                state.startDrag(DragSession(DragSource.New(AppContent(app.key)), app.icon, Offset.Zero,
                    state.folderSurfaceBounds.getValue(1).center))
            }
            rule.mainClock.advanceTimeBy(800)
            assertNull(rule.runOnIdle { state.folderDropPreview(state.drag!!) })
            capture("large-full-rejected")
            fixture(2, true)
            rule.runOnIdle {
                state.startDrag(DragSession(DragSource.New(AppContent(app.key)), app.icon, Offset.Zero,
                    state.folderSurfaceBounds.getValue(1).center))
            }
            rule.mainClock.advanceTimeBy(800)
            marker(preview()).assertExists()
            rule.runOnIdle { state.moveDrag(Offset(state.rootCoords!!.size.width / 2f, state.rootCoords!!.size.height - 20f)) }
            rule.mainClock.advanceTimeBy(800)
            assertNull(rule.runOnIdle { state.folderDropPreview(state.drag!!) })
            capture("dock-exit")
            fixture(2)
            rule.runOnIdle {
                graph.layout.edit { Edits.add(it, FolderContent(entries = listOf(FolderEntry(app.key, "source-0"), FolderEntry(app.key, "source-1"))), 3, 2) }
                state.openFolder = 1
            }
            rule.mainClock.advanceTimeBy(800)
            rule.runOnIdle {
                state.startDrag(DragSession(DragSource.Member(2, app.key, "source-0"), app.icon, Offset.Zero,
                    state.folderHitGridBounds!!.center))
            }
            rule.mainClock.advanceTimeBy(800)
            val crossFolder = preview()
            marker(crossFolder).assertExists(); capture("cross-folder")
            rule.runOnIdle { state.endDrag() }
            rule.waitUntil(10000) { rule.mainClock.advanceTimeBy(100); state.drag == null }
            assertEquals("source-0", folder().entries[crossFolder.slot].id)
            assertTrue(graph.layout.layout.value.items.first { it.id == 2L }.content is AppContent)
            rule.mainClock.advanceTimeBy(800)
            capture("cleanup")
        } finally {
            rule.runOnUiThread { state.cancelDrag(); state.folderLanding?.let(state::finishLanding); graph.layout.edit { saved } }
            rule.mainClock.advanceTimeBy(800)
            rule.waitForIdle()
            assertEquals(saved, graph.layout.layout.value)
        }
    }
}
