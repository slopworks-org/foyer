package app.foyer.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** One independently movable occurrence. Slot is absolute only while the folder is large. */
@Serializable
data class FolderEntry(val app: AppKey, val id: String = java.util.UUID.randomUUID().toString(), val slot: Int? = null)

@Serializable(with = FolderSerializer::class)
@SerialName("folder")
data class FolderContent(
    val entries: List<FolderEntry> = emptyList(),
    val large: Boolean = false,
    val name: String = "",
) : Content {
    /** Legacy construction and decoding consume each occurrence once, preserving occupied cells. */
    constructor(apps: List<AppKey>, large: Boolean = false, name: String = "", slots: List<AppKey?> = emptyList()) :
        this(legacyEntries(apps, slots), large, name)

    val apps get() = entries.map { it.app }
    val slots get() = if (!large) emptyList() else (0..8).map { slot -> entries.find { it.slot == slot }?.app }
    val slotEntries get() = (0..8).map { slot -> entries.find { it.slot == slot } }

    fun normalized(): FolderContent {
        if (entries.isEmpty()) return if (large) copy(name = "") else this
        val ids = mutableSetOf<String>()
        val repaired = entries.map { entry ->
            if (entry.id.isNotBlank() && ids.add(entry.id)) entry else {
                var id: String
                do { id = java.util.UUID.randomUUID().toString() } while (!ids.add(id))
                entry.copy(id = id)
            }
        }
        if (!large || repaired.size > 9) return copy(entries = repaired.map { it.copy(slot = null) }, large = false)
        val occupied = mutableSetOf<Int>()
        val fixed = repaired.map { entry ->
            entry.copy(slot = entry.slot?.takeIf { it in 0..8 && occupied.add(it) })
        }.map { entry ->
            if (entry.slot != null) entry else entry.copy(slot = (0..8).first { occupied.add(it) })
        }.sortedBy { it.slot }
        return copy(entries = fixed, name = "")
    }

    fun without(keys: Set<AppKey>): FolderContent = copy(entries = entries.filterNot { it.app in keys }).normalized()
    fun withoutEntry(id: String): FolderContent = copy(entries = entries.filterNot { it.id == id }).normalized()
}

private fun legacyEntries(apps: List<AppKey>, slots: List<AppKey?>): List<FolderEntry> {
    val remaining = apps.map { FolderEntry(it) }.toMutableList()
    val result = mutableListOf<FolderEntry>()
    slots.take(9).forEachIndexed { index, key ->
        val member = remaining.indexOfFirst { it.app == key }
        if (member >= 0) result += remaining.removeAt(member).copy(slot = index)
    }
    return result + remaining
}

/** Read old apps/slots files, write only the canonical occurrence representation. */
@Serializable
@SerialName("folder")
private data class FolderWire(
    val entries: List<FolderEntry>? = null, val large: Boolean = false, val name: String = "",
    val apps: List<AppKey> = emptyList(), val slots: List<AppKey?> = emptyList(),
)

