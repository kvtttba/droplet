package io.github.kvtttba.droplet.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.kvtttba.droplet.BuildConfig
import io.github.kvtttba.droplet.core.domain.automation.BudgetProgress
import io.github.kvtttba.droplet.core.security.AppLockState
import io.github.kvtttba.droplet.core.security.AppThemePreference
import io.github.kvtttba.droplet.core.security.PrivacyPreferences
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.ThemeMode
import io.github.kvtttba.droplet.feature.aicapture.AiCaptureRoute
import io.github.kvtttba.droplet.feature.aicapture.AiCaptureScreen
import io.github.kvtttba.droplet.feature.aicapture.AiCaptureTab
import io.github.kvtttba.droplet.feature.aicapture.AiCaptureUiState
import io.github.kvtttba.droplet.feature.aicapture.AiCaptureViewModel
import io.github.kvtttba.droplet.feature.aicapture.AiDraftReviewRoute
import io.github.kvtttba.droplet.feature.aicapture.AiDraftReviewScreen
import io.github.kvtttba.droplet.feature.aicapture.AiDraftReviewUiState
import io.github.kvtttba.droplet.feature.aicapture.AiDraftReviewViewModel
import io.github.kvtttba.droplet.feature.aicapture.PendingAiSharePayload
import io.github.kvtttba.droplet.feature.aicapture.QuickCaptureModalSheet
import io.github.kvtttba.droplet.feature.aicapture.QuickCaptureMode
import io.github.kvtttba.droplet.feature.assets.AssetsRoute
import io.github.kvtttba.droplet.feature.assets.AssetsScreen
import io.github.kvtttba.droplet.feature.assets.AssetsUiState
import io.github.kvtttba.droplet.feature.assets.CreditCardDetailRoute
import io.github.kvtttba.droplet.feature.assets.CreditCardDetailScreen
import io.github.kvtttba.droplet.feature.assets.CreditCardDetailUiState
import io.github.kvtttba.droplet.feature.assets.CreditCardDetailViewModel
import io.github.kvtttba.droplet.feature.assets.DebtDetailRoute
import io.github.kvtttba.droplet.feature.assets.DebtDetailScreen
import io.github.kvtttba.droplet.feature.assets.DebtDetailUiState
import io.github.kvtttba.droplet.feature.assets.DebtDetailViewModel
import io.github.kvtttba.droplet.feature.assets.InvestmentDetailRoute
import io.github.kvtttba.droplet.feature.assets.InvestmentDetailScreen
import io.github.kvtttba.droplet.feature.assets.InvestmentDetailUiState
import io.github.kvtttba.droplet.feature.assets.InvestmentDetailViewModel
import io.github.kvtttba.droplet.feature.assets.LoanDetailRoute
import io.github.kvtttba.droplet.feature.assets.LoanDetailScreen
import io.github.kvtttba.droplet.feature.assets.LoanDetailUiState
import io.github.kvtttba.droplet.feature.assets.LoanDetailViewModel
import io.github.kvtttba.droplet.feature.assets.ReimbursementListRoute
import io.github.kvtttba.droplet.feature.assets.ReimbursementListScreen
import io.github.kvtttba.droplet.feature.assets.ReimbursementListUiState
import io.github.kvtttba.droplet.feature.ledger.LedgerRoute
import io.github.kvtttba.droplet.feature.ledger.LedgerScreen
import io.github.kvtttba.droplet.feature.ledger.LedgerUiState
import io.github.kvtttba.droplet.feature.ledger.editor.TransactionEditorRoute
import io.github.kvtttba.droplet.feature.ledger.editor.TransactionEditorViewModel
import io.github.kvtttba.droplet.feature.ledger.manage.AccountManagementRoute
import io.github.kvtttba.droplet.feature.ledger.manage.CategoryTagManagementRoute
import io.github.kvtttba.droplet.feature.settings.AiSettingsRoute
import io.github.kvtttba.droplet.feature.settings.AiSettingsScreen
import io.github.kvtttba.droplet.feature.settings.AiSettingsUiState
import io.github.kvtttba.droplet.feature.settings.BudgetManagementRoute
import io.github.kvtttba.droplet.feature.settings.BudgetManagementScreen
import io.github.kvtttba.droplet.feature.settings.BudgetManagementUiState
import io.github.kvtttba.droplet.feature.settings.CreditCardReminderSettingsRoute
import io.github.kvtttba.droplet.feature.settings.CreditCardReminderSettingsScreen
import io.github.kvtttba.droplet.feature.settings.CreditCardReminderSettingsUiState
import io.github.kvtttba.droplet.feature.settings.DataExportRoute
import io.github.kvtttba.droplet.feature.settings.DataExportScreen
import io.github.kvtttba.droplet.feature.settings.DataExportUiState
import io.github.kvtttba.droplet.feature.settings.MarketSyncSettingsRoute
import io.github.kvtttba.droplet.feature.settings.MarketSyncSettingsScreen
import io.github.kvtttba.droplet.feature.settings.MarketSyncSettingsUiState
import io.github.kvtttba.droplet.feature.settings.PrivacySecurityRoute
import io.github.kvtttba.droplet.feature.settings.PrivacySecurityScreen
import io.github.kvtttba.droplet.feature.settings.PrivacySecurityUiState
import io.github.kvtttba.droplet.feature.settings.RecurringRulesRoute
import io.github.kvtttba.droplet.feature.settings.RecurringRulesScreen
import io.github.kvtttba.droplet.feature.settings.RecurringRulesUiState
import io.github.kvtttba.droplet.feature.settings.SettingsScreen
import io.github.kvtttba.droplet.feature.settings.TemplateManagementRoute
import io.github.kvtttba.droplet.feature.settings.TemplateManagementScreen
import io.github.kvtttba.droplet.feature.settings.TemplateManagementUiState
import io.github.kvtttba.droplet.feature.stats.StatsRoute
import io.github.kvtttba.droplet.feature.stats.StatsScreen
import io.github.kvtttba.droplet.feature.stats.StatsUiState
import io.github.kvtttba.droplet.notification.PlanningAlertNotificationPublisher
import kotlinx.coroutines.launch

