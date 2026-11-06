package app.foyer.updates

import app.foyer.data.Settings
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReleasesTest {
    private fun payload(tag: String = "v1.10.0", url: String = "$REPOSITORY/releases/download/$tag/foyer-${tag.removePrefix("v")}.apk") =
        """{"tag_name":"$tag","draft":false,"prerelease":false,"assets":[{"name":"foyer-${tag.removePrefix("v")}.apk","state":"uploaded","browser_download_url":"$url"}]}"""

    @Test fun comparesNumericVersionsAndRejectsDowngradesAndPrereleases() {
        assertTrue(newer("v1.10.0", "1.9.0"))
        assertFalse(newer("v1.9.0", "1.10.0"))
        assertFalse(newer("v1.10.0", "1.10.0"))
        assertFalse(newer("v2.0.0-beta.1", "1.10.0"))
        assertFalse(newer("v999999999999.0.0", "1.10.0"))
    }

    @Test fun requiresPublishedStableReleaseAndExactRepositoryAsset() {
        assertEquals("1.10.0", parseRelease(payload(), "1.9.0")?.version)
        assertNull(parseRelease(payload(), "1.10.0"))
        assertNull(parseRelease(payload().replace("\"draft\":false", "\"draft\":true"), "1.9.0"))
        assertNull(parseRelease(payload().replace("\"prerelease\":false", "\"prerelease\":true"), "1.9.0"))
        assertNull(parseRelease(payload().replace("uploaded", "new"), "1.9.0"))
        assertNull(parseRelease(payload(url = "https://example.com/update.apk"), "1.9.0"))
        assertNull(parseRelease(payload().replace("foyer-1.10.0.apk", "other.apk"), "1.9.0"))
    }

    @Test fun consentDefaultsOffAndBothAnswersSurviveSerialization() {
        assertNull(Json.decodeFromString<Settings>("{}").updateChecks)
        for (answer in listOf(false, true)) {
            val saved = Json.encodeToString(Settings.serializer(), Settings(updateChecks = answer))
            assertEquals(answer, Json.decodeFromString<Settings>(saved).updateChecks)
        }
    }
}
