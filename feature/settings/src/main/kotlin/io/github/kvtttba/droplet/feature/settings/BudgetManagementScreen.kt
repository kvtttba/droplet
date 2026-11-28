package io.github.kvtttba.droplet.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.domain.automation.BudgetAlertLevel
import io.github.kvtttba.droplet.core.domain.automation.BudgetProgress
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.Budget
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.component.PrivacyToggleIconButton
import io.github.kvtttba.droplet.core.ui.icon.DropletIconRegistry
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.permission.rememberNotificationPermissionController
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme

private val presetWarnThresholds = listOf(70, 80, 90, 95)

@Composable
fun BudgetManagementRoute(
    onNavigateBack: () -> Unit,
    onToggleAmountsHidden: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: BudgetManagementViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionController = rememberNotificationPermissionController(
        onStatusMessage = onShowMessage,
    )

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            onShowMessage(msg)
            viewModel.clearUserMessage()
        }
    }

    BudgetManagementScreen(
        uiState = uiState,
        notificationPermissionGranted = permissionController.isGranted,
        onNavigateBack = onNavigateBack,
        onToggleAmountsHidden = onToggleAmountsHidden,
        onSelectPeriod = viewModel::selectPeriod,
        onToggleAlertsEnabled = { enabled ->
            viewModel.setBudgetAlertsEnabled(enabled)
            if (enabled && !permissionController.isGranted) {
                permissionController.requestOrOpenSettings()
            }
        },
        onRequestNotificationPermission = permissionController.requestOrOpenSettings,
        onSaveBudget = { existingId, period, categoryId, amountText, warnPct, onComplete ->
            viewModel.saveBudget(
                existingId = existingId,
                period = period,
                categoryAccountId = categoryId,
                amountText = amountText,
                warnThresholdPct = warnPct,
                onComplete = onComplete,
            )
        },
        onToggleBudgetEnabled = viewModel::setBudgetEnabled,
        onDeleteBudget = viewModel::deleteBudget,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BudgetManagementScreen(
    uiState: BudgetManagementUiState,
    notificationPermissionGranted: Boolean = true,
    onNavigateBack: () -> Unit = {},
    onToggleAmountsHidden: () -> Unit = {},
    onSelectPeriod: (BudgetPeriod) -> Unit = {},
    onToggleAlertsEnabled: (Boolean) -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onSaveBudget: (
        existingId: String?,
        period: BudgetPeriod,
        categoryAccountId: String?,
        amountText: String,
        warnThresholdPct: Int,
        onComplete: (String?) -> Unit,
    ) -> Unit = { _, _, _, _, _, cb -> cb(null) },
    onToggleBudgetEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onDeleteBudget: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    val semantic = DropletTheme.semanticColors
    var editorTarget by remember { mutableStateOf<BudgetEditorTarget?>(null) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_budget_management"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "预算管理",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("budget_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    PrivacyToggleIconButton(
                        amountsHidden = amountsHidden,
                        onToggle = onToggleAmountsHidden,
                    )
                    IconButton(
                        onClick = {
                            editorTarget = BudgetEditorTarget.Create(
                                defaultPeriod = uiState.selectedPeriod,
                                defaultOverall = uiState.activeOverall == null,
                            )
                        },
                        modifier = Modifier.testTag("budget_add_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "新建预算",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("budget_management_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Period switcher (Monthly / Yearly)
            item(key = "budget_period_tabs") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = uiState.selectedPeriod == BudgetPeriod.MONTHLY,
                        onClick = { onSelectPeriod(BudgetPeriod.MONTHLY) },
                        label = { Text("月度预算 · ${uiState.targetMonth.monthValue}月") },
                        modifier = Modifier.testTag("budget_period_MONTHLY"),
                    )
                    FilterChip(
                        selected = uiState.selectedPeriod == BudgetPeriod.YEARLY,
                        onClick = { onSelectPeriod(BudgetPeriod.YEARLY) },
                        label = { Text("年度预算 · ${uiState.targetMonth.year}年") },
                        modifier = Modifier.testTag("budget_period_YEARLY"),
                    )
                }
            }

            // Hero Overall Budget Card
            item(key = "budget_overall_hero") {
                val overall = uiState.activeOverall
                if (overall != null) {
                    OverallBudgetHeroCard(
                        progress = overall,
                        onEdit = { editorTarget = BudgetEditorTarget.Edit(overall.budget) },
                    )
                } else {
                    DropletHeroCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = DropletIcons.Savings,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (uiState.selectedPeriod == BudgetPeriod.MONTHLY) {
                                        "尚未设置月度总预算"
                                    } else {
                                        "尚未设置年度总预算"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = "设定总限额后自动追踪实时消耗、日均可用额度与 80%/100% 预警",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                editorTarget = BudgetEditorTarget.Create(
                                    defaultPeriod = uiState.selectedPeriod,
                                    defaultOverall = true,
                                )
                            },
                            modifier = Modifier.testTag("budget_create_overall_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (uiState.selectedPeriod == BudgetPeriod.MONTHLY) {
                                    "设置本月总预算"
                                } else {
                                    "设置本年总预算"
                                },
                            )
                        }
                    }
                }
            }

            // Alert Notification Preferences Card
            item(key = "budget_alert_controls") {
                DropletCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "预算超阈值本地预警",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "达到设定预警线（默认 80%）或超支 100% 时推送本地提醒，同周期防重复打扰",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = uiState.budgetAlertsEnabled,
                            onCheckedChange = onToggleAlertsEnabled,
                            modifier = Modifier.testTag("budget_alerts_enabled_switch"),
                        )
                    }
                    if (uiState.budgetAlertsEnabled && !notificationPermissionGranted) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "未授予通知权限，建议开启以接收预警弹窗",
                                style = MaterialTheme.typography.bodySmall,
                                color = semantic.warning,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = onRequestNotificationPermission,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text("去授权", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // Category Budgets Section Header
            item(key = "budget_categories_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DropletSectionHeader(
                        title = if (uiState.selectedPeriod == BudgetPeriod.MONTHLY) {
                            "月度支出分类预算"
                        } else {
                            "年度支出分类预算"
                        },
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = {
                            editorTarget = BudgetEditorTarget.Create(
                                defaultPeriod = uiState.selectedPeriod,
                                defaultOverall = false,
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("budget_add_category_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("分类预算", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (uiState.activeCategoryBudgets.isEmpty()) {
                item(key = "budget_categories_empty") {
                    DropletCard {
                        Text(
                            text = "尚未配置分类预算。为「餐饮」「购物」「交通」等大类设置预算时，系统将自动汇总其全部子分类净支出（自动扣减退款，排除待报销垫付）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(
                    items = uiState.activeCategoryBudgets,
                    key = { "budget_cat_${it.budget.id}" },
                ) { progress ->
                    CategoryBudgetCard(
                        progress = progress,
                        onEdit = { editorTarget = BudgetEditorTarget.Edit(progress.budget) },
                        onToggleEnabled = { enabled -> onToggleBudgetEnabled(progress.budget.id, enabled) },
                    )
                }
            }
        }
    }

    editorTarget?.let { target ->
        BudgetEditorBottomSheet(
            target = target,
            expenseCategories = uiState.expenseCategories,
            isSaving = uiState.isSaving,
            onDismiss = { editorTarget = null },
            onSave = { existingId, period, categoryId, amountText, warnPct ->
                onSaveBudget(existingId, period, categoryId, amountText, warnPct) { err ->
                    if (err == null) {
                        editorTarget = null
                    }
                }
            },
            onDelete = { budgetId ->
                onDeleteBudget(budgetId)
                editorTarget = null
            },
        )
    }
}

@Composable
private fun OverallBudgetHeroCard(
    progress: BudgetProgress,
    onEdit: () -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val (barColor, badgeBg, badgeFg, statusLabel) = budgetAlertVisuals(progress, semantic)

    DropletHeroCard(
        modifier = Modifier.testTag("budget_overall_card"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = progress.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "预警线 ${progress.budget.warnThresholdPct}% · 周期剩余 ${progress.periodRange.remainingDays} 天",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DropletPillBadge(
                    text = statusLabel,
                    containerColor = badgeBg,
                    contentColor = badgeFg,
                    modifier = Modifier
                        .semantics(mergeDescendants = true) {}
                        .testTag("budget_overall_status_badge"),
                )
                TextButton(
                    onClick = onEdit,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("budget_overall_edit_button"),
                ) {
                    Text(
                        text = "调整",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    text = "已消耗",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                PrivacyAmountText(
                    money = progress.spentAmount,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("budget_overall_spent"),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "总限额",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                PrivacyAmountText(
                    money = progress.budget.limitAmount,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("budget_overall_limit"),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LinearProgressIndicator(
            progress = { progress.progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(9.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            strokeCap = StrokeCap.Round,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = if (progress.remainingAmount.isNegative) "已超支" else "剩余可用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                PrivacyAmountText(
                    money = if (progress.remainingAmount.isNegative) -progress.remainingAmount else progress.remainingAmount,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (progress.remainingAmount.isNegative) semantic.expense else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("budget_overall_remaining"),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "剩余日均可用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                PrivacyAmountText(
                    money = progress.dailyAvailableAmount,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("budget_overall_daily_available"),
                )
            }
        }
    }
}

@Composable
private fun CategoryBudgetCard(
    progress: BudgetProgress,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val (barColor, badgeBg, badgeFg, statusLabel) = budgetAlertVisuals(progress, semantic)
    val accent = DropletIconRegistry.parseColor(progress.categoryColor, MaterialTheme.colorScheme.primary)

    DropletCard(
        modifier = Modifier
            .clickable(onClick = onEdit)
            .testTag("budget_category_card_${progress.displayTitle}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DropletIconRegistry.resolve(progress.categoryIcon),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = progress.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DropletPillBadge(
                        text = if (progress.budget.isEnabled) statusLabel else "已停用",
                        containerColor = if (progress.budget.isEnabled) badgeBg else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (progress.budget.isEnabled) badgeFg else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "日均可用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrivacyAmountText(
                        money = progress.dailyAvailableAmount,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "· 预警 ${progress.budget.warnThresholdPct}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = progress.budget.isEnabled,
                onCheckedChange = onToggleEnabled,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        LinearProgressIndicator(
            progress = { progress.progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = if (progress.budget.isEnabled) barColor else MaterialTheme.colorScheme.outline,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "已用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrivacyAmountText(
                    money = progress.spentAmount,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "限额",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrivacyAmountText(
                    money = progress.budget.limitAmount,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class BudgetAlertVisualTuple(
    val barColor: Color,
    val badgeBg: Color,
    val badgeFg: Color,
    val label: String,
)

@Composable
private fun budgetAlertVisuals(
    progress: BudgetProgress,
    semantic: io.github.kvtttba.droplet.core.ui.theme.DropletSemanticColors,
): BudgetAlertVisualTuple = when (progress.alertLevel) {
    BudgetAlertLevel.EXCEEDED -> BudgetAlertVisualTuple(
        barColor = semantic.expense,
        badgeBg = semantic.expenseContainer,
        badgeFg = semantic.expense,
        label = "已超支 ${progress.usedPercentageInt}%",
    )
    BudgetAlertLevel.WARNING -> BudgetAlertVisualTuple(
        barColor = semantic.warning,
        badgeBg = semantic.warningContainer,
        badgeFg = semantic.warning,
        label = "预警 ${progress.usedPercentageInt}%",
    )
    BudgetAlertLevel.NORMAL -> BudgetAlertVisualTuple(
        barColor = MaterialTheme.colorScheme.primary,
        badgeBg = semantic.incomeContainer,
        badgeFg = semantic.income,
        label = "已用 ${progress.usedPercentageInt}%",
    )
}

private sealed interface BudgetEditorTarget {
    data class Create(
        val defaultPeriod: BudgetPeriod,
        val defaultOverall: Boolean,
    ) : BudgetEditorTarget

    data class Edit(val budget: Budget) : BudgetEditorTarget
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BudgetEditorBottomSheet(
    target: BudgetEditorTarget,
    expenseCategories: List<Account>,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        existingId: String?,
        period: BudgetPeriod,
        categoryAccountId: String?,
        amountText: String,
        warnThresholdPct: Int,
    ) -> Unit,
    onDelete: (String) -> Unit,
) {
    val editing = (target as? BudgetEditorTarget.Edit)?.budget
    var period by remember(target) {
        mutableStateOf(
            editing?.period ?: (target as BudgetEditorTarget.Create).defaultPeriod,
        )
    }
    var selectedCategoryId by remember(target) {
        mutableStateOf(
            when (target) {
                is BudgetEditorTarget.Edit -> target.budget.categoryAccountId
                is BudgetEditorTarget.Create -> if (target.defaultOverall) {
                    null
                } else {
                    expenseCategories.firstOrNull { it.parentId == null }?.id
                }
            },
        )
    }
    var amountText by remember(target) {
        mutableStateOf(
            editing?.limitAmount?.toBigDecimal()?.stripTrailingZeros()?.toPlainString().orEmpty(),
        )
    }
    var warnPct by remember(target) {
        mutableIntStateOf(editing?.warnThresholdPct ?: 80)
    }

    val rootCategories = remember(expenseCategories) {
        expenseCategories.filter { it.parentId == null }.sortedBy { it.sortOrder }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("budget_editor_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (editing == null) "新建预算" else "编辑预算",
                style = MaterialTheme.typography.titleLarge,
            )

            Text(text = "预算周期", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = period == BudgetPeriod.MONTHLY,
                    onClick = { period = BudgetPeriod.MONTHLY },
                    label = { Text("月度") },
                    modifier = Modifier.testTag("budget_sheet_period_MONTHLY"),
                )
                FilterChip(
                    selected = period == BudgetPeriod.YEARLY,
                    onClick = { period = BudgetPeriod.YEARLY },
                    label = { Text("年度") },
                    modifier = Modifier.testTag("budget_sheet_period_YEARLY"),
                )
            }

            Text(text = "预算范围", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = selectedCategoryId == null,
                    onClick = { selectedCategoryId = null },
                    label = { Text("总预算") },
                    modifier = Modifier.testTag("budget_sheet_scope_overall"),
                )
                rootCategories.forEach { cat ->
                    FilterChip(
                        selected = selectedCategoryId == cat.id,
                        onClick = { selectedCategoryId = cat.id },
                        label = { Text(cat.name) },
                        modifier = Modifier.testTag("budget_sheet_category_${cat.name}"),
                    )
                }
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { raw ->
                    amountText = raw.filter { it.isDigit() || it == '.' }
                },
                label = { Text("预算限额 (${Currency.DEFAULT.symbol})") },
                placeholder = { Text("例如 5000.00") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("budget_sheet_amount_input"),
            )

            Text(text = "预警提醒比例（达到即预警）", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                presetWarnThresholds.forEach { pct ->
                    FilterChip(
                        selected = warnPct == pct,
                        onClick = { warnPct = pct },
                        label = { Text("$pct%") },
                        modifier = Modifier.testTag("budget_sheet_warn_$pct"),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (editing != null) {
                    OutlinedButton(
                        onClick = { onDelete(editing.id) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.testTag("budget_sheet_delete_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("删除")
                    }
                }
                Button(
                    onClick = {
                        onSave(editing?.id, period, selectedCategoryId, amountText, warnPct)
                    },
                    enabled = !isSaving && amountText.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("budget_sheet_save_button"),
                ) {
                    Text(if (isSaving) "保存中..." else "保存预算")
                }
            }
        }
    }
}
