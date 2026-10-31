package app.foyer.ui

import androidx.activity.compose.BackHandler
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
internal fun SemanticsPropertyReceiver.folderActions(removeLabel: String, open: () -> Unit, menu: () -> Unit, remove: () -> Unit) {
    onClick { open(); true }
    onLongClick { menu(); true }
    customActions = listOf(CustomAccessibilityAction(removeLabel) { remove(); true })
}

/** TalkBack exposes the same atomic transfers without adding visible management controls. */
@Composable
internal fun folderTransferActions(state: LauncherState, key: AppKey, sourceItem: Long? = null, sourceFolder: Long? = null): List<CustomAccessibilityAction> {
    val layout by state.graph.layout.layout.collectAsState()
    val resources = androidx.compose.ui.platform.LocalResources.current
    val actions = mutableListOf<CustomAccessibilityAction>()
    if (sourceFolder != null) {
        val members = (layout.items.find { it.id == sourceFolder }?.content as? FolderContent)?.apps.orEmpty()
        val index = members.indexOf(key)
        if (index > 0) actions += CustomAccessibilityAction(stringResource(R.string.folder_earlier)) {
            state.accessibleTransfer(key, sourceFolder = sourceFolder, targetFolder = sourceFolder, index = index - 1); true
        }
        if (index in 0 until members.lastIndex) actions += CustomAccessibilityAction(stringResource(R.string.folder_later)) {
            state.accessibleTransfer(key, sourceFolder = sourceFolder, targetFolder = sourceFolder, index = index + 2); true
        }
        actions += CustomAccessibilityAction(stringResource(R.string.action_add_to_home)) {
            state.accessibleTransfer(key, sourceFolder = sourceFolder); true
        }
        actions += CustomAccessibilityAction(stringResource(R.string.action_add_to_dock)) {
            state.accessibleTransfer(key, sourceFolder = sourceFolder, toDock = true); true
        }
    }
    layout.feed.forEach { item ->
        val folder = item.content as? FolderContent ?: return@forEach
        if (item.id != sourceFolder && key !in folder.apps) {
            val name = folder.name.ifEmpty { resources.getString(R.string.folder_default) }
            actions += CustomAccessibilityAction(resources.getString(R.string.folder_move_to, name)) {
                state.accessibleTransfer(key, sourceItem, sourceFolder, item.id); true
            }
        }
    }
    return actions
}

@Composable
private fun memberModifier(state: LauncherState, id: Long, key: AppKey, overlay: Boolean = false, iconCenter: Offset? = null): Modifier {
    val bounds = if (overlay) state.panelMemberBounds else state.memberBounds
    DisposableEffect(id, key) { onDispose { bounds.remove(id to key) } }
    val accessibleActions = folderTransferActions(state, key, sourceFolder = id)
    val dragged = (state.drag?.source as? DragSource.Member)?.let { it.folderId == id && it.key == key } == true
    // Keep the pointer-owning lazy item alive when edge scrolling moves it offscreen.
    val container = LocalPinnableContainer.current
    DisposableEffect(dragged, container) {
        val pin = if (dragged) container?.pin() else null
        onDispose { pin?.release() }
    }
    return Modifier.graphicsLayer { alpha = if (dragged) 0f else 1f }
        .onGloballyPositioned { bounds[id to key] = it.boundsInRoot() }
        .itemGestures(ItemGestureCallbacks(
            onTap = { state.openFolder = null; state.launch(AppContent(key), it) },
            onLongPress = {},
            onDragStart = { pointer, bounds ->
                state.startDrag(DragSession(DragSource.Member(id, key), state.appFor(AppContent(key))?.icon, (iconCenter ?: bounds.center) - pointer, pointer))
            },
            onDrag = state::moveDrag, onDragEnd = state::endDrag, onDragCancel = state::cancelDrag,
        )).semantics {
            customActions = accessibleActions
            contentDescription = state.appFor(AppContent(key))?.label ?: key.pkg
            onClick { state.launch(AppContent(key), null); true }
        }
}

