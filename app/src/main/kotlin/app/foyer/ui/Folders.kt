package app.foyer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.foyer.R
import app.foyer.data.*
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Shared semantic routes: long press opens the full menu; Remove is also available directly. */
internal fun SemanticsPropertyReceiver.folderActions(removeLabel: String, open: (() -> Unit)?, menu: () -> Unit, remove: () -> Unit) {
    if (open != null) onClick { open(); true }
    onLongClick { menu(); true }
    customActions = listOf(CustomAccessibilityAction(removeLabel) { remove(); true })
}

/** TalkBack exposes the same atomic transfers without adding visible management controls. */
@Composable
internal fun folderTransferActions(state: LauncherState, key: AppKey, sourceItem: Long? = null, sourceFolder: Long? = null, sourceEntry: String? = null): List<CustomAccessibilityAction> {
    val layout by state.graph.layout.layout.collectAsState()
    val resources = androidx.compose.ui.platform.LocalResources.current
    val actions = mutableListOf<CustomAccessibilityAction>()
    if (sourceFolder != null) {
        actions += CustomAccessibilityAction(stringResource(R.string.action_remove)) {
            state.graph.layout.editChange { current ->
                val folder = current.items.find { it.id == sourceFolder }?.content as? FolderContent
                if (folder == null || sourceEntry == null) LayoutChange(current) else Edits.folderChange(current, sourceFolder, folder.withoutEntry(sourceEntry))
            }; true
        }
        val sourceFolderItem = layout.items.find { it.id == sourceFolder }
        val source = sourceFolderItem?.content as? FolderContent
        val members = source?.entries.orEmpty()
        if (sourceFolderItem != null) actions += CustomAccessibilityAction(stringResource(R.string.folder_actions)) {
            state.folderBounds[sourceFolder]?.let { state.showItemMenu(sourceFolderItem, it) }; true
        }
        val index = if (source?.large == true) source.slotEntries.indexOfFirst { it?.id == sourceEntry } else members.indexOfFirst { it.id == sourceEntry }
        val lastIndex = if (source?.large == true) 8 else members.lastIndex
        if (index > 0) actions += CustomAccessibilityAction(stringResource(R.string.folder_earlier)) {
            state.accessibleTransfer(key, sourceFolder = sourceFolder, sourceEntry = sourceEntry, targetFolder = sourceFolder, index = index - 1); true
        }
        if (index in 0 until lastIndex) actions += CustomAccessibilityAction(stringResource(R.string.folder_later)) {
            // Small folders use insertion boundaries; large folders use absolute destinations.
            val destination = index + if (source?.large == true) 1 else 2
            state.accessibleTransfer(key, sourceFolder = sourceFolder, sourceEntry = sourceEntry, targetFolder = sourceFolder, index = destination); true
        }
        actions += CustomAccessibilityAction(stringResource(R.string.action_add_to_home)) {
            state.accessibleTransfer(key, sourceFolder = sourceFolder, sourceEntry = sourceEntry); true
        }
        actions += CustomAccessibilityAction(stringResource(R.string.action_add_to_dock)) {
            state.accessibleTransfer(key, sourceFolder = sourceFolder, sourceEntry = sourceEntry, toDock = true); true
        }
    }
    val targets = layout.feed.filter { it.content is AppContent && it.id != sourceItem }
    val targetLabels = targets.associate { it.id to (state.appFor(it.content)?.label ?: (it.content as AppContent).app.pkg) }
    targets.forEach { target ->
        val label = targetLabels.getValue(target.id)
        val qualified = if (targetLabels.values.count { it == label } > 1)
            resources.getString(R.string.folder_position_label, label, target.y + 1, target.x + 1) else label
        actions += CustomAccessibilityAction(resources.getString(R.string.folder_combine_with, qualified)) {
            state.accessibleMerge(target, key, sourceItem, sourceFolder, sourceEntry); true
        }
    }
    val folders = layout.feed.filter { item ->
        val folder = item.content as? FolderContent
        folder != null && item.id != sourceFolder && (!folder.large || folder.entries.size < 9)
    }
    val folderLabels = folders.associate { it.id to (it.content as FolderContent).name.ifEmpty { resources.getString(R.string.folder_default) } }
    folders.forEach { item ->
        val name = folderLabels.getValue(item.id)
        val qualified = if (folderLabels.values.count { it == name } > 1)
            resources.getString(R.string.folder_position_label, name, item.y + 1, item.x + 1) else name
        actions += CustomAccessibilityAction(resources.getString(R.string.folder_move_to, qualified)) {
            state.accessibleTransfer(key, sourceItem, sourceFolder, item.id, sourceEntry = sourceEntry); true
        }
    }
    return actions
}

