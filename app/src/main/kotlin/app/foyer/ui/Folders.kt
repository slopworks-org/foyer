package app.foyer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import android.widget.Toast
import app.foyer.R
import app.foyer.data.*

@Composable
fun FolderTile(state: LauncherState, item: HomeItem, folder: FolderContent, iconSize: Dp, modifier: Modifier) {
    val title = folder.name.ifEmpty { stringResource(R.string.folder_default) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val callbacks = remember(item) {
        ItemGestureCallbacks(
            onTap = { state.openFolder = item.id },
            onLongPress = { state.showItemMenu(item, coordinates?.boundsInRoot() ?: it) },
            onDragStart = { pointer, bounds ->
                state.startDrag(DragSession(DragSource.Placed(item), null, (coordinates?.boundsInRoot() ?: bounds).center - pointer, pointer))
            },
            onDrag = state::moveDrag, onDragEnd = state::endDrag, onDragCancel = state::cancelDrag,
        )
    }
    Column(modifier.onGloballyPositioned { coordinates = it }.itemGestures(callbacks).semantics {
        contentDescription = title
        onClick { state.openFolder = item.id; true }
        onLongClick { state.folderEditor = item.id; true }
    },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val shape = if (folder.large) RoundedCornerShape(24.dp) else CircleShape
        val panel = if (folder.large) Modifier.weight(1f).fillMaxWidth().padding(6.dp) else Modifier.size(iconSize)
        Column(panel.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(if (folder.large) 8.dp else 5.dp),
            verticalArrangement = Arrangement.SpaceEvenly) {
            val columns = if (folder.large) 3 else 2
            val count = if (folder.large) 9 else 4
            repeat(columns) { row ->
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    repeat(columns) { col ->
                        val key = folder.apps.take(count).getOrNull(row * columns + col)
                        val app = key?.let { state.appFor(AppContent(it)) }
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            if (key != null) {
                                val size = if (folder.large) iconSize * 0.65f else iconSize * 0.38f
                                AppIcon(app?.icon, size, if (folder.large) Modifier
                                    .semantics { contentDescription = app?.label ?: key.pkg }
                                    .itemGestures(ItemGestureCallbacks(
                                        onTap = { state.launch(AppContent(key), it) },
                                        onLongPress = callbacks.onLongPress,
                                        onDragStart = callbacks.onDragStart,
                                        onDrag = callbacks.onDrag,
                                        onDragEnd = callbacks.onDragEnd,
                                        onDragCancel = callbacks.onDragCancel,
                                    )).semantics { onClick { state.launch(AppContent(key), null); true } } else Modifier)
                            }
                        }
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
    val id = state.openFolder ?: return
    val folder = layout.items.find { it.id == id }?.content as? FolderContent ?: return
    Dialog(onDismissRequest = { state.openFolder = null }) {
        Surface(shape = RoundedCornerShape(32.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp)) {
                if (folder.name.isNotEmpty()) Text(folder.name, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(8.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 480.dp)) {
                    items(folder.apps, key = { it.toString() }) { key ->
                        val app = state.appFor(AppContent(key))
                        Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable {
                            state.openFolder = null
                            state.launch(AppContent(key), null)
                        }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            AppIcon(app?.icon, 48.dp)
                            Text(app?.label ?: key.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                TextButton(onClick = { state.openFolder = null; state.folderEditor = id }) {
                    Text(stringResource(R.string.folder_manage))
                }
            }
        }
    }
}

/** Selection order is folder order; moving a member never changes another home item. */
@Composable
fun FolderEditor(state: LauncherState, layout: HomeLayout) {
    val id = state.folderEditor ?: return
    val folder = layout.items.find { it.id == id }?.content as? FolderContent ?: return
    var name by remember(id) { mutableStateOf(folder.name) }
    var large by remember(id) { mutableStateOf(folder.large) }
    val selected = remember(id) { mutableStateListOf<AppKey>().apply { addAll(folder.apps) } }
    val context = LocalContext.current
    val capacity = stringResource(R.string.folder_capacity)
    val collision = stringResource(R.string.folder_collision)
    val largeLabel = stringResource(R.string.folder_large)
    val apps = state.searchable.apps.values.sortedWith(compareBy({ it.label.lowercase() }, { it.key.toString() }))
    AlertDialog(onDismissRequest = { state.folderEditor = null },
        title = { Text(stringResource(R.string.folder_manage)) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.folder_title)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(largeLabel, Modifier.weight(1f))
                    Switch(modifier = Modifier.semantics { contentDescription = largeLabel }, checked = large, onCheckedChange = {
                        if (it && selected.size > 9) Toast.makeText(context, capacity, Toast.LENGTH_SHORT).show()
                        else large = it
                    })
                }
                Text(stringResource(R.string.folder_order), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(selected.toList(), key = { "member/$it" }) { key ->
                        val index = selected.indexOf(key)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(state.appFor(AppContent(key))?.label ?: key.pkg, Modifier.weight(1f), maxLines = 1)
                            TextButton(enabled = index > 0, onClick = {
                                selected.removeAt(index); selected.add(index - 1, key)
                            }) { Text(stringResource(R.string.folder_up)) }
                            TextButton(onClick = { selected.remove(key) }) { Text(stringResource(R.string.action_remove)) }
                        }
                    }
                    items(apps.filter { it.key !in selected }, key = { "app/${it.key}" }) { app ->
                        Row(Modifier.fillMaxWidth().clickable {
                            if (large && selected.size >= 9) Toast.makeText(context, capacity, Toast.LENGTH_SHORT).show()
                            else selected.add(app.key)
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app.icon, 32.dp)
                            Text(app.label, Modifier.padding(start = 12.dp).weight(1f), maxLines = 1)
                            Icon(MenuIcons.Add, stringResource(R.string.folder_add))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            // An uninstall while this dialog was open must not resurrect removed members.
            val available = state.searchable.apps.values.map { it.key }.toSet()
            val current = (state.graph.layout.layout.value.items.find { it.id == id }?.content as? FolderContent)?.apps.orEmpty()
            if (state.saveFolder(id, FolderContent(selected.filter { it in available || it in current }, large, name.trim()))) state.folderEditor = null
            else Toast.makeText(context, collision, Toast.LENGTH_SHORT).show()
        }) { Text(stringResource(R.string.folder_save)) } },
        dismissButton = { TextButton(onClick = { state.folderEditor = null }) { Text(stringResource(R.string.folder_cancel)) } },
    )
}
