package io.github.kvtttba.droplet

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DropletAppE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun endToEndRecordExpenseFilterTogglePrivacyAndManageCatalogs() {
        // 1. Starts on Ledger screen with seeded Room database
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ledger"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("screen_ledger").assertIsDisplayed()

        // 2. Open Quick Capture modal sheet via FAB and launch Manual Transaction Editor
        composeRule.onNodeWithTag("fab_quick_add").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("quick_capture_sheet"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("quick_capture_manual").performClick()

        // 3. Wait for seeded accounts & categories to appear, fill expense form, and save atomically
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_transaction_editor"), timeoutMillis = 5_000)
        composeRule.waitUntilAtLeastOneExists(hasTestTag("editor_account_储蓄卡"), timeoutMillis = 10_000)

        composeRule.onNodeWithTag("editor_amount_input").performTextInput("38.50")
        composeRule.onNodeWithTag("editor_lazy_column").performScrollToKey("metadata_card")
        composeRule.waitUntilAtLeastOneExists(hasTestTag("editor_tag_工作日"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("editor_tag_工作日").performClick()
        composeRule.onNodeWithTag("editor_payee_input").performTextReplacement("测试简餐")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("editor_save_button").performClick()

        // 4. Returns to Ledger and verifies real-time Room Flow update of list & monthly summary
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ledger"), timeoutMillis = 10_000)
        composeRule.waitUntilAtLeastOneExists(hasText("测试简餐", substring = true), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("ledger_monthly_expense").assertTextContains("38.50", substring = true)

        // 5. Verify inline tag filter
        composeRule.onNodeWithTag("ledger_filter_row")
            .performScrollToKey("tag_${DefaultSeedCatalog.TagIds.WORKDAY}")
        composeRule.onNodeWithTag("ledger_tag_chip_工作日").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("测试简餐", substring = true), timeoutMillis = 5_000)

        composeRule.onNodeWithTag("ledger_filter_row").performScrollToKey("chip_all")
        composeRule.onNodeWithTag("ledger_filter_all").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("测试简餐", substring = true), timeoutMillis = 5_000)

        // 6. Toggle global privacy hide and confirm masking carries over to Assets tab
        composeRule.onNodeWithTag("privacy_toggle_button").performClick()
        composeRule.onNodeWithTag("ledger_monthly_balance").assertTextContains("****", substring = true)

        composeRule.onNodeWithTag("nav_tab_assets").performClick()
        composeRule.onNodeWithTag("screen_assets").assertIsDisplayed()
        composeRule.onNodeWithTag("assets_net_worth").assertTextContains("****", substring = true)

        // 7. Navigate to Stats and Settings, then open Account & Category/Tag Management screens
        composeRule.onNodeWithTag("nav_tab_stats").performClick()
        composeRule.onNodeWithTag("screen_stats").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_settings").performClick()
        composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()

        composeRule.onNodeWithTag("settings_entry_accounts").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_account_management"), timeoutMillis = 5_000)
        composeRule.waitUntilAtLeastOneExists(hasTestTag("account_row_储蓄卡"), timeoutMillis = 5_000)

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_settings"), timeoutMillis = 5_000)

        composeRule.onNodeWithTag("settings_entry_categories_tags").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_category_tag_management"), timeoutMillis = 5_000)
        composeRule.waitUntilAtLeastOneExists(hasText("餐饮", substring = true), timeoutMillis = 5_000)
    }
}
