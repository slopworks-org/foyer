package app.foyer.data

import org.junit.Assert.*
import org.junit.Test

class FolderDragTest {
    private fun key(n: Int, user: Long = 0) = AppKey("pkg$n", "Main", user)
    private fun base(large: Boolean = false, apps: List<AppKey> = listOf(key(0), key(99))) =
        Edits.add(HomeLayout(), FolderContent(apps, large), 0, 0)
    private fun members(l: HomeLayout, id: Long = 1) = (l.items.first { it.id == id }.content as FolderContent).apps

    @Test fun rejectedSourceEditRejectsTheWholeTransferAndMerge() {
        val initial = Edits.add(base(), AppContent(key(8)), 3, 0)
        val overlapping = initial.copy(items = initial.items + HomeItem(99, AppContent(key(9)), 0, 0))
        val entry = (overlapping.items.first().content as FolderContent).entries.first()
        assertNull(Edits.planTransferApp(overlapping, entry.app, sourceFolder = 1, sourceEntry = entry.id, x = 4, y = 2))
        assertNull(Edits.mergeChange(overlapping, initial.items.last(), entry.app, sourceFolder = 1, sourceEntry = entry.id))
        assertEquals(initial.items.first(), overlapping.items.first())
    }

    @Test fun rejectedDestinationNeverConsumesTheSource() {
        val valid = Edits.add(base(), AppContent(key(5)), 3, 0)
        val overlapping = valid.copy(items = valid.items + HomeItem(99, AppContent(key(9)), 0, 0))
        assertNull(Edits.planTransferApp(overlapping, key(5), sourceItem = 2, targetFolder = 1))
        assertEquals(valid.items.last(), overlapping.items.find { it.id == 2L })
        assertSame(overlapping, Edits.transferApp(overlapping, key(5), sourceItem = 2, targetFolder = 1))
    }

    @Test fun plannedIncomingIdentitySurvivesReplanningAgainstNewLayout() {
        val initial = base()
        val preview = Edits.planTransferApp(initial, key(5), targetFolder = 1, incomingId = "incoming")!!
        val current = Edits.add(initial, AppContent(key(8)), 3, 2)
        val commit = Edits.planTransferApp(current, key(5), targetFolder = 1, incomingId = "incoming")!!
        assertEquals(preview.incomingId, commit.incomingId)
        assertEquals("incoming", (commit.layout.items.first().content as FolderContent).entries.last().id)
        assertEquals(current.items.last(), commit.layout.items.last())
        assertEquals(1L, commit.destination)
    }

    @Test fun ownLargeSlotIsAcceptedWithoutChangingOccurrences() {
        val initial = base(large = true)
        val entry = (initial.items.first().content as FolderContent).entries.first()
        val result = Edits.planTransferApp(initial, entry.app, sourceFolder = 1,
            sourceEntry = entry.id, targetFolder = 1, index = entry.slot!!)!!
        assertEquals(initial, result.layout)
        assertEquals(entry.id, result.incomingId)
    }

    @Test fun fifthExternalAdditionAndModeChanges() {
        val four = (1..4).map(::key)
        val expected = listOf(key(1), key(2), key(5), key(3), key(4))
        for (index in listOf(4, Int.MAX_VALUE)) {
            val search = Edits.transferApp(base(apps = four), key(5), targetFolder = 1, index = index)
            assertEquals(expected, members(search))
            val desktop = Edits.add(base(apps = four), AppContent(key(5)), 3, 0)
            assertEquals(expected, members(Edits.transferApp(desktop, key(5), sourceItem = 2, targetFolder = 1, index = index)))
            val other = Edits.add(base(apps = four), FolderContent(listOf(key(5), key(99))), 3, 0)
            val moved = Edits.transferApp(other, key(5), sourceFolder = 2, targetFolder = 1, index = index)
            assertEquals(expected, members(moved))
            assertEquals(AppContent(key(99)), moved.items.first { it.id == 2L }.content)
        }
        val explicit = Edits.transferApp(base(apps = four), key(5), targetFolder = 1, index = 1)
        assertEquals(listOf(key(1), key(5), key(2), key(3), key(4)), members(explicit))
        val large = Edits.transferApp(base(true, four), key(5), targetFolder = 1)
        assertEquals(four + key(5), members(large))
        val shrunk = Edits.folder(large, 1, (large.items.first().content as FolderContent).copy(large = false))
        assertEquals(members(large), members(shrunk))
        val enlarged = Edits.folder(shrunk, 1, (shrunk.items.first().content as FolderContent).copy(large = true))
        assertEquals(members(large), members(enlarged))
        val six = Edits.transferApp(explicit, key(6), targetFolder = 1)
        assertEquals(members(explicit) + key(6), members(six))
    }

