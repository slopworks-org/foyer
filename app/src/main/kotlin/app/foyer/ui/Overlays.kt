package app.foyer.ui

import android.appwidget.AppWidgetProviderInfo
import android.graphics.drawable.Drawable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Icon
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import app.foyer.data.ShortcutEntry
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.res.stringResource
import app.foyer.R
import app.foyer.data.HomeLayout
import app.foyer.data.Settings
import app.foyer.system.widgetSpan
import app.foyer.WidgetEntry
import app.foyer.search.ItemKind
import app.foyer.search.SearchIndex
import app.foyer.search.SearchItem
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import kotlin.math.roundToInt

private fun Modifier.onTap(action: () -> Unit) = pointerInput(action) { detectTapGestures { action() } }

private val MENU_WIDTH = 216.dp
private val MENU_OUTER_CORNER = 24.dp
private val MENU_INNER_CORNER = 6.dp
private val MENU_ROW = 52.dp
private val TAIL_W = 16.dp
private val TAIL_H = 8.dp

/**
 * Long-press popup, Pixel Launcher style: shortcuts and actions are two stacked cards, one open
 * and the other folded into a header that swaps them. Shortcuts sit next to the icon and are
 * open first. Lined up with the icon's left edge, with a small tail pointing at it.
 */
@Composable
fun MenuOverlay(state: LauncherState) {
    val spec = state.menu ?: return
    BackHandler { state.menu = null }
    val density = LocalDensity.current
    val appear = remember(spec) { Animatable(0f) }
    LaunchedEffect(spec) { appear.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) }
    var measured by remember(spec) { mutableStateOf(IntSize.Zero) }
    // Chosen once, so swapping the cards never flips the popup to the other side of the icon.
    var above by remember(spec) { mutableStateOf<Boolean?>(null) }
    var shortcutsOpen by remember(spec) { mutableStateOf(true) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(spec) { detectTapGestures { state.menu = null } },
    ) {
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val gap = with(density) { 6.dp.toPx() }
        val a = if (spec.onIcon) {
            val half = with(density) { state.iconSize.toPx() } / 2f
            Rect(spec.anchor.center.x - half, spec.anchor.center.y - half, spec.anchor.center.x + half, spec.anchor.center.y + half)
        } else spec.anchor
        val s = measured
        if (above == null && s != IntSize.Zero) above = a.top - gap - s.height > margin * 4
        val up = above ?: true
        val x = when {
            a.left + s.width <= screenW - margin -> a.left
            a.right - s.width >= margin -> a.right - s.width
            else -> a.center.x - s.width / 2f
        }.coerceIn(margin, (screenW - s.width - margin).coerceAtLeast(margin))
        val y = (if (up) a.top - gap - s.height else a.bottom + gap)
            .coerceIn(margin, (screenH - s.height - margin).coerceAtLeast(margin))
        val tailX = with(density) { (a.center.x - x).toDp() }

        val shortcuts = spec.shortcuts.takeIf { it.isNotEmpty() }
        val actions = spec.actions.takeIf { it.isNotEmpty() }
        // Top to bottom; the shortcuts card is the one next to the icon.
        val cards = buildList {
            if (up) { if (actions != null) add(false); if (shortcuts != null) add(true) }
            else { if (shortcuts != null) add(true); if (actions != null) add(false) }
        }

        Column(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(MENU_WIDTH)
                .onSizeChanged { measured = it }
                .graphicsLayer {
                    alpha = if (s == IntSize.Zero) 0f else appear.value.coerceIn(0f, 1f)
                    val sc = 0.85f + 0.15f * appear.value
                    scaleX = sc
                    scaleY = sc
                    transformOrigin = TransformOrigin(
                        (tailX.value / MENU_WIDTH.value).coerceIn(0f, 1f),
                        if (up) 1f else 0f,
                    )
                }
                .pointerInput(Unit) { detectTapGestures { } } // swallow taps between the cards
                .animateContentSize(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (!up) MenuTail(tailX, pointsUp = true)
            cards.forEachIndexed { i, isShortcuts ->
                val top = if (i == 0) MENU_OUTER_CORNER else MENU_INNER_CORNER
                val bottom = if (i == cards.lastIndex) MENU_OUTER_CORNER else MENU_INNER_CORNER
                val open = cards.size == 1 || isShortcuts == shortcutsOpen
                MenuCard(top, bottom) {
                    when {
                        !open -> MenuFoldedHeader(stringResource(if (isShortcuts) R.string.menu_shortcuts else R.string.menu_actions)) { shortcutsOpen = isShortcuts }
                        isShortcuts -> for (sc in shortcuts!!) MenuShortcutRow(state, sc)
                        else -> for (action in actions!!) MenuActionRow(action) {
                            state.menu = null
                            action.onClick()
                        }
                    }
                }
            }
            if (up) MenuTail(tailX, pointsUp = false)
        }
    }
}