@Composable
private fun memberModifier(state: LauncherState, id: Long, key: FolderEntry, overlay: Boolean = false, iconCenter: Offset? = null): Modifier {
    val bounds = if (overlay) state.panelMemberBounds else state.memberBounds
    DisposableEffect(id, key) { onDispose { bounds.remove(id to key.id) } }
    val accessibleActions = folderTransferActions(state, key.app, sourceFolder = id, sourceEntry = key.id)
    val layout by state.graph.layout.layout.collectAsState()
    val folder = layout.items.find { it.id == id }?.content as? FolderContent
    val label = state.appFor(AppContent(key.app))?.label ?: key.app.pkg
    val position = if (folder?.large == true) key.slot ?: 0 else folder?.entries?.indexOfFirst { it.id == key.id } ?: 0
    val columns = folderColumns(folder?.entries?.size ?: 0, folder?.large == true)
    val description = if ((folder?.entries?.count { it.app == key.app } ?: 0) > 1)
        stringResource(R.string.folder_position_label, label, position / columns + 1, position % columns + 1) else label
    val dragged = (state.drag?.source as? DragSource.Member)?.let { it.folderId == id && it.entryId == key.id } == true
    // Keep the pointer-owning lazy item alive when edge scrolling moves it offscreen.
    val container = LocalPinnableContainer.current
    DisposableEffect(dragged, container) {
        val pin = if (dragged) container?.pin() else null
        onDispose { pin?.release() }
    }
    return Modifier.graphicsLayer { alpha = if (dragged) 0f else 1f }
        .onGloballyPositioned { bounds[id to key.id] = it.boundsInRoot() }
        .itemGestures(ItemGestureCallbacks(
            onTap = { state.openFolder = null; state.launch(AppContent(key.app), it) },
            onLongPress = { bounds -> state.showMemberMenu(id, key, bounds) },
            onDragStart = { pointer, bounds ->
                state.startDrag(DragSession(DragSource.Member(id, key.app, key.id), state.appFor(AppContent(key.app))?.icon, (iconCenter ?: bounds.center) - pointer, pointer))
            },
            onDrag = state::moveDrag, onDragEnd = state::endDrag, onDragCancel = state::cancelDrag,
        )).semantics {
            customActions = accessibleActions
            contentDescription = description
            onClick { state.launch(AppContent(key.app), null); true }
        }
}

@Composable
fun FolderTile(state: LauncherState, item: HomeItem, folder: FolderContent, iconSize: Dp, modifier: Modifier, interactive: Boolean = true) {
    val title = (if (folder.large) "" else folder.name).ifEmpty { stringResource(R.string.folder_default) }
    val removeLabel = stringResource(R.string.action_remove)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(item.id, interactive) { onDispose { if (interactive) {
        state.folderBounds.remove(item.id)
        state.folderSurfaceBounds.remove(item.id)
    } } }
    val callbacks = ItemGestureCallbacks(
        onTap = { if (!folder.large) state.openFolder = item.id },
        onLongPress = { state.showItemMenu(item, coordinates?.boundsInRoot() ?: it) },
        onDragStart = { pointer, bounds ->
            state.startDrag(DragSession(DragSource.Placed(item), null, (coordinates?.boundsInRoot() ?: bounds).center - pointer, pointer))
        },
        onDrag = state::moveDrag, onDragEnd = state::endDrag, onDragCancel = state::cancelDrag,
    )
    Column(modifier.onGloballyPositioned {
        coordinates = it
        // Keep the full physical cell for feedback; the overlay clips it to the viewport.
        if (interactive) state.folderBounds[item.id] = Rect(it.localToRoot(Offset.Zero), Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val panel = if (folder.large) Modifier.fillMaxSize().padding(6.dp) else Modifier.size(iconSize)
        val target = state.drag?.let { state.folderDropPreview(it) }
        BoxWithConstraints(panel, contentAlignment = Alignment.Center) {
            val surface = if (folder.large) Modifier.size(minOf(maxWidth, maxHeight)) else Modifier.fillMaxSize()
            Box(surface.graphicsLayer { alpha = if (interactive && (state.folderTransitionId == item.id || state.mergePreviews.any { it.target.id == item.id && it.committed })) 0f else 1f }.onGloballyPositioned { if (interactive) state.folderSurfaceBounds[item.id] = if (folder.large)
                    Rect(it.localToRoot(Offset.Zero), Size(it.size.width.toFloat(), it.size.height.toFloat())) else it.boundsInRoot() }
                .then(if (interactive && !folder.large && target?.standalone == null && target?.folderId == item.id) Modifier.border(2.dp, MaterialTheme.colorScheme.primary,
                if (folder.large) RoundedCornerShape(24.dp) else CircleShape) else Modifier)) {
                // A sibling hit target gives member drags exclusive ownership of their gestures.
                Box(Modifier.matchParentSize().clip(if (folder.large) RoundedCornerShape(24.dp) else CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).then(if (interactive) Modifier.itemGestures(callbacks).semantics {
                        contentDescription = title
                        folderActions(removeLabel, if (folder.large) null else ({ state.openFolder = item.id }),
                            { coordinates?.boundsInRoot()?.let { state.showItemMenu(item, it) } }, { state.graph.removeItem(item) })
                    } else Modifier))
                if (folder.large) {
                    BoxWithConstraints(Modifier.fillMaxSize().padding(LARGE_FOLDER_INSET)) {
                        val density = LocalDensity.current
                        SideEffect { state.folderInsetPx = with(density) { LARGE_FOLDER_INSET.toPx() } }
                        val rowHeight = maxHeight / 3
                        // Saved slots use absolute left-to-right coordinates in every locale.
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            LazyVerticalGrid(columns = GridCells.Fixed(3), userScrollEnabled = false) {
                                items(9, key = { slot -> folder.slotEntries[slot]?.let { "entry:${it.id}" } ?: "empty:$slot" }) { slot ->
                                    val key = folder.normalized().slotEntries[slot]
                                    Box(Modifier.animateItem().height(rowHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        if (key != null) AppIcon(state.appFor(AppContent(key.app))?.icon, iconSize * LARGE_FOLDER_ICON_SCALE,
                                            (if (interactive) memberModifier(state, item.id, key) else Modifier).graphicsLayer {
                                                alpha = if (state.folderLanding?.let { it.folderId == item.id && it.entryId == key.id } == true) 0f else 1f
                                            })
                                    }
                                }
                            }
                        }
                        if (interactive && target?.standalone == null && target?.folderId == item.id) {
                            val slot = target.slot
                            Box(Modifier.absoluteOffset { IntOffset(
                                with(density) { (maxWidth / 3 * (slot % 3)).roundToPx() },
                                with(density) { (rowHeight * (slot / 3)).roundToPx() }) }
                                .size(maxWidth / 3, rowHeight).testTag("folder-drop-${item.id}-$slot")
                                .landingFrame(iconSize / 4, outline = false, color = MaterialTheme.colorScheme.onSurface))
                        }
                    }
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize().padding(5.dp), contentAlignment = AbsoluteAlignment.TopLeft) {
                        folderPreviewMembers(folder.apps.size).forEach { (index, center) ->
                            key(folder.entries[index]) {
                                val x by animateFloatAsState(center.x, tween(240), label = "preview x")
                                val y by animateFloatAsState(center.y, tween(240), label = "preview y")
                                val size = iconSize * .38f
                                val density = LocalDensity.current
                                val frame = with(density) {
                                    val inner = Rect(Offset.Zero, Size(maxWidth.toPx(), maxHeight.toPx()))
                                    // The outer Box has already applied the shared 5dp inset.
                                    val resting = folderMemberStart(inner, false, folder.apps.size, index, iconSize.toPx(), 0f)
                                    Rect(Offset(inner.width * x - resting.width / 2, inner.height * y - resting.height / 2), resting.size)
                                }
                                val dragged = (state.drag?.source as? DragSource.Member)?.let { it.folderId == item.id && it.entryId == folder.entries[index].id } == true
                                AppIcon(state.appFor(AppContent(folder.entries[index].app))?.icon, size,
                                    Modifier.absoluteOffset { IntOffset(frame.left.roundToInt(), frame.top.roundToInt()) }.onGloballyPositioned { if (interactive) state.memberBounds[item.id to folder.entries[index].id] = it.boundsInRoot() }.testTag("folder-preview-${item.id}-$index").graphicsLayer {
                                        alpha = if (dragged || state.folderLanding?.let { it.folderId == item.id && it.entryId == folder.entries[index].id } == true) 0f else 1f
                                    })
                            }
                        }
                    }
                }
            }
        }
        if (!folder.large && folder.name.isNotEmpty()) Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = LocalOnWallpaper.current.text, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("folder-caption-${item.id}").padding(top = 4.dp))
    }
}