enum class DropletTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    LEDGER(
        route = "ledger",
        label = "明细",
        selectedIcon = DropletIcons.ReceiptLong,
        unselectedIcon = DropletIcons.ReceiptLongOutlined,
    ),
    ASSETS(
        route = "assets",
        label = "资产",
        selectedIcon = DropletIcons.AccountBalanceWallet,
        unselectedIcon = DropletIcons.AccountBalanceWalletOutlined,
    ),
    STATS(
        route = "stats",
        label = "统计",
        selectedIcon = DropletIcons.BarChart,
        unselectedIcon = DropletIcons.BarChartOutlined,
    ),
    SETTINGS(
        route = "settings",
        label = "我的",
        selectedIcon = Icons.Rounded.Person,
        unselectedIcon = Icons.Outlined.Person,
    ),
}

object DropletRoutes {
    const val TRANSACTION_EDITOR_PATTERN =
        "transaction_editor?transactionId={transactionId}&${TransactionEditorViewModel.NAV_ARG_TEMPLATE_ID}={${TransactionEditorViewModel.NAV_ARG_TEMPLATE_ID}}"
    const val INVESTMENT_DETAIL_PATTERN = "investment_detail/{${InvestmentDetailViewModel.NAV_ARG_ASSET_ID}}"
    const val CREDIT_CARD_DETAIL_PATTERN = "credit_card_detail/{${CreditCardDetailViewModel.NAV_ARG_ACCOUNT_ID}}"
    const val LOAN_DETAIL_PATTERN = "loan_detail/{${LoanDetailViewModel.NAV_ARG_PLAN_ID}}"
    const val DEBT_DETAIL_PATTERN = "debt_detail/{${DebtDetailViewModel.NAV_ARG_PROFILE_ID}}"
    const val REIMBURSEMENT_LIST = "reimbursement_list"
    const val ACCOUNT_MANAGEMENT = "manage_accounts"
    const val CATEGORY_TAG_MANAGEMENT = "manage_categories_tags"
    const val PRIVACY_SECURITY = "privacy_security"
    const val MARKET_SYNC_SETTINGS = "market_sync_settings"
    const val CREDIT_CARD_REMINDER_SETTINGS = "credit_card_reminder_settings"
    const val AI_SETTINGS = "ai_settings"
    const val BUDGET_MANAGEMENT = "budget_management"
    const val RECURRING_RULES = "recurring_rules"
    const val TEMPLATE_MANAGEMENT = "template_management"
    const val DATA_EXPORT = "data_export"
    const val AI_CAPTURE_PATTERN = "ai_capture?${AiCaptureViewModel.NAV_ARG_TAB}={${AiCaptureViewModel.NAV_ARG_TAB}}"
    const val AI_DRAFT_REVIEW_PATTERN = "ai_draft_review/{${AiDraftReviewViewModel.NAV_ARG_DRAFT_ID}}"

    fun transactionEditor(
        transactionId: String? = null,
        templateId: String? = null,
    ): String {
        val params = buildList {
            if (!transactionId.isNullOrBlank()) {
                add("transactionId=${Uri.encode(transactionId)}")
            }
            if (!templateId.isNullOrBlank()) {
                add("${TransactionEditorViewModel.NAV_ARG_TEMPLATE_ID}=${Uri.encode(templateId)}")
            }
        }
        return if (params.isEmpty()) {
            "transaction_editor"
        } else {
            "transaction_editor?${params.joinToString("&")}"
        }
    }

    fun investmentDetail(assetId: String): String =
        "investment_detail/${Uri.encode(assetId)}"

    fun creditCardDetail(accountId: String): String =
        "credit_card_detail/${Uri.encode(accountId)}"

    fun loanDetail(planId: String): String =
        "loan_detail/${Uri.encode(planId)}"

    fun debtDetail(profileId: String): String =
        "debt_detail/${Uri.encode(profileId)}"

    fun aiCapture(tab: AiCaptureTab = AiCaptureTab.TEXT): String =
        "ai_capture?${AiCaptureViewModel.NAV_ARG_TAB}=${Uri.encode(tab.routeKey)}"

    fun aiDraftReview(draftId: String): String =
        "ai_draft_review/${Uri.encode(draftId)}"
}

fun AppThemePreference.toThemeMode(): ThemeMode = when (this) {
    AppThemePreference.SYSTEM -> ThemeMode.SYSTEM
    AppThemePreference.LIGHT -> ThemeMode.LIGHT
    AppThemePreference.DARK -> ThemeMode.DARK
}

fun ThemeMode.toAppThemePreference(): AppThemePreference = when (this) {
    ThemeMode.SYSTEM -> AppThemePreference.SYSTEM
    ThemeMode.LIGHT -> AppThemePreference.LIGHT
    ThemeMode.DARK -> AppThemePreference.DARK
}

/**
 * Production runtime application root wired to Hilt ViewModels, Room database,
 * DataStore privacy/sync preferences, and biometric lock overlay.
 */
