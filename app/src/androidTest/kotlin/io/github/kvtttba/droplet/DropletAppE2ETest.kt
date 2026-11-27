package io.github.kvtttba.droplet

import android.view.WindowManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DropletAppE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun endToEndRecordExpenseFilterTogglePrivacyManageCatalogsAndPersistSecurityPrefs() {
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

        // 8. Navigate back to Settings and open Privacy & Security screen (M1.4)
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_settings"), timeoutMillis = 5_000)

        composeRule.onNodeWithTag("screen_settings").performScrollToKey("settings_security_card")
        composeRule.onNodeWithTag("settings_entry_privacy_security").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_privacy_security"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("privacy_capability_banner").assertIsDisplayed()

        // 9. Select relock timeout & enable FLAG_SECURE and confirm Window FLAG_SECURE is applied
        composeRule.onNodeWithTag("privacy_security_list").performScrollToKey("app_lock_card")
        composeRule.onNodeWithTag("relock_timeout_SECONDS_30").performClick()
        composeRule.onNodeWithTag("relock_timeout_SECONDS_30").assertIsSelected()

        composeRule.onNodeWithTag("privacy_security_list").performScrollToKey("screen_privacy_card")
        composeRule.onNodeWithTag("privacy_switch_amounts_hidden").assertIsOn()
        assertFalse(isFlagSecureApplied())

        composeRule.onNodeWithTag("privacy_switch_flag_secure").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { isFlagSecureApplied() }
        assertTrue(isFlagSecureApplied())
        composeRule.onNodeWithTag("privacy_switch_flag_secure").assertIsOn()

        // 10. Recreate MainActivity and confirm DataStore persists amountsHidden, FLAG_SECURE, and timeout
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_privacy_security"), timeoutMillis = 10_000)
        composeRule.waitUntil(timeoutMillis = 5_000) { isFlagSecureApplied() }
        assertTrue(isFlagSecureApplied())

        composeRule.onNodeWithTag("privacy_security_list").performScrollToKey("app_lock_card")
        composeRule.onNodeWithTag("relock_timeout_SECONDS_30").assertIsSelected()

        composeRule.onNodeWithTag("privacy_security_list").performScrollToKey("screen_privacy_card")
        composeRule.onNodeWithTag("privacy_switch_flag_secure").assertIsOn()
        composeRule.onNodeWithTag("privacy_switch_amounts_hidden").assertIsOn()

        // 11. Navigate back to Ledger and confirm persisted privacy masking survives Activity recreation
        composeRule.onNodeWithTag("privacy_back_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_settings"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("nav_tab_ledger").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ledger"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("ledger_monthly_balance").assertTextContains("****", substring = true)

        // 12. Return to Privacy & Security and toggle FLAG_SECURE + amountsHidden back off cleanly
        composeRule.onNodeWithTag("nav_tab_settings").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_settings"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("screen_settings").performScrollToKey("settings_security_card")
        composeRule.onNodeWithTag("settings_entry_privacy_security").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_privacy_security"), timeoutMillis = 5_000)

        composeRule.onNodeWithTag("privacy_security_list").performScrollToKey("screen_privacy_card")
        composeRule.onNodeWithTag("privacy_switch_flag_secure").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { !isFlagSecureApplied() }
        assertFalse(isFlagSecureApplied())
        composeRule.onNodeWithTag("privacy_switch_flag_secure").assertIsOff()

        composeRule.onNodeWithTag("privacy_switch_amounts_hidden").performClick()
        composeRule.onNodeWithTag("privacy_switch_amounts_hidden").assertIsOff()
    }

    private fun isFlagSecureApplied(): Boolean {
        var applied = false
        composeRule.activityRule.scenario.onActivity { activity ->
            applied = (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        }
        return applied
    }
}
