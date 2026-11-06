package app.foyer.ui

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.foyer.MainActivity
import app.foyer.data.*
import app.foyer.graph
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run stage and verifyAndRestore separately with an actual process restart between them. */
class FolderEntriesRestartTest {
    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue(condition())
    }

    /** Opening AtomicFile while a writer owns .new can discard that pending write. */
    private fun awaitPersisted(activity: MainActivity, expected: HomeLayout) {
        val file = File(activity.filesDir, "home.json")
        waitFor {
            !File(file.path + ".new").exists() && !File(file.path + ".bak").exists() && runCatching {
                json.decodeFromString<HomeLayout>(file.readText()) == expected
            }.getOrDefault(false)
        }
        assertEquals(expected, LayoutStore(activity, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)).layout.value)
    }

    @Test fun stage() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("restartPhase") == "stage")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            val graph = activity.graph
            val directory = File(activity.getExternalFilesDir(null), "folder-entries").apply { mkdirs() }
            val backup = File(directory, "restart-original.json")
            check(!backup.exists() || json.decodeFromString<HomeLayout>(backup.readText()) == graph.layout.layout.value) {
                "Finish the previous restart verification before staging another fixture"
            }
            backup.writeText(json.encodeToString(HomeLayout.serializer(), graph.layout.layout.value))
            waitFor { graph.apps.apps.value.isNotEmpty() }
            val key = json.encodeToString(AppKey.serializer(), graph.apps.apps.value.first().key)
            // Exercise the same legacy decoder without writing home.json directly.
            val old = """{"seeded":true,"nextId":2,"items":[{"id":1,"x":1,"y":1,"w":2,"h":2,"content":{"type":"folder","large":true,"apps":[$key,$key],"slots":[$key,null,null,null,null,null,null,null,$key]}},{"id":96,"x":0,"y":12,"w":2,"h":2,"content":{"type":"folder","large":true,"apps":[]}},{"id":97,"x":2,"y":12,"w":2,"h":2,"content":{"type":"folder","large":true,"apps":[$key],"slots":[null,null,null,null,null,null,null,null,$key]}},{"id":98,"x":3,"y":10,"content":{"type":"folder","apps":[$key]}},{"id":99,"x":4,"y":10,"content":{"type":"folder","apps":[]}}]}"""
            val migrated = Edits.normalizeFolders(json.decodeFromString<HomeLayout>(old))
            graph.layout.edit { migrated }
            awaitPersisted(activity, migrated)
            File(directory, "restart-expected.json").writeText(json.encodeToString(HomeLayout.serializer(), migrated))
        }
    }

    @Test fun verifyAndRestore() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("restartPhase") == "verify")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            val directory = File(activity.getExternalFilesDir(null), "folder-entries")
            val original = json.decodeFromString<HomeLayout>(File(directory, "restart-original.json").readText())
            try {
                val expected = json.decodeFromString<HomeLayout>(File(directory, "restart-expected.json").readText())
                assertEquals(expected, activity.graph.layout.layout.value)
                val folder = expected.items.first { it.id == 1L }.content as FolderContent
                assertTrue((expected.items.first { it.id == 99L }.content as FolderContent).entries.isEmpty())
                assertEquals(listOf(0, 8), folder.entries.map { it.slot })
                assertTrue(expected.items.first { it.id == 98L }.content is AppContent)
                val largeOne = expected.items.first { it.id == 97L }
                assertEquals(2, largeOne.w); assertEquals(2, largeOne.h)
                assertEquals(8, (largeOne.content as FolderContent).entries.single().slot)
                assertTrue((expected.items.first { it.id == 96L }.content as FolderContent).large)
                assertTrue((expected.items.first { it.id == 96L }.content as FolderContent).entries.isEmpty())
                assertEquals(100L, expected.nextId)
                assertEquals(2, folder.entries.map { it.id }.distinct().size)
                File(directory, "restart-verified.txt").writeText("Process restart retained migrated duplicate IDs, sparse slots 0 and 8, retained large empty 96 and large singleton 97 in slot 8, retained small empty 99, collapsed small singleton 98, and nextId 100.\n")
            } finally {
                activity.graph.layout.edit { original }
                awaitPersisted(activity, original)
                File(directory, "restart-original.json").delete()
            }
        }
    }
}
