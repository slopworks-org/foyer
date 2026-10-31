package app.foyer.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class HomeLayoutTest {
    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }
    private fun app(n: Int, user: Long = 0) = AppKey("pkg$n", "Activity", user)
    private fun layout(folder: FolderContent = FolderContent()) = Edits.add(HomeLayout(), folder, 0, 0)

    @Test fun restartReconciliationPreservesUnknownAndUsesProfileIdentity() {
        val absent = app(1)
        val otherProfile = app(1, 10)
        val unavailable = app(2)
        val failed = app(3)
        val installedWithoutActivity = app(4)
        val keys = listOf(absent, otherProfile, unavailable, failed, installedWithoutActivity)
        val missing = missingFolderApps(keys) {
            when (it) {
                absent -> PackagePresence.Missing
                unavailable -> PackagePresence.Unknown // locked, quiet, updating or unavailable
                failed -> error("Package API failed")
                else -> PackagePresence.Present // disabled apps still have package identity
            }
        }
        val result = Edits.reconcileFolderApps(layout(FolderContent(keys)), missing)
        assertEquals(keys - absent, (result.items.single().content as FolderContent).apps)
    }

    @Test fun oldLayoutDecodesWithoutFolders() {
        val old = """{"items":[{"id":7,"content":{"type":"app","app":{"pkg":"pkg1","cls":"Activity","user":0}},"x":2,"y":3}],"nextId":8}"""
        val decoded = json.decodeFromString<HomeLayout>(old)
        assertEquals(AppContent(app(1)), decoded.items.single().content)
        assertEquals(7L, decoded.items.single().id)
        assertEquals(8L, decoded.nextId)
    }

    @Test fun roundTripRetainsOrderNameModeAndCells() {
        val original = Edits.add(HomeLayout(), FolderContent(listOf(app(3), app(1, 10)), true, "Tools"), 2, 4)
        assertEquals(original, json.decodeFromString<HomeLayout>(json.encodeToString(HomeLayout.serializer(), original)))
        assertEquals(2, original.items.single().w)
        assertEquals(2, original.items.single().h)
    }

    @Test fun capacityAndDuplicateFailuresAreAtomic() {
        val small = layout(FolderContent((1..10).map { app(it) }))
        assertSame(small, Edits.folder(small, 1, (small.items.single().content as FolderContent).copy(large = true)))
        assertSame(small, Edits.folder(small, 1, FolderContent(listOf(app(1), app(1)))))
        assertTrue(Edits.add(HomeLayout(), FolderContent((1..10).map { app(it) }, true), 0, 0).items.isEmpty())
    }

    @Test fun expansionDoesNotMoveOrOverwriteNeighbors() {
        val occupied = Edits.add(layout(), AppContent(app(1)), 1, 1)
        assertSame(occupied, Edits.folder(occupied, 1, FolderContent(large = true)))
        val free = Edits.remove(occupied, 2)
        val expanded = Edits.folder(free, 1, FolderContent(large = true))
        assertEquals(free.items.single().id, expanded.items.single().id)
        assertEquals(2, expanded.items.single().w)
        assertSame(expanded, Edits.move(expanded, 1, 4, 0, false))
        val edge = Edits.add(HomeLayout(), FolderContent(), 4, 0)
        assertSame(edge, Edits.folder(edge, 1, FolderContent(large = true)))
    }

    @Test fun membershipOrderingAndEmptyTitleAreExplicit() {
        val start = layout(FolderContent(listOf(app(1), app(2)), name = "Tools"))
        val edited = Edits.folder(start, 1, FolderContent(listOf(app(2), app(3), app(1)), name = ""))
        assertEquals(FolderContent(listOf(app(2), app(3), app(1))), edited.items.single().content)
        val empty = Edits.folder(edited, 1, FolderContent())
        assertEquals(1, empty.items.size)
        assertEquals(FolderContent(), empty.items.single().content)
    }

    @Test fun foldersCannotEnterDockThroughAnyEdit() {
        val start = layout()
        assertSame(start, Edits.move(start, 1, 0, 0, true))
        assertSame(start, Edits.add(start, FolderContent(), 1, 0, dock = true))
        val dock = Edits.add(start, AppContent(app(1)), 2, 0, dock = true)
        assertSame(dock, Edits.swap(dock, dock.items[0], dock.items[1]))
        assertSame(start, Edits.resize(start, 1, 0, 0, 2, 2))
        val large = layout(FolderContent(large = true))
        assertSame(large, Edits.move(large, 1, 0, 0, true))
        assertSame(large, Edits.add(large, FolderContent(large = true), 2, 0, dock = true))
        val withDock = Edits.add(large, AppContent(app(1)), 2, 0, dock = true)
        assertSame(withDock, Edits.swap(withDock, withDock.items[0], withDock.items[1]))
    }

    @Test fun uninstallAndProfileCleanupPreserveFolderAndOtherProfiles() {
        val start = layout(FolderContent(listOf(app(1), app(1, 10), app(2)), name = "Tools"))
        val cleaned = Edits.forgetPackage(start, "pkg1", 0)
        assertEquals(listOf(app(1, 10), app(2)), (cleaned.items.single().content as FolderContent).apps)
        val profile = Edits.forgetProfiles(cleaned, setOf(10))
        assertEquals(listOf(app(2)), (profile.items.single().content as FolderContent).apps)
        assertEquals(start.items.single().id, profile.items.single().id)
        val empty = Edits.forgetPackage(profile, "pkg2", 0)
        assertTrue((empty.items.single().content as FolderContent).apps.isEmpty())
        assertEquals("Tools", (empty.items.single().content as FolderContent).name)
    }
}
