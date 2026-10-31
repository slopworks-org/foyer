package app.foyer.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
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
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("mkdir -p /sdcard/Download/foyer-three-fixes").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        automation.adoptShellPermissionIdentity("android.permission.SET_WALLPAPER")
        try { android.app.WallpaperManager.getInstance(rule.activity).clear() }
        finally { automation.dropShellPermissionIdentity() }
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
                var result = Edits.add(HomeLayout(seeded = true), FolderContent(apps.take(count), name = "Everyday"), x, y)
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
        file.outputStream().use { rule.onNode(isRoot() and SemanticsMatcher("full activity root") { it.boundsInRoot.width > 100f }).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val device = File(file.parentFile, "$name-device.png")
        device.outputStream().use {
            automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // Download survives Gradle uninstalling the instrumented APK after a run.
        automation.executeShellCommand("cp ${file.absolutePath} ${device.absolutePath} /sdcard/Download/foyer-three-fixes/").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }

    private fun assertPreviewIdentities() {
        for (index in listOf(0, 1, 3, 4)) {
            rule.onNodeWithTag("folder-morph-preview-$index", useUnmergedTree = true)
                .assertContentDescriptionEquals(apps[index].toString())
        }
        rule.onNodeWithTag("folder-morph-preview-2", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun threeCorrectionsEvidence() {
        seed(3, 2, 3)
        val caption = rule.onNodeWithTag("folder-caption-$folderId", useUnmergedTree = true)
        val bounds = caption.fetchSemanticsNode().boundsInRoot
        fun captionContrast(): Long {
            val bitmap = caption.captureToImage().asAndroidBitmap()
            var contrast = 0L
            for (y in 0 until bitmap.height) for (x in 1 until bitmap.width) {
                val a = bitmap.getPixel(x - 1, y)
                val b = bitmap.getPixel(x, y)
                contrast += kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                    kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
                    kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
            }
            return contrast
        }
        val contrast = captionContrast()
        evidence("01-three-app-preview")
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { state.openFolder = folderId }
        for (frame in 0..18) {
            rule.mainClock.advanceTimeBy(16)
            caption.assertIsDisplayed()
            assertEquals(bounds, caption.fetchSemanticsNode().boundsInRoot)
            if (frame <= 1) assertTrue("desktop text must still be drawn before the panel covers it", captionContrast() > contrast * .7)
            evidence("03-open-%02d".format(frame))
        }
        rule.runOnUiThread { state.openFolder = null }
        for (frame in 0..16) {
            rule.mainClock.advanceTimeBy(16)
            caption.assertIsDisplayed()
            assertEquals(bounds, caption.fetchSemanticsNode().boundsInRoot)
            if (frame == 12) assertTrue("desktop text must be revealed before the transition is disposed", captionContrast() > contrast * .7)
            evidence("03-close-%02d".format(frame))
        }
        rule.mainClock.autoAdvance = true
        open()
        evidence("01-three-open-grid")
        val homeDock = rule.runOnIdle { state.dockSurfaceCoords!!.boundsInRoot() }
        showIme()
        assertEquals(homeDock, rule.runOnIdle { state.dockSurfaceCoords!!.boundsInRoot() })
        val resting = state.dockRowBottomPx
        val dock = state.dockSurfaceCoords!!.boundsInRoot()
        assertTrue("dock must stay behind the keyboard", dock.bottom > resting)
        val name = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
        name.performTextReplacement("Everyday")
        evidence("ime-02-rename-keyboard")
        rule.runOnIdle { state.openFolder = null }
        rule.waitForIdle()
        rule.runOnIdle { state.openSearch() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(state.searchActive)
            assertTrue(state.searchProgress.value > .99f)
            assertTrue(state.dockSurfaceCoords!!.boundsInRoot().top < homeDock.top)
        }
        evidence("ime-02-real-search")
    }

    @Test fun landingAcrossPreviewCounts() {
        for (count in listOf(0, 1, 2, 3, 4, 5, 8, 9, 10)) {
            for (large in listOf(false, true)) {
                if (large && count >= 9) continue
                seed(count, 2, 3)
                rule.runOnIdle {
                    state.graph.layout.edit { current ->
                        val folder = current.items.first { it.id == folderId }.content as FolderContent
                        Edits.folder(current, folderId, folder.copy(large = large))
                    }
                }
                rule.waitForIdle()
                rule.mainClock.autoAdvance = false
                rule.runOnUiThread {
                    val key = apps[count]
                    val bounds = state.folderBounds[folderId]!!
                    val release = Offset(bounds.right - 8, bounds.bottom - 8)
                    state.startDrag(DragSession(DragSource.New(AppContent(key)), state.appFor(AppContent(key))?.icon,
                        Offset.Zero, release).also { it.visualScale = 1.12f })
                    assertEquals(DropTarget.Folder(folderId), state.targetFor(state.drag!!))
                    assertNotNull(state.plan(state.drag!!, DropTarget.Folder(folderId)))
                    state.endDrag()
                }
                // The drop validates app availability asynchronously.
                rule.waitUntil(5_000) {
                    rule.mainClock.advanceTimeBy(16)
                    (state.graph.layout.layout.value.items.first().content as FolderContent).apps.size == count + 1
                }
                rule.runOnUiThread {
                    val landing = state.folderLanding!!
                    val bounds = state.folderBounds[folderId]!!
                    assertEquals(Offset(bounds.right - 8, bounds.bottom - 8), landing.origin)
                    assertEquals(1.12f, landing.scale)
                }
                for (frame in 0..22) {
                    rule.mainClock.advanceTimeBy(16)
                    if (frame == 5) rule.onNodeWithTag("folder-landing").assertIsDisplayed()
                    evidence("04-${if (large) "large" else "small"}-$count-%02d".format(frame))
                }
                rule.mainClock.autoAdvance = true
                rule.waitForIdle()
                rule.runOnIdle {
                    assertNull(state.folderLanding)
                    assertEquals(apps.take(count + 1), (state.graph.layout.layout.value.items.first().content as FolderContent).apps)
                }
            }
        }
    }

    @Test fun landingFromDesktopAndFolderAndSearchWithInterruptedPanel() {
        for (source in 0..2) {
            seed(5, 2, 3)
            val key = apps[6]
            var dragSource: DragSource = DragSource.New(AppContent(key))
            if (source < 2) rule.runOnIdle {
                state.graph.layout.edit { current ->
                    val result = Edits.add(current, if (source == 0) AppContent(key)
                        else FolderContent(listOf(key), name = "Source"), 0, 0)
                    dragSource = if (source == 0) DragSource.Placed(result.items.last())
                        else DragSource.Member(result.items.last().id, key)
                    result
                }
            }
            if (source == 2) {
                rule.runOnIdle { state.openSearch() }
                rule.waitForIdle()
            } else open()
            rule.mainClock.autoAdvance = false
            rule.runOnUiThread {
                val release = state.folderBounds[folderId]!!.center
                state.startDrag(DragSession(dragSource, state.appFor(AppContent(key))?.icon, Offset.Zero, release))
            }
            rule.mainClock.advanceTimeBy(1_000)
            if (source == 2) {
                rule.runOnUiThread { state.moveDrag(state.drag!!.pointer) }
                rule.waitUntil(5_000) {
                    rule.mainClock.advanceTimeBy(16)
                    state.openFolder == folderId
                }
            }
            rule.mainClock.advanceTimeBy(320)
            rule.runOnUiThread {
                val panel = state.folderPanelBounds!!
                state.drag!!.pointer = Offset(panel.right - 16, panel.bottom - 12)
                assertEquals(folderId, (state.targetFor(state.drag!!) as DropTarget.Folder).id)
                state.endDrag()
            }
            rule.waitUntil(5_000) {
                rule.mainClock.advanceTimeBy(16)
                (state.graph.layout.layout.value.items.first().content as FolderContent).apps.size == 6
            }
            for (frame in 0..24) {
                if (frame == 5) rule.runOnUiThread { state.openFolder = null }
                if (frame == 9) rule.runOnUiThread { state.openFolder = folderId }
                rule.mainClock.advanceTimeBy(16)
                evidence("04-source-$source-%02d".format(frame))
            }
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
            rule.runOnIdle {
                assertNull(state.folderLanding)
                assertEquals(apps.take(5) + key, (state.graph.layout.layout.value.items.first().content as FolderContent).apps)
                assertNull(state.drag)
            }
        }
    }

    @Test fun failedAndCanceledDropsNeverCreateLanding() {
        seed(9)
        rule.runOnIdle {
            state.graph.layout.edit { Edits.folder(it, folderId, (it.items.first().content as FolderContent).copy(large = true)) }
        }
        rule.waitForIdle()
        for (key in listOf(apps[10], apps.first())) {
            rule.runOnIdle {
                val bounds = state.folderBounds[folderId]!!
                state.startDrag(DragSession(DragSource.New(AppContent(key)), state.appFor(AppContent(key))?.icon,
                    Offset.Zero, Offset(bounds.right - 8, bounds.bottom - 8)))
                state.endDrag()
            }
            rule.waitUntil(5_000) { state.drag == null }
            rule.runOnIdle { assertNull(state.folderLanding) }
        }
        rule.runOnIdle {
            state.startDrag(DragSession(DragSource.New(AppContent(apps[10])), null, Offset.Zero, Offset.Zero))
            state.cancelDrag()
            assertNull(state.folderLanding)
            assertEquals(apps.take(9), (state.graph.layout.layout.value.items.first().content as FolderContent).apps)
        }
        seed(2)
        val before = state.graph.layout.layout.value
        rule.runOnIdle {
            val source = before.items.last()
            state.startDrag(DragSession(DragSource.Placed(source), state.appFor(source.content)?.icon,
                Offset.Zero, state.folderBounds[folderId]!!.center))
            state.endDrag()
            state.cancelDrag()
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(before, state.graph.layout.layout.value); assertNull(state.folderLanding) }
    }

    @Test fun fiveMemberIdentityAndThemedLabels() {
        seed(5, 2, 3)
        evidence("five-closed")
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { state.openFolder = folderId }
        rule.mainClock.advanceTimeBy(192)
        assertPreviewIdentities()
        evidence("five-opening")
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val label = rule.onNodeWithTag("folder-label-4", useUnmergedTree = true)
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val context = rule.activity
        val dark = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val scheme = if (dark) androidx.compose.material3.dynamicDarkColorScheme(context)
            else androidx.compose.material3.dynamicLightColorScheme(context)
        assertEquals(scheme.onSurface, results.single().layoutInput.style.color)
        evidence(if (dark) "five-dark-open" else "five-light-open")
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { state.openFolder = null }
        rule.mainClock.advanceTimeBy(96)
        assertPreviewIdentities()
        evidence(if (dark) "five-dark-closing" else "five-light-closing")
        rule.runOnUiThread { state.openFolder = folderId }
        rule.mainClock.advanceTimeBy(64)
        assertPreviewIdentities()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(apps.take(5), (state.graph.layout.layout.value.items.first().content as FolderContent).apps)
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
            rule.onNode(isRoot() and SemanticsMatcher("full activity root") { it.boundsInRoot.width > 100f }).performTouchInput { swipe(Offset(grid.center.x, grid.bottom - 10), Offset(grid.center.x, grid.top + 10), 500) }
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
        assertPreviewIdentities()
        evidence("scrolled-closing")
        rule.mainClock.advanceTimeBy(48)
        evidence("scrolled-closing-late")
        rule.runOnUiThread { state.openFolder = folderId }
        rule.mainClock.advanceTimeBy(64)
        assertPreviewIdentities()
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
        rule.onNode(isRoot() and SemanticsMatcher("full activity root") { it.boundsInRoot.width > 100f }).performTouchInput { down(first) }
        rule.mainClock.advanceTimeBy(600)
        rule.onNode(isRoot() and SemanticsMatcher("full activity root") { it.boundsInRoot.width > 100f }).performTouchInput {
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
        rule.onNode(isRoot() and SemanticsMatcher("full activity root") { it.boundsInRoot.width > 100f }).performTouchInput { up() }
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
        rule.onNode(isRoot() and SemanticsMatcher("full activity root") { it.boundsInRoot.width > 100f }).performTouchInput { down(last) }
        rule.mainClock.advanceTimeBy(600)
        rule.onNode(isRoot() and SemanticsMatcher("full activity root") { it.boundsInRoot.width > 100f }).performTouchInput {
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
        rule.onNode(isRoot() and SemanticsMatcher("full activity root") { it.boundsInRoot.width > 100f }).performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(apps.first(), (state.graph.layout.layout.value.items.first().content as FolderContent).apps.first())
        }
    }
}