@Composable
fun FolderOverlay(state: LauncherState, layout: HomeLayout) {
    val expansion = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.openFolder, layout) {
        val target = state.openFolder
        if ((target == null || shown != null && target != shown) && !state.searchActive) {
            state.focusManager?.clearFocus()
            state.keyboard?.hide()
        }
        if (target != null && target != shown) {
            expansion.snapTo(0f)
            shown = target
        }
        expansion.animateTo(if (target == null) 0f else 1f,
            tween(if (target == null) 220 else 260, easing = FastOutSlowInEasing))
        if (target == null) shown = null
    }
    val id = shown ?: return
    val folder = layout.items.find { it.id == id }?.content as? FolderContent ?: return
    if (folder.large) return
    val close = { state.focusManager?.clearFocus(); state.keyboard?.hide(); state.openFolder = null }
    BackHandler { state.cancelDrag(); close() }
    val drag = state.drag
    FolderPanelContent(
        FolderPanelUi(id, folder, state.iconSize, expansion.value, state.folderSurfaceBounds[id],
            state.dockRowBottomPx - state.dockContentPx, drag?.pointer, drag?.anchor, drag?.released == true,
            (drag?.source as? DragSource.Member)?.takeIf { it.folderId == id }?.entryId,
            state.folderLanding?.takeIf { it.folderId == id }?.entryId,
            drag?.let { state.folderDropPreview(it, includeReleased = true) },
            folder.entries.associate { it.app to state.appFor(AppContent(it.app)) }),
        onClose = close, onDone = { state.focusManager?.clearFocus(); state.keyboard?.hide() },
        onRename = { state.renameFolder(id, it) }, onRegister = state::registerPanel, onClear = state::clearPanel,
        onMemberFrame = { entry, frame ->
            state.folderMemberFrames[id to entry] = frame
            val unregister: () -> Unit = {
                if (state.folderMemberFrames[id to entry] === frame) state.folderMemberFrames.remove(id to entry)
            }
            unregister
        },
        memberInteraction = { entry, center -> memberModifier(state, id, entry, overlay = true, iconCenter = center) },
    )
}

internal data class FolderPanelUi(
    val id: Long, val folder: FolderContent, val iconSize: Dp, val progress: Float = 1f,
    val anchor: Rect? = null, val dockTop: Float = 0f,
    val pointer: Offset? = null, val anchorPoint: Offset? = null, val released: Boolean = false,
    val member: String? = null, val landingEntry: String? = null, val target: FolderDropPreview? = null,
    val apps: Map<AppKey, AppInfo?> = emptyMap(),
)

