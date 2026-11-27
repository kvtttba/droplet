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
import io.github.kvtttba.droplet.core.security.AppLockState
import io.github.kvtttba.droplet.core.security.AppThemePreference
import io.github.kvtttba.droplet.core.security.PrivacyPreferences
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.ThemeMode
import io.github.kvtttba.droplet.feature.aicapture.QuickCaptureModalSheet
import io.github.kvtttba.droplet.feature.aicapture.QuickCaptureMode
import io.github.kvtttba.droplet.feature.assets.AssetsRoute
import io.github.kvtttba.droplet.feature.assets.AssetsScreen
import io.github.kvtttba.droplet.feature.assets.AssetsUiState
import io.github.kvtttba.droplet.feature.assets.CreditCardDetailRoute
import io.github.kvtttba.droplet.feature.assets.CreditCardDetailScreen
import io.github.kvtttba.droplet.feature.assets.CreditCardDetailUiState
import io.github.kvtttba.droplet.feature.assets.CreditCardDetailViewModel
import io.github.kvtttba.droplet.feature.assets.InvestmentDetailRoute
import io.github.kvtttba.droplet.feature.assets.InvestmentDetailScreen
import io.github.kvtttba.droplet.feature.assets.InvestmentDetailUiState
import io.github.kvtttba.droplet.feature.assets.InvestmentDetailViewModel
import io.github.kvtttba.droplet.feature.ledger.LedgerRoute
import io.github.kvtttba.droplet.feature.ledger.LedgerScreen
import io.github.kvtttba.droplet.feature.ledger.LedgerUiState
import io.github.kvtttba.droplet.feature.ledger.editor.TransactionEditorRoute
import io.github.kvtttba.droplet.feature.ledger.manage.AccountManagementRoute
import io.github.kvtttba.droplet.feature.ledger.manage.CategoryTagManagementRoute
import io.github.kvtttba.droplet.feature.settings.CreditCardReminderSettingsRoute
import io.github.kvtttba.droplet.feature.settings.CreditCardReminderSettingsScreen
import io.github.kvtttba.droplet.feature.settings.CreditCardReminderSettingsUiState
import io.github.kvtttba.droplet.feature.settings.MarketSyncSettingsRoute
import io.github.kvtttba.droplet.feature.settings.MarketSyncSettingsScreen
import io.github.kvtttba.droplet.feature.settings.MarketSyncSettingsUiState
import io.github.kvtttba.droplet.feature.settings.PrivacySecurityRoute
import io.github.kvtttba.droplet.feature.settings.PrivacySecurityScreen
import io.github.kvtttba.droplet.feature.settings.PrivacySecurityUiState
import io.github.kvtttba.droplet.feature.settings.SettingsScreen
import io.github.kvtttba.droplet.feature.stats.StatsScreen
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
    const val TRANSACTION_EDITOR_PATTERN = "transaction_editor?transactionId={transactionId}"
    const val INVESTMENT_DETAIL_PATTERN = "investment_detail/{${InvestmentDetailViewModel.NAV_ARG_ASSET_ID}}"
    const val CREDIT_CARD_DETAIL_PATTERN = "credit_card_detail/{${CreditCardDetailViewModel.NAV_ARG_ACCOUNT_ID}}"
    const val ACCOUNT_MANAGEMENT = "manage_accounts"
    const val CATEGORY_TAG_MANAGEMENT = "manage_categories_tags"
    const val PRIVACY_SECURITY = "privacy_security"
    const val MARKET_SYNC_SETTINGS = "market_sync_settings"
    const val CREDIT_CARD_REMINDER_SETTINGS = "credit_card_reminder_settings"

    fun transactionEditor(transactionId: String? = null): String =
        if (transactionId.isNullOrBlank()) {
            "transaction_editor"
        } else {
            "transaction_editor?transactionId=${Uri.encode(transactionId)}"
        }

    fun investmentDetail(assetId: String): String =
        "investment_detail/${Uri.encode(assetId)}"

    fun creditCardDetail(accountId: String): String =
        "credit_card_detail/${Uri.encode(accountId)}"
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
    pendingCreditCardAccountId: String? = null,
    onConsumePendingCreditCardAccountId: () -> Unit = {},
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
        pendingCreditCardAccountId = pendingCreditCardAccountId,
        onConsumePendingCreditCardAccountId = onConsumePendingCreditCardAccountId,
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
        ledgerContent = { onToggleHidden, onCreateTx, onEditTx, onShowMessage ->
            LedgerRoute(
                onToggleAmountsHidden = onToggleHidden,
                onCreateTransaction = onCreateTx,
                onEditTransaction = onEditTx,
                onShowMessage = onShowMessage,
            )
        },
        assetsContent = { onToggleHidden, onOpenDetail, onOpenCreditCard, onOpenSyncSettings, onShowMessage ->
            AssetsRoute(
                onToggleAmountsHidden = onToggleHidden,
                onOpenInvestmentDetail = onOpenDetail,
                onOpenCreditCardDetail = onOpenCreditCard,
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
    investmentDetailPreviewState: InvestmentDetailUiState? = null,
    creditCardDetailPreviewState: CreditCardDetailUiState? = null,
    marketSyncPreviewState: MarketSyncSettingsUiState? = null,
    creditCardReminderSettingsPreviewState: CreditCardReminderSettingsUiState? = null,
    ledgerContent: @Composable (
        onToggleAmountsHidden: () -> Unit,
        onCreateTransaction: () -> Unit,
        onEditTransaction: (String) -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit = { onToggleHidden, onCreateTx, onEditTx, _ ->
        LedgerScreen(
            uiState = ledgerPreviewState ?: LedgerUiState(),
            onToggleAmountsHidden = onToggleHidden,
            onCreateTransaction = onCreateTx,
            onEditTransaction = onEditTx,
        )
    },
    assetsContent: @Composable (
        onToggleAmountsHidden: () -> Unit,
        onOpenInvestmentDetail: (String) -> Unit,
        onOpenCreditCardDetail: (String) -> Unit,
        onOpenMarketSyncSettings: () -> Unit,
        onShowMessage: (String) -> Unit,
    ) -> Unit = { onToggleHidden, onOpenDetail, onOpenCreditCard, onOpenSyncSettings, _ ->
        AssetsScreen(
            uiState = assetsPreviewState ?: AssetsUiState(isLoading = false),
            onToggleAmountsHidden = onToggleHidden,
            onOpenInvestmentDetail = onOpenDetail,
            onOpenCreditCardDetail = onOpenCreditCard,
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
        pendingCreditCardAccountId = null,
        onConsumePendingCreditCardAccountId = {},
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
    pendingCreditCardAccountId: String?,
    onConsumePendingCreditCardAccountId: () -> Unit,
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
        onShowMessage: (String) -> Unit,
    ) -> Unit,
    assetsContent: @Composable (
        onToggleAmountsHidden: () -> Unit,
        onOpenInvestmentDetail: (String) -> Unit,
        onOpenCreditCardDetail: (String) -> Unit,
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
            snackbarHostState.showSnackbar(message)
        }
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
                                    { navController.navigate(DropletRoutes.transactionEditor()) },
                                    { txId -> navController.navigate(DropletRoutes.transactionEditor(txId)) },
                                    showMessage,
                                )
                            }
                            composable(DropletTab.ASSETS.route) {
                                assetsContent(
                                    onToggleAmountsHidden,
                                    { assetId -> navController.navigate(DropletRoutes.investmentDetail(assetId)) },
                                    { accountId -> navController.navigate(DropletRoutes.creditCardDetail(accountId)) },
                                    { navController.navigate(DropletRoutes.MARKET_SYNC_SETTINGS) },
                                    showMessage,
                                )
                            }
                            composable(DropletTab.STATS.route) {
                                StatsScreen(
                                    onToggleAmountsHidden = onToggleAmountsHidden,
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
                                    biometricLockEnabled = biometricLockEnabled,
                                    flagSecureEnabled = flagSecureEnabled,
                                    marketSyncSummary = marketSyncSummary,
                                    creditCardReminderSummary = creditCardReminderSummary,
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
                                route = DropletRoutes.TRANSACTION_EDITOR_PATTERN,
                                arguments = listOf(
                                    navArgument("transactionId") {
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
                        }
                    }

                    if (showQuickCaptureSheet && !isAppLocked) {
                        QuickCaptureModalSheet(
                            onSelectMode = { mode ->
                                showQuickCaptureSheet = false
                                when (mode) {
                                    QuickCaptureMode.MANUAL -> {
                                        navController.navigate(DropletRoutes.transactionEditor())
                                    }
                                    else -> {
                                        showMessage("${mode.title} 将在 ${mode.milestoneTag} 切片接入")
                                    }
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
