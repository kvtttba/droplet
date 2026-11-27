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
import io.github.kvtttba.droplet.core.domain.investment.PortfolioPositionEngine
import io.github.kvtttba.droplet.core.domain.investment.PortfolioSummaryAggregator
import io.github.kvtttba.droplet.core.domain.investment.PositionLedgerState
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DepositInterestMode
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.MarketQuote
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.model.UnitPrice
import io.github.kvtttba.droplet.core.network.NetworkProxyConfig
import io.github.kvtttba.droplet.core.network.NetworkProxyType
import io.github.kvtttba.droplet.core.security.BiometricCapability
import io.github.kvtttba.droplet.core.security.MarketSyncInterval
import io.github.kvtttba.droplet.core.security.RelockTimeout
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.ThemeMode
import io.github.kvtttba.droplet.feature.aicapture.QuickCaptureSheetContent
import io.github.kvtttba.droplet.feature.assets.AssetsUiState
import io.github.kvtttba.droplet.feature.assets.InvestmentDetailScreen
import io.github.kvtttba.droplet.feature.assets.InvestmentDetailUiState
import io.github.kvtttba.droplet.feature.assets.InvestmentHistoryKind
import io.github.kvtttba.droplet.feature.assets.InvestmentTransactionItem
import io.github.kvtttba.droplet.feature.settings.MarketSyncSettingsScreen
import io.github.kvtttba.droplet.feature.settings.MarketSyncSettingsUiState
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
                assetsPreviewState = sampleAssetsUiState(),
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
                assetsPreviewState = sampleAssetsUiState(),
            )
        }
        composeRule.onNodeWithTag("screen_assets").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/assets_dark.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureInvestmentDetailLight() {
        composeRule.setContent {
            DropletTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                InvestmentDetailScreen(uiState = sampleInvestmentDetailUiState())
            }
        }
        composeRule.onNodeWithTag("screen_investment_detail").assertIsDisplayed()
        composeRule.onNodeWithTag("detail_holding_quantity").assertTextContains("0.12345678", substring = true)
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/investment_detail_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureMarketSyncSettingsLight() {
        composeRule.setContent {
            DropletTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                MarketSyncSettingsScreen(uiState = sampleMarketSyncSettingsUiState())
            }
        }
        composeRule.onNodeWithTag("screen_market_sync_settings").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/market_sync_settings_light.png",
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

    @Test
    fun interactiveShell_assetsDetailAndMarketSyncNavigationWork() {
        composeRule.setContent {
            DropletAppShell(
                initialTab = DropletTab.ASSETS,
                initialThemeMode = ThemeMode.LIGHT,
                initialDynamicColor = false,
                assetsPreviewState = sampleAssetsUiState(),
                investmentDetailPreviewState = sampleInvestmentDetailUiState(),
                marketSyncPreviewState = sampleMarketSyncSettingsUiState(),
            )
        }

        composeRule.onNodeWithTag("screen_assets").assertIsDisplayed()
        composeRule.onNodeWithTag("assets_position_BTC").performClick()
        composeRule.onNodeWithTag("screen_investment_detail").assertIsDisplayed()
        composeRule.onNodeWithTag("investment_detail_back").performClick()

        composeRule.onNodeWithTag("screen_assets").assertIsDisplayed()
        composeRule.onNodeWithTag("assets_sync_settings_button").performClick()
        composeRule.onNodeWithTag("screen_market_sync_settings").assertIsDisplayed()
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

        private fun sampleMarketRates(): ExchangeRateTable = ExchangeRateTable.of(
            ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.20", ExchangeRateSource.MARKET_CACHE, fixedTimestampMs),
            ExchangeRateSnapshot.of(Currency.HKD, Currency.CNY, "0.92", ExchangeRateSource.MARKET_CACHE, fixedTimestampMs),
        )

        private fun sampleBtcPosition() = PortfolioPositionEngine.evaluatePositionFromState(
            asset = InvestmentAsset(
                id = "snap-asset-btc",
                accountId = "holding:snap-asset-btc",
                assetClass = AssetClass.CRYPTO,
                symbol = "BTC",
                name = "Bitcoin",
                currency = Currency.USD,
            ),
            ledgerState = PositionLedgerState(
                assetId = "snap-asset-btc",
                currency = Currency.USD,
                holdingQuantity = Quantity.of("0.12345678"),
                remainingCostBasis = Money.fromDecimalString("8850.00", Currency.USD),
                averageCostPrice = UnitPrice.of("71684.99696817"),
                realizedPnL = Money.fromDecimalString("620.00", Currency.USD),
                totalDividends = Money.zero(Currency.USD),
                totalDividendTax = Money.zero(Currency.USD),
                totalTradeFees = Money.fromDecimalString("18.50", Currency.USD),
                totalFees = Money.fromDecimalString("18.50", Currency.USD),
                cumulativeBuyCost = Money.fromDecimalString("10450.00", Currency.USD),
                cumulativeSellProceeds = Money.fromDecimalString("2220.00", Currency.USD),
                buyCount = 3,
                sellCount = 1,
                dividendCount = 0,
                lastTransactionAt = fixedTimestampMs,
            ),
            marketQuote = MarketQuote(
                assetId = "snap-asset-btc",
                latestPrice = UnitPrice.of("92800.00"),
                prevClose = UnitPrice.of("90500.00"),
                dailyChangePctDecimal = "2.5414",
                priceCurrency = Currency.USD,
                sourceProvider = "okx",
                isManualOverride = false,
                fetchedAt = fixedTimestampMs,
            ),
            baseCurrency = Currency.CNY,
            exchangeRateProvider = sampleMarketRates(),
            asOfEpochMs = fixedTimestampMs,
        )

        private fun sampleAssetsUiState(): AssetsUiState {
            val rates = sampleMarketRates()
            val etfHolding = Account(
                id = "holding:snap-asset-etf",
                name = "沪深300ETF 持仓",
                type = AccountType.ASSET,
                subType = AccountSubType.INVEST_HOLDING,
                currency = Currency.CNY,
                includeInNetWorth = true,
            )
            val btcHolding = Account(
                id = "holding:snap-asset-btc",
                name = "Bitcoin 持仓",
                type = AccountType.ASSET,
                subType = AccountSubType.INVEST_HOLDING,
                currency = Currency.USD,
                includeInNetWorth = true,
            )
            val hkHolding = Account(
                id = "holding:snap-asset-hk",
                name = "腾讯控股 持仓",
                type = AccountType.ASSET,
                subType = AccountSubType.INVEST_HOLDING,
                currency = Currency.HKD,
                includeInNetWorth = true,
            )
            val fdHolding = Account(
                id = "holding:snap-asset-fd",
                name = "招行一年定期 持仓",
                type = AccountType.ASSET,
                subType = AccountSubType.INVEST_HOLDING,
                currency = Currency.CNY,
                includeInNetWorth = true,
            )
            val wealthHolding = Account(
                id = "holding:snap-asset-wealth",
                name = "稳健日开理财 持仓",
                type = AccountType.ASSET,
                subType = AccountSubType.INVEST_HOLDING,
                currency = Currency.CNY,
                includeInNetWorth = true,
            )
            val housingFund = Account(
                id = "sample:housing_fund",
                name = "住房公积金",
                type = AccountType.ASSET,
                subType = AccountSubType.STORED_VALUE,
                currency = Currency.CNY,
                icon = "savings",
                color = "#546E7A",
                includeInNetWorth = false,
            )
            val creditCard = sampleOperationalAccounts
                .first { it.id == DefaultSeedCatalog.AccountIds.CREDIT_CARD }
                .copy(
                    creditLimitMinor = Money.fromDecimalString("50000.00", Currency.CNY).amountMinor,
                    billingDay = 5,
                    dueDay = 25,
                )
            val debitCard = sampleOperationalAccounts
                .first { it.id == DefaultSeedCatalog.AccountIds.DEBIT_CARD }
            val usdCash = sampleOperationalAccounts
                .first { it.id == "sample:usd_cash" }

            val etfPos = PortfolioPositionEngine.evaluatePositionFromState(
                asset = InvestmentAsset(
                    id = "snap-asset-etf",
                    accountId = etfHolding.id,
                    assetClass = AssetClass.ETF,
                    symbol = "510300",
                    name = "沪深300ETF",
                    currency = Currency.CNY,
                ),
                ledgerState = PositionLedgerState(
                    assetId = "snap-asset-etf",
                    currency = Currency.CNY,
                    holdingQuantity = Quantity.of("15000"),
                    remainingCostBasis = Money.fromDecimalString("58500.00", Currency.CNY),
                    averageCostPrice = UnitPrice.of("3.90"),
                    realizedPnL = Money.zero(Currency.CNY),
                    totalDividends = Money.fromDecimalString("850.00", Currency.CNY),
                    totalDividendTax = Money.zero(Currency.CNY),
                    totalTradeFees = Money.fromDecimalString("15.00", Currency.CNY),
                    totalFees = Money.fromDecimalString("15.00", Currency.CNY),
                    cumulativeBuyCost = Money.fromDecimalString("58500.00", Currency.CNY),
                    cumulativeSellProceeds = Money.zero(Currency.CNY),
                    buyCount = 2,
                    sellCount = 0,
                    dividendCount = 1,
                ),
                marketQuote = MarketQuote(
                    assetId = "snap-asset-etf",
                    latestPrice = UnitPrice.of("4.22"),
                    prevClose = UnitPrice.of("4.16"),
                    dailyChangePctDecimal = "1.4423",
                    priceCurrency = Currency.CNY,
                    sourceProvider = "eastmoney",
                    fetchedAt = fixedTimestampMs,
                ),
                baseCurrency = Currency.CNY,
                exchangeRateProvider = rates,
                asOfEpochMs = fixedTimestampMs,
            )

            val btcPos = sampleBtcPosition()

            val hkPos = PortfolioPositionEngine.evaluatePositionFromState(
                asset = InvestmentAsset(
                    id = "snap-asset-hk",
                    accountId = hkHolding.id,
                    assetClass = AssetClass.HK_STOCK,
                    symbol = "00700",
                    name = "腾讯控股",
                    currency = Currency.HKD,
                ),
                ledgerState = PositionLedgerState(
                    assetId = "snap-asset-hk",
                    currency = Currency.HKD,
                    holdingQuantity = Quantity.of("100"),
                    remainingCostBasis = Money.fromDecimalString("36000.00", Currency.HKD),
                    averageCostPrice = UnitPrice.of("360"),
                    realizedPnL = Money.zero(Currency.HKD),
                    totalDividends = Money.zero(Currency.HKD),
                    totalDividendTax = Money.zero(Currency.HKD),
                    totalTradeFees = Money.zero(Currency.HKD),
                    totalFees = Money.zero(Currency.HKD),
                    cumulativeBuyCost = Money.fromDecimalString("36000.00", Currency.HKD),
                    cumulativeSellProceeds = Money.zero(Currency.HKD),
                    buyCount = 1,
                    sellCount = 0,
                    dividendCount = 0,
                ),
                marketQuote = MarketQuote(
                    assetId = "snap-asset-hk",
                    latestPrice = UnitPrice.of("418.00"),
                    prevClose = UnitPrice.of("412.00"),
                    dailyChangePctDecimal = "1.4563",
                    priceCurrency = Currency.HKD,
                    sourceProvider = "eastmoney",
                    fetchedAt = fixedTimestampMs,
                ),
                baseCurrency = Currency.CNY,
                exchangeRateProvider = rates,
                asOfEpochMs = fixedTimestampMs,
            )

            val fdStartMs = LocalDate.of(2026, 5, 1).atStartOfDay(ZoneOffset.ofHours(8)).toInstant().toEpochMilli()
            val fdMaturityMs = LocalDate.of(2027, 5, 1).atStartOfDay(ZoneOffset.ofHours(8)).toInstant().toEpochMilli()
            val fdPos = PortfolioPositionEngine.evaluatePositionFromState(
                asset = InvestmentAsset(
                    id = "snap-asset-fd",
                    accountId = fdHolding.id,
                    assetClass = AssetClass.FIXED_DEPOSIT,
                    symbol = "CMB-1Y",
                    name = "招行一年定期",
                    currency = Currency.CNY,
                    depositRateAnnualDecimal = "0.0245",
                    depositStartAt = fdStartMs,
                    depositMaturityAt = fdMaturityMs,
                    depositInterestMode = DepositInterestMode.SIMPLE_AT_MATURITY,
                ),
                ledgerState = PositionLedgerState(
                    assetId = "snap-asset-fd",
                    currency = Currency.CNY,
                    holdingQuantity = Quantity.of("50000"),
                    remainingCostBasis = Money.fromDecimalString("50000.00", Currency.CNY),
                    averageCostPrice = UnitPrice.of("1"),
                    realizedPnL = Money.zero(Currency.CNY),
                    totalDividends = Money.zero(Currency.CNY),
                    totalDividendTax = Money.zero(Currency.CNY),
                    totalTradeFees = Money.zero(Currency.CNY),
                    totalFees = Money.zero(Currency.CNY),
                    cumulativeBuyCost = Money.fromDecimalString("50000.00", Currency.CNY),
                    cumulativeSellProceeds = Money.zero(Currency.CNY),
                    buyCount = 1,
                    sellCount = 0,
                    dividendCount = 0,
                ),
                baseCurrency = Currency.CNY,
                exchangeRateProvider = rates,
                asOfEpochMs = fixedTimestampMs,
            )

            val wealthPos = PortfolioPositionEngine.evaluatePositionFromState(
                asset = InvestmentAsset(
                    id = "snap-asset-wealth",
                    accountId = wealthHolding.id,
                    assetClass = AssetClass.BANK_WEALTH,
                    symbol = "BW-01",
                    name = "稳健日开理财",
                    currency = Currency.CNY,
                ),
                ledgerState = PositionLedgerState(
                    assetId = "snap-asset-wealth",
                    currency = Currency.CNY,
                    holdingQuantity = Quantity.of("20000"),
                    remainingCostBasis = Money.fromDecimalString("20000.00", Currency.CNY),
                    averageCostPrice = UnitPrice.of("1"),
                    realizedPnL = Money.zero(Currency.CNY),
                    totalDividends = Money.zero(Currency.CNY),
                    totalDividendTax = Money.zero(Currency.CNY),
                    totalTradeFees = Money.zero(Currency.CNY),
                    totalFees = Money.zero(Currency.CNY),
                    cumulativeBuyCost = Money.fromDecimalString("20000.00", Currency.CNY),
                    cumulativeSellProceeds = Money.zero(Currency.CNY),
                    buyCount = 1,
                    sellCount = 0,
                    dividendCount = 0,
                ),
                marketQuote = MarketQuote(
                    assetId = "snap-asset-wealth",
                    latestPrice = UnitPrice.of("1.0285"),
                    priceCurrency = Currency.CNY,
                    sourceProvider = "manual",
                    isManualOverride = true,
                    fetchedAt = fixedTimestampMs,
                ),
                baseCurrency = Currency.CNY,
                exchangeRateProvider = rates,
                asOfEpochMs = fixedTimestampMs,
            )

            val summary = PortfolioSummaryAggregator.aggregate(
                accounts = listOf(
                    debitCard,
                    creditCard,
                    usdCash,
                    etfHolding,
                    btcHolding,
                    hkHolding,
                    fdHolding,
                    wealthHolding,
                    housingFund,
                ),
                accountBalancesById = mapOf(
                    debitCard.id to Money.fromDecimalString("42800.00", Currency.CNY),
                    creditCard.id to Money.fromDecimalString("-12500.00", Currency.CNY),
                    usdCash.id to Money.fromDecimalString("1500.00", Currency.USD),
                    housingFund.id to Money.fromDecimalString("68400.00", Currency.CNY),
                ),
                positions = listOf(etfPos, btcPos, hkPos, fdPos, wealthPos),
                baseCurrency = Currency.CNY,
                exchangeRateProvider = rates,
            )

            return AssetsUiState(
                displayCurrency = Currency.CNY,
                summary = summary,
                lastUpdatedAtEpochMs = fixedTimestampMs,
                isLoading = false,
            )
        }

        private fun sampleInvestmentDetailUiState(): InvestmentDetailUiState = InvestmentDetailUiState(
            assetId = "snap-asset-btc",
            position = sampleBtcPosition(),
            transactions = listOf(
                InvestmentTransactionItem(
                    id = "hist-1",
                    occurredAt = fixedTimestampMs,
                    dateTimeLabel = "2026-11-27 12:30",
                    kind = InvestmentHistoryKind.DCA,
                    quantity = Quantity.of("0.02345678"),
                    unitPrice = UnitPrice.of("89500.00"),
                    amount = Money.fromDecimalString("2099.38", Currency.USD),
                    fee = Money.fromDecimalString("2.10", Currency.USD),
                    cashAccountName = "美元资金户",
                    note = "周定投自动买入",
                ),
                InvestmentTransactionItem(
                    id = "hist-2",
                    occurredAt = fixedTimestampMs - 7 * 86_400_000L,
                    dateTimeLabel = "2026-11-20 12:30",
                    kind = InvestmentHistoryKind.SELL,
                    quantity = Quantity.of("0.03000000"),
                    unitPrice = UnitPrice.of("74000.00"),
                    amount = Money.fromDecimalString("2220.00", Currency.USD),
                    fee = Money.fromDecimalString("4.40", Currency.USD),
                    cashAccountName = "美元资金户",
                    note = "止盈部分减仓",
                ),
                InvestmentTransactionItem(
                    id = "hist-3",
                    occurredAt = fixedTimestampMs - 30 * 86_400_000L,
                    dateTimeLabel = "2026-10-28 12:30",
                    kind = InvestmentHistoryKind.BUY,
                    quantity = Quantity.of("0.13000000"),
                    unitPrice = UnitPrice.of("64235.54"),
                    amount = Money.fromDecimalString("8350.62", Currency.USD),
                    fee = Money.fromDecimalString("12.00", Currency.USD),
                    cashAccountName = "美元资金户",
                    note = "建仓买入",
                ),
            ),
            matchingCashAccounts = listOf(sampleOperationalAccounts.first { it.id == "sample:usd_cash" }),
            isLoading = false,
        )

        private fun sampleMarketSyncSettingsUiState(): MarketSyncSettingsUiState = MarketSyncSettingsUiState(
            refreshInterval = MarketSyncInterval.HOURS_12,
            wifiOnly = true,
            savedProxyConfig = NetworkProxyConfig(
                type = NetworkProxyType.HTTP,
                host = "127.0.0.1",
                port = 7890,
            ),
            draftProxyType = NetworkProxyType.HTTP,
            draftProxyHost = "127.0.0.1",
            draftProxyPortText = "7890",
            lastUpdatedAtEpochMs = fixedTimestampMs,
            cachedRateCount = 6,
            cachedQuoteCount = 5,
            lastRefreshSummary = "汇率已更新 · 更新 4 项行情",
        )
    }
}