object FolderSerializer : kotlinx.serialization.KSerializer<FolderContent> {
    override val descriptor = FolderWire.serializer().descriptor
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: FolderContent) =
        encoder.encodeSerializableValue(FolderWire.serializer(), FolderWire(value.entries, value.large, value.name))
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): FolderContent {
        val wire = decoder.decodeSerializableValue(FolderWire.serializer())
        return FolderContent(wire.entries ?: legacyEntries(wire.apps, wire.slots), wire.large, wire.name).normalized()
    }
}

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
    val themedIcons: Boolean = false,
    val showAppLabels: Boolean = false,
    /** Null means the update-check question has not been answered. */
    val updateChecks: Boolean? = null,
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
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) HomeLayout() else {
            val raw = file.readFully().decodeToString()
            val normalized = Edits.normalizeFolders(json.decodeFromString<HomeLayout>(raw))
            // Also persist repaired or decoder-generated IDs in canonical files.
            val migrated = json.parseToJsonElement(raw) != json.parseToJsonElement(json.encodeToString(HomeLayout.serializer(), normalized))
            if (migrated) runCatching { write(normalized) }.onFailure {
                android.util.Log.e("Foyer", "Could not persist folder entry migration", it)
            }
            normalized
        }
    } catch (e: Exception) {
        android.util.Log.e("Foyer", "Broken home.json, starting fresh", e)
        HomeLayout()
    }

    private val replacements = mutableMapOf<Long, FolderReplacement>()

    @Synchronized
    fun takePresentation(): LayoutChange =
        LayoutChange(_layout.value, replacements.values.toList()).also { replacements.clear() }

    fun edit(transform: (HomeLayout) -> HomeLayout) = editChange { LayoutChange(transform(it)) }

    @Synchronized
    fun editChange(transform: (HomeLayout) -> LayoutChange) {
        val change = transform(_layout.value)
        replacements.putAll(change.replacements.associateBy { it.placedId })
        replacements.entries.removeAll { (id, transition) ->
            change.layout.items.find { it.id == id }?.content != AppContent(transition.survivor.app)
        }
        _layout.value = change.layout
        val snapshot = _layout.value
        scope.launch(Dispatchers.IO) {
            runCatching { save(snapshot) }.onFailure { android.util.Log.e("Foyer", "Could not save home layout", it) }
        }
    }

    @Synchronized
    private fun save(layout: HomeLayout) {
        if (layout !== _layout.value) return // a newer edit will save itself
        write(layout)
    }

    private fun write(layout: HomeLayout) {
        val out = file.startWrite()
        try {
            out.write(json.encodeToString(HomeLayout.serializer(), layout).encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }
}

/** Occurrence-specific presentation metadata, owned by a live commit rather than a placed item. */
data class FolderReplacement(val placedId: Long, val survivor: FolderEntry)
data class LayoutChange(val layout: HomeLayout, val replacements: List<FolderReplacement> = emptyList())

/** Accepted transfer, including no-op reorders; null planning results mean rejection. */
data class TransferResult(val layout: HomeLayout, val landedId: Long,
    val incomingId: String? = null, val destination: Long? = null,
    val replacements: List<FolderReplacement> = emptyList()) {
    val change get() = LayoutChange(layout, replacements)
}

/** Pure edits on the layout, kept here so the UI only describes intent. */
object Edits {
    private fun replacement(id: Long, folder: FolderContent) =
        folder.entries.singleOrNull()?.takeUnless { folder.large }?.let { FolderReplacement(id, it) }

    fun folderChange(l: HomeLayout, id: Long, content: FolderContent): LayoutChange =
        planFolderChange(l, id, content) ?: LayoutChange(l)

    fun reconcileFolderAppsChange(l: HomeLayout, missing: Set<AppKey>) =
        LayoutChange(reconcileFolderApps(l, missing), l.items.mapNotNull { item ->
            (item.content as? FolderContent)?.let { replacement(item.id, it.without(missing)) }
        })

    fun forgetPackageChange(l: HomeLayout, pkg: String, user: Long) =
        LayoutChange(forgetPackage(l, pkg, user), l.items.mapNotNull { item ->
            (item.content as? FolderContent)?.let { folder ->
                replacement(item.id, folder.without(folder.apps.filter { it.pkg == pkg && it.user == user }.toSet()))
            }
        })

    /** Large folders retain their footprint at every count; only small singletons become apps. */
    private fun canonicalFolder(item: HomeItem, folder: FolderContent): HomeItem {
        if (!folder.large && folder.entries.size == 1) return item.copy(content = AppContent(folder.entries.single().app), w = 1, h = 1)
        val normalized = folder.normalized()
        return item.copy(content = normalized, w = if (normalized.large) 2 else 1, h = if (normalized.large) 2 else 1)
    }

    fun normalizeFolders(l: HomeLayout): HomeLayout = l.copy(items = l.items.map { item ->
        (item.content as? FolderContent)?.let { canonicalFolder(item, it) } ?: item
    }, nextId = maxOf(l.nextId, (l.items.maxOfOrNull { it.id } ?: 0).coerceAtMost(Long.MAX_VALUE - 1) + 1))

    fun add(l: HomeLayout, content: Content, x: Int, y: Int, w: Int = 1, h: Int = 1, dock: Boolean = false): HomeLayout {
        val span = if (content is FolderContent) { if (content.large) 2 else 1 } else w
        val height = if (content is FolderContent) span else h
        if (content is FolderContent && (dock || content.entries.map { it.id }.distinct().size != content.entries.size || content.large && content.apps.size > 9)) return l
        if (dock) { if (!l.dockSlotFree(x)) return l } else if (!l.isFree(x, y, span, height)) return l
        return l.copy(items = l.items + HomeItem(l.nextId, content, x, y, span, height, dock).let { if (content is FolderContent) canonicalFolder(it, content) else it }, nextId = l.nextId + 1)
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

    /** Reserve the destination, then relocate its occupants within the existing grid atomically. */
    fun displace(l: HomeLayout, incoming: HomeItem): HomeLayout? {
        if (incoming.dock && (incoming.content is WidgetContent || incoming.content is FolderContent)) return null
        if (incoming.x < 0 || incoming.y < 0 || incoming.w < 1 || incoming.h < 1 ||
            incoming.x + incoming.w > (if (incoming.dock) HomeLayout.DOCK_SLOTS else HomeLayout.COLUMNS)) return null
        val others = l.items.filterNot { it.id == incoming.id }
        val blocked = others.filter { it.dock == incoming.dock &&
            it.overlaps(incoming.x, incoming.y, incoming.w, incoming.h) }
            .sortedWith(compareByDescending<HomeItem> { it.w * it.h }.thenBy { it.id })
        val rows = if (incoming.dock) 1 else maxOf(l.rows, incoming.y + incoming.h)
        val reserved = l.copy(items = others.filterNot { it in blocked } + incoming)
        val columns = if (incoming.dock) HomeLayout.DOCK_SLOTS else HomeLayout.COLUMNS
        if (blocked.sumOf { it.w * it.h } > columns * rows - reserved.items
                .filter { it.dock == incoming.dock }.sumOf { it.w * it.h }) return null
        fun relocate(index: Int, current: HomeLayout): HomeLayout? {
            if (index == blocked.size) return current
            val item = blocked[index]
            val destinations = (0..rows - item.h).flatMap { y -> (0..columns - item.w).map { x -> x to y } }
                .sortedWith(compareBy<Pair<Int, Int>> { (x, y) ->
                    (x - item.x) * (x - item.x) + (y - item.y) * (y - item.y)
                }.thenBy { it.second }.thenBy { it.first })
                .filter { (x, y) -> if (incoming.dock) current.dockSlotFree(x) else current.isFree(x, y, item.w, item.h) }
            for ((x, y) in destinations) {
                val next = current.copy(items = current.items + item.copy(x = x, y = y))
                relocate(index + 1, next)?.let { return it }
                // All remaining single-cell items share the same free cells.
                if (item.w == 1 && item.h == 1) return null
            }
            return null
        }
        val result = relocate(0, reserved) ?: return null
        val positions = result.items.associateBy { it.id }
        return result.copy(items = l.items.map { positions.getValue(it.id) } +
            listOfNotNull(incoming.takeUnless { candidate -> l.items.any { it.id == candidate.id } }),
            nextId = maxOf(l.nextId, incoming.id + 1))
    }

    fun resize(l: HomeLayout, id: Long, x: Int, y: Int, w: Int, h: Int): HomeLayout {
        if (l.items.find { it.id == id }?.content !is WidgetContent || !l.isFree(x, y, w, h, id)) return l
        return l.copy(items = l.items.map { if (it.id == id) it.copy(x = x, y = y, w = w, h = h) else it })
    }

    /** Reject invalid edits atomically: membership and occupied cells never change on failure. */
    fun folder(l: HomeLayout, id: Long, folder: FolderContent): HomeLayout =
        planFolderChange(l, id, folder)?.layout ?: l

    fun planFolderChange(l: HomeLayout, id: Long, folder: FolderContent): LayoutChange? {
        val item = l.items.find { it.id == id && it.content is FolderContent && !it.dock } ?: return null
        val span = if (folder.large) 2 else 1
        if (folder.entries.map { it.id }.distinct().size != folder.entries.size || folder.large && folder.apps.size > 9 ||
            !l.isFree(item.x, item.y, span, span, id)) return null
        val old = item.content as FolderContent
        if (old.large && !folder.large && (old.entries.size < 2 || folder.entries.size < 2)) return null
        // Mode changes deliberately pack occupied row-major order; large mode has no name.
        val normalized = (if (old.large != folder.large) folder.copy(name = if (folder.large) "" else folder.name, entries = folder.entries.map { it.copy(slot = null) }) else folder).normalized()
        return LayoutChange(l.copy(items = l.items.map { if (it.id == id) canonicalFolder(it.copy(w = span, h = span), normalized) else it }),
            listOfNotNull(replacement(id, normalized)))
    }

    fun transferApp(
        l: HomeLayout, key: AppKey, sourceItem: Long? = null, sourceFolder: Long? = null,
        targetFolder: Long? = null, index: Int = Int.MAX_VALUE,
        x: Int = 0, y: Int = 0, dock: Boolean = false, available: Boolean = true,
        sourceEntry: String? = null,
    ): HomeLayout = planTransferApp(l, key, sourceItem, sourceFolder, targetFolder, index, x, y, dock, available, sourceEntry)?.layout ?: l

    /** Atomic occurrence transfer; a stale or ambiguous source never consumes anything. */
    fun planTransferApp(
        l: HomeLayout, key: AppKey, sourceItem: Long? = null, sourceFolder: Long? = null,
        targetFolder: Long? = null, index: Int = Int.MAX_VALUE,
        x: Int = 0, y: Int = 0, dock: Boolean = false, available: Boolean = true,
        sourceEntry: String? = null, incomingId: String = java.util.UUID.randomUUID().toString(),
    ): TransferResult? {
        if (!available || sourceItem != null && sourceFolder != null) return null
        val source = sourceItem?.let { id -> l.items.find { it.id == id } }
        if (sourceItem != null && source?.content != AppContent(key)) return null
        val from = sourceFolder?.let { id -> l.items.find { it.id == id }?.content as? FolderContent }
        val entry = from?.entries?.let { entries ->
            if (sourceEntry != null) entries.singleOrNull { it.id == sourceEntry && it.app == key }
            else entries.singleOrNull { it.app == key }
        }
        if (sourceFolder != null && entry == null) return null
        val target = targetFolder?.let { id -> l.items.find { it.id == id && !it.dock }?.content as? FolderContent }
        if (targetFolder != null && target == null) return null
        if (target != null) {
            val same = sourceFolder == targetFolder
            if (!same && target.large && target.entries.size >= 9) return null
            val incoming = entry ?: FolderEntry(key, id = incomingId)
            if (!same && target.entries.any { it.id == incoming.id }) return null
            val updated: List<FolderEntry>
            if (target.large) {
                val fixed = target.normalized().slotEntries.toMutableList()
                val old = if (same) fixed.indexOfFirst { it?.id == incoming.id } else -1
                val slot = if (index == Int.MAX_VALUE) { if (old >= 0) old else fixed.indexOf(null) } else index
                if (slot !in 0..8 || old < 0 && fixed[slot] != null) return null
                if (old >= 0) fixed[old] = fixed[slot]?.copy(slot = old)
                fixed[slot] = incoming.copy(slot = slot)
                updated = fixed.filterNotNull()
            } else {
                val old = if (same) target.entries.indexOfFirst { it.id == incoming.id } else -1
                val members = target.entries.filterNot { same && it.id == incoming.id }.toMutableList()
                val insertion = when {
                    old >= 0 && old < index && index != Int.MAX_VALUE -> index - 1
                    !same && target.entries.size == 4 && index >= 4 -> 2
                    else -> index
                }
                members.add(insertion.coerceIn(0, members.size), incoming.copy(slot = null))
                updated = members
            }
            val destination = planFolderChange(l, targetFolder, target.copy(entries = updated)) ?: return null
            var result = destination.layout
            if (sourceItem != null) result = remove(result, sourceItem)
            val departure = if (sourceFolder != null && !same)
                planFolderChange(result, sourceFolder, from!!.withoutEntry(entry!!.id)) ?: return null
                else LayoutChange(result)
            return TransferResult(departure.layout, targetFolder, incoming.id, targetFolder,
                destination.replacements + departure.replacements)
        }
        val incoming = source?.copy(x = x, y = y, dock = dock)
            ?: HomeItem(l.nextId, AppContent(key), x, y, dock = dock)
        val added = displace(l, incoming) ?: return null
        if (source != null) return TransferResult(added, source.id)
        val departure = if (sourceFolder == null) LayoutChange(added)
            else planFolderChange(added, sourceFolder, from!!.withoutEntry(entry!!.id)) ?: return null
        return TransferResult(departure.layout, l.nextId, replacements = departure.replacements)
    }

    /** Replace the target in place only after validating the whole move. */
    fun mergeApps(l: HomeLayout, target: HomeItem, key: AppKey, sourceItem: HomeItem? = null,
                  sourceFolder: Long? = null, sourceEntry: String? = null, available: Boolean = true, incomingId: String = java.util.UUID.randomUUID().toString()): HomeLayout =
        mergeChange(l, target, key, sourceItem, sourceFolder, sourceEntry, available, incomingId)?.layout ?: l

    fun mergeChange(l: HomeLayout, target: HomeItem, key: AppKey, sourceItem: HomeItem? = null,
                  sourceFolder: Long? = null, sourceEntry: String? = null, available: Boolean = true, incomingId: String = java.util.UUID.randomUUID().toString()): LayoutChange? {
        if (!available || target.dock || target.w != 1 || target.h != 1 || target.content !is AppContent ||
            !l.isFree(target.x, target.y, 1, 1, target.id) ||
            l.items.find { it.id == target.id } != target || sourceItem?.id == target.id ||
            sourceItem != null && sourceFolder != null) return null
        if (sourceItem != null && (l.items.find { it.id == sourceItem.id } != sourceItem || sourceItem.content != AppContent(key))) return null
        val from = l.items.find { it.id == sourceFolder }?.content as? FolderContent
        val entry = from?.entries?.singleOrNull { it.id == sourceEntry && it.app == key }
        if (sourceFolder != null && entry == null) return null
        var result = l.copy(items = l.items.map {
            if (it.id == target.id) it.copy(content = FolderContent(entries = listOf(FolderEntry(target.content.app), entry?.copy(slot = null) ?: FolderEntry(key, id = incomingId)))) else it
        })
        if (sourceItem != null) result = remove(result, sourceItem.id)
        return if (sourceFolder == null) LayoutChange(result)
            else planFolderChange(result, sourceFolder, from!!.withoutEntry(entry!!.id))
    }

    fun reconcileFolderApps(l: HomeLayout, missing: Set<AppKey>): HomeLayout = l.copy(
        items = l.items.mapNotNull { item ->
            val folder = item.content as? FolderContent
            if (folder == null) item else canonicalFolder(item, folder.without(missing))
        },
    )

    fun forgetProfiles(l: HomeLayout, users: Set<Long>): HomeLayout = l.copy(
        items = l.items.mapNotNull { item ->
            val c = item.content
            when (c) {
                is FolderContent -> canonicalFolder(item, c.without(c.apps.filter { it.user in users }.toSet()))
                is AppContent -> item.takeUnless { c.app.user in users }
                is ShortcutContent -> item.takeUnless { c.user in users }
                is WidgetContent -> item
            }
        },
    )

    fun forgetPackage(l: HomeLayout, pkg: String, user: Long): HomeLayout = l.copy(
        items = l.items.mapNotNull { item ->
            when (val c = item.content) {
                is AppContent -> item.takeUnless { c.app.pkg == pkg && c.app.user == user }
                is ShortcutContent -> item.takeUnless { c.pkg == pkg && c.user == user }
                is WidgetContent -> item.takeUnless { c.provider.startsWith("$pkg/") }
                is FolderContent -> canonicalFolder(item, c.without(c.apps.filter { it.pkg == pkg && it.user == user }.toSet()))
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
