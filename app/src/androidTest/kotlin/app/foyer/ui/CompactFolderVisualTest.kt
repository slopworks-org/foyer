package app.foyer.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.foyer.MainActivity
import app.foyer.data.*
import app.foyer.graph
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Whole-screen proportions plus real-clock native movement, restoring the model via LayoutStore. */
class CompactFolderVisualTest {
    @Test fun sparseAndDenseTargets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun waitFor(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 10000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                main { ready = condition() }
                if (!ready) SystemClock.sleep(20)
            }
            assertTrue(ready)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            val saved = activity.graph.layout.layout.value
            lateinit var state: LauncherState
            var initialized = false
            var light by mutableStateOf(false)
            val directory = File(activity.getExternalFilesDir(null), "compact-targets").apply { mkdirs() }
            fun shot(name: String) {
                instrumentation.waitForIdleSync(); SystemClock.sleep(120)
                instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
                    File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            try {
                waitFor { activity.graph.apps.apps.value.isNotEmpty() }
                val apps = activity.graph.apps.apps.value
                val camera = apps.first { it.key.pkg.contains("camera") }
                val calendar = apps.first { it.key.pkg.contains("calendar") }
                main {
                    activity.setContent {
                        val scope = rememberCoroutineScope()
                        state = remember { LauncherState(activity.graph, scope, activity) }
                        val config = Configuration(LocalConfiguration.current).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                if (light) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
                        }
                        CompositionLocalProvider(LocalConfiguration provides config) { FoyerRoot(state) }
                        SideEffect { initialized = true }
                    }
                }
                waitFor { initialized }
                fun fixture(count: Int) {
                    main {
                        state.cancelDrag(); state.folderLanding?.let(state::finishLanding)
                        activity.graph.layout.edit {
                            Edits.add(Edits.add(HomeLayout(seeded = true), FolderContent(large = true,
                                entries = (0 until count).map { FolderEntry(if (it % 2 == 0) camera.key else calendar.key, "e$it", it) }), 1, 4),
                                AppContent(camera.key), 1, 1)
                        }
                    }
                    SystemClock.sleep(700)
                }
                for (isLight in listOf(false, true)) {
                    main { light = isLight }
                    val theme = if (isLight) "light" else "dark"
                    fixture(3); shot("$theme-sparse-rest")
                    for (art in listOf(camera, calendar)) for (slot in listOf(4, 6, 7, 8)) for (offset in listOf(0f, .28f)) {
                        main {
                            state.cancelDrag(); state.folderLanding?.let(state::finishLanding)
                            val cell = largeFolderCell(state.folderSurfaceBounds.getValue(1), slot, state.folderInsetPx)
                            state.startDrag(DragSession(DragSource.New(AppContent(art.key)), art.icon, Offset.Zero,
                                cell.center + Offset(cell.width * offset, 0f)))
                        }
                        SystemClock.sleep(450)
                        shot("$theme-${art.key.pkg.substringAfterLast('.')}-slot$slot-${if (offset == 0f) "center" else "offset"}")
                    }
                    fixture(9); shot("$theme-dense-rest")
                    main {
                        val entry = (activity.graph.layout.layout.value.items.first().content as FolderContent).entries.first()
                        state.startDrag(DragSession(DragSource.Member(1, entry.app, entry.id), camera.icon, Offset.Zero,
                            largeFolderCell(state.folderSurfaceBounds.getValue(1), 8, state.folderInsetPx).center))
                    }
                    SystemClock.sleep(450); shot("$theme-dense-swap")
                }
                main { light = false }; fixture(3)
                var downTime = 0L
                fun send(action: Int, point: Offset) {
                    val now = SystemClock.uptimeMillis()
                    if (action == MotionEvent.ACTION_DOWN) downTime = now
                    main {
                        val window = state.rootCoords!!.localToWindow(point)
                        MotionEvent.obtain(downTime, now, action, window.x, window.y, 0).also {
                            it.source = InputDevice.SOURCE_TOUCHSCREEN; activity.dispatchTouchEvent(it); it.recycle()
                        }
                    }
                }
                var start = Offset.Zero
                main { start = state.appIconBounds.getValue(2).center }
                send(MotionEvent.ACTION_DOWN, start); SystemClock.sleep(620)
                send(MotionEvent.ACTION_MOVE, start + Offset(25f, 25f)); SystemClock.sleep(100)
                waitFor { state.drag != null }
                for (slot in listOf(4, 6, 7, 8)) {
                    var from = Offset.Zero; var to = Offset.Zero
                    main {
                        from = state.drag!!.pointer
                        to = largeFolderCell(state.folderSurfaceBounds.getValue(1), slot, state.folderInsetPx).center - state.drag!!.anchorOffset
                    }
                    for (step in 1..24) {
                        send(MotionEvent.ACTION_MOVE, from + (to - from) * (step / 24f)); SystemClock.sleep(16)
                    }
                    SystemClock.sleep(450)
                    main { assertEquals(slot, state.folderDropPreview(state.drag!!)!!.slot) }
                    shot("film-slot$slot")
                }
                var end = Offset.Zero; main { end = state.drag!!.pointer }
                send(MotionEvent.ACTION_UP, end)
                waitFor { state.drag == null && state.folderLanding == null }
                shot("film-released")
                main { assertNotNull((activity.graph.layout.layout.value.items.first().content as FolderContent).slotEntries[8]) }
            } finally {
                main {
                    if (initialized) { state.cancelDrag(); state.folderLanding?.let(state::finishLanding) }
                    activity.graph.layout.edit { saved }
                }
                waitFor { activity.graph.layout.layout.value == saved }
                SystemClock.sleep(800)
                assertEquals(saved, activity.graph.layout.layout.value)
            }
        }
    }
}