@Composable
fun FolderTile(state: LauncherState, item: HomeItem, folder: FolderContent, iconSize: Dp, modifier: Modifier, interactive: Boolean = true) {
    val title = folder.name.ifEmpty { stringResource(R.string.folder_default) }
    val removeLabel = stringResource(R.string.action_remove)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(item.id, interactive) { onDispose { if (interactive) {
        state.folderBounds.remove(item.id)
        state.folderSurfaceBounds.remove(item.id)
    } } }
    val callbacks = ItemGestureCallbacks(
        onTap = { state.openFolder = item.id },
        onLongPress = { state.showItemMenu(item, coordinates?.boundsInRoot() ?: it) },
        onDragStart = { pointer, bounds ->
            state.startDrag(DragSession(DragSource.Placed(item), null, (coordinates?.boundsInRoot() ?: bounds).center - pointer, pointer))
        },
        onDrag = state::moveDrag, onDragEnd = state::endDrag, onDragCancel = state::cancelDrag,
    )
    Column(modifier.onGloballyPositioned {
        coordinates = it
        if (interactive) state.folderBounds[item.id] = it.boundsInRoot()
    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val panel = if (folder.large) Modifier.weight(1f).fillMaxWidth().padding(6.dp) else Modifier.size(iconSize)
        val target = state.drag?.let { state.targetFor(it) } as? DropTarget.Folder
        Box(panel.graphicsLayer { alpha = if (interactive && state.folderTransitionId == item.id) 0f else 1f }.onGloballyPositioned { if (interactive) state.folderSurfaceBounds[item.id] = it.boundsInRoot() }
            .then(if (interactive && target?.id == item.id) Modifier.border(2.dp, MaterialTheme.colorScheme.primary,
            if (folder.large) RoundedCornerShape(24.dp) else CircleShape) else Modifier)) {
            // A sibling hit target gives member drags exclusive ownership of their gestures.
            Box(Modifier.matchParentSize().clip(if (folder.large) RoundedCornerShape(24.dp) else CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).then(if (interactive) Modifier.itemGestures(callbacks).semantics {
                    contentDescription = title
                    folderActions(removeLabel, { state.openFolder = item.id },
                        { coordinates?.boundsInRoot()?.let { state.showItemMenu(item, it) } }, { state.graph.removeItem(item) })
                } else Modifier))
            if (folder.large) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
                    val rowHeight = maxHeight / 3
                    LazyVerticalGrid(columns = GridCells.Fixed(3), userScrollEnabled = false) {
                        items(folderPreviewMembers(folder.apps.size, large = true).map { folder.apps[it.index] }, key = { it.toString() }) { key ->
                            Box(Modifier.animateItem().height(rowHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                AppIcon(state.appFor(AppContent(key))?.icon, iconSize * .65f,
                                    (if (interactive) memberModifier(state, item.id, key) else Modifier).graphicsLayer {
                                        alpha = if (state.folderLanding?.let { it.folderId == item.id && it.key == key } == true) 0f else 1f
                                    })
                            }
                        }
                    }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize().padding(5.dp)) {
                    folderPreviewMembers(folder.apps.size).forEach { (index, center) ->
                        val size = iconSize * .38f
                        AppIcon(state.appFor(AppContent(folder.apps[index]))?.icon, size,
                            Modifier.offset(maxWidth * center.x - size / 2, maxHeight * center.y - size / 2).graphicsLayer {
                                alpha = if (state.folderLanding?.let { it.folderId == item.id && it.key == folder.apps[index] } == true) 0f else 1f
                            })
                    }
                }
            }
        }
        if (folder.name.isNotEmpty()) Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = LocalOnWallpaper.current.text, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("folder-caption-${item.id}").padding(top = 4.dp))
    }
}

