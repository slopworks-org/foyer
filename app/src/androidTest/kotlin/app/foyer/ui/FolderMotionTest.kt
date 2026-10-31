package app.foyer.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.input.ImeAction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.foyer.MainActivity
import app.foyer.data.*
import app.foyer.graph
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real layout, lazy composition and pointer ownership; fixtures always go through Edits. */
class FolderMotionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private lateinit var state: LauncherState
    private lateinit var saved: HomeLayout
    private lateinit var apps: List<AppKey>
    private var folderId = 0L

    @Before fun setup() {
        val graph = rule.activity.graph
        saved = graph.layout.layout.value
        rule.waitUntil(10_000) { graph.apps.apps.value.size >= 15 }
        apps = graph.apps.apps.value.map { it.key }
        rule.activity.setContent {
            val scope = rememberCoroutineScope()
            state = remember { LauncherState(graph, scope, rule.activity) }
            FoyerRoot(state)
        }
        rule.waitForIdle()
    }

    @After fun restore() {
        rule.runOnUiThread { state.cancelDrag(); state.graph.layout.edit { saved } }
        rule.mainClock.autoAdvance = true
    }

    private fun seed(count: Int, x: Int = 0, y: Int = 0) {
        rule.runOnIdle {
            state.openFolder = null
            state.graph.layout.edit {
                var result = Edits.add(HomeLayout(seeded = true), FolderContent(apps.take(count)), x, y)
                folderId = result.items.single().id
                // Keep a scrollable feed behind the panel to catch accidental feed autoscroll.
                result = Edits.add(result, AppContent(apps.last()), 4, 18)
                result
            }
        }
        rule.waitForIdle()
    }

    private fun open() {
        rule.runOnIdle { state.openFolder = folderId }
        rule.waitForIdle()
    }

    private fun showIme() {
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done)).performClick()
        rule.runOnIdle { state.keyboard?.show() }
        rule.waitUntil(5_000) {
            val insets = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
            val bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            bottom > 0 && (state.folderPanelBounds?.bottom ?: Float.MAX_VALUE) <= state.rootCoords!!.size.height - bottom
        }
        rule.waitForIdle()
    }

    private fun evidence(name: String) {
        val file = File(rule.activity.getExternalFilesDir(null), "folder-motion/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (name.startsWith("ime-")) {
            File(file.parentFile, "$name-device.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun sixMembersAtEdgesAndInterruptedMorph() {
        for ((x, y) in listOf(0 to 0, 4 to 0, 0 to 5, 4 to 5)) {
            seed(6, x, y)
            open()
            rule.runOnIdle {
                val panel = state.folderPanelBounds!!
                assertTrue(panel.left >= 0 && panel.right <= state.rootCoords!!.size.width)
                assertEquals(0, state.feedScroll.value)
            }
            evidence("six-edge-$x-$y")
            rule.mainClock.autoAdvance = false
            rule.runOnUiThread { state.openFolder = null }
            rule.mainClock.advanceTimeBy(96)
            evidence("six-closing-$x-$y")
            rule.runOnUiThread { state.openFolder = folderId }
            rule.mainClock.advanceTimeBy(80)
            evidence("six-reversed-$x-$y")
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
            rule.runOnIdle { assertEquals(folderId, state.folderTransitionId) }
        }
    }

    @Test fun matchedSixMemberOpeningFrames() {
        seed(6, 2, 3)
        evidence("matched-six-closed")
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { state.openFolder = folderId }
        for (frame in 0..5) {
            rule.mainClock.advanceTimeBy(if (frame == 0) 16 else 64)
            evidence("matched-six-open-$frame")
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        evidence("matched-six-open")
    }

    @Test fun scrolledClosingKeepsPreviewMembersAndCanReverse() {
        seed(apps.size)
        open()
        // The full-height emulator needs IME to put its 21 installed apps offscreen;
        // a compact emulator exercises the same regression without the keyboard.
        if (state.folderGridBounds!!.height >= state.folderRowHeight * 6) showIme()
        val grid = rule.runOnIdle { state.folderGridBounds!! }
        repeat(2) {
            rule.onRoot().performTouchInput { swipe(Offset(grid.center.x, grid.bottom - 10), Offset(grid.center.x, grid.top + 10), 500) }
        }
        rule.waitForIdle()
        evidence("scrolled-open")
        rule.runOnIdle {
            assertTrue("fixture must scroll beyond the first preview row", state.folderGridScroll > state.folderRowHeight)
            assertNull("regression must exercise an uncomposed preview member", state.panelMemberBounds[folderId to apps.first()])
        }
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { state.openFolder = null }
        rule.mainClock.advanceTimeBy(128)
        repeat(4) { rule.onNodeWithTag("folder-morph-preview-$it", useUnmergedTree = true).assertExists() }
        evidence("scrolled-closing")
        rule.mainClock.advanceTimeBy(48)
        evidence("scrolled-closing-late")
        rule.runOnUiThread { state.openFolder = folderId }
        rule.mainClock.advanceTimeBy(64)
        evidence("scrolled-reversed")
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(folderId, state.folderTransitionId) }
    }

    @Test fun dragAutoscrollRetainsPointerAndLeavesFeedStationaryWithIme() {
        seed(apps.size)
        open()
        showIme()
        evidence("ime-open")
        val grid = rule.runOnIdle { state.folderGridBounds!! }
        val first = rule.runOnIdle { state.panelMemberBounds[folderId to apps.first()]!!.center }
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput { down(first) }
        rule.mainClock.advanceTimeBy(600)
        rule.onRoot().performTouchInput {
            advanceEventTime(600)
            moveTo(Offset(grid.center.x, grid.bottom - 4), 150)
        }
        rule.mainClock.advanceTimeBy(2_000)
        evidence("ime-drag-autoscrolled")
        rule.runOnUiThread {
            assertTrue("panel must scroll past the pointer-owning row", state.folderGridScroll > state.folderRowHeight)
            assertNotNull("lazy item must retain gesture ownership offscreen", state.drag)
            assertEquals(0, state.feedScroll.value)
        }
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.runOnIdle {
            val members = (state.graph.layout.layout.value.items.first().content as FolderContent).apps
            assertTrue("drag must reach an initially offscreen slot", members.indexOf(apps.first()) >= 6)
            assertEquals(apps.toSet(), members.toSet())
            assertEquals(0, state.feedScroll.value)
        }
        evidence("ime-reordered")
        val last = rule.runOnIdle { state.panelMemberBounds[folderId to apps.first()]!!.center }
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput { down(last) }
        rule.mainClock.advanceTimeBy(600)
        rule.onRoot().performTouchInput {
            advanceEventTime(600)
            moveTo(Offset(grid.left + grid.width / 6, grid.top + 4), 150)
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.runOnUiThread {
            assertTrue("top edge must scroll back", state.folderGridScroll < state.folderRowHeight / 2)
            assertNotNull(state.drag)
            assertEquals(0, state.feedScroll.value)
        }
        evidence("ime-drag-back-to-top")
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(apps.first(), (state.graph.layout.layout.value.items.first().content as FolderContent).apps.first())
        }
    }
}
