package app.foyer.data

import org.junit.Assert.*
import org.junit.Test

class EmptyFoldersTest {
    private val key = AppKey("pkg", "Main", 0)
    private val other = AppKey("other", "Main", 10)

    @Test fun normalizeRetainsLargeZeroAndOneWithoutCompactionOrIdReuse() {
        val empty = HomeItem(2, FolderContent(large = true, name = "Keep"), 0, 0, 2, 2)
        val singleton = HomeItem(9, FolderContent(listOf(key), large = true), 2, 8, 2, 2)
        val neighbor = HomeItem(10, AppContent(other), 4, 8)
        val legacy = HomeLayout(items = listOf(empty, singleton, neighbor), nextId = 20, seeded = true, aliases = mapOf("x" to listOf("y")))
        val normalized = Edits.normalizeFolders(legacy)
        assertEquals(listOf(empty.copy(content = (empty.content as FolderContent).normalized()), singleton.copy(content = (singleton.content as FolderContent).normalized()), neighbor), normalized.items)
        assertEquals(legacy.copy(items = normalized.items), normalized)
        assertEquals(normalized, Edits.normalizeFolders(normalized))
        assertEquals(51L, Edits.normalizeFolders(HomeLayout(items = listOf(empty.copy(id = 50)))).nextId)
    }

    @Test fun emptySmallFolderEnlargesButCannotShrinkAgain() {
        val initial = Edits.add(HomeLayout(), FolderContent(name = "Tools"), 0, 0)
        val enlarged = Edits.folder(initial, 1, (initial.items.single().content as FolderContent).copy(large = true))
        val large = enlarged.items.single().content as FolderContent
        assertTrue(large.entries.isEmpty())
        assertEquals("", large.name)
        assertSame(enlarged, Edits.folder(enlarged, 1, large.copy(large = false)))
    }

    @Test fun zeroToOneAndDuplicateTwoToOneUseStoredTopLeft() {
        for (large in listOf(false, true)) {
            val empty = Edits.add(HomeLayout(), FolderContent(large = large, name = "Keep"), 2, 5)
            val filled = Edits.transferApp(empty, key, targetFolder = 1, index = if (large) 8 else 0)
            if (large) {
                val retained = filled.items.single()
                assertEquals(2, retained.w)
                assertEquals(8, (retained.content as FolderContent).entries.single().slot)
                assertNull(retained.folderSurvivor)
            } else assertEquals(HomeItem(1, AppContent(key), 2, 5), filled.items.single())
            val entries = listOf(FolderEntry(key, "a", 0), FolderEntry(key, "b", 8))
            val source = Edits.add(HomeLayout(), FolderContent(entries = entries, large = large), 2, 5)
            val folder = source.items.single().content as FolderContent
            val result = Edits.folder(source, 1, folder.withoutEntry("a"))
            if (large) {
                val retained = result.items.single()
                assertEquals(source.items.single().copy(content = folder.withoutEntry("a")), retained)
                assertNull(retained.folderSurvivor)
                assertSame(result, Edits.folder(result, 1, (retained.content as FolderContent).copy(large = false)))
                val emptyAgain = Edits.folder(result, 1, retained.content.withoutEntry("b"))
                assertEquals(empty.items.single(), emptyAgain.items.single())
            } else {
                assertEquals(HomeItem(1, AppContent(key), 2, 5), result.items.single())
                assertEquals("b", result.items.single().folderSurvivor?.id)
            }
            assertEquals(source.nextId, result.nextId)
        }
    }

