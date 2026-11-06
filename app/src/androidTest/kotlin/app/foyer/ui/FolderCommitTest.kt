package app.foyer.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import app.foyer.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FolderCommitTest {
    @Test fun duplicateSurvivorSurvivesLaterEditsAndNeverReplaysAfterLoad() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "folder-commit-test").apply { deleteRecursively(); mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
        }
        val job = SupervisorJob()
        val scope = CoroutineScope(job)
        try {
            val key = AppKey("sample", "Main", 0)
            val folder = FolderContent(entries = listOf(FolderEntry(key, "a"), FolderEntry(key, "b")))
            val store = LayoutStore(isolated, scope)
            store.edit { Edits.add(it, folder, 0, 0) }
            store.takePresentation()
            store.editChange { Edits.folderChange(it, 1, folder.withoutEntry("a")) }
            val collapsedSnapshot = store.layout.value
            store.edit { Edits.move(it, 1, 2, 3, dock = false) }
            assertNotSame(collapsedSnapshot, store.layout.value)
            val presentation = store.takePresentation()
            assertSame(store.layout.value, presentation.layout)
            assertEquals("b", presentation.replacements.single().survivor.id)
            assertEquals(2, presentation.layout.items.single().x)
            assertTrue(store.takePresentation().replacements.isEmpty())
            job.children.toList().forEach { it.join() }
            val restarted = LayoutStore(isolated, scope)
            assertEquals(store.layout.value, restarted.layout.value)
            assertTrue(restarted.takePresentation().replacements.isEmpty())
        } finally {
            job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