    @Test fun desktopMovesSearchAddsAndProfilesRemainDistinct() {
        val initial = Edits.add(base(), AppContent(key(1)), 3, 0)
        val moved = Edits.transferApp(initial, key(1), sourceItem = 2, targetFolder = 1)
        assertEquals(listOf(key(0), key(99), key(1)), members(moved))
        assertEquals(listOf(1L), moved.items.map { it.id })
        val added = Edits.transferApp(moved, key(1, 10), targetFolder = 1)
        assertEquals(listOf(key(0), key(99), key(1), key(1, 10)), members(added))
        assertEquals(initial.nextId, added.nextId)
    }

    @Test fun reorderAndTransferBetweenFolders() {
        val initial = Edits.add(base(apps = listOf(key(1), key(2), key(3))), FolderContent(listOf(key(0), key(99))), 3, 0)
        val ordered = Edits.transferApp(initial, key(3), sourceFolder = 1, targetFolder = 1, index = 0)
        assertEquals(listOf(key(3), key(1), key(2)), members(ordered))
        val downward = Edits.transferApp(initial, key(1), sourceFolder = 1, targetFolder = 1, index = 2)
        assertEquals(listOf(key(2), key(1), key(3)), members(downward))
        val appended = Edits.transferApp(initial, key(1), sourceFolder = 1, targetFolder = 1)
        assertEquals(listOf(key(2), key(3), key(1)), members(appended))
        val moved = Edits.transferApp(ordered, key(1), sourceFolder = 1, targetFolder = 2)
        assertEquals(listOf(key(3), key(2)), members(moved))
        assertEquals(listOf(key(0), key(99), key(1)), members(moved, 2))
        assertEquals(initial.items.map { Triple(it.id, it.x, it.y) }, moved.items.map { Triple(it.id, it.x, it.y) })
    }

    @Test fun memberMovesOutToDesktopOrDockOnlyWhenFree() {
        val initial = base(apps = listOf(key(1), key(99)))
        assertSame(initial, Edits.transferApp(initial, key(1), sourceFolder = 1, x = 0, y = 0))
        for (dock in listOf(false, true)) {
            val moved = Edits.transferApp(initial, key(1), sourceFolder = 1, x = 3, y = 0, dock = dock)
            assertEquals(AppContent(key(99)), moved.items.first().content)
            assertEquals(HomeItem(2, AppContent(key(1)), 3, 0, dock = dock), moved.items.last())
        }
    }

    @Test fun fullDuplicateMissingAndCanceledDropsKeepSources() {
        val full = Edits.add(base(true, (1..9).map(::key)), AppContent(key(10)), 3, 0)
        assertSame(full, Edits.transferApp(full, key(10), sourceItem = 2, targetFolder = 1))
        val duplicate = Edits.add(base(apps = listOf(key(1), key(99))), AppContent(key(1)), 3, 0)
        assertEquals(listOf(key(1), key(99), key(1)), members(Edits.transferApp(duplicate, key(1), sourceItem = 2, targetFolder = 1)))
        assertSame(duplicate, Edits.transferApp(duplicate, key(1), targetFolder = 1, available = false))
        assertSame(duplicate, Edits.transferApp(duplicate, key(2), sourceFolder = 1, x = 4))
        // Cancel has no mutation: transfer is only invoked on a valid completed drop.
        assertEquals(listOf(key(1), key(99)), members(duplicate))
    }

    @Test fun uninstallAndStaleSourcesCannotResurrectMembers() {
        val initial = Edits.add(base(apps = listOf(key(1), key(99))), AppContent(key(2)), 3, 0)
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
