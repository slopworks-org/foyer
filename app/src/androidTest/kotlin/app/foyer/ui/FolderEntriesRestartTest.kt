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
            val old = """{"seeded":true,"nextId":2,"items":[{"id":1,"x":1,"y":1,"w":2,"h":2,"content":{"type":"folder","large":true,"apps":[$key,$key],"slots":[$key,null,null,null,null,null,null,null,$key]}}]}"""
            val migrated = Edits.normalizeFolders(json.decodeFromString<HomeLayout>(old))
            graph.layout.edit { migrated }
            waitFor { LayoutStore(activity, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)).layout.value == migrated }
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
                val folder = expected.items.single().content as FolderContent
                assertEquals(listOf(0, 8), folder.entries.map { it.slot })
                assertEquals(2, folder.entries.map { it.id }.distinct().size)
                File(directory, "restart-verified.txt").writeText("Process restart retained migrated duplicate IDs and sparse slots 0 and 8.\n")
            } finally {
                activity.graph.layout.edit { original }
                waitFor { LayoutStore(activity, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)).layout.value == original }
                File(directory, "restart-original.json").delete()
            }
        }
    }
}
