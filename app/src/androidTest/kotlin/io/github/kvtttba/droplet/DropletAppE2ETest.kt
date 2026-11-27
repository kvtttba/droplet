package io.github.kvtttba.droplet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DropletAppE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesNavigatesAllTabsTogglesPrivacyAndOpensQuickCapture() {
        // 1. Starts on Ledger screen with visible amount
        composeRule.onNodeWithTag("screen_ledger").assertIsDisplayed()
        composeRule.onNodeWithTag("ledger_monthly_balance").assertTextContains("4,286.50", substring = true)

        // 2. Toggle global privacy hide and confirm masking carries over to Assets tab
        composeRule.onNodeWithTag("privacy_toggle_button").performClick()
        composeRule.onNodeWithTag("ledger_monthly_balance").assertTextContains("****", substring = true)

        composeRule.onNodeWithTag("nav_tab_assets").performClick()
        composeRule.onNodeWithTag("screen_assets").assertIsDisplayed()
        composeRule.onNodeWithTag("assets_net_worth").assertTextContains("****", substring = true)

        // 3. Navigate through Stats and Settings tabs
        composeRule.onNodeWithTag("nav_tab_stats").performClick()
        composeRule.onNodeWithTag("screen_stats").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_settings").performClick()
        composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()

        // 4. Open Quick Capture modal sheet via FAB and select a mode
        composeRule.onNodeWithTag("fab_quick_add").performClick()
        composeRule.onNodeWithTag("quick_capture_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("quick_capture_manual").performClick()

        // 5. Return to Ledger tab
        composeRule.onNodeWithTag("nav_tab_ledger").performClick()
        composeRule.onNodeWithTag("screen_ledger").assertIsDisplayed()
    }
}
