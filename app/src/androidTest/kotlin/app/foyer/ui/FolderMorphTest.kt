package app.foyer.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.foyer.MainActivity
import app.foyer.data.AppContent
import app.foyer.data.Edits
import app.foyer.data.FolderContent
import app.foyer.data.HomeLayout
import app.foyer.graph
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real uptime recognition plus injected authoritative validation; all fixtures go through Edits. */
class FolderMorphTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun prearmReleaseReturnsAndValidationTimeoutNeverCommitsLate() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        lateinit var state: LauncherState
        rule.waitUntil(10000) { graph.apps.apps.value.isNotEmpty() }
        val key = graph.apps.apps.value.first().key
        var validationDelay = 0L
        try {
            rule.runOnUiThread {
                graph.layout.edit { Edits.add(Edits.add(HomeLayout(seeded = true), AppContent(key), 2, 3), AppContent(key), 0, 3) }
            }
            val initial = graph.layout.layout.value
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                state = remember { LauncherState(graph, scope, rule.activity) { keys ->
                    delay(validationDelay)
                    graph.apps.missingFolderApps(keys)
                } }
                FoyerRoot(state)
            }
            rule.waitForIdle()
            rule.waitUntil { state.appIconBounds.size == 2 }
            fun begin() = rule.runOnUiThread {
                val source = graph.layout.layout.value.items.last()
                val origin = state.appIconBounds.getValue(source.id).center
                state.startDrag(DragSession(DragSource.Placed(source), state.appFor(source.content)?.icon, Offset.Zero, origin))
                state.moveDrag(state.appIconBounds.getValue(1).center)
            }
            begin()
            rule.runOnUiThread {
                assertNull(state.mergeReady)
                state.endDrag()
                assertNotNull(state.folderLanding)
            }
            rule.waitUntil(5000) { state.drag == null && state.folderLanding == null }
            assertEquals(initial, graph.layout.layout.value)
            validationDelay = 1800
            begin()
            rule.waitUntil(5000) {
                rule.mainClock.advanceTimeBy(16)
                rule.runOnUiThread { state.refreshDragTargets() }
                state.mergeReady == 1L
            }
            rule.runOnUiThread {
                state.endDrag(Offset(300f, 0f))
                assertNotNull(state.folderLanding)
                assertEquals(initial, graph.layout.layout.value)
            }
            rule.waitUntil(6000) { state.drag == null && state.folderLanding == null }
            assertEquals(initial, graph.layout.layout.value)
            // A fresh armed gesture validates and leaves the resulting folder closed.
            validationDelay = 0
            begin()
            rule.waitUntil(5000) {
                rule.mainClock.advanceTimeBy(16)
                rule.runOnUiThread { state.refreshDragTargets() }
                state.mergeReady == 1L
            }
            rule.runOnUiThread { state.endDrag() }
            rule.waitUntil(5000) { graph.layout.layout.value.items.singleOrNull()?.content is FolderContent }
            rule.waitUntil(5000) { state.folderLanding == null }
            rule.runOnUiThread {
                assertNull(state.openFolder)
                assertTrue(state.mergePreviews.isEmpty())
                assertEquals(listOf(key, key), (graph.layout.layout.value.items.single().content as FolderContent).apps)
            }
        } finally {
            rule.runOnUiThread { runCatching { state.cancelDrag() }; graph.layout.edit { saved } }
        }
    }

    @Test fun removingLastMemberClearsPanelOwnershipAndKeepsSingletonUntouched() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        lateinit var state: LauncherState
        rule.waitUntil(10000) { graph.apps.apps.value.isNotEmpty() }
        val key = graph.apps.apps.value.first().key
        try {
            rule.runOnUiThread {
                graph.layout.edit { Edits.add(HomeLayout(seeded = true), FolderContent(listOf(key), name = "Keep"), 2, 3) }
            }
            val singleton = graph.layout.layout.value.items.single()
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                state = remember { LauncherState(graph, scope, rule.activity) }
                FoyerRoot(state)
            }
            rule.waitForIdle()
            rule.waitUntil { state.folderBounds.containsKey(singleton.id) }
            rule.runOnUiThread { state.openFolder = singleton.id }
            rule.waitUntil { state.folderPanelBounds != null }
            assertEquals(singleton, graph.layout.layout.value.items.single())
            rule.runOnUiThread { graph.layout.edit { Edits.folder(it, singleton.id, FolderContent()) } }
            rule.waitUntil { state.openFolder == null && state.folderTransitionId == null && state.folderPanelBounds == null }
            rule.runOnUiThread {
                assertTrue(graph.layout.layout.value.items.isEmpty())
                assertFalse(state.folderBounds.containsKey(singleton.id))
                assertFalse(state.folderSurfaceBounds.containsKey(singleton.id))
            }
        } finally {
            rule.runOnUiThread { runCatching { state.cancelDrag() }; graph.layout.edit { saved } }
        }
    }
}
