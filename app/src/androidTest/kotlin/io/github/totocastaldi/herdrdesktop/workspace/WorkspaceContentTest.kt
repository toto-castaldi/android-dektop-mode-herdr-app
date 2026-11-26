package io.github.totocastaldi.herdrdesktop.workspace

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Space
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.TerminalInfo
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.Workspace
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceEdits
import io.github.totocastaldi.herdrdesktop.coreclient.workspace.WorkspaceState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The spaces and tabs screen on a fixed core state, recording the actions it sends. */
@RunWith(AndroidJUnit4::class)
class WorkspaceContentTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()

    private val actions = object : WorkspaceActions {
        override fun createSpace() {
            calls += "createSpace"
        }

        override fun renameSpace(spaceId: String, name: String) {
            calls += "renameSpace $spaceId $name"
        }

        override fun moveSpace(spaceId: String, toIndex: Int) {
            calls += "moveSpace $spaceId $toIndex"
        }

        override fun closeSpace(spaceId: String) {
            calls += "closeSpace $spaceId"
        }

        override fun createTab(spaceId: String, folder: String?) {
            calls += "createTab $spaceId $folder"
        }

        override fun renameTab(spaceId: String, tabId: String, name: String) {
            calls += "renameTab $spaceId $tabId $name"
        }

        override fun moveTab(spaceId: String, tabId: String, toIndex: Int) {
            calls += "moveTab $spaceId $tabId $toIndex"
        }

        override fun closeTab(spaceId: String, tabId: String) {
            calls += "closeTab $spaceId $tabId"
        }
    }

    private val state = WorkspaceState.Ready(
        Workspace(
            listOf(
                Space(
                    "sp1",
                    tabs = listOf(
                        WorkspaceEdits.singlePaneTab("tb1", "p1", "t1"),
                        WorkspaceEdits.singlePaneTab("tb2", "p2", "t2").copy(name = "logs")
                    )
                ),
                Space("sp2", name = "Server", tabs = listOf(WorkspaceEdits.singlePaneTab("tb3", "p3", "t3")))
            )
        ),
        mapOf(
            "t1" to TerminalInfo("t1", "/home/toto/herdr", true),
            "t2" to TerminalInfo("t2", "/var/log", true),
            "t3" to TerminalInfo("t3", "/srv", true)
        )
    )

    private fun text(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    private fun show(s: WorkspaceState = state) = rule.setContent {
        MaterialTheme { WorkspaceContent("walle", s, actions) {} }
    }

    @Test
    fun unnamedSpaceShowsTheFolderOfItsFirstTerminalAndTabsTheirLabel() {
        show()
        rule.onNodeWithText("herdr").assertIsDisplayed()
        rule.onNodeWithText("Server").assertIsDisplayed()
        rule.onNodeWithText("1").assertIsDisplayed()
        rule.onNodeWithText("logs").assertIsDisplayed()
        rule.onNodeWithText(text(R.string.workspace_terminal, "t1", "/home/toto/herdr")).assertIsDisplayed()
    }

    @Test
    fun newTabStartsInTheFolderOfTheActiveTerminal() {
        show()
        rule.onNodeWithTag("tab-1").performClick()
        rule.onNodeWithText(text(R.string.workspace_new_tab)).performClick()
        assertEquals(listOf("createTab sp1 /var/log"), calls)
    }

    @Test
    fun renameMoveAndCreateSpace() {
        show()
        rule.onNodeWithTag("space-0-menu").performClick()
        rule.onNodeWithText(text(R.string.workspace_rename)).performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("Progetto")
        rule.onNodeWithText(text(R.string.save)).performClick()
        rule.onNodeWithTag("space-1-menu").performClick()
        rule.onNodeWithText(text(R.string.workspace_move_up)).performClick()
        rule.onNodeWithText(text(R.string.workspace_new_space)).performClick()
        assertEquals(listOf("renameSpace sp1 Progetto", "moveSpace sp2 0", "createSpace"), calls)
    }

    @Test
    fun closingATabAsksFirstThenEndsIt() {
        show()
        rule.onNodeWithTag("tab-1-menu").performClick()
        rule.onNodeWithText(text(R.string.workspace_close)).performClick()
        rule.onNodeWithText(text(R.string.workspace_close_tab_title, "logs")).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.cancel)).performClick()
        assertEquals(emptyList<String>(), calls)

        rule.onNodeWithTag("tab-1-menu").performClick()
        rule.onNodeWithText(text(R.string.workspace_close)).performClick()
        rule.onNodeWithText(text(R.string.workspace_close)).performClick()
        assertEquals(listOf("closeTab sp1 tb2"), calls)
    }

    @Test
    fun renameAndMoveTab() {
        show()
        rule.onNodeWithTag("tab-0-menu").performClick()
        rule.onNodeWithText(text(R.string.workspace_move_right)).performClick()
        rule.onNodeWithTag("tab-0-menu").performClick()
        rule.onNodeWithText(text(R.string.workspace_rename)).performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("build")
        rule.onNodeWithText(text(R.string.save)).performClick()
        assertEquals(listOf("moveTab sp1 tb1 1", "renameTab sp1 tb1 build"), calls)
    }

    @Test
    fun disconnectedMachineShowsNoSpaces() {
        show(WorkspaceState.Unavailable)
        rule.onNodeWithText(text(R.string.workspace_unavailable)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.workspace_new_space)).assertDoesNotExist()
    }
}
