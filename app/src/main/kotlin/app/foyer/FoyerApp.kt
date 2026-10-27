package app.foyer

import android.appwidget.AppWidgetProviderInfo
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import app.foyer.data.AppContent
import app.foyer.data.AppInfo
import app.foyer.data.AppRepository
import app.foyer.data.Edits
import app.foyer.data.HomeItem
import app.foyer.data.LayoutStore
import app.foyer.data.ShortcutContent
import app.foyer.data.WidgetContent
import app.foyer.search.ItemKind
import app.foyer.search.SearchIndex
import app.foyer.search.SearchItem
import app.foyer.system.FoyerWidgetHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FoyerApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }
}

val Context.graph: Graph get() = (applicationContext as FoyerApp).graph

/** A home-screen widget as the picker sees it: found by its own name or its app's. */
class WidgetEntry(val key: String, val info: AppWidgetProviderInfo, val label: String, val appLabel: String, val userSerial: Long)

/** Search index plus a way back from a hit's key to the thing it stands for. */
class Searchable(
    val index: SearchIndex,
    val apps: Map<String, AppInfo>,
    val shortcuts: Map<String, app.foyer.data.ShortcutEntry>,
)

fun searchKey(app: AppInfo) = "a/${app.key}"

/** Process-wide singletons. A launcher is one screen; no DI framework needed. */
class Graph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val apps = AppRepository(context, scope)
    val layout = LayoutStore(context, scope)
    val widgetHost = FoyerWidgetHost(context)
    val widgetManager: AppWidgetManager = AppWidgetManager.getInstance(context)

    /** Last measured feed cell size, used to size widgets that are added from outside the UI. */
    var cellSizePx: Pair<Float, Float> = context.resources.displayMetrics.let { it.widthPixels / 5f to it.widthPixels / 5f * 1.18f }

    private val aliases = layout.layout.map { it.aliases }.distinctUntilChanged()

    val searchable: StateFlow<Searchable> = combine(apps.apps, apps.shortcuts, aliases) { appList, shortcutList, aliasMap ->
        val items = ArrayList<SearchItem>(appList.size + shortcutList.size)
        val byKey = HashMap<String, AppInfo>()
        for (a in appList) {
            val k = searchKey(a)
            byKey[k] = a
            items += SearchItem(k, a.label, ItemKind.App, aliases = aliasMap[a.key.toString()].orEmpty())
        }
        val shortcutsByKey = HashMap<String, app.foyer.data.ShortcutEntry>()
        for (s in shortcutList) {
            if (s.label.isBlank()) continue
            shortcutsByKey[s.key] = s
            items += SearchItem(s.key, s.label, ItemKind.Shortcut, parentLabel = s.appLabel)
        }
        Searchable(SearchIndex(items), byKey, shortcutsByKey)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, Searchable(SearchIndex(emptyList()), emptyMap(), emptyMap()))

    /** Every home-screen widget of every profile. Slow (loads labels): call off the main thread. */
    fun loadWidgets(appList: List<AppInfo> = apps.apps.value): List<WidgetEntry> {
        val pm = context.packageManager
        return context.getSystemService(LauncherApps::class.java).profiles.flatMap { user ->
            val serial = apps.serialOf(user)
            runCatching { widgetManager.getInstalledProvidersForProfile(user) }.getOrDefault(emptyList())
                .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
                .mapNotNull { info ->
                    val pkg = info.provider.packageName
                    val appLabel = appList.firstOrNull { it.key.pkg == pkg && it.key.user == serial }?.label
                        ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
                        ?: return@mapNotNull null
                    val label = runCatching { info.loadLabel(pm) }.getOrNull()?.takeIf { it.isNotBlank() } ?: appLabel
                    WidgetEntry("w/$serial/${info.provider.flattenToString()}", info, label, appLabel, serial)
                }
        }
    }

    init {
        apps.onPackageRemoved = { pkg, user -> forgetPackage(pkg, user) }
        scope.launch { seedOnFirstRun() }
        scope.launch { syncPinnedShortcuts() }
    }

    private fun forgetPackage(pkg: String, user: Long) {
        layout.edit { l ->
            val gone = l.items.filter { item ->
                when (val c = item.content) {
                    is AppContent -> c.app.pkg == pkg && c.app.user == user
                    is ShortcutContent -> c.pkg == pkg && c.user == user
                    is WidgetContent -> c.provider.startsWith("$pkg/")
                }
            }
            gone.forEach { (it.content as? WidgetContent)?.let { w -> widgetHost.deleteAppWidgetId(w.widgetId) } }
            l.copy(items = l.items - gone.toSet())
        }
    }

    fun removeItem(item: HomeItem) {
        (item.content as? WidgetContent)?.let { widgetHost.deleteAppWidgetId(it.widgetId) }
        layout.edit { Edits.remove(it, item.id) }
    }

    /**
     * First run only: the usual four in the dock and an empty feed; the user brings in what
     * they want from search. After that, nothing is ever placed or moved without the user.
     */
    private suspend fun seedOnFirstRun() {
        if (layout.layout.value.seeded) return
        apps.loaded.first { it }
        val list = apps.apps.value
        if (layout.layout.value.seeded) return
        val pm = context.packageManager
        fun defaultPkg(intent: Intent) =
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val dockPkgs = listOfNotNull(
            defaultPkg(Intent(Intent.ACTION_DIAL)),
            defaultPkg(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))),
            defaultPkg(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
            defaultPkg(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
        ).distinct()
        val personal = list.filter { !it.isWork }
        val dockApps = dockPkgs.mapNotNull { p -> personal.firstOrNull { it.key.pkg == p } }
        layout.edit { l0 ->
            var l = l0
            dockApps.forEachIndexed { i, a -> l = Edits.add(l, AppContent(a.key), x = i, y = 0, dock = true) }
            l.copy(seeded = true)
        }
    }

    /** Pinned shortcuts on the home screen must stay pinned with the system, or apps may drop them. */
    private suspend fun syncPinnedShortcuts() {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        var previous: Map<Pair<String, Long>, List<String>> = emptyMap()
        layout.layout
            .map { l -> l.items.mapNotNull { it.content as? ShortcutContent }.groupBy({ it.pkg to it.user }, { it.shortcutId }) }
            .distinctUntilChanged()
            .collect { current ->
                if (!apps.hasShortcutPermission) return@collect
                for (key in previous.keys + current.keys) {
                    val ids = current[key].orEmpty().distinct()
                    if (ids == previous[key].orEmpty().distinct()) continue
                    val user = apps.userOf(key.second) ?: continue
                    try {
                        launcherApps.pinShortcuts(key.first, ids, user)
                    } catch (e: Exception) {
                        Log.w("Foyer", "pinShortcuts failed for ${key.first}", e)
                    }
                }
                previous = current
            }
    }
}
