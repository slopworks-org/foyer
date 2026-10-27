package app.foyer.ui

import androidx.compose.ui.graphics.vector.ImageVector
import android.appwidget.AppWidgetProviderInfo
import android.graphics.Rect
import android.os.UserHandle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import app.foyer.R
import app.foyer.Graph
import app.foyer.data.AppContent
import app.foyer.data.AppInfo
import app.foyer.data.Content
import app.foyer.data.Edits
import app.foyer.data.HomeItem
import app.foyer.data.HomeLayout
import app.foyer.data.Icon
import app.foyer.data.ShortcutContent
import app.foyer.data.ShortcutEntry
import app.foyer.data.WidgetContent
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

interface LauncherActions {
    fun launchApp(app: AppInfo, bounds: Rect?)
    fun launchShortcut(pkg: String, id: String, user: UserHandle, bounds: Rect?)
    fun appDetails(app: AppInfo)
    fun uninstall(app: AppInfo)
    fun wallpaper()
    fun requestDefaultHome()
    fun expandNotifications()
    fun webSearch(query: String)
    fun addWidget(info: AppWidgetProviderInfo, x: Int, y: Int)
    fun reconfigureWidget(widgetId: Int)
}

class MenuAction(@StringRes val label: Int, val icon: ImageVector, val onClick: () -> Unit)

/**
 * Long-press popup: app shortcuts and actions. [anchor] is in root px; with [onIcon] it is an
 * icon tile, and the popup lines up with the icon itself rather than the whole cell.
 */
class MenuSpec(
    val anchor: androidx.compose.ui.geometry.Rect,
    val shortcuts: List<ShortcutEntry> = emptyList(),
    val actions: List<MenuAction>,
    val onIcon: Boolean = false,
)

sealed interface DragSource {
    data class Placed(val item: HomeItem) : DragSource
    data class New(val content: Content) : DragSource
}

@Stable
class DragSession(
    val source: DragSource,
    val icon: Icon?,
    /** Anchor minus pointer, px. The anchor is the icon centre, or a widget's top-left corner. */
    val anchorOffset: Offset,
    pointer: Offset,
) {
    var pointer by mutableStateOf(pointer)
    val anchor get() = pointer + anchorOffset
    val content: Content get() = (source as? DragSource.Placed)?.item?.content ?: (source as DragSource.New).content
    val w get() = (source as? DragSource.Placed)?.item?.w ?: 1
    val h get() = (source as? DragSource.Placed)?.item?.h ?: 1
    val placedId get() = (source as? DragSource.Placed)?.item?.id
    val isWidget get() = content is WidgetContent
}

sealed interface DropTarget {
    data class Feed(val x: Int, val y: Int) : DropTarget
    data class Dock(val slot: Int) : DropTarget
}

@Stable
class LauncherState(val graph: Graph, private val scope: CoroutineScope, val actions: LauncherActions) {
    // --- search ---
    val searchProgress = Animatable(0f)
    var query by mutableStateOf("")
    val searchFocus = FocusRequester()
    var keyboard: SoftwareKeyboardController? = null
    var focusManager: FocusManager? = null
    val searchOpen get() = searchProgress.targetValue > 0.5f

    /** Snapshot copy of [Graph.searchable] so tiles recompose when apps finish loading. */
    var searchable by mutableStateOf(graph.searchable.value)

    /**
     * Where the dock's icon row ends (and its search bar begins), in root px, with the dock at
     * rest; and the height of the handle plus icon row above it.
     */
    var dockRowBottomPx = 0f
    var dockContentPx = 0f

    /**
     * The line between the dock's icons and search content at progress [p], in root px. The dock
     * rests on the keyboard while it is up ([lift] px above the navigation bar), and the line
     * travels from there to the top of the screen; the icons ride above it, results below it.
     */
    fun searchEdge(p: Float, lift: Float) = (dockRowBottomPx - lift) * (1f - p)

    /** Left/right inset of the dock's search bar, which the search field matches exactly. */
    var searchBarInset by mutableStateOf(16.dp)

    /** The search field may only hold focus while search is in use; otherwise focus restore would pop the IME on home. */
    var searchActive by mutableStateOf(false)

    // --- overlays ---
    var menu by mutableStateOf<MenuSpec?>(null)
    var selectedWidget by mutableStateOf<Long?>(null)
    var widgetPickerAt by mutableStateOf<Pair<Int, Int>?>(null)
    var settingsOpen by mutableStateOf(false)
    var aliasEditor by mutableStateOf<AppInfo?>(null)
    var isDefaultHome by mutableStateOf(true)

