package app.foyer.data

import org.junit.Assert.*
import org.junit.Test

class EmptyFoldersTest {
    private val key = AppKey("pkg", "Main", 0)
    @Test fun rejectEmptyCreationAndNormalizeWithoutCompactionOrIdReuse() {
        val empty = HomeLayout(nextId = 20)
        assertSame(empty, Edits.add(empty, FolderContent(), 0, 0))
        val singleton = HomeItem(9, FolderContent(listOf(key), name = "Keep"), 4, 8)
        val legacy = empty.copy(items = listOf(HomeItem(2, FolderContent(large = true), 0, 0, 2, 2), singleton))
        val normalized = Edits.normalizeFolders(legacy)
        assertEquals(listOf(singleton), normalized.items)
        assertEquals(20L, normalized.nextId)
        assertEquals(normalized, Edits.normalizeFolders(normalized))
    }
    @Test fun lastOccurrenceTransfersAtomicallyAndRejectionsNeverCleanSources() {
        for (large in listOf(false, true)) {
            val source = Edits.add(HomeLayout(), FolderContent(listOf(key), large), 0, 0)
            val targetLayout = Edits.add(source, AppContent(key), 3, 4)
            val target = targetLayout.items.last()
            val entry = (source.items.single().content as FolderContent).entries.single()
            assertSame(targetLayout, Edits.mergeApps(targetLayout, target, key, sourceFolder = 1, sourceEntry = "stale"))
            val result = Edits.mergeApps(targetLayout, target, key, sourceFolder = 1, sourceEntry = entry.id)
            assertEquals(listOf(target.id), result.items.map { it.id })
            assertEquals(entry.id, (result.items.single().content as FolderContent).entries[1].id)
            assertEquals(targetLayout.nextId, result.nextId)
            val cleaned = Edits.reconcileFolderApps(source, setOf(key))
            assertTrue(cleaned.items.isEmpty())
            assertEquals(source.nextId, cleaned.nextId)
        }
    }
    @Test fun singletonRetainsIdentityModeNameAndHoles() {
        val source = Edits.add(HomeLayout(), FolderContent(listOf(key, key), true), 2, 6)
        val old = source.items.single()
        val folder = old.content as FolderContent
        val result = Edits.folder(source, old.id, folder.withoutEntry(folder.entries[0].id))
        assertEquals(old.copy(content = folder.withoutEntry(folder.entries[0].id)), result.items.single())
        assertEquals(1, (result.items.single().content as FolderContent).entries.single().slot)
    }
}
