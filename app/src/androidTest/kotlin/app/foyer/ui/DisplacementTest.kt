package app.foyer.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.foyer.MainActivity
import app.foyer.data.*
import app.foyer.graph
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DisplacementTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun releaseDisplacesSmoothlyAndWidgetFailurePreservesLayout() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        lateinit var state: LauncherState
        rule.waitUntil(10_000) { graph.apps.apps.value.size >= 2 }
        val apps = graph.apps.apps.value.take(2)
        rule.activity.setContent {
            val scope = rememberCoroutineScope()
            state = remember { LauncherState(graph, scope, rule.activity) }
            FoyerRoot(state)
        }
        try {
            rule.runOnIdle {
                graph.layout.edit {
                    Edits.add(Edits.add(HomeLayout(seeded = true), AppContent(apps[0].key), 0, 2),
                        AppContent(apps[1].key), 2, 0)
                }
            }
            rule.waitForIdle()
            val before = rule.runOnIdle { state.appIconBounds.getValue(2).center }
            rule.mainClock.autoAdvance = false
            rule.runOnUiThread {
                val source = graph.layout.layout.value.items.first()
                val start = state.appIconBounds.getValue(source.id).center
                state.startDrag(DragSession(DragSource.Placed(source), apps[0].icon, Offset.Zero, start))
                // Cell edge avoids the existing center-to-folder merge gesture.
                val grid = state.gridCoords!!.localToRoot(Offset.Zero)
                state.moveDrag(grid + Offset(state.cellW * 2.05f, state.cellH * 0.5f))
                assertTrue(state.targetFor(state.drag!!) is DropTarget.Feed)
                state.endDrag()
            }
            rule.waitUntil(10_000) {
                rule.mainClock.advanceTimeByFrame()
                graph.layout.layout.value.items.first().x == 2
            }
            rule.mainClock.advanceTimeBy(64)
            rule.waitForIdle()
            val during = rule.runOnIdle { state.appIconBounds.getValue(2).center }
            rule.mainClock.advanceTimeBy(1500)
            rule.waitForIdle()
            val after = rule.runOnIdle { state.appIconBounds.getValue(2).center }
            assertTrue("occupant starts moving left", during.x < before.x)
            assertTrue("occupant has an intermediate frame", during.x > after.x + 1f)
            assertEquals(before.x - state.cellW, after.x, 1f)
            rule.mainClock.autoAdvance = true
            rule.runOnIdle {
                val moving = HomeItem(1, WidgetContent(-1, "test"), 0, 2, 2, 2)
                val other = HomeItem(2, WidgetContent(-2, "test"), 0, 0, 2, 1)
                val icon = HomeItem(3, AppContent(apps[0].key), 0, 1)
                graph.layout.edit { HomeLayout(listOf(moving, other, icon), nextId = 4, seeded = true) }
            }
            rule.waitForIdle()
            rule.runOnIdle {
                val moving = graph.layout.layout.value.items.first()
                val session = DragSession(DragSource.Placed(moving), null, Offset.Zero, Offset.Zero)
                val result = state.plan(session, DropTarget.Feed(0, 0))!!
                assertTrue(result.layout.items.drop(1).all { !it.overlaps(0, 0, 2, 2) })
                graph.layout.edit { result.layout }
            }
            rule.waitForIdle()
            rule.runOnIdle {
                val widget = HomeItem(1, WidgetContent(-1, "test"), 0, 0, 2, 1)
                val fixed = listOf(HomeItem(2, AppContent(apps[0].key), 2, 0),
                    HomeItem(3, AppContent(apps[1].key), 4, 0))
                graph.layout.edit { HomeLayout(listOf(widget) + fixed, nextId = 4, seeded = true) }
                val incoming = DragSession(DragSource.New(AppContent(apps[0].key)), apps[0].icon, Offset.Zero, Offset.Zero)
                val snapshot = graph.layout.layout.value
                assertNull(state.plan(incoming, DropTarget.Feed(0, 0)))
                assertEquals(snapshot, graph.layout.layout.value)
            }
        } finally {
            rule.mainClock.autoAdvance = true
            rule.runOnUiThread { state.cancelDrag(); graph.layout.edit { saved } }
        }
    }
}
