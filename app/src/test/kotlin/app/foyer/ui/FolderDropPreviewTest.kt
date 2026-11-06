package app.foyer.ui

import androidx.compose.ui.geometry.Rect
import app.foyer.data.*
import org.junit.Assert.*
import org.junit.Test

class FolderDropPreviewTest {
    private val app = AppKey("pkg", "Main", 0)

    @Test fun previewMatchesCommittedOccurrenceAcrossEveryInsertionBoundary() {
        for (count in 2..12) for (source in -1 until count) for (index in 0..count + 1) {
            val entries = (0 until count).map { FolderEntry(app, "entry-$it") }
            val before = Edits.add(HomeLayout(), FolderContent(entries = entries), 0, 0)
            val member = entries.getOrNull(source)
            val after = Edits.transferApp(before, app, sourceFolder = member?.let { 1 },
                sourceEntry = member?.id, targetFolder = 1, index = index)
            val folder = after.items.first().content as FolderContent
            val incoming = member?.id ?: folder.entries.single { it.id !in entries.map { e -> e.id } }.id
            val preview = folderDropPreview(1, folder, incoming)!!
            assertEquals(incoming, folder.entries[preview.slot].id)
            assertEquals(folder.entries.size, preview.panelOrder.size)
            assertNull(preview.panelOrder[preview.slot])
            assertEquals(folder.entries.filterNot { it.id == incoming }, preview.panelOrder.filterNotNull())
        }
    }

    @Test fun sparseSlotsAndSwapsKeepAbsoluteDestinationsAndRejectOccupiedExternalDrops() {
        val entries = listOf(FolderEntry(app, "first", 0), FolderEntry(app, "second", 7))
        val before = Edits.add(HomeLayout(), FolderContent(entries = entries, large = true), 0, 0)
        for (slot in 0..8) {
            val surface = Rect(13f, 29f, 223f, 239f)
            val cell = largeFolderCell(surface, slot, 16f)
            assertEquals(slot, largeFolderSlot(cell.center, surface, 16f))
            assertEquals(cell.center, folderMemberStart(surface, true, 2, slot, 48f, 16f).center)
            val moved = Edits.transferApp(before, app, sourceFolder = 1, sourceEntry = "first", targetFolder = 1, index = slot)
            val preview = folderDropPreview(1, moved.items.first().content as FolderContent, "first")!!
            assertEquals(slot, preview.slot)
            val inserted = Edits.transferApp(before, app, targetFolder = 1, index = slot)
            if (slot == 0 || slot == 7) assertSame(before, inserted)
            else {
                val folder = inserted.items.first().content as FolderContent
                val incoming = folder.entries.single { it.id !in entries.map { e -> e.id } }
                assertEquals(slot, folderDropPreview(1, folder, incoming.id)!!.slot)
            }
        }
        assertNull(folderDropPreview(1, FolderContent(entries = entries, large = true), "missing"))
    }
}
