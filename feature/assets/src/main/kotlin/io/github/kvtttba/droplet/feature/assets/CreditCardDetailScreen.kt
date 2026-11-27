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
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementStatus
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementSummary
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.component.PrivacyToggleIconButton
import io.github.kvtttba.droplet.core.ui.icon.DropletIconRegistry
import io.github.kvtttba.droplet.core.ui.permission.rememberNotificationPermissionController
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.tabularNums
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter

private enum class CreditCardActiveSheet {
    REPAY,
    EDIT_PARAMS,
}

@Composable
fun CreditCardDetailRoute(
    onNavigateBack: () -> Unit,
    onToggleAmountsHidden: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: CreditCardDetailViewModel = hiltViewModel(),
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

    CreditCardDetailScreen(
        uiState = uiState,
        notificationPermissionGranted = permissionController.isGranted,
        onNavigateBack = onNavigateBack,
        onToggleAmountsHidden = onToggleAmountsHidden,
        onSelectTab = viewModel::selectTab,
        onToggleRemindersEnabled = { enabled ->
            viewModel.setRemindersEnabled(enabled)
            if (enabled && !permissionController.isGranted) {
                permissionController.requestOrOpenSettings()
            }
        },
        onRequestNotificationPermission = permissionController.requestOrOpenSettings,
        onRecordRepayment = viewModel::recordRepayment,
        onUpdateCreditParameters = viewModel::updateCreditParameters,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreditCardDetailScreen(
    uiState: CreditCardDetailUiState,
    notificationPermissionGranted: Boolean = true,
    onNavigateBack: () -> Unit = {},
    onToggleAmountsHidden: () -> Unit = {},
    onSelectTab: (CreditCardStatementTab) -> Unit = {},
    onToggleRemindersEnabled: (Boolean) -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onRecordRepayment: (
        paymentAccountId: String?,
        repaymentAmountText: String,
        paidFromAmountText: String,
        feeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit = { _, _, _, _, _, cb -> cb(null) },
    onUpdateCreditParameters: (
        limitText: String,
        billingDayText: String,
        dueDayText: String,
        remindDaysText: String,
        onComplete: (String?) -> Unit,
    ) -> Unit = { _, _, _, _, cb -> cb(null) },
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    val semantic = DropletTheme.semanticColors
    var activeSheet by remember { mutableStateOf<CreditCardActiveSheet?>(null) }

    val summary = uiState.summary

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_credit_card_detail"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("credit_card_detail_back"),
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
                            text = summary?.account?.name ?: "信用卡账单",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (summary != null) {
                            val scheduleLabel = buildList {
                                add(summary.account.currency.code)
                                summary.account.billingDay?.let { add("账单日 ${it}日") }
                                summary.account.dueDay?.let { add("还款日 ${it}日") }
                            }.joinToString(" · ")
                            Text(
                                text = scheduleLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (summary != null) {
                        IconButton(
                            onClick = { activeSheet = CreditCardActiveSheet.EDIT_PARAMS },
                            modifier = Modifier.testTag("credit_card_edit_params_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "编辑信用参数",
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
                    text = if (uiState.isLoading) "正在加载信用卡账单…" else "未找到该信用账户",
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
                .testTag("credit_card_detail_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "credit_hero_card") {
                CreditCardHeroCard(summary = summary)
            }

            item(key = "credit_actions_card") {
                DropletCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { activeSheet = CreditCardActiveSheet.REPAY },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1.3f)
                                .height(46.dp)
                                .testTag("credit_card_repay_button"),
                        ) {
                            Text(
                                text = when {
                                    summary.remainingStatementDue.isPositive -> "立即还款（本期账单）"
                                    summary.currentDebt.isPositive -> "立即还款"
                                    else -> "还款 / 预存溢缴款"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = { activeSheet = CreditCardActiveSheet.EDIT_PARAMS },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(0.9f)
                                .height(46.dp)
                                .testTag("credit_card_open_settings_button"),
                        ) {
                            Text("信用参数", maxLines = 1)
                        }
                    }
                }
            }

            item(key = "credit_reminder_card") {
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
                                text = "到期还款本地提醒",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val remindDetail = if (summary.isBillingScheduleConfigured) {
                                val sourceNote = if (summary.account.remindDaysBefore != null) "本卡独立设置" else "跟随全局默认"
                                val windowText = if (summary.effectiveRemindDaysBefore == 0) {
                                    "还款日当天提醒"
                                } else {
                                    "提前 ${summary.effectiveRemindDaysBefore} 天起每日提醒"
                                }
                                "$windowText · $sourceNote"
                            } else {
                                "请先设置账单日与还款日以启用自动扫描"
                            }
                            Text(
                                text = remindDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = uiState.remindersEnabled,
                            onCheckedChange = onToggleRemindersEnabled,
                            modifier = Modifier.testTag("credit_card_reminder_switch"),
                        )
                    }

                    if (uiState.remindersEnabled && !notificationPermissionGranted) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("credit_card_notification_permission_banner"),
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
                                    text = "未授予系统通知权限，开启后可在到期前收到本地还款提醒",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = semantic.warning,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                FilledTonalButton(
                                    onClick = onRequestNotificationPermission,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("credit_card_request_notification_permission"),
                                ) {
                                    Text("开启通知", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }

            item(key = "credit_statement_tabs") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropletSectionHeader(
                        title = "账单流水明细",
                        trailing = when (uiState.selectedTab) {
                            CreditCardStatementTab.BILLED -> summary.lastStatementPeriod?.let {
                                "${it.startDate.format(shortDateFormatter)} 至 ${it.endDate.format(shortDateFormatter)}"
                            } ?: "未配置周期"
                            CreditCardStatementTab.UNBILLED -> summary.unbilledPeriod?.let {
                                "${it.startDate.format(shortDateFormatter)} 至 ${it.endDate.format(shortDateFormatter)}"
                            } ?: "实时未出"
                            CreditCardStatementTab.ALL -> "共 ${uiState.allTransactions.size} 笔"
                        },
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val counts = mapOf(
                            CreditCardStatementTab.BILLED to uiState.billedTransactions.size,
                            CreditCardStatementTab.UNBILLED to uiState.unbilledTransactions.size,
                            CreditCardStatementTab.ALL to uiState.allTransactions.size,
                        )
                        CreditCardStatementTab.entries.forEach { tab ->
                            FilterChip(
                                selected = uiState.selectedTab == tab,
                                onClick = { onSelectTab(tab) },
                                label = { Text("${tab.label} (${counts[tab] ?: 0})") },
                                modifier = Modifier.testTag("credit_card_tab_${tab.name.lowercase()}"),
                            )
                        }
                    }
                }
            }

            item(key = "credit_statement_transactions_card") {
                DropletCard {
                    if (uiState.selectedTab == CreditCardStatementTab.BILLED &&
                        summary.statementPaidAfterClosing.isPositive
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = semantic.incomeContainer.copy(alpha = 0.7f),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "出账后已还 / 退款冲减",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = semantic.income,
                                )
                                PrivacyAmountText(
                                    money = summary.statementPaidAfterClosing,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = semantic.income,
                                    showPositiveSign = true,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    val rows = uiState.activeTransactions
                    if (rows.isEmpty()) {
                        Text(
                            text = when (uiState.selectedTab) {
                                CreditCardStatementTab.BILLED ->
                                    if (summary.isBillingScheduleConfigured) {
                                        "本期已出账单区间内暂无消费或还款流水"
                                    } else {
                                        "尚未设置账单日与还款日，请点击右上角或「信用参数」配置"
                                    }
                                CreditCardStatementTab.UNBILLED -> "当前未出账单区间暂无新增消费或还款"
                                CreditCardStatementTab.ALL -> "该信用账户暂无已入账流水"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        rows.forEachIndexed { index, item ->
                            CreditCardTransactionRow(item = item)
                            if (index != rows.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 48.dp),
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
            CreditCardActiveSheet.REPAY -> CreditCardRepaymentBottomSheet(
                summary = summary,
                paymentAccounts = uiState.paymentAccounts,
                exchangeRateTable = uiState.exchangeRateTable,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSubmit = onRecordRepayment,
            )
            CreditCardActiveSheet.EDIT_PARAMS -> CreditCardParametersBottomSheet(
                summary = summary,
                globalDefaultRemindDays = uiState.globalDefaultRemindDaysBefore,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSubmit = onUpdateCreditParameters,
            )
            null -> Unit
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreditCardHeroCard(summary: CreditCardStatementSummary) {
    val semantic = DropletTheme.semanticColors
    val (badgeLabel, badgeContainer, badgeContent) = when (summary.status) {
        CreditCardStatementStatus.OVERDUE -> Triple(
            "已逾期 ${summary.overdueDays} 天",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        CreditCardStatementStatus.DUE_SOON -> Triple(
            if (summary.isDueToday) "今日到期还款" else "距还款日 ${summary.daysUntilCurrentDue} 天",
            semantic.warningContainer,
            semantic.warning,
        )
        CreditCardStatementStatus.PENDING -> Triple(
            summary.daysUntilCurrentDue?.let { "距还款日 $it 天" } ?: "待还款",
            MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
            MaterialTheme.colorScheme.onSurface,
        )
        CreditCardStatementStatus.PAID_OFF -> Triple(
            "本期账单已还清",
            semantic.incomeContainer,
            semantic.income,
        )
        CreditCardStatementStatus.UNCONFIGURED -> Triple(
            "未设账单/还款日",
            MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val heroLabel = if (summary.isBillingScheduleConfigured) {
        "本期已出账单剩余应还 (${summary.account.currency.code})"
    } else {
        "当前总欠款 (${summary.account.currency.code})"
    }
    val heroMoney: Money = if (summary.isBillingScheduleConfigured) {
        summary.remainingStatementDue
    } else {
        summary.currentDebt
    }

    DropletHeroCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = heroLabel,
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
            money = heroMoney,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.testTag("credit_card_statement_due"),
        )
        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (summary.overpayment.isPositive) "当前溢缴款" else "当前总欠款",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                Spacer(modifier = Modifier.height(2.dp))
                PrivacyAmountText(
                    money = if (summary.overpayment.isPositive) summary.overpayment else summary.currentDebt,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (summary.overpayment.isPositive) {
                        semantic.income
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    showPositiveSign = summary.overpayment.isPositive,
                    modifier = Modifier.testTag("credit_card_current_debt"),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "本期未出账单",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                Spacer(modifier = Modifier.height(2.dp))
                PrivacyAmountText(
                    money = summary.unbilledRemainingDebt,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("credit_card_unbilled_amount"),
                )
            }
        }

        val limit = summary.creditLimit
        val available = summary.availableCredit
        if (limit != null && limit.isPositive && available != null) {
            val ratio = BigDecimal.valueOf(summary.usedLimit.amountMinor)
                .divide(BigDecimal.valueOf(limit.amountMinor), 4, RoundingMode.HALF_UP)
            val progress = ratio.toFloat().coerceIn(0f, 1f)
            val pctText = summary.utilizationPctDecimal?.let { "$it%" } ?: "0%"

            Spacer(modifier = Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = when {
                    progress >= 0.8f -> semantic.expense
                    progress >= 0.6f -> semantic.warning
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f),
                strokeCap = StrokeCap.Round,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    )
                    PrivacyAmountText(
                        money = summary.usedLimit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "($pctText)",
                        style = MaterialTheme.typography.labelSmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "可用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    )
                    PrivacyAmountText(
                        money = available,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.testTag("credit_card_available_credit"),
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "授信总额度",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                PrivacyAmountText(
                    money = limit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                )
                if (summary.overLimitAmount.isPositive) {
                    Text(
                        text = "· 已超额",
                        style = MaterialTheme.typography.labelSmall,
                        color = semantic.expense,
                    )
                    PrivacyAmountText(
                        money = summary.overLimitAmount,
                        style = MaterialTheme.typography.labelSmall,
                        color = semantic.expense,
                    )
                }
            }
        }

        if (summary.lastStatementDate != null || summary.displayDueDate != null) {
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                summary.nextStatementDate?.let { nextBill ->
                    val label = if (summary.isBillingDayToday) {
                        "今日出账（下期 ${nextBill.format(shortDateFormatter)}）"
                    } else {
                        "下次出账 ${nextBill.format(shortDateFormatter)}（${summary.daysUntilNextBilling}天后）"
                    }
                    DropletPillBadge(
                        text = label,
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
                summary.displayDueDate?.let { dueDate ->
                    val days = summary.daysUntilDisplayDue
                    val label = when {
                        summary.status == CreditCardStatementStatus.OVERDUE ->
                            "还款日 ${dueDate.format(shortDateFormatter)}（已逾期 ${summary.overdueDays} 天）"
                        days == 0 ->
                            "还款日 ${dueDate.format(shortDateFormatter)}（今日）"
                        days != null && days > 0 ->
                            "还款日 ${dueDate.format(shortDateFormatter)}（${days}天后）"
                        else ->
                            "还款日 ${dueDate.format(shortDateFormatter)}"
                    }
                    DropletPillBadge(
                        text = label,
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun CreditCardTransactionRow(item: CreditCardTransactionItem) {
    val semantic = DropletTheme.semanticColors
    val accent = DropletIconRegistry.parseColor(item.colorHex, MaterialTheme.colorScheme.primary)
    val amountColor = if (item.isRepaymentOrRefund) semantic.income else semantic.expense

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
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DropletIconRegistry.resolve(item.iconKey),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${item.dateTimeLabel} · ${item.subtitle}",
                style = MaterialTheme.typography.bodySmall.tabularNums(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        PrivacyAmountText(
            money = item.deltaAmount,
            style = MaterialTheme.typography.titleSmall,
            color = amountColor,
            showPositiveSign = item.isRepaymentOrRefund,
        )
    }
}

private val shortDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
