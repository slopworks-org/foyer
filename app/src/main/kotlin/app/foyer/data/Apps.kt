package app.foyer.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/** Icon as two bitmaps: the full-colour one and, when the app ships it, the monochrome layer for themed icons. */
class Icon(val full: ImageBitmap, val mono: ImageBitmap?)

class AppInfo(
    val key: AppKey,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: Icon,
    val isSystem: Boolean,
    val isWork: Boolean,
)

class ShortcutEntry(
    val key: String,
    val info: ShortcutInfo,
    val userSerial: Long,
    val appLabel: String,
) {
    val pkg: String get() = info.`package`
    val id: String get() = info.id
    val user: UserHandle get() = info.userHandle
    val label: String = (info.shortLabel ?: info.longLabel ?: "").toString()
}

class AppRepository(private val context: Context, private val scope: CoroutineScope) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val iconSizePx = (context.resources.displayMetrics.density * ICON_DP).toInt()

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _shortcuts = MutableStateFlow<List<ShortcutEntry>>(emptyList())
    val shortcuts: StateFlow<List<ShortcutEntry>> = _shortcuts.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Called with (package, user serial) when a package is fully removed. */
    var onPackageRemoved: ((String, Long) -> Unit)? = null

    private var reloadJob: Job? = null

    /** Reloads run one at a time, so a slow one can never overwrite a newer result. */
    private val serial = Dispatchers.IO.limitedParallelism(1)

    init {
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) {
                onPackageRemoved?.invoke(packageName, serialOf(user))
                reload()
            }
            override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
            override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
            override fun onPackagesSuspended(packageNames: Array<out String>?, user: UserHandle?) = reload()
            override fun onPackagesUnsuspended(packageNames: Array<out String>?, user: UserHandle?) = reload()
            override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) = reloadShortcuts()
        }, Handler(Looper.getMainLooper()))
        reload()
    }

    fun serialOf(user: UserHandle): Long = userManager.getSerialNumberForUser(user)
    fun userOf(serial: Long): UserHandle? = userManager.getUserForSerialNumber(serial)

    fun reload() {
        reloadJob?.cancel()
        reloadJob = scope.launch(serial) {
            val previous = _apps.value.associateBy { it.key }
            val collator = Collator.getInstance()
            val list = userManager.userProfiles.flatMap { user ->
                val serial = serialOf(user)
                val isWork = user != Process.myUserHandle()
                launcherApps.getActivityList(null, user).mapNotNull { info ->
                    if (info.componentName.packageName == context.packageName) return@mapNotNull null
                    val key = AppKey(info.componentName.packageName, info.componentName.className, serial)
                    val label = info.label.toString().trim()
                    val old = previous[key]
                    AppInfo(
                        key = key,
                        label = label,
                        component = info.componentName,
                        user = user,
                        // Re-use icons of apps that did not change; reloads happen on every package event.
                        icon = if (old != null && old.label == label) old.icon else loadIcon(info, isWork),
                        isSystem = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        isWork = isWork,
                    )
                }
            }.sortedWith(compareBy(collator) { it.label })
            Log.d("Foyer", "Loaded ${list.size} apps")
            _apps.value = list
            _loaded.value = true
            loadShortcuts(list)
        }
    }

    private fun reloadShortcuts() {
        scope.launch(serial) { loadShortcuts(_apps.value) }
    }

    val hasShortcutPermission: Boolean
        get() = try {
            launcherApps.hasShortcutHostPermission()
        } catch (e: IllegalStateException) {
            false // user locked
        }

    private fun loadShortcuts(apps: List<AppInfo>) {
        if (!hasShortcutPermission) {
            _shortcuts.value = emptyList()
            return
        }
        val labels = apps.associate { (it.key.pkg to it.key.user) to it.label }
        val out = ArrayList<ShortcutEntry>()
        val seen = HashSet<String>()
        for (user in userManager.userProfiles) {
            val serial = serialOf(user)
            val query = LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED,
            )
            val infos = try {
                launcherApps.getShortcuts(query, user).orEmpty()
            } catch (e: Exception) {
                Log.w("Foyer", "getShortcuts failed for $user", e)
                emptyList()
            }
            for (info in infos) {
                if (!info.isEnabled) continue
                val appLabel = labels[info.`package` to serial] ?: continue
                val key = "s/$serial/${info.`package`}/${info.id}"
                if (!seen.add(key)) continue
                out += ShortcutEntry(key, info, serial, appLabel)
            }
        }
        _shortcuts.value = out
    }

    /** Shortcuts of one app, in the app's own order (deterministic, not "recent first"). */
    fun shortcutsOf(app: AppInfo): List<ShortcutEntry> = _shortcuts.value
        .filter { it.pkg == app.key.pkg && it.userSerial == app.key.user && (it.info.isDynamic || it.info.isDeclaredInManifest) }
        .sortedWith(compareBy<ShortcutEntry> { if (it.info.isDeclaredInManifest) 0 else 1 }.thenBy { it.info.rank }.thenBy { it.id })
        .take(5)

    private fun loadIcon(info: LauncherActivityInfo, isWork: Boolean): Icon {
        val density = context.resources.displayMetrics.densityDpi
        val raw = info.getIcon(density)
        val drawable = if (isWork) context.packageManager.getUserBadgedIcon(raw, info.user) else raw
        return render(drawable, monoOf(raw))
    }

    private val shortcutIcons = LruCache<String, Icon>(256)

    suspend fun shortcutIcon(entry: ShortcutEntry): Icon? = shortcutIcons.get(entry.key) ?: withContext(Dispatchers.IO) {
        try {
            val d = launcherApps.getShortcutIconDrawable(entry.info, context.resources.displayMetrics.densityDpi)
                ?: return@withContext null
            render(d, monoOf(d)).also { shortcutIcons.put(entry.key, it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun monoOf(d: Drawable): Drawable? =
        if (Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable) d.monochrome else null

    private fun render(d: Drawable, mono: Drawable?): Icon {
        val s = iconSizePx
        val full = createBitmap(s, s)
        Canvas(full).let { c ->
            if (d is AdaptiveIconDrawable) {
                d.setBounds(0, 0, s, s)
            } else {
                // Legacy square icons: shrink a bit so they sit in the same optical size as adaptive ones.
                val inset = (s * 0.06f).toInt()
                d.setBounds(inset, inset, s - inset, s - inset)
            }
            d.draw(c)
        }
        val monoBitmap = mono?.let { m ->
            createBitmap(s, s).also { b ->
                // Adaptive layers are 108dp with the middle 72dp visible: overdraw by 1/4 on each side.
                val extra = s / 4
                val layer = m.mutate()
                layer.setTint(Color.WHITE)
                layer.setBounds(-extra, -extra, s + extra, s + extra)
                layer.draw(Canvas(b))
            }.asImageBitmap()
        }
        return Icon(full.asImageBitmap(), monoBitmap)
    }

    companion object {
        const val ICON_DP = 56
    }
}
