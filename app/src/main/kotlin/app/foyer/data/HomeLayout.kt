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
    fun add(l: HomeLayout, content: Content, x: Int, y: Int, w: Int = 1, h: Int = 1, dock: Boolean = false): HomeLayout =
        l.copy(items = l.items + HomeItem(l.nextId, content, x, y, w, h, dock), nextId = l.nextId + 1)

    fun addFirstFree(l: HomeLayout, content: Content, w: Int = 1, h: Int = 1): HomeLayout {
        val (x, y) = l.firstFree(w, h)
        return add(l, content, x, y, w, h)
    }

    fun setAliases(l: HomeLayout, app: AppKey, aliases: List<String>) =
        l.copy(aliases = if (aliases.isEmpty()) l.aliases - app.toString() else l.aliases + (app.toString() to aliases))

    fun remove(l: HomeLayout, id: Long) = l.copy(items = l.items.filterNot { it.id == id })

    fun move(l: HomeLayout, id: Long, x: Int, y: Int, dock: Boolean) =
        l.copy(items = l.items.map { if (it.id == id) it.copy(x = x, y = y, dock = dock) else it })

    fun resize(l: HomeLayout, id: Long, x: Int, y: Int, w: Int, h: Int) =
        l.copy(items = l.items.map { if (it.id == id) it.copy(x = x, y = y, w = w, h = h) else it })

    fun swap(l: HomeLayout, a: HomeItem, b: HomeItem) = l.copy(
        items = l.items.map {
            when (it.id) {
                a.id -> it.copy(x = b.x, y = b.y, dock = b.dock)
                b.id -> it.copy(x = a.x, y = a.y, dock = a.dock)
                else -> it
            }
        },
    )
}
