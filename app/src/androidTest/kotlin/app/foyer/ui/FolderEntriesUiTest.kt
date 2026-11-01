package app.foyer.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
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

class FolderEntriesUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun duplicateGesturesRemovalAndAccessibleCreation() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        lateinit var state: LauncherState
        val launched = mutableListOf<AppKey>()
        rule.waitUntil(10000) { graph.apps.apps.value.isNotEmpty() }
        val app = graph.apps.apps.value.first().key
        val a = FolderEntry(app); val b = FolderEntry(app)
        fun folder() = graph.layout.layout.value.items.first { it.id == 1L }.content as FolderContent
        fun shot(name: String) {
            val directory = File(rule.activity.getExternalFilesDir(null), "folder-entries").apply { mkdirs() }
            rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        fun drag(from: Offset, to: Offset) {
            rule.mainClock.autoAdvance = false
            rule.onRoot().performTouchInput { down(from) }
            rule.mainClock.advanceTimeBy(620)
            rule.onRoot().performTouchInput { advanceEventTime(620); moveTo(from + Offset(35f, 35f), 16) }
            rule.mainClock.advanceTimeBy(16)
            rule.onRoot().performTouchInput { moveTo(to, 240); up() }
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
        }
        try {
            rule.runOnIdle { graph.layout.edit { Edits.add(HomeLayout(seeded = true), FolderContent(entries = listOf(a, b)), 1, 2) } }
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                state = remember { LauncherState(graph, scope, object : LauncherActions by rule.activity {
                    override fun launchApp(app: AppInfo, bounds: android.graphics.Rect?) { launched += app.key }
                }) }
                FoyerRoot(state)
            }
            rule.waitForIdle()
            rule.runOnIdle { state.openFolder = 1 }
            rule.waitForIdle()
            rule.onRoot().performTouchInput { click(state.panelMemberBounds.getValue(1L to a.id).center) }
            rule.waitForIdle()
            rule.runOnIdle { state.openFolder = 1 }
            rule.waitForIdle()
            rule.onRoot().performTouchInput { click(state.panelMemberBounds.getValue(1L to b.id).center) }
            rule.waitForIdle()
            assertEquals(listOf(app, app), launched)
            rule.runOnIdle { state.openFolder = 1 }
            rule.waitForIdle()
            drag(state.panelMemberBounds.getValue(1L to b.id).center, state.panelMemberBounds.getValue(1L to a.id).center)
            assertEquals(listOf(b.id, a.id), folder().entries.map { it.id })
            shot("10-small-duplicate-reordered")
            // Actual long press and visible Remove action consume only the selected occurrence.
            rule.onRoot().performTouchInput { longClick(state.panelMemberBounds.getValue(1L to b.id).center) }
            rule.onNodeWithText(rule.activity.getString(R.string.action_remove)).performClick()
            rule.waitForIdle()
            assertEquals(a.id, folder().entries.single().id)
            shot("11-small-one-copy-removed")
            rule.runOnIdle {
                state.openFolder = null
                graph.layout.edit { Edits.folder(it, 1, FolderContent(entries = listOf(a.copy(slot = 0), b.copy(slot = 8)), large = true)) }
            }
            rule.waitForIdle()
            // Mode conversion packs by design. Moving the second copy addresses its stable ID.
            val surface = state.folderSurfaceBounds.getValue(1)
            val empty = Offset(surface.right - state.folderInsetPx - (surface.width - state.folderInsetPx * 2) / 6,
                surface.bottom - state.folderInsetPx - (surface.height - state.folderInsetPx * 2) / 6)
            drag(state.memberBounds.getValue(1L to b.id).center, empty)
            assertEquals(b.id, folder().slotEntries[8]?.id)
            assertEquals(a.id, folder().slotEntries[0]?.id)
            shot("12-large-duplicate-independent-slot")
            rule.onRoot().performTouchInput { longClick(state.memberBounds.getValue(1L to a.id).center) }
            rule.onNodeWithText(rule.activity.getString(R.string.action_remove)).performClick()
            rule.waitForIdle()
            assertEquals(b.id, folder().entries.single().id)
            assertNull(folder().slotEntries[0]); assertEquals(b.id, folder().slotEntries[8]?.id)
            shot("13-large-one-copy-removed-hole-retained")
            // Equivalent accessible creation is attached to apps, without a dedicated create UI.
            rule.runOnIdle { graph.layout.edit { Edits.add(Edits.add(HomeLayout(seeded = true), AppContent(app), 0, 0), AppContent(app), 2, 2) } }
            rule.waitForIdle()
            val label = graph.apps.apps.value.first().label
            val nodes = rule.onAllNodesWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNodes()
            val action = nodes.flatMap { it.config.getOrElse(SemanticsActions.CustomActions) { emptyList() } }
                .first { it.label.startsWith(rule.activity.getString(R.string.folder_combine_with, "").trim()) }
            rule.runOnIdle { assertTrue(action.action()) }
            rule.waitUntil { graph.layout.layout.value.items.size == 1 }
            assertEquals(listOf(app, app), (graph.layout.layout.value.items.single().content as FolderContent).apps)
            shot("14-accessible-combine-two-copies")
        } finally { rule.runOnIdle { state.cancelDrag(); state.openFolder = null; graph.layout.edit { saved } } }
    }
}