@Composable
fun FolderOverlay(state: LauncherState, layout: HomeLayout) {
    val expansion = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.openFolder) {
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
    val density = LocalDensity.current
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    val safeLeft = WindowInsets.safeDrawing.getLeft(density, direction).toFloat()
    val safeRight = WindowInsets.safeDrawing.getRight(density, direction).toFloat()
    val safeTop = WindowInsets.safeDrawing.getTop(density).toFloat()
    val safeBottom = WindowInsets.safeDrawing.getBottom(density).toFloat()
    val ime = WindowInsets.ime.getBottom(density).toFloat()
    val close = { state.focusManager?.clearFocus(); state.keyboard?.hide(); state.openFolder = null }
    BackHandler { state.cancelDrag(); close() }
    DisposableEffect(id) {
        state.folderTransitionId = id
        onDispose {
            state.folderTransitionId = null
            state.folderPanelBounds = null
            state.folderGridBounds = null
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val dockTop = state.dockRowBottomPx - state.dockContentPx
        val bottom = minOf(height - maxOf(safeBottom, ime), if (dockTop > safeTop) dockTop else height - safeBottom)
        val viewport = Rect(safeLeft + margin, safeTop + margin, width - safeRight - margin, (bottom - margin).coerceAtLeast(safeTop + margin))
        val anchor = state.folderSurfaceBounds[id] ?: Rect(Offset(width / 2, height / 2), Size.Zero)
        val rows = ceil(folder.apps.size / 3f).toInt().coerceAtLeast(1)
        // The reference panel is about 4.85 home-icon diameters wide, with generous row spacing.
        val icon = state.iconSize
        val rowHeight = icon * 1.8f
        val rect = folderPanelRect(anchor, viewport, with(density) {
            Size((icon * 4.85f).toPx(), (rowHeight * rows + 64.dp).toPx())
        })
        val preview = folderPreviewMembers(folder.apps.size, folder.large)
        val p = expansion.value
        val current = androidx.compose.ui.geometry.lerp(anchor, rect, p)
        val radius = with(density) { if (folder.large) 24.dp.toPx() else anchor.width / 2 }
        val corner = with(density) { (radius + (24.dp.toPx() - radius) * p).toDp() }
        val labels = ((p - .45f) / .55f).coerceIn(0f, 1f)
        val drag = state.drag
        val gridState = key(id) { rememberLazyGridState() }
        val liveMemberFrames = remember(id) { mutableMapOf<AppKey, () -> Rect?>() }
        val rowPx = with(density) { rowHeight.toPx() }
        val iconPx = with(density) { icon.toPx() }
        val gridRect = Rect(rect.left + with(density) { 8.dp.toPx() }, rect.top + with(density) { 12.dp.toPx() },
            rect.right - with(density) { 8.dp.toPx() }, rect.bottom - with(density) { 52.dp.toPx() })
        val scroll = gridState.firstVisibleItemIndex / 3 * rowPx + gridState.firstVisibleItemScrollOffset
        SideEffect {
            state.folderGridScroll = scroll
        }
        LaunchedEffect(id, drag != null) {
            val zone = with(density) { 56.dp.toPx() }
            val speed = with(density) { 840.dp.toPx() }
            var previous = withFrameNanos { it }
            while (state.drag != null) {
                val now = withFrameNanos { it }
                val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
                previous = now
                val session = state.drag ?: break
                if (!state.folderOwnsDrag(session)) continue
                val grid = state.folderGridBounds ?: continue
                val delta = folderEdgeScroll(session.pointer, grid, zone, speed) * seconds
                if (delta != 0f) gridState.scrollBy(delta)
            }
        }
        val target = drag?.let { state.targetFor(it) } as? DropTarget.Folder
        val member = (drag?.source as? DragSource.Member)?.takeIf { it.folderId == id }?.key
        val order = if (target?.id == id) folderDragOrder(folder.apps, member, target.index) else folder.apps
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .22f * p)).clickable(onClick = close))
        Box(Modifier.offset { IntOffset(current.left.roundToInt(), current.top.roundToInt()) }
            .size(with(density) { current.width.toDp() }, with(density) { current.height.toDp() })
            .onGloballyPositioned { state.folderPanelBounds = it.boundsInRoot() }
            .clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable {}) {
            Column(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                .offset { IntOffset((rect.left - current.left).roundToInt(), (rect.top - current.top).roundToInt()) }
                .padding(horizontal = 8.dp).padding(top = 12.dp, bottom = 4.dp)) {
                LazyVerticalGrid(columns = GridCells.Fixed(3), state = gridState, userScrollEnabled = drag == null,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                    .onGloballyPositioned {
                        state.folderGridBounds = Rect(it.localToRoot(Offset.Zero), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                        state.folderRowHeight = with(density) { rowHeight.toPx() }
                    }) {
                    items(order, key = { it?.toString() ?: "folder-insertion" }) { key ->
                        if (key == null) {
                            Box(Modifier.animateItem().height(rowHeight).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.padding(top = 8.dp).size(icon).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .07f), CircleShape))
                            }
                        } else {
                            val app = state.appFor(AppContent(key))
                            val index = folder.apps.indexOf(key)
                            val start = folderMemberStart(anchor, folder.large, folder.apps.size, index,
                                with(density) { icon.toPx() }, with(density) { 5.dp.toPx() })
                            var end by remember(key) { mutableStateOf<Rect?>(null) }
                            DisposableEffect(key, start) {
                                // Both the preview morph and the root landing follow live lazy placement.
                                liveMemberFrames[key] = { end }
                                state.folderMemberFrames[id to key] = {
                                    end?.let {
                                        val value = expansion.value
                                        FolderMemberVisual(folderMemberFrame(start, it, value),
                                            if (preview.any { member -> member.index == index }) 1f
                                            else ((value - .45f) / .55f).coerceIn(0f, 1f))
                                    }
                                }
                                onDispose {
                                    liveMemberFrames.remove(key)
                                    state.folderMemberFrames.remove(id to key)
                                }
                            }
                            Column(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                                .then(memberModifier(state, id, key, overlay = true, iconCenter = end?.center)).height(rowHeight)
                                .graphicsLayer { alpha = if (member == key) 0f else 1f },
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
                                        alpha = if (state.folderLanding?.let { it.folderId == id && it.key == key } == true) 0f else if (preview.any { it.index == index }) {
                                            if (p < 1f) 0f else 1f
                                        } else labels
                                    })
                                }
                                Text(app?.label ?: key.pkg, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
                                        alpha = labels
                                    })
                            }
                        }
                    }
                }
                BasicTextField(value = folder.name, onValueChange = { state.renameFolder(id, it) },
                    singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { state.focusManager?.clearFocus(); state.keyboard?.hide() }),
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
                val clip = androidx.compose.ui.geometry.lerp(current, gridRect, p)
                Box(Modifier.fillMaxSize().drawWithContent {
                    clipRect(clip.left - current.left, clip.top - current.top,
                        clip.right - current.left, clip.bottom - current.top) { this@drawWithContent.drawContent() }
                }) {
                    preview.forEach { (index, _) ->
                        val key = folder.apps[index]
                        val slot = order.indexOf(key).coerceAtLeast(0)
                        val start = folderMemberStart(anchor, folder.large, folder.apps.size, index, iconPx,
                            with(density) { 5.dp.toPx() })
                        val end = liveMemberFrames[key]?.invoke()
                            ?: folderGridIcon(gridRect, slot, rowPx, iconPx, with(density) { 8.dp.toPx() }, scroll)
                        val frame = folderMemberFrame(start, end, p)
                        AppIcon(state.appFor(AppContent(key))?.icon, icon, Modifier.testTag("folder-morph-preview-$index").semantics { contentDescription = key.toString() }
                            .offset { IntOffset((frame.center.x - current.left - iconPx / 2).roundToInt(),
                                (frame.center.y - current.top - iconPx / 2).roundToInt()) }
                            .graphicsLayer {
                                scaleX = frame.width / iconPx
                                scaleY = scaleX
                                alpha = if (member == key || state.folderLanding?.let { it.folderId == id && it.key == key } == true) 0f else 1f
                            })
                    }
                }
            }
        }
    }
}

