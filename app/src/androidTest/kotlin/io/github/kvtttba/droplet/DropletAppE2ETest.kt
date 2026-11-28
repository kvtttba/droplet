package io.github.kvtttba.droplet

import android.view.WindowManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@OptIn(ExperimentalTestApi::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class DropletAppE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun test01_endToEndRecordExpenseFilterTogglePrivacyManageCatalogsAndPersistSecurityPrefs() {
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
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("relock_timeout_SECONDS_30").assertIsSelected()
                true
            }.getOrDefault(false)
        }

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
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("privacy_switch_amounts_hidden").assertIsOff()
                true
            }.getOrDefault(false)
        }
    }

    @Test
    fun test02_endToEndInvestmentCreateBuyManualOverrideAndMarketSyncSettings() {
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
    fun test03_endToEndCreditCardConfigExpenseBillCheckAndOneTapRepayment() {
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
        composeRule.waitUntilAtLeastOneExists(hasTestTag("reminder_default_days_5"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("reminder_default_days_5").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("reminder_default_days_5").assertIsSelected()
                true
            }.getOrDefault(false)
        }
    }

    @Test
    fun test04_endToEndInstallmentLoanCreateScheduleCheckAndFirstRepayment() {
        // 1. Navigate to Assets tab and open Create Installment Loan sheet
        composeRule.waitUntilAtLeastOneExists(hasTestTag("nav_tab_assets"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("nav_tab_assets").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_assets"), timeoutMillis = 10_000)

        composeRule.onNodeWithTag("screen_assets").performScrollToKey("section_loans_header")
        composeRule.onNodeWithTag("assets_add_loan_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("create_loan_sheet"), timeoutMillis = 5_000)

        // 2. Fill fixed-fee installment loan: ¥6,000 / 6 periods / ¥30 fee per period -> ¥1,000 principal + ¥30 fee
        composeRule.onNodeWithTag("create_loan_name_input").performTextReplacement("测试电脑分期")
        composeRule.onNodeWithTag("create_loan_method_FIXED_FEE").performScrollTo().performClick()
        composeRule.onNodeWithTag("create_loan_principal_input").performScrollTo().performTextReplacement("6000")
        composeRule.onNodeWithTag("create_loan_periods_input").performScrollTo().performTextReplacement("6")
        composeRule.onNodeWithTag("create_loan_fixed_fee_input").performScrollTo().performTextReplacement("30")
        composeRule.onNodeWithTag("create_loan_payment_储蓄卡").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("create_loan_submit_button").performScrollTo().performClick()

        // 3. Wait for sheet dismissal, scroll to Installment Loans section, and open Loan Detail
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("create_loan_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_assets"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("screen_assets").performScrollToKey("section_loans")
        composeRule.waitUntilAtLeastOneExists(hasTestTag("assets_loan_测试电脑分期"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("assets_loan_测试电脑分期").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_loan_detail"), timeoutMillis = 5_000)

        // 4. Verify initial remaining principal (¥6,000.00), loan liability (-¥6,000.00), and schedule row 1
        composeRule.onNodeWithTag("loan_detail_list").performScrollToKey("loan_hero_card")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("loan_detail_remaining_principal").assertTextContains("6,000.00", substring = true)
                composeRule.onNodeWithTag("loan_detail_account_balance").assertTextContains("-¥6,000.00", substring = true)
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("loan_detail_list").performScrollToKey("loan_schedule_card")
        composeRule.waitUntilAtLeastOneExists(hasTestTag("loan_installment_row_1"), timeoutMillis = 5_000)

        // 5. Confirm period 1 repayment via one-tap action and confirm liability + remaining principal drop to ¥5,000.00
        composeRule.onNodeWithTag("loan_detail_list").performScrollToKey("loan_actions_card")
        composeRule.onNodeWithTag("loan_repay_next_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("loan_repay_sheet"), timeoutMillis = 5_000)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("loan_repay_confirm_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("loan_repay_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("loan_detail_list").performScrollToKey("loan_hero_card")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("loan_detail_remaining_principal").assertTextContains("5,000.00", substring = true)
                composeRule.onNodeWithTag("loan_detail_account_balance").assertTextContains("-¥5,000.00", substring = true)
                composeRule.onNodeWithTag("loan_detail_progress_label").assertTextContains("1/6", substring = true)
                true
            }.getOrDefault(false)
        }
    }

    @Test
    fun test05_endToEndDebtReimbursementShortfallAndMultiStageRefund() {
        // --- Part 1: Counterparty Debt (create -> lend ¥1,000 -> collect ¥400 + ¥20 interest -> write off ¥600) ---
        composeRule.waitUntilAtLeastOneExists(hasTestTag("nav_tab_assets"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("nav_tab_assets").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_assets"), timeoutMillis = 10_000)

        composeRule.onNodeWithTag("screen_assets").performScrollToKey("section_debts_header")
        composeRule.onNodeWithTag("assets_add_debt_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("create_debt_profile_sheet"), timeoutMillis = 5_000)

        composeRule.onNodeWithTag("create_debt_name_input").performTextReplacement("测试往来张三")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("create_debt_submit_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("create_debt_profile_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("screen_assets").performScrollToKey("section_debts")
        composeRule.waitUntilAtLeastOneExists(hasTestTag("assets_debt_测试往来张三"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("assets_debt_测试往来张三").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_debt_detail"), timeoutMillis = 5_000)

        // Lend ¥1,000 from 储蓄卡
        composeRule.onNodeWithTag("debt_detail_list").performScrollToKey("debt_actions_card")
        composeRule.onNodeWithTag("debt_action_lend").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("debt_lend_sheet"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("debt_amount_input").performTextReplacement("1000")
        composeRule.waitUntilAtLeastOneExists(hasTestTag("debt_funding_account_储蓄卡"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("debt_funding_account_储蓄卡").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("debt_sheet_submit_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("debt_lend_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("debt_detail_list").performScrollToKey("debt_hero_card")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("debt_detail_receivable").assertTextContains("1,000.00", substring = true)
                true
            }.getOrDefault(false)
        }

        // Partial collect ¥400 principal + ¥20 interest income
        composeRule.onNodeWithTag("debt_detail_list").performScrollToKey("debt_actions_card")
        composeRule.onNodeWithTag("debt_action_collect").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("debt_collect_sheet"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("debt_principal_input").performTextReplacement("400")
        composeRule.onNodeWithTag("debt_interest_input").performTextReplacement("20")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("debt_sheet_submit_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("debt_collect_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("debt_detail_list").performScrollToKey("debt_hero_card")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("debt_detail_receivable").assertTextContains("600.00", substring = true)
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("debt_detail_list").performScrollToKey("debt_metrics_card")
        composeRule.onNodeWithTag("debt_detail_interest_income").assertTextContains("20.00", substring = true)

        // Bad-debt write-off remaining ¥600 -> receivable drops to ¥0.00
        composeRule.onNodeWithTag("debt_detail_list").performScrollToKey("debt_actions_card")
        composeRule.onNodeWithTag("debt_action_write_off").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("debt_write_off_sheet"), timeoutMillis = 5_000)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("debt_write_off_submit_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("debt_write_off_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("debt_detail_list").performScrollToKey("debt_hero_card")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("debt_detail_receivable").assertTextContains("0.00", substring = true)
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("debt_detail_back").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_assets"), timeoutMillis = 5_000)

        // --- Part 2: Reimbursable Advance (¥500) -> Settle with Shortfall (¥450 received + ¥50 personal expense) ---
        composeRule.onNodeWithTag("nav_tab_ledger").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ledger"), timeoutMillis = 5_000)

        composeRule.onNodeWithTag("fab_quick_add").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("quick_capture_sheet"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("quick_capture_manual").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_transaction_editor"), timeoutMillis = 5_000)
        composeRule.waitUntilAtLeastOneExists(hasTestTag("editor_account_储蓄卡"), timeoutMillis = 10_000)

        composeRule.onNodeWithTag("editor_amount_input").performTextReplacement("500")
        composeRule.onNodeWithTag("editor_lazy_column").performScrollToKey("advanced_options")
        composeRule.onNodeWithTag("editor_advanced_toggle").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("editor_reimburse_switch"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("editor_reimburse_switch").performClick()
        composeRule.onNodeWithTag("editor_reimburse_switch").assertIsOn()
        composeRule.onNodeWithTag("editor_lazy_column").performScrollToKey("metadata_card")
        composeRule.onNodeWithTag("editor_payee_input").performTextReplacement("差旅报销测试")
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("editor_save_button").assertIsEnabled().performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ledger"), timeoutMillis = 10_000)

        // Navigate to Assets -> Pending Reimbursements and settle with ¥450 received + ¥50 shortfall expense
        composeRule.onNodeWithTag("nav_tab_assets").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_assets"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("screen_assets").performScrollToKey("section_reimbursements")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("assets_reimbursement_entry").assertTextContains("500.00", substring = true)
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("assets_reimbursement_entry").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_reimbursement_list"), timeoutMillis = 5_000)
        composeRule.waitUntilAtLeastOneExists(hasTestTag("reimbursement_settle_btn_0"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("reimbursement_settle_btn_0").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("reimbursement_single_settle_sheet"), timeoutMillis = 5_000)

        composeRule.onNodeWithTag("reimbursement_received_input").performTextReplacement("450")
        composeRule.waitUntilAtLeastOneExists(hasTestTag("reimbursement_diff_preview"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("reimbursement_diff_preview").assertTextContains("50.00", substring = true)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("reimbursement_settle_confirm_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("reimbursement_single_settle_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("reimbursement_pending_total").assertTextContains("0.00", substring = true)
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("reimbursement_back").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_assets"), timeoutMillis = 5_000)

        // --- Part 3: Multi-stage Linked Refunds on ¥200 Expense (¥60 + ¥40 -> net ¥100) ---
        composeRule.onNodeWithTag("nav_tab_ledger").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ledger"), timeoutMillis = 5_000)

        composeRule.onNodeWithTag("fab_quick_add").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("quick_capture_sheet"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("quick_capture_manual").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_transaction_editor"), timeoutMillis = 5_000)
        composeRule.waitUntilAtLeastOneExists(hasTestTag("editor_account_储蓄卡"), timeoutMillis = 10_000)

        composeRule.onNodeWithTag("editor_amount_input").performTextReplacement("200")
        composeRule.onNodeWithTag("editor_lazy_column").performScrollToKey("metadata_card")
        composeRule.onNodeWithTag("editor_payee_input").performTextReplacement("网购退款测试")
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("editor_save_button").assertIsEnabled().performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ledger"), timeoutMillis = 10_000)
        composeRule.waitUntilAtLeastOneExists(hasText("网购退款测试", substring = true), timeoutMillis = 10_000)

        // Open expense detail sheet & trigger first partial refund (¥60)
        composeRule.onNodeWithText("网购退款测试", substring = true).performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("tx_detail_refund_button"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("tx_detail_refund_button").performScrollTo().performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("record_refund_sheet"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("refund_amount_input").performTextReplacement("60")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("refund_submit_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("record_refund_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("tx_detail_refunded_total").assertTextContains("60.00", substring = true)
                composeRule.onNodeWithTag("tx_detail_net_expense").assertTextContains("140.00", substring = true)
                true
            }.getOrDefault(false)
        }

        // Trigger second partial refund (¥40) -> refunded ¥100, remaining ¥100, net ¥100
        composeRule.onNodeWithTag("tx_detail_refund_button").performScrollTo().performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("record_refund_sheet"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("refund_amount_input").performTextReplacement("40")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("refund_submit_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("record_refund_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("tx_detail_refunded_total").assertTextContains("100.00", substring = true)
                composeRule.onNodeWithTag("tx_detail_remaining_refundable").assertTextContains("100.00", substring = true)
                composeRule.onNodeWithTag("tx_detail_net_expense").assertTextContains("100.00", substring = true)
                true
            }.getOrDefault(false)
        }
    }

    @Test
    fun test06_endToEndAiSettingsConfigSlotOverrideAndPersistence() {
        val sampleBaseUrl = "https://mock-ai.example.invalid/v1"
        val sampleModel = "demo-model-mini"
        val sampleVisionModel = "demo-vision-pro"
        val samplePlaceholderInput = "demo-placeholder-key-0001"

        // 1. Navigate to Settings tab and open AI Service Configuration screen
        composeRule.waitUntilAtLeastOneExists(hasTestTag("nav_tab_settings"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("nav_tab_settings").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_settings"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("screen_settings").performScrollToKey("settings_security_card")
        composeRule.onNodeWithTag("settings_entry_ai_config").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ai_settings"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("ai_privacy_notice_banner").assertIsDisplayed()

        // 2. Fill default OpenAI Compatible config using sanitized synthetic values and save (Keystore encrypted)
        composeRule.onNodeWithTag("ai_settings_list").performScrollToNode(hasTestTag("ai_default_base_url_input"))
        composeRule.onNodeWithTag("ai_default_base_url_input").performTextReplacement(sampleBaseUrl)
        composeRule.onNodeWithTag("ai_settings_list").performScrollToNode(hasTestTag("ai_default_api_key_input"))
        composeRule.onNodeWithTag("ai_default_api_key_input").performTextReplacement(samplePlaceholderInput)
        composeRule.onNodeWithTag("ai_settings_list").performScrollToNode(hasTestTag("ai_default_model_input"))
        composeRule.onNodeWithTag("ai_default_model_input").performTextReplacement(sampleModel)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("ai_settings_list").performScrollToNode(hasTestTag("ai_default_save_button"))
        composeRule.onNodeWithTag("ai_default_save_button").performClick()

        // Wait for async Keystore encryption + DataStore write to reflect masked badge
        composeRule.onNodeWithTag("ai_settings_list").performScrollToKey("ai_default_config_card")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("ai_default_saved_key_badge")
                    .assertTextContains("••••0001", substring = true)
                composeRule.onNodeWithTag("ai_default_status_badge")
                    .assertTextContains("已加密配置", substring = true)
                true
            }.getOrDefault(false)
        }

        // 3. Switch voice transcription engine mode to CUSTOM_AI
        composeRule.onNodeWithTag("ai_settings_list").performScrollToKey("ai_voice_engine_card")
        composeRule.onNodeWithTag("ai_voice_mode_CUSTOM_AI").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("ai_voice_mode_CUSTOM_AI").assertIsSelected()
                true
            }.getOrDefault(false)
        }

        // 4. Enable VISION slot independent override and save custom vision model
        composeRule.onNodeWithTag("ai_settings_list").performScrollToKey("ai_slot_card_VISION")
        composeRule.onNodeWithTag("ai_slot_switch_VISION").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("ai_slot_VISION_model_input"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("ai_settings_list").performScrollToNode(hasTestTag("ai_slot_VISION_model_input"))
        composeRule.onNodeWithTag("ai_slot_VISION_model_input").performTextReplacement(sampleVisionModel)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("ai_settings_list").performScrollToNode(hasTestTag("ai_slot_save_VISION"))
        composeRule.onNodeWithTag("ai_slot_save_VISION").performClick()

        composeRule.onNodeWithTag("ai_settings_list").performScrollToKey("ai_hero_privacy_card")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("ai_hero_summary").assertTextContains("1 项覆盖", substring = true)
                true
            }.getOrDefault(false)
        }

        // 5. Recreate Activity and verify encrypted key presence, override slot, and voice mode persist
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ai_settings"), timeoutMillis = 10_000)

        composeRule.onNodeWithTag("ai_settings_list").performScrollToKey("ai_default_config_card")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("ai_default_base_url_input")
                    .assertTextContains(sampleBaseUrl, substring = true)
                composeRule.onNodeWithTag("ai_default_model_input")
                    .assertTextContains(sampleModel, substring = true)
                composeRule.onNodeWithTag("ai_default_saved_key_badge")
                    .assertTextContains("••••0001", substring = true)
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithTag("ai_settings_list").performScrollToKey("ai_voice_engine_card")
        composeRule.onNodeWithTag("ai_voice_mode_CUSTOM_AI").assertIsSelected()

        composeRule.onNodeWithTag("ai_settings_list").performScrollToKey("ai_slot_card_VISION")
        composeRule.onNodeWithTag("ai_slot_switch_VISION").assertIsOn()
        composeRule.onNodeWithTag("ai_settings_list").performScrollToNode(hasTestTag("ai_slot_VISION_model_input"))
        composeRule.onNodeWithTag("ai_slot_VISION_model_input")
            .assertTextContains(sampleVisionModel, substring = true)

        // 6. Navigate back to Settings and confirm live AI config summary reflects persisted state
        composeRule.onNodeWithTag("ai_settings_back_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_settings"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("screen_settings").performScrollToKey("settings_security_card")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("settings_entry_ai_config")
                    .assertTextContains(sampleModel, substring = true)
                composeRule.onNodeWithTag("settings_entry_ai_config")
                    .assertTextContains("AI 语音", substring = true)
                true
            }.getOrDefault(false)
        }
    }

    @Test
    fun test07_endToEndAiCaptureOfflineDraftReviewAndBatchCommit() {
        // 1. From Ledger FAB Quick Capture sheet, open "一句话 AI 记账"
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ledger"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("fab_quick_add").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("quick_capture_sheet"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("quick_capture_natural_text").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ai_capture"), timeoutMillis = 5_000)

        // 2. Enter natural-language text and save offline to Draft Inbox (zero external network calls)
        composeRule.onNodeWithTag("ai_text_input").performTextReplacement("测试早餐 18.50 微信")
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ai_text_save_offline_button").assertIsEnabled().performClick()

        // 3. Scroll to Draft Inbox, confirm PENDING_ONLINE badge, and tap "手动转草稿"
        composeRule.onNodeWithTag("ai_capture_list").performScrollToNode(hasTestTag("ai_draft_card_0"))
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("ai_draft_status_badge_0")
                    .assertTextContains("待联网解析", substring = true)
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("ai_capture_list")
            .performScrollToNode(hasTestTag("ai_draft_manual_edit_button_0"))
        composeRule.onNodeWithTag("ai_draft_manual_edit_button_0").performClick()

        // 4. Verify Draft Batch Review screen opens with uncertain field badges highlighted & editor expanded
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ai_draft_review"), timeoutMillis = 5_000)
        composeRule.waitUntilAtLeastOneExists(hasTestTag("ai_review_card_0"), timeoutMillis = 5_000)
        composeRule.waitUntilAtLeastOneExists(hasTestTag("ai_review_uncertain_0_category_path"), timeoutMillis = 5_000)

        // Verify extracted amount, pick category & account, fill payee, and commit atomically
        composeRule.onNodeWithTag("ai_review_list")
            .performScrollToNode(hasTestTag("ai_review_amount_input_0"))
        composeRule.onNodeWithTag("ai_review_amount_input_0").assertTextContains("18.50", substring = true)

        composeRule.onNodeWithTag("ai_review_list")
            .performScrollToNode(hasTestTag("ai_review_category_0_早餐"))
        composeRule.onNodeWithTag("ai_review_category_0_早餐").performClick()

        composeRule.onNodeWithTag("ai_review_list")
            .performScrollToNode(hasTestTag("ai_review_account_0_微信零钱"))
        composeRule.onNodeWithTag("ai_review_account_0_微信零钱").performClick()

        composeRule.onNodeWithTag("ai_review_list")
            .performScrollToNode(hasTestTag("ai_review_payee_input_0"))
        composeRule.onNodeWithTag("ai_review_payee_input_0").performTextReplacement("测试AI豆浆")
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ai_review_total_amount_0").assertTextContains("18.50", substring = true)
        composeRule.onNodeWithTag("ai_review_commit_button").assertIsEnabled().performClick()

        // 5. Returns to Ledger and verifies real-time Room Flow displays committed AI transaction
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ledger"), timeoutMillis = 10_000)
        composeRule.waitUntilAtLeastOneExists(hasText("测试AI豆浆", substring = true), timeoutMillis = 10_000)
    }

    @Test
    fun test08_endToEndBudgetAlertRecurringAutoPostAndQuickTemplate() {
        // 1. From Settings -> Budget Management, create a monthly overall budget (¥1,000, 80% warn)
        composeRule.waitUntilAtLeastOneExists(hasTestTag("nav_tab_settings"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("nav_tab_settings").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_settings"), timeoutMillis = 5_000)

        composeRule.onNodeWithTag("screen_settings").performScrollToKey("settings_planning_card")
        composeRule.onNodeWithTag("settings_entry_budgets").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_budget_management"), timeoutMillis = 5_000)

        composeRule.waitUntilAtLeastOneExists(hasTestTag("budget_create_overall_button"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("budget_create_overall_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("budget_editor_sheet"), timeoutMillis = 5_000)

        composeRule.onNodeWithTag("budget_sheet_amount_input").performTextReplacement("1000")
        composeRule.onNodeWithTag("budget_sheet_warn_80").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("budget_sheet_save_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("budget_editor_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntilAtLeastOneExists(hasTestTag("budget_overall_card"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("budget_overall_limit").assertTextContains("1,000.00", substring = true)

        // 2. Return to Settings -> Recurring Rules: create rule and verify pause / resume toggle
        composeRule.onNodeWithTag("budget_back_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_settings"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("screen_settings").performScrollToKey("settings_planning_card")
        composeRule.onNodeWithTag("settings_entry_recurring_rules").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_recurring_rules"), timeoutMillis = 5_000)

        composeRule.onNodeWithTag("recurring_add_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("recurring_editor_sheet"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("recurring_sheet_name_input").performTextReplacement("测试每月房租")
        composeRule.onNodeWithTag("recurring_sheet_amount_input").performTextReplacement("2500")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("recurring_sheet_save_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("recurring_editor_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("recurring_rules_list")
            .performScrollToNode(hasTestTag("recurring_rule_card_测试每月房租"))
        composeRule.onNodeWithTag("recurring_rule_status_测试每月房租")
            .assertTextContains("自动入账", substring = true)

        // Pause -> status updates to 已暂停 -> Resume -> returns to 自动入账
        composeRule.onNodeWithTag("recurring_pause_switch_测试每月房租").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("recurring_rule_status_测试每月房租")
                    .assertTextContains("已暂停", substring = true)
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("recurring_pause_switch_测试每月房租").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("recurring_rule_status_测试每月房租")
                    .assertTextContains("自动入账", substring = true)
                true
            }.getOrDefault(false)
        }

        // 3. Return to Settings -> Quick Templates: create "通勤地铁" and one-tap direct post
        composeRule.onNodeWithTag("recurring_back_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_settings"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("screen_settings").performScrollToKey("settings_planning_card")
        composeRule.onNodeWithTag("settings_entry_templates").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_template_management"), timeoutMillis = 5_000)

        composeRule.onNodeWithTag("template_add_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("template_editor_sheet"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("template_sheet_name_input").performTextReplacement("通勤地铁")
        composeRule.onNodeWithTag("template_sheet_amount_input").performTextReplacement("6")
        composeRule.onNodeWithTag("template_sheet_payee_input").performScrollTo().performTextReplacement("测试地铁通勤")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("template_sheet_save_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("template_editor_sheet")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("template_management_list")
            .performScrollToNode(hasTestTag("template_card_通勤地铁"))
        composeRule.onNodeWithTag("template_post_button_通勤地铁").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("已用 1 次", substring = true), timeoutMillis = 10_000)

        // 4. Return to Ledger tab and confirm posted transaction + live monthly budget progress card
        composeRule.onNodeWithTag("template_back_button").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_settings"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag("nav_tab_ledger").performClick()
        composeRule.waitUntilAtLeastOneExists(hasTestTag("screen_ledger"), timeoutMillis = 10_000)

        composeRule.waitUntilAtLeastOneExists(hasTestTag("ledger_budget_progress_card"), timeoutMillis = 10_000)
        composeRule.onNodeWithTag("ledger_budget_progress_card").assertTextContains("1,000.00", substring = true)
        composeRule.waitUntilAtLeastOneExists(hasText("测试地铁通勤", substring = true), timeoutMillis = 10_000)
    }

    private fun isFlagSecureApplied(): Boolean {
        var applied = false
        composeRule.activityRule.scenario.onActivity { activity ->
            applied = (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        }
        return applied
    }
}
