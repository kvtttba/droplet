package io.github.kvtttba.droplet.ui

import android.net.Uri
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.ThemeMode
import io.github.kvtttba.droplet.feature.aicapture.QuickCaptureModalSheet
import io.github.kvtttba.droplet.feature.aicapture.QuickCaptureMode
import io.github.kvtttba.droplet.feature.assets.AssetsScreen
import io.github.kvtttba.droplet.feature.ledger.LedgerRoute
import io.github.kvtttba.droplet.feature.ledger.LedgerScreen
import io.github.kvtttba.droplet.feature.ledger.LedgerUiState
import io.github.kvtttba.droplet.feature.ledger.editor.TransactionEditorRoute
import io.github.kvtttba.droplet.feature.ledger.manage.AccountManagementRoute
import io.github.kvtttba.droplet.feature.ledger.manage.CategoryTagManagementRoute
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
    const val ACCOUNT_MANAGEMENT = "manage_accounts"
    const val CATEGORY_TAG_MANAGEMENT = "manage_categories_tags"

    fun transactionEditor(transactionId: String? = null): String =
        if (transactionId.isNullOrBlank()) {
            "transaction_editor"
        } else {
            "transaction_editor?transactionId=${Uri.encode(transactionId)}"
        }
}

/**
 * Production runtime application root wired to Hilt ViewModels & Room database.
 */
@Composable
fun DropletApp(
    initialTab: DropletTab = DropletTab.LEDGER,
    initialThemeMode: ThemeMode = ThemeMode.SYSTEM,
    initialDynamicColor: Boolean = true,
    initialAmountsHidden: Boolean = false,
    initialShowQuickCapture: Boolean = false,
) {
    DropletAppShell(
        initialTab = initialTab,
        initialThemeMode = initialThemeMode,
        initialDynamicColor = initialDynamicColor,
        initialAmountsHidden = initialAmountsHidden,
        initialShowQuickCapture = initialShowQuickCapture,
        ledgerContent = { onToggleHidden, onCreateTx, onEditTx, onShowMessage ->
            LedgerRoute(
                onToggleAmountsHidden = onToggleHidden,
                onCreateTransaction = onCreateTx,
                onEditTransaction = onEditTx,
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
    )
}

/**
 * Stateless/slot-injected shell used by both [DropletApp] and pure Compose UI/Roborazzi tests
 * without requiring Hilt application initialization.
 */
@Composable
fun DropletAppShell(
    initialTab: DropletTab = DropletTab.LEDGER,
    initialThemeMode: ThemeMode = ThemeMode.SYSTEM,
    initialDynamicColor: Boolean = true,
    initialAmountsHidden: Boolean = false,
    initialShowQuickCapture: Boolean = false,
    ledgerPreviewState: LedgerUiState? = null,
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
    var showQuickCaptureSheet by rememberSaveable { mutableStateOf(initialShowQuickCapture) }

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
                            { amountsHidden = !amountsHidden },
                            { navController.navigate(DropletRoutes.transactionEditor()) },
                            { txId -> navController.navigate(DropletRoutes.transactionEditor(txId)) },
                            showMessage,
                        )
                    }
                    composable(DropletTab.ASSETS.route) {
                        AssetsScreen(
                            onToggleAmountsHidden = { amountsHidden = !amountsHidden },
                        )
                    }
                    composable(DropletTab.STATS.route) {
                        StatsScreen(
                            onToggleAmountsHidden = { amountsHidden = !amountsHidden },
                        )
                    }
                    composable(DropletTab.SETTINGS.route) {
                        SettingsScreen(
                            themeMode = themeMode,
                            dynamicColor = dynamicColor,
                            onThemeModeChange = { themeMode = it },
                            onDynamicColorChange = { dynamicColor = it },
                            onToggleAmountsHidden = { amountsHidden = !amountsHidden },
                            onOpenAccountManagement = {
                                navController.navigate(DropletRoutes.ACCOUNT_MANAGEMENT)
                            },
                            onOpenCategoryTagManagement = {
                                navController.navigate(DropletRoutes.CATEGORY_TAG_MANAGEMENT)
                            },
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
                }
            }

            if (showQuickCaptureSheet) {
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
    }
}
