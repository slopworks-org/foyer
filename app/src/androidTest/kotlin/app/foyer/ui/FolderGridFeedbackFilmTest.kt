package app.foyer.ui

import android.os.SystemClock
import androidx.activity.compose.setContent
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

/** Normal Android frame clock: short retarget, cancel and release evidence. */
class FolderGridFeedbackFilmTest {
    @Test fun retargetAndRelease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            lateinit var state: LauncherState
            scenario.onActivity { activity = it }
            fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
            val saved = activity.graph.layout.layout.value
            main {
                activity.setContent {
                    val scope = rememberCoroutineScope()
                    state = remember { LauncherState(activity.graph, scope, activity) }
                    FoyerRoot(state)
                }
            }
            SystemClock.sleep(700)
            try {
                val apps = activity.graph.apps.apps.value
                assertTrue(apps.size >= 9)
                for (count in listOf(3, 9)) {
                    main {
                        activity.graph.layout.edit {
                            Edits.add(HomeLayout(seeded = true), FolderContent(entries = apps.take(count).mapIndexed { i, app ->
                                FolderEntry(app.key, "film-$i", i)
                            }, large = true), 1, 2)
                        }
                    }
                    SystemClock.sleep(400)
                    val app = apps.first()
                    main {
                        val cell = largeFolderCell(state.folderSurfaceBounds.getValue(1), 0, state.folderInsetPx)
                        state.startDrag(DragSession(DragSource.Member(1, app.key, "film-0"), app.icon, Offset.Zero, cell.center))
                    }
                    SystemClock.sleep(200)
                    for (slot in listOf(1, 2, 5, 4, 3, 6, 7, 8, 4)) {
                        main {
                            val cell = largeFolderCell(state.folderSurfaceBounds.getValue(1), slot, state.folderInsetPx)
                            state.moveDrag(cell.center)
                            assertEquals(1f, state.drag!!.visualAlpha)
                        }
                        SystemClock.sleep(140)
                    }
                    main { state.cancelDrag() }
                    SystemClock.sleep(300)
                    main {
                        val cell = largeFolderCell(state.folderSurfaceBounds.getValue(1), 4, state.folderInsetPx)
                        state.startDrag(DragSession(DragSource.Member(1, app.key, "film-0"), app.icon, Offset.Zero, cell.center))
                    }
                    SystemClock.sleep(250)
                    main { state.endDrag() }
                    SystemClock.sleep(900)
                    main {
                        assertNull(state.drag)
                        val folder = activity.graph.layout.layout.value.items.single().content as FolderContent
                        assertEquals("film-0", folder.slotEntries[4]?.id)
                    }
                }
            } finally {
                main { state.cancelDrag(); state.folderLanding?.let(state::finishLanding); activity.graph.layout.edit { saved } }
                SystemClock.sleep(300)
                assertEquals(saved, activity.graph.layout.layout.value)
            }
        }
    }
}
