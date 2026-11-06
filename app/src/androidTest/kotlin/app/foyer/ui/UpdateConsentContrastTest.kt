package app.foyer.ui

import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.foyer.MainActivity
import app.foyer.R
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Rendered pixels cover inherited content colors, transparency and Material button defaults. */
class UpdateConsentContrastTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun consentActionsRemainReadableAndDistinctAcrossPalettes() {
        val context = rule.activity
        // The phone's primary and primaryContainer are only 1.16:1 apart.
        // Reproduce those exact sampled roles without changing production dynamic colors.
        val cyan = darkColorScheme(
            primary = Color(0xFF65CCFF), onPrimary = Color(0xFF00425A),
            primaryContainer = Color(0xFF04C0FF), onPrimaryContainer = Color(0xFF003547),
            surface = Color(0xFF001723), surfaceContainerLow = Color(0xFF001723),
            surfaceContainerHigh = Color(0xFF173440), onSurface = Color(0xFFBFE6FF),
            onSurfaceVariant = Color(0xFF8DB5CB), outline = Color(0xFF8AAEBD),
        )
        val scenes = listOf(
            Scene("cyan-dark", cyan),
            Scene("dynamic-light", dynamicLightColorScheme(context)),
            Scene("dynamic-dark", dynamicDarkColorScheme(context)),
            Scene("lavender-light", lightColorScheme()),
            Scene("lavender-dark", darkColorScheme()),
            Scene("green-light", lightColorScheme(primary = Color(0xFF006D3B), onPrimary = Color.White)),
            Scene("cyan-ru-large", cyan, "ru", 1.5f),
        )
        var scene by mutableStateOf(scenes.first())
        val choices = mutableListOf<Boolean>()
        rule.activity.setContent {
            key(scene) {
                DisposableEffect(Unit) {
                    val bars = androidx.core.view.WindowCompat.getInsetsController(context.window, context.window.decorView)
                    val previous = bars.isAppearanceLightStatusBars
                    bars.isAppearanceLightStatusBars = scene.colors.surfaceContainerLow.luminance() > 0.5f
                    onDispose { bars.isAppearanceLightStatusBars = previous }
                }
                val config = Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(scene.language))
                }
                val localized = context.createConfigurationContext(config)
                CompositionLocalProvider(
                    LocalContext provides localized, LocalConfiguration provides config,
                    LocalDensity provides Density(LocalDensity.current.density, scene.fontScale),
                ) {
                    MaterialTheme(colorScheme = scene.colors) {
                        Surface(color = scene.colors.surfaceContainerLow) {
                            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                                .padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                                Text(localized.getString(R.string.action_settings),
                                    Modifier.padding(top = 24.dp, bottom = 24.dp, start = 8.dp),
                                    style = MaterialTheme.typography.headlineMedium)
                                UpdateConsentBanner(choices::add)
                                Spacer(Modifier.height(16.dp))
                                SettingSwitch(localized.getString(R.string.settings_themed_icons),
                                    localized.getString(R.string.settings_themed_icons_summary), false, {})
                                SettingSwitch(localized.getString(R.string.settings_app_labels),
                                    localized.getString(R.string.settings_app_labels_summary), false, {})
                                UpdatePreference(false, {}, {})
                            }
                        }
                    }
                }
            }
        }
        val evidence = StringBuilder("scene,no_text,yes_text,yes_fill_panel,no_outline_panel\n")
        for (current in scenes) {
            rule.runOnIdle { scene = current }
            val config = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(current.language))
            }
            val localized = context.createConfigurationContext(config)
            val no = localized.getString(R.string.updates_no)
            val yes = localized.getString(R.string.updates_yes)
            rule.onNodeWithText(yes).performScrollTo().assertIsDisplayed().assertHasClickAction()
            rule.onNodeWithText(no).assertIsDisplayed().assertHasClickAction()
            val noPixels = rule.onNodeWithText(no).captureToImage().toPixelMap()
            val yesPixels = rule.onNodeWithText(yes).captureToImage().toPixelMap()
            val panel = dominant(noPixels) // Transparent outlined button reveals its actual panel.
            val fill = dominant(yesPixels)
            val noContrast = labelContrast(no, panel)
            val yesContrast = labelContrast(yes, fill)
            val separation = contrast(fill, panel)
            // Sample the top-center outline, away from both rounded corners and label.
            val outline = (0 until noPixels.height / 4).map { noPixels[noPixels.width / 2, it] }
                .maxBy { contrast(it, panel) }
            val outlineContrast = contrast(outline, panel)
            assertTrue("${current.name} No label $noContrast", noContrast >= 4.5f)
            assertTrue("${current.name} Yes label $yesContrast", yesContrast >= 4.5f)
            assertTrue("${current.name} Yes silhouette $separation", separation >= 3f)
            assertTrue("${current.name} No outline $outlineContrast", outlineContrast >= 3f)
            evidence.append("${current.name},$noContrast,$yesContrast,$separation,$outlineContrast\n")
            if (InstrumentationRegistry.getArguments().getString("consentScreenshots") == "true") {
                rule.waitForIdle()
                shell("mkdir -p /data/local/tmp/foyer-consent")
                shell("screencap -p /data/local/tmp/foyer-consent/${current.name}.png")
            }
            // Capture before interaction so evidence never shows a pressed/ripple state.
            rule.onNodeWithText(no).performClick()
            rule.onNodeWithText(yes).performClick()
            assertEquals(listOf(false, true), choices.takeLast(2))
        }
        if (InstrumentationRegistry.getArguments().getString("consentScreenshots") == "true") {
            val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommandRw("tee /data/local/tmp/foyer-consent/contrast.csv")
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(evidence.toString().toByteArray()) }
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use {
                assertEquals(evidence.toString(), it.readBytes().decodeToString())
            }
        }
    }

    private fun labelContrast(label: String, background: Color): Float {
        val pixels = rule.onNodeWithText(label, useUnmergedTree = true).captureToImage().toPixelMap()
        // Require repeated glyph-core pixels. Antialiased edges blend into the background and
        // are not the text's effective foreground; a lone outlier must not pass this assertion.
        return histogram(pixels).filterValues { it >= 4 }.keys.maxOf { contrast(Color(it), background) }
    }

    private fun dominant(pixels: PixelMap) = Color(histogram(pixels).maxBy { it.value }.key)

    private fun histogram(pixels: PixelMap): Map<Int, Int> {
        val counts = mutableMapOf<Int, Int>()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y].toArgb()
            counts[color] = (counts[color] ?: 0) + 1
        }
        return counts
    }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }

    private data class Scene(val name: String, val colors: ColorScheme, val language: String = "en", val fontScale: Float = 1f)
}
