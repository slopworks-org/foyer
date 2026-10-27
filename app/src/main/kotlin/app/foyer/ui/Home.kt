package app.foyer.ui

import android.os.Bundle
import android.util.SizeF
import android.view.RoundedCorner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import app.foyer.R
import app.foyer.data.AppContent
import app.foyer.data.Content
import app.foyer.data.HomeItem
import app.foyer.data.HomeLayout
import app.foyer.data.ShortcutContent
import app.foyer.data.WidgetContent
import app.foyer.system.FoyerWidgetHostView
import app.foyer.system.widgetSpan
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

// Grid proportions measured off Pixel Launcher (5 columns, no labels): side margin ≈ 2.8% of the
// width, icon ≈ 62% of a cell's width, a row ≈ 1.43 cells wide, widgets inset by 8dp.
private const val SIDE_MARGIN = 0.028f
private const val ICON_OF_CELL = 0.62f
private const val ROW_OF_CELL = 1.43f
private val WIDGET_INSET = 8.dp

/** Room around a selected widget's frame for its resize handles. */
private val FRAME_MARGIN = 16.dp

/** The selection frame hugs the widget: this far inside its cells, just outside the widget. */
private val FRAME_INSET = WIDGET_INSET - 4.dp

/** Root position of what a drop springs from: the icon centre, or a widget's top-left. */
private fun anchorOf(coords: LayoutCoordinates, item: HomeItem): Offset {
    val p = coords.positionInRoot()
    if (item.content is WidgetContent) return p
    return Offset(p.x + coords.size.width / 2f, p.y + coords.size.height / 2f)
}

private fun widgetTopLeft(state: LauncherState, item: HomeItem): Offset {
    val g = state.gridCoords?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
    return Offset(g.x + item.x * state.cellW, g.y + item.y * state.cellH)
}
/** Dock card: a grab handle on top, then the icon row. */
private val DOCK_HANDLE = 16.dp
private val DOCK_ICON_PAD = 12.dp
private val DOCK_CORNER = 32.dp

@Composable
fun Home(state: LauncherState, layout: HomeLayout, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val hPad = maxWidth * SIDE_MARGIN
        val cellW: Dp = (maxWidth - hPad * 2) / HomeLayout.COLUMNS
        val cellH: Dp = cellW * ROW_OF_CELL
        with(density) {
            state.cellW = cellW.toPx()
            state.cellH = cellH.toPx()
            state.graph.cellSizePx = state.cellW to state.cellH
        }
        val iconSize = cellW * ICON_OF_CELL
        state.iconSize = iconSize
        val dockHeight = DOCK_HANDLE + iconSize + DOCK_ICON_PAD * 2 + SEARCH_BAR_HEIGHT + SEARCH_BAR_BOTTOM
        val dockTotal = dockHeight + navBottom
        with(density) {
            state.dockRowBottomPx = (maxHeight - navBottom - SEARCH_BAR_HEIGHT - SEARCH_BAR_BOTTOM).toPx()
            state.dockContentPx = (DOCK_HANDLE + iconSize + DOCK_ICON_PAD * 2).toPx()
        }
        val heightPx = with(density) { maxHeight.toPx() }
        val fadeTop = with(density) { (statusTop * 0.5f).toPx() } / heightPx
        val fadeTopEnd = with(density) { statusTop.toPx() } / heightPx

        Feed(
            state = state,
            layout = layout,
            cellW = cellW,
            cellH = cellH,
            iconSize = iconSize,
            hPad = hPad,
            topPad = statusTop + 4.dp,
            bottomPad = dockTotal + 12.dp,
            viewportHeight = maxHeight,
            modifier = Modifier
                .fillMaxSize()
                // Soft fade under the status bar; the dock is opaque and covers the rest.
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    // Recedes a little while search grows over it.
                    val p = state.searchProgress.value
                    val sc = 1f - 0.06f * p
                    scaleX = sc
                    scaleY = sc
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            fadeTop to Color.Transparent,
                            fadeTopEnd to Color.Black,
                            1f to Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )

        Dock(
            state = state,
            items = layout.dock,
            iconSize = iconSize,
            bottomCorners = displayBottomCorners(),
            // The search bar spans from the first icon's left edge to the last one's right edge.
            searchInset = ((cellW - iconSize) / 2).also { state.searchBarInset = hPad + it },
            // Edge to edge and down under the navigation bar; icons stay on the feed's columns.
            contentPadding = PaddingValues(start = hPad, end = hPad, bottom = navBottom),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(dockTotal),
        )
    }
}

