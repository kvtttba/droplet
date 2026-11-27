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
import androidx.compose.ui.test.performScrollTo
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

    @Test
    fun endToEndInvestmentCreateBuyManualOverrideAndMarketSyncSettings() {
        // 1. Navigate to Assets tab and open Create Investment Asset sheet
        composeRule.waitUntilAtLeastOneExists(hasTestTag("nav_tab_assets"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("nav_tab_assets").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_assets"), timeoutMillis = 10_000)

        composeRule.onNodeWithTag("screen_assets").performScrollToKey("section_investments_header")
        composeRule.onNodeWithTag("assets_add_investment_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("create_asset_sheet"), timeoutMillis = 5_000)

        // 2. Create a new CNY ETF investment asset
        composeRule.onNodeWithTag("create_asset_class_ETF").performClick()
        composeRule.onNodeWithTag("create_asset_name_input").performTextReplacement("沪深300测试ETF")
        composeRule.onNodeWithTag("create_asset_symbol_input").performTextReplacement("510300")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("create_asset_submit_button").performScrollTo().performClick()

        // 3. Open the newly created holding detail screen from Assets overview
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("create_asset_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_assets"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("screen_assets").performScrollToKey("investments_card")
        composeRule.waitUntilAtLeastOneExists(hasTestTag("assets_position_510300"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("assets_position_510300").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_investment_detail"), timeoutMillis = 5_000)

        // 4. Record a buy transaction: 100 units @ ¥10.00 = ¥1,000.00
        composeRule.onNodeWithTag("investment_detail_list").performScrollToKey("detail_actions_card")
        composeRule.onNodeWithTag("detail_action_buy").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("buy_sheet"), timeoutMillis = 5_000)
        composeRule.waitUntilAtLeastOneExists(hasTestTag("cash_account_储蓄卡"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("cash_account_储蓄卡").performClick()
        composeRule.onNodeWithTag("buy_quantity_input").performTextReplacement("100")
        composeRule.onNodeWithTag("buy_price_input").performTextReplacement("10.00")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("buy_submit_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("buy_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("investment_detail_list").performScrollToKey("detail_hero_card")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("detail_market_value").assertTextContains("1,000.00", substring = true)
                true
            }.getOrDefault(false)
        }

        // 5. Apply manual quote override to ¥12.50 -> market value ¥1,250.00, unrealized P&L +¥250.00
        composeRule.onNodeWithTag("investment_detail_list").performScrollToKey("detail_actions_card")
        composeRule.onNodeWithTag("detail_action_manual_override").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("manual_override_sheet"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("override_price_input").performTextReplacement("12.50")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("override_submit_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("manual_override_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("investment_detail_list").performScrollToKey("detail_hero_card")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("detail_market_value").assertTextContains("1,250.00", substring = true)
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("investment_detail_list").performScrollToKey("detail_return_breakdown")
        composeRule.onNodeWithTag("detail_unrealized_pnl").assertTextContains("+¥250.00", substring = true)

        // 6. Return to Assets overview and verify real-time total assets & unrealized P&L update
        composeRule.onNodeWithTag("investment_detail_back").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_assets"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("screen_assets").performScrollToKey("assets_hero_card")
        composeRule.onNodeWithTag("assets_total_assets").assertTextContains("1,250.00", substring = true)
        composeRule.onNodeWithTag("screen_assets").performScrollToKey("investments_card")
        composeRule.onNodeWithTag("assets_investment_unrealized_pnl").assertTextContains("+¥250.00", substring = true)

        // 7. Open Market Sync & Proxy Settings and verify schedule & Wi-Fi toggle persist
        composeRule.onNodeWithTag("screen_assets").performScrollToKey("assets_header")
        composeRule.onNodeWithTag("assets_sync_settings_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_market_sync_settings"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("market_sync_settings_list").performScrollToKey("sync_schedule_card")
        composeRule.onNodeWithTag("market_sync_interval_HOURS_6").performClick()
        composeRule.onNodeWithTag("market_sync_interval_HOURS_6").assertIsSelected()
        composeRule.onNodeWithTag("market_sync_switch_wifi_only").performClick()
        composeRule.onNodeWithTag("market_sync_switch_wifi_only").assertIsOn()
    }

    @Test
    fun endToEndCreditCardConfigExpenseBillCheckAndOneTapRepayment() {
        // 1. Navigate to Assets -> Credit Card Detail
        composeRule.waitUntilAtLeastOneExists(hasTestTag("nav_tab_assets"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("nav_tab_assets").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_assets"), timeoutMillis = 10_000)

        composeRule.onNodeWithTag("screen_assets").performScrollToKey("section_credit")
        composeRule.waitUntilAtLeastOneExists(hasTestTag("assets_credit_card_信用卡"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("assets_credit_card_信用卡").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_credit_card_detail"), timeoutMillis = 5_000)

        // 2. Configure credit limit (¥20,000), billing day (5), due day (25), remind days (3)
        composeRule.onNodeWithTag("credit_card_detail_list").performScrollToKey("credit_actions_card")
        composeRule.onNodeWithTag("credit_card_open_settings_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("credit_card_params_sheet"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("credit_params_limit_input").performTextReplacement("20000")
        composeRule.onNodeWithTag("credit_params_billing_day_input").performTextReplacement("5")
        composeRule.onNodeWithTag("credit_params_due_day_input").performTextReplacement("25")
        composeRule.onNodeWithTag("credit_params_remind_days_input").performTextReplacement("3")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("credit_params_save_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("credit_card_params_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("credit_card_detail_list").performScrollToKey("credit_hero_card")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("credit_card_available_credit").assertTextContains("20,000.00", substring = true)
                true
            }.getOrDefault(false)
        }

        // 3. Return to Ledger and record a ¥320.00 credit card expense
        composeRule.onNodeWithTag("credit_card_detail_back").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_assets"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("nav_tab_ledger").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ledger"), timeoutMillis = 5_000)

        composeRule.onNodeWithTag("fab_quick_add").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("quick_capture_sheet"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("quick_capture_manual").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_transaction_editor"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("editor_amount_input").performTextInput("320.00")

        composeRule.onNodeWithTag("editor_lazy_column").performScrollToKey("account_picker")
        composeRule.waitUntilAtLeastOneExists(hasTestTag("editor_account_row"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("editor_account_row")
            .performScrollToKey(DefaultSeedCatalog.AccountIds.CREDIT_CARD)
        composeRule.onNodeWithTag("editor_account_信用卡").performClick()

        composeRule.onNodeWithTag("editor_lazy_column").performScrollToKey("metadata_card")
        composeRule.onNodeWithTag("editor_payee_input").performTextReplacement("测试信用卡消费")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("editor_save_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ledger"), timeoutMillis = 10_000)

        // 4. Open Credit Card Detail from Assets and verify used debt (¥320) & available limit (¥19,680)
        composeRule.onNodeWithTag("nav_tab_assets").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_assets"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("screen_assets").performScrollToKey("section_credit")
        composeRule.onNodeWithTag("assets_credit_card_信用卡").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_credit_card_detail"), timeoutMillis = 5_000)

        composeRule.onNodeWithTag("credit_card_detail_list").performScrollToKey("credit_hero_card")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("credit_card_current_debt").assertTextContains("320.00", substring = true)
                composeRule.onNodeWithTag("credit_card_available_credit").assertTextContains("19,680.00", substring = true)
                true
            }.getOrDefault(false)
        }

        // Verify the transaction appears in statement list ("全部流水")
        composeRule.onNodeWithTag("credit_card_detail_list").performScrollToKey("credit_statement_tabs")
        composeRule.onNodeWithTag("credit_card_tab_all").performClick()
        composeRule.onNodeWithTag("credit_card_detail_list").performScrollToKey("credit_statement_transactions_card")
        composeRule.waitUntilAtLeastOneExists(hasText("测试信用卡消费", substring = true), timeoutMillis = 5_000)

        // 5. Perform one-tap repayment (auto-prefilled ¥320.00) from Debit Card and confirm limit restored
        composeRule.onNodeWithTag("credit_card_detail_list").performScrollToKey("credit_actions_card")
        composeRule.onNodeWithTag("credit_card_repay_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("credit_card_repayment_sheet"), timeoutMillis = 5_000)
        composeRule.waitUntilAtLeastOneExists(hasTestTag("repay_account_储蓄卡"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("repay_account_储蓄卡").performClick()
        composeRule.onNodeWithTag("repay_amount_input").assertTextContains("320.00", substring = true)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("repay_submit_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("credit_card_repayment_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("credit_card_detail_list").performScrollToKey("credit_hero_card")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("credit_card_current_debt").assertTextContains("0.00", substring = true)
                composeRule.onNodeWithTag("credit_card_available_credit").assertTextContains("20,000.00", substring = true)
                true
            }.getOrDefault(false)
        }

        // 6. Visit Credit Card Reminder Settings from Settings tab and persist advance reminder days
        composeRule.onNodeWithTag("credit_card_detail_back").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_assets"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("nav_tab_settings").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_settings"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("screen_settings").performScrollToKey("settings_security_card")
        composeRule.onNodeWithTag("settings_entry_credit_card_reminders").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_credit_card_reminders"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("credit_card_reminders_list").performScrollToKey("reminder_controls_card")
        composeRule.onNodeWithTag("reminder_default_days_5").performClick()
        composeRule.onNodeWithTag("reminder_default_days_5").assertIsSelected()
    }

    private fun isFlagSecureApplied(): Boolean {
        var applied = false
        composeRule.activityRule.scenario.onActivity { activity ->
            applied = (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        }
        return applied
    }
}
