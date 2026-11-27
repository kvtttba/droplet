package io.github.kvtttba.droplet.feature.assets

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.domain.loan.LoanPlanStatus
import io.github.kvtttba.droplet.core.domain.loan.LoanPlanSummary
import io.github.kvtttba.droplet.core.model.InstallmentStatus
import io.github.kvtttba.droplet.core.model.LoanInstallment
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

private sealed interface LoanDetailActiveSheet {
    data class RepayInstallment(val installment: LoanInstallment) : LoanDetailActiveSheet
    data object EarlyPayoff : LoanDetailActiveSheet
    data object EditParams : LoanDetailActiveSheet
}

@Composable
fun LoanDetailRoute(
    onNavigateBack: () -> Unit,
    onToggleAmountsHidden: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: LoanDetailViewModel = hiltViewModel(),
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

    LoanDetailScreen(
        uiState = uiState,
        notificationPermissionGranted = permissionController.isGranted,
        onNavigateBack = onNavigateBack,
        onToggleAmountsHidden = onToggleAmountsHidden,
        onSelectTab = viewModel::selectTab,
        onToggleAutoPost = viewModel::toggleAutoPost,
        onRequestNotificationPermission = permissionController.requestOrOpenSettings,
        onRepayInstallment = viewModel::repayInstallment,
        onEarlyPayoff = viewModel::earlyPayoff,
        onUpdateParameters = { name, payId, intId, autoPost, remindDays, note, cb ->
            viewModel.updateParameters(name, payId, intId, autoPost, remindDays, note) { err ->
                if (err == null && remindDays.isNotBlank() && !permissionController.isGranted) {
                    permissionController.requestOrOpenSettings()
                }
                cb(err)
            }
        },
        onToggleArchive = viewModel::setArchived,
        onDeleteLoan = viewModel::deleteLoanPlan,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LoanDetailScreen(
    uiState: LoanDetailUiState,
    notificationPermissionGranted: Boolean = true,
    onNavigateBack: () -> Unit = {},
    onToggleAmountsHidden: () -> Unit = {},
    onSelectTab: (LoanScheduleFilterTab) -> Unit = {},
    onToggleAutoPost: (Boolean) -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onRepayInstallment: (
        installmentId: String,
        paymentAccountIdOverride: String?,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit = { _, _, _, cb -> cb(null) },
    onEarlyPayoff: (
        paymentAccountIdOverride: String?,
        extraFeeText: String,
        note: String,
        archiveAfterPayoff: Boolean,
        onComplete: (String?) -> Unit,
    ) -> Unit = { _, _, _, _, cb -> cb(null) },
    onUpdateParameters: (
        name: String,
        paymentAccountId: String?,
        interestExpenseAccountId: String?,
        autoPost: Boolean,
        remindDaysBeforeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit = { _, _, _, _, _, _, cb -> cb(null) },
    onToggleArchive: (Boolean, (String?) -> Unit) -> Unit = { _, cb -> cb(null) },
    onDeleteLoan: ((String?) -> Unit) -> Unit = { cb -> cb(null) },
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    val semantic = DropletTheme.semanticColors
    var activeSheet by remember { mutableStateOf<LoanDetailActiveSheet?>(null) }

    val summary = uiState.summary

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_loan_detail"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("loan_detail_back"),
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
                            text = summary?.plan?.name ?: "分期贷款详情",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (summary != null) {
                            val subtitle = listOf(
                                AssetsFormatters.loanMethodLabel(summary.plan.method),
                                AssetsFormatters.formatLoanRateOrFeeSummary(summary.plan, hidden = amountsHidden),
                                "每月 ${summary.plan.monthlyRepayDay} 日",
                            ).joinToString(" · ")
                            Text(
                                text = subtitle,
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
                            onClick = { activeSheet = LoanDetailActiveSheet.EditParams },
                            modifier = Modifier.testTag("loan_edit_params_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "维护贷款参数",
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
                    text = if (uiState.isLoading) "正在加载分期还款计划…" else "未找到该分期贷款",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        val plan = summary.plan
        val nextPending = summary.nextPendingInstallment

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("loan_detail_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "loan_hero_card") {
                LoanDetailHeroCard(
                    summary = summary,
                    loanAccountBalance = uiState.loanAccountBalance,
                )
            }

            item(key = "loan_actions_card") {
                DropletCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                if (nextPending != null) {
                                    activeSheet = LoanDetailActiveSheet.RepayInstallment(nextPending)
                                }
                            },
                            enabled = nextPending != null && !plan.isArchived && !uiState.isSubmitting,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1.35f)
                                .height(46.dp)
                                .testTag("loan_repay_next_button"),
                        ) {
                            Text(
                                text = if (nextPending != null) {
                                    "确认第 ${nextPending.periodNumber} 期还款"
                                } else {
                                    "已全部结清"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = { activeSheet = LoanDetailActiveSheet.EarlyPayoff },
                            enabled = summary.remainingPeriods > 0 && !plan.isArchived && !uiState.isSubmitting,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(0.95f)
                                .height(46.dp)
                                .testTag("loan_early_payoff_button"),
                        ) {
                            Text("提前结清", maxLines = 1)
                        }
                    }
                }
            }

            item(key = "loan_automation_card") {
                DropletCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "到期自动生成复式分录",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val remindLabel = when (val days = plan.remindDaysBefore) {
                                null -> "未开启本地提醒"
                                0 -> "到期日当天提醒"
                                else -> "提前 $days 天提醒"
                            }
                            Text(
                                text = "$remindLabel · 付款户：${uiState.paymentAccount?.name ?: "默认账户"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = plan.autoPost,
                            onCheckedChange = onToggleAutoPost,
                            enabled = !plan.isArchived && !uiState.isSubmitting,
                            modifier = Modifier.testTag("loan_auto_post_switch"),
                        )
                    }

                    if (plan.remindDaysBefore != null && !notificationPermissionGranted) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("loan_notification_permission_banner"),
                            shape = RoundedCornerShape(12.dp),
                            color = semantic.warningContainer,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "未授予系统通知权限，开启后可在贷款到期前收到本地提醒",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = semantic.warning,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                FilledTonalButton(
                                    onClick = onRequestNotificationPermission,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("loan_request_notification_permission"),
                                ) {
                                    Text("开启通知", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }

            item(key = "loan_schedule_tabs") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropletSectionHeader(
                        title = "逐期还款计划表",
                        trailing = "已还 ${summary.paidPeriods} / ${summary.totalPeriods} 期",
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val counts = mapOf(
                            LoanScheduleFilterTab.ALL to summary.installments.size,
                            LoanScheduleFilterTab.PENDING to summary.remainingPeriods,
                            LoanScheduleFilterTab.PAID to summary.paidPeriods,
                        )
                        LoanScheduleFilterTab.entries.forEach { tab ->
                            FilterChip(
                                selected = uiState.selectedTab == tab,
                                onClick = { onSelectTab(tab) },
                                label = { Text("${tab.label} (${counts[tab] ?: 0})") },
                                modifier = Modifier.testTag("loan_schedule_filter_${tab.name.lowercase()}"),
                            )
                        }
                    }
                }
            }

            item(key = "loan_schedule_card") {
                val rows = uiState.visibleInstallments
                DropletCard {
                    if (rows.isEmpty()) {
                        Text(
                            text = when (uiState.selectedTab) {
                                LoanScheduleFilterTab.PENDING -> "全部期次均已结清"
                                LoanScheduleFilterTab.PAID -> "尚未有已入账期次"
                                LoanScheduleFilterTab.ALL -> "暂无还款计划"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        rows.forEachIndexed { index, installment ->
                            LoanInstallmentScheduleRow(
                                summary = summary,
                                installment = installment,
                                canRepay = !plan.isArchived && !uiState.isSubmitting,
                                onRepayClick = {
                                    activeSheet = LoanDetailActiveSheet.RepayInstallment(installment)
                                },
                            )
                            if (index != rows.lastIndex) {
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
        when (val sheet = activeSheet) {
            is LoanDetailActiveSheet.RepayInstallment -> LoanInstallmentRepaymentSheet(
                summary = summary,
                installment = sheet.installment,
                paymentAccounts = uiState.paymentAccounts,
                interestExpenseAccountName = uiState.interestExpenseAccount?.name ?: "利息手续费",
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onConfirm = onRepayInstallment,
            )
            LoanDetailActiveSheet.EarlyPayoff -> LoanEarlyPayoffSheet(
                summary = summary,
                paymentAccounts = uiState.paymentAccounts,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onConfirm = onEarlyPayoff,
            )
            LoanDetailActiveSheet.EditParams -> LoanParametersSheet(
                summary = summary,
                paymentAccounts = uiState.paymentAccounts,
                expenseCategories = uiState.expenseCategories,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSave = onUpdateParameters,
                onToggleArchive = onToggleArchive,
                onDelete = onDeleteLoan,
            )
            null -> Unit
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoanDetailHeroCard(
    summary: LoanPlanSummary,
    loanAccountBalance: Money?,
) {
    val semantic = DropletTheme.semanticColors
    val plan = summary.plan

    val (badgeLabel, badgeContainer, badgeContent) = when (summary.status) {
        LoanPlanStatus.PAID_OFF -> Triple(
            "已全部结清",
            semantic.incomeContainer,
            semantic.income,
        )
        LoanPlanStatus.OVERDUE -> Triple(
            "已逾期 ${summary.overdueDays} 天",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        LoanPlanStatus.DUE_SOON -> Triple(
            if (summary.isNextDueToday) "今日到期还款" else "距下期 ${summary.daysUntilNextDue} 天",
            semantic.warningContainer,
            semantic.warning,
        )
        LoanPlanStatus.ACTIVE -> Triple(
            summary.daysUntilNextDue?.let { "距下期 $it 天" } ?: "还款中",
            MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
            MaterialTheme.colorScheme.onSurface,
        )
    }

    val progressRatio = if (summary.totalPrincipal.isPositive) {
        BigDecimal.valueOf(summary.paidPrincipal.amountMinor)
            .divide(BigDecimal.valueOf(summary.totalPrincipal.amountMinor), 4, RoundingMode.HALF_UP)
            .toFloat()
            .coerceIn(0f, 1f)
    } else {
        1f
    }

    DropletHeroCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "待还剩余本金 (${plan.currency.code})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            DropletPillBadge(
                text = badgeLabel,
                containerColor = badgeContainer,
                contentColor = badgeContent,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        PrivacyAmountText(
            money = summary.remainingPrincipal,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.testTag("loan_detail_remaining_principal"),
        )

        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "已还本金",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                Spacer(modifier = Modifier.height(2.dp))
                PrivacyAmountText(
                    money = summary.paidPrincipal,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "待还息费",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                Spacer(modifier = Modifier.height(2.dp))
                PrivacyAmountText(
                    money = summary.remainingInterest,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "负债户余额",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                Spacer(modifier = Modifier.height(2.dp))
                PrivacyAmountText(
                    money = loanAccountBalance ?: -summary.remainingPrincipal,
                    style = MaterialTheme.typography.titleSmall,
                    color = if ((loanAccountBalance ?: -summary.remainingPrincipal).isNegative) {
                        semantic.expense
                    } else {
                        semantic.income
                    },
                    modifier = Modifier.testTag("loan_detail_account_balance"),
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { progressRatio },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = if (summary.isPaidOff) semantic.income else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f),
            strokeCap = StrokeCap.Round,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "已还 ${summary.paidPeriods}/${summary.totalPeriods} 期 · 本金进度 ${summary.repaidProgressPctDecimal}%",
                style = MaterialTheme.typography.labelSmall.tabularNums(),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.84f),
                modifier = Modifier.testTag("loan_detail_progress_label"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "总本金",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                PrivacyAmountText(
                    money = summary.totalPrincipal,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            summary.nextPendingInstallment?.let { next ->
                val dueLabel = summary.nextDueDate?.let { AssetsFormatters.formatLocalDateIso(it) }
                    ?: AssetsFormatters.formatIsoDate(next.dueAt, summary.zoneId)
                DropletPillBadge(
                    text = "下期第 ${next.periodNumber} 期 · $dueLabel",
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (plan.autoPost) {
                DropletPillBadge(
                    text = "自动入账已开启",
                    containerColor = semantic.incomeContainer,
                    contentColor = semantic.income,
                )
            }
            if (plan.isArchived) {
                DropletPillBadge(
                    text = "已归档",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoanInstallmentScheduleRow(
    summary: LoanPlanSummary,
    installment: LoanInstallment,
    canRepay: Boolean,
    onRepayClick: () -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val currency = summary.plan.currency
    val dueDate = Instant.ofEpochMilli(installment.dueAt).atZone(summary.zoneId).toLocalDate()
    val isPaid = installment.status == InstallmentStatus.PAID
    val isOverdue = !isPaid && dueDate.isBefore(summary.asOfDate)
    val isDueToday = !isPaid && dueDate == summary.asOfDate

    val (pillText, pillBg, pillFg) = when {
        isPaid -> Triple("已还", semantic.incomeContainer, semantic.income)
        isOverdue -> Triple("已逾期", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        isDueToday -> Triple("今日到期", semantic.warningContainer, semantic.warning)
        else -> Triple("待还", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .testTag("loan_installment_row_${installment.periodNumber}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1.1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "第 ${installment.periodNumber} 期",
                    style = MaterialTheme.typography.titleSmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                DropletPillBadge(
                    text = pillText,
                    containerColor = pillBg,
                    contentColor = pillFg,
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = AssetsFormatters.formatLocalDateIso(dueDate),
                style = MaterialTheme.typography.bodySmall.tabularNums(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrivacyAmountText(
                    money = Money(installment.principalMinor, currency),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "+ 息",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrivacyAmountText(
                    money = Money(installment.interestOrFeeMinor, currency),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            PrivacyAmountText(
                money = Money(installment.totalDueMinor, currency),
                style = MaterialTheme.typography.titleSmall,
                color = if (isPaid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (!isPaid) {
                FilledTonalButton(
                    onClick = onRepayClick,
                    enabled = canRepay,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("loan_installment_repay_${installment.periodNumber}"),
                ) {
                    Text(
                        text = "确认入账",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else {
                val paidLabel = installment.paidAt?.let {
                    "已还 ${AssetsFormatters.formatIsoDate(it, summary.zoneId)}"
                } ?: "已入账"
                Text(
                    text = paidLabel,
                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                    color = semantic.income,
                )
            }
        }
    }
}