@Composable
private fun Feed(
    state: LauncherState,
    layout: HomeLayout,
    cellW: Dp,
    cellH: Dp,
    iconSize: Dp,
    hPad: Dp,
    topPad: Dp,
    bottomPad: Dp,
    viewportHeight: Dp,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    // Pull down at the very top opens the shade. A third-party launcher cannot drag the shade
    // itself (that is a private SystemUI channel for the recents provider), so the feed follows
    // the finger instead and the decision is made on release: far enough and not pulled back
    // up → open; otherwise spring back. Holding still does nothing.
    val pullThreshold = with(density) { 96.dp.toPx() }
    val pullScope = rememberCoroutineScope()
    val pullVisual = remember { Animatable(0f) }
    val pull = remember {
        object : NestedScrollConnection {
            var pulled = 0f

            fun show() {
                val p = pulled
                pullScope.launch { pullVisual.snapTo(p) }
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Moving back up first undoes the pull before the feed scrolls again.
                if (source == NestedScrollSource.UserInput && pulled > 0f && available.y < 0f) {
                    val used = maxOf(available.y, -pulled)
                    pulled += used
                    show()
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    pulled += available.y
                    show()
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val p = pulled
                if (p <= 0f) return Velocity.Zero
                pulled = 0f
                if (p > pullThreshold && available.y > -500f) state.actions.expandNotifications()
                pullScope.launch { pullVisual.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) }
                return available
            }
        }
    }

    val drag = state.drag
    // Scrolling away lets go of a selected widget, like Pixel Launcher.
    LaunchedEffect(state.feedScroll.isScrollInProgress) {
        if (state.feedScroll.isScrollInProgress) state.selectedWidget = null
    }
    val visibleRows = ((viewportHeight - topPad - bottomPad) / cellH).toInt() + 1
    val rows = max(layout.rows + if (drag != null) 2 else 0, visibleRows)

    // Auto-scroll while dragging near the top or bottom of the feed.
    LaunchedEffect(drag != null) {
        if (drag == null) return@LaunchedEffect
        val zone = with(density) { 72.dp.toPx() }
        val maxSpeed = with(density) { 14.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val s = state.drag ?: break
            val vp = state.viewportCoords?.takeIf { it.isAttached } ?: continue
            val root = state.rootCoords ?: continue
            val y = vp.localPositionOf(root, s.pointer).y
            val top = with(density) { topPad.toPx() }
            val bottom = vp.size.height - with(density) { bottomPad.toPx() } + zone * 0.5f
            val delta = when {
                y < top + zone -> -maxSpeed * ((top + zone - y) / zone).coerceIn(0f, 1f)
                y > bottom - zone && y < bottom + zone * 0.5f -> maxSpeed * ((y - (bottom - zone)) / zone).coerceIn(0f, 1f)
                else -> 0f
            }
            if (delta != 0f) state.feedScroll.scrollBy(delta)
        }
    }

    Column(
        modifier
            .onGloballyPositioned { state.viewportCoords = it }
            .nestedScroll(pull)
            // While something is dragged, the finger belongs to that.
            .verticalScroll(state.feedScroll, enabled = drag == null)
            .graphicsLayer {
                // Rubber band: follows the finger, ever slower, ~40dp at the threshold.
                val p = pullVisual.value
                translationY = pullThreshold * 0.6f * (1f - exp(-p / pullThreshold))
            },
    ) {
        Spacer(Modifier.height(topPad))
        if (!state.isDefaultHome) DefaultHomeBanner(state)
        Box(
            Modifier
                .padding(horizontal = hPad)
                .fillMaxWidth()
                .height(cellH * rows)
                .onGloballyPositioned { state.gridCoords = it }
                .emptyAreaGestures(state),
        ) {
            for (item in layout.feed) {
                androidx.compose.runtime.key(item.id) {
                    PlacedItem(
                        state = state,
                        item = item,
                        iconSize = iconSize,
                        modifier = Modifier
                            .offset(cellW * item.x, cellH * item.y)
                            .size(cellW * item.w, cellH * item.h),
                    )
                }
            }
            DropIndicator(state, cellW, cellH, iconSize)
            val selected = state.selectedWidget?.let { id -> layout.feed.firstOrNull { it.id == id } }
            if (selected != null && drag == null) WidgetFrame(state, selected, cellW, cellH)
        }
        Spacer(Modifier.height(bottomPad))
    }
}