    /** Nothing open on top of the feed: no search, menu, picker or settings. */
    val isHomeIdle get() = searchProgress.value == 0f && !searchActive && menu == null && widgetPickerAt == null && !settingsOpen && aliasEditor == null

    // --- geometry, filled in by the UI ---
    val feedScroll = ScrollState(0)
    var rootCoords: LayoutCoordinates? = null
    var gridCoords: LayoutCoordinates? = null
    var viewportCoords: LayoutCoordinates? = null
    var dockCoords: LayoutCoordinates? = null
    var cellW by mutableStateOf(1f)
    var cellH by mutableStateOf(1f)
    var iconSize by mutableStateOf(56.dp)

    // --- drag & drop ---
    var drag by mutableStateOf<DragSession?>(null)
        private set

    /** Where (root px) a just-dropped item should spring from. Consumed by the item. */
    val dropOrigins = mutableStateMapOf<Long, Offset>()

    private val layout get() = graph.layout.layout.value

    /**
     * Search position while a finger drags it. Kept here synchronously: launching one snapTo
     * per touch event would let them read stale values and drop part of the movement.
     */
    var searchDragProgress = 0f
        private set

    private var searchDragOpening = false

    fun beginSearchDrag(opening: Boolean) {
        searchDragProgress = searchProgress.value
        searchDragOpening = opening
    }

    fun dragSearch(delta: Float) {
        searchDragProgress = (searchDragProgress + delta).coerceIn(0f, 1f)
        val target = searchDragProgress
        scope.launch { searchProgress.snapTo(target) }
        // The keyboard waits until the gesture clearly means it: 30% of the way, both ways, so
        // even a slow pull looks calm. Going back past the point undoes it right away, with a
        // little slack so it does not flicker at the threshold.
        val (showAt, hideAt) = if (searchDragOpening) KEYBOARD_AT to KEYBOARD_AT - 0.1f else 1f - KEYBOARD_AT + 0.1f to 1f - KEYBOARD_AT
        if (target >= showAt && !searchActive) showKeyboard()
        if (target < hideAt && searchActive) hideKeyboard()
    }

    /** Search slides over the whole screen, so drags are measured against its height. */
    val screenHeight get() = (rootCoords?.size?.height ?: 1).coerceAtLeast(1).toFloat()

    /**
     * Finger lifted after dragging search open ([opening]) or shut. [velocityY] is in px/s,
     * positive downwards. A short flick or a quarter of the way is enough either way.
     */
    fun releaseSearchDrag(velocityY: Float, opening: Boolean) {
        val v = -velocityY / screenHeight
        val open = if (opening) {
            velocityY < -FLICK || (velocityY < FLICK && searchDragProgress > 0.25f)
        } else {
            !(velocityY > FLICK || (velocityY > -FLICK && searchDragProgress < 0.75f))
        }
        if (open) openSearch(v) else closeSearch(velocity = v)
    }

