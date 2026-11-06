package app.foyer.ui

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Opt in with recordEvidence=true; evidencePrefixes filters names and skipEvidence overrides it.
 * Recording is not a screenshot comparison or a visual-equivalence assertion.
 */
internal class FolderEvidence(private val directory: File,
    private val arguments: android.os.Bundle = InstrumentationRegistry.getArguments(),
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    fun device(name: String) = capture(name, image = { instrumentation.uiAutomation.takeScreenshot() })

    fun enabled(name: String): Boolean {
        if (arguments.getString("recordEvidence") != "true" || arguments.getString("skipEvidence") == "true") return false
        val prefixes = arguments.getString("evidencePrefixes")?.split(",")
        return prefixes == null || prefixes.any { name.startsWith(it) }
    }

    fun capture(name: String, includeDevice: Boolean = false, archive: String? = null, image: () -> Bitmap) {
        if (!enabled(name)) return
        directory.mkdirs()
        val files = mutableListOf<File>()
        fun save(suffix: String, bitmap: Bitmap) {
            val file = File(directory, "$name$suffix.png")
            try {
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                files += file
            } finally {
                bitmap.recycle()
            }
        }
        save("", image())
        if (includeDevice) save("-device", instrumentation.uiAutomation.takeScreenshot())
        if (archive != null) {
            instrumentation.uiAutomation.executeShellCommand("cp ${files.joinToString(" ") { it.absolutePath }} $archive/").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
    }
}
