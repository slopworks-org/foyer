package app.foyer.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.HttpURLConnection

internal const val REPOSITORY = "https://github.com/slopworks-org/foyer"

data class Release(val version: String, val downloadUrl: String)

internal fun versionParts(value: String): List<Int>? {
    if (!value.matches(Regex("v?\\d+\\.\\d+\\.\\d+"))) return null
    return value.removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }
}

internal fun newer(candidate: String, installed: String): Boolean {
    val left = versionParts(candidate) ?: return false
    val right = versionParts(installed) ?: return false
    return left.zip(right).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
}

internal fun parseRelease(raw: String, installed: String): Release? {
    val release = Json.parseToJsonElement(raw).jsonObject
    if (release["draft"]?.jsonPrimitive?.boolean != false || release["prerelease"]?.jsonPrimitive?.boolean != false) return null
    val tag = release.getValue("tag_name").jsonPrimitive.content
    if (!newer(tag, installed)) return null
    val version = tag.removePrefix("v")
    val expected = "$REPOSITORY/releases/download/$tag/foyer-$version.apk"
    val asset = release["assets"]?.jsonArray?.firstOrNull {
        val obj = it.jsonObject
        obj["name"]?.jsonPrimitive?.content == "foyer-$version.apk" &&
            obj["browser_download_url"]?.jsonPrimitive?.content == expected &&
            obj["state"]?.jsonPrimitive?.content == "uploaded"
    } ?: return null
    return Release(version, asset.jsonObject.getValue("browser_download_url").jsonPrimitive.content)
}

/** Bounded blocking HTTP on IO; socket timeouts also bound cancellation latency. */
suspend fun latestRelease(installed: String): Release? = runInterruptible(Dispatchers.IO) {
    val connection = URI("https://api.github.com/repos/slopworks-org/foyer/releases/latest").toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Foyer/$installed")
        if (connection.responseCode == 404) return@runInterruptible null
        if (connection.responseCode != 200) throw IOException("Release request failed")
        val bytes = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                if (output.size() + count > 1_048_576) throw IOException("Release response too large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        parseRelease(bytes.decodeToString(), installed)
    } finally {
        connection.disconnect()
    }
}
