package app.foyer.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Identifies a launchable activity across restarts. [user] is the profile's serial number. */
@Serializable
data class AppKey(val pkg: String, val cls: String, val user: Long) {
    override fun toString() = "$user/$pkg/$cls"
}

@Serializable
sealed interface Content

@Serializable
@SerialName("app")
data class AppContent(val app: AppKey) : Content

@Serializable
@SerialName("shortcut")
data class ShortcutContent(val pkg: String, val shortcutId: String, val user: Long, val label: String) : Content

@Serializable
@SerialName("widget")
data class WidgetContent(val widgetId: Int, val provider: String) : Content

@Serializable
@SerialName("folder")
data class FolderContent(
    val apps: List<AppKey> = emptyList(),
    val large: Boolean = false,
    val name: String = "",
) : Content

/**
 * Something placed on the home screen. Every item owns explicit cells, so removing or
 * uninstalling one never shifts the others: the feed is a place, not a list.
 * In the dock only [x] matters (the slot).
 */
@Serializable
data class HomeItem(
    val id: Long,
    val content: Content,
    val x: Int,
    val y: Int,
    val w: Int = 1,
    val h: Int = 1,
    val dock: Boolean = false,
) {
    fun covers(cx: Int, cy: Int) = cx in x until x + w && cy in y until y + h
    fun overlaps(ox: Int, oy: Int, ow: Int, oh: Int) = x < ox + ow && ox < x + w && y < oy + oh && oy < y + h
}

@Serializable
data class Settings(
    val themedIcons: Boolean = true,
)

@Serializable
data class HomeLayout(
    val items: List<HomeItem> = emptyList(),
    val nextId: Long = 1,
    val settings: Settings = Settings(),
    val seeded: Boolean = false,
    /** The user's own names for apps, by [AppKey.toString]; search finds the app by them. */
    val aliases: Map<String, List<String>> = emptyMap(),
) {
    val feed get() = items.filter { !it.dock }
    val dock get() = items.filter { it.dock }

    fun isFree(x: Int, y: Int, w: Int, h: Int, ignore: Long? = null): Boolean {
        if (x < 0 || y < 0 || x + w > COLUMNS) return false
        return feed.none { it.id != ignore && it.overlaps(x, y, w, h) }
    }

    fun dockSlotFree(slot: Int, ignore: Long? = null) =
        slot in 0 until DOCK_SLOTS && dock.none { it.id != ignore && it.x == slot }

    /** First free area scanning rows top to bottom, then left to right. */
    fun firstFree(w: Int, h: Int, fromY: Int = 0): Pair<Int, Int> {
        var y = fromY
        while (true) {
            for (x in 0..COLUMNS - w) if (isFree(x, y, w, h)) return x to y
            y++
        }
    }

    val rows get() = feed.maxOfOrNull { it.y + it.h } ?: 0

    companion object {
        const val COLUMNS = 5
        const val DOCK_SLOTS = 5
    }
}

