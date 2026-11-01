package app.foyer.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.foyer.MainActivity
import app.foyer.data.*
import app.foyer.graph
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Uses native pointer events and the real frame clock, including the merge-intent dwell. */
class FolderEntriesRealTest {
    @Test fun creationAndDuplicateOccurrenceFilm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var state: LauncherState
        lateinit var saved: HomeLayout
        lateinit var apps: List<AppKey>
        val report = StringBuilder()
        val rtl = mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
            fun waitFor(description: String, condition: () -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 10000
                var ready = false
                while (!ready && SystemClock.uptimeMillis() < deadline) {
                    main { ready = condition() }; if (!ready) SystemClock.sleep(30)
                }
                var diagnostic = ""
                if (!ready) main { diagnostic = runCatching { "scroll=${state.feedScroll.value}, max=${state.feedScroll.maxValue}, row=${state.cellH}, target=${state.appIconBounds[1]}, pointer=${state.drag?.pointer}, dock=${state.dockSurfaceCoords?.boundsInRoot()}, viewport=${state.viewportCoords?.size}" }.getOrDefault("") }
                assertTrue("$description $diagnostic", ready)
            }
            waitFor("apps loaded") { activity.graph.apps.apps.value.size >= 10 }
            main {
                saved = activity.graph.layout.layout.value
                apps = activity.graph.apps.apps.value.map { it.key }
                activity.setContent {
                    val scope = rememberCoroutineScope()
                    state = remember { LauncherState(activity.graph, scope, activity) }
                    CompositionLocalProvider(LocalLayoutDirection provides if (rtl.value) LayoutDirection.Rtl else LayoutDirection.Ltr) { FoyerRoot(state) }
                }
            }
            SystemClock.sleep(400)
            val directory = File(activity.getExternalFilesDir(null), "folder-entries").apply { mkdirs() }
            fun shot(name: String) {
                instrumentation.uiAutomation.takeScreenshot().let { image ->
                    File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
            }
            var downTime = 0L
            fun send(action: Int, point: Offset) {
                val time = SystemClock.uptimeMillis()
                if (action == MotionEvent.ACTION_DOWN) downTime = time
                var window = Offset.Zero
                main { window = state.rootCoords!!.localToWindow(Offset.Zero) }
                val event = MotionEvent.obtain(downTime, time, action, point.x + window.x, point.y + window.y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                main { activity.dispatchTouchEvent(event) }; event.recycle()
            }
            fun begin(point: Offset) {
                send(MotionEvent.ACTION_DOWN, point); SystemClock.sleep(620)
                send(MotionEvent.ACTION_MOVE, point + Offset(35f, 35f)); SystemClock.sleep(50)
                main { assertNotNull("native drag started", state.drag) }
            }
            fun approach(anchor: Offset) {
                var start = Offset.Zero; var destination = Offset.Zero
                main { start = state.drag!!.pointer; destination = anchor - state.drag!!.anchorOffset }
                for (step in 1..15) {
                    send(MotionEvent.ACTION_MOVE, start + (destination - start) * (step / 15f))
                    SystemClock.sleep(16)
                }
            }
            fun release(cancel: Boolean = false) {
                var pointer = Offset.Zero; main { pointer = state.drag!!.pointer }
                send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, pointer)
                waitFor("drag finished") { state.drag == null }; SystemClock.sleep(500)
            }
            fun folder(id: Long = 1) = activity.graph.layout.layout.value.items.first { it.id == id }.content as FolderContent
            fun reset(layout: HomeLayout) {
                main { state.cancelDrag(); state.openFolder = null; state.menu = null; state.feedScroll.dispatchRawDelta(-state.feedScroll.value.toFloat()); activity.graph.layout.edit { layout } }
                SystemClock.sleep(500)
            }
            try {
                val placed = Edits.add(Edits.add(HomeLayout(seeded = true), AppContent(apps[0]), 2, 2), AppContent(apps[0]), 0, 0)
                reset(placed)
                var start = Offset.Zero; var destination = Offset.Zero
                main { start = state.appIconBounds.getValue(2).center; destination = state.appIconBounds.getValue(1).center }
                begin(start); approach(destination)
                main { assertEquals(1L, state.mergeCandidate); assertNull(state.mergeReady) }
                shot("01-hold-to-group")
                waitFor("merge armed") { state.mergeReady == 1L }
                SystemClock.sleep(120) // let the armed feedback reach the displayed frame
                shot("02-release-to-group"); release()
                main {
                    assertEquals(listOf(apps[0], apps[0]), folder().apps)
                    assertEquals(1, activity.graph.layout.layout.value.items.size)
                    assertEquals(2, folder().entries.map { it.id }.distinct().size)
                }
                shot("03-created-two-copies")
                main { state.openFolder = 1 }; SystemClock.sleep(600)
                shot("04-two-independent-copies")
                lateinit var first: FolderEntry; lateinit var second: FolderEntry
                main {
                    first = folder().entries[0]; second = folder().entries[1]
                    start = state.panelMemberBounds.getValue(1L to second.id).center
                    destination = state.gridCoords!!.localToRoot(Offset(state.cellW * 4.5f, state.cellH * 5.5f))
                }
                begin(start); approach(destination); release()
                main {
                    assertEquals(AppContent(first.app), activity.graph.layout.layout.value.items.first { it.id == 1L }.content)
                    assertTrue(activity.graph.layout.layout.value.items.any { it.content == AppContent(second.app) })
                }
                shot("05-second-copy-moved-out")
                report.append("Native 240ms approach + 450ms deliberate center dwell creates unnamed same-app folder at target. Second occurrence independently dragged out.\n")
                // A real search-result drag copies the app into a new folder with a desktop app.
                reset(Edits.add(HomeLayout(seeded = true), AppContent(apps[0]), 2, 2))
                var label = ""
                main { label = activity.graph.apps.apps.value.first { it.key == apps[0] }.label; state.openSearch(); state.query = label }
                SystemClock.sleep(900)
                shot("05a-search-result-before-drag")
                // The best result is bottom-aligned immediately above the search field.
                // Use its visible native accessibility node when available.
                val automation = instrumentation.uiAutomation
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
                fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.text?.toString() == label && !node.isEditable) return node
                    for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                    return null
                }
                val node = automation.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                    .firstNotNullOfOrNull { find(it.root) } ?: error("search result not accessible: $label; query=${state.query}")
                val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                main {
                    val screen = state.rootCoords!!.localToScreen(Offset.Zero)
                    start = Offset(bounds.exactCenterX(), bounds.exactCenterY()) - screen
                }
                begin(start)
                SystemClock.sleep(500)
                main { assertTrue(state.drag!!.source is DragSource.New); destination = state.appIconBounds.getValue(1).center }
                approach(destination); waitFor("search merge ready") { state.mergeReady == 1L }; release()
                main { assertEquals(listOf(apps[0], apps[0]), folder().apps) }
                shot("05b-search-copy-created-folder")
                report.append("Actual search-result gesture creates a same-app folder using DragSource.New.\n")
                // A member-to-app drag moves exactly the selected duplicate into a new folder.
                val memberSource = Edits.add(Edits.add(HomeLayout(seeded = true), AppContent(apps[0]), 2, 3),
                    FolderContent(entries = listOf(first, second)), 0, 0)
                reset(memberSource)
                main { state.openFolder = 2 }; SystemClock.sleep(500)
                main { start = state.panelMemberBounds.getValue(2L to second.id).center; destination = state.appIconBounds.getValue(1).center }
                begin(start); approach(destination); waitFor("member merge ready") { state.mergeReady == 1L }; release()
                main {
                    assertEquals(second.id, folder().entries[1].id)
                    assertEquals(AppContent(first.app), activity.graph.layout.layout.value.items.first { it.id == 2L }.content)
                }
                shot("05c-member-copy-moved-to-new-folder")
                report.append("Actual member-to-app gesture moves only the captured duplicate ID and preserves its sibling.\n")
                // Full large folders count occurrences and preserve placed sources on rejection.
                var full = Edits.add(HomeLayout(seeded = true), FolderContent(List(9) { apps[0] }, true), 1, 1)
                full = Edits.add(full, AppContent(apps[0]), 4, 0)
                reset(full)
                main { start = state.appIconBounds.getValue(2).center; destination = state.folderBounds.getValue(1).center }
                begin(start); approach(destination); release()
                main { assertEquals(full, activity.graph.layout.layout.value) }
                shot("06-full-nine-reject-source-retained")
                // Cancel a ready merge: neither source nor target is consumed.
                reset(placed)
                main { start = state.appIconBounds.getValue(2).center; destination = state.appIconBounds.getValue(1).center }
                begin(start); approach(destination); waitFor("ready before cancel") { state.mergeReady == 1L }
                release(cancel = true)
                main { assertEquals(placed, activity.graph.layout.layout.value) }
                shot("07-cancel-retains-both")
                // Center release before arming cancels; swaps require an edge intent.
                reset(Edits.add(Edits.add(HomeLayout(seeded = true), AppContent(apps[0]), 2, 2), AppContent(apps[1]), 0, 0))
                main { start = state.appIconBounds.getValue(2).center; destination = state.appIconBounds.getValue(1).center }
                begin(start); approach(destination); release()
                main {
                    assertTrue(activity.graph.layout.layout.value.items.all { it.content is AppContent })
                    assertEquals(0, activity.graph.layout.layout.value.items.first { it.id == 2L }.x)
                }
                shot("08-prearm-center-drop-cancels")
                report.append("Full large folder rejects tenth duplicate without consuming source. ACTION_CANCEL preserves both apps after arming. Pre-arm center release retains both desktop apps.\n")
                main { rtl.value = true }
                reset(Edits.add(Edits.add(HomeLayout(seeded = true), AppContent(apps[0]), 0, 2), AppContent(apps[0]), 4, 0))
                main { start = state.appIconBounds.getValue(2).center; destination = state.appIconBounds.getValue(1).center }
                begin(start); approach(destination); waitFor("RTL merge armed") { state.mergeReady == 1L }; SystemClock.sleep(120); shot("08a-rtl-edge-hint-clamped"); release()
                main { assertEquals(0, activity.graph.layout.layout.value.items.single().x); assertEquals(listOf(apps[0], apps[0]), folder().apps) }
                shot("08b-rtl-creation-keeps-target-position")
                main { rtl.value = false }
                reset(Edits.add(Edits.add(HomeLayout(seeded = true), AppContent(apps[0]), 2, 10), AppContent(apps[0]), 0, 0))
                main { start = state.appIconBounds.getValue(2).center }
                begin(start)
                main { destination = Offset(state.rootCoords!!.size.width / 2f, state.dockSurfaceCoords!!.boundsInRoot().top - 40f) }
                approach(destination)
                waitFor("native drag autoscroll reaches distant target") {
                    state.feedScroll.value > 0 && state.appIconBounds.getValue(1).let {
                        it.height > 0 && it.center.y > state.cellH && it.center.y < state.dockSurfaceCoords!!.boundsInRoot().top - state.cellH * .5f
                    }
                }
                // Leave the scroll zone, then aim at the target's updated root bounds.
                var pointer = Offset.Zero
                main { pointer = Offset(state.rootCoords!!.size.width / 2f, state.cellH * 3) - state.drag!!.anchorOffset }
                send(MotionEvent.ACTION_MOVE, pointer); SystemClock.sleep(100)
                main { destination = state.appIconBounds.getValue(1).center }
                approach(destination); waitFor("scrolled merge armed") { state.mergeReady == 1L }; release()
                main { assertEquals(10, activity.graph.layout.layout.value.items.single().y); assertEquals(listOf(apps[0], apps[0]), folder().apps) }
                shot("08c-scrolled-creation-keeps-target-row")
                report.append("Native RTL and feed-autoscroll creation retain the target's stored position.\n")
                // Persist sparse duplicate positions via the real store, then load a new store.
                val sparse = FolderContent(entries = listOf(first.copy(slot = 0), second.copy(slot = 8)), large = true)
                reset(Edits.add(HomeLayout(seeded = true), sparse, 1, 1))
                waitFor("persisted occurrences and sparse slots reload") {
                    LayoutStore(activity, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)).layout.value.items.firstOrNull()?.content == sparse
                }
                shot("09-sparse-duplicate-reloaded")
                report.append("Actual LayoutStore reload retains IDs and occupied slots 0 and 8.\n")
                File(directory, "native-report.txt").writeText(report.toString())
            } finally { main { state.cancelDrag(); state.openFolder = null; activity.graph.layout.edit { saved } } }
        }
    }
}
