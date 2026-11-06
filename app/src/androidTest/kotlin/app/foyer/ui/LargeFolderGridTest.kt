package app.foyer.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
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

class LargeFolderGridTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun rtlKeepsAbsoluteSparseSlots() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        lateinit var state: LauncherState
        rule.waitUntil(10000) { graph.apps.apps.value.size >= 2 }
        val apps = graph.apps.apps.value.take(2).map { it.key }
        try {
            graph.layout.edit { Edits.add(HomeLayout(seeded = true),
                FolderContent(apps, true, slots = listOf(apps[0], null, null, null, null, null, null, null, apps[1])), 1, 1) }
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                state = remember { LauncherState(graph, scope, rule.activity) }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { FoyerRoot(state) }
            }
            rule.waitForIdle()
            rule.runOnIdle {
                val surface = state.folderSurfaceBounds.getValue(1)
                val first = state.memberBounds.getValue(1L to state.testEntryId(1L, apps[0])).center
                val last = state.memberBounds.getValue(1L to state.testEntryId(1L, apps[1])).center
                assertTrue(first.x < surface.center.x)
                assertTrue(last.x > surface.center.x)
                assertEquals(with(rule.density) { LARGE_FOLDER_INSET.toPx() }, state.folderInsetPx, .01f)
                for ((slot, point) in listOf(0 to first, 8 to last)) {
                    val landing = folderMemberStart(surface, true, 9, slot,
                        with(rule.density) { state.iconSize.toPx() }, state.folderInsetPx)
                    assertEquals(point.x, landing.center.x, 1.1f)
                    assertEquals(point.y, landing.center.y, 1.1f)
                    assertEquals(slot, largeFolderSlot(landing.center, surface, state.folderInsetPx))
                }
                for (slot in 0..8) {
                    val cell = largeFolderCell(surface, slot, state.folderInsetPx)
                    val frame = cell.deflate(with(rule.density) { 4.dp.toPx() })
                    assertEquals(cell.center, frame.center)
                    assertTrue(cell.contains(frame.topLeft) && cell.contains(frame.bottomRight))
                    for ((identity, artwork) in state.memberBounds) {
                        if (identity.first == 1L && !cell.contains(artwork.center)) assertFalse(frame.overlaps(artwork))
                    }
                }
                assertEquals(0, largeFolderSlot(first, surface, state.folderInsetPx))
                assertEquals(8, largeFolderSlot(last, surface, state.folderInsetPx))
                val emptyTopRight = Offset(last.x, first.y)
                val session = DragSession(DragSource.New(AppContent(apps[1])), null, Offset.Zero, emptyTopRight)
                assertEquals(DropTarget.Folder(1, 2), state.targetFor(session))
            }
        } finally { rule.runOnIdle { graph.layout.edit { saved } } }
    }

    @Test fun squareSlotsAndFolderActionsAtCapacity() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        lateinit var state: LauncherState
        var launched: AppKey? = null
        rule.waitUntil(10000) { graph.apps.apps.value.size >= 11 }
        val apps = graph.apps.apps.value.map { it.key }
        val directory = File(rule.activity.getExternalFilesDir(null), "large-grid").apply { mkdirs() }
        fun shot(name: String) {
            rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        rule.activity.setContent {
            val scope = rememberCoroutineScope()
            state = remember { LauncherState(graph, scope, object : LauncherActions by rule.activity {
                override fun launchApp(app: app.foyer.data.AppInfo, bounds: android.graphics.Rect?) {
                    launched = app.key
                    rule.activity.launchApp(app, bounds)
                }
            }) }
            FoyerRoot(state)
        }
        rule.waitForIdle()
        fun folder() = graph.layout.layout.value.items.first { it.id == 1L }.content as FolderContent
        try {
            for (count in 7..9) {
                rule.runOnIdle {
                    state.menu = null
                    graph.layout.edit { Edits.add(HomeLayout(seeded = true), FolderContent(apps.take(count), true, "Must disappear"), 1, 1) }
                }
                rule.waitUntil { state.memberBounds.size == count && state.folderSurfaceBounds[1] != null }
                rule.waitForIdle()
                val surface = state.folderSurfaceBounds.getValue(1)
                val footprint = graph.layout.layout.value.items.single()
                assertEquals(2, footprint.w)
                assertEquals(2, footprint.h)
                assertEquals(surface.width, surface.height, 1f)
                assertEquals("", folder().name)
                File(directory, "dimensions-$count.txt").writeText("surface=$surface; footprint=${footprint.w}x${footprint.h}; cells=${state.cellW}x${state.cellH}")
                shot("$count-apps-square")
                // Actual pointer long press on a member also discovers the full folder menu.
                val member = state.memberBounds.getValue(1L to state.testEntryId(1L, apps[count - 1]))
                rule.onRoot().performTouchInput { longClick(member.center) }
                rule.waitForIdle()
                assertTrue(state.menu!!.actions.any { it.label == R.string.folder_shrink })
                shot("$count-apps-member-menu")
                rule.runOnIdle { state.menu = null }
                // The reserved outer gutter independently owns the folder gesture.
                rule.onRoot().performTouchInput { longClick(Offset(surface.center.x, surface.top + 3f)) }
                rule.waitForIdle()
                assertTrue(state.menu!!.actions.any { it.label == R.string.folder_shrink })
                shot("$count-apps-gutter-menu")
                rule.runOnIdle { state.menu = null }
                // Tap background and attempt the indirect state route: neither can open a panel.
                rule.onRoot().performTouchInput { click(Offset(surface.center.x, surface.top + 3f)) }
                rule.runOnIdle { state.openFolder = 1 }
                rule.waitForIdle()
                assertNull(state.openFolder)
                assertNull(state.folderPanelBounds)
                rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
                    // The folder background itself must have no open action.
                    if (node.boundsInRoot == surface && node.config.contains(SemanticsActions.OnLongClick)) {
                        assertFalse(node.config.contains(SemanticsActions.OnClick))
                    }
                }
            }
            rule.runOnIdle {
                val member = DragSession(DragSource.Member(1, apps[8], state.testEntryId(1, apps[8])), null, Offset.Zero, Offset.Zero)
                assertNull(state.plan(member, DropTarget.Folder(1, 9)))
                val external = DragSession(DragSource.New(AppContent(apps[9])), null, Offset.Zero, Offset.Zero)
                assertNull(state.plan(external, DropTarget.Folder(1)))
            }
            // The member accessibility route also exposes the same full folder menu at nine.
            val label = graph.apps.apps.value.first { it.key == apps[8] }.label
            val memberActions = rule.onNodeWithContentDescription(label, useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            rule.runOnIdle {
                assertTrue(memberActions.first { it.label == rule.activity.getString(R.string.folder_actions) }.action())
            }
            rule.runOnIdle {
                state.menu!!.actions.first { it.label == R.string.folder_shrink }.onClick()
                state.menu = null
            }
            rule.waitForIdle()
            assertFalse(folder().large)
            assertEquals(9, folder().apps.size)
            shot("nine-apps-shrunk")
            rule.runOnIdle { graph.layout.editChange { Edits.folderChange(it, 1, folder().copy(large = true)) } }
            rule.waitForIdle()
            // Fixed empty-cell targeting, source preservation, swapping and reload through LayoutStore.
            rule.runOnIdle {
                graph.layout.editChange { Edits.reconcileFolderAppsChange(it, setOf(apps[1], apps[6])) }
            }
            rule.waitForIdle()
            shot("nine-with-holes")
            val source = state.memberBounds.getValue(1L to state.testEntryId(1L, apps[2])).center
            val surface = state.folderSurfaceBounds.getValue(1)
            val empty = Offset(surface.center.x, surface.top + state.folderInsetPx + (surface.height - 2 * state.folderInsetPx) / 6)
            fun touchDrag(from: Offset, to: Offset) {
                rule.mainClock.autoAdvance = false
                rule.onRoot().performTouchInput { down(from) }
                rule.mainClock.advanceTimeBy(600)
                rule.onRoot().performTouchInput { advanceEventTime(600); moveTo(from + Offset(25f, 25f), 16) }
                rule.mainClock.advanceTimeBy(16)
                rule.onRoot().performTouchInput { moveTo(to, 250); up() }
                rule.mainClock.autoAdvance = true
                rule.waitUntil(10000) { state.drag == null && state.folderLanding == null }
                rule.waitForIdle()
            }
            touchDrag(source, empty)
            assertEquals(apps[2], folder().slots[1])
            assertNull(folder().slots[2])
            shot("touch-move-into-hole")
            touchDrag(state.memberBounds.getValue(1L to state.testEntryId(1L, apps[4])).center, state.memberBounds.getValue(1L to state.testEntryId(1L, apps[5])).center)
            assertEquals(apps[5], folder().slots[4])
            assertEquals(apps[4], folder().slots[5])
            shot("touch-occupied-slot-swap")
            rule.runOnIdle { graph.layout.edit { Edits.add(it, FolderContent(listOf(apps[9], apps[10])), 3, 4) } }
            rule.waitForIdle()
            touchDrag(state.memberBounds.getValue(1L to state.testEntryId(1L, apps[3])).center, state.folderBounds.getValue(2).center)
            assertNull(folder().slots[3])
            assertEquals(listOf(apps[9], apps[10], apps[3]), (graph.layout.layout.value.items.last().content as FolderContent).apps)
            shot("touch-large-to-small")
            rule.runOnIdle { state.openFolder = 2 }
            rule.waitForIdle()
            val destination = Offset(surface.left + state.folderInsetPx + (surface.width - 2 * state.folderInsetPx) / 6,
                surface.top + state.folderInsetPx + (surface.height - 2 * state.folderInsetPx) * 5 / 6)
            touchDrag(state.panelMemberBounds.getValue(2L to state.testEntryId(2L, apps[3])).center, destination)
            assertEquals(apps[3], folder().slots[6])
            assertEquals(listOf(apps[9], apps[10]), (graph.layout.layout.value.items.last().content as FolderContent).apps)
            shot("touch-small-to-large")
            val expected = folder().slots
            assertNull(expected[2]); assertNull(expected[3])
            rule.runOnIdle {
                val surface = state.folderSurfaceBounds.getValue(1)
                val point = Offset(surface.left + state.folderInsetPx + (surface.width - 2 * state.folderInsetPx) * 5 / 6,
                    surface.top + state.folderInsetPx + (surface.height - 2 * state.folderInsetPx) / 6)
                val drag = DragSession(DragSource.New(AppContent(apps[9])), null, Offset.Zero, point)
                assertEquals(DropTarget.Folder(1, 2), state.targetFor(drag))
                state.startDrag(drag)
                state.moveDrag(point)
            }
            // Hover timeout must not expand a large folder.
            Thread.sleep(700)
            rule.runOnUiThread { assertNull(state.openFolder); state.cancelDrag() }
            rule.waitUntil(5000) {
                LayoutStore(rule.activity, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)).layout.value.items
                    .firstOrNull { it.id == 1L }?.content == folder()
            }
            assertEquals(expected, folder().slots)
            rule.runOnIdle {
                val menuItem = graph.layout.layout.value.items.first()
                state.showItemMenu(menuItem, state.folderBounds.getValue(1))
                state.menu!!.actions.first { it.label == R.string.folder_shrink }.onClick()
            }
            rule.waitForIdle()
            assertFalse(folder().large)
            assertEquals(expected.filterNotNull(), folder().apps)
            shot("shrunk-row-major")
            rule.runOnIdle { state.menu = null; graph.layout.editChange { Edits.folderChange(it, 1, folder().copy(large = true)) } }
            rule.waitForIdle()
            val launchPoint = state.memberBounds.getValue(1L to state.testEntryId(1L, apps[0])).center
            rule.onRoot().performTouchInput { click(launchPoint) }
            rule.waitUntil(5000) { launched == apps[0] }
            Thread.sleep(600)
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(directory, "direct-member-launch.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            rule.runOnUiThread {
                rule.activity.startActivity(android.content.Intent(rule.activity, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        } finally {
            rule.mainClock.autoAdvance = true
            val restartFixture = InstrumentationRegistry.getArguments().getString("restartFixture") == "true"
            rule.runOnUiThread {
                state.cancelDrag(); state.menu = null; state.openFolder = null
                graph.layout.edit {
                    if (restartFixture) Edits.reconcileFolderApps(
                        Edits.add(HomeLayout(seeded = true), FolderContent(apps.take(9), true), 1, 1), setOf(apps[1], apps[6]))
                    else saved
                }
            }
            if (restartFixture) rule.waitUntil(5000) {
                LayoutStore(rule.activity, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)).layout.value == graph.layout.layout.value
            }
        }
    }
}