@Composable
private fun MenuCard(top: Dp, bottom: Dp, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(top, top, bottom, bottom))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 4.dp),
    ) { content() }
}

/** The folded card: its title and a chip that opens it (and folds the other one). */
@Composable
private fun MenuFoldedHeader(title: String, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .onTap(onOpen)
            .height(MENU_ROW)
            .padding(start = 18.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(width = 48.dp, height = 32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MenuIcons.UnfoldMore, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun MenuShortcutRow(state: LauncherState, sc: ShortcutEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .onTap {
                state.menu = null
                state.actions.launchShortcut(sc.pkg, sc.id, sc.user, null)
            }
            .height(MENU_ROW)
            .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShortcutIcon(state, sc, null, 32.dp)
        Spacer(Modifier.width(14.dp))
        Text(
            sc.label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Pin this shortcut to the home screen.
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .onTap {
                    state.menu = null
                    state.pinShortcut(sc)
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MenuIcons.Add, stringResource(R.string.action_add_to_home), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun MenuActionRow(action: MenuAction, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .onTap(onClick)
            .height(MENU_ROW)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(action.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(16.dp))
        Text(stringResource(action.label), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Small triangle from the card nearest the icon towards it; left out when the icon is past the corners. */
@Composable
private fun MenuTail(centerX: Dp, pointsUp: Boolean) {
    val color = MaterialTheme.colorScheme.surfaceContainer
    val visible = centerX in (TAIL_W / 2 + 12.dp)..(MENU_WIDTH - TAIL_W / 2 - 12.dp)
    // Overlap the card by the column spacing so the tail joins it.
    Canvas(Modifier.fillMaxWidth().height(TAIL_H).offset(y = if (pointsUp) 3.dp else (-3).dp)) {
        if (!visible) return@Canvas
        val cx = centerX.toPx()
        val hw = TAIL_W.toPx() / 2
        val path = Path().apply {
            if (pointsUp) {
                moveTo(cx - hw, size.height); lineTo(cx, 0f); lineTo(cx + hw, size.height)
            } else {
                moveTo(cx - hw, 0f); lineTo(cx, size.height); lineTo(cx + hw, 0f)
            }
            close()
        }
        drawPath(path, color)
    }
}

private class ProviderGroup(val key: String, val label: String, val icon: Drawable?, val widgets: List<WidgetEntry>)

/** The picker's catalogue: widgets grouped by app, plus the same fuzzy index the app search uses. */
private class WidgetCatalog(val groups: List<ProviderGroup>, val index: SearchIndex, val byKey: Map<String, WidgetEntry>)

@Composable
fun WidgetPicker(state: LauncherState) {
    val at = state.widgetPickerAt ?: return
    val back = predictiveBackLayer { state.widgetPickerAt = null }
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val catalog by produceState<WidgetCatalog?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val collator = Collator.getInstance()
            val all = state.graph.loadWidgets()
            val groups = all
                .groupBy { "${it.userSerial}/${it.info.provider.packageName}" }
                .map { (key, list) ->
                    val pkg = list.first().info.provider.packageName
                    val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                    ProviderGroup(key, list.first().appLabel, icon, list.sortedWith(compareBy(collator) { it.label }))
                }
                .sortedWith(compareBy(collator) { it.label })
            WidgetCatalog(
                groups,
                SearchIndex(all.map { SearchItem(it.key, it.label, ItemKind.Widget, parentLabel = it.appLabel) }),
                all.associateBy { it.key },
            )
        }
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(catalog, query) {
        val c = catalog ?: return@remember null
        if (query.isBlank()) return@remember c.groups
        // Best match first; each app keeps only its matching widgets and sits where its best one ranks.
        val byGroup = LinkedHashMap<String, MutableList<WidgetEntry>>()
        for (hit in c.index.search(query, limit = 200)) {
            val w = c.byKey[hit.item.key] ?: continue
            byGroup.getOrPut("${w.userSerial}/${w.info.provider.packageName}") { ArrayList() } += w
        }
        val groups = c.groups.associateBy { it.key }
        byGroup.mapNotNull { (key, list) -> groups[key]?.let { ProviderGroup(key, it.label, it.icon, list) } }
    }
    Box(
        back
            .fillMaxSize()
            .background(colors.surfaceContainerLow)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Text(
                stringResource(R.string.action_widgets),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onSurface,
                modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 12.dp),
            )
            WidgetSearchField(query) { query = it }
            val list = shown ?: return@Column
            val listState = rememberLazyListState()
            LaunchedEffect(query) { listState.scrollToItem(0) }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
                modifier = Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            ) {
                if (list.isEmpty()) {
                    item(key = "none") {
                        Text(
                            stringResource(R.string.search_no_results),
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                items(list, key = { it.key }) { g ->
                    Column(Modifier.padding(vertical = 10.dp)) {
                        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            g.icon?.let { DrawableImage(it, 28) }
                            Spacer(Modifier.width(12.dp))
                            Text(g.label, color = colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        }
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
                            items(g.widgets, key = { it.key }) { w ->
                                WidgetPreview(state, w.info) {
                                    state.widgetPickerAt = null
                                    state.actions.addWidget(w.info, at.first, at.second)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetSearchField(query: String, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
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
            if (query.isEmpty()) Text(stringResource(R.string.search_widgets), color = colors.onSurfaceVariant, fontSize = 17.sp)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = colors.onSurface, fontSize = 17.sp),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).pointerInput(Unit) { detectTapGestures { onChange("") } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(MenuIcons.Close, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WidgetPreview(state: LauncherState, info: AppWidgetProviderInfo, onPick: () -> Unit) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.densityDpi
    val preview by produceState<ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                (info.loadPreviewImage(context, density) ?: info.loadIcon(context, density))?.let { d ->
                    val w = d.intrinsicWidth.coerceIn(1, 800)
                    val h = d.intrinsicHeight.coerceIn(1, 800)
                    d.toBitmap(w, h).asImageBitmap()
                }
            }.getOrNull()
        }
    }
    val span = remember(info) { widgetSpan(info, state.cellW, state.cellH) }
    Column(
        Modifier
            .width(168.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .onTap(onPick)
            .padding(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            preview?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Text(
            info.loadLabel(context.packageManager),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text("${span.w} × ${span.h}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun DrawableImage(d: Drawable, sizeDp: Int) {
    val px = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    val bmp = remember(d) { d.toBitmap(px, px).asImageBitmap() }
    Image(bmp, null, modifier = Modifier.size(sizeDp.dp))
}

/** The user's own names for an app, typed comma-separated in one field. */
@Composable
fun AliasDialog(state: LauncherState) {
    val app = state.aliasEditor ?: return
    val close = { state.aliasEditor = null }
    var text by remember(app) {
        val initial = state.aliasesOf(app).joinToString(", ")
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val focus = remember { FocusRequester() }
    val save = {
        state.saveAliases(app, text.text)
        close()
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(app.label) },
        text = {
            Column {
                Text(
                    stringResource(R.string.aliases_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.aliases_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = save) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
    LaunchedEffect(app) { focus.requestFocus() }
}

@Composable
fun SettingsScreen(state: LauncherState, settings: Settings) {
    if (!state.settingsOpen) return
    val back = predictiveBackLayer { state.settingsOpen = false }
    val colors = MaterialTheme.colorScheme
    fun edit(f: (Settings) -> Settings) = state.graph.layout.edit { it.copy(settings = f(it.settings)) }
    Box(
        back
            .fillMaxSize()
            .background(colors.surfaceContainerLow)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 8.dp)) {
            Text(
                stringResource(R.string.action_settings),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onSurface,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 16.dp),
            )
            SettingSwitch(stringResource(R.string.settings_themed_icons), stringResource(R.string.settings_themed_icons_summary), settings.themedIcons) {
                edit { s -> s.copy(themedIcons = it) }
            }
            SettingRow(stringResource(R.string.settings_default_home), stringResource(if (state.isDefaultHome) R.string.settings_default_home_set else R.string.settings_default_home_unset)) {
                state.actions.requestDefaultHome()
            }
            SettingRow(stringResource(R.string.action_wallpaper_style), null) { state.actions.wallpaper() }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.settings_footer, HomeLayout.COLUMNS),
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(20.dp))
            .onTap { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
            subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(20.dp))
            .onTap(onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
        subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
    }
}

/**
 * Material predictive back for a full-screen panel: it shrinks and leans toward the swipe
 * edge while the gesture is in progress, then closes on commit or springs back on cancel.
 */
@Composable
private fun predictiveBackLayer(onBack: () -> Unit): Modifier {
    val progress = remember { Animatable(0f) }
    var fromLeft by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    PredictiveBackHandler { events ->
        try {
            events.collect { e ->
                fromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
                progress.snapTo(e.progress)
            }
            onBack()
        } catch (c: CancellationException) {
            scope.launch { progress.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)) }
            throw c
        }
    }
    val shift = with(LocalDensity.current) { 24.dp.toPx() }
    return Modifier.graphicsLayer {
        val p = progress.value
        val s = 1f - 0.1f * p
        scaleX = s
        scaleY = s
        translationX = (if (fromLeft) 1f else -1f) * shift * p
        shape = RoundedCornerShape(32.dp * p)
        clip = p > 0f
    }
}
