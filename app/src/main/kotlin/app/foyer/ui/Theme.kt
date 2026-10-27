package app.foyer.ui

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import app.foyer.data.Icon

/** How text drawn straight on the wallpaper should look. */
@Immutable
data class OnWallpaper(val darkText: Boolean) {
    val text: Color get() = if (darkText) Color(0xFF1F1F1F) else Color.White
}

val LocalOnWallpaper = staticCompositionLocalOf { OnWallpaper(darkText = false) }
val LocalThemedIcons = staticCompositionLocalOf { true }

@Composable
fun FoyerTheme(themedIcons: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme: ColorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

    var darkText by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val wm = WallpaperManager.getInstance(context)
        fun update(colors: WallpaperColors?) {
            darkText = colors != null && colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
        }
        update(wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM))
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) update(colors)
        }
        wm.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { wm.removeOnColorsChangedListener(listener) }
    }

    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalOnWallpaper provides OnWallpaper(darkText),
            LocalThemedIcons provides themedIcons,
            content = content,
        )
    }
}

@Composable
fun AppIcon(icon: Icon?, size: Dp, modifier: Modifier = Modifier, themed: Boolean = LocalThemedIcons.current) {
    val mono = icon?.mono
    when {
        icon == null -> Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
        themed && mono != null -> {
            val dark = isSystemInDarkTheme()
            val c = MaterialTheme.colorScheme
            Box(modifier.size(size).clip(CircleShape).background(if (dark) c.surfaceContainerHighest else c.primaryContainer)) {
                Image(
                    bitmap = mono,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(if (dark) c.primary else c.onPrimaryContainer),
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        else -> Image(icon.full, contentDescription = null, filterQuality = FilterQuality.High, modifier = modifier.size(size))
    }
}
