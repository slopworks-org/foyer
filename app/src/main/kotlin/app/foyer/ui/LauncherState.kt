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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import app.foyer.R
import app.foyer.Graph
import app.foyer.data.FolderContent
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
    data class Member(val folderId: Long, val key: app.foyer.data.AppKey, val entryId: String) : DragSource
    data class New(val content: Content) : DragSource
}

internal data class FolderLanding(val folderId: Long, val key: app.foyer.data.AppKey,
    val icon: Icon?, val origin: Offset, val scale: Float, val velocity: Offset = Offset.Zero,
    val releasedNanos: Long = System.nanoTime()) {
    var entryId by mutableStateOf<String?>(null)
    var validated by mutableStateOf(false)
}

@Stable
class DragSession(
    val source: DragSource,
    val icon: Icon?,
    /** Anchor minus pointer, px. The anchor is the icon centre, or a widget's top-left corner. */
    val anchorOffset: Offset,
    pointer: Offset,
) {
    var visualScale = 1f
    var pointer by mutableStateOf(pointer)
    val anchor get() = pointer + anchorOffset
    val content: Content get() = when (source) {
        is DragSource.Placed -> source.item.content
        is DragSource.Member -> AppContent(source.key)
        is DragSource.New -> source.content
    }
    val w get() = (source as? DragSource.Placed)?.item?.w ?: 1
    val h get() = (source as? DragSource.Placed)?.item?.h ?: 1
    val placedId get() = (source as? DragSource.Placed)?.item?.id
    val isWidget get() = content is WidgetContent
}

sealed interface DropTarget {
    data class Merge(val target: HomeItem) : DropTarget
    data class Feed(val x: Int, val y: Int) : DropTarget
    data class Folder(val id: Long, val index: Int = Int.MAX_VALUE) : DropTarget
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
    var folderFeedback by mutableStateOf<Int?>(null)
    private var requestedFolder by mutableStateOf<Long?>(null)
    var openFolder: Long?
        get() = requestedFolder?.takeIf { id -> (graph.layout.layout.value.items.find { it.id == id }?.content as? FolderContent)?.large == false }
        set(value) { requestedFolder = value?.takeIf { id -> (graph.layout.layout.value.items.find { it.id == id }?.content as? FolderContent)?.large == false } }
    val folderBounds = mutableStateMapOf<Long, androidx.compose.ui.geometry.Rect>()
    var folderInsetPx = 0f
    val folderSurfaceBounds = mutableStateMapOf<Long, androidx.compose.ui.geometry.Rect>()
    var folderTransitionId by mutableStateOf<Long?>(null)
    var folderGridBounds: androidx.compose.ui.geometry.Rect? = null
    var folderHitGridBounds: androidx.compose.ui.geometry.Rect? = null
    var folderGridColumns = 2
    var folderRowHeight = 1f
    var folderGridScroll = 0f
    internal var folderContentViewport by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    internal var folderLanding by mutableStateOf<FolderLanding?>(null)
    internal val folderMemberFrames = mutableMapOf<Pair<Long, String>, () -> FolderMemberVisual?>()
    val memberBounds = mutableStateMapOf<Pair<Long, String>, androidx.compose.ui.geometry.Rect>()
    val panelMemberBounds = mutableStateMapOf<Pair<Long, String>, androidx.compose.ui.geometry.Rect>()
    var folderPanelBounds: androidx.compose.ui.geometry.Rect? = null
    private var hoverJob: kotlinx.coroutines.Job? = null
    private var hoverFolder: Long? = null
    val appIconBounds = mutableStateMapOf<Long, androidx.compose.ui.geometry.Rect>()
    var mergeCandidate by mutableStateOf<Long?>(null)
        private set
    var mergeReady by mutableStateOf<Long?>(null)
        private set
    private var mergeJob: kotlinx.coroutines.Job? = null
    var menu by mutableStateOf<MenuSpec?>(null)
    var selectedWidget by mutableStateOf<Long?>(null)
    var widgetPickerAt by mutableStateOf<Pair<Int, Int>?>(null)
    var settingsOpen by mutableStateOf(false)
    var aliasEditor by mutableStateOf<AppInfo?>(null)
    var isDefaultHome by mutableStateOf(true)