/** Plain panel content owns scrolling, placement and animation mechanics; wiring supplies actions. */
@Composable
internal fun FolderPanelContent(
    ui: FolderPanelUi,
    onClose: () -> Unit = {}, onDone: () -> Unit = {}, onRename: (String) -> Unit = {},
    onRegister: (FolderPanelGeometry) -> Unit = {}, onClear: (FolderPanelGeometry) -> Unit = {},
    onMemberFrame: (String, () -> FolderMemberVisual?) -> (() -> Unit) = { _, _ -> {} },
    memberInteraction: @Composable (FolderEntry, Offset?) -> Modifier = { _, _ -> Modifier },
) {
    val id = ui.id
    val folder = ui.folder
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safeLeft = WindowInsets.safeDrawing.getLeft(density, direction).toFloat()
    val safeRight = WindowInsets.safeDrawing.getRight(density, direction).toFloat()
    val safeTop = WindowInsets.safeDrawing.getTop(density).toFloat()
    val safeBottom = WindowInsets.safeDrawing.getBottom(density).toFloat()
    val ime = WindowInsets.ime.getBottom(density).toFloat()
    val geometry = remember(id) { FolderPanelGeometry(id) }
    DisposableEffect(geometry) {
        onRegister(geometry)
        onDispose { onClear(geometry) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val dockTop = ui.dockTop
        val bottom = minOf(height - maxOf(safeBottom, ime), if (dockTop > safeTop) dockTop else height - safeBottom)
        val viewport = Rect(safeLeft + margin, safeTop + margin, width - safeRight - margin, (bottom - margin).coerceAtLeast(safeTop + margin))
        val anchor = ui.anchor ?: Rect(Offset(width / 2, height / 2), Size.Zero)
        val target = ui.target
        val member = ui.member
        val order = if (target?.folderId == id) target.panelOrder.map { entry ->
            entry ?: folder.entries.find { it.id == member }
        } else folder.entries
        val columns = folderColumns(order.size)
        val icon = ui.iconSize
        val rowHeight = icon * 1.8f
        fun panelRect(count: Int): Rect {
            val cols = folderColumns(count)
            val rows = ceil(count.toFloat() / cols).toInt().coerceAtLeast(if (cols == 2) 2 else 1)
            return folderPanelRect(anchor, viewport, with(density) {
                Size((icon * (cols * 1.6f + .05f)).toPx(), (rowHeight * rows + 64.dp).toPx())
            })
        }
        fun gridRect(panel: Rect) = with(density) {
            Rect(panel.left + 8.dp.toPx(), panel.top + 12.dp.toPx(), panel.right - 8.dp.toPx(), panel.bottom - 52.dp.toPx())
        }
        val desiredRect = panelRect(order.size)
        val hitRect = panelRect(folder.apps.size)
        val rect by androidx.compose.animation.core.animateValueAsState(desiredRect,
            androidx.compose.animation.core.TwoWayConverter<Rect, androidx.compose.animation.core.AnimationVector4D>(
                { androidx.compose.animation.core.AnimationVector4D(it.left, it.top, it.right, it.bottom) },
                { Rect(it.v1, it.v2, it.v3, it.v4) }), tween(240), label = "panel bounds")
        val preview = folderPreviewMembers(folder.apps.size)
        val p = ui.progress
        val current = androidx.compose.ui.geometry.lerp(anchor, rect, p)
        val radius = anchor.width / 2
        val corner = with(density) { (radius + (24.dp.toPx() - radius) * p).toDp() }
        val labels = ((p - .45f) / .55f).coerceIn(0f, 1f)
        val gridState = key(id) { rememberLazyGridState() }
        val liveMemberFrames = remember(id) { mutableMapOf<String, () -> Rect?>() }
        val rowPx = with(density) { rowHeight.toPx() }
        val iconPx = with(density) { icon.toPx() }
        val gridRect = gridRect(rect)
        val scroll = gridState.firstVisibleItemIndex / columns * rowPx + gridState.firstVisibleItemScrollOffset
        SideEffect {
            geometry.hitGrid = gridRect(hitRect)
            geometry.columns = columns
            geometry.scroll = scroll
            geometry.viewport = folderContentClip(current, gridRect, p)
        }
        val currentUi by rememberUpdatedState(ui)
        LaunchedEffect(id, ui.pointer != null && !ui.released) {
            if (ui.pointer == null || ui.released) return@LaunchedEffect
            val zone = with(density) { 56.dp.toPx() }
            val speed = with(density) { 840.dp.toPx() }
            var previous = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
                previous = now
                val pointer = currentUi.pointer ?: continue
                if (currentUi.released || geometry.bounds?.contains(currentUi.anchorPoint ?: pointer) != true) continue
                val grid = geometry.grid ?: continue
                val delta = folderEdgeScroll(pointer, grid, zone, speed) * seconds
                if (delta != 0f) gridState.scrollBy(delta)
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .22f * p)).clickable(onClick = onClose))
        Box(Modifier.offset { IntOffset(current.left.roundToInt(), current.top.roundToInt()) }
            .size(with(density) { current.width.toDp() }, with(density) { current.height.toDp() })
            .onGloballyPositioned { geometry.bounds = it.boundsInRoot() }
            .clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(interactionSource = null, indication = null) {}) {
            Column(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                .offset { IntOffset((rect.left - current.left).roundToInt(), (rect.top - current.top).roundToInt()) }
                .padding(horizontal = 8.dp).padding(top = 12.dp, bottom = 4.dp)) {
                LazyVerticalGrid(columns = GridCells.Fixed(columns), state = gridState, userScrollEnabled = ui.pointer == null,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("folder-grid-$id")
                    .onGloballyPositioned {
                        geometry.grid = Rect(it.localToRoot(Offset.Zero), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                        geometry.rowHeight = with(density) { rowHeight.toPx() }
                    }) {
                    items(order, key = { it?.let { entry -> "entry:${entry.id}" } ?: "folder-insertion" }) { key ->
                        if (key == null) {
                            Box(Modifier.animateItem().height(rowHeight).fillMaxWidth()
                                .then(if (ui.pointer != null && !ui.released) Modifier.testTag("folder-drop-$id-${target?.slot}")
                                    .landingFrame(icon / 2, color = MaterialTheme.colorScheme.onSurface) else Modifier))
                        } else {
                            val app = ui.apps[key.app]
                            val index = folder.entries.indexOf(key)
                            val start = folderMemberStart(anchor, false, folder.apps.size, index,
                                with(density) { icon.toPx() }, with(density) { 5.dp.toPx() })
                            var end by remember(key) { mutableStateOf<Rect?>(null) }
                            DisposableEffect(key, start) {
                                // Both the preview morph and the root landing follow live lazy placement.
                                liveMemberFrames[key.id] = { end }
                                val unregister = onMemberFrame(key.id) {
                                    end?.let {
                                        val value = currentUi.progress
                                        FolderMemberVisual(folderMemberFrame(start, it, value),
                                            if (preview.any { member -> member.index == index }) 1f
                                            else ((value - .45f) / .55f).coerceIn(0f, 1f))
                                    }
                                }
                                onDispose {
                                    liveMemberFrames.remove(key.id)
                                    unregister()
                                }
                            }

                            Column(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                                .height(rowHeight)
                                .then(if (ui.pointer != null && !ui.released && target?.folderId == id && target.entryId == key.id)
                                    Modifier.testTag("folder-drop-$id-${target.slot}").landingFrame(icon / 2, color = MaterialTheme.colorScheme.onSurface) else Modifier)
                                .then(memberInteraction(key, end?.center)),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.padding(top = 8.dp).size(icon).onGloballyPositioned {
                                    // boundsInRoot intersects ancestor clips, corrupting morph destinations.
                                    end = Rect(it.localToRoot(Offset.Zero), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                                }) {
                                    AppIcon(app?.icon, icon, Modifier.graphicsLayer {
                                        val destination = end?.center ?: rect.center
                                        translationX = (start.center.x - destination.x) * (1f - p)
                                        translationY = (start.center.y - destination.y) * (1f - p)
                                        val scale = start.width / with(density) { icon.toPx() }
                                        scaleX = scale + (1f - scale) * p
                                        scaleY = scaleX
                                        alpha = if (member == key.id || ui.landingEntry == key.id) 0f else if (preview.any { it.index == index }) {
                                            if (p < 1f) 0f else 1f
                                        } else labels
                                    })
                                }
                                Text(app?.label ?: key.app.pkg, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.testTag("folder-label-$index").padding(top = 6.dp).graphicsLayer {
                                        val destination = end?.center ?: rect.center
                                        val scale = start.width / iconPx
                                        scaleX = scale + (1f - scale) * p
                                        scaleY = scaleX
                                        transformOrigin = TransformOrigin(.5f, 0f)
                                        translationX = (start.center.x - destination.x) * (1f - p)
                                        translationY = (start.center.y - destination.y) * (1f - p) +
                                            (scaleX - 1f) * (iconPx / 2 + with(density) { 6.dp.toPx() })
                                        alpha = if (member == key.id || ui.landingEntry == key.id) 0f else labels
                                    })
                            }
                        }
                    }
                }
                BasicTextField(value = folder.name, onValueChange = onRename,
                    singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone() }),
                    decorationBox = { field ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (folder.name.isEmpty()) Text(stringResource(R.string.folder_title),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            field()
                        }
                    }, modifier = Modifier.fillMaxWidth().height(48.dp).graphicsLayer { alpha = labels })
            }
            // Preview members must exist independently of lazy composition: after scrolling,
            // their grid slots can be far outside the viewport when closing or reversing.
            if (p < 1f) {
                val clip = folderContentClip(current, gridRect, p)
                Box(Modifier.fillMaxSize().drawWithContent {
                    clipRect(clip.left - current.left, clip.top - current.top,
                        clip.right - current.left, clip.bottom - current.top) { this@drawWithContent.drawContent() }
                }) {
                    preview.forEach { (index, _) ->
                        val key = folder.entries[index]
                        val slot = order.indexOf(key).coerceAtLeast(0)
                        val start = folderMemberStart(anchor, false, folder.apps.size, index, iconPx,
                            with(density) { 5.dp.toPx() })
                        val end = liveMemberFrames[key.id]?.invoke()
                            ?: folderGridIcon(gridRect, slot, rowPx, iconPx, with(density) { 8.dp.toPx() }, scroll, columns)
                        val frame = folderMemberFrame(start, end, p)
                        AppIcon(ui.apps[key.app]?.icon, icon, Modifier.testTag("folder-morph-preview-$index").semantics { contentDescription = key.app.toString() }
                            .offset { IntOffset((frame.center.x - current.left - iconPx / 2).roundToInt(),
                                (frame.center.y - current.top - iconPx / 2).roundToInt()) }
                            .graphicsLayer {
                                scaleX = frame.width / iconPx
                                scaleY = scaleX
                                alpha = if (member == key.id || ui.landingEntry == key.id) 0f else 1f
                            })
                    }
                }
            }
        }
    }
}

