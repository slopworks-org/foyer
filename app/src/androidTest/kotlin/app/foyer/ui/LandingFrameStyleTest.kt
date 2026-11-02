package app.foyer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Pixel-level guard against doubled fill/outline alpha in large folder's split layers. */
class LandingFrameStyleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun opaqueSurfaceColorOverridesWallpaperContrastForBothLayers() {
        val surface = Color(0xFFF0F0F0)
        val foreground = Color(0xFF202020)
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme(surfaceContainerHigh = surface, onSurface = foreground)) {
                CompositionLocalProvider(LocalOnWallpaper provides OnWallpaper(darkText = false)) {
                    Row {
                        Box(Modifier.size(80.dp).testTag("surface-combined").background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .landingFrame(20.dp, color = MaterialTheme.colorScheme.onSurface))
                        Box(Modifier.size(80.dp).testTag("surface-split").background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Box(Modifier.size(80.dp).landingFrame(20.dp, outline = false, color = MaterialTheme.colorScheme.onSurface))
                            Box(Modifier.size(80.dp).landingFrame(20.dp, fill = false, color = MaterialTheme.colorScheme.onSurface))
                        }
                    }
                }
            }
        }
        for (tag in listOf("surface-combined", "surface-split")) {
            val pixels = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
            val fill = surface.red * .86f + foreground.red * .14f
            assertEquals(fill, pixels[pixels.width / 2, pixels.height / 2].red, .006f)
            val x = with(rule.density) { 4.75.dp.roundToPx() }
            assertEquals(fill * .55f + foreground.red * .45f, pixels[x, pixels.height / 2].red, .025f)
        }
    }

    @Test fun combinedAndSplitLayersUseIdenticalWallpaperColorAndAlpha() {
        rule.setContent {
            Row {
                for (darkText in listOf(false, true)) {
                    CompositionLocalProvider(LocalOnWallpaper provides OnWallpaper(darkText)) {
                        Box(Modifier.size(80.dp).testTag("combined-$darkText").background(Color.Gray).landingFrame(20.dp))
                        Box(Modifier.size(80.dp).testTag("split-$darkText").background(Color.Gray)) {
                            Box(Modifier.size(80.dp).landingFrame(20.dp, outline = false))
                            Box(Modifier.size(80.dp).landingFrame(20.dp, fill = false))
                        }
                    }
                }
            }
        }
        for (darkText in listOf(false, true)) {
            val combined = rule.onNodeWithTag("combined-$darkText").captureToImage().toPixelMap()
            val split = rule.onNodeWithTag("split-$darkText").captureToImage().toPixelMap()
            for (y in 0 until combined.height) for (x in 0 until combined.width) {
                // Adjacent nodes can land on different subpixel origins; tolerate two
                // 8-bit levels of rasterization, far below a duplicated alpha layer.
                assertEquals("$darkText at $x,$y", combined[x, y].red, split[x, y].red, .009f)
            }
            val base = if (darkText) 31f / 255 else 1f
            assertEquals(Color.Gray.red * .86f + base * .14f, combined[combined.width / 2, combined.height / 2].red, .006f)
            val x = with(rule.density) { 4.75.dp.roundToPx() }
            val border = combined[x, combined.height / 2].red
            assertEquals((Color.Gray.red * .86f + base * .14f) * .55f + base * .45f, border, .025f)
        }
    }
}