    /** Nothing open on top of the feed: no search, menu, picker or settings. */
    val isHomeIdle get() = searchProgress.value == 0f && !searchActive && menu == null && widgetPickerAt == null && !settingsOpen && aliasEditor == null && openFolder == null

    // --- geometry, filled in by the UI ---
    val feedScroll = ScrollState(0)
    var rootCoords: LayoutCoordinates? = null
    var gridCoords: LayoutCoordinates? = null
    var viewportCoords: LayoutCoordinates? = null
    var dockCoords: LayoutCoordinates? = null
    var dockSurfaceCoords: LayoutCoordinates? = null
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
        if (openFolder != null) { keyboard?.hide(); focusManager?.clearFocus() }
        menu = null
        selectedWidget = null
        openFolder = null
    }

    /** Home button while already home: peel off one layer; with nothing open, go back to the top. */
    fun onHomePressed(wasVisible: Boolean) {
        cancelDrag()
        val hadOverlay = openFolder != null || menu != null || selectedWidget != null || widgetPickerAt != null || settingsOpen || searchOpen || aliasEditor != null
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
            is FolderContent -> Unit
            is WidgetContent -> Unit
        }
    }

    fun appFor(content: Content): AppInfo? {
        val key = when (content) {
            is AppContent -> content.app
            is ShortcutContent -> return searchable.apps.values.firstOrNull { it.key.pkg == content.pkg && it.key.user == content.user }
            is FolderContent -> return null
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
            if (item.content is FolderContent) {
                val folder = item.content
                add(MenuAction(if (folder.large) R.string.folder_shrink else R.string.folder_enlarge, MenuIcons.Edit) {
                    openFolder = null
                    graph.layout.edit { current ->
                        val live = current.items.find { it.id == item.id }?.content as? FolderContent
                        if (live == null) current else {
                            val result = Edits.folder(current, item.id, live.copy(large = !live.large))
                            if (result === current) folderFeedback = if (live.apps.size > 9) R.string.folder_capacity else R.string.folder_collision
                            result
                        }
                    }
                })
            }
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

    fun showMemberMenu(folderId: Long, entry: app.foyer.data.FolderEntry, anchor: androidx.compose.ui.geometry.Rect) {
        val app = appFor(AppContent(entry.app))
        menu = MenuSpec(anchor, actions = buildList {
            if (app != null) add(infoAction(app))
            add(MenuAction(R.string.action_remove, MenuIcons.Close) {
                graph.layout.edit { current ->
                    val folder = current.items.find { it.id == folderId }?.content as? FolderContent
                    if (folder == null) current else Edits.folder(current, folderId, folder.withoutEntry(entry.id))
                }
            })
            val item = layout.items.find { it.id == folderId }
            if ((item?.content as? FolderContent)?.large == true) {
                add(MenuAction(R.string.folder_shrink, MenuIcons.Edit) {
                    graph.layout.edit { current ->
                        val folder = current.items.find { it.id == folderId }?.content as? FolderContent
                        if (folder == null) current else Edits.folder(current, folderId, folder.copy(large = false))
                    }
                })
            }
        }, onIcon = true)
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
        folderLanding = null
        resetMerge()
        drag = session
        if (searchOpen) closeSearch()
    }

    fun moveDrag(pointer: Offset) {
        drag?.pointer = pointer
        refreshDragTargets()
    }

    fun refreshDragTargets() {
        val session = drag ?: return
        val candidate = mergeAppAt(session)?.id
        if (mergeCandidate != candidate) {
            resetMerge()
            mergeCandidate = candidate
            if (candidate != null) mergeJob = scope.launch {
                kotlinx.coroutines.delay(450)
                if (drag === session && mergeAppAt(session)?.id == candidate) mergeReady = candidate
            }
        }
        val target = targetFor(session) as? DropTarget.Folder
        val id = target?.id
        if (hoverFolder != id) {
            hoverJob?.cancel()
            hoverFolder = id
            if (id != null && (layout.items.find { it.id == id }?.content as? FolderContent)?.large == false && openFolder != id && session.source !is DragSource.Member) hoverJob = scope.launch {
                kotlinx.coroutines.delay(550)
                if (drag === session) openFolder = id
            }
        }
    }

    private fun resetMerge() {
        mergeJob?.cancel()
        mergeCandidate = null
        mergeReady = null
    }

    /** Center dwell expresses merge intent; the surrounding cell continues to allow swaps. */
    private fun mergeAppAt(s: DragSession): HomeItem? {
        if (s.content !is AppContent) return null
        val root = rootCoords ?: return null
        val viewport = androidx.compose.ui.geometry.Rect(0f, 0f, root.size.width.toFloat(), root.size.height.toFloat())
        val dock = dockSurfaceCoords?.takeIf { it.isAttached }?.boundsInRoot()?.let { androidx.compose.ui.geometry.Rect(0f, it.top, viewport.right, viewport.bottom) }
        if (dropLayerAt(s.anchor, viewport, folderPanelBounds.takeIf { openFolder != null }, dock) != DropLayer.Feed) return null
        return layout.feed.firstOrNull { item ->
            item.id != s.placedId && item.content is AppContent && appIconBounds[item.id]?.let {
                androidx.compose.ui.geometry.Rect(it.center - Offset(it.width * .35f, it.height * .35f), androidx.compose.ui.geometry.Size(it.width * .7f, it.height * .7f)).contains(s.anchor)
            } == true
        }
    }

    fun cancelDrag() {
        resetMerge()
        folderLanding = null
        hoverJob?.cancel()
        hoverFolder = null
        drag = null
        if (!searchOpen) query = ""
    }

    fun endDrag(velocity: Offset = Offset.Zero) {
        val s = drag ?: return
        val target = targetFor(s)
        resetMerge()
        hoverJob?.cancel()
        hoverFolder = null
        val key = (s.content as? AppContent)?.app
        val release = s.anchor
        val releaseScale = s.visualScale
        val flight = if (target is DropTarget.Folder && key != null)
            FolderLanding(target.id, key, s.icon, release, releaseScale, velocity).also { folderLanding = it } else null
        scope.launch {
            val keys = listOfNotNull(key, ((target as? DropTarget.Merge)?.target?.content as? AppContent)?.app)
            val missing = graph.apps.missingFolderApps(keys)
            if (drag !== s) return@launch
            graph.layout.edit { current ->
                val available = keys.all { it !in missing && (it.pkg to it.user) !in graph.apps.removedPackages }
                val plan = if (available && target != null) plan(s, target, current) else null
                if (plan != null) {
                    if (target is DropTarget.Folder && key != null) {
                        val before = (current.items.find { it.id == target.id }?.content as? FolderContent)?.entries.orEmpty().map { it.id }.toSet()
                        flight?.entryId = (s.source as? DragSource.Member)?.takeIf { it.folderId == target.id }?.entryId
                            ?: (plan.first.items.find { it.id == target.id }?.content as? FolderContent)?.entries?.firstOrNull { it.id !in before }?.id
                        flight?.validated = true
                    } else dropOrigins[plan.second] = s.anchor
                    plan.first
                } else {
                    if (folderLanding === flight) folderLanding = null
                    if (target is DropTarget.Folder || target is DropTarget.Merge) folderFeedback = R.string.folder_drop_failed
                    else if (s.content is FolderContent) folderFeedback = R.string.folder_move_failed
                    s.placedId?.let { dropOrigins[it] = s.anchor }
                    current
                }
            }
            if (drag === s) {
                if (s.source is DragSource.Member && (target as? DropTarget.Folder)?.id != s.source.folderId) openFolder = null
                drag = null
                if (!searchOpen) query = ""
            }
        }
    }

    fun folderOwnsDrag(s: DragSession): Boolean = openFolder != null &&
        folderPanelBounds?.contains(s.anchor) == true

    fun targetFor(s: DragSession): DropTarget? {
        val root = rootCoords ?: return null
        val p = s.anchor
        val viewport = androidx.compose.ui.geometry.Rect(0f, 0f, root.size.width.toFloat(), root.size.height.toFloat())
        // The dock background extends to the screen bottom while search/IME translates its row.
        val dock = dockSurfaceCoords?.takeIf { it.isAttached }?.boundsInRoot()?.let {
            androidx.compose.ui.geometry.Rect(viewport.left, it.top, viewport.right, viewport.bottom)
        }
        when (dropLayerAt(p, viewport, folderPanelBounds.takeIf { openFolder != null }, dock)) {
            DropLayer.Panel -> {
                if (s.content !is AppContent) return null
                val id = openFolder ?: return null
                val folder = layout.items.find { it.id == id }?.content as? FolderContent ?: return null
                val index = folderHitGridBounds?.let { folderInsertionIndex(p, it, folderRowHeight, folder.apps.size, folderGridScroll, folder.large) } ?: folder.apps.size
                val source = (s.source as? DragSource.Member)?.takeIf { it.folderId == id }
                return DropTarget.Folder(id, folderInsertionForMember(index, folder.entries.indexOfFirst { it.id == source?.entryId }))
            }
            DropLayer.Dock -> {
                if (s.isWidget || s.content is FolderContent) return null
                val dc = dockCoords?.takeIf { it.isAttached } ?: return null
                val local = dc.localPositionOf(root, p)
                val slot = floor(local.x / (dc.size.width / HomeLayout.DOCK_SLOTS.toFloat())).toInt()
                return DropTarget.Dock(slot.coerceIn(0, HomeLayout.DOCK_SLOTS - 1))
            }
            null -> return null
            DropLayer.Feed -> Unit
        }
        if (s.content is AppContent) {
            mergeAppAt(s)?.takeIf { it.id == mergeReady }?.let { return DropTarget.Merge(it) }
            folderSurfaceBounds.entries.firstOrNull { (id, bounds) ->
                (layout.items.find { it.id == id }?.content as? FolderContent)?.large == true && bounds.contains(p)
            }?.let { (id, bounds) ->
                // The outer gutter is a default add target; the inner grid addresses exact slots.
                return if (bounds.deflate(folderInsetPx).contains(p))
                    DropTarget.Folder(id, largeFolderSlot(p, bounds, folderInsetPx)) else DropTarget.Folder(id)
            }
            folderBounds.entries.firstOrNull { (id, bounds) -> id != s.placedId && bounds.contains(p) }?.let {
                return DropTarget.Folder(it.key)
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
            x = floor(local.x / cellW - (s.w - 1) / 2f).toInt().coerceIn(0, HomeLayout.COLUMNS - s.w)
            y = floor(local.y / cellH - (s.h - 1) / 2f).toInt()
        }
        if (y < 0) return null
        return DropTarget.Feed(x, y)
    }

    /** The layout after dropping, plus the id of the item that landed; null if it cannot go there. */
    fun plan(s: DragSession, t: DropTarget, l: HomeLayout = layout): Pair<HomeLayout, Long>? {
        val placed = (s.source as? DragSource.Placed)?.item?.let { p -> l.items.firstOrNull { it.id == p.id } }
        if (s.source is DragSource.Placed && placed != s.source.item) return null
        val member = s.source as? DragSource.Member
        val key = (s.content as? AppContent)?.app
        if (t is DropTarget.Merge) {
            if (key == null) return null
            val result = Edits.mergeApps(l, t.target, key, placed, member?.folderId, member?.entryId)
            return if (result === l) null else result to t.target.id
        }
        if (t is DropTarget.Folder || member != null) {
            if (key == null) return null
            val result = Edits.transferApp(l, key, placed?.id, member?.folderId,
                (t as? DropTarget.Folder)?.id, (t as? DropTarget.Folder)?.index ?: Int.MAX_VALUE,
                (t as? DropTarget.Feed)?.x ?: (t as? DropTarget.Dock)?.slot ?: 0,
                (t as? DropTarget.Feed)?.y ?: 0, t is DropTarget.Dock, sourceEntry = member?.entryId)
            val unchangedMember = member != null && t is DropTarget.Folder && member.folderId == t.id &&
                (l.items.find { it.id == t.id }?.content as? FolderContent)?.let { folder ->
                    !folder.large && folder.entries.any { it.id == member.entryId } && folderDragOrder(folder.entries, folder.entries.find { it.id == member.entryId }, t.index) == folder.entries
                } == true
            return if (result === l && !unchangedMember) null else result to ((t as? DropTarget.Folder)?.id ?: placed?.id ?: l.nextId)
        }
        return when (t) {
            is DropTarget.Folder, is DropTarget.Merge -> null
            is DropTarget.Feed -> {
                if (l.isFree(t.x, t.y, s.w, s.h, ignore = placed?.id)) {
                    if (placed != null) Edits.move(l, placed.id, t.x, t.y, dock = false) to placed.id
                    else Edits.add(l, s.content, t.x, t.y) to l.nextId
                } else {
                    val other = l.feed.singleOrNull { it.id != placed?.id && it.overlaps(t.x, t.y, s.w, s.h) }
                    if (placed != null && other != null && other.w == 1 && other.h == 1 && s.w == 1 && s.h == 1 &&
                        (!placed.dock || other.content !is WidgetContent && other.content !is FolderContent)
                    ) Edits.swap(l, placed, other) to placed.id else null
                }
            }
            is DropTarget.Dock -> {
                if (s.isWidget || s.content is FolderContent) return null
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

    fun accessibleTransfer(key: app.foyer.data.AppKey, sourceItem: Long? = null, sourceFolder: Long? = null,
                           targetFolder: Long? = null, index: Int = Int.MAX_VALUE, toDock: Boolean = false, sourceEntry: String? = null) {
        scope.launch {
            val missing = graph.apps.missingFolderApps(listOf(key))
            graph.layout.edit { current ->
                val (x, y) = if (toDock) ((0 until HomeLayout.DOCK_SLOTS).firstOrNull { current.dockSlotFree(it) } ?: -1) to 0
                    else current.firstFree(1, 1)
                val result = Edits.transferApp(current, key, sourceItem, sourceFolder, targetFolder, index, x, y, toDock,
                    key !in missing && (key.pkg to key.user) !in graph.apps.removedPackages, sourceEntry)
                if (result === current) folderFeedback = R.string.folder_drop_failed
                result
            }
        }
    }

    fun accessibleMerge(target: HomeItem, key: app.foyer.data.AppKey, sourceItem: Long?, sourceFolder: Long?, sourceEntry: String?) {
        val source = sourceItem?.let { id -> layout.items.find { it.id == id } } ?: if (sourceItem != null) return else null
        scope.launch {
            val keys = listOf(key, (target.content as AppContent).app)
            val missing = graph.apps.missingFolderApps(keys)
            graph.layout.edit { current ->
                val result = Edits.mergeApps(current, target, key, source, sourceFolder, sourceEntry,
                    keys.all { it !in missing && (it.pkg to it.user) !in graph.apps.removedPackages })
                if (result === current) folderFeedback = R.string.folder_drop_failed
                result
            }
        }
    }

    fun renameFolder(id: Long, name: String) = graph.layout.edit { current ->
        val folder = current.items.find { it.id == id }?.content as? FolderContent
        if (folder == null || folder.large) current else Edits.folder(current, id, folder.copy(name = name))
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
