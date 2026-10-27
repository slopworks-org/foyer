package app.foyer.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun FoyerRoot(state: LauncherState) {
    val layout by state.graph.layout.layout.collectAsStateWithLifecycle()
    val searchable by state.graph.searchable.collectAsStateWithLifecycle()
    state.searchable = searchable
    state.keyboard = LocalSoftwareKeyboardController.current
    state.focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val closePull = remember { SearchClosePull(state) }
    val lift = keyboardLift()

    FoyerTheme(themedIcons = layout.settings.themedIcons) {
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { state.rootCoords = it }
                    // Like Pixel Launcher: no back gesture on the bare home screen, so edge
                    // swipes are free for widgets and icons. Home windows are exempt from the
                    // usual 200dp exclusion limit. Overlays keep back to close themselves.
                    .then(if (state.isHomeIdle) Modifier.systemGestureExclusion() else Modifier),
            ) {
                // Search grows out of the dock: the dock's card becomes the panel's background
                // (see Dock), so the panel itself only brings its content.
                Home(state = state, layout = layout)

                SearchPanel(
                    state = state,
                    searchable = searchable,
                    modifier = Modifier
                        .graphicsLayer {
                            // Closed: moved off screen, so it neither shows nor catches touches.
                            val closed = state.searchProgress.value <= 0.001f
                            alpha = if (closed) 0f else 1f
                            translationY = if (closed) size.height else 0f
                            // Search content only shows below the dock's icons, never over them.
                            val p = state.searchProgress.value
                            val top = state.searchEdge(p, lift())
                            shape = object : Shape {
                                override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
                                    Outline.Rectangle(Rect(0f, top, size.width, size.height))
                            }
                            clip = p < 1f
                        }
                        // Pull down to close: lists hand over what they cannot scroll, the rest
                        // of the panel (field, empty space) is dragged directly.
                        .nestedScroll(closePull)
                        .verticalSwipe(
                            enabled = { state.searchOpen && state.drag == null && state.menu == null },
                            direction = 1,
                            onStart = closePull::start,
                            onDelta = { dy -> state.dragSearch(-dy / state.screenHeight) },
                            onEnd = closePull::release,
                        ),
                )
                // Predictive back: the panel slides away with the gesture, showing home underneath.
                PredictiveBackHandler(enabled = state.searchOpen && state.menu == null) { events ->
                    try {
                        events.collect { e -> state.searchProgress.snapTo(1f - 0.4f * e.progress) }
                        state.closeSearch()
                    } catch (c: CancellationException) {
                        scope.launch { state.searchProgress.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)) }
                        throw c
                    }
                }
                BackHandler(enabled = state.selectedWidget != null && state.menu == null) { state.selectedWidget = null }

                SystemBarsAppearance(state)

                DragGhost(state, iconSize = state.iconSize)
                MenuOverlay(state)
                WidgetPicker(state)
                SettingsScreen(state, layout.settings)
                AliasDialog(state)
            }
        }
    }
}

/**
 * Status bar icons: on the bare wallpaper they follow the wallpaper's own hint (white on dark
 * wallpapers, like Pixel Launcher); over search and other panels they follow the theme.
 */
@Composable
private fun SystemBarsAppearance(state: LauncherState) {
    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()
    val darkText = LocalOnWallpaper.current.darkText
    val overPanel by remember {
        derivedStateOf { state.searchProgress.value > 0.5f || state.settingsOpen || state.widgetPickerAt != null }
    }
    val lightBars = if (overPanel) !darkTheme else darkText
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightBars
            // The navigation bar always sits on the dock or a panel, never on bare wallpaper.
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

/**
 * Closing search by pulling the results down. A list scrolls first; whatever it cannot use
 * (it is already at its edge, or too short to scroll) moves the panel instead, and moving
 * back up takes the panel back before the list scrolls again.
 */
private class SearchClosePull(private val state: LauncherState) : NestedScrollConnection {
    private var active = false

    fun start() {
        active = true
        // The keyboard stays until 30% of the way down (see dragSearch); the dock rests on it meanwhile.
        state.beginSearchDrag(opening = false)
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!active || source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
        val before = state.searchDragProgress
        state.dragSearch(-available.y / state.screenHeight)
        val used = (state.searchDragProgress - before) * state.screenHeight
        return Offset(0f, -used)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Once pulling, keep going all the way: past halfway search no longer counts as open.
        if (source != NestedScrollSource.UserInput || available.y <= 0f || !(active || state.searchOpen)) return Offset.Zero
        if (!active) start()
        state.dragSearch(-available.y / state.screenHeight)
        return Offset(0f, available.y)
    }

    fun release(velocityY: Float) {
        active = false
        state.releaseSearchDrag(velocityY, opening = false)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!active) return Velocity.Zero
        release(available.y)
        return available
    }
}