/** The prospective surface and real closed tile use the same physical miniature geometry. */
@Composable
internal fun MergePreviewOverlay(state: LauncherState, layout: HomeLayout) {
    val density = LocalDensity.current
    val icon = with(density) { state.iconSize.toPx() }
    val inset = with(density) { 5.dp.toPx() }
    state.mergePreviews.filter { preview -> layout.items.any { it.id == preview.target.id } }.forEach { preview ->
        key(preview) {
            val surface = state.folderSurfaceBounds[preview.target.id]
                ?: state.appIconBounds[preview.target.id] ?: preview.bounds
            val progress = preview.progress.value
            val target = folderMemberFrame(surface, folderMemberStart(surface, false, 2, 0, icon, inset), progress)
            val incoming = folderMemberStart(surface, false, 2, 1, icon, inset)
            val projection = remember { Animatable(1f) }
            LaunchedEffect(preview.released) {
                if (preview.released) projection.animateTo(0f, tween(80))
                else projection.animateTo(1f, tween(80))
            }
            val dockTop = state.dockSurfaceCoords?.takeIf { it.isAttached }?.boundsInRoot()?.top ?: state.screenHeight
            val top = state.viewportCoords?.takeIf { it.isAttached }?.boundsInRoot()?.top?.coerceAtLeast(0f) ?: 0f
            Box(Modifier.fillMaxSize().drawWithContent {
                clipRect(0f, top, size.width, dockTop.coerceAtLeast(top)) {
                    val panel = state.folderPanelBounds
                    if (panel == null) this@drawWithContent.drawContent()
                    else clipRect(panel.left, panel.top, panel.right, panel.bottom, androidx.compose.ui.graphics.ClipOp.Difference) {
                        this@drawWithContent.drawContent()
                    }
                }
            }.testTag("folder-merge-preview")) {
                Box(Modifier.absoluteOffset { IntOffset(surface.left.roundToInt(), surface.top.roundToInt()) }
                    .size(with(density) { surface.width.toDp() })
                    .graphicsLayer { alpha = progress; scaleX = .8f + .2f * progress; scaleY = scaleX }
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape))
                AppIcon(state.appFor(preview.target.content)?.icon, state.iconSize,
                    Modifier.absoluteOffset { IntOffset((target.center.x - icon / 2).roundToInt(), (target.center.y - icon / 2).roundToInt()) }
                        .graphicsLayer { scaleX = target.width / icon; scaleY = scaleX })
                AppIcon(preview.incoming, state.iconSize,
                    Modifier.absoluteOffset { IntOffset((incoming.center.x - icon / 2).roundToInt(), (incoming.center.y - icon / 2).roundToInt()) }
                        .graphicsLayer { scaleX = incoming.width / icon; scaleY = scaleX; alpha = .4f * progress * projection.value })
            }
        }
    }
}

