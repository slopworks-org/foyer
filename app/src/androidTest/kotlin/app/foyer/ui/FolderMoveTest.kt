package app.foyer.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import app.foyer.MainActivity
import app.foyer.R
import app.foyer.data.AppKey
import app.foyer.data.Edits
import app.foyer.data.FolderContent
import app.foyer.data.HomeLayout
import app.foyer.graph
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FolderMoveTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun withFolder(count: Int, test: (LauncherState) -> Unit) {
        val activity = rule.activity
        val graph = activity.graph
        val saved = graph.layout.layout.value
        val key = AppKey(activity.packageName, MainActivity::class.java.name, graph.apps.serialOf(android.os.Process.myUserHandle()))
        lateinit var state: LauncherState
        try {
            rule.runOnIdle {
                graph.layout.edit { Edits.add(HomeLayout(seeded = true), FolderContent(List(count) { key }, large = true), 0, 0) }
                activity.setContent {
                    val scope = rememberCoroutineScope()
                    state = remember { LauncherState(graph, scope, activity) }
                    FoyerRoot(state)
                }
            }
            rule.waitForIdle()
            test(state)
        } finally {
            rule.mainClock.autoAdvance = true
            rule.runOnIdle { graph.layout.edit { saved } }
        }
    }

    @Test fun emptySlotsGapsRimAndOuterStripsOpenTheFolderMenu() = withFolder(2) { state ->
        val cell = state.folderBounds.getValue(1)
        val card = state.folderSurfaceBounds.getValue(1)
        val members = state.memberBounds.values.sortedBy { it.left }
        val gap = Offset((members[0].right + members[1].left) / 2, members[0].center.y)
        val points = listOf(card.center, gap, Offset(members[0].right + 1, gap.y), Offset(card.left + 1, card.center.y),
            Offset(cell.center.x, (cell.top + card.top) / 2), Offset(cell.center.x, (cell.bottom + card.bottom) / 2))
        for (point in points) {
            rule.onRoot().performTouchInput { longClick(point) }
            rule.runOnIdle {
                val menu = requireNotNull(state.menu) { "No folder menu at $point" }
                assertFalse(menu.onIcon)
                assertEquals(card, menu.anchor)
                state.menu = null
            }
        }
    }

    @Test fun holdingAnIconDragsTheMemberAndHoldingTheStripDragsTheFolder() = withFolder(2) { state ->
        val item = state.graph.layout.layout.value.feed.single()
        val folder = item.content as FolderContent
        val entry = folder.entries.first()
        val member = state.memberBounds.getValue(item.id to entry.id)
        val cell = state.folderBounds.getValue(item.id)
        val strip = Offset(cell.center.x, (cell.top + state.folderSurfaceBounds.getValue(item.id).top) / 2)
        rule.mainClock.autoAdvance = false
        fun holdAndDrag(start: Offset, expected: DragSource, anchor: Offset) {
            rule.onRoot().performTouchInput { down(start) }
            rule.mainClock.advanceTimeBy(650)
            rule.onRoot().performTouchInput { advanceEventTime(650); moveTo(start + Offset(state.cellW / 2, 0f)) }
            rule.mainClock.advanceTimeByFrame()
            rule.runOnIdle {
                assertEquals(expected, state.drag?.source)
                assertEquals(anchor, state.drag?.origin)
            }
            rule.onRoot().performTouchInput { cancel() }
            rule.mainClock.advanceTimeUntil { state.drag == null }
        }
        holdAndDrag(member.center, DragSource.Member(item.id, entry.app, entry.id), member.center)
        holdAndDrag(strip, DragSource.Placed(item), cell.center)
    }

    @Test fun shrinkingOrRemovingASelectedFolderExitsMoveMode() = withFolder(2) { state ->
        val store = state.graph.layout
        val initial = store.layout.value
        val item = initial.feed.single()
        val folder = item.content as FolderContent
        rule.runOnIdle {
            state.selectedFolder = item.id
            store.editChange { Edits.folderChange(it, item.id, folder.copy(large = false)) }
        }
        rule.runOnIdle {
            assertNull(state.selectedFolder)
            store.edit { initial }
        }
        rule.runOnIdle {
            state.selectedFolder = item.id
            store.edit { Edits.remove(it, item.id) }
        }
        rule.runOnIdle { assertNull(state.selectedFolder) }
    }

    @Test fun memberMenuSelectsAndMovesTheWholeFullFolder() = withFolder(9) { state ->
        val source = state.graph.layout.layout.value.feed.single()
        val member = state.memberBounds.values.first().center
        fun select() {
            rule.onRoot().performTouchInput { longClick(member) }
            rule.runOnIdle { assertTrue(requireNotNull(state.menu).onIcon) }
            rule.onNodeWithText(rule.activity.getString(R.string.folder_move)).performTouchInput { click() }
            rule.runOnIdle {
                assertEquals(source.id, state.selectedFolder)
                assertFalse(state.isHomeIdle)
            }
        }
        select()
        rule.onRoot().performTouchInput { click(member) }
        rule.runOnIdle { assertNull(state.selectedFolder) }
        select()
        rule.onRoot().performTouchInput { click(member + Offset(2 * state.cellW, 0f)) }
        rule.runOnIdle { assertNull(state.selectedFolder) }
        select()
        rule.runOnIdle { state.onHomePressed(wasVisible = true) }
        rule.runOnIdle { assertTrue(state.isHomeIdle) }
        select()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { assertNull(state.selectedFolder) }
        select()
        rule.mainClock.autoAdvance = false
        val end = member + Offset(2 * state.cellW, 0f)
        rule.onRoot().performTouchInput { down(member); moveTo(end, 120) }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle {
            assertEquals(DragSource.Placed(source), state.drag?.source)
            assertNull(state.selectedFolder)
        }
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.runOnIdle {
            assertNull(state.drag)
            assertEquals(source.copy(x = 2), state.graph.layout.layout.value.feed.single())
        }
    }
}
