package app.foyer.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.foyer.MainActivity
import app.foyer.R
import app.foyer.data.LayoutStore
import app.foyer.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UpdateSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun noDismissesQuestionPermanentlyAndManualCheckRemainsAvailable() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        try {
            rule.runOnIdle { graph.layout.edit { it.copy(settings = it.settings.copy(updateChecks = null)) } }
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                val state = remember { LauncherState(graph, scope, rule.activity).apply {
                    settingsOpen = true
                    isDefaultHome = true
                } }
                FoyerRoot(state)
            }
            rule.onNodeWithText(rule.activity.getString(R.string.updates_question)).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(rule.activity.getString(R.string.updates_no)).performScrollTo().performClick()
            rule.onNodeWithText(rule.activity.getString(R.string.updates_question)).assertDoesNotExist()
            rule.onNodeWithText(rule.activity.getString(R.string.updates_check)).assertDoesNotExist()
            rule.onNodeWithText(rule.activity.getString(R.string.updates_title)).performScrollTo().performClick()
            rule.onNodeWithText(rule.activity.getString(R.string.updates_check)).assertIsEnabled()
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.updates_back)).performClick()
            rule.onNodeWithText(rule.activity.getString(R.string.updates_question)).assertDoesNotExist()
            val reloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            try {
                // LayoutStore persists asynchronously; wait for its durable state, not just the UI.
                rule.waitUntil(5000) { LayoutStore(rule.activity, reloadScope).layout.value.settings.updateChecks == false }
            } finally { reloadScope.cancel() }
        } finally {
            rule.runOnIdle { graph.layout.edit { saved } }
        }
    }

    @Test fun loadingFailureAndAvailableUpdateHaveActionableStates() {
        var updates = 0
        rule.activity.setContent {
            MaterialTheme {
                Column { TestUpdateSettingsContent(false, UpdateUiState("0.1.0", false, "1.2.0", R.string.updates_error, true),
                    {}, {}, { updates++ }, {}) }
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.updates_download)).performScrollTo().performClick()
        assertEquals(1, updates)
        rule.onNodeWithText(rule.activity.getString(R.string.updates_recheck_error)).performScrollTo().assertIsDisplayed()
        rule.activity.setContent {
            MaterialTheme { Column { TestUpdateSettingsContent(false, UpdateUiState("0.1.0", true, null, 0, true), {}, {}, {}, {}) } }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.updates_checking)).assertIsNotEnabled()
    }
    @Test fun splitPreferenceNavigatesWithoutTogglingAndSwitchDoesNotNavigate() {
        var opened = 0
        var enabled by mutableStateOf(false)
        rule.activity.setContent {
            MaterialTheme { UpdatePreference(enabled, { opened++ }, { enabled = it }) }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.updates_title)).performClick()
        rule.runOnIdle { assertEquals(1, opened); assertFalse(enabled) }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.updates_automatic)).assertIsOff().performClick()
        rule.runOnIdle { assertEquals(1, opened); assertTrue(enabled) }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.updates_automatic)).assertIsOn().performClick()
        rule.runOnIdle { assertEquals(1, opened); assertFalse(enabled) }
    }

    @Test fun bannerFollowsDefaultHomeAndBackAndHomeResetTheSubpage() {
        val graph = rule.activity.graph
        val saved = graph.layout.layout.value
        lateinit var state: LauncherState
        try {
            rule.runOnIdle { graph.layout.edit { it.copy(settings = it.settings.copy(updateChecks = null)) } }
            rule.activity.setContent {
                val scope = rememberCoroutineScope()
                state = remember { LauncherState(graph, scope, rule.activity).apply { settingsOpen = true; isDefaultHome = false } }
                FoyerRoot(state)
            }
            val originalHeader = rule.onAllNodesWithText(rule.activity.getString(R.string.action_settings)).fetchSemanticsNodes().minBy { it.boundsInRoot.top }.boundsInRoot
            val homeTitle = rule.onNodeWithText(rule.activity.getString(R.string.settings_default_home_title)).fetchSemanticsNode().boundsInRoot
            val updateTitle = rule.onNodeWithText(rule.activity.getString(R.string.updates_question)).fetchSemanticsNode().boundsInRoot
            assertTrue(homeTitle.top < updateTitle.top)
            rule.onNodeWithText(rule.activity.getString(R.string.updates_title)).performScrollTo().performClick()
            rule.onNodeWithText(rule.activity.getString(R.string.updates_installed, "0.1.0")).assertIsDisplayed()
            assertNull(graph.layout.layout.value.settings.updateChecks)
            val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            val width = rule.activity.resources.displayMetrics.widthPixels
            val middle = rule.activity.resources.displayMetrics.heightPixels / 2
            automation.executeShellCommand("input swipe 1 $middle ${width * 3 / 4} $middle 350").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            rule.waitUntil(5000) {
                rule.onAllNodesWithContentDescription(rule.activity.getString(R.string.updates_back)).fetchSemanticsNodes().isEmpty()
            }
            val settingsTitle = rule.onAllNodesWithText(rule.activity.getString(R.string.action_settings)).fetchSemanticsNodes().minBy { it.boundsInRoot.top }.boundsInRoot
            assertEquals(originalHeader.left, settingsTitle.left, 1f)
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.updates_back)).assertDoesNotExist()
            rule.onNodeWithText(rule.activity.getString(R.string.updates_title)).performClick()
            // The launcher intent closes and reopens synchronously, without an intermediate frame.
            rule.runOnIdle { state.onHomePressed(true); state.settingsOpen = true }
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.updates_back)).assertDoesNotExist()
        } finally { rule.runOnIdle { graph.layout.edit { saved } } }
    }

    @Test fun checkCompletesWhileSubpageIsHiddenAndResultSurvivesReturn() {
        var visible by mutableStateOf(true)
        var loaded by mutableStateOf(false)
        rule.activity.setContent {
            MaterialTheme {
                UpdateSettings(onChoice = {}, loadRelease = {
                    kotlinx.coroutines.delay(200)
                    loaded = true
                    app.foyer.updates.Release("1.2.0", "https://example.com/unused-test-fixture")
                }) { update, actions ->
                    Column {
                        androidx.compose.material3.TextButton(onClick = { visible = !visible }) {
                            androidx.compose.material3.Text(if (visible) "Hide details" else "Show details")
                        }
                        if (visible) TestUpdateSettingsContent(false, update, actions.choose, actions.check, actions.download, actions.notifications)
                    }
                }
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.updates_check)).performScrollTo().performClick()
        rule.onNodeWithText("Hide details").performClick()
        rule.waitUntil(5000) { loaded }
        rule.onNodeWithText("Show details").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText(rule.activity.getString(R.string.updates_available, "1.2.0")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(rule.activity.getString(R.string.updates_download)).assertIsEnabled()
    }

    @Test fun localizedSplitPreferenceKeepsItsBoundsWhenToggledAtLargeFont() {
        var enabled by mutableStateOf(false)
        val configuration = android.content.res.Configuration(rule.activity.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag("ru"))
        }
        val localized = rule.activity.createConfigurationContext(configuration)
        rule.activity.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides localized,
                androidx.compose.ui.platform.LocalConfiguration provides configuration,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density, 1.3f),
            ) {
                MaterialTheme {
                    UpdatePreference(enabled, {}, { enabled = it }, androidx.compose.ui.Modifier.width(320.dp))
                }
            }
        }
        val title = localized.getString(R.string.updates_title)
        val toggle = localized.getString(R.string.updates_automatic)
        val off = rule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val switchOff = rule.onNodeWithContentDescription(toggle).fetchSemanticsNode().boundsInRoot
        rule.onNodeWithContentDescription(toggle).performClick()
        val on = rule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val switchOn = rule.onNodeWithContentDescription(toggle).fetchSemanticsNode().boundsInRoot
        assertEquals(off, on)
        assertEquals(switchOff, switchOn)
        rule.onNodeWithContentDescription(toggle).assertIsOn()
    }

    @Test fun mainUpdateSwitchIsOneAccessibleTargetAndTogglesBothWays() {
        var enabled by mutableStateOf(false)
        rule.activity.setContent {
            MaterialTheme {
                TestUpdateSettingsContent(enabled, UpdateUiState("0.1.0", false, null, 0, true),
                    { enabled = it }, {}, {}, {})
            }
        }
        rule.onAllNodes(isToggleable()).assertCountEquals(1)
        val offBounds = rule.onNodeWithText(rule.activity.getString(R.string.updates_automatic)).fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText(rule.activity.getString(R.string.updates_automatic)).assertIsOff().performClick()
        rule.runOnIdle { assertTrue(enabled) }
        assertEquals(offBounds, rule.onNodeWithText(rule.activity.getString(R.string.updates_automatic)).fetchSemanticsNode().boundsInRoot)
        rule.onAllNodes(isToggleable()).assertCountEquals(1)
        rule.onNodeWithText(rule.activity.getString(R.string.updates_automatic)).assertIsOn().performClick()
        rule.runOnIdle { assertFalse(enabled) }
    }

    @Test fun notificationAdvisoryOnlyAppearsWhenDeniedRegardlessOfAutomaticChecks() {
        var enabled by mutableStateOf(false)
        var notifications by mutableStateOf(false)
        var opened = 0
        var checked = 0
        rule.activity.setContent {
            MaterialTheme {
                TestUpdateSettingsContent(enabled, UpdateUiState("0.1.0", false, null, 0, notifications),
                    { enabled = it }, { checked++ }, {}, { opened++ })
            }
        }
        val action = rule.activity.getString(R.string.updates_notifications_action)
        val master = rule.activity.getString(R.string.updates_automatic)
        for (allowed in listOf(false, true)) {
            rule.runOnIdle { notifications = allowed }
            for (automatic in listOf(false, true)) {
                rule.runOnIdle { enabled = automatic }
                val title = rule.activity.getString(R.string.updates_notifications_title)
                val body = rule.activity.getString(R.string.updates_notifications_disabled)
                if (allowed) {
                    rule.onNodeWithText(title).assertDoesNotExist()
                    rule.onNodeWithText(body).assertDoesNotExist()
                    rule.onNodeWithText(action).assertDoesNotExist()
                } else {
                    rule.onNodeWithText(title).assertIsDisplayed()
                    rule.onNodeWithText(body).assertIsDisplayed()
                    assertTrue(rule.onNodeWithText(master).fetchSemanticsNode().boundsInRoot.bottom <
                        rule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top)
                    rule.onNodeWithText(action).assertIsEnabled().assertHasClickAction()
                    rule.onNodeWithText(action).performClick()
                }
            }
        }
        assertEquals(2, opened)
        rule.runOnIdle { enabled = false }
        rule.onNodeWithText(rule.activity.getString(R.string.updates_check)).performScrollTo().performClick()
        assertEquals(1, checked)
    }

    @Test fun masterBackgroundAndLabelRemainIdenticalInBothThemes() {
        var enabled by mutableStateOf(false)
        var dark by mutableStateOf(false)
        rule.activity.setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            MaterialTheme(colorScheme = if (dark) androidx.compose.material3.dynamicDarkColorScheme(context)
                else androidx.compose.material3.dynamicLightColorScheme(context)) {
                TestUpdateSettingsContent(enabled, UpdateUiState("0.1.0", false, null, 0, true),
                    { enabled = it }, {}, {}, {})
            }
        }
        for (night in listOf(false, true)) {
            rule.runOnIdle { dark = night; enabled = false }
            val master = rule.onNodeWithText(rule.activity.getString(R.string.updates_automatic))
            val off = master.captureToImage().toPixelMap()
            rule.runOnIdle { enabled = true }
            val on = master.captureToImage().toPixelMap()
            assertEquals(off.width, on.width)
            assertEquals(off.height, on.height)
            // Include the capsule and label, excluding only the trailing switch region.
            for (y in 0 until off.height) for (x in 0 until off.width * 3 / 4) {
                assertEquals("Theme $night pixel ($x, $y)", off[x, y], on[x, y])
            }
        }
    }

}

@androidx.compose.runtime.Composable
private fun TestUpdateSettingsContent(
    enabled: Boolean, state: UpdateUiState, onChoice: (Boolean) -> Unit,
    onCheck: () -> Unit, onUpdate: () -> Unit, onNotifications: () -> Unit,
) {
    UpdateSettingsContent(enabled, state, onChoice, onCheck, onUpdate, onNotifications,
        androidx.compose.ui.Modifier.verticalScroll(rememberScrollState()))
}