/** One root-space flight owns the icon until the destination is ready to draw it. */
@Composable
internal fun FolderLandingOverlay(state: LauncherState, layout: HomeLayout) {
    val landing = state.folderLanding ?: return
    val density = LocalDensity.current
    val icon = with(density) { state.iconSize.toPx() }
    DisposableEffect(landing) {
        onDispose { if (state.folderLanding === landing) state.folderLanding = null }
    }
    val startSize = icon * landing.scale
    val start = Rect(landing.origin - Offset(startSize / 2, startSize / 2), Size(startSize, startSize))
    val destinationItem = layout.items.find { it.id == landing.folderId }
    val folder = destinationItem?.content as? FolderContent
    val desktopDestination = (destinationItem?.content as? AppContent)?.takeIf { it.app == landing.key }?.let { state.appIconBounds[landing.folderId] }
    // Keep the flight composed even while reflow temporarily detaches the destination tile.
    val liveSurface = state.folderSurfaceBounds[landing.folderId]
    val surface = liveSurface ?: state.mergePreviews.firstOrNull { it.target.id == landing.folderId }?.bounds ?: start
    val index = (if (folder?.large == true) folder.slotEntries.indexOfFirst { it?.id == landing.entryId } else folder?.entries?.indexOfFirst { it.id == landing.entryId })?.coerceAtLeast(0) ?: 0
    val visible = folderPreviewMembers(if (folder?.large == true) 9 else folder?.apps?.size ?: 0, folder?.large ?: false).any { it.index == index }
    val panel = state.folderTransitionId == landing.folderId
    val memberVisual = if (panel) state.folderMemberFrames[landing.folderId to landing.entryId]?.invoke() else null
    val source = landing.source
    val returningMember = (source.source as? DragSource.Member)?.takeIf { state.folderTransitionId == it.folderId }
    val returnVisual = returningMember?.let { state.folderMemberFrames[it.folderId to it.entryId]?.invoke() }
    val sourceBounds = when (val from = source.source) {
        is DragSource.Placed -> state.appIconBounds[from.item.id].takeIf { live -> live != null && layout.items.any { it.id == from.item.id && it.content == from.item.content } }
        is DragSource.Member -> {
            val fromFolder = layout.items.find { it.id == from.folderId }?.content as? FolderContent
            val memberIndex = fromFolder?.entries?.indexOfFirst { it.id == from.entryId } ?: -1
            if (memberIndex < 0) null else if (state.folderTransitionId == from.folderId) returnVisual?.bounds ?: state.folderGridBounds?.let {
                folderGridIcon(it, memberIndex, state.folderRowHeight, icon, with(density) { 8.dp.toPx() }, state.folderGridScroll, state.folderGridColumns)
            }
            else state.memberBounds[from.folderId to from.entryId] ?: state.folderSurfaceBounds[from.folderId]?.let {
                folderMemberStart(it, fromFolder!!.large, if (fromFolder.large) 9 else fromFolder.apps.size, if (fromFolder.large) fromFolder.entries[memberIndex].slot ?: memberIndex else memberIndex, icon, with(density) { (if (fromFolder.large) LARGE_FOLDER_INSET else 5.dp).toPx() })
            }
        }
        is DragSource.New -> source.origin.let { Rect(it - Offset(icon / 2, icon / 2), Size(icon, icon)) }
    }
    val end = if (landing.returning) sourceBounds else if (desktopDestination != null) desktopDestination else if (panel) memberVisual?.bounds ?: state.folderGridBounds?.let {
            folderGridIcon(it, index, state.folderRowHeight, icon, with(density) { 8.dp.toPx() }, state.folderGridScroll, state.folderGridColumns)
        }
        else if (folder != null && liveSurface != null) folderMemberStart(surface, folder.large, if (folder.large) 9 else folder.apps.size, index, icon, with(density) { (if (folder.large) LARGE_FOLDER_INSET else 5.dp).toPx() }) else null
    val destination = end ?: surface
    val viewport = if (if (landing.returning) returningMember != null else panel) state.folderContentViewport else null
    val destinationVisible = if (!landing.returning && desktopDestination != null) true else if (!landing.returning && folder == null) false else if (landing.returning) sourceBounds != null && source.source !is DragSource.New && (returningMember == null || viewport?.overlaps(destination) == true) else if (panel) viewport?.overlaps(destination) == true else visible
    val destinationAlpha = if (destinationVisible) (if (landing.returning) returnVisual?.alpha else memberVisual?.alpha) ?: 1f else 0f
    val target by rememberUpdatedState(if (landing.validated && end != null) destination.center else null)
    // A quick release may land before the target morph ends. Keep its presentation
    // owner until the real folder can take over at identical geometry.
    val morphComplete = state.mergePreviews.none { it.target.id == landing.folderId && it.committed && it.progress.value < 1f }
    val canFinish by rememberUpdatedState(end != null || folder == null || landing.returning)
    val morphSettled by rememberUpdatedState(morphComplete)
    val velocity = remember(landing) { boundedReleaseVelocity(landing.velocity, icon * 18f) }
    val captureSeconds = remember(landing) { releaseCaptureSeconds(velocity.getDistance(), icon) }
    val appearanceSeconds = remember(landing) { releaseAppearanceSeconds(velocity.getDistance(), icon) }
    var motion by remember(landing) { mutableStateOf(ReleaseMotion(landing.origin, velocity, captureSeconds, settlingFrequency = releaseSettlingFrequency(velocity.getDistance(), icon))) }
    var appearanceAge by remember(landing) { mutableFloatStateOf(0f) }
    val progress = FastOutSlowInEasing.transform(releaseAppearanceFraction(appearanceAge, captureSeconds, appearanceSeconds))
    val rootSize = state.rootCoords?.size
    val edge = (startSize + (destination.width - startSize) * progress) / 2f
    val limits by rememberUpdatedState(rootSize?.let {
        releaseLandingBounds(Rect(edge, edge, maxOf(edge, it.width - edge), maxOf(edge, it.height - edge)), target, progress)
    })
    LaunchedEffect(landing) {
        val durationScale = coroutineContext[androidx.compose.ui.MotionDurationScale]?.scaleFactor ?: 1f
        var waitingAge = 0f
        var previous = withFrameNanos { now ->
            // A real frame can be stamped just before input dispatch; test clocks use a different epoch.
            val elapsed = (now - landing.releasedNanos) / 1_000_000_000f
            motion = motion.step(target, if (elapsed < -1f) 1f / 60f else elapsed.coerceIn(0f, .064f), limits)
            now
        }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - previous) / 1_000_000_000f / durationScale.coerceAtLeast(.01f)).coerceIn(0f, if (durationScale == 0f) 3f else .064f)
            motion = motion.step(target, dt, limits)
            if (landing.validated) waitingAge += dt
            if (landing.validated && canFinish) appearanceAge += dt
            previous = now
            if (landing.validated && morphSettled && appearanceAge >= captureSeconds + appearanceSeconds &&
                (target == null || (motion.position - target!!).getDistance() < .75f && motion.velocity.getDistance() < 15f) || waitingAge > 3f) {
                state.finishLanding(landing)
                break
            }
        }
    }
    val frameSize = start.width + (destination.width - start.width) * progress
    val frame = Rect(motion.position - Offset(frameSize / 2, frameSize / 2), Size(frameSize, frameSize))
    landing.renderedBounds = frame
    AppIcon(landing.icon, state.iconSize, Modifier
        .offset { IntOffset((frame.center.x - icon / 2).roundToInt(), (frame.center.y - icon / 2).roundToInt()) }
        .graphicsLayer {
            scaleX = frame.width / icon
            scaleY = scaleX
            alpha = 1f + (destinationAlpha - 1f) * ((progress - .65f) / .35f).coerceIn(0f, 1f)
        }.testTag("folder-landing").drawWithContent {
            if (viewport == null || frame.width <= 0f || frame.height <= 0f) drawContent()
            else {
                val clip = folderLandingClip(frame, viewport, progress)
                clipRect((clip.left - frame.left) * size.width / frame.width,
                    (clip.top - frame.top) * size.height / frame.height,
                    (clip.right - frame.left) * size.width / frame.width,
                    (clip.bottom - frame.top) * size.height / frame.height) { this@drawWithContent.drawContent() }
            }
        })
}