class LayoutStore(context: Context, private val scope: CoroutineScope) {
    private val file = AtomicFile(File(context.filesDir, "home.json"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; classDiscriminator = "type" }

    private val _layout = MutableStateFlow(load())
    val layout: StateFlow<HomeLayout> = _layout.asStateFlow()

    private fun load(): HomeLayout = try {
        if (file.baseFile.exists()) json.decodeFromString<HomeLayout>(file.readFully().decodeToString()) else HomeLayout()
    } catch (e: Exception) {
        android.util.Log.e("Foyer", "Broken home.json, starting fresh", e)
        HomeLayout()
    }

    fun edit(transform: (HomeLayout) -> HomeLayout) {
        _layout.update(transform)
        val snapshot = _layout.value
        scope.launch(Dispatchers.IO) { save(snapshot) }
    }

    @Synchronized
    private fun save(layout: HomeLayout) {
        if (layout !== _layout.value) return // a newer edit will save itself
        val out = file.startWrite()
        try {
            out.write(json.encodeToString(HomeLayout.serializer(), layout).encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
        }
    }
}

/** Pure edits on the layout, kept here so the UI only describes intent. */
object Edits {
    fun add(l: HomeLayout, content: Content, x: Int, y: Int, w: Int = 1, h: Int = 1, dock: Boolean = false): HomeLayout {
        val span = if (content is FolderContent) { if (content.large) 2 else 1 } else w
        val height = if (content is FolderContent) span else h
        if (content is FolderContent && (dock || content.apps.distinct() != content.apps || content.large && content.apps.size > 9)) return l
        if (dock) { if (!l.dockSlotFree(x)) return l } else if (!l.isFree(x, y, span, height)) return l
        return l.copy(items = l.items + HomeItem(l.nextId, content, x, y, span, height, dock), nextId = l.nextId + 1)
    }

    fun addFirstFree(l: HomeLayout, content: Content, w: Int = 1, h: Int = 1): HomeLayout {
        val span = if (content is FolderContent) { if (content.large) 2 else 1 } else w
        val (x, y) = l.firstFree(span, if (content is FolderContent) span else h)
        return add(l, content, x, y, w, h)
    }

    fun setAliases(l: HomeLayout, app: AppKey, aliases: List<String>) =
        l.copy(aliases = if (aliases.isEmpty()) l.aliases - app.toString() else l.aliases + (app.toString() to aliases))

    fun remove(l: HomeLayout, id: Long) = l.copy(items = l.items.filterNot { it.id == id })

    fun move(l: HomeLayout, id: Long, x: Int, y: Int, dock: Boolean): HomeLayout {
        val item = l.items.find { it.id == id } ?: return l
        if (dock && (item.content is FolderContent || item.content is WidgetContent)) return l
        if (dock) { if (!l.dockSlotFree(x, id)) return l } else if (!l.isFree(x, y, item.w, item.h, id)) return l
        return l.copy(items = l.items.map { if (it.id == id) it.copy(x = x, y = y, dock = dock) else it })
    }

    fun resize(l: HomeLayout, id: Long, x: Int, y: Int, w: Int, h: Int): HomeLayout {
        if (l.items.find { it.id == id }?.content !is WidgetContent || !l.isFree(x, y, w, h, id)) return l
        return l.copy(items = l.items.map { if (it.id == id) it.copy(x = x, y = y, w = w, h = h) else it })
    }

    /** Reject invalid edits atomically: membership and occupied cells never change on failure. */
    fun folder(l: HomeLayout, id: Long, folder: FolderContent): HomeLayout {
        val item = l.items.find { it.id == id && it.content is FolderContent && !it.dock } ?: return l
        val span = if (folder.large) 2 else 1
        if (folder.apps != folder.apps.distinct() || folder.large && folder.apps.size > 9 ||
            !l.isFree(item.x, item.y, span, span, id)) return l
        return l.copy(items = l.items.map { if (it.id == id) it.copy(content = folder, w = span, h = span) else it })
    }

    /** A drag only commits against current membership, placement and installed-app identity. */
    fun transferApp(
        l: HomeLayout, key: AppKey, sourceItem: Long? = null, sourceFolder: Long? = null,
        targetFolder: Long? = null, index: Int = Int.MAX_VALUE,
        x: Int = 0, y: Int = 0, dock: Boolean = false, available: Boolean = true,
    ): HomeLayout {
        if (!available || sourceItem != null && sourceFolder != null) return l
        val source = sourceItem?.let { id -> l.items.find { it.id == id } }
        if (sourceItem != null && source?.content != AppContent(key)) return l
        val from = sourceFolder?.let { id -> l.items.find { it.id == id }?.content as? FolderContent }
        if (sourceFolder != null && (from == null || key !in from.apps)) return l
        val target = targetFolder?.let { id -> l.items.find { it.id == id }?.content as? FolderContent }
        if (targetFolder != null && target == null) return l
        if (target != null) {
            if (sourceFolder != targetFolder && (key in target.apps || target.large && target.apps.size >= 9)) return l
            val members = target.apps - key
            val updated = members.toMutableList().apply { add(index.coerceIn(0, size), key) }
            var result = folder(l, targetFolder, target.copy(apps = updated))
            if (sourceItem != null) result = remove(result, sourceItem)
            if (sourceFolder != null && sourceFolder != targetFolder) result = folder(result, sourceFolder, from!!.copy(apps = from.apps - key))
            return result
        }
        if (source != null) return move(l, source.id, x, y, dock)
        val added = add(l, AppContent(key), x, y, dock = dock)
        if (added === l) return l
        return if (sourceFolder == null) added else folder(added, sourceFolder, from!!.copy(apps = from.apps - key))
    }

    fun reconcileFolderApps(l: HomeLayout, missing: Set<AppKey>): HomeLayout = l.copy(
        items = l.items.map { item ->
            val folder = item.content as? FolderContent
            if (folder == null) item else item.copy(content = folder.copy(apps = folder.apps - missing))
        },
    )

    /** Apply a draft to the latest layout, without restoring original members removed meanwhile. */
    fun folderDraft(l: HomeLayout, id: Long, draft: FolderContent, original: Set<AppKey>, missing: Set<AppKey>): HomeLayout {
        val current = (l.items.find { it.id == id }?.content as? FolderContent)?.apps.orEmpty()
        return folder(l, id, draft.copy(apps = draft.apps.filter { it !in missing && (it !in original || it in current) }))
    }

    fun forgetProfiles(l: HomeLayout, users: Set<Long>): HomeLayout = l.copy(
        items = l.items.map { item ->
            val c = item.content
            if (c is FolderContent) item.copy(content = c.copy(apps = c.apps.filterNot { it.user in users })) else item
        },
    )

    fun forgetPackage(l: HomeLayout, pkg: String, user: Long): HomeLayout = l.copy(
        items = l.items.mapNotNull { item ->
            when (val c = item.content) {
                is AppContent -> item.takeUnless { c.app.pkg == pkg && c.app.user == user }
                is ShortcutContent -> item.takeUnless { c.pkg == pkg && c.user == user }
                is WidgetContent -> item.takeUnless { c.provider.startsWith("$pkg/") }
                is FolderContent -> item.copy(content = c.copy(apps = c.apps.filterNot { it.pkg == pkg && it.user == user }))
            }
        },
        aliases = l.aliases.filterKeys { !it.startsWith("$user/$pkg/") },
    )

    fun swap(l: HomeLayout, a: HomeItem, b: HomeItem): HomeLayout {
        if (a.w != b.w || a.h != b.h ||
            a.content is FolderContent && b.dock || b.content is FolderContent && a.dock) return l
        return l.copy(
            items = l.items.map {
                when (it.id) {
                    a.id -> it.copy(x = b.x, y = b.y, dock = b.dock)
                    b.id -> it.copy(x = a.x, y = a.y, dock = a.dock)
                    else -> it
                }
            },
        )
    }
}
