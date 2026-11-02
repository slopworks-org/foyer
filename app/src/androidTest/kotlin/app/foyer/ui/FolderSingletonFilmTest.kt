package app.foyer.ui

import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.foyer.MainActivity
import app.foyer.data.*
import app.foyer.graph
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Normal-speed native gestures; snapshots are restored through LayoutStore even on failure. */
class FolderSingletonFilmTest {
    @Test fun singletonTransitions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun waitFor(name: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 10000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                main { ready = condition() }
                if (!ready) SystemClock.sleep(20)
            }
            assertTrue(name, ready)
        }
        val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            val saved = activity.graph.layout.layout.value
            val directory = File(activity.getExternalFilesDir(null), "singleton").apply { mkdirs() }
            File(directory, "original.json").writeText(json.encodeToString(HomeLayout.serializer(), saved))
            lateinit var state: LauncherState
            var initialized = false
            var reject = false
            var label by mutableStateOf("starting")
            var pointer by mutableStateOf(Offset.Zero)
            var pressed by mutableStateOf(false)
            val haptics = mutableListOf<Pair<Long, Boolean>>()
            val hapticTrace = StringBuilder("uptime,case,armed\n")
            val trace = StringBuilder("uptime,case,collapse,start,end,landing,frame,surfaceFrame\n")
            try {
                waitFor("apps") { activity.graph.apps.apps.value.size >= 2 }
                val apps = activity.graph.apps.apps.value.map { it.key }
                main {
                    activity.setContent {
                        val scope = rememberCoroutineScope()
                        state = remember { LauncherState(activity.graph, scope, activity) { keys -> if (reject) keys.toSet() else emptySet() } }
                        SideEffect { initialized = true; state.mergeFeedback = { haptics += SystemClock.uptimeMillis() to it; hapticTrace.append("${SystemClock.uptimeMillis()},$label,$it\n") } }
                        var frame by remember { mutableLongStateOf(0) }
                        LaunchedEffect(Unit) {
                            while (true) withFrameNanos {
                                frame = SystemClock.uptimeMillis()
                                trace.append("$frame,$label,${state.folderCollapses.values.firstOrNull()?.progress?.value},${state.folderCollapses.values.firstOrNull()?.start},${state.appIconBounds[1]},${state.folderLanding != null},${state.folderCollapses.values.firstOrNull()?.frame},${state.folderCollapses.values.firstOrNull()?.surfaceFrame}\n")
                            }
                        }
                        Box(Modifier.fillMaxSize()) {
                            FoyerRoot(state)
                            Canvas(Modifier.fillMaxSize()) {
                                val paint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 28f; isAntiAlias = true }
                                drawContext.canvas.nativeCanvas.apply {
                                    drawRect(0f, 78f, size.width, 145f, Paint().apply { color = 0xdd000000.toInt() })
                                    drawText("$label | $frame ms", 12f, 110f, paint)
                                    drawText(if (pressed) "native DOWN" else "native UP", 12f, 140f, paint)
                                    if (pressed) drawCircle(pointer.x, pointer.y, 14f, Paint().apply { color = 0xff00ffff.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f })
                                }
                            }
                        }
                    }
                }
                waitFor("composition") { initialized }
                val gesture = NativeFolderGesture(activity, { state }, ::main) { now, action, point ->
                    pointer = point
                    pressed = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
                }
                fun send(action: Int, point: Offset) = gesture.send(action, point)
                fun reset(name: String, large: Boolean = false, duplicate: Boolean = false, emptyTarget: Boolean = false) {
                    main {
                        label = name; reject = false; state.cancelDrag(); state.openFolder = null; state.menu = null
                        state.feedScroll.dispatchRawDelta(-state.feedScroll.value.toFloat())
                        activity.graph.layout.edit {
                            val entries = listOf(FolderEntry(apps[0], "depart", if (large) 0 else null), FolderEntry(apps[if (duplicate) 0 else 1], "survive", if (large) 8 else null))
                            var l = Edits.add(HomeLayout(seeded = true), FolderContent(entries = entries, large = large, name = "Tools"), 0, 2)
                            if (emptyTarget) l = Edits.add(l, FolderContent(), 3, 4)
                            Edits.add(l, AppContent(apps[0]), 4, 1)
                        }
                    }
                    SystemClock.sleep(650)
                }
                fun open() { main { state.openFolder = 1 }; waitFor("panel") { state.panelMemberBounds.containsKey(1L to "depart") }; SystemClock.sleep(500) }
                fun begin(point: Offset) {
                    send(MotionEvent.ACTION_DOWN, point); SystemClock.sleep(620)
                    send(MotionEvent.ACTION_MOVE, point + Offset(30f, 30f)); SystemClock.sleep(50)
                    main { assertNotNull(state.drag) }
                }
                fun approach(point: Offset) { gesture.approach(point); SystemClock.sleep(400) }
                fun release(cancel: Boolean = false) {
                    var point = Offset.Zero; main { point = state.drag!!.pointer }
                    send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, point)
                    waitFor("settled") { state.drag == null && state.folderLanding == null && state.folderCollapses.isEmpty() }
                    SystemClock.sleep(650)
                }
                fun point(block: () -> Offset): Offset { var result = Offset.Zero; main { result = block() }; return result }
                fun feed() = point { state.gridCoords!!.localToRoot(Offset(state.cellW * 4.5f, state.cellH * 6.5f)) }
                fun assertSurvivor() = main {
                    val item = state.graph.layout.layout.value.items.first { it.id == 1L }
                    assertEquals(AppContent(apps[if (label.contains("duplicate")) 0 else 1]), item.content)
                    assertEquals(0, item.x); assertEquals(2, item.y); assertEquals(1, item.w); assertEquals(1, item.h)
                    assertNull(state.openFolder)
                }
                reset("01 closed small cleanup")
                main { activity.graph.layout.editChange { Edits.reconcileFolderAppsChange(it, setOf(apps[0])) } }
                SystemClock.sleep(1000); assertSurvivor()
                reset("02 open small move to feed"); open()
                begin(point { state.panelMemberBounds.getValue(1L to "depart").center }); approach(feed()); release(); assertSurvivor()
                reset("03 large sparse slot8 survivor", large = true)
                begin(point { state.memberBounds.getValue(1L to "depart").center }); approach(feed()); release()
                main {
                    val item = state.graph.layout.layout.value.items.first { it.id == 1L }
                    assertEquals(2, item.w); assertEquals(2, item.h)
                    assertEquals(8, (item.content as FolderContent).entries.single().slot)
                    assertTrue(state.folderCollapses.isEmpty())
                }
                reset("03a large frame moves then releases", large = true)
                begin(point { state.appIconBounds.getValue(2).center })
                approach(point { largeFolderCell(state.folderSurfaceBounds.getValue(1), 4, state.folderInsetPx).center })
                approach(point { largeFolderCell(state.folderSurfaceBounds.getValue(1), 5, state.folderInsetPx).center })
                main { assertEquals(5, state.folderDropPreview(state.drag!!)!!.slot) }
                instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
                    File(directory, "large-frame-before-release.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                release()
                main { assertNotNull((state.graph.layout.layout.value.items.first().content as FolderContent).slotEntries[5]) }
                reset("04 duplicate copies open move", duplicate = true); open()
                begin(point { state.panelMemberBounds.getValue(1L to "depart").center }); approach(feed()); release(); assertSurvivor()
                reset("05 empty destination 0 to 1", emptyTarget = true)
                begin(point { state.appIconBounds.getValue(3).center }); approach(point { state.folderSurfaceBounds.getValue(2).center }); release()
                main { assertEquals(AppContent(apps[0]), state.graph.layout.layout.value.items.first { it.id == 2L }.content) }
                reset("06 simultaneous source and empty destination", emptyTarget = true); open()
                begin(point { state.panelMemberBounds.getValue(1L to "depart").center }); approach(point { state.folderSurfaceBounds.getValue(2).center }); release(); assertSurvivor()
                main { assertEquals(AppContent(apps[0]), state.graph.layout.layout.value.items.first { it.id == 2L }.content) }
                reset("07 cancelled retains two"); open()
                begin(point { state.panelMemberBounds.getValue(1L to "depart").center }); approach(feed()); release(cancel = true)
                main { assertEquals(2, (state.graph.layout.layout.value.items.first().content as FolderContent).entries.size) }
                reset("08 rejected retains two"); open(); main { reject = true }
                begin(point { state.panelMemberBounds.getValue(1L to "depart").center }); approach(feed()); release()
                main { assertEquals(2, (state.graph.layout.layout.value.items.first().content as FolderContent).entries.size) }
                reset("09 opaque app merge hover")
                begin(point { state.appIconBounds.getValue(2).center })
                // Create another desktop app through Edits; source folder is untouched.
                main { activity.graph.layout.edit { Edits.add(it, AppContent(apps[1]), 2, 5) } }
                SystemClock.sleep(300); approach(point { state.appIconBounds.getValue(3).center })
                waitFor("merge armed") { state.mergeReady == 3L }
                main { assertEquals(listOf(true), haptics.map { it.second }) }
                // Leaving an armed candidate emits exactly one exit; entering again emits one arm.
                approach(feed())
                main { assertEquals(listOf(true, false), haptics.map { it.second }) }
                approach(point { state.appIconBounds.getValue(3).center })
                waitFor("merge rearmed") { state.mergeReady == 3L }
                main { assertEquals(listOf(true, false, true), haptics.map { it.second }) }
                SystemClock.sleep(500)
                instrumentation.uiAutomation.takeScreenshot().also { bitmap -> File(directory, "opaque-hover.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
                release()
                main {
                    assertEquals(2, (state.graph.layout.layout.value.items.first { it.id == 3L }.content as FolderContent).entries.size)
                    assertEquals(listOf(true, false, true), haptics.map { it.second })
                }
                reset("10 haptic early exit switch scroll cancel reject")
                main {
                    haptics.clear()
                    activity.graph.layout.edit { Edits.add(Edits.add(Edits.add(it, AppContent(apps[1]), 2, 3), AppContent(apps[0]), 0, 3), AppContent(apps[1]), 0, 12) }
                }
                SystemClock.sleep(300)
                begin(point { state.appIconBounds.getValue(2).center })
                fun moveAnchor(anchor: Offset) {
                    send(MotionEvent.ACTION_MOVE, point { anchor - state.drag!!.anchorOffset })
                }
                moveAnchor(point { state.appIconBounds.getValue(3).center }); SystemClock.sleep(40)
                moveAnchor(point { state.gridCoords!!.localToRoot(Offset(state.cellW * 4.5f, state.cellH * 2.5f)) }); SystemClock.sleep(400)
                main { assertEquals(listOf(true, false), haptics.map { it.second }); haptics.clear() }
                approach(point { state.appIconBounds.getValue(3).center })
                waitFor("first ready") { state.mergeReady == 3L }
                moveAnchor(point { state.appIconBounds.getValue(4).center })
                SystemClock.sleep(50)
                main { assertEquals(listOf(true, false, true), haptics.map { it.second }); assertEquals(4L, state.mergeReady) }
                waitFor("switched ready") { state.mergeReady == 4L }
                main {
                    assertEquals(listOf(true, false, true), haptics.map { it.second })
                    state.feedScroll.dispatchRawDelta(30f)
                    state.refreshDragTargets()
                    assertEquals(listOf(true, false, true, false), haptics.map { it.second })
                    assertNull(state.mergeReady)
                }
                approach(point { state.appIconBounds.getValue(4).center })
                waitFor("post scroll ready") { state.mergeReady == 4L }
                release(cancel = true)
                main { assertEquals(listOf(true, false, true, false, true, false), haptics.map { it.second }) }
                begin(point { state.appIconBounds.getValue(2).center })
                approach(point { state.appIconBounds.getValue(3).center })
                waitFor("rejected ready") { state.mergeReady == 3L }
                main { reject = true }
                release()
                main { assertEquals(listOf(true, false, true, false, true, false, true, false), haptics.map { it.second }) }
                reset("11 cleanup while survivor held"); open()
                begin(point { state.panelMemberBounds.getValue(1L to "survive").center })
                main { activity.graph.layout.editChange { Edits.reconcileFolderAppsChange(it, setOf(apps[0])) } }
                waitFor("held survivor handed off") { state.drag == null && state.folderLanding == null && state.folderCollapses.isEmpty() }
                send(MotionEvent.ACTION_CANCEL, pointer)
                assertSurvivor()
                reset("12 cleanup while survivor returning"); open()
                begin(point { state.panelMemberBounds.getValue(1L to "survive").center }); approach(feed())
                main {
                    state.cancelDrag()
                    activity.graph.layout.editChange { Edits.reconcileFolderAppsChange(it, setOf(apps[0])) }
                }
                waitFor("returning survivor handed off") { state.drag == null && state.folderLanding == null && state.folderCollapses.isEmpty() }
                send(MotionEvent.ACTION_CANCEL, pointer)
                assertSurvivor()
            } finally {
                main {
                    if (initialized) { state.cancelDrag(); state.openFolder = null; state.menu = null }
                    activity.graph.layout.edit { saved }
                }
                val deadline = SystemClock.uptimeMillis() + 10000
                val file = File(activity.filesDir, "home.json")
                while (SystemClock.uptimeMillis() < deadline && (!file.exists() || File(file.path + ".new").exists() || runCatching { json.decodeFromString<HomeLayout>(file.readText()) != saved }.getOrDefault(true))) SystemClock.sleep(50)
                assertEquals(saved, json.decodeFromString<HomeLayout>(file.readText()))
                File(directory, "trace.csv").writeText(trace.toString())
                File(directory, "haptics.csv").writeText(hapticTrace.toString())
                File(directory, "restored.json").writeText(json.encodeToString(HomeLayout.serializer(), saved))
            }
        }
    }
}