    @Test fun successfulLargeTransfersRetainBothSidesAndFailedTransfersAreAtomic() {
        val source = Edits.add(HomeLayout(), FolderContent(listOf(key, key), true), 0, 0)
        val both = Edits.add(source, FolderContent(large = true), 3, 4)
        val entry = (source.items.single().content as FolderContent).entries.first()
        assertSame(both, Edits.transferApp(both, key, sourceFolder = 1, sourceEntry = "stale", targetFolder = 2))
        assertSame(both, Edits.transferApp(both, key, sourceFolder = 1, sourceEntry = entry.id, targetFolder = 2, available = false))
        val result = Edits.transferApp(both, key, sourceFolder = 1, sourceEntry = entry.id, targetFolder = 2)
        assertTrue(result.items.all { it.content is FolderContent && it.w == 2 && it.h == 2 })
        assertEquals(entry, (result.items.last().content as FolderContent).entries.single())
        assertNull(result.items.first().folderSurvivor)
        val moved = Edits.transferApp(source, key, sourceFolder = 1, sourceEntry = entry.id, x = 4, y = 3)
        assertEquals(1, (moved.items.first().content as FolderContent).entries.size)
        assertEquals(AppContent(key), moved.items.last().content)
        val target = Edits.add(source, AppContent(other), 4, 3)
        val merged = Edits.mergeApps(target, target.items.last(), key, sourceFolder = 1, sourceEntry = entry.id)
        assertEquals(1, (merged.items.first().content as FolderContent).entries.size)
        assertEquals(listOf(other, key), (merged.items.last().content as FolderContent).apps)
    }

    @Test fun singletonLargeReorderAndCrossFolderTransferKeepOccurrenceIdentity() {
        val entry = FolderEntry(key, "only", 8)
        val initial = Edits.add(HomeLayout(), FolderContent(entries = listOf(entry), large = true), 0, 0)
        val reordered = Edits.transferApp(initial, key, sourceFolder = 1, sourceEntry = entry.id, targetFolder = 1, index = 3)
        assertEquals(entry.copy(slot = 3), (reordered.items.single().content as FolderContent).entries.single())
        val both = Edits.add(reordered, FolderContent(entries = listOf(FolderEntry(key, "target", 7)), large = true), 3, 4)
        val result = Edits.transferApp(both, key, sourceFolder = 1, sourceEntry = entry.id, targetFolder = 2, index = 8)
        assertTrue((result.items.first().content as FolderContent).entries.isEmpty())
        val target = result.items.last().content as FolderContent
        assertEquals(listOf("target", "only"), target.entries.map { it.id })
        assertEquals(listOf(7, 8), target.entries.map { it.slot })
        val swapped = Edits.transferApp(result, key, sourceFolder = 2, sourceEntry = entry.id, targetFolder = 2, index = 7)
        assertEquals(listOf("only", "target"), (swapped.items.last().content as FolderContent).entries.map { it.id })
        assertEquals(result.nextId, swapped.nextId)
        assertTrue(swapped.items.all { it.folderSurvivor == null && it.w == 2 && it.h == 2 })
    }

    @Test fun confirmedCleanupRetainsLargeFoldersAndCollapsesSmallSurvivors() {
        for (large in listOf(false, true)) {
            val initial = Edits.add(HomeLayout(), FolderContent(listOf(key, other), large), 0, 0)
            for (clean in listOf<(HomeLayout) -> HomeLayout>(
                { Edits.reconcileFolderApps(it, setOf(key)) },
                { Edits.forgetPackage(it, key.pkg, key.user) },
                { Edits.forgetProfiles(it, setOf(key.user)) },
            )) {
                val result = clean(initial).items.single()
                if (large) {
                    assertEquals(initial.items.single().copy(content = (initial.items.single().content as FolderContent).without(setOf(key))), result)
                    assertNull(result.folderSurvivor)
                } else assertEquals(HomeItem(1, AppContent(other), 0, 0), result)
            }
            val duplicate = Edits.add(HomeLayout(), FolderContent(listOf(key, key), large), 0, 0)
            val emptied = Edits.reconcileFolderApps(duplicate, setOf(key))
            assertEquals(duplicate.items.single().copy(content = (duplicate.items.single().content as FolderContent).copy(entries = emptyList())), emptied.items.single())
            assertTrue(Edits.remove(emptied, 1).items.isEmpty())
        }
    }
}
