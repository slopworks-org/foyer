package app.foyer.data

import org.junit.Assert.*
import org.junit.Test

class FolderDragTest {
    private fun key(n: Int, user: Long = 0) = AppKey("pkg$n", "Main", user)
    private fun base(large: Boolean = false, apps: List<AppKey> = emptyList()) =
        Edits.add(HomeLayout(), FolderContent(apps, large), 0, 0)
    private fun members(l: HomeLayout, id: Long = 1) = (l.items.first { it.id == id }.content as FolderContent).apps

    @Test fun desktopMovesSearchAddsAndProfilesRemainDistinct() {
        val initial = Edits.add(base(), AppContent(key(1)), 3, 0)
        val moved = Edits.transferApp(initial, key(1), sourceItem = 2, targetFolder = 1)
        assertEquals(listOf(key(1)), members(moved))
        assertEquals(listOf(1L), moved.items.map { it.id })
        val added = Edits.transferApp(moved, key(1, 10), targetFolder = 1)
        assertEquals(listOf(key(1), key(1, 10)), members(added))
        assertEquals(initial.nextId, added.nextId)
    }

    @Test fun reorderAndTransferBetweenFolders() {
        val initial = Edits.add(base(apps = listOf(key(1), key(2), key(3))), FolderContent(), 3, 0)
        val ordered = Edits.transferApp(initial, key(3), sourceFolder = 1, targetFolder = 1, index = 0)
        assertEquals(listOf(key(3), key(1), key(2)), members(ordered))
        val downward = Edits.transferApp(initial, key(1), sourceFolder = 1, targetFolder = 1, index = 2)
        assertEquals(listOf(key(2), key(1), key(3)), members(downward))
        val appended = Edits.transferApp(initial, key(1), sourceFolder = 1, targetFolder = 1)
        assertEquals(listOf(key(2), key(3), key(1)), members(appended))
        val moved = Edits.transferApp(ordered, key(1), sourceFolder = 1, targetFolder = 2)
        assertEquals(listOf(key(3), key(2)), members(moved))
        assertEquals(listOf(key(1)), members(moved, 2))
        assertEquals(initial.items.map { Triple(it.id, it.x, it.y) }, moved.items.map { Triple(it.id, it.x, it.y) })
    }

    @Test fun memberMovesOutToDesktopOrDockOnlyWhenFree() {
        val initial = base(apps = listOf(key(1)))
        assertSame(initial, Edits.transferApp(initial, key(1), sourceFolder = 1, x = 0, y = 0))
        for (dock in listOf(false, true)) {
            val moved = Edits.transferApp(initial, key(1), sourceFolder = 1, x = 3, y = 0, dock = dock)
            assertTrue(members(moved).isEmpty())
            assertEquals(HomeItem(2, AppContent(key(1)), 3, 0, dock = dock), moved.items.last())
        }
    }

    @Test fun fullDuplicateMissingAndCanceledDropsKeepSources() {
        val full = Edits.add(base(true, (1..9).map(::key)), AppContent(key(10)), 3, 0)
        assertSame(full, Edits.transferApp(full, key(10), sourceItem = 2, targetFolder = 1))
        val duplicate = Edits.add(base(apps = listOf(key(1))), AppContent(key(1)), 3, 0)
        assertSame(duplicate, Edits.transferApp(duplicate, key(1), sourceItem = 2, targetFolder = 1))
        assertSame(duplicate, Edits.transferApp(duplicate, key(1), targetFolder = 1, available = false))
        assertSame(duplicate, Edits.transferApp(duplicate, key(2), sourceFolder = 1, x = 4))
        // Cancel has no mutation: transfer is only invoked on a valid completed drop.
        assertEquals(listOf(key(1)), members(duplicate))
    }

    @Test fun uninstallAndStaleSourcesCannotResurrectMembers() {
        val initial = Edits.add(base(apps = listOf(key(1))), AppContent(key(2)), 3, 0)
        val cleaned = Edits.forgetPackage(initial, "pkg1", 0)
        assertSame(cleaned, Edits.transferApp(cleaned, key(1), sourceFolder = 1, x = 4))
        val removed = Edits.forgetPackage(initial, "pkg2", 0)
        assertSame(removed, Edits.transferApp(removed, key(2), sourceItem = 2, targetFolder = 1))
        assertSame(initial, Edits.transferApp(initial, key(1), sourceFolder = 1, x = 4, available = false))
        val profile = base(apps = listOf(key(1, 10)))
        val forgotten = Edits.forgetProfiles(profile, setOf(10))
        assertSame(forgotten, Edits.transferApp(forgotten, key(1, 10), sourceFolder = 1, x = 4))
    }
}
