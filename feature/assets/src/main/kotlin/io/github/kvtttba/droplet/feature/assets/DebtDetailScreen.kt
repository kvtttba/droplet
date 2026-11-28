package io.github.kvtttba.droplet.feature.assets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.domain.debt.DebtProfileStatus
import io.github.kvtttba.droplet.core.domain.debt.DebtProfileSummary
import io.github.kvtttba.droplet.core.domain.debt.DebtTransactionKind
import io.github.kvtttba.droplet.core.domain.debt.DebtTransactionRecord
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.component.PrivacyToggleIconButton
import io.github.kvtttba.droplet.core.ui.permission.rememberNotificationPermissionController
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.tabularNums

private sealed interface DebtDetailActiveSheet {
    data object Lend : DebtDetailActiveSheet
    data object Borrow : DebtDetailActiveSheet
    data object Collect : DebtDetailActiveSheet
    data object Repay : DebtDetailActiveSheet
    data object BadDebtWriteOff : DebtDetailActiveSheet
    data object EditProfile : DebtDetailActiveSheet
}

@Composable
fun DebtDetailRoute(
    onNavigateBack: () -> Unit,
    onToggleAmountsHidden: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DebtDetailViewModel = hiltViewModel(),
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

    LaunchedEffect(uiState.isDeleted) {
        if (uiState.isDeleted) {
            onNavigateBack()
        }
    }

    DebtDetailScreen(
        uiState = uiState,
        notificationPermissionGranted = permissionController.isGranted,
        onNavigateBack = onNavigateBack,
        onToggleAmountsHidden = onToggleAmountsHidden,
        onRequestNotificationPermission = permissionController.requestOrOpenSettings,
        onRecordLend = viewModel::recordLend,
        onRecordBorrow = viewModel::recordBorrow,
        onRecordCollect = viewModel::recordCollect,
        onRecordRepay = viewModel::recordRepay,
        onRecordBadDebtWriteOff = viewModel::recordBadDebtWriteOff,
        onUpdateProfile = { name, dueDateIso, rate, note, cb ->
            viewModel.updateProfile(name, dueDateIso, rate, note) { err ->
                if (err == null && dueDateIso.isNotBlank() && !permissionController.isGranted) {
                    permissionController.requestOrOpenSettings()
                }
                cb(err)
            }
        },
        onDeleteProfile = viewModel::deleteProfile,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebtDetailScreen(
    uiState: DebtDetailUiState,
    notificationPermissionGranted: Boolean = true,
    onNavigateBack: () -> Unit = {},
    onToggleAmountsHidden: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onRecordLend: (String?, String, String, (String?) -> Unit) -> Unit = { _, _, _, cb -> cb(null) },
    onRecordBorrow: (String?, String, String, (String?) -> Unit) -> Unit = { _, _, _, cb -> cb(null) },
    onRecordCollect: (String?, String, String, String, String, (String?) -> Unit) -> Unit = { _, _, _, _, _, cb -> cb(null) },
    onRecordRepay: (String?, String, String, String, String, (String?) -> Unit) -> Unit = { _, _, _, _, _, cb -> cb(null) },
    onRecordBadDebtWriteOff: (String, String, String, (String?) -> Unit) -> Unit = { _, _, _, cb -> cb(null) },
    onUpdateProfile: (String, String, String, String, (String?) -> Unit) -> Unit = { _, _, _, _, cb -> cb(null) },
    onDeleteProfile: ((String?) -> Unit) -> Unit = { cb -> cb(null) },
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    val semantic = DropletTheme.semanticColors
    var activeSheet by remember { mutableStateOf<DebtDetailActiveSheet?>(null) }

    val summary = uiState.summary

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_debt_detail"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("debt_detail_back"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = summary?.profile?.name ?: "往来借贷详情",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (summary != null) {
                            val parts = buildList {
                                add("币种 ${summary.currency.code}")
                                summary.dueDate?.let { add("约定 ${AssetsFormatters.formatLocalDateIso(it)}") }
                                summary.profile.annualInterestRateDecimal?.let {
                                    add("年化 ${AssetsFormatters.formatAnnualRatePercent(it)}")
                                }
                            }
                            Text(
                                text = parts.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (summary != null) {
                        IconButton(
                            onClick = { activeSheet = DebtDetailActiveSheet.EditProfile },
                            modifier = Modifier.testTag("debt_edit_profile_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "维护往来人",
                            )
                        }
                    }
                    PrivacyToggleIconButton(
                        amountsHidden = amountsHidden,
                        onToggle = onToggleAmountsHidden,
                    )
                },
            )
        },
    ) { innerPadding ->
        if (summary == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (uiState.isLoading) "正在加载往来借贷明细…" else "未找到该往来人",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("debt_detail_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "debt_hero_card") {
                DebtDetailHeroCard(summary = summary)
            }

            if (summary.profile.defaultDueAt != null && !notificationPermissionGranted) {
                item(key = "debt_permission_card") {
                    DropletCard(containerColor = semantic.warningContainer) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Notifications,
                                contentDescription = null,
                                tint = semantic.warning,
                                modifier = Modifier.size(20.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "开启通知以接收约定还款日提醒",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = semantic.warning,
                                )
                                Text(
                                    text = "本地离线提醒，绝不联网上传往来信息。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = semantic.warning.copy(alpha = 0.85f),
                                )
                            }
                            FilledTonalButton(onClick = onRequestNotificationPermission) {
                                Text("去开启")
                            }
                        }
                    }
                }
            }

            item(key = "debt_actions_card") {
                DropletCard {
                    Text(
                        text = "借贷记账操作",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { activeSheet = DebtDetailActiveSheet.Lend },
                            enabled = !uiState.isSubmitting,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("debt_action_lend"),
                        ) {
                            Text("借出", maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = { activeSheet = DebtDetailActiveSheet.Collect },
                            enabled = !uiState.isSubmitting && summary.receivableBalance.isPositive,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("debt_action_collect"),
                        ) {
                            Text("收款", maxLines = 1)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { activeSheet = DebtDetailActiveSheet.Borrow },
                            enabled = !uiState.isSubmitting,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("debt_action_borrow"),
                        ) {
                            Text("借入", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { activeSheet = DebtDetailActiveSheet.Repay },
                            enabled = !uiState.isSubmitting && summary.payableBalance.isPositive,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("debt_action_repay"),
                        ) {
                            Text("还款", maxLines = 1)
                        }
                    }
                    if (summary.receivableBalance.isPositive) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { activeSheet = DebtDetailActiveSheet.BadDebtWriteOff },
                            enabled = !uiState.isSubmitting,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .testTag("debt_action_write_off"),
                        ) {
                            Text("坏账核销（剩余应收转坏账损失）", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            item(key = "debt_metrics_card") {
                DropletCard {
                    Text(
                        text = "累计往来统计",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DebtMetricCell(
                            label = "累计借出",
                            money = summary.totalLent,
                            modifier = Modifier.weight(1f),
                        )
                        DebtMetricCell(
                            label = "已收本金",
                            money = summary.totalCollectedPrincipal,
                            modifier = Modifier.weight(1f),
                        )
                        DebtMetricCell(
                            label = "利息收入",
                            money = summary.totalInterestIncome,
                            valueColor = if (summary.totalInterestIncome.isPositive) semantic.income else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            testTag = "debt_detail_interest_income",
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DebtMetricCell(
                            label = "累计借入",
                            money = summary.totalBorrowed,
                            modifier = Modifier.weight(1f),
                        )
                        DebtMetricCell(
                            label = "已还本金",
                            money = summary.totalRepaidPrincipal,
                            modifier = Modifier.weight(1f),
                        )
                        DebtMetricCell(
                            label = if (summary.totalBadDebtWriteOff.isPositive) "坏账核销" else "利息支出",
                            money = if (summary.totalBadDebtWriteOff.isPositive) summary.totalBadDebtWriteOff else summary.totalInterestExpense,
                            valueColor = if (summary.totalBadDebtWriteOff.isPositive || summary.totalInterestExpense.isPositive) {
                                semantic.expense
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                            testTag = "debt_detail_write_off_or_interest",
                        )
                    }
                }
            }

            item(key = "debt_history_header") {
                DropletSectionHeader(
                    title = "往来借贷记录",
                    trailing = "${uiState.transactions.size} 笔",
                )
            }

            item(key = "debt_history_card") {
                DropletCard {
                    if (uiState.transactions.isEmpty()) {
                        Text(
                            text = "暂无借贷流水，点击上方「借出」或「借入」即可开始记录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        uiState.transactions.forEachIndexed { index, record ->
                            DebtTransactionHistoryRow(
                                record = record,
                                fundingAccountName = record.fundingAccountId?.let { uiState.accountsById[it]?.name },
                                zoneId = summary.zoneId,
                            )
                            if (index != uiState.transactions.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (summary != null) {
        when (activeSheet) {
            DebtDetailActiveSheet.Lend -> DebtLendBorrowSheet(
                isLend = true,
                summary = summary,
                fundingAccounts = uiState.fundingAccounts,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSubmit = onRecordLend,
            )
            DebtDetailActiveSheet.Borrow -> DebtLendBorrowSheet(
                isLend = false,
                summary = summary,
                fundingAccounts = uiState.fundingAccounts,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSubmit = onRecordBorrow,
            )
            DebtDetailActiveSheet.Collect -> DebtCollectRepaySheet(
                isCollect = true,
                summary = summary,
                fundingAccounts = uiState.fundingAccounts,
                interestCategories = uiState.incomeCategories,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSubmit = onRecordCollect,
            )
            DebtDetailActiveSheet.Repay -> DebtCollectRepaySheet(
                isCollect = false,
                summary = summary,
                fundingAccounts = uiState.fundingAccounts,
                interestCategories = uiState.expenseCategories,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSubmit = onRecordRepay,
            )
            DebtDetailActiveSheet.BadDebtWriteOff -> DebtBadDebtWriteOffSheet(
                summary = summary,
                expenseCategories = uiState.expenseCategories,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSubmit = onRecordBadDebtWriteOff,
            )
            DebtDetailActiveSheet.EditProfile -> EditDebtProfileSheet(
                summary = summary,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSave = onUpdateProfile,
                onDelete = onDeleteProfile,
            )
            null -> Unit
        }
    }
}

@Composable
private fun DebtDetailHeroCard(summary: DebtProfileSummary) {
    val semantic = DropletTheme.semanticColors
    val (statusLabel, statusBg, statusFg) = when (summary.status) {
        DebtProfileStatus.SETTLED -> Triple("已结清", semantic.incomeContainer, semantic.income)
        DebtProfileStatus.OVERDUE -> Triple(
            "逾期 ${summary.overdueDays} 天",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        DebtProfileStatus.DUE_SOON -> Triple(
            if (summary.isDueToday) "今日到期" else "${summary.daysUntilDue}天后到期",
            semantic.warningContainer,
            semantic.warning,
        )
        DebtProfileStatus.ACTIVE -> Triple(
            summary.daysUntilDue?.let { "剩 ${it} 天" } ?: "进行中",
            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }

    val netTitle = when {
        summary.netBalance.isPositive -> "对方净欠我 (${summary.currency.code})"
        summary.netBalance.isNegative -> "我净欠对方 (${summary.currency.code})"
        else -> "往来净余额 (${summary.currency.code})"
    }

    DropletHeroCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = netTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )
            DropletPillBadge(
                text = statusLabel,
                containerColor = statusBg,
                contentColor = statusFg,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        PrivacyAmountText(
            money = summary.netBalance.abs(),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.testTag("debt_detail_net_balance"),
        )
        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "别人欠我（应收）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
                Spacer(modifier = Modifier.height(3.dp))
                PrivacyAmountText(
                    money = summary.receivableBalance,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (summary.receivableBalance.isPositive) semantic.income else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("debt_detail_receivable"),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "我欠别人（应付）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
                Spacer(modifier = Modifier.height(3.dp))
                PrivacyAmountText(
                    money = summary.payableBalance,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (summary.payableBalance.isPositive) semantic.expense else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("debt_detail_payable"),
                )
            }
        }
        if (!summary.profile.note.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "备注：${summary.profile.note}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
            )
        }
    }
}

@Composable
private fun DebtMetricCell(
    label: String,
    money: Money,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    testTag: String? = null,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(3.dp))
        PrivacyAmountText(
            money = money,
            style = MaterialTheme.typography.titleSmall,
            color = valueColor,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
        )
    }
}

@Composable
private fun DebtTransactionHistoryRow(
    record: DebtTransactionRecord,
    fundingAccountName: String?,
    zoneId: java.time.ZoneId,
) {
    val semantic = DropletTheme.semanticColors
    val (label, bg, fg, displayAmount) = when (record.kind) {
        DebtTransactionKind.LEND -> Quadruple(
            "借出",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            record.principalAmount,
        )
        DebtTransactionKind.BORROW -> Quadruple(
            "借入",
            semantic.warningContainer,
            semantic.warning,
            record.principalAmount,
        )
        DebtTransactionKind.COLLECT -> Quadruple(
            "收款",
            semantic.incomeContainer,
            semantic.income,
            record.principalAmount + record.interestAmount,
        )
        DebtTransactionKind.REPAY -> Quadruple(
            "还款",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            record.principalAmount + record.interestAmount,
        )
        DebtTransactionKind.BAD_DEBT_WRITE_OFF -> Quadruple(
            "坏账核销",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            record.writeOffAmount,
        )
        DebtTransactionKind.OTHER -> Quadruple(
            "往来",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
            record.principalAmount,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.take(1),
                style = MaterialTheme.typography.titleSmall,
                color = fg,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = record.transaction.payee ?: label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                DropletPillBadge(
                    text = label,
                    containerColor = bg,
                    contentColor = fg,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            val metaParts = buildList {
                add(AssetsFormatters.formatFullTimestamp(record.transaction.occurredAt, zoneId))
                fundingAccountName?.let { add(it) }
                record.transaction.note?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            Text(
                text = metaParts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall.tabularNums(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            PrivacyAmountText(
                money = displayAmount,
                style = MaterialTheme.typography.titleSmall,
                color = fg,
            )
            if (record.interestAmount.isPositive) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "含息",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrivacyAmountText(
                        money = record.interestAmount,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