/** One root-space flight owns the icon until the destination is ready to draw it. */
@Composable
internal fun FolderLandingOverlay(state: LauncherState, layout: HomeLayout) {
    val landing = state.folderLanding ?: return
    val progress = remember(landing) { Animatable(0f) }
    val density = LocalDensity.current
    val icon = with(density) { state.iconSize.toPx() }
    LaunchedEffect(landing) {
        progress.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        if (state.folderLanding === landing) state.folderLanding = null
    }
    DisposableEffect(landing) {
        onDispose { if (state.folderLanding === landing) state.folderLanding = null }
    }
    val folder = layout.items.find { it.id == landing.folderId }?.content as? FolderContent ?: return
    val surface = state.folderSurfaceBounds[landing.folderId] ?: return
    val index = folder.apps.indexOf(landing.key)
    val visible = folderPreviewMembers(folder.apps.size, folder.large).any { it.index == index }
    val panel = state.folderTransitionId == landing.folderId
    val memberVisual = if (panel) state.folderMemberFrames[landing.folderId to landing.key]?.invoke() else null
    val end = if (panel) memberVisual?.bounds ?: state.folderGridBounds?.let {
            folderGridIcon(it, index, state.folderRowHeight, icon, with(density) { 8.dp.toPx() }, state.folderGridScroll)
        }
        else folderMemberStart(surface, folder.large, folder.apps.size, index, icon, with(density) { 5.dp.toPx() })
    val destination = end ?: surface
    val destinationVisible = if (panel) state.folderPanelBounds?.contains(destination.center) == true else visible
    val destinationAlpha = if (destinationVisible) memberVisual?.alpha ?: 1f else 0f
    val startSize = icon * landing.scale
    val start = Rect(landing.origin - Offset(startSize / 2, startSize / 2), Size(startSize, startSize))
    val frame = folderMemberFrame(start, destination, progress.value)
    AppIcon(landing.icon, state.iconSize, Modifier.testTag("folder-landing")
        .offset { IntOffset((frame.center.x - icon / 2).roundToInt(), (frame.center.y - icon / 2).roundToInt()) }
        .graphicsLayer {
            scaleX = frame.width / icon
            scaleY = scaleX
            alpha = 1f + (destinationAlpha - 1f) * ((progress.value - .65f) / .35f).coerceIn(0f, 1f)
        })
}
