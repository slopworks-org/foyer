package app.foyer.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LargeFolderSlotsTest {
    private val keys = (0..10).map { AppKey("pkg$it", "Main", if (it == 4) 10 else 0) }
    private fun folder(l: HomeLayout, id: Long = 1) = l.items.first { it.id == id }.content as FolderContent
    private fun base(count: Int = 9) = Edits.add(HomeLayout(), FolderContent(keys.take(count), large = true, name = "Old name"), 0, 0)

    @Test fun cleanupAndPersistenceRetainAbsolutePositions() {
        val initial = base()
        val removed = Edits.reconcileFolderApps(initial, setOf(keys[1], keys[7]))
        val profile = Edits.forgetProfiles(removed, setOf(10))
        val cleaned = Edits.forgetPackage(profile, keys[3].pkg, 0)
        val expected = keys.take(9).map { it.takeUnless { it in setOf(keys[1], keys[3], keys[4], keys[7]) } }
        assertEquals(expected, folder(cleaned).slots)
        val json = Json { classDiscriminator = "type" }
        val restored = Edits.normalizeFolders(json.decodeFromString<HomeLayout>(json.encodeToString(HomeLayout.serializer(), cleaned)))
        assertEquals(expected, folder(restored).slots)
        assertEquals("", folder(restored).name)
        val moved = Edits.transferApp(restored, keys[8], sourceFolder = 1, x = 4, y = 3)
        assertNull(folder(moved).slots[8])
        assertEquals(expected.take(8), folder(moved).slots.take(8))
    }

    @Test fun swapEmptyDropDefaultAndRejectionsAreAtomic() {
        val initial = base(8)
        val swap = Edits.transferApp(initial, keys[1], sourceFolder = 1, targetFolder = 1, index = 6)
        val expected = keys.take(8).toMutableList<AppKey?>().apply { add(null); this[1] = keys[6]; this[6] = keys[1] }
        assertEquals(expected, folder(swap).slots)
        val hole = Edits.transferApp(swap, keys[2], sourceFolder = 1, targetFolder = 1, index = 8)
        assertNull(folder(hole).slots[2])
        assertEquals(keys[2], folder(hole).slots[8])
        val external = Edits.add(hole, AppContent(keys[9]), 3, 0)
        assertSame(external, Edits.transferApp(external, keys[9], sourceItem = 2, targetFolder = 1, index = 0))
        val filled = Edits.transferApp(external, keys[9], sourceItem = 2, targetFolder = 1)
        assertEquals(keys[9], folder(filled).slots[2])
        assertEquals(1, filled.items.size)
        assertSame(filled, Edits.transferApp(filled, keys[10], targetFolder = 1))
        assertSame(filled, Edits.transferApp(filled, keys[0], targetFolder = 1))
        assertSame(hole, Edits.transferApp(hole, keys[10], targetFolder = 1, index = 9))
        assertSame(hole, Edits.transferApp(hole, keys[2], sourceFolder = 1, targetFolder = 1, index = 2, available = false))
    }

    @Test fun migrationAndConversionsKeepEveryAppInDefinedOrder() {
        val legacy = HomeLayout(items = listOf(HomeItem(1, FolderContent(keys.take(9), true, "Legacy"), 0, 0, 2, 2)))
        val migrated = Edits.normalizeFolders(legacy)
        assertEquals(keys.take(9), folder(migrated).slots)
        assertEquals("", folder(migrated).name)
        val sparse = Edits.reconcileFolderApps(migrated, setOf(keys[1], keys[5]))
        val small = Edits.folder(sparse, 1, folder(sparse).copy(large = false))
        assertEquals(keys.take(9) - setOf(keys[1], keys[5]), folder(small).apps)
        assertTrue(folder(small).slots.isEmpty())
        val named = Edits.folder(small, 1, folder(small).copy(name = "Small only"))
        val large = Edits.folder(named, 1, folder(named).copy(large = true))
        assertEquals(folder(small).apps + listOf(null, null), folder(large).slots)
        assertEquals("", folder(large).name)
        val overflow = Edits.normalizeFolders(legacy.copy(items = legacy.items.map { it.copy(content = FolderContent(keys, true)) }))
        assertFalse(folder(overflow).large)
        assertEquals(keys, folder(overflow).apps)
    }

    @Test fun malformedSlotsAndCrossFolderTransfersKeepMembershipConsistent() {
        val malformed = FolderContent(keys.take(4), true, slots = listOf(keys[2], keys[2], keys[10], null, keys[0])).normalized()
        assertEquals(listOf(keys[2], keys[1], keys[3], null, keys[0], null, null, null, null), malformed.slots)
        assertEquals(malformed.apps.size, malformed.apps.distinct().size)
        val initial = Edits.add(base(6), FolderContent(keys.takeLast(2)), 3, 0)
        val moved = Edits.transferApp(initial, keys[2], sourceFolder = 1, targetFolder = 2, index = 1)
        assertNull(folder(moved).slots[2])
        assertEquals(listOf(keys[9], keys[2], keys[10]), folder(moved, 2).apps)
        val back = Edits.transferApp(moved, keys[2], sourceFolder = 2, targetFolder = 1, index = 8)
        assertNull(folder(back).slots[2])
        assertEquals(keys[2], folder(back).slots[8])
        val blocked = Edits.add(Edits.folder(initial, 1, folder(initial).copy(large = false)), AppContent(keys[8]), 1, 0)
        assertSame(blocked, Edits.folder(blocked, 1, folder(blocked).copy(large = true)))
    }
}