/** Long-press on an empty cell opens the home menu; a tap anywhere empty dismisses widget selection. */
private fun Modifier.emptyAreaGestures(state: LauncherState): Modifier {
    var coords: LayoutCoordinates? = null
    return this
        .onGloballyPositioned { coords = it }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { state.dismissOverlays() },
                onLongPress = { pos ->
                    val lc = coords ?: return@detectTapGestures
                    val x = (pos.x / state.cellW).toInt().coerceIn(0, HomeLayout.COLUMNS - 1)
                    val y = (pos.y / state.cellH).toInt()
                    // Items run their own long-press; the background only owns free cells.
                    if (state.graph.layout.layout.value.feed.any { it.covers(x, y) }) return@detectTapGestures
                    val root = lc.positionInRoot() + pos
                    state.showHomeMenu(x, y, Rect(root.x - 1, root.y - 1, root.x + 1, root.y + 1))
                },
            )
        }
}

@Composable
private fun DefaultHomeBanner(state: LauncherState) {
    Box(
        Modifier
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .pointerInput(Unit) { detectTapGestures { state.actions.requestDefaultHome() } }
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(
            stringResource(R.string.make_default_home),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun DropIndicator(state: LauncherState, cellW: Dp, cellH: Dp, iconSize: Dp) {
    val s = state.drag ?: return
    val t = state.targetFor(s) as? DropTarget.Feed ?: return
    if (state.plan(s, t) == null) return
    val color = LocalOnWallpaper.current.text
    Box(
        Modifier
            .offset(cellW * t.x, cellH * t.y)
            .size(cellW * s.w, cellH * s.h)
            .padding(4.dp)
            .clip(RoundedCornerShape(if (s.isWidget) 24.dp else iconSize / 2))
            .background(color.copy(alpha = 0.14f))
            .border(1.5.dp, color.copy(alpha = 0.45f), RoundedCornerShape(if (s.isWidget) 24.dp else iconSize / 2)),
    )
}

/** An item in the feed or dock: handles gestures, hiding while dragged, and the spring after a drop. */
@Composable
fun PlacedItem(state: LauncherState, item: HomeItem, iconSize: Dp, modifier: Modifier) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val springBack = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val animScope = rememberCoroutineScope()
    val origin = state.dropOrigins[item.id]
    LaunchedEffect(origin, item.x, item.y, item.dock) {
        if (origin == null) return@LaunchedEffect
        withFrameNanos { } // let the new position be laid out first
        val now = coords?.takeIf { it.isAttached }?.let { anchorOf(it, item) }
        if (now != null) {
            // Own scope: consuming the origin below restarts this effect, which must not cut the spring short.
            animScope.launch {
                springBack.snapTo(origin - now)
                springBack.animateTo(Offset.Zero, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
            }
        }
        state.dropOrigins.remove(item.id)
    }
    val drag = state.drag
    val dragged = drag != null && drag.placedId == item.id

    val content = item.content
    val baseModifier = modifier
        .onGloballyPositioned { coords = it }
        .graphicsLayer {
            if (dragged && content is WidgetContent) {
                // Widgets cannot be drawn twice, so they move in place instead of leaving a ghost.
                state.feedScroll.value // re-run on scroll
                val here = coords?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
                val target = drag!!.anchor
                translationX = target.x - here.x
                translationY = target.y - here.y
                scaleX = 1.03f
                scaleY = 1.03f
            } else {
                translationX = springBack.value.x
                translationY = springBack.value.y
                alpha = if (dragged) 0f else 1f
            }
        }

    when (content) {
        is AppContent, is ShortcutContent -> IconTile(state, item, iconSize, baseModifier)
        is WidgetContent -> WidgetTile(state, item, content, baseModifier)
    }
}

@Composable
private fun IconTile(state: LauncherState, item: HomeItem, iconSize: Dp, modifier: Modifier) {
    val content = item.content
    val app = state.appFor(content)
    val shortcut = (content as? ShortcutContent)?.let { state.shortcutFor(it) }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.9f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val callbacks = remember(item) {
        ItemGestureCallbacks(
            onTap = { bounds -> state.launch(item.content, bounds) },
            onLongPress = { bounds -> state.showItemMenu(item, bounds) },
            onDragStart = { pointer, bounds ->
                state.startDrag(DragSession(DragSource.Placed(item), iconOf(state, item.content), bounds.center - pointer, pointer))
            },
            onDrag = state::moveDrag,
            onDragEnd = state::endDrag,
            onDragCancel = state::cancelDrag,
        )
    }
    Box(modifier.itemGestures(callbacks) { pressed = it }, contentAlignment = Alignment.Center) {
        Box(Modifier.scale(scale)) {
            if (content is ShortcutContent) ShortcutIcon(state, shortcut, app, iconSize)
            else AppIcon(app?.icon, iconSize, Modifier.graphicsLayer { alpha = if (app == null) 0.35f else 1f })
        }
    }
}

fun iconOf(state: LauncherState, content: Content) = when (content) {
    is AppContent -> state.appFor(content)?.icon
    is ShortcutContent -> state.appFor(content)?.icon
    is WidgetContent -> null
}

@Composable
fun ShortcutIcon(state: LauncherState, shortcut: app.foyer.data.ShortcutEntry?, app: app.foyer.data.AppInfo?, size: Dp) {
    val icon by produceState<app.foyer.data.Icon?>(null, shortcut?.key) {
        value = shortcut?.let { state.graph.apps.shortcutIcon(it) }
    }
    Box(Modifier.size(size)) {
        AppIcon(icon ?: app?.icon, size, themed = false)
        if (app != null) {
            AppIcon(
                app.icon,
                size * 0.36f,
                Modifier
                    .align(Alignment.BottomEnd)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

@Composable
private fun WidgetTile(state: LauncherState, item: HomeItem, content: WidgetContent, modifier: Modifier) {
    val graph = state.graph
    val info = remember(content.widgetId) { graph.widgetManager.getAppWidgetInfo(content.widgetId) }
    BoxWithConstraints(modifier.padding(WIDGET_INSET)) {
        if (info == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f))
                    .itemGestures(
                        ItemGestureCallbacks(
                            onTap = {},
                            onLongPress = { b -> state.showItemMenu(item, b) },
                            onDragStart = { _, _ -> },
                            onDrag = {},
                            onDragEnd = {},
                            onDragCancel = {},
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.widget_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            return@BoxWithConstraints
        }
        var view by remember { mutableStateOf<FoyerWidgetHostView?>(null) }
        val w = maxWidth.value
        val h = maxHeight.value
        LaunchedEffect(view, w, h) {
            view?.reportSize(w, h)
        }
        val itemNow = rememberUpdatedItem(item)
        // A scroll that started on the widget is a scroll, never a long press.
        LaunchedEffect(view, state.feedScroll.isScrollInProgress) {
            if (state.feedScroll.isScrollInProgress) view?.cancelLongPress()
        }
        val hostView = LocalView.current
        fun toRoot(x: Float, y: Float): Offset {
            val loc = IntArray(2).also { hostView.getLocationOnScreen(it) }
            return Offset(x - loc[0], y - loc[1])
        }
        AndroidView(
            factory = { ctx ->
                (graph.widgetHost.createView(ctx, content.widgetId, info) as FoyerWidgetHostView).also { view = it }
            },
            update = { v ->
                v.onLongPress = {
                    val coords = state.gridCoords
                    val g = coords?.positionInRoot() ?: Offset.Zero
                    val i = itemNow()
                    val r = Rect(
                        g.x + i.x * state.cellW, g.y + i.y * state.cellH,
                        g.x + (i.x + i.w) * state.cellW, g.y + (i.y + i.h) * state.cellH,
                    )
                    state.selectedWidget = i.id
                    state.showItemMenu(i, r)
                }
                // Keep holding and move: the widget follows the finger, like Pixel Launcher.
                v.onDragStart = { x, y ->
                    val i = itemNow()
                    val p = toRoot(x, y)
                    state.startDrag(DragSession(DragSource.Placed(i), null, widgetTopLeft(state, i) - p, p))
                }
                v.onDragMove = { x, y -> state.moveDrag(toRoot(x, y)) }
                v.onDragEnd = { completed -> if (completed) state.endDrag() else state.cancelDrag() }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun rememberUpdatedItem(item: HomeItem): () -> HomeItem {
    val ref = remember { arrayOf(item) }
    ref[0] = item
    return remember { { ref[0] } }
}

/**
 * Frame around a long-pressed widget: long-press the middle to move it, drag a side handle to
 * resize it in whole cells. Live preview while dragging, committed on release if it fits.
 */
@Composable
private fun WidgetFrame(state: LauncherState, item: HomeItem, cellW: Dp, cellH: Dp) {
    val content = item.content as? WidgetContent ?: return
    val info = remember(content.widgetId) { state.graph.widgetManager.getAppWidgetInfo(content.widgetId) }
    val span = remember(info, state.cellW, state.cellH) { info?.let { widgetSpan(it, state.cellW, state.cellH) } }
    // Pending resize, in cells, relative to the item.
    var dl by remember(item) { mutableStateOf(0f) }
    var dt by remember(item) { mutableStateOf(0f) }
    var dr by remember(item) { mutableStateOf(0f) }
    var db by remember(item) { mutableStateOf(0f) }

    /** Proposed (x, y, w, h). Only one handle moves at a time, so the opposite edge stays put. */
    fun proposal(): IntArray {
        // The provider's limits never get stricter than what the widget already is.
        val minW = minOf(span?.minW ?: 1, item.w)
        val minH = minOf(span?.minH ?: 1, item.h)
        val maxW = maxOf(span?.maxW ?: HomeLayout.COLUMNS, item.w)
        val maxH = maxOf(span?.maxH ?: 20, item.h)
        val right = item.x + item.w
        val bottom = item.y + item.h
        val l = (item.x + dl.roundToInt()).coerceIn(maxOf(0, right - maxW), right - minW)
        val r = (right + dr.roundToInt()).coerceIn(item.x + minW, minOf(HomeLayout.COLUMNS, item.x + maxW))
        val t = (item.y + dt.roundToInt()).coerceIn(maxOf(0, bottom - maxH), bottom - minH)
        val b = (bottom + db.roundToInt()).coerceIn(item.y + minH, item.y + maxH)
        val x = if (dl != 0f) l else item.x
        val y = if (dt != 0f) t else item.y
        val w = if (dl != 0f) right - l else if (dr != 0f) r - item.x else item.w
        val h = if (dt != 0f) bottom - t else if (db != 0f) b - item.y else item.h
        return intArrayOf(x, y, w, h)
    }

    val p = proposal()
    val layout by state.graph.layout.layout.collectAsState()
    val fits = layout.isFree(p[0], p[1], p[2], p[3], ignore = item.id)
    val accent = MaterialTheme.colorScheme.primary
    val frameColor = if (fits) accent else MaterialTheme.colorScheme.error

    // Outer box = frame plus a margin, so handles sit fully inside it and stay touchable.
    // The whole area is excluded from the system back gesture: a widget at the screen edge
    // must be resizable without the swipe turning into "back".
    Box(
        Modifier
            .offset(cellW * p[0] - FRAME_MARGIN + FRAME_INSET, cellH * p[1] - FRAME_MARGIN + FRAME_INSET)
            // required: the margin may stick out of the grid, plain size() would squeeze the frame.
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .requiredSize(cellW * p[2] + (FRAME_MARGIN - FRAME_INSET) * 2, cellH * p[3] + (FRAME_MARGIN - FRAME_INSET) * 2)
            .systemGestureExclusion(),
    ) {
        Box(
            Modifier
                .padding(FRAME_MARGIN)
                .fillMaxSize()
                .border(2.dp, frameColor, RoundedCornerShape(26.dp))
                // Moving takes a long press, as on the widget itself; a plain swipe over the
                // frame scrolls the feed (and drops the selection) instead of moving the widget.
                .itemGestures(
                    ItemGestureCallbacks(
                        onTap = { state.dismissOverlays() },
                        onLongPress = {},
                        onDragStart = { pointer, _ ->
                            state.startDrag(DragSession(DragSource.Placed(item), null, widgetTopLeft(state, item) - pointer, pointer))
                        },
                        onDrag = state::moveDrag,
                        onDragEnd = state::endDrag,
                        onDragCancel = state::cancelDrag,
                    ),
                ),
        )
        val canH = span?.resizeH != false
        val canV = span?.resizeV != false
        fun Modifier.handle(onDrag: (Offset) -> Unit) = this
            .size(FRAME_MARGIN * 2)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        val q = proposal()
                        if (state.graph.layout.layout.value.isFree(q[0], q[1], q[2], q[3], ignore = item.id)) {
                            state.resizeWidget(item, q[0], q[1], q[2], q[3])
                        }
                        dl = 0f; dt = 0f; dr = 0f; db = 0f
                    },
                    onDragCancel = { dl = 0f; dt = 0f; dr = 0f; db = 0f },
                ) { change, amount ->
                    change.consume()
                    onDrag(amount)
                }
            }
            .padding(9.dp)
            .clip(CircleShape)
            .background(accent)
        if (canH) {
            Box(Modifier.align(Alignment.CenterStart).handle { dl += it.x / state.cellW })
            Box(Modifier.align(Alignment.CenterEnd).handle { dr += it.x / state.cellW })
        }
        if (canV) {
            Box(Modifier.align(Alignment.TopCenter).handle { dt += it.y / state.cellH })
            Box(Modifier.align(Alignment.BottomCenter).handle { db += it.y / state.cellH })
        }
    }
}

@Composable
private fun Dock(
    state: LauncherState,
    items: List<HomeItem>,
    iconSize: Dp,
    bottomCorners: Pair<Dp, Dp>,
    searchInset: Dp,
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val lift = keyboardLift()
    Column(
        modifier
            // The whole dock rides on the line between its icons and search content (see
            // LauncherState.searchEdge): up and off the top as search opens, and resting on
            // the keyboard whenever it is up, so nothing ever slips behind it.
            .graphicsLayer { translationY = state.searchEdge(state.searchProgress.value, lift()) - state.dockRowBottomPx }
            // Its card becomes the background of search, reaching down to the screen bottom.
            .drawBehind {
                val shift = state.dockRowBottomPx - state.searchEdge(state.searchProgress.value, lift())
                val rt = CornerRadius(DOCK_CORNER.toPx())
                val bl = CornerRadius(bottomCorners.first.toPx())
                val br = CornerRadius(bottomCorners.second.toPx())
                val card = RoundRect(0f, 0f, size.width, size.height + shift, rt, rt, br, bl)
                drawPath(Path().apply { addRoundRect(card) }, colors.surfaceContainer)
            }
            .verticalSwipe(
                enabled = { state.drag == null && state.menu == null && state.selectedWidget == null && !state.searchOpen },
                direction = -1,
                onStart = {
                    state.beginSearchDrag(opening = true)
                    state.dismissOverlays()
                },
                onDelta = { dy -> state.dragSearch(-dy / state.screenHeight) },
                onEnd = { v -> state.releaseSearchDrag(v, opening = true) },
            )
            .pointerInput(Unit) { detectTapGestures { state.openSearch() } }
            .padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(DOCK_HANDLE).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier
                    .size(width = 32.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.onSurfaceVariant.copy(alpha = 0.45f)),
            )
        }
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { state.dockCoords = it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val s = state.drag
            val target = s?.let { state.targetFor(it) } as? DropTarget.Dock
            val showTarget = target != null && state.plan(s, target) != null
            for (slot in 0 until HomeLayout.DOCK_SLOTS) {
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (showTarget && target.slot == slot) {
                        Box(
                            Modifier
                                .size(iconSize + 8.dp)
                                .clip(CircleShape)
                                .background(colors.onSurface.copy(alpha = 0.10f))
                                .border(1.5.dp, colors.onSurface.copy(alpha = 0.35f), CircleShape),
                        )
                    }
                    val item = items.firstOrNull { it.x == slot }
                    if (item != null) {
                        androidx.compose.runtime.key(item.id) {
                            PlacedItem(state, item, iconSize, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        DockSearchBar(
            Modifier
                .padding(start = searchInset, end = searchInset, bottom = SEARCH_BAR_BOTTOM)
                // The search field takes over in exactly this spot as soon as search shows.
                .graphicsLayer { alpha = if (state.searchProgress.value <= 0.001f) 1f else 0f },
        )
    }
}

/**
 * The display's own bottom corner radii (left, right). The dock follows them: invisible at
 * rest, but when the system shrinks home into a card (swipe up from the navigation handle)
 * the dock keeps the screen's shape instead of showing square corners.
 */
@Composable
private fun displayBottomCorners(): Pair<Dp, Dp> {
    val insets = LocalView.current.rootWindowInsets ?: return 0.dp to 0.dp
    val density = LocalDensity.current
    fun radius(position: Int) = with(density) { (insets.getRoundedCorner(position)?.radius ?: 0).toDp() }
    return radius(RoundedCorner.POSITION_BOTTOM_LEFT) to radius(RoundedCorner.POSITION_BOTTOM_RIGHT)
}

/** Looks like a field, but only opens search: the dock's own tap and pull handle it. */
@Composable
private fun DockSearchBar(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .height(SEARCH_BAR_HEIGHT)
            .clip(CircleShape)
            // Quiet like Pixel Launcher's: one tone above the dock, an icon and no placeholder text.
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MenuIcons.Search, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
    }
}

/** Icon following the finger while dragging apps and shortcuts. */
@Composable
fun DragGhost(state: LauncherState, iconSize: Dp) {
    val s = state.drag ?: return
    if (s.isWidget) return
    val lift = remember(s) { Animatable(1f) }
    LaunchedEffect(s) { lift.animateTo(1.12f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)) }
    val half = with(LocalDensity.current) { iconSize.toPx() / 2 }
    AppIcon(
        s.icon,
        iconSize,
        Modifier
            .offset { (s.anchor - Offset(half, half)).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
            .graphicsLayer {
                scaleX = lift.value
                scaleY = lift.value
            },
    )
}
