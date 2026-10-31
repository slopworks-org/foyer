package app.foyer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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

@Composable
private fun memberModifier(state: LauncherState, id: Long, key: AppKey, overlay: Boolean = false): Modifier {
    val bounds = if (overlay) state.panelMemberBounds else state.memberBounds
    DisposableEffect(id, key) { onDispose { bounds.remove(id to key) } }
    val target = state.drag?.let { state.targetFor(it) } as? DropTarget.Folder
    val layout by state.graph.layout.layout.collectAsState()
    val folder = layout.items.find { it.id == id }?.content as? FolderContent
    val insertion = target?.id == id && folder?.apps?.getOrNull(target.index) == key
    return Modifier.then(if (insertion) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
        .onGloballyPositioned { bounds[id to key] = it.boundsInRoot() }
        .itemGestures(ItemGestureCallbacks(
            onTap = { state.openFolder = null; state.launch(AppContent(key), it) },
            onLongPress = {},
            onDragStart = { pointer, bounds ->
                state.startDrag(DragSession(DragSource.Member(id, key), state.appFor(AppContent(key))?.icon, bounds.center - pointer, pointer))
            },
            onDrag = state::moveDrag, onDragEnd = state::endDrag, onDragCancel = state::cancelDrag,
        )).semantics {
            contentDescription = state.appFor(AppContent(key))?.label ?: key.pkg
            onClick { state.launch(AppContent(key), null); true }
        }
}

@Composable
fun FolderTile(state: LauncherState, item: HomeItem, folder: FolderContent, iconSize: Dp, modifier: Modifier, interactive: Boolean = true) {
    val title = folder.name.ifEmpty { stringResource(R.string.folder_default) }
    val removeLabel = stringResource(R.string.action_remove)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(item.id, interactive) { onDispose { if (interactive) state.folderBounds.remove(item.id) } }
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
        Box(panel.then(if (interactive && target?.id == item.id) Modifier.border(2.dp, MaterialTheme.colorScheme.primary,
            if (folder.large) RoundedCornerShape(24.dp) else CircleShape) else Modifier)) {
            // A sibling hit target gives member drags exclusive ownership of their gestures.
            Box(Modifier.matchParentSize().clip(if (folder.large) RoundedCornerShape(24.dp) else CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).then(if (interactive) Modifier.itemGestures(callbacks).semantics {
                    contentDescription = title
                    folderActions(removeLabel, { state.openFolder = item.id },
                        { coordinates?.boundsInRoot()?.let { state.showItemMenu(item, it) } }, { state.graph.removeItem(item) })
                } else Modifier))
            if (folder.large) {
                Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                    repeat(3) { row ->
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            repeat(3) { column ->
                                val key = folder.apps.getOrNull(row * 3 + column)
                                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                    if (key != null) AppIcon(state.appFor(AppContent(key))?.icon, iconSize * .65f,
                                        if (interactive) memberModifier(state, item.id, key) else Modifier)
                                }
                            }
                        }
                    }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize().padding(5.dp)) {
                    folderPreviewCenters(folder.apps.size).forEachIndexed { index, center ->
                        val size = iconSize * .38f
                        AppIcon(state.appFor(AppContent(folder.apps[index]))?.icon, size,
                            Modifier.offset(maxWidth * center.x - size / 2, maxHeight * center.y - size / 2))
                    }
                }
            }
        }
        if (folder.name.isNotEmpty()) Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = LocalOnWallpaper.current.text, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun FolderOverlay(state: LauncherState, layout: HomeLayout) {
    val expansion = remember { androidx.compose.animation.core.Animatable(0f) }
    var shown by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.openFolder) {
        val target = state.openFolder
        if (target != null) shown = target
        expansion.animateTo(if (target == null) 0f else 1f, androidx.compose.animation.core.tween(160))
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
    DisposableEffect(id) { onDispose { state.folderPanelBounds = null } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val dockTop = state.dockRowBottomPx - state.dockContentPx
        val bottom = minOf(height - maxOf(safeBottom, ime), if (dockTop > safeTop) dockTop else height - safeBottom)
        val viewport = Rect(safeLeft + margin, safeTop + margin, width - safeRight - margin, (bottom - margin).coerceAtLeast(safeTop + margin))
        val anchor = state.folderBounds[id] ?: Rect(Offset(width / 2, height / 2), Size.Zero)
        val rows = ceil(folder.apps.size / 3f).toInt().coerceAtLeast(1)
        val rect = folderPanelRect(anchor, viewport, with(density) { Size(336.dp.toPx(), (rows * 104 + 80).dp.toPx()) })
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f * expansion.value)).clickable(onClick = close))
        Surface(Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .onGloballyPositioned { state.folderPanelBounds = it.boundsInRoot() }
            .graphicsLayer {
                alpha = expansion.value
                scaleX = .2f + .8f * expansion.value
                scaleY = .2f + .8f * expansion.value
                transformOrigin = TransformOrigin(
                    ((anchor.center.x - rect.left) / rect.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                    ((anchor.center.y - rect.top) / rect.height.coerceAtLeast(1f)).coerceIn(0f, 1f))
            }
            .clickable {}, shape = RoundedCornerShape(32.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(12.dp)) {
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(folder.apps, key = { it.toString() }) { key ->
                        val app = state.appFor(AppContent(key))
                        Column(memberModifier(state, id, key, overlay = true).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            AppIcon(app?.icon, 48.dp)
                            Text(app?.label ?: key.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                TextField(value = folder.name, onValueChange = { state.renameFolder(id, it) },
                    placeholder = { Text(stringResource(R.string.folder_title), Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                    singleLine = true, textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { state.focusManager?.clearFocus(); state.keyboard?.hide() }),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
