package app.foyer.data

import org.junit.Assert.*
import org.junit.Test

class EmptyFoldersTest {
    private val key = AppKey("pkg", "Main", 0)
    private val other = AppKey("other", "Main", 10)

    @Test fun normalizeKeepsEmptyMetadataAndCollapsesSingletonWithoutCompactionOrIdReuse() {
        val empty = HomeItem(2, FolderContent(large = true, name = "Keep"), 0, 0, 2, 2)
        val singleton = HomeItem(9, FolderContent(listOf(key), large = true), 2, 8, 2, 2)
        val neighbor = HomeItem(10, AppContent(other), 4, 8)
        val legacy = HomeLayout(items = listOf(empty, singleton, neighbor), nextId = 20, seeded = true, aliases = mapOf("x" to listOf("y")))
        val normalized = Edits.normalizeFolders(legacy)
        assertEquals(listOf(empty, singleton.copy(content = AppContent(key), w = 1, h = 1), neighbor), normalized.items)
        assertEquals(legacy.copy(items = normalized.items), normalized)
        assertEquals(normalized, Edits.normalizeFolders(normalized))
        assertEquals(51L, Edits.normalizeFolders(HomeLayout(items = listOf(empty.copy(id = 50)))).nextId)
    }

    @Test fun emptyFolderModeRoundTripClearsTheName() {
        val initial = Edits.add(HomeLayout(), FolderContent(name = "Tools"), 0, 0)
        val enlarged = Edits.folder(initial, 1, (initial.items.single().content as FolderContent).copy(large = true))
        val large = enlarged.items.single().content as FolderContent
        assertTrue(large.entries.isEmpty())
        assertEquals("", large.name)
        val small = Edits.folder(enlarged, 1, large.copy(large = false)).items.single()
        assertEquals("", (small.content as FolderContent).name)
        assertEquals(1, small.w)
    }

    @Test fun zeroToOneAndDuplicateTwoToOneUseStoredTopLeft() {
        for (large in listOf(false, true)) {
            val empty = Edits.add(HomeLayout(), FolderContent(large = large, name = "Keep"), 2, 5)
            val filled = Edits.transferApp(empty, key, targetFolder = 1, index = if (large) 8 else 0)
            assertEquals(HomeItem(1, AppContent(key), 2, 5), filled.items.single())
            val entries = listOf(FolderEntry(key, "a", 0), FolderEntry(key, "b", 8))
            val source = Edits.add(HomeLayout(), FolderContent(entries = entries, large = large), 2, 5)
            val folder = source.items.single().content as FolderContent
            val result = Edits.folder(source, 1, folder.withoutEntry("a"))
            assertEquals(HomeItem(1, AppContent(key), 2, 5), result.items.single())
            assertEquals("b", result.items.single().folderSurvivor?.id)
            assertEquals(source.nextId, result.nextId)
        }
    }

    @Test fun successfulTransfersCollapseBothSidesButFailedTransfersAreAtomic() {
        val source = Edits.add(HomeLayout(), FolderContent(listOf(key, key), true), 0, 0)
        val both = Edits.add(source, FolderContent(large = true), 3, 4)
        val entry = (source.items.single().content as FolderContent).entries.first()
        assertSame(both, Edits.transferApp(both, key, sourceFolder = 1, sourceEntry = "stale", targetFolder = 2))
        assertSame(both, Edits.transferApp(both, key, sourceFolder = 1, sourceEntry = entry.id, targetFolder = 2, available = false))
        val result = Edits.transferApp(both, key, sourceFolder = 1, sourceEntry = entry.id, targetFolder = 2)
        assertEquals(listOf(HomeItem(1, AppContent(key), 0, 0), HomeItem(2, AppContent(key), 3, 4)), result.items)
        val moved = Edits.transferApp(source, key, sourceFolder = 1, sourceEntry = entry.id, x = 4, y = 3)
        assertEquals(AppContent(key), moved.items.first().content)
        assertEquals(AppContent(key), moved.items.last().content)
        val target = Edits.add(source, AppContent(other), 4, 3)
        val merged = Edits.mergeApps(target, target.items.last(), key, sourceFolder = 1, sourceEntry = entry.id)
        assertEquals(AppContent(key), merged.items.first().content)
        assertEquals(listOf(other, key), (merged.items.last().content as FolderContent).apps)
    }

    @Test fun confirmedCleanupRetainsTrueEmptyFoldersAndCollapsesSurvivors() {
        for (large in listOf(false, true)) {
            val initial = Edits.add(HomeLayout(), FolderContent(listOf(key, other), large), 0, 0)
            for (clean in listOf<(HomeLayout) -> HomeLayout>(
                { Edits.reconcileFolderApps(it, setOf(key)) },
                { Edits.forgetPackage(it, key.pkg, key.user) },
                { Edits.forgetProfiles(it, setOf(key.user)) },
            )) assertEquals(HomeItem(1, AppContent(other), 0, 0), clean(initial).items.single())
            val duplicate = Edits.add(HomeLayout(), FolderContent(listOf(key, key), large), 0, 0)
            val emptied = Edits.reconcileFolderApps(duplicate, setOf(key))
            assertEquals(duplicate.items.single().copy(content = (duplicate.items.single().content as FolderContent).copy(entries = emptyList())), emptied.items.single())
            assertTrue(Edits.remove(emptied, 1).items.isEmpty())
        }
    }
}
