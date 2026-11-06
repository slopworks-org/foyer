package app.foyer.ui

import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.foyer.MainActivity
import app.foyer.R
import app.foyer.data.*
import app.foyer.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AppLabelsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun toggleUpdatesFeedKeepsDockUnlabeledAndSurvivesReload() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        lateinit var state: LauncherState
        rule.waitUntil(10000) { graph.apps.apps.value.isNotEmpty() }
        val app = graph.apps.apps.value.first()
        try {
            rule.runOnUiThread {
                graph.layout.edit {
                    Edits.add(Edits.add(Edits.add(Edits.add(HomeLayout(seeded = true), AppContent(app.key), 0, 1),
                        AppContent(app.key), 0, 0, dock = true),
                        ShortcutContent(app.key.pkg, "test-shortcut", app.key.user, "New event"), 1, 0, dock = true),
                        ShortcutContent(app.key.pkg, "feed-shortcut", app.key.user, "Compose message"), 1, 1)
                }
            }
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                state = remember { LauncherState(graph, scope, rule.activity) }
                FoyerRoot(state)
            }
            for (id in 1..4) rule.onNodeWithTag("app-label-$id").assertDoesNotExist()
            rule.onAllNodesWithContentDescription(app.label).assertCountEquals(2)
            val dockHeight = rule.runOnIdle { state.dockContentPx }
            val originalIcon = rule.runOnIdle { state.appIconBounds.getValue(1) }
            rule.runOnIdle { state.settingsOpen = true }
            rule.onNodeWithText(rule.activity.getString(R.string.settings_app_labels)).performClick()
            rule.runOnIdle {
                assertTrue(graph.layout.layout.value.settings.showAppLabels)
                state.settingsOpen = false
            }
            rule.onNodeWithTag("app-label-1").assertIsDisplayed().assertTextEquals(app.label)
            for (id in 2..3) rule.onNodeWithTag("app-label-$id").assertDoesNotExist()
            rule.onNodeWithContentDescription("New event").assertIsDisplayed()
            rule.runOnIdle { assertEquals(dockHeight, state.dockContentPx, 0f) }
            val icon = rule.runOnIdle { state.appIconBounds.getValue(1) }
            val label = rule.onNodeWithTag("app-label-1").fetchSemanticsNode().boundsInRoot
            val cell = rule.onAllNodesWithContentDescription(app.label).fetchSemanticsNodes()
                .first { it.boundsInRoot.contains(icon.center) }.boundsInRoot
            assertEquals(cell.center.y, (icon.top + label.bottom) / 2f, 1f)
            assertEquals(cell.center.x, icon.center.x, 1f)
            assertTrue(icon.center.y < originalIcon.center.y)
            rule.onRoot().performTouchInput { longClick(icon.center) }
            rule.runOnIdle {
                assertEquals(icon, state.menu!!.anchor)
                state.menu = null
            }
            val shortcut = rule.onNodeWithContentDescription("Compose message")
            val shortcutCell = shortcut.fetchSemanticsNode().boundsInRoot
            val shortcutLabel = rule.onNodeWithTag("app-label-4").fetchSemanticsNode().boundsInRoot
            shortcut.performTouchInput { longClick() }
            rule.runOnIdle {
                assertEquals(shortcutCell.center.y, (state.menu!!.anchor.top + shortcutLabel.bottom) / 2f, 1f)
                state.menu = null
            }
            val file = File(rule.activity.filesDir, "home.json")
            rule.waitUntil(10000) {
                !File(file.path + ".new").exists() && !File(file.path + ".bak").exists() &&
                    runCatching { file.readText().contains("\"showAppLabels\":true") }.getOrDefault(false)
            }
            rule.runOnIdle {
                assertTrue(LayoutStore(rule.activity, CoroutineScope(Dispatchers.Main)).layout.value.settings.showAppLabels)
            }
            val image = rule.onRoot().captureToImage().asAndroidBitmap()
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?.let(::File) ?: rule.activity.getExternalFilesDir(null)!!
            output.mkdirs()
            File(output, "app-labels.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            rule.runOnIdle { state.settingsOpen = true }
            rule.onNodeWithText(rule.activity.getString(R.string.settings_app_labels)).performClick()
            rule.runOnIdle {
                assertFalse(graph.layout.layout.value.settings.showAppLabels)
                state.settingsOpen = false
            }
            for (id in 1..4) rule.onNodeWithTag("app-label-$id").assertDoesNotExist()
            rule.onAllNodesWithContentDescription(app.label).assertCountEquals(2)
            rule.runOnIdle { assertEquals(originalIcon, state.appIconBounds.getValue(1)) }
        } finally {
            rule.runOnUiThread { graph.layout.edit { saved } }
        }
    }
}