/** A single visual owner carries the survivor from its last displayed frame to the real app icon. */
@Composable
internal fun FolderCollapseOverlay(state: LauncherState) {
    val density = LocalDensity.current
    val icon = with(density) { state.iconSize.toPx() }
    state.folderCollapses.values.toList().forEach { collapse ->
        key(collapse) {
            val destination by rememberUpdatedState(state.appIconBounds[collapse.item.id])
            LaunchedEffect(collapse) {
                try {
                    // Measure the replacement before starting; bounds remain live throughout both phases.
                    val measured = kotlinx.coroutines.withTimeoutOrNull(500L) {
                        while (destination == null) withFrameNanos { }
                        true
                    } ?: false
                    if (!measured) return@LaunchedEffect
                    var previous = 0f
                    collapse.progress.animateTo(1f, tween(340, easing = androidx.compose.animation.core.LinearEasing)) {
                        val end = destination ?: return@animateTo
                        collapse.frame = advanceCollapseFrame(collapse.frame, end, previous, value, icon)
                        collapse.surfaceFrame = advanceCollapseFrame(collapse.surfaceFrame, end, previous, value, icon, surface = true)
                        previous = value
                    }
                } finally { if (state.folderCollapses[collapse.item.id] === collapse) state.folderCollapses.remove(collapse.item.id) }
            }
            val close = collapseClose(collapse.progress.value)
            val grow = collapseGrow(collapse.progress.value)
            val frame = collapse.frame
            val surface = collapse.surfaceFrame
            if (collapse.panel) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .22f * (1f - close))))
            Box(Modifier.offset { IntOffset(surface.left.roundToInt(), surface.top.roundToInt()) }
                .size(with(density) { surface.width.toDp() }, with(density) { surface.height.toDp() })
                .graphicsLayer { alpha = 1f - grow }
                .clip(RoundedCornerShape(with(density) {
                    val initial = if (collapse.panel) 24.dp.toPx() else collapse.surface.width / 2
                    (initial + (surface.width / 2 - initial) * close).toDp()
                }))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh))
            val oldFolder = collapse.item.content as FolderContent
            val captionSurface = if (collapse.panel) surface else collapse.surface
            if (oldFolder.name.isNotEmpty()) Text(oldFolder.name,
                color = if (collapse.panel) MaterialTheme.colorScheme.onSurface else LocalOnWallpaper.current.text,
                style = if (collapse.panel) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.offset { IntOffset(captionSurface.left.roundToInt(),
                    (if (collapse.panel) captionSurface.bottom - with(density) { 48.dp.toPx() } else captionSurface.bottom + with(density) { 4.dp.toPx() }).roundToInt()) }
                    .width(with(density) { captionSurface.width.toDp() }).then(if (collapse.panel) Modifier.height(48.dp).wrapContentHeight() else Modifier)
                    .graphicsLayer { alpha = (1f - close * if (collapse.panel) 2f else 1f).coerceAtLeast(0f) })
            AppIcon(collapse.icon, state.iconSize, Modifier.offset {
                IntOffset((frame.center.x - icon / 2).roundToInt(), (frame.center.y - icon / 2).roundToInt())
            }.graphicsLayer { scaleX = frame.width / icon; scaleY = frame.height / icon }.testTag("folder-survivor-${collapse.item.id}"))
        }
    }
}