@Composable
fun DropletApp(
    preferences: PrivacyPreferences = PrivacyPreferences(),
    isPreferencesHydrated: Boolean = true,
    lockState: AppLockState = AppLockState(),
    marketSyncSummary: String = "每 12 小时 · 直连",
    creditCardReminderSummary: String = "已开启 · 默认提前 3 天提醒",
    aiConfigSummary: String = "未配置 · 点击设置 OpenAI Compatible 接口",
    budgetSummary: String = "设置月/年总预算与分类消耗预警",
    recurringSummary: String = "房租、工资与订阅自动入账或到期提醒",
    templateSummary: String = "高频消费一键入账或预填表单",
    monthlyBudgetProgress: BudgetProgress? = null,
    pendingAiDraftCount: Int = 0,
    pendingCreditCardAccountId: String? = null,
    onConsumePendingCreditCardAccountId: () -> Unit = {},
    pendingLoanPlanId: String? = null,
    onConsumePendingLoanPlanId: () -> Unit = {},
    pendingDebtProfileId: String? = null,
    onConsumePendingDebtProfileId: () -> Unit = {},
    pendingAiSharePayload: PendingAiSharePayload? = null,
    onConsumePendingAiSharePayload: () -> Unit = {},
    pendingPlanningDestination: String? = null,
    onConsumePendingPlanningDestination: () -> Unit = {},
    initialTab: DropletTab = DropletTab.LEDGER,
    initialShowQuickCapture: Boolean = false,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onDynamicColorChange: (Boolean) -> Unit = {},
    onToggleAmountsHidden: () -> Unit = {},
    onRequestUnlock: () -> Unit = {},
    onVerifyIdentityForEnable: ((Boolean) -> Unit) -> Unit = { it(false) },
    onLockOverlayBackPressed: () -> Unit = {},
) {
    val themeMode = preferences.themeMode.toThemeMode()
    if (!isPreferencesHydrated) {
        DropletTheme(
            themeMode = themeMode,
            dynamicColor = preferences.dynamicColor,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {}
        }
        return
    }

    DropletAppContent(
        initialTab = initialTab,
        themeMode = themeMode,
        dynamicColor = preferences.dynamicColor,
        amountsHidden = preferences.amountsHidden,
        biometricLockEnabled = preferences.biometricLockEnabled,
        flagSecureEnabled = preferences.flagSecureEnabled,
        marketSyncSummary = marketSyncSummary,
        creditCardReminderSummary = creditCardReminderSummary,
        aiConfigSummary = aiConfigSummary,
        budgetSummary = budgetSummary,
        recurringSummary = recurringSummary,
        templateSummary = templateSummary,
        monthlyBudgetProgress = monthlyBudgetProgress,
        pendingAiDraftCount = pendingAiDraftCount,
        pendingCreditCardAccountId = pendingCreditCardAccountId,
        onConsumePendingCreditCardAccountId = onConsumePendingCreditCardAccountId,
        pendingLoanPlanId = pendingLoanPlanId,
        onConsumePendingLoanPlanId = onConsumePendingLoanPlanId,
        pendingDebtProfileId = pendingDebtProfileId,
        onConsumePendingDebtProfileId = onConsumePendingDebtProfileId,
        pendingAiSharePayload = pendingAiSharePayload,
        onConsumePendingAiSharePayload = onConsumePendingAiSharePayload,
        pendingPlanningDestination = pendingPlanningDestination,
        onConsumePendingPlanningDestination = onConsumePendingPlanningDestination,
        isAppLocked = lockState.isOverlayVisible,
        isAuthenticating = lockState.isAuthenticating,
        lockErrorMessage = lockState.lastErrorMessage,
        initialShowQuickCapture = initialShowQuickCapture,
        versionLabel = "v${BuildConfig.VERSION_NAME}",
        onThemeModeChange = onThemeModeChange,
        onDynamicColorChange = onDynamicColorChange,
        onToggleAmountsHidden = onToggleAmountsHidden,
        onRequestUnlock = onRequestUnlock,
        onLockOverlayBackPressed = onLockOverlayBackPressed,
        ledgerContent = { onToggleHidden, onCreateTx, onEditTx, onOpenReimbursements, onOpenBudgets, onOpenTemplates, onShowMessage ->
            LedgerRoute(
                onToggleAmountsHidden = onToggleHidden,
                onCreateTransaction = onCreateTx,
                onEditTransaction = onEditTx,
                onOpenReimbursementList = onOpenReimbursements,
                onOpenBudgetManagement = onOpenBudgets,
                onOpenTemplateManagement = onOpenTemplates,
                onShowMessage = onShowMessage,
            )
        },
        assetsContent = { onToggleHidden, onOpenDetail, onOpenCreditCard, onOpenLoan, onOpenDebt, onOpenReimbursements, onOpenSyncSettings, onShowMessage ->
            AssetsRoute(
                onToggleAmountsHidden = onToggleHidden,
                onOpenInvestmentDetail = onOpenDetail,
                onOpenCreditCardDetail = onOpenCreditCard,
                onOpenLoanDetail = onOpenLoan,
                onOpenDebtDetail = onOpenDebt,
                onOpenReimbursementList = onOpenReimbursements,
                onOpenMarketSyncSettings = onOpenSyncSettings,
                onShowMessage = onShowMessage,
            )
        },
        investmentDetailContent = { onBack, onToggleHidden, onShowMessage ->
            InvestmentDetailRoute(
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
                onShowMessage = onShowMessage,
            )
        },
        creditCardDetailContent = { onBack, onToggleHidden, onShowMessage ->
            CreditCardDetailRoute(
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
                onShowMessage = onShowMessage,
            )
        },
        loanDetailContent = { onBack, onToggleHidden, onShowMessage ->
            LoanDetailRoute(
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
                onShowMessage = onShowMessage,
            )
        },
        debtDetailContent = { onBack, onToggleHidden, onShowMessage ->
            DebtDetailRoute(
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
                onShowMessage = onShowMessage,
            )
        },
        reimbursementListContent = { onBack, onToggleHidden, onShowMessage ->
            ReimbursementListRoute(
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
                onShowMessage = onShowMessage,
            )
        },
        editorContent = { onBack, onSaved ->
            TransactionEditorRoute(
                onNavigateBack = onBack,
                onSaved = onSaved,
            )
        },
        accountManageContent = { onBack, onShowMessage ->
            AccountManagementRoute(
                onNavigateBack = onBack,
                onShowMessage = onShowMessage,
            )
        },
        categoryTagManageContent = { onBack, onShowMessage ->
            CategoryTagManagementRoute(
                onNavigateBack = onBack,
                onShowMessage = onShowMessage,
            )
        },
        privacySecurityContent = { onBack, onShowMessage ->
            PrivacySecurityRoute(
                onNavigateBack = onBack,
                onVerifyIdentityForEnable = onVerifyIdentityForEnable,
                onShowMessage = onShowMessage,
            )
        },
        marketSyncSettingsContent = { onBack, onShowMessage ->
            MarketSyncSettingsRoute(
                onNavigateBack = onBack,
                onShowMessage = onShowMessage,
            )
        },
        creditCardReminderSettingsContent = { onBack, onOpenCardDetail, onShowMessage ->
            CreditCardReminderSettingsRoute(
                onNavigateBack = onBack,
                onOpenCreditCardDetail = onOpenCardDetail,
                onShowMessage = onShowMessage,
            )
        },
        aiSettingsContent = { onBack, onShowMessage ->
            AiSettingsRoute(
                onNavigateBack = onBack,
                onShowMessage = onShowMessage,
            )
        },
        aiCaptureContent = { onBack, onOpenDraftReview, onOpenAiSettings, onShowMessage ->
            AiCaptureRoute(
                onNavigateBack = onBack,
                onOpenDraftReview = onOpenDraftReview,
                onOpenAiSettings = onOpenAiSettings,
                onShowMessage = onShowMessage,
                pendingSharePayload = pendingAiSharePayload,
                onConsumePendingSharePayload = onConsumePendingAiSharePayload,
            )
        },
        aiDraftReviewContent = { onBack, onCommitted, onToggleHidden, onShowMessage ->
            AiDraftReviewRoute(
                onNavigateBack = onBack,
                onCommitted = onCommitted,
                onToggleAmountsHidden = onToggleHidden,
                onShowMessage = onShowMessage,
            )
        },
        budgetManagementContent = { onBack, onToggleHidden, onShowMessage ->
            BudgetManagementRoute(
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
                onShowMessage = onShowMessage,
            )
        },
        recurringRulesContent = { onBack, onToggleHidden, onShowMessage ->
            RecurringRulesRoute(
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
                onShowMessage = onShowMessage,
            )
        },
        templateManagementContent = { onBack, onToggleHidden, onPrefillEditor, onShowMessage ->
            TemplateManagementRoute(
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
                onOpenEditorWithTemplate = onPrefillEditor,
                onShowMessage = onShowMessage,
            )
        },
        statsContent = { onToggleHidden, onOpenBudgets, onOpenExport, onEditTx ->
            StatsRoute(
                onToggleAmountsHidden = onToggleHidden,
                onOpenBudgetManagement = onOpenBudgets,
                onOpenDataExport = onOpenExport,
                onEditTransaction = onEditTx,
            )
        },
        dataExportContent = { onBack, onShowMessage ->
            DataExportRoute(
                onNavigateBack = onBack,
                onShowMessage = onShowMessage,
            )
        },
    )
}

