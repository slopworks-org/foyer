package app.foyer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.foyer.data.AppKey
import app.foyer.data.FolderContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FolderPanelContentTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rendersAndRenamesWithoutLauncherOrInstalledApps() {
        val folder = FolderContent(listOf(AppKey("sample.one", "Main", 0), AppKey("sample.two", "Main", 0)), name = "Tools")
        val name = mutableStateOf(folder.name)
        var renamed = ""
        var done = false
        rule.setContent {
            MaterialTheme {
                FolderPanelContent(FolderPanelUi(1, folder.copy(name = name.value), 56.dp),
                    onRename = { renamed = it; name.value = it }, onDone = { done = true })
            }
        }
        rule.onNodeWithText("sample.one").assertExists()
        rule.onNodeWithText("sample.two").assertExists()
        rule.onNode(hasSetTextAction()).performTextReplacement("Work")
        rule.runOnIdle { assertEquals("Work", renamed) }
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.runOnIdle { assertEquals(true, done) }
    }
}
