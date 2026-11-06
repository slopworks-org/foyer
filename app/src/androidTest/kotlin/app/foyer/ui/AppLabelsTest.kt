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

    @Test fun toggleUpdatesFeedAndDockAndSurvivesReload() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        lateinit var state: LauncherState
        rule.waitUntil(10000) { graph.apps.apps.value.isNotEmpty() }
        val app = graph.apps.apps.value.first()
        try {
            rule.runOnUiThread {
                graph.layout.edit {
                    Edits.add(Edits.add(HomeLayout(seeded = true), AppContent(app.key), 0, 1),
                        AppContent(app.key), 0, 0, dock = true)
                }
            }
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                state = remember { LauncherState(graph, scope, rule.activity) }
                FoyerRoot(state)
            }
            for (id in 1..2) rule.onNodeWithTag("app-label-$id").assertDoesNotExist()
            rule.onAllNodesWithContentDescription(app.label).assertCountEquals(2)
            rule.runOnIdle { state.settingsOpen = true }
            rule.onNodeWithText(rule.activity.getString(R.string.settings_app_labels)).performClick()
            rule.runOnIdle {
                assertTrue(graph.layout.layout.value.settings.showAppLabels)
                state.settingsOpen = false
            }
            for (id in 1..2) rule.onNodeWithTag("app-label-$id").assertIsDisplayed().assertTextEquals(app.label)
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
            for (id in 1..2) rule.onNodeWithTag("app-label-$id").assertDoesNotExist()
            rule.onAllNodesWithContentDescription(app.label).assertCountEquals(2)
        } finally {
            rule.runOnUiThread { graph.layout.edit { saved } }
        }
    }
}
