package io.github.kvtttba.droplet.ui

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.security.BiometricCapability
import io.github.kvtttba.droplet.core.security.RelockTimeout
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.ThemeMode
import io.github.kvtttba.droplet.feature.aicapture.QuickCaptureSheetContent
import io.github.kvtttba.droplet.feature.settings.PrivacySecurityScreen
import io.github.kvtttba.droplet.feature.settings.PrivacySecurityUiState
import io.github.kvtttba.droplet.feature.ledger.LedgerAmountTone
import io.github.kvtttba.droplet.feature.ledger.LedgerDayGroup
import io.github.kvtttba.droplet.feature.ledger.LedgerItemMapper
import io.github.kvtttba.droplet.feature.ledger.LedgerPostingDetail
import io.github.kvtttba.droplet.feature.ledger.LedgerTransactionItem
import io.github.kvtttba.droplet.feature.ledger.LedgerUiState
import io.github.kvtttba.droplet.feature.ledger.editor.CurrencyImbalanceInfo
import io.github.kvtttba.droplet.feature.ledger.editor.EditorTab
import io.github.kvtttba.droplet.feature.ledger.editor.PostingDirection
import io.github.kvtttba.droplet.feature.ledger.editor.SplitRowDraft
import io.github.kvtttba.droplet.feature.ledger.editor.TransactionEditorScreen
import io.github.kvtttba.droplet.feature.ledger.editor.TransactionEditorUiState
import io.github.kvtttba.droplet.feature.ledger.manage.AccountManagementScreen
import io.github.kvtttba.droplet.feature.ledger.manage.AccountManagementUiState
import io.github.kvtttba.droplet.feature.ledger.manage.AccountSectionGroup
import io.github.kvtttba.droplet.feature.ledger.manage.AccountWithBalance
import io.github.kvtttba.droplet.feature.ledger.manage.CategoryTagManagementScreen
import io.github.kvtttba.droplet.feature.ledger.manage.CategoryTagManagementUiState
import io.github.kvtttba.droplet.feature.ledger.manage.ManageCatalogTab
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = Application::class,
    sdk = [35],
    qualifiers = "w412dp-h915dp-xxhdpi",
)
class DropletAppScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var previousTimeZone: TimeZone? = null

    @Before
    fun setUpTimeZone() {
        previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun restoreTimeZone() {
        previousTimeZone?.let { TimeZone.setDefault(it) }
    }

    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(
            changeThreshold = 0.015f,
        ),
    )

    @Test
    fun captureLedgerLight() {
        composeRule.setContent {
            DropletAppShell(
                initialTab = DropletTab.LEDGER,
                initialThemeMode = ThemeMode.LIGHT,
                initialDynamicColor = false,
                ledgerPreviewState = sampleLedgerUiState(),
            )
        }
        composeRule.onNodeWithTag("screen_ledger").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/ledger_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureLedgerDark() {
        composeRule.setContent {
            DropletAppShell(
                initialTab = DropletTab.LEDGER,
                initialThemeMode = ThemeMode.DARK,
                initialDynamicColor = false,
                ledgerPreviewState = sampleLedgerUiState(),
            )
        }
        composeRule.onNodeWithTag("screen_ledger").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/ledger_dark.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureLedgerPrivacyHidden() {
        composeRule.setContent {
            DropletAppShell(
                initialTab = DropletTab.LEDGER,
                initialThemeMode = ThemeMode.LIGHT,
                initialDynamicColor = false,
                initialAmountsHidden = true,
                ledgerPreviewState = sampleLedgerUiState(),
            )
        }
        composeRule.onNodeWithTag("ledger_monthly_balance").assertTextContains("****", substring = true)
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/ledger_privacy_hidden.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureTransactionEditorIntuitiveLight() {
        composeRule.setContent {
            DropletTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                TransactionEditorScreen(uiState = sampleIntuitiveEditorState())
            }
        }
        composeRule.onNodeWithTag("screen_transaction_editor").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/transaction_editor_intuitive_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureTransactionEditorCrossCurrencyLight() {
        composeRule.setContent {
            DropletTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                TransactionEditorScreen(uiState = sampleCrossCurrencyTransferEditorState())
            }
        }
        composeRule.onNodeWithTag("editor_implied_rate_card").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/transaction_editor_cross_currency_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureTransactionEditorSplitLight() {
        composeRule.setContent {
            DropletTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                TransactionEditorScreen(uiState = sampleSplitEditorState())
            }
        }
        composeRule.onNodeWithTag("split_balance_banner").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/transaction_editor_split_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureAccountManagementLight() {
        composeRule.setContent {
            DropletTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                AccountManagementScreen(uiState = sampleAccountManagementState())
            }
        }
        composeRule.onNodeWithTag("screen_account_management").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/account_management_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureCategoryTagManagementLight() {
        composeRule.setContent {
            DropletTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                CategoryTagManagementScreen(uiState = sampleCategoryTagManagementState())
            }
        }
        composeRule.onNodeWithTag("screen_category_tag_management").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/category_tag_management_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureAssetsLight() {
        composeRule.setContent {
            DropletAppShell(
                initialTab = DropletTab.ASSETS,
                initialThemeMode = ThemeMode.LIGHT,
                initialDynamicColor = false,
                ledgerPreviewState = sampleLedgerUiState(),
            )
        }
        composeRule.onNodeWithTag("screen_assets").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/assets_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureAssetsDark() {
        composeRule.setContent {
            DropletAppShell(
                initialTab = DropletTab.ASSETS,
                initialThemeMode = ThemeMode.DARK,
                initialDynamicColor = false,
                ledgerPreviewState = sampleLedgerUiState(),
            )
        }
        composeRule.onNodeWithTag("screen_assets").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/assets_dark.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureStatsLight() {
        composeRule.setContent {
            DropletAppShell(
                initialTab = DropletTab.STATS,
                initialThemeMode = ThemeMode.LIGHT,
                initialDynamicColor = false,
                ledgerPreviewState = sampleLedgerUiState(),
            )
        }
        composeRule.onNodeWithTag("screen_stats").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/stats_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureSettingsLight() {
        composeRule.setContent {
            DropletAppShell(
                initialTab = DropletTab.SETTINGS,
                initialThemeMode = ThemeMode.LIGHT,
                initialDynamicColor = false,
                ledgerPreviewState = sampleLedgerUiState(),
            )
        }
        composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/settings_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureQuickCaptureSheetLight() {
        composeRule.setContent {
            DropletTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                Surface {
                    QuickCaptureSheetContent(onSelectMode = {})
                }
            }
        }
        composeRule.onNodeWithTag("quick_capture_sheet").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/quick_capture_sheet_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureAppLockOverlayLight() {
        composeRule.setContent {
            DropletTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                AppLockOverlay(
                    isAuthenticating = false,
                    errorMessage = null,
                    onUnlockClick = {},
                )
            }
        }
        composeRule.onNodeWithTag("app_lock_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("app_lock_unlock_button").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/app_lock_overlay_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureAppLockOverlayDark() {
        composeRule.setContent {
            DropletTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                AppLockOverlay(
                    isAuthenticating = false,
                    errorMessage = "已取消验证，请点击下方按钮重试",
                    onUnlockClick = {},
                )
            }
        }
        composeRule.onNodeWithTag("app_lock_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("app_lock_error_banner").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/app_lock_overlay_dark.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun capturePrivacySecurityLight() {
        composeRule.setContent {
            DropletTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                PrivacySecurityScreen(
                    uiState = PrivacySecurityUiState(
                        biometricLockEnabled = true,
                        relockTimeout = RelockTimeout.MINUTE_1,
                        flagSecureEnabled = true,
                        amountsHidden = false,
                        biometricCapability = BiometricCapability.AVAILABLE,
                    ),
                )
            }
        }
        composeRule.onNodeWithTag("screen_privacy_security").assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_lock_now_button").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/privacy_security_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun interactiveShell_privacySecurityAndAppLockFlowWork() {
        composeRule.setContent {
            DropletAppShell(
                initialTab = DropletTab.SETTINGS,
                initialThemeMode = ThemeMode.LIGHT,
                initialDynamicColor = false,
                initialBiometricLockEnabled = true,
                initialIsLocked = true,
                ledgerPreviewState = sampleLedgerUiState(),
            )
        }

        // Cold-start locked overlay obscures everything until unlock
        composeRule.onNodeWithTag("app_lock_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("app_lock_unlock_button").performClick()

        // After unlock, Settings screen is shown and navigates into PrivacySecurityScreen
        composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("screen_settings").performScrollToKey("settings_security_card")
        composeRule.onNodeWithTag("settings_entry_privacy_security").performClick()
        composeRule.onNodeWithTag("screen_privacy_security").assertIsDisplayed()

        // Manual lock re-displays AppLockOverlay
        composeRule.onNodeWithTag("privacy_security_list").performScrollToKey("app_lock_card")
        composeRule.onNodeWithTag("privacy_lock_now_button").performClick()
        composeRule.onNodeWithTag("app_lock_overlay").assertIsDisplayed()
    }

    @Test
    fun interactiveShell_navigationPrivacyAndFabWork() {
        composeRule.setContent {
            DropletAppShell(
                initialThemeMode = ThemeMode.LIGHT,
                initialDynamicColor = false,
                ledgerPreviewState = sampleLedgerUiState(),
            )
        }

        composeRule.onNodeWithTag("privacy_toggle_button").performClick()
        composeRule.onNodeWithTag("ledger_monthly_balance").assertTextContains("****", substring = true)

        composeRule.onNodeWithTag("nav_tab_assets").performClick()
        composeRule.onNodeWithTag("screen_assets").assertIsDisplayed()
        composeRule.onNodeWithTag("assets_net_worth").assertTextContains("****", substring = true)

        composeRule.onNodeWithTag("nav_tab_stats").performClick()
        composeRule.onNodeWithTag("screen_stats").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_settings").performClick()
        composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_entry_accounts").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_entry_categories_tags").assertIsDisplayed()

        composeRule.onNodeWithTag("fab_quick_add").performClick()
        composeRule.onNodeWithTag("quick_capture_sheet").assertIsDisplayed()
    }

    companion object {
        private val fixedTimestampMs: Long =
            LocalDate.of(2026, 11, 27).atTime(12, 30).toInstant(ZoneOffset.ofHours(8)).toEpochMilli()

        private val sampleTags = DefaultSeedCatalog.defaultTags(fixedTimestampMs)
        private val sampleOperationalAccounts = DefaultSeedCatalog.defaultOperationalAccounts(fixedTimestampMs)
            .filterNot { it.isSystem } + listOf(
            Account(
                id = "sample:usd_cash",
                name = "美元资金户",
                type = AccountType.ASSET,
                subType = AccountSubType.INVEST_CASH,
                currency = Currency.USD,
                icon = "trending_up",
                color = "#4E5E7E",
                includeInNetWorth = true,
                sortOrder = 60,
            ),
        )
        private val sampleExpenseGroups = LedgerItemMapper.buildCategoryGroups(
            DefaultSeedCatalog.defaultExpenseCategories(fixedTimestampMs),
        )
        private val sampleIncomeGroups = LedgerItemMapper.buildCategoryGroups(
            DefaultSeedCatalog.defaultIncomeCategories(fixedTimestampMs),
        )

        private fun sampleLedgerUiState(): LedgerUiState {
            val workdayTag = sampleTags.first { it.id == DefaultSeedCatalog.TagIds.WORKDAY }
            val businessTag = sampleTags.first { it.id == DefaultSeedCatalog.TagIds.BUSINESS_TRIP }
            val fixedTag = sampleTags.first { it.id == DefaultSeedCatalog.TagIds.FIXED_EXPENSE }

            return LedgerUiState(
                selectedMonth = YearMonth.of(2026, 11),
                baseCurrency = Currency.CNY,
                monthlyBalance = Money.fromDecimalString("4286.50", Currency.CNY),
                monthlyIncome = Money.fromDecimalString("12800.00", Currency.CNY),
                monthlyExpense = Money.fromDecimalString("8513.50", Currency.CNY),
                transactionCount = 4,
                availableAccounts = sampleOperationalAccounts,
                expenseCategoryGroups = sampleExpenseGroups,
                incomeCategoryGroups = sampleIncomeGroups,
                availableTags = sampleTags,
                dayGroups = listOf(
                    LedgerDayGroup(
                        date = LocalDate.of(2026, 11, 27),
                        dateHeader = "11月27日 · 周五",
                        dayExpenseBase = Money.fromDecimalString("44.50", Currency.CNY),
                        dayIncomeBase = Money.zero(Currency.CNY),
                        items = listOf(
                            LedgerTransactionItem(
                                id = "snap-tx-1",
                                occurredAt = fixedTimestampMs,
                                timeLabel = "12:18",
                                dateTimeLabel = "2026-11-27 12:18",
                                displayType = TransactionDisplayType.EXPENSE,
                                status = TransactionStatus.POSTED,
                                title = "餐饮·午餐 · 简餐馆",
                                subtitle = "储蓄卡",
                                accountLabel = "储蓄卡",
                                payee = "简餐馆",
                                note = null,
                                iconKey = "restaurant",
                                colorHex = "#EF6C00",
                                primaryAmount = Money.fromDecimalString("-38.50", Currency.CNY),
                                amountTone = LedgerAmountTone.EXPENSE,
                                showPositiveSign = false,
                                tags = listOf(workdayTag),
                            ),
                            LedgerTransactionItem(
                                id = "snap-tx-2",
                                occurredAt = fixedTimestampMs - 3_600_000L,
                                timeLabel = "08:42",
                                dateTimeLabel = "2026-11-27 08:42",
                                displayType = TransactionDisplayType.EXPENSE,
                                status = TransactionStatus.POSTED,
                                title = "交通·地铁公交",
                                subtitle = "支付宝",
                                accountLabel = "支付宝",
                                payee = null,
                                note = "通勤地铁",
                                iconKey = "directions_subway",
                                colorHex = "#1976D2",
                                primaryAmount = Money.fromDecimalString("-6.00", Currency.CNY),
                                amountTone = LedgerAmountTone.EXPENSE,
                                showPositiveSign = false,
                                tags = listOf(workdayTag, fixedTag),
                            ),
                            LedgerTransactionItem(
                                id = "snap-tx-3",
                                occurredAt = fixedTimestampMs - 7_200_000L,
                                timeLabel = "07:55",
                                dateTimeLabel = "2026-11-27 07:55",
                                displayType = TransactionDisplayType.REIMBURSE,
                                status = TransactionStatus.POSTED,
                                title = "差旅打车",
                                subtitle = "信用卡 · 去机场",
                                accountLabel = "信用卡",
                                payee = "差旅打车",
                                note = "去机场",
                                iconKey = "local_taxi",
                                colorHex = "#FB8C00",
                                primaryAmount = Money.fromDecimalString("-64.00", Currency.CNY),
                                amountTone = LedgerAmountTone.EXPENSE,
                                showPositiveSign = false,
                                statusBadge = "待报销",
                                isReimburseBadge = true,
                                tags = listOf(businessTag),
                            ),
                        ),
                    ),
                    LedgerDayGroup(
                        date = LocalDate.of(2026, 11, 26),
                        dateHeader = "11月26日 · 周四",
                        dayExpenseBase = Money.zero(Currency.CNY),
                        dayIncomeBase = Money.fromDecimalString("108.75", Currency.CNY),
                        items = listOf(
                            LedgerTransactionItem(
                                id = "snap-tx-4",
                                occurredAt = fixedTimestampMs - 86_400_000L,
                                timeLabel = "21:10",
                                dateTimeLabel = "2026-11-26 21:10",
                                displayType = TransactionDisplayType.INCOME,
                                status = TransactionStatus.POSTED,
                                title = "投资理财·投资分红 · ETF 分红",
                                subtitle = "美元资金户",
                                accountLabel = "美元资金户",
                                payee = "ETF 分红",
                                note = null,
                                iconKey = "trending_up",
                                colorHex = "#00897B",
                                primaryAmount = Money.fromDecimalString("15.00", Currency.USD),
                                secondaryBaseAmount = Money.fromDecimalString("108.75", Currency.CNY),
                                amountTone = LedgerAmountTone.INCOME,
                                showPositiveSign = true,
                                tags = emptyList(),
                                isMultiCurrency = true,
                            ),
                        ),
                    ),
                ),
            )
        }

        private fun sampleIntuitiveEditorState(): TransactionEditorUiState =
            TransactionEditorUiState(
                isSplitMode = false,
                activeTab = EditorTab.EXPENSE,
                amountText = "38.50",
                transactionCurrency = Currency.CNY,
                selectedParentCategoryId = DefaultSeedCatalog.AccountIds.EXP_FOOD,
                selectedCategoryId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
                selectedAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                occurredAt = fixedTimestampMs,
                selectedTagIds = setOf(DefaultSeedCatalog.TagIds.WORKDAY),
                payee = "简餐馆",
                note = "午餐牛肉面",
                livePreviewSummary = "借: 餐饮·午餐 +¥38.50  ·  贷: 储蓄卡 -¥38.50",
                operationalAccounts = sampleOperationalAccounts,
                accountBalances = mapOf(
                    DefaultSeedCatalog.AccountIds.DEBIT_CARD to Money.fromDecimalString("28450.00", Currency.CNY),
                    DefaultSeedCatalog.AccountIds.WECHAT to Money.fromDecimalString("1620.50", Currency.CNY),
                    DefaultSeedCatalog.AccountIds.ALIPAY to Money.fromDecimalString("4180.00", Currency.CNY),
                    DefaultSeedCatalog.AccountIds.CREDIT_CARD to Money.fromDecimalString("-2380.00", Currency.CNY),
                    "sample:usd_cash" to Money.fromDecimalString("1500.00", Currency.USD),
                ),
                expenseCategoryGroups = sampleExpenseGroups,
                incomeCategoryGroups = sampleIncomeGroups,
                availableTags = sampleTags,
            )

        private fun sampleCrossCurrencyTransferEditorState(): TransactionEditorUiState {
            val usdFirst = sampleOperationalAccounts.sortedBy {
                when (it.id) {
                    DefaultSeedCatalog.AccountIds.DEBIT_CARD -> 0
                    "sample:usd_cash" -> 1
                    else -> 2
                }
            }
            return TransactionEditorUiState(
                isSplitMode = false,
                activeTab = EditorTab.TRANSFER,
                amountText = "7250.00",
                transactionCurrency = Currency.CNY,
                counterAmountText = "1000.00",
                impliedRateSummary = "反推成交汇率：1 CNY = 0.1379 USD（1 USD = 7.25 CNY）",
                selectedAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                selectedTargetAccountId = "sample:usd_cash",
                occurredAt = fixedTimestampMs,
                payee = "购汇入金",
                note = "银行实付反推成交汇率",
                livePreviewSummary = "借: 美元资金户 +$1,000.00  ·  贷: 储蓄卡 -¥7,250.00",
                operationalAccounts = usdFirst,
                accountBalances = mapOf(
                    DefaultSeedCatalog.AccountIds.DEBIT_CARD to Money.fromDecimalString("28450.00", Currency.CNY),
                    "sample:usd_cash" to Money.fromDecimalString("1500.00", Currency.USD),
                ),
                availableTags = sampleTags,
            )
        }

        private fun sampleSplitEditorState(): TransactionEditorUiState {
            val allSelectable = sampleOperationalAccounts +
                DefaultSeedCatalog.defaultExpenseCategories(fixedTimestampMs) +
                DefaultSeedCatalog.defaultIncomeCategories(fixedTimestampMs)
            return TransactionEditorUiState(
                isSplitMode = true,
                occurredAt = fixedTimestampMs,
                payee = "11月工资单拆账",
                note = "实发 + 个税与五险一金",
                selectedTagIds = setOf(DefaultSeedCatalog.TagIds.WORKDAY),
                splitRows = listOf(
                    SplitRowDraft(
                        rowId = "row-1",
                        accountId = DefaultSeedCatalog.AccountIds.INC_WORK_SALARY,
                        direction = PostingDirection.CREDIT,
                        currency = Currency.CNY,
                        amountText = "15000.00",
                    ),
                    SplitRowDraft(
                        rowId = "row-2",
                        accountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                        direction = PostingDirection.DEBIT,
                        currency = Currency.CNY,
                        amountText = "12000.00",
                    ),
                    SplitRowDraft(
                        rowId = "row-3",
                        accountId = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
                        direction = PostingDirection.DEBIT,
                        currency = Currency.CNY,
                        amountText = "3000.00",
                    ),
                ),
                currencyImbalances = listOf(CurrencyImbalanceInfo(Currency.CNY, 0L)),
                isSplitReadyToSave = true,
                splitStatusSummary = "借贷已完全平衡，可直接原子入账",
                operationalAccounts = sampleOperationalAccounts,
                allSelectableAccounts = allSelectable,
                availableTags = sampleTags,
            )
        }

        private fun sampleAccountManagementState(): AccountManagementUiState {
            val creditCard = sampleOperationalAccounts
                .first { it.id == DefaultSeedCatalog.AccountIds.CREDIT_CARD }
                .copy(
                    creditLimitMinor = Money.fromDecimalString("50000.00", Currency.CNY).amountMinor,
                    billingDay = 5,
                    dueDay = 25,
                    remindDaysBefore = 3,
                )
            val daily = sampleOperationalAccounts
                .filter { it.subType in setOf(AccountSubType.CASH, AccountSubType.DEBIT_CARD) }
                .map { acct ->
                    val bal = when (acct.id) {
                        DefaultSeedCatalog.AccountIds.CASH -> "420.00"
                        DefaultSeedCatalog.AccountIds.DEBIT_CARD -> "28450.00"
                        DefaultSeedCatalog.AccountIds.WECHAT -> "1620.50"
                        else -> "4180.00"
                    }
                    AccountWithBalance(acct, Money.fromDecimalString(bal, Currency.CNY), 6)
                }
            val usd = sampleOperationalAccounts.first { it.id == "sample:usd_cash" }
            return AccountManagementUiState(
                activeGroups = listOf(
                    AccountSectionGroup("日常资金账户", daily),
                    AccountSectionGroup(
                        "信用账户",
                        listOf(
                            AccountWithBalance(
                                creditCard,
                                Money.fromDecimalString("-2380.00", Currency.CNY),
                                4,
                            ),
                        ),
                    ),
                    AccountSectionGroup(
                        "投资、贷款与往来账户",
                        listOf(
                            AccountWithBalance(
                                usd,
                                Money.fromDecimalString("1500.00", Currency.USD),
                                2,
                            ),
                        ),
                    ),
                ),
                totalActiveCount = daily.size + 2,
            )
        }

        private fun sampleCategoryTagManagementState(): CategoryTagManagementUiState =
            CategoryTagManagementUiState(
                activeTab = ManageCatalogTab.EXPENSE_CATEGORIES,
                expenseGroups = sampleExpenseGroups.take(4),
                incomeGroups = sampleIncomeGroups,
                tags = sampleTags,
            )
    }
}
