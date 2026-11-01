package app.foyer.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
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

/** Immediate spatial intent plus injected authoritative validation; all fixtures go through Edits. */
class FolderMorphTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun missingCollapseDestinationReleasesVisualOwnership() {
        val graph = rule.activity.graph
        var state: LauncherState? = null
        rule.activity.setContent {
            val scope = rememberCoroutineScope()
            val current = remember {
                LauncherState(graph, scope, rule.activity).also {
                    val key = app.foyer.data.AppKey("missing", "Main", 0)
                    val item = app.foyer.data.HomeItem(999, FolderContent(listOf(key, key)), 0, 0)
                    it.folderCollapses[item.id] = FolderCollapse(item, key,
                        Rect(0f, 0f, 20f, 20f), Rect(0f, 0f, 56f, 56f), false, null)
                }
            }
            state = current
            // Deliberately omit the replacement tile: it can never supply icon bounds.
            FolderCollapseOverlay(current)
        }
        rule.waitUntil(3000) { state?.folderCollapses?.isEmpty() == true }
        rule.onAllNodesWithTag("folder-survivor-999").assertCountEquals(0)
    }

    @Test fun cleanupHandsHeldAndReturningSurvivorToOneOwner() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        rule.waitUntil(10000) { graph.apps.apps.value.size >= 2 }
        val keys = graph.apps.apps.value.take(2).map { it.key }
        var state: LauncherState? = null
        try {
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                state = remember { LauncherState(graph, scope, rule.activity) }
            }
            rule.waitForIdle()
            for (returning in listOf(false, true)) rule.runOnUiThread {
                val current = state!!
                val initial = Edits.add(HomeLayout(seeded = true), FolderContent(keys), 0, 0)
                graph.layout.edit { initial }
                current.observeFolderChanges(initial, 56f, 5f)
                val entry = (initial.items.single().content as FolderContent).entries.first()
                current.folderSurfaceBounds[1] = Rect(0f, 0f, 56f, 56f)
                val session = DragSession(DragSource.Member(1, keys[0], entry.id), null, Offset.Zero, Offset(200f, 300f))
                current.startDrag(session)
                val expected = Rect(172f, 272f, 228f, 328f)
                if (returning) {
                    current.cancelDrag()
                    current.folderLanding!!.renderedBounds = expected
                }
                val cleaned = Edits.reconcileFolderApps(initial, setOf(keys[1]))
                graph.layout.edit { cleaned }
                current.observeFolderChanges(cleaned, 56f, 5f)
                val collapse = current.folderCollapses.getValue(1)
                assertEquals(expected, collapse.start)
                assertNull(current.drag)
                assertNull(current.folderLanding)
                // The disposed member's gesture may still send cancellation afterward.
                current.cancelDrag()
                assertNull(current.folderLanding)
                assertSame(collapse, current.folderCollapses[1])
                assertEquals(AppContent(keys[0]), cleaned.items.single().content)
            }
        } finally {
            rule.runOnUiThread { state?.cancelDrag(); graph.layout.edit { saved } }
        }
    }

    @Test fun panelAndDockCancellationReturnToIconGeometry() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        var state: LauncherState? = null
        rule.waitUntil(10000) { graph.apps.apps.value.isNotEmpty() }
        val key = graph.apps.apps.value.first().key
        try {
            graph.layout.edit {
                Edits.add(Edits.add(HomeLayout(seeded = true), FolderContent(listOf(key, key)), 1, 2), AppContent(key), 0, 0, dock = true)
            }
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                val current = remember { LauncherState(graph, scope, rule.activity) }
                state = current
                FoyerRoot(current)
            }
            rule.waitForIdle()
            for (panel in listOf(true, false)) {
                rule.runOnUiThread { state!!.openFolder = if (panel) 1 else null }
                rule.waitForIdle()
                var expected = Rect.Zero
                rule.mainClock.autoAdvance = false
                rule.runOnUiThread {
                    val current = state!!
                    val source = if (panel) {
                        val entry = (graph.layout.layout.value.items.first().content as FolderContent).entries.first()
                        expected = current.folderMemberFrames.getValue(1L to entry.id).invoke()!!.bounds
                        DragSource.Member(1, key, entry.id)
                    } else {
                        val item = graph.layout.layout.value.items.last()
                        expected = current.appIconBounds.getValue(item.id)
                        DragSource.Placed(item)
                    }
                    current.startDrag(DragSession(source, null, Offset.Zero, expected.center))
                    current.moveDrag(expected.center + Offset(100f, -150f))
                    current.cancelDrag()
                }
                var last = Rect.Zero
                for (frame in 0..120) {
                    rule.mainClock.advanceTimeBy(16)
                    val nodes = rule.onAllNodesWithTag("folder-landing", useUnmergedTree = true).fetchSemanticsNodes()
                    if (nodes.isNotEmpty()) last = nodes.single().boundsInRoot
                    else if (frame > 0) break
                }
                assertEquals(expected.center.x, last.center.x, 3f)
                assertEquals(expected.center.y, last.center.y, 3f)
                assertEquals(expected.width, last.width, 3f)
                rule.mainClock.autoAdvance = true
                rule.waitForIdle()
            }
        } finally {
            rule.runOnUiThread { state?.cancelDrag(); graph.layout.edit { saved } }
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun blockingValidationCannotHoldTheVisualDeadline() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var state: LauncherState? = null
        rule.waitUntil(10000) { graph.apps.apps.value.isNotEmpty() }
        val key = graph.apps.apps.value.first().key
        try {
            graph.layout.edit { Edits.add(HomeLayout(seeded = true), AppContent(key), 0, 2) }
            val initial = graph.layout.layout.value
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                val current = remember { LauncherState(graph, scope, rule.activity) {
                    started.countDown()
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    emptySet()
                } }
                state = current
                FoyerRoot(current)
            }
            rule.waitForIdle()
            rule.runOnUiThread {
                val current = state!!
                val item = initial.items.single()
                current.startDrag(DragSession(DragSource.Placed(item), null, Offset.Zero, current.appIconBounds.getValue(item.id).center))
                current.endDrag()
            }
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            rule.waitUntil(4000) { state!!.folderLanding?.returning == true || state!!.drag == null }
            assertEquals(1L, release.count)
            assertEquals(initial, graph.layout.layout.value)
            rule.waitUntil(5000) { state!!.drag == null && state!!.folderLanding == null }
            release.countDown()
            rule.waitForIdle()
            assertEquals(initial, graph.layout.layout.value)
        } finally {
            release.countDown()
            rule.runOnUiThread { state?.cancelDrag(); graph.layout.edit { saved } }
        }
    }

    @Test fun continuousAutoScrollDoesNotRepeatMergeFeedback() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        lateinit var state: LauncherState
        rule.waitUntil(10000) { graph.apps.apps.value.isNotEmpty() }
        val key = graph.apps.apps.value.first().key
        try {
            rule.runOnUiThread {
                graph.layout.edit { Edits.add(Edits.add(HomeLayout(seeded = true), AppContent(key), 2, 3), AppContent(key), 0, 3) }
            }
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                state = remember { LauncherState(graph, scope, rule.activity) }
                FoyerRoot(state)
            }
            rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            rule.runOnUiThread {
                val feedback = mutableListOf<Boolean>()
                state.mergeFeedback = { feedback += it }
                val source = graph.layout.layout.value.items.last()
                val target = state.appIconBounds.getValue(1).center
                state.startDrag(DragSession(DragSource.Placed(source), null, Offset.Zero,
                    state.appIconBounds.getValue(source.id).center))
                state.moveDrag(target)
                assertEquals(listOf(true), feedback)
                state.feedAutoScrolling = true
                // Simulate fresh placement stamps between scrolling frames. Even when
                // the measured offset is unchanged across refreshes, scrolling is active.
                repeat(8) {
                    graph.layout.layout.value.items.forEach { item ->
                        state.appIconMeasurements[item.id] = item to state.feedScroll.value
                    }
                    state.refreshDragTargets()
                    state.moveDrag(target)
                    state.refreshDragTargets()
                    assertNull(state.mergeReady)
                }
                assertEquals(listOf(true, false), feedback)
                state.feedAutoScrolling = false
                state.refreshDragTargets()
                assertEquals(1L, state.mergeReady)
                assertEquals(listOf(true, false, true), feedback)
                state.cancelDrag()
            }
        } finally {
            rule.runOnUiThread { runCatching { state.cancelDrag() }; graph.layout.edit { saved } }
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun immediateReleaseMergesAndValidationTimeoutNeverCommitsLate() {
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
                assertEquals(1L, state.mergeReady)
                state.endDrag()
                assertNotNull(state.folderLanding)
            }
            rule.waitUntil(5000) { state.drag == null && state.folderLanding == null }
            assertTrue(graph.layout.layout.value.items.single().content is FolderContent)
            rule.runOnUiThread { graph.layout.edit { initial } }
            rule.waitForIdle()
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

    @Test fun removingOneOfTwoCollapsesAndClearsPanelOwnership() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        lateinit var state: LauncherState
        rule.waitUntil(10000) { graph.apps.apps.value.isNotEmpty() }
        val key = graph.apps.apps.value.first().key
        try {
            rule.runOnUiThread {
                graph.layout.edit { Edits.add(HomeLayout(seeded = true), FolderContent(listOf(key, key), name = "Keep"), 2, 3) }
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
            rule.runOnUiThread { graph.layout.edit { Edits.folder(it, singleton.id, (it.items.single().content as FolderContent).let { folder -> folder.withoutEntry(folder.entries.first().id) }) } }
            rule.waitUntil { state.openFolder == null && state.folderTransitionId == null && state.folderPanelBounds == null }
            rule.runOnUiThread {
                assertEquals(AppContent(key), graph.layout.layout.value.items.single().content)
                assertFalse(state.folderBounds.containsKey(singleton.id))
                assertFalse(state.folderSurfaceBounds.containsKey(singleton.id))
            }
        } finally {
            rule.runOnUiThread { runCatching { state.cancelDrag() }; graph.layout.edit { saved } }
        }
    }
}
