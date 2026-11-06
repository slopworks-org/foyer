package app.foyer.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.foyer.MainActivity
import app.foyer.data.AppContent
import app.foyer.data.Edits
import app.foyer.data.HomeLayout
import app.foyer.graph
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FeedScrollTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun scrollExistsOnlyWhenPlacedContentNeedsIt() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        lateinit var state: LauncherState
        rule.waitUntil(10_000) { graph.apps.apps.value.isNotEmpty() }
        val app = graph.apps.apps.value.first()
        try {
            rule.runOnIdle { graph.layout.edit { HomeLayout(seeded = true) } }
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                state = remember { LauncherState(graph, scope, rule.activity) }
                FoyerRoot(state)
            }
            rule.waitForIdle()
            val feed = rule.onNode(hasScrollAction() and SemanticsMatcher.keyNotDefined(SemanticsActions.ScrollToIndex))
            fun assertNoScroll() {
                rule.runOnIdle {
                    assertEquals(0, state.feedScroll.maxValue)
                    assertEquals(0, state.feedScroll.value)
                }
                feed.performTouchInput { swipeUp(startY = height * .6f, endY = height * .2f) }
                rule.runOnIdle { assertEquals(0, state.feedScroll.value) }
            }
            assertNoScroll()
            rule.runOnIdle {
                graph.layout.edit { Edits.add(it, AppContent(app.key), 0, 0) }
            }
            rule.waitForIdle()
            assertNoScroll()
            rule.runOnIdle {
                val row = (state.viewportCoords!!.size.height / state.cellH).toInt() + 1
                graph.layout.edit { Edits.add(it, AppContent(app.key), 0, row) }
            }
            rule.waitForIdle()
            rule.runOnIdle { assertTrue(state.feedScroll.maxValue > 0) }
            feed.performTouchInput { swipeUp(startY = height * .6f, endY = height * .2f) }
            rule.runOnIdle { assertTrue(state.feedScroll.value > 0) }
            rule.runOnIdle {
                graph.layout.edit { HomeLayout(seeded = true) }
            }
            rule.waitForIdle()
            assertNoScroll()
        } finally {
            rule.runOnIdle { graph.layout.edit { saved } }
        }
    }
}
