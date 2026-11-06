package app.foyer.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FolderEntriesTest {
    @Test fun malformedCanonicalIdsAreRepairedWithoutLosingOccurrences() {
        val key = """{"pkg":"same","cls":"Main","user":0}"""
        val raw = """{"items":[{"id":1,"x":0,"y":0,"content":{"type":"folder","entries":[{"app":$key,"id":"same"},{"app":$key,"id":"same"},{"app":$key,"id":""},{"app":$key},{"app":$key,"id":"empty-1"}]}}]}"""
        val loaded = json.decodeFromString<HomeLayout>(raw)
        val entries = folder(loaded).entries
        assertEquals(5, entries.size)
        assertEquals(5, entries.map { it.id }.distinct().size)
        assertTrue(entries.all { it.id.isNotBlank() })
        assertEquals(4, folder(loaded).withoutEntry(entries.first().id).entries.size)
        assertEquals(loaded, json.decodeFromString<HomeLayout>(json.encodeToString(HomeLayout.serializer(), loaded)))
    }

    private val app = AppKey("same", "Main", 0)
    private val other = AppKey("other", "Main", 0)
    private val json = Json { classDiscriminator = "type" }
    private fun folder(l: HomeLayout, id: Long = 1) = l.items.first { it.id == id }.content as FolderContent

    @Test fun legacyHolesAndRepeatedAppsMigrateToCanonicalOccurrences() {
        val old = """{"items":[{"id":1,"x":0,"y":0,"w":2,"h":2,"content":{"type":"folder","large":true,"apps":[{"pkg":"same","cls":"Main","user":0},{"pkg":"same","cls":"Main","user":0}],"slots":[{"pkg":"same","cls":"Main","user":0},null,null,null,null,null,null,null,{"pkg":"same","cls":"Main","user":0}]}}]}"""
        val migrated = Edits.normalizeFolders(json.decodeFromString<HomeLayout>(old))
        assertEquals(listOf(0, 8), folder(migrated).entries.map { it.slot })
        assertEquals(2, folder(migrated).entries.map { it.id }.distinct().size)
        val encoded = json.encodeToString(HomeLayout.serializer(), migrated)
        assertFalse(encoded.contains("\"apps\""))
        assertFalse(encoded.contains("\"slots\""))
        assertEquals(migrated, json.decodeFromString<HomeLayout>(encoded))
        val removed = folder(migrated).withoutEntry(folder(migrated).entries.first().id)
        assertNull(removed.slots[0]); assertEquals(app, removed.slots[8])
    }

    @Test fun duplicateOccurrencesReorderMoveRemoveAndSurviveModeChanges() {
        val a = FolderEntry(app); val b = FolderEntry(app); val c = FolderEntry(other)
        val initial = Edits.add(HomeLayout(), FolderContent(entries = listOf(a, b, c)), 0, 0)
        val reordered = Edits.transferApp(initial, app, sourceFolder = 1, sourceEntry = b.id, targetFolder = 1, index = 0)
        assertEquals(listOf(b.id, a.id, c.id), folder(reordered).entries.map { it.id })
        assertSame(initial, Edits.transferApp(initial, app, sourceFolder = 1, targetFolder = 1))
        val large = Edits.folder(reordered, 1, folder(reordered).copy(large = true))
        val hole = Edits.transferApp(large, app, sourceFolder = 1, sourceEntry = a.id, targetFolder = 1, index = 8)
        assertEquals(a.id, folder(hole).slotEntries[8]?.id)
        assertNull(folder(hole).slotEntries[1])
        val small = Edits.folder(hole, 1, folder(hole).copy(large = false))
        assertEquals(listOf(b.id, c.id, a.id), folder(small).entries.map { it.id })
        val destination = FolderEntry(other)
        val companion = FolderEntry(other)
        val two = Edits.add(small, FolderContent(entries = listOf(destination, companion)), 3, 0)
        val moved = Edits.transferApp(two, app, sourceFolder = 1, sourceEntry = b.id, targetFolder = 2)
        assertEquals(listOf(c.id, a.id), folder(moved).entries.map { it.id })
        assertEquals(listOf(destination.id, companion.id, b.id), folder(moved, 2).entries.map { it.id })
        assertEquals(moved, json.decodeFromString<HomeLayout>(json.encodeToString(HomeLayout.serializer(), moved)))
        assertEquals(AppContent(other), Edits.forgetPackage(moved, app.pkg, app.user).items.first().content)
        val stale = Edits.folder(two, 1, folder(two).withoutEntry(b.id))
        assertSame(stale, Edits.transferApp(stale, app, sourceFolder = 1, sourceEntry = b.id, targetFolder = 2))
    }

    @Test fun crossFolderDuplicateTransferRetainsEachOccurrenceAndProfileCleanupRemovesAll() {
        val a = FolderEntry(app); val b = FolderEntry(app); val c = FolderEntry(app); val d = FolderEntry(app)
        val source = Edits.add(HomeLayout(), FolderContent(entries = listOf(a, b)), 0, 0)
        val both = Edits.add(source, FolderContent(entries = listOf(c, d)), 3, 0)
        val moved = Edits.transferApp(both, app, sourceFolder = 1, sourceEntry = b.id, targetFolder = 2)
        assertEquals(AppContent(app), moved.items.first().content)
        assertEquals(listOf(c.id, d.id, b.id), folder(moved, 2).entries.map { it.id })
        val large = Edits.folder(moved, 2, folder(moved, 2).copy(large = true))
        val restored = json.decodeFromString<HomeLayout>(json.encodeToString(HomeLayout.serializer(), large))
        assertEquals(listOf(c.id, d.id, b.id), folder(restored, 2).entries.map { it.id })
        val cleaned = Edits.forgetProfiles(restored, setOf(app.user))
        assertTrue((cleaned.items.single().content as FolderContent).entries.isEmpty())
    }

    @Test fun nineDuplicateEntriesFillCapacityAndRejectWithoutConsumingSource() {
        val full = Edits.add(HomeLayout(), FolderContent(List(9) { app }, true), 0, 0)
        val placed = Edits.add(full, AppContent(app), 3, 0)
        assertSame(placed, Edits.transferApp(placed, app, sourceItem = 2, targetFolder = 1))
        val source = Edits.add(full, FolderContent(listOf(app, other)), 3, 0)
        val id = folder(source, 2).entries.first().id
        assertSame(source, Edits.transferApp(source, app, sourceFolder = 2, sourceEntry = id, targetFolder = 1))
        val reordered = Edits.transferApp(full, app, sourceFolder = 1, sourceEntry = folder(full).entries[0].id, targetFolder = 1, index = 8)
        assertEquals(folder(full).entries[0].id, folder(reordered).slotEntries[8]?.id)
    }

    @Test fun creationIsAtomicAndPreservesTargetPositionAndSourceIdentity() {
        val targetOnly = Edits.add(HomeLayout(), AppContent(app), 2, 3)
        val target = targetOnly.items.single()
        val external = Edits.mergeApps(targetOnly, target, app)
        assertEquals(listOf(app, app), folder(external).apps)
        assertEquals(2, folder(external).entries.map { it.id }.distinct().size)
        assertEquals(target.copy(content = folder(external)), external.items.single())
        assertSame(targetOnly, Edits.mergeApps(targetOnly, target, app, target))
        assertSame(targetOnly, Edits.mergeApps(targetOnly, target, app, available = false))
        val source = Edits.add(targetOnly, FolderContent(listOf(app, app)), 0, 0)
        val entry = folder(source, 2).entries[1]
        val merged = Edits.mergeApps(source, target, app, sourceFolder = 2, sourceEntry = entry.id)
        assertEquals(entry.id, folder(merged).entries[1].id)
        assertEquals(AppContent(app), merged.items.first { it.id == 2L }.content)
        val stale = Edits.move(source, target.id, 3, 3, false)
        assertSame(stale, Edits.mergeApps(stale, target, app, sourceFolder = 2, sourceEntry = entry.id))
        val placed = Edits.add(targetOnly, AppContent(app), 0, 0)
        val sourceItem = placed.items.last()
        val movedSource = Edits.move(placed, sourceItem.id, 1, 0, false)
        assertSame(movedSource, Edits.mergeApps(movedSource, target, app, sourceItem))
        val collision = targetOnly.copy(items = targetOnly.items + HomeItem(9, AppContent(other), target.x, target.y))
        assertSame(collision, Edits.mergeApps(collision, target, app))
        val dock = Edits.add(HomeLayout(), AppContent(app), 0, 0, dock = true)
        assertSame(dock, Edits.mergeApps(dock, dock.items.single(), app))
    }
}