    fun openSearch(velocity: Float = 0f) {
        dismissOverlays()
        // Ask for the keyboard right away so it slides in together with the panel.
        showKeyboard()
        scope.launch {
            searchProgress.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow), velocity)
        }
    }

    fun showKeyboard() {
        searchActive = true
        scope.launch {
            withFrameNanos { } // let the field become focusable first
            try {
                searchFocus.requestFocus()
            } catch (_: IllegalStateException) {
            }
            keyboard?.show()
        }
    }

    private fun hideKeyboard() {
        keyboard?.hide()
        focusManager?.clearFocus(force = true)
        searchActive = false
    }

    fun closeSearch(animated: Boolean = true, velocity: Float = 0f) {
        hideKeyboard()
        scope.launch {
            if (animated) {
                searchProgress.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow), velocity)
            } else {
                searchProgress.snapTo(0f)
            }
            // A drag that started in search still needs its source row composed.
            if (drag == null && !searchOpen) query = ""
        }
    }

    fun dismissOverlays() {
        menu = null
        selectedWidget = null
    }

    /** Home button while already home: peel off one layer; with nothing open, go back to the top. */
    fun onHomePressed(wasVisible: Boolean) {
        val hadOverlay = menu != null || selectedWidget != null || widgetPickerAt != null || settingsOpen || searchOpen || aliasEditor != null
        dismissOverlays()
        aliasEditor = null
        widgetPickerAt = null
        settingsOpen = false
        if (searchOpen) closeSearch(animated = wasVisible)
        if (wasVisible && !hadOverlay) scope.launch { feedScroll.animateScrollTo(0) }
    }

    // --- launching ---

    fun launch(content: Content, bounds: Rect?) {
        when (content) {
            is AppContent -> searchable.apps["a/${content.app}"]?.let { actions.launchApp(it, bounds) }
            is ShortcutContent -> graph.apps.userOf(content.user)?.let { actions.launchShortcut(content.pkg, content.shortcutId, it, bounds) }
            is WidgetContent -> Unit
        }
    }

    fun appFor(content: Content): AppInfo? {
        val key = when (content) {
            is AppContent -> content.app
            is ShortcutContent -> return searchable.apps.values.firstOrNull { it.key.pkg == content.pkg && it.key.user == content.user }
            is WidgetContent -> return null
        }
        return searchable.apps["a/$key"]
    }

    fun shortcutFor(content: ShortcutContent): ShortcutEntry? =
        searchable.shortcuts["s/${content.user}/${content.pkg}/${content.shortcutId}"]

    // --- menus ---

    fun showItemMenu(item: HomeItem, anchor: androidx.compose.ui.geometry.Rect) {
        val app = appFor(item.content)
        val actionsList = buildList {
            // Same order as Pixel Launcher: app info, remove, uninstall; for widgets remove, settings.
            if (app != null && item.content is AppContent) {
                add(infoAction(app))
                add(aliasAction(app))
            }
            add(MenuAction(R.string.action_remove, MenuIcons.Close) { graph.removeItem(item) })
            if (item.content is WidgetContent) {
                val info = graph.widgetManager.getAppWidgetInfo(item.content.widgetId)
                if (info != null && info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0) {
                    add(MenuAction(R.string.action_settings, MenuIcons.Settings) { actions.reconfigureWidget(item.content.widgetId) })
                }
            }
            if (app != null && item.content is AppContent) uninstallAction(app)?.let(::add)
        }
        val shortcuts = if (app != null && item.content is AppContent) graph.apps.shortcutsOf(app) else emptyList()
        menu = MenuSpec(anchor, shortcuts, actionsList, onIcon = item.content !is WidgetContent)
    }

    fun showSearchMenu(content: Content, anchor: androidx.compose.ui.geometry.Rect) {
        val app = appFor(content)
        val actionsList = buildList {
            if (app != null && content is AppContent) {
                add(infoAction(app))
                add(aliasAction(app))
            }
            add(MenuAction(R.string.action_add_to_home, MenuIcons.Add) { addToFeed(content) })
            if (content is AppContent) {
                val freeSlot = (0 until HomeLayout.DOCK_SLOTS).firstOrNull { layout.dockSlotFree(it) }
                if (freeSlot != null) add(MenuAction(R.string.action_add_to_dock, MenuIcons.Dock) { graph.layout.edit { Edits.add(it, content, freeSlot, 0, dock = true) } })
                if (app != null) uninstallAction(app)?.let(::add)
            }
        }
        val shortcuts = if (app != null && content is AppContent) graph.apps.shortcutsOf(app) else emptyList()
        menu = MenuSpec(anchor, shortcuts, actionsList)
    }

    private fun infoAction(app: AppInfo) = MenuAction(R.string.action_app_info, MenuIcons.Info) { actions.appDetails(app) }

    private fun aliasAction(app: AppInfo) = MenuAction(R.string.action_aliases, MenuIcons.Edit) { aliasEditor = app }

    fun aliasesOf(app: AppInfo): List<String> = layout.aliases[app.key.toString()].orEmpty()

    /** [text] is comma-separated; blanks and repeats are dropped, and an empty list forgets the app. */
    fun saveAliases(app: AppInfo, text: String) {
        val list = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        graph.layout.edit { Edits.setAliases(it, app.key, list) }
    }

    private fun uninstallAction(app: AppInfo) =
        if (app.isSystem) null else MenuAction(R.string.action_uninstall, MenuIcons.Delete) { actions.uninstall(app) }

    fun showHomeMenu(cellX: Int, cellY: Int, anchor: androidx.compose.ui.geometry.Rect) {
        menu = MenuSpec(
            anchor,
            actions = listOf(
                MenuAction(R.string.action_widgets, MenuIcons.Widgets) { widgetPickerAt = cellX to cellY },
                MenuAction(R.string.action_wallpaper_style, MenuIcons.Wallpaper) { actions.wallpaper() },
                MenuAction(R.string.action_settings, MenuIcons.Settings) { settingsOpen = true },
            ),
        )
    }

    fun addToFeed(content: Content) = graph.layout.edit { Edits.addFirstFree(it, content) }

    fun pinShortcut(s: ShortcutEntry) = addToFeed(ShortcutContent(s.pkg, s.id, s.userSerial, s.label))

    // --- drag & drop ---

    fun startDrag(session: DragSession) {
        menu = null
        drag = session
        if (searchOpen) closeSearch()
    }

    fun moveDrag(pointer: Offset) {
        drag?.pointer = pointer
    }

    fun cancelDrag() {
        drag = null
        if (!searchOpen) query = ""
    }

    fun endDrag() {
        val s = drag ?: return
        val target = targetFor(s)
        val plan = target?.let { plan(s, it) }
        if (plan != null) {
            dropOrigins[plan.second] = s.anchor
            graph.layout.edit { plan.first }
        } else if (s.placedId != null) {
            // Spring back home.
            dropOrigins[s.placedId!!] = s.anchor
        }
        drag = null
        if (!searchOpen) query = ""
    }

    fun targetFor(s: DragSession): DropTarget? {
        val root = rootCoords ?: return null
        val p = s.anchor
        if (!s.isWidget) {
            dockCoords?.takeIf { it.isAttached }?.let { dc ->
                val local = dc.localPositionOf(root, p)
                if (local.x in 0f..dc.size.width.toFloat() && local.y in -cellH * 0.2f..dc.size.height.toFloat() + cellH) {
                    val slot = (local.x / (dc.size.width / HomeLayout.DOCK_SLOTS.toFloat())).toInt()
                    return DropTarget.Dock(slot.coerceIn(0, HomeLayout.DOCK_SLOTS - 1))
                }
            }
        }
        val grid = gridCoords?.takeIf { it.isAttached } ?: return null
        val local = grid.localPositionOf(root, p)
        val x: Int
        val y: Int
        if (s.isWidget) {
            x = (local.x / cellW).roundToInt().coerceIn(0, HomeLayout.COLUMNS - s.w)
            y = (local.y / cellH).roundToInt()
        } else {
            x = floor(local.x / cellW).toInt().coerceIn(0, HomeLayout.COLUMNS - 1)
            y = floor(local.y / cellH).toInt()
        }
        if (y < 0) return null
        return DropTarget.Feed(x, y)
    }

    /** The layout after dropping, plus the id of the item that landed; null if it cannot go there. */
    fun plan(s: DragSession, t: DropTarget): Pair<HomeLayout, Long>? {
        val l = layout
        val placed = (s.source as? DragSource.Placed)?.item?.let { p -> l.items.firstOrNull { it.id == p.id } }
        if (s.source is DragSource.Placed && placed == null) return null
        return when (t) {
            is DropTarget.Feed -> {
                if (l.isFree(t.x, t.y, s.w, s.h, ignore = placed?.id)) {
                    if (placed != null) Edits.move(l, placed.id, t.x, t.y, dock = false) to placed.id
                    else Edits.add(l, s.content, t.x, t.y) to l.nextId
                } else {
                    val other = l.feed.singleOrNull { it.id != placed?.id && it.overlaps(t.x, t.y, s.w, s.h) }
                    if (placed != null && other != null && other.w == 1 && other.h == 1 && s.w == 1 && s.h == 1 &&
                        (!placed.dock || other.content !is WidgetContent)
                    ) Edits.swap(l, placed, other) to placed.id else null
                }
            }
            is DropTarget.Dock -> {
                if (s.isWidget) return null
                if (l.dockSlotFree(t.slot, ignore = placed?.id)) {
                    if (placed != null) Edits.move(l, placed.id, t.slot, 0, dock = true) to placed.id
                    else Edits.add(l, s.content, t.slot, 0, dock = true) to l.nextId
                } else {
                    val other = l.dock.firstOrNull { it.x == t.slot && it.id != placed?.id }
                    if (placed != null && other != null && placed.w == 1 && placed.h == 1) Edits.swap(l, placed, other) to placed.id else null
                }
            }
        }
    }

    // --- widgets ---

    fun resizeWidget(item: HomeItem, x: Int, y: Int, w: Int, h: Int): Boolean {
        val l = layout
        if (!l.isFree(x, y, w, h, ignore = item.id)) return false
        graph.layout.edit { Edits.resize(it, item.id, x, y, w, h) }
        return true
    }
}

private const val FLICK = 400f
private const val KEYBOARD_AT = 0.3f
