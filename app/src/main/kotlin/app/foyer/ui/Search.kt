package app.foyer.ui

import androidx.compose.ui.unit.Density
import androidx.compose.material3.Icon
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import app.foyer.R
import app.foyer.Searchable
import app.foyer.data.AppContent
import app.foyer.data.AppInfo
import app.foyer.data.Content
import app.foyer.data.ShortcutContent
import app.foyer.search.SearchHit

/** How far the keyboard currently reaches above the navigation bar, in px (animates with it). */
@Composable
fun keyboardLift(): Density.() -> Float {
    val ime = WindowInsets.ime
    val nav = WindowInsets.navigationBars
    return { (ime.getBottom(this) - nav.getBottom(this)).coerceAtLeast(0).toFloat() }
}

/** The search bar, shared by the dock and the search panel so they line up exactly. */
val SEARCH_BAR_HEIGHT = 52.dp
val SEARCH_BAR_BOTTOM = 12.dp

private sealed interface Target {
    data class App(val app: AppInfo) : Target
    data class Shortcut(val s: app.foyer.data.ShortcutEntry) : Target
}

private fun Searchable.resolve(hit: SearchHit): Target? =
    apps[hit.item.key]?.let { Target.App(it) }
        ?: shortcuts[hit.item.key]?.let { Target.Shortcut(it) }

/** What a hit puts on the home screen. */
private fun Target.content(): Content = when (this) {
    is Target.App -> AppContent(app.key)
    is Target.Shortcut -> ShortcutContent(s.pkg, s.id, s.userSerial, s.label)
}

@Composable
fun SearchPanel(state: LauncherState, searchable: Searchable, modifier: Modifier = Modifier) {
    val query = state.query
    // The whole result — order, best hit, highlights — is a pure function of (index, query).
    val hits = remember(searchable, query) { searchable.index.search(query) }
    val colors = MaterialTheme.colorScheme
    val lift = keyboardLift()
    val statusBars = WindowInsets.statusBars

    fun launch(t: Target, bounds: android.graphics.Rect?) = when (t) {
        is Target.App -> state.actions.launchApp(t.app, bounds)
        is Target.Shortcut -> state.actions.launchShortcut(t.s.pkg, t.s.id, t.s.user, bounds)
    }

    fun launchBest() {
        // No match → Enter does nothing. Never falls through to a web search.
        val best = hits.firstOrNull()?.let(searchable::resolve) ?: return
        launch(best, null)
    }

    // No background of its own: the dock's card grows behind it (see Dock).
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            // Results hang right under the dock's icons and come up with them.
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) { Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // This box starts right under the status bar.
                        val edge = state.searchEdge(state.searchProgress.value, lift())
                        translationY = (edge - statusBars.getTop(this)).coerceAtLeast(0f)
                    },
            ) {
                if (query.isBlank()) {
                    AllApps(state, searchable)
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(query) { listState.scrollToItem(0) }
                    // Best result at the bottom: right above the field, closest to the thumb.
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(hits, key = { _, h -> h.item.key }) { i, hit ->
                            val target = searchable.resolve(hit) ?: return@itemsIndexed
                            ResultRow(state, hit, target, best = i == 0, onLaunch = { b -> launch(target, b) })
                        }
                        // Tap only: Enter stays with the best local hit, or does nothing.
                        item(key = "web") { WebSearchRow { state.actions.webSearch(query) } }
                        if (hits.isEmpty()) {
                            item(key = "none") {
                                Text(
                                    stringResource(R.string.search_no_results),
                                    color = colors.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                )
                            }
                        }
                    }
                }
            } }
            SearchField(state, onGo = ::launchBest)
        }
    }
}

