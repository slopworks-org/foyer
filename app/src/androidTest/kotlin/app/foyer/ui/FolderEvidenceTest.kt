package app.foyer.ui

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FolderEvidenceTest {
    @Test fun recordingIsOptInAndFiltersBeforeCapturing() {
        val arguments = android.os.Bundle()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "evidence-test")
        directory.deleteRecursively()
        val evidence = FolderEvidence(directory, arguments)
        try {
            evidence.capture("default") { error("ordinary checks must not capture") }
            assertFalse(directory.exists())
            arguments.putString("recordEvidence", "true")
            arguments.putString("evidencePrefixes", "selected")
            evidence.capture("other") { error("filtered frames must not capture") }
            evidence.capture("selected") { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) }
            assertTrue(File(directory, "selected.png").length() > 0)
            arguments.putString("skipEvidence", "true")
            evidence.capture("selected-skipped") { error("skip must override recording") }
        } finally {
            directory.deleteRecursively()
        }
    }
}
