package app.foyer.ui

import android.content.res.Configuration
import android.graphics.Paint
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.foyer.MainActivity
import app.foyer.data.*
import app.foyer.graph
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real frame clock and synthetic native touch events. The overlay is evidence-only. */
class FolderMorphFilmTest {
    @Test fun continuousAcceptanceFilm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var state: LauncherState
        lateinit var saved: HomeLayout
        lateinit var apps: List<AppKey>
        var label by mutableStateOf("starting")
        var pointer by mutableStateOf(Offset.Zero)
        var pressed by mutableStateOf(false)
        var rtl by mutableStateOf(false)
        var light by mutableStateOf(false)
        var validationDelay = 0L
        var reject = false
        val trace = StringBuilder("uptime,case,event,x,y,preview,ready,validated,returning\n")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
            fun waitFor(name: String, condition: () -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 10000
                var ready = false
                while (!ready && SystemClock.uptimeMillis() < deadline) {
                    main { ready = condition() }; if (!ready) SystemClock.sleep(20)
                }
                assertTrue(name, ready)
            }
            waitFor("apps") { activity.graph.apps.apps.value.size >= 3 }
            main {
                saved = activity.graph.layout.layout.value
                File(activity.getExternalFilesDir(null), "morph-original.json").writeText(Json.encodeToString(HomeLayout.serializer(), saved))
                apps = activity.graph.apps.apps.value.map { it.key }
                activity.setContent {
                    val scope = rememberCoroutineScope()
                    state = remember { LauncherState(activity.graph, scope, activity) { keys ->
                        delay(validationDelay)
                        if (reject) keys.toSet() else activity.graph.apps.missingFolderApps(keys)
                    } }
                    SideEffect {
                        state.mergeFeedback = { armed ->
                            trace.append("${SystemClock.uptimeMillis()},$label,haptic-$armed,${pointer.x},${pointer.y},${state.mergePreviews.lastOrNull()?.progress?.value},${state.mergeReady},${state.folderLanding?.validated},${state.folderLanding?.returning}\n")
                        }
                    }
                    var frame by remember { mutableLongStateOf(0) }
                    LaunchedEffect(Unit) {
                        while (true) withFrameNanos {
                            frame = SystemClock.uptimeMillis()
                            trace.append("$frame,$label,frame,${pointer.x},${pointer.y},${state.mergePreviews.lastOrNull()?.progress?.value},${state.mergeReady},${state.folderLanding?.validated},${state.folderLanding?.returning}\n")
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        val configuration = Configuration(LocalConfiguration.current).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (light) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES }
                        CompositionLocalProvider(LocalConfiguration provides configuration, LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) { FoyerRoot(state) }
                        Canvas(Modifier.fillMaxSize()) {
                            val paint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 27f; isAntiAlias = true }
                            drawContext.canvas.nativeCanvas.apply {
                                drawRect(0f, 78f, size.width, 154f, Paint().apply { color = 0xdd000000.toInt() })
                                drawText("$label | uptime $frame ms", 12f, 110f, paint)
                                drawText(if (pressed) "native touch DOWN" else "native touch UP", 12f, 143f, paint)
                                if (pressed) drawCircle(pointer.x, pointer.y, 14f, Paint().apply { color = 0xff00ffff.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f })
                            }
                        }
                    }
                }
            }
            SystemClock.sleep(600)
            var downTime = 0L
            fun send(action: Int, point: Offset) {
                val now = SystemClock.uptimeMillis()
                if (action == MotionEvent.ACTION_DOWN) downTime = now
                var window = Offset.Zero
                main { window = state.rootCoords!!.localToWindow(Offset.Zero); pointer = point; pressed = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL }
                val event = MotionEvent.obtain(downTime, now, action, point.x + window.x, point.y + window.y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                main { activity.dispatchTouchEvent(event); trace.append("$now,$label,touch-$action,${point.x},${point.y},,,,,\n") }
                event.recycle()
            }
            fun begin(id: Long = 2) {
                var start = Offset.Zero
                main { start = state.appIconBounds.getValue(id).center }
                send(MotionEvent.ACTION_DOWN, start); SystemClock.sleep(620)
                send(MotionEvent.ACTION_MOVE, start + Offset(30f, 30f)); SystemClock.sleep(40)
                main { assertNotNull(state.drag) }
            }
            fun approach(point: Offset, steps: Int = 12) {
                var start = Offset.Zero; var end = Offset.Zero
                main { start = state.drag!!.pointer; end = point - state.drag!!.anchorOffset }
                for (step in 1..steps) { send(MotionEvent.ACTION_MOVE, start + (end - start) * (step.toFloat() / steps)); SystemClock.sleep(16) }
            }
            fun target(id: Long = 1): Offset { var point = Offset.Zero; main { point = state.appIconBounds.getValue(id).center }; return point }
            fun release(cancel: Boolean = false) {
                var point = Offset.Zero; main { point = state.drag!!.pointer }
                send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, point)
                waitFor("settled") { state.drag == null && state.folderLanding == null }
                SystemClock.sleep(450)
            }
            fun reset(name: String, same: Boolean = false, third: Boolean = false) {
                main {
                    label = name; validationDelay = 0; reject = false
                    state.cancelDrag(); state.openFolder = null; state.menu = null
                    state.feedScroll.dispatchRawDelta(-state.feedScroll.value.toFloat())
                    activity.graph.layout.edit {
                        var l = Edits.add(Edits.add(HomeLayout(seeded = true), AppContent(apps[0]), 2, 3), AppContent(apps[if (same) 0 else 1]), 0, 1)
                        if (third) l = Edits.add(l, AppContent(apps[2]), 4, 3)
                        l
                    }
                }
                SystemClock.sleep(650)
            }
            try {
                if (InstrumentationRegistry.getArguments().getString("panelOnly") != "true") {
                reset("01 different apps")
                begin(); approach(target()); waitFor("armed") { state.mergeReady == 1L }; SystemClock.sleep(350); release()
                main { assertTrue(activity.graph.layout.layout.value.items.single().content is FolderContent); assertNull(state.openFolder) }
                reset("02 same app", same = true)
                begin(); approach(target()); waitFor("armed") { state.mergeReady == 1L }; SystemClock.sleep(200); release()
                main { assertEquals(2, (activity.graph.layout.layout.value.items.single().content as FolderContent).entries.map { it.id }.distinct().size) }
                reset("03 fast center immediate merge")
                begin(); approach(target(), 1)
                main { assertEquals(1L, state.mergeReady); assertTrue(state.mergePreviews.last().progress.value < 1f) }
                release()
                main { assertTrue(activity.graph.layout.layout.value.items.single().content is FolderContent) }
                reset("03b pointer up first enters center")
                begin()
                var up = Offset.Zero
                main { up = state.appIconBounds.getValue(1).center - state.drag!!.anchorOffset }
                send(MotionEvent.ACTION_UP, up)
                waitFor("up-only merge") { state.folderLanding == null && state.drag == null }
                main { assertTrue(activity.graph.layout.layout.value.items.single().content is FolderContent) }
                reset("03c fast pass exits without commit")
                begin(); approach(target(), 1)
                approach(target() + Offset(0f, 220f), 1)
                main { assertEquals(2, activity.graph.layout.layout.value.items.size); assertNull(state.mergeReady) }
                release(cancel = true)
                reset("03d stale target during validation")
                main { validationDelay = 200 }
                begin(); approach(target(), 1)
                var staleUp = Offset.Zero
                main { staleUp = state.drag!!.pointer }
                send(MotionEvent.ACTION_UP, staleUp)
                main {
                    assertEquals(2, activity.graph.layout.layout.value.items.size)
                    activity.graph.layout.edit { Edits.remove(it, 1) }
                }
                waitFor("stale target rejected") { state.folderLanding == null && state.drag == null }
                main { assertTrue(activity.graph.layout.layout.value.items.single().content is AppContent) }
                reset("04 armed exit reversal")
                begin(); approach(target()); waitFor("armed") { state.mergeReady == 1L }; SystemClock.sleep(200)
                approach(target() + Offset(0f, 220f)); SystemClock.sleep(350); release(cancel = true)
                main { assertEquals(2, activity.graph.layout.layout.value.items.size) }
                reset("05 armed ACTION_CANCEL")
                begin(); approach(target()); waitFor("armed") { state.mergeReady == 1L }; SystemClock.sleep(200); release(cancel = true)
                reset("06 delayed rejection continuous return")
                main { validationDelay = 700; reject = true }
                begin(); approach(target()); waitFor("armed") { state.mergeReady == 1L }; release()
                main { assertEquals(2, activity.graph.layout.layout.value.items.size) }
                reset("07 target switches", third = true)
                begin(); approach(target()); waitFor("first armed") { state.mergeReady == 1L }
                approach(target(3)); waitFor("second armed") { state.mergeReady == 3L }; SystemClock.sleep(200); release()
                main { assertEquals(2, activity.graph.layout.layout.value.items.size) }
                reset("08 edge swap")
                begin(); var edge = Offset.Zero
                main { val b = state.appIconBounds.getValue(1); edge = Offset(b.left + b.width * .08f, b.center.y) }
                approach(edge); SystemClock.sleep(180); release()
                main { assertEquals(2, activity.graph.layout.layout.value.items.first { it.id == 2L }.x) }
                main { rtl = true; light = true }
                reset("09 RTL same app", same = true)
                begin(); approach(target()); waitFor("RTL armed") { state.mergeReady == 1L }; SystemClock.sleep(250); release()
                main { rtl = false; label = "10 singleton replacement panel cleanup"; state.graph.layout.edit { Edits.add(HomeLayout(seeded = true), FolderContent(listOf(apps[0], apps[1]), name = "Tools"), 2, 3) } }
                SystemClock.sleep(600)
                main { state.openFolder = 1 }; SystemClock.sleep(700)
                main { assertEquals(2, (state.graph.layout.layout.value.items.single().content as FolderContent).apps.size) }
                SystemClock.sleep(500)
                main {
                    val entry = (state.graph.layout.layout.value.items.single().content as FolderContent).entries.first()
                    state.showMemberMenu(1, entry, state.panelMemberBounds.getValue(1L to entry.id))
                }
                SystemClock.sleep(600)
                main { state.menu!!.actions.single { it.label == app.foyer.R.string.action_remove }.onClick() }
                waitFor("empty panel cleaned") { state.graph.layout.layout.value.items.single().content is AppContent && state.openFolder == null && state.folderPanelBounds == null && state.menu == null }
                SystemClock.sleep(900)
                main {
                    label = "11 two-to-one move to feed"; light = false
                    state.graph.layout.edit { Edits.add(HomeLayout(seeded = true), FolderContent(listOf(apps[0], apps[1])), 0, 1) }
                }
                SystemClock.sleep(550); main { state.openFolder = 1 }; SystemClock.sleep(550)
                var member = Offset.Zero
                main { member = state.panelMemberBounds.values.first().center }
                send(MotionEvent.ACTION_DOWN, member); SystemClock.sleep(620)
                send(MotionEvent.ACTION_MOVE, member + Offset(30f, 30f)); SystemClock.sleep(50)
                var empty = Offset.Zero
                main { empty = state.gridCoords!!.localToRoot(Offset(state.cellW * 4.5f, state.cellH * 5.5f)) }
                approach(empty); release()
                main { assertEquals(2, state.graph.layout.layout.value.items.count { it.content is AppContent }); assertNull(state.openFolder) }
                main {
                    label = "12 two-to-one source merge"
                    state.graph.layout.edit { Edits.add(Edits.add(HomeLayout(seeded = true), AppContent(apps[1]), 2, 4), FolderContent(listOf(apps[0], apps[1])), 0, 1) }
                }
                SystemClock.sleep(550); main { state.openFolder = 2 }; SystemClock.sleep(550)
                main { member = state.panelMemberBounds.values.first().center }
                send(MotionEvent.ACTION_DOWN, member); SystemClock.sleep(620)
                send(MotionEvent.ACTION_MOVE, member + Offset(30f, 30f)); SystemClock.sleep(50)
                approach(target()); waitFor("member armed") { state.mergeReady == 1L }; SystemClock.sleep(200); release()
                main { assertEquals(2, state.graph.layout.layout.value.items.size); assertEquals(2, (state.graph.layout.layout.value.items.first { it.id == 1L }.content as FolderContent).entries.size); assertNull(state.openFolder) }
                }
                main {
                    label = "13 panel press no indication"
                    state.graph.layout.edit { Edits.add(HomeLayout(seeded = true), FolderContent(listOf(apps[0], apps[1]), name = "Tools"), 2, 3) }
                }
                SystemClock.sleep(600); main { state.openFolder = 1 }; SystemClock.sleep(600)
                var member = Offset.Zero
                main { member = state.panelMemberBounds.values.first().center }
                fun shot(name: String) {
                    instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
                        File(activity.getExternalFilesDir(null), "immediate-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                    }
                }
                shot("panel-before")
                send(MotionEvent.ACTION_DOWN, member); SystemClock.sleep(180)
                send(MotionEvent.ACTION_CANCEL, member); SystemClock.sleep(400); shot("panel-cancel")
                main { assertNull(state.menu); assertEquals(1L, state.openFolder) }
                send(MotionEvent.ACTION_DOWN, member); SystemClock.sleep(180)
                send(MotionEvent.ACTION_UP, member)
                main { assertNull(state.menu); assertNull(state.openFolder) }
                SystemClock.sleep(800)
            } finally {
                main {
                    File(activity.getExternalFilesDir(null), "morph-film-trace.csv").writeText(trace.toString())
                    state.cancelDrag(); state.openFolder = null; state.menu = null; activity.graph.layout.edit { saved }
                    assertEquals(saved, activity.graph.layout.layout.value)
                }
            }
        }
    }
}
