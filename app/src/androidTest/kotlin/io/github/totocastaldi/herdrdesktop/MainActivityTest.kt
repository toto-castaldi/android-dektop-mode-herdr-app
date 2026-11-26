package io.github.totocastaldi.herdrdesktop

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.totocastaldi.herdrdesktop.coreclient.keys.SshKeyStore
import io.github.totocastaldi.herdrdesktop.coreclient.machine.LocalMachine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int) = composeRule.activity.getString(id)

    @Test
    fun showsMachinesScreen() {
        composeRule.onNodeWithText(text(R.string.machines_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.key_title)).assertIsDisplayed()
    }

    @Test
    fun localMachineIsListedWithItsFixedEndpointAndCannotBeEdited() {
        composeRule.onNodeWithText(text(R.string.local_machine_name)).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.local_machine_endpoint, "droid@127.0.0.1:2222")
        )
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.local_setup)).assertIsDisplayed()
        // Only the local machine is listed: no edit or remove action is offered for it.
        val container = (composeRule.activity.application as HerdrApp).container
        if (container.machines.machines.value.size == 1) {
            composeRule.onNodeWithText(text(R.string.machine_edit)).assertDoesNotExist()
            composeRule.onNodeWithText(text(R.string.machine_remove)).assertDoesNotExist()
        }
    }

    @Test
    fun setupDialogShowsTheCommandWithTheAppKey() {
        val container = (composeRule.activity.application as HerdrApp).container
        composeRule.onNodeWithText(text(R.string.local_setup)).performClick()
        composeRule.onNodeWithText(text(R.string.local_setup_title)).assertIsDisplayed()
        val key = container.keys.publicKey(SshKeyStore.DEFAULT_ALIAS)
        if (key != null) {
            composeRule.onNodeWithText(LocalMachine.setupCommand(key.authorizedKeysLine())).assertIsDisplayed()
        } else {
            composeRule.onNodeWithText(text(R.string.local_setup_no_key)).assertIsDisplayed()
        }
        composeRule.onNodeWithText(text(R.string.close)).performClick()
    }

    @Test
    fun addsAndRemovesAMachineWithPassword() {
        val name = "ui-test-machine"
        composeRule.onNodeWithText(text(R.string.machine_add)).performClick()
        composeRule.onNodeWithText(text(R.string.field_name)).performTextInput(name)
        composeRule.onNodeWithText(text(R.string.field_host)).performTextInput("192.0.2.1")
        composeRule.onNodeWithText(text(R.string.field_user)).performTextInput("tester")
        composeRule.onNodeWithText(text(R.string.auth_password)).performClick()
        composeRule.onNode(
            hasText(text(R.string.field_password)) and hasSetTextAction()
        ).performTextInput("not-a-real-password")
        composeRule.onNodeWithText(text(R.string.save)).performClick()

        composeRule.onNodeWithText(name).assertIsDisplayed()
        composeRule.onNodeWithText("tester@192.0.2.1:22").assertIsDisplayed()

        val container = (composeRule.activity.application as HerdrApp).container
        val saved = container.machines.machines.value.first { it.name == name }
        container.connections.forget(saved.id)
        container.machines.remove(saved.id)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(name).assertDoesNotExist()
    }
}
