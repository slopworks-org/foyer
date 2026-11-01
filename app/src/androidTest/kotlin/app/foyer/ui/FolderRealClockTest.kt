package app.foyer.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.foyer.MainActivity
import app.foyer.data.*
import app.foyer.graph
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Native MotionEvents and Android's real frame clock; no Compose test rule or clock. */
class FolderRealClockTest {
    @Test fun momentumFilm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val trace = StringBuilder("case,releaseUptime,x,y,vx,vy,iconPx\n")
        val samples = StringBuilder("case,uptimeMillis,x,y,pressed\n")
        var case = ""
        lateinit var state: LauncherState
        lateinit var saved: HomeLayout
        lateinit var apps: List<AppKey>
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            fun main(block: (MainActivity) -> Unit) { instrumentation.runOnMainSync { block(activity) } }
            val deadline = SystemClock.uptimeMillis() + 10000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                main { ready = it.graph.apps.apps.value.size >= 15 }
                SystemClock.sleep(30)
            }
            assertTrue(ready)
            main { activity ->
                saved = activity.graph.layout.layout.value
                apps = activity.graph.apps.apps.value.map { it.key }
                activity.setContent {
                    val scope = rememberCoroutineScope()
                    state = remember { LauncherState(activity.graph, scope, activity) }
                    Box(Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach {
                                    samples.append("$case,${it.uptimeMillis},${it.position.x},${it.position.y},${it.pressed}\n")
                                }
                            }
                        }
                    }) { FoyerRoot(state) }
                }
            }
            SystemClock.sleep(400)
            try {
                for (name in listOf("sideways", "diagonal-away", "toward", "pause", "open-three", "open-four", "large")) {
                    case = name
                    val count = if (case == "open-three") 3 else if (case == "large") 5 else 4
                    val key = apps[count]
                    var folderId = 0L
                    main {
                        state.cancelDrag()
                        state.openFolder = null
                        state.graph.layout.edit {
                            var next = Edits.add(HomeLayout(seeded = true), FolderContent(apps.take(count), name = "Everyday", large = case == "large"), 2, 3)
                            folderId = next.items.single().id
                            next = Edits.add(next, AppContent(key), 0, 0)
                            next
                        }
                    }
                    SystemClock.sleep(450)
                    var start = Offset.Zero
                    var windowOffset = Offset.Zero
                    main {
                        start = state.gridCoords!!.localToRoot(Offset(state.cellW / 2, state.cellH / 2))
                        windowOffset = state.rootCoords!!.localToWindow(Offset.Zero)
                    }
                    val downTime = SystemClock.uptimeMillis()
                    fun send(action: Int, point: Offset) {
                        val p = point + windowOffset
                        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, p.x, p.y, 0)
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        main { it.dispatchTouchEvent(event) }
                        event.recycle()
                    }
                    send(MotionEvent.ACTION_DOWN, start)
                    SystemClock.sleep(620)
                    send(MotionEvent.ACTION_MOVE, start + Offset(35f, 35f))
                    SystemClock.sleep(50)
                    if (case.startsWith("open")) { main { state.openFolder = folderId }; SystemClock.sleep(450) }
                    var end = Offset.Zero
                    main {
                        assertNotNull(state.drag)
                        end = (if (case.startsWith("open")) state.folderHitGridBounds!!.center else state.folderBounds[folderId]!!.center) - state.drag!!.anchorOffset
                    }
                    val direction = when (case) {
                        "sideways", "pause" -> Offset(1f, 0f)
                        "diagonal-away", "open-four" -> Offset(-.7f, .7f)
                        "toward" -> Offset(0f, 1f)
                        else -> Offset(.7f, .7f)
                    }
                    send(MotionEvent.ACTION_MOVE, end - direction * 480f)
                    SystemClock.sleep(160)
                    for (step in 1..6) {
                        send(MotionEvent.ACTION_MOVE, end - direction * (480f * (1f - step / 6f)))
                        if (step < 6) SystemClock.sleep(16)
                    }
                    if (case == "pause") SystemClock.sleep(220)
                    send(MotionEvent.ACTION_UP, end)
                    main { activity ->
                        val flight = state.folderLanding ?: error("no flight: $case drag=${state.drag?.anchor} target=${state.drag?.let { state.targetFor(it) }} count=${(state.graph.layout.layout.value.items.first().content as FolderContent).apps.size} end=$end")
                        if (case == "pause") assertEquals(0f, flight.velocity.getDistance(), 1f)
                        else if (InstrumentationRegistry.getArguments().getString("baselineFilm") != "true")
                            assertTrue("fast native release: $case ${flight.velocity}", flight.velocity.getDistance() > 300f)
                        trace.append("$case,${SystemClock.uptimeMillis()},${flight.origin.x},${flight.origin.y},${flight.velocity.x},${flight.velocity.y},${state.iconSize.value * activity.resources.displayMetrics.density}\n")
                    }
                    SystemClock.sleep(1500)
                    main {
                        assertNull(state.drag)
                        assertNull(state.folderLanding)
                        assertTrue((state.graph.layout.layout.value.items.first().content as FolderContent).apps.contains(key))
                    }
                }
                main {
                    File(it.getExternalFilesDir(null), "real-film-trace.csv").writeText(trace.toString())
                    File(it.getExternalFilesDir(null), "real-film-samples.csv").writeText(samples.toString())
                }
            } finally { main { state.cancelDrag(); state.graph.layout.edit { saved } } }
        }
    }
}