@Composable
private fun SearchField(state: LauncherState, onGo: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Same place, size and colour as the dock's search bar: opening search turns one into the other.
    Row(
        Modifier
            .padding(start = state.searchBarInset, end = state.searchBarInset, top = 10.dp, bottom = SEARCH_BAR_BOTTOM)
            .fillMaxWidth()
            .height(SEARCH_BAR_HEIGHT)
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh)
            .padding(start = 18.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MenuIcons.Search, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (state.query.isEmpty()) {
                Text(
                    stringResource(R.string.search_apps),
                    color = colors.onSurfaceVariant,
                    fontSize = 17.sp,
                    modifier = Modifier.graphicsLayer { alpha = ((state.searchProgress.value - 0.3f) / 0.4f).coerceIn(0f, 1f) },
                )
            }
            BasicTextField(
                value = state.query,
                onValueChange = { state.query = it },
                singleLine = true,
                textStyle = TextStyle(color = colors.onSurface, fontSize = 17.sp),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onGo() }, onDone = { onGo() }, onSearch = { onGo() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(state.searchFocus)
                    .focusProperties { canFocus = state.searchActive }
                    .onPreviewKeyEvent { e ->
                        // Hardware keyboards.
                        if (e.key == Key.Enter || e.key == Key.NumPadEnter) {
                            if (e.type == KeyEventType.KeyUp) onGo()
                            true
                        } else {
                            false
                        }
                    },
            )
        }
        if (state.query.isNotEmpty()) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .pointerInput(Unit) { detectTapGestures { state.query = "" } },
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = colors.onSurfaceVariant, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ResultRow(state: LauncherState, hit: SearchHit, target: Target, best: Boolean, onLaunch: (android.graphics.Rect) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(0.6f, Spring.StiffnessMedium), label = "press")
    val iconCenterX = with(density) { (16 + 20).dp.toPx() }
    val callbacks = remember(target) {
        ItemGestureCallbacks(
            onTap = onLaunch,
            onLongPress = { b -> state.showSearchMenu(target.content(), b) },
            onDragStart = { pointer, b ->
                val content = target.content()
                val icon = when (target) {
                    is Target.App -> target.app.icon
                    else -> state.appFor(content)?.icon
                }
                state.startDrag(DragSession(DragSource.New(content), icon, Offset(b.left + iconCenterX, b.center.y) - pointer, pointer))
            },
            onDrag = state::moveDrag,
            onDragEnd = state::endDrag,
            onDragCancel = state::cancelDrag,
        )
    }
    val label = buildAnnotatedString {
        append(hit.item.label)
        for (r in hit.highlights) {
            addStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold), r.first, r.last + 1)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .then(if (best) Modifier.background(colors.secondaryContainer) else Modifier)
            .itemGestures(callbacks) { pressed = it }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (target) {
            is Target.App -> AppIcon(target.app.icon, 40.dp)
            is Target.Shortcut -> ShortcutIcon(state, target.s, state.appFor(target.content()), 40.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                label,
                color = if (best) colors.onSecondaryContainer else colors.onSurface,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = when (target) {
                is Target.Shortcut -> target.s.appLabel
                is Target.App -> if (target.app.isWork) stringResource(R.string.work_profile) else null
            }
            if (sub != null) {
                Text(sub, color = colors.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (best) {
            Text("↵", color = colors.onSecondaryContainer, fontSize = 20.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Hands the query to the browser. Never the best hit, so Enter cannot end up on the web. */
@Composable
private fun WebSearchRow(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(colors.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(MenuIcons.Public, null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.search_web), color = colors.onSurface, fontSize = 16.sp, maxLines = 1)
    }
}

/** Empty query: every app, alphabetically. Stable, no "recent", no suggestions. */
@Composable
private fun AllApps(state: LauncherState, searchable: Searchable) {
    val apps = remember(searchable) { searchable.apps.values.sortedWith(compareBy(java.text.Collator.getInstance()) { it.label }) }
    val density = LocalDensity.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(apps, key = { it.key.toString() }) { app ->
            var pressed by remember { mutableStateOf(false) }
            val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(0.55f, Spring.StiffnessMedium), label = "press")
            val labelAdjust = with(density) { 11.dp.toPx() }
            val callbacks = remember(app) {
                ItemGestureCallbacks(
                    onTap = { b -> state.actions.launchApp(app, b) },
                    onLongPress = { b -> state.showSearchMenu(AppContent(app.key), b) },
                    onDragStart = { pointer, b ->
                        val c = Offset(b.center.x, b.center.y - labelAdjust)
                        state.startDrag(DragSession(DragSource.New(AppContent(app.key)), app.icon, c - pointer, pointer))
                    },
                    onDrag = state::moveDrag,
                    onDragEnd = state::endDrag,
                    onDragCancel = state::cancelDrag,
                )
            }
            Column(
                Modifier
                    .padding(vertical = 10.dp)
                    .itemGestures(callbacks) { pressed = it },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppIcon(app.icon, 52.dp, Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
                Text(
                    app.label,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                )
            }
        }
    }
}