/** Cell-bound feedback never sweeps across stationary neighboring artwork. */
@Composable
internal fun FolderDropOutline(state: LauncherState) {
    val session = state.drag ?: return
    val target = state.folderDropPreview(session)?.takeIf { it.folder.large || it.standalone != null } ?: return
    val standalone = target.standalone
    val bounds = if (standalone != null) {
        state.folderBounds[target.folderId] ?: return
    } else {
        val surface = state.folderSurfaceBounds[target.folderId] ?: return
        largeFolderCell(surface, target.slot, state.folderInsetPx)
    }
    val viewport = state.viewportCoords?.takeIf { it.isAttached }?.boundsInRoot() ?: return
    val dockTop = state.dockSurfaceCoords?.takeIf { it.isAttached }?.boundsInRoot()?.top ?: Float.POSITIVE_INFINITY
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().drawWithContent {
        clipRect(viewport.left, viewport.top, viewport.right, minOf(viewport.bottom, dockTop)) {
            val panel = state.folderPanelBounds
            // The foreground panel occludes feedback belonging to a neighboring large folder.
            if (panel == null || standalone != null) this@drawWithContent.drawContent()
            else clipRect(panel.left, panel.top, panel.right, panel.bottom,
                androidx.compose.ui.graphics.ClipOp.Difference) { this@drawWithContent.drawContent() }
        }
    }) {
        Box(Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
            .testTag("folder-drop-outline-${target.folderId}")
            .landingFrame(if (standalone != null) state.iconSize / 2 else state.iconSize / 4,
                fill = standalone != null,
                color = if (standalone != null) LocalOnWallpaper.current.text else MaterialTheme.colorScheme.onSurface))
    }
}