/**
 * Stateless/slot-injected shell used by pure Compose UI and Roborazzi snapshot tests
 * without requiring Hilt application initialization.
 */
@Composable
fun DropletAppShell(
    initialTab: DropletTab = DropletTab.LEDGER,
    initialThemeMode: ThemeMode = ThemeMode.SYSTEM,
    initialDynamicColor: Boolean = true,
    initialAmountsHidden: Boolean = false,
    initialBiometricLockEnabled: Boolean = false,
    initialFlagSecureEnabled: Boolean = false,
    initialIsLocked: Boolean = false,
    initialShowQuickCapture: Boolean = false,
    ledgerPreviewState: LedgerUiState? = null,
    assetsPreviewState: AssetsUiState? = null,
    statsPreviewState: StatsUiState? = null,
    investmentDetailPreviewState: InvestmentDetailUiState? = null,
    creditCardDetailPreviewState: CreditCardDetailUiState? = null,
    loanDetailPreviewState: LoanDetailUiState? = null,
    debtDetailPreviewState: DebtDetailUiState? = null,
    reimbursementListPreviewState: ReimbursementListUiState? = null,
    marketSyncPreviewState: MarketSyncSettingsUiState? = null,
    creditCardReminderSettingsPreviewState: CreditCardReminderSettingsUiState? = null,
    aiSettingsPreviewState: AiSettingsUiState? = null,
    aiCapturePreviewState: AiCaptureUiState? = null,
    aiDraftReviewPreviewState: AiDraftReviewUiState? = null,
    budgetManagementPreviewState: BudgetManagementUiState? = null,
    recurringRulesPreviewState: RecurringRulesUiState? = null,
    templateManagementPreviewState: TemplateManagementUiState? = null,
    dataExportPreviewState: DataExportUiState? = null,
    initialPendingAiDraftCount: Int = 0,
    ledgerContent: @Composable (
        onToggleAmountsHidden: () -> Unit,
        onCreateTransaction: () -> Unit,
        onEditTransaction: (String) -> Unit,
        onOpenReimbursementList: () -> Unit,
        onOpenBudgetManagement: () -> Unit,
        onOpenTemplateManagement: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit = { onToggleHidden, onCreateTx, onEditTx, onOpenReimbursements, onOpenBudgets, onOpenTemplates, _ ->
        LedgerScreen(
            uiState = ledgerPreviewState ?: LedgerUiState(),
            onToggleAmountsHidden = onToggleHidden,
            onCreateTransaction = onCreateTx,
            onEditTransaction = onEditTx,
            onOpenReimbursementList = onOpenReimbursements,
            onOpenBudgetManagement = onOpenBudgets,
            onOpenTemplateManagement = onOpenTemplates,
        )
    },
    assetsContent: @Composable (
        onToggleAmountsHidden: () -> Unit,
        onOpenInvestmentDetail: (String) -> Unit,
        onOpenCreditCardDetail: (String) -> Unit,
        onOpenLoanDetail: (String) -> Unit,
        onOpenDebtDetail: (String) -> Unit,
        onOpenReimbursementList: () -> Unit,
        onOpenMarketSyncSettings: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit = { onToggleHidden, onOpenDetail, onOpenCreditCard, onOpenLoan, onOpenDebt, onOpenReimbursements, onOpenSyncSettings, _ ->
        AssetsScreen(
            uiState = assetsPreviewState ?: AssetsUiState(isLoading = false),
            onToggleAmountsHidden = onToggleHidden,
            onOpenInvestmentDetail = onOpenDetail,
            onOpenCreditCardDetail = onOpenCreditCard,
            onOpenLoanDetail = onOpenLoan,
            onOpenDebtDetail = onOpenDebt,
            onOpenReimbursementList = onOpenReimbursements,
            onOpenMarketSyncSettings = onOpenSyncSettings,
        )
    },
    editorContent: @Composable (
        onNavigateBack: () -> Unit,
        onSaved: (String) -> Unit,
    ) -> Unit = { _, _ -> },
    accountManageContent: @Composable (
        onNavigateBack: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit = { _, _ -> },
    categoryTagManageContent: @Composable (
        onNavigateBack: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit = { _, _ -> },
) {
    var themeMode by rememberSaveable { mutableStateOf(initialThemeMode) }
    var dynamicColor by rememberSaveable { mutableStateOf(initialDynamicColor) }
    var amountsHidden by rememberSaveable { mutableStateOf(initialAmountsHidden) }
    var biometricLockEnabled by rememberSaveable { mutableStateOf(initialBiometricLockEnabled) }
    var flagSecureEnabled by rememberSaveable { mutableStateOf(initialFlagSecureEnabled) }
    var isLocked by rememberSaveable { mutableStateOf(initialIsLocked) }

    DropletAppContent(
        initialTab = initialTab,
        themeMode = themeMode,
        dynamicColor = dynamicColor,
        amountsHidden = amountsHidden,
        biometricLockEnabled = biometricLockEnabled,
        flagSecureEnabled = flagSecureEnabled,
        marketSyncSummary = "每 12 小时 · 直连",
        creditCardReminderSummary = "已开启 · 默认提前 3 天提醒",
        aiConfigSummary = aiSettingsPreviewState?.savedConfig?.formatSummary()
            ?: "未配置 · 点击设置 OpenAI Compatible 接口",
        budgetSummary = "设置月/年总预算与分类消耗预警",
        recurringSummary = "房租、工资与订阅自动入账或到期提醒",
        templateSummary = "高频消费一键入账或预填表单",
        monthlyBudgetProgress = null,
        pendingAiDraftCount = initialPendingAiDraftCount,
        pendingCreditCardAccountId = null,
        onConsumePendingCreditCardAccountId = {},
        pendingLoanPlanId = null,
        onConsumePendingLoanPlanId = {},
        pendingDebtProfileId = null,
        onConsumePendingDebtProfileId = {},
        pendingAiSharePayload = null,
        onConsumePendingAiSharePayload = {},
        pendingPlanningDestination = null,
        onConsumePendingPlanningDestination = {},
        isAppLocked = isLocked,
        isAuthenticating = false,
        lockErrorMessage = null,
        initialShowQuickCapture = initialShowQuickCapture,
        versionLabel = "v${BuildConfig.VERSION_NAME}",
        onThemeModeChange = { themeMode = it },
        onDynamicColorChange = { dynamicColor = it },
        onToggleAmountsHidden = { amountsHidden = !amountsHidden },
        onRequestUnlock = { isLocked = false },
        onLockOverlayBackPressed = {},
        ledgerContent = ledgerContent,
        assetsContent = assetsContent,
        investmentDetailContent = { onBack, onToggleHidden, _ ->
            InvestmentDetailScreen(
                uiState = investmentDetailPreviewState ?: InvestmentDetailUiState(isLoading = false),
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
            )
        },
        creditCardDetailContent = { onBack, onToggleHidden, _ ->
            CreditCardDetailScreen(
                uiState = creditCardDetailPreviewState ?: CreditCardDetailUiState(isLoading = false),
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
            )
        },
        loanDetailContent = { onBack, onToggleHidden, _ ->
            LoanDetailScreen(
                uiState = loanDetailPreviewState ?: LoanDetailUiState(isLoading = false),
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
            )
        },
        debtDetailContent = { onBack, onToggleHidden, _ ->
            DebtDetailScreen(
                uiState = debtDetailPreviewState ?: DebtDetailUiState(isLoading = false),
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
            )
        },
        reimbursementListContent = { onBack, onToggleHidden, _ ->
            ReimbursementListScreen(
                uiState = reimbursementListPreviewState ?: ReimbursementListUiState(isLoading = false),
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
            )
        },
        editorContent = editorContent,
        accountManageContent = accountManageContent,
        categoryTagManageContent = categoryTagManageContent,
        privacySecurityContent = { onBack, _ ->
            PrivacySecurityScreen(
                uiState = PrivacySecurityUiState(
                    biometricLockEnabled = biometricLockEnabled,
                    flagSecureEnabled = flagSecureEnabled,
                    amountsHidden = amountsHidden,
                ),
                onNavigateBack = onBack,
                onToggleBiometricLock = { biometricLockEnabled = it },
                onToggleFlagSecure = { flagSecureEnabled = it },
                onToggleAmountsHidden = { amountsHidden = it },
                onLockNow = { isLocked = true },
            )
        },
        marketSyncSettingsContent = { onBack, _ ->
            MarketSyncSettingsScreen(
                uiState = marketSyncPreviewState ?: MarketSyncSettingsUiState(),
                onNavigateBack = onBack,
            )
        },
        creditCardReminderSettingsContent = { onBack, onOpenCardDetail, _ ->
            CreditCardReminderSettingsScreen(
                uiState = creditCardReminderSettingsPreviewState ?: CreditCardReminderSettingsUiState(),
                onNavigateBack = onBack,
                onOpenCreditCardDetail = onOpenCardDetail,
            )
        },
        aiSettingsContent = { onBack, _ ->
            AiSettingsScreen(
                uiState = aiSettingsPreviewState ?: AiSettingsUiState(),
                onNavigateBack = onBack,
            )
        },
        aiCaptureContent = { onBack, onOpenDraftReview, onOpenAiSettings, _ ->
            AiCaptureScreen(
                uiState = aiCapturePreviewState ?: AiCaptureUiState(),
                onNavigateBack = onBack,
                onOpenDraftReview = onOpenDraftReview,
                onOpenAiSettings = onOpenAiSettings,
            )
        },
        aiDraftReviewContent = { onBack, _, onToggleHidden, _ ->
            AiDraftReviewScreen(
                uiState = aiDraftReviewPreviewState ?: AiDraftReviewUiState(isLoading = false),
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
            )
        },
        budgetManagementContent = { onBack, onToggleHidden, _ ->
            BudgetManagementScreen(
                uiState = budgetManagementPreviewState ?: BudgetManagementUiState(),
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
            )
        },
        recurringRulesContent = { onBack, onToggleHidden, _ ->
            RecurringRulesScreen(
                uiState = recurringRulesPreviewState ?: RecurringRulesUiState(),
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
            )
        },
        templateManagementContent = { onBack, onToggleHidden, onPrefillEditor, _ ->
            TemplateManagementScreen(
                uiState = templateManagementPreviewState ?: TemplateManagementUiState(),
                onNavigateBack = onBack,
                onToggleAmountsHidden = onToggleHidden,
                onPrefillEditor = onPrefillEditor,
            )
        },
        statsContent = { onToggleHidden, onOpenBudgets, onOpenExport, onEditTx ->
            StatsScreen(
                uiState = statsPreviewState ?: StatsUiState(isLoading = false),
                onToggleAmountsHidden = onToggleHidden,
                onOpenBudgetManagement = onOpenBudgets,
                onOpenDataExport = onOpenExport,
                onEditTransaction = onEditTx,
            )
        },
        dataExportContent = { onBack, _ ->
            DataExportScreen(
                uiState = dataExportPreviewState ?: DataExportUiState(isLoadingPreview = false),
                onNavigateBack = onBack,
            )
        },
    )
}

@Composable
private fun DropletAppContent(
    initialTab: DropletTab,
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    amountsHidden: Boolean,
    biometricLockEnabled: Boolean,
    flagSecureEnabled: Boolean,
    marketSyncSummary: String,
    creditCardReminderSummary: String,
    aiConfigSummary: String,
    budgetSummary: String,
    recurringSummary: String,
    templateSummary: String,
    monthlyBudgetProgress: BudgetProgress?,
    pendingAiDraftCount: Int,
    pendingCreditCardAccountId: String?,
    onConsumePendingCreditCardAccountId: () -> Unit,
    pendingLoanPlanId: String?,
    onConsumePendingLoanPlanId: () -> Unit,
    pendingDebtProfileId: String?,
    onConsumePendingDebtProfileId: () -> Unit,
    pendingAiSharePayload: PendingAiSharePayload?,
    onConsumePendingAiSharePayload: () -> Unit,
    pendingPlanningDestination: String?,
    onConsumePendingPlanningDestination: () -> Unit,
    isAppLocked: Boolean,
    isAuthenticating: Boolean,
    lockErrorMessage: String?,
    initialShowQuickCapture: Boolean,
    versionLabel: String,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onToggleAmountsHidden: () -> Unit,
    onRequestUnlock: () -> Unit,
    onLockOverlayBackPressed: () -> Unit,
    ledgerContent: @Composable (
        onToggleAmountsHidden: () -> Unit,
        onCreateTransaction: () -> Unit,
        onEditTransaction: (String) -> Unit,
        onOpenReimbursementList: () -> Unit,
        onOpenBudgetManagement: () -> Unit,
        onOpenTemplateManagement: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    assetsContent: @Composable (
        onToggleAmountsHidden: () -> Unit,
        onOpenInvestmentDetail: (String) -> Unit,
        onOpenCreditCardDetail: (String) -> Unit,
        onOpenLoanDetail: (String) -> Unit,
        onOpenDebtDetail: (String) -> Unit,
        onOpenReimbursementList: () -> Unit,
        onOpenMarketSyncSettings: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    investmentDetailContent: @Composable (
        onNavigateBack: () -> Unit,
        onToggleAmountsHidden: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    creditCardDetailContent: @Composable (
        onNavigateBack: () -> Unit,
        onToggleAmountsHidden: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    loanDetailContent: @Composable (
        onNavigateBack: () -> Unit,
        onToggleAmountsHidden: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    debtDetailContent: @Composable (
        onNavigateBack: () -> Unit,
        onToggleAmountsHidden: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    reimbursementListContent: @Composable (
        onNavigateBack: () -> Unit,
        onToggleAmountsHidden: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    editorContent: @Composable (
        onNavigateBack: () -> Unit,
        onSaved: (String) -> Unit,
    ) -> Unit,
    accountManageContent: @Composable (
        onNavigateBack: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    categoryTagManageContent: @Composable (
        onNavigateBack: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    privacySecurityContent: @Composable (
        onNavigateBack: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    marketSyncSettingsContent: @Composable (
        onNavigateBack: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    creditCardReminderSettingsContent: @Composable (
        onNavigateBack: () -> Unit,
        onOpenCreditCardDetail: (String) -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    aiSettingsContent: @Composable (
        onNavigateBack: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    aiCaptureContent: @Composable (
        onNavigateBack: () -> Unit,
        onOpenDraftReview: (String) -> Unit,
        onOpenAiSettings: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    aiDraftReviewContent: @Composable (
        onNavigateBack: () -> Unit,
        onCommitted: (String) -> Unit,
        onToggleAmountsHidden: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    budgetManagementContent: @Composable (
        onNavigateBack: () -> Unit,
        onToggleAmountsHidden: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    recurringRulesContent: @Composable (
        onNavigateBack: () -> Unit,
        onToggleAmountsHidden: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    templateManagementContent: @Composable (
        onNavigateBack: () -> Unit,
        onToggleAmountsHidden: () -> Unit,
        onOpenEditorWithTemplate: (String) -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    statsContent: @Composable (
        onToggleAmountsHidden: () -> Unit,
        onOpenBudgetManagement: () -> Unit,
        onOpenDataExport: () -> Unit,
        onEditTransaction: (String) -> Unit,
    ) -> Unit,
    dataExportContent: @Composable (
        onNavigateBack: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit,
) {
    var showQuickCaptureSheet by rememberSaveable { mutableStateOf(initialShowQuickCapture) }
    var hasUnlockedAtLeastOnce by rememberSaveable { mutableStateOf(!isAppLocked) }
    if (!isAppLocked && !hasUnlockedAtLeastOnce) {
        hasUnlockedAtLeastOnce = true
    }

    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val showMessage: (String) -> Unit = { message ->
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }
    val openTransactionEditor: (String?, String?) -> Unit = { txId, templateId ->
        snackbarHostState.currentSnackbarData?.dismiss()
        navController.navigate(DropletRoutes.transactionEditor(txId, templateId))
    }

    DropletTheme(
        themeMode = themeMode,
        dynamicColor = dynamicColor,
    ) {
        CompositionLocalProvider(LocalAmountsHidden provides amountsHidden) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (hasUnlockedAtLeastOnce) {
                    LaunchedEffect(pendingCreditCardAccountId, isAppLocked) {
                        if (!pendingCreditCardAccountId.isNullOrBlank() && !isAppLocked) {
                            navController.navigate(DropletRoutes.creditCardDetail(pendingCreditCardAccountId)) {
                                launchSingleTop = true
                            }
                            onConsumePendingCreditCardAccountId()
                        }
                    }
                    LaunchedEffect(pendingLoanPlanId, isAppLocked) {
                        if (!pendingLoanPlanId.isNullOrBlank() && !isAppLocked) {
                            navController.navigate(DropletRoutes.loanDetail(pendingLoanPlanId)) {
                                launchSingleTop = true
                            }
                            onConsumePendingLoanPlanId()
                        }
                    }
                    LaunchedEffect(pendingDebtProfileId, isAppLocked) {
                        if (!pendingDebtProfileId.isNullOrBlank() && !isAppLocked) {
                            navController.navigate(DropletRoutes.debtDetail(pendingDebtProfileId)) {
                                launchSingleTop = true
                            }
                            onConsumePendingDebtProfileId()
                        }
                    }
                    LaunchedEffect(pendingAiSharePayload?.id, isAppLocked) {
                        if (pendingAiSharePayload != null && !isAppLocked) {
                            showQuickCaptureSheet = false
                            snackbarHostState.currentSnackbarData?.dismiss()
                            navController.navigate(
                                DropletRoutes.aiCapture(pendingAiSharePayload.preferredTab),
                            ) {
                                launchSingleTop = true
                            }
                        }
                    }
                    LaunchedEffect(pendingPlanningDestination, isAppLocked) {
                        if (!pendingPlanningDestination.isNullOrBlank() && !isAppLocked) {
                            val targetRoute = when (pendingPlanningDestination) {
                                PlanningAlertNotificationPublisher.DEST_BUDGETS -> DropletRoutes.BUDGET_MANAGEMENT
                                PlanningAlertNotificationPublisher.DEST_RECURRING -> DropletRoutes.RECURRING_RULES
                                else -> null
                            }
                            if (targetRoute != null) {
                                navController.navigate(targetRoute) {
                                    launchSingleTop = true
                                }
                            }
                            onConsumePendingPlanningDestination()
                        }
                    }

                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route ?: initialTab.route
                    val isTopLevelTab = DropletTab.entries.any { it.route == currentRoute }

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = MaterialTheme.colorScheme.background,
                        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                        bottomBar = {
                            if (isTopLevelTab) {
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                                ) {
                                    DropletTab.entries.forEach { tab ->
                                        val selected = currentRoute == tab.route
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = {
                                                navController.navigate(tab.route) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            },
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                    contentDescription = tab.label,
                                                )
                                            },
                                            label = { Text(text = tab.label) },
                                            modifier = Modifier.testTag("nav_tab_${tab.route}"),
                                        )
                                    }
                                }
                            }
                        },
                        floatingActionButton = {
                            if (isTopLevelTab) {
                                FloatingActionButton(
                                    onClick = { showQuickCaptureSheet = true },
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.testTag("fab_quick_add"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = "快捷记账",
                                    )
                                }
                            }
                        },
                    ) { innerPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = initialTab.route,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        ) {
                            composable(DropletTab.LEDGER.route) {
                                ledgerContent(
                                    onToggleAmountsHidden,
                                    { openTransactionEditor(null, null) },
                                    { txId -> openTransactionEditor(txId, null) },
                                    { navController.navigate(DropletRoutes.REIMBURSEMENT_LIST) },
                                    { navController.navigate(DropletRoutes.BUDGET_MANAGEMENT) },
                                    { navController.navigate(DropletRoutes.TEMPLATE_MANAGEMENT) },
                                    showMessage,
                                )
                            }
                            composable(DropletTab.ASSETS.route) {
                                assetsContent(
                                    onToggleAmountsHidden,
                                    { assetId -> navController.navigate(DropletRoutes.investmentDetail(assetId)) },
                                    { accountId -> navController.navigate(DropletRoutes.creditCardDetail(accountId)) },
                                    { planId -> navController.navigate(DropletRoutes.loanDetail(planId)) },
                                    { profileId -> navController.navigate(DropletRoutes.debtDetail(profileId)) },
                                    { navController.navigate(DropletRoutes.REIMBURSEMENT_LIST) },
                                    { navController.navigate(DropletRoutes.MARKET_SYNC_SETTINGS) },
                                    showMessage,
                                )
                            }
                            composable(DropletTab.STATS.route) {
                                statsContent(
                                    onToggleAmountsHidden,
                                    { navController.navigate(DropletRoutes.BUDGET_MANAGEMENT) },
                                    { navController.navigate(DropletRoutes.DATA_EXPORT) },
                                    { txId -> openTransactionEditor(txId, null) },
                                )
                            }
                            composable(DropletTab.SETTINGS.route) {
                                SettingsScreen(
                                    themeMode = themeMode,
                                    dynamicColor = dynamicColor,
                                    onThemeModeChange = onThemeModeChange,
                                    onDynamicColorChange = onDynamicColorChange,
                                    onToggleAmountsHidden = onToggleAmountsHidden,
                                    onOpenAccountManagement = {
                                        navController.navigate(DropletRoutes.ACCOUNT_MANAGEMENT)
                                    },
                                    onOpenCategoryTagManagement = {
                                        navController.navigate(DropletRoutes.CATEGORY_TAG_MANAGEMENT)
                                    },
                                    onOpenPrivacySecurity = {
                                        navController.navigate(DropletRoutes.PRIVACY_SECURITY)
                                    },
                                    onOpenMarketSyncSettings = {
                                        navController.navigate(DropletRoutes.MARKET_SYNC_SETTINGS)
                                    },
                                    onOpenCreditCardReminders = {
                                        navController.navigate(DropletRoutes.CREDIT_CARD_REMINDER_SETTINGS)
                                    },
                                    onOpenAiSettings = {
                                        navController.navigate(DropletRoutes.AI_SETTINGS)
                                    },
                                    onOpenBudgetManagement = {
                                        navController.navigate(DropletRoutes.BUDGET_MANAGEMENT)
                                    },
                                    onOpenRecurringRules = {
                                        navController.navigate(DropletRoutes.RECURRING_RULES)
                                    },
                                    onOpenTemplateManagement = {
                                        navController.navigate(DropletRoutes.TEMPLATE_MANAGEMENT)
                                    },
                                    onOpenDataExport = {
                                        navController.navigate(DropletRoutes.DATA_EXPORT)
                                    },
                                    biometricLockEnabled = biometricLockEnabled,
                                    flagSecureEnabled = flagSecureEnabled,
                                    marketSyncSummary = marketSyncSummary,
                                    creditCardReminderSummary = creditCardReminderSummary,
                                    aiConfigSummary = aiConfigSummary,
                                    budgetSummary = budgetSummary,
                                    recurringSummary = recurringSummary,
                                    templateSummary = templateSummary,
                                    versionLabel = versionLabel,
                                )
                            }
                            composable(
                                route = DropletRoutes.INVESTMENT_DETAIL_PATTERN,
                                arguments = listOf(
                                    navArgument(InvestmentDetailViewModel.NAV_ARG_ASSET_ID) {
                                        type = NavType.StringType
                                    },
                                ),
                            ) {
                                investmentDetailContent(
                                    { navController.popBackStack() },
                                    onToggleAmountsHidden,
                                    showMessage,
                                )
                            }
                            composable(
                                route = DropletRoutes.CREDIT_CARD_DETAIL_PATTERN,
                                arguments = listOf(
                                    navArgument(CreditCardDetailViewModel.NAV_ARG_ACCOUNT_ID) {
                                        type = NavType.StringType
                                    },
                                ),
                            ) {
                                creditCardDetailContent(
                                    { navController.popBackStack() },
                                    onToggleAmountsHidden,
                                    showMessage,
                                )
                            }
                            composable(
                                route = DropletRoutes.LOAN_DETAIL_PATTERN,
                                arguments = listOf(
                                    navArgument(LoanDetailViewModel.NAV_ARG_PLAN_ID) {
                                        type = NavType.StringType
                                    },
                                ),
                            ) {
                                loanDetailContent(
                                    { navController.popBackStack() },
                                    onToggleAmountsHidden,
                                    showMessage,
                                )
                            }
                            composable(
                                route = DropletRoutes.DEBT_DETAIL_PATTERN,
                                arguments = listOf(
                                    navArgument(DebtDetailViewModel.NAV_ARG_PROFILE_ID) {
                                        type = NavType.StringType
                                    },
                                ),
                            ) {
                                debtDetailContent(
                                    { navController.popBackStack() },
                                    onToggleAmountsHidden,
                                    showMessage,
                                )
                            }
                            composable(DropletRoutes.REIMBURSEMENT_LIST) {
                                reimbursementListContent(
                                    { navController.popBackStack() },
                                    onToggleAmountsHidden,
                                    showMessage,
                                )
                            }
                            composable(
                                route = DropletRoutes.TRANSACTION_EDITOR_PATTERN,
                                arguments = listOf(
                                    navArgument("transactionId") {
                                        type = NavType.StringType
                                        nullable = true
                                        defaultValue = null
                                    },
                                    navArgument(TransactionEditorViewModel.NAV_ARG_TEMPLATE_ID) {
                                        type = NavType.StringType
                                        nullable = true
                                        defaultValue = null
                                    },
                                ),
                            ) {
                                editorContent(
                                    { navController.popBackStack() },
                                    { message ->
                                        navController.popBackStack()
                                        showMessage(message)
                                    },
                                )
                            }
                            composable(DropletRoutes.ACCOUNT_MANAGEMENT) {
                                accountManageContent(
                                    { navController.popBackStack() },
                                    showMessage,
                                )
                            }
                            composable(DropletRoutes.CATEGORY_TAG_MANAGEMENT) {
                                categoryTagManageContent(
                                    { navController.popBackStack() },
                                    showMessage,
                                )
                            }
                            composable(DropletRoutes.PRIVACY_SECURITY) {
                                privacySecurityContent(
                                    { navController.popBackStack() },
                                    showMessage,
                                )
                            }
                            composable(DropletRoutes.MARKET_SYNC_SETTINGS) {
                                marketSyncSettingsContent(
                                    { navController.popBackStack() },
                                    showMessage,
                                )
                            }
                            composable(DropletRoutes.CREDIT_CARD_REMINDER_SETTINGS) {
                                creditCardReminderSettingsContent(
                                    { navController.popBackStack() },
                                    { accountId -> navController.navigate(DropletRoutes.creditCardDetail(accountId)) },
                                    showMessage,
                                )
                            }
                            composable(DropletRoutes.AI_SETTINGS) {
                                aiSettingsContent(
                                    { navController.popBackStack() },
                                    showMessage,
                                )
                            }
                            composable(DropletRoutes.BUDGET_MANAGEMENT) {
                                budgetManagementContent(
                                    { navController.popBackStack() },
                                    onToggleAmountsHidden,
                                    showMessage,
                                )
                            }
                            composable(DropletRoutes.RECURRING_RULES) {
                                recurringRulesContent(
                                    { navController.popBackStack() },
                                    onToggleAmountsHidden,
                                    showMessage,
                                )
                            }
                            composable(DropletRoutes.TEMPLATE_MANAGEMENT) {
                                templateManagementContent(
                                    { navController.popBackStack() },
                                    onToggleAmountsHidden,
                                    { templateId -> openTransactionEditor(null, templateId) },
                                    showMessage,
                                )
                            }
                            composable(DropletRoutes.DATA_EXPORT) {
                                dataExportContent(
                                    { navController.popBackStack() },
                                    showMessage,
                                )
                            }
                            composable(
                                route = DropletRoutes.AI_CAPTURE_PATTERN,
                                arguments = listOf(
                                    navArgument(AiCaptureViewModel.NAV_ARG_TAB) {
                                        type = NavType.StringType
                                        nullable = true
                                        defaultValue = AiCaptureTab.TEXT.routeKey
                                    },
                                ),
                            ) {
                                aiCaptureContent(
                                    { navController.popBackStack() },
                                    { draftId ->
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        navController.navigate(DropletRoutes.aiDraftReview(draftId))
                                    },
                                    {
                                        navController.navigate(DropletRoutes.AI_SETTINGS)
                                    },
                                    showMessage,
                                )
                            }
                            composable(
                                route = DropletRoutes.AI_DRAFT_REVIEW_PATTERN,
                                arguments = listOf(
                                    navArgument(AiDraftReviewViewModel.NAV_ARG_DRAFT_ID) {
                                        type = NavType.StringType
                                    },
                                ),
                            ) {
                                aiDraftReviewContent(
                                    { navController.popBackStack() },
                                    { message ->
                                        navController.navigate(DropletTab.LEDGER.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                inclusive = false
                                            }
                                            launchSingleTop = true
                                        }
                                        showMessage(message)
                                    },
                                    onToggleAmountsHidden,
                                    showMessage,
                                )
                            }
                        }
                    }

                    if (showQuickCaptureSheet && !isAppLocked) {
                        QuickCaptureModalSheet(
                            pendingDraftCount = pendingAiDraftCount,
                            onOpenDraftInbox = {
                                showQuickCaptureSheet = false
                                snackbarHostState.currentSnackbarData?.dismiss()
                                navController.navigate(DropletRoutes.aiCapture(AiCaptureTab.TEXT))
                            },
                            onSelectMode = { mode ->
                                showQuickCaptureSheet = false
                                snackbarHostState.currentSnackbarData?.dismiss()
                                when (mode) {
                                    QuickCaptureMode.MANUAL -> openTransactionEditor(null, null)
                                    QuickCaptureMode.IMAGE -> navController.navigate(
                                        DropletRoutes.aiCapture(AiCaptureTab.IMAGE),
                                    )
                                    QuickCaptureMode.VOICE -> navController.navigate(
                                        DropletRoutes.aiCapture(AiCaptureTab.VOICE),
                                    )
                                    QuickCaptureMode.NATURAL_TEXT -> navController.navigate(
                                        DropletRoutes.aiCapture(AiCaptureTab.TEXT),
                                    )
                                }
                            },
                            onDismissRequest = { showQuickCaptureSheet = false },
                        )
                    }
                }

                if (isAppLocked) {
                    AppLockOverlay(
                        isAuthenticating = isAuthenticating,
                        errorMessage = lockErrorMessage,
                        onUnlockClick = onRequestUnlock,
                        onBackPressed = onLockOverlayBackPressed,
                    )
                }
            }
        }
    }
}
