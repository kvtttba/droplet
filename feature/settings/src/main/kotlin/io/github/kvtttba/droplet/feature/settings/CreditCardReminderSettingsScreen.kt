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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementStatus
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementSummary
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferences
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.permission.rememberNotificationPermissionController
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme

@Composable
fun CreditCardReminderSettingsRoute(
    onNavigateBack: () -> Unit,
    onOpenCreditCardDetail: (String) -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: CreditCardReminderSettingsViewModel = hiltViewModel(),
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

    CreditCardReminderSettingsScreen(
        uiState = uiState,
        notificationPermissionGranted = permissionController.isGranted,
        onNavigateBack = onNavigateBack,
        onToggleRemindersEnabled = { enabled ->
            viewModel.setRemindersEnabled(enabled)
            if (enabled && !permissionController.isGranted) {
                permissionController.requestOrOpenSettings()
            }
        },
        onSelectDefaultRemindDays = viewModel::setDefaultRemindDaysBefore,
        onRequestNotificationPermission = permissionController.requestOrOpenSettings,
        onOpenSystemNotificationSettings = permissionController.openSystemSettings,
        onOpenCreditCardDetail = onOpenCreditCardDetail,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreditCardReminderSettingsScreen(
    uiState: CreditCardReminderSettingsUiState,
    notificationPermissionGranted: Boolean = true,
    onNavigateBack: () -> Unit = {},
    onToggleRemindersEnabled: (Boolean) -> Unit = {},
    onSelectDefaultRemindDays: (Int) -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onOpenSystemNotificationSettings: () -> Unit = {},
    onOpenCreditCardDetail: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val semantic = DropletTheme.semanticColors
    val configuredCount = uiState.creditCardSummaries.count { it.isBillingScheduleConfigured }
    val dueCandidateCount = uiState.creditCardSummaries.count {
        it.isInReminderWindow && it.remainingStatementDue.isPositive
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_credit_card_reminders"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "信用卡还款提醒",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("credit_card_reminders_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
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
                .testTag("credit_card_reminders_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "reminder_hero_card") {
                DropletHeroCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (uiState.remindersEnabled) {
                                    "还款本地提醒已开启"
                                } else {
                                    "还款本地提醒已关闭"
                                },
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (uiState.defaultRemindDaysBefore == 0) {
                                    "默认还款日当天提醒 · 离线定时检查"
                                } else {
                                    "默认提前 ${uiState.defaultRemindDaysBefore} 天提醒 · 离线定时检查"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DropletPillBadge(
                            text = "已配账单日 $configuredCount/${uiState.creditCardSummaries.size} 张",
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                        DropletPillBadge(
                            text = if (dueCandidateCount > 0) "待提醒 $dueCandidateCount 张" else "当前无临期账单",
                            containerColor = if (dueCandidateCount > 0) {
                                semantic.warningContainer
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                            },
                            contentColor = if (dueCandidateCount > 0) {
                                semantic.warning
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }

            item(key = "section_reminder_controls") {
                DropletSectionHeader(title = "提醒规则与通知权限")
            }

            item(key = "reminder_controls_card") {
                DropletCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "开启信用卡还款本地提醒",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "每日离线扫描处于提醒窗口内且仍有待还金额的信用卡，同日防重复推送",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = uiState.remindersEnabled,
                            onCheckedChange = onToggleRemindersEnabled,
                            modifier = Modifier.testTag("reminder_settings_enabled_switch"),
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "默认提前提醒天数（未单独设置的卡片生效）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CreditCardReminderPreferences.PRESET_REMIND_DAYS.forEach { days ->
                            FilterChip(
                                selected = uiState.defaultRemindDaysBefore == days,
                                onClick = { onSelectDefaultRemindDays(days) },
                                label = { Text(if (days == 0) "还款日当天" else "提前 $days 天") },
                                modifier = Modifier.testTag("reminder_default_days_$days"),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reminder_permission_card"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "系统通知权限",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                DropletPillBadge(
                                    text = if (notificationPermissionGranted) "已授权" else "未授权",
                                    containerColor = if (notificationPermissionGranted) {
                                        semantic.incomeContainer
                                    } else {
                                        semantic.warningContainer
                                    },
                                    contentColor = if (notificationPermissionGranted) {
                                        semantic.income
                                    } else {
                                        semantic.warning
                                    },
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (notificationPermissionGranted) {
                                    "通知渠道「信用卡还款提醒」已就绪，开启隐私隐身时将自动隐藏通知金额"
                                } else {
                                    "Android 13+ 需授予通知权限方可弹出本地提醒，拒绝不影响核心记账功能"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        if (notificationPermissionGranted) {
                            OutlinedButton(
                                onClick = onOpenSystemNotificationSettings,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("reminder_open_system_settings_button"),
                            ) {
                                Text("渠道设置", style = MaterialTheme.typography.labelMedium)
                            }
                        } else {
                            FilledTonalButton(
                                onClick = onRequestNotificationPermission,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("reminder_request_permission_button"),
                            ) {
                                Text("去授权", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            item(key = "section_cards_overview") {
                DropletSectionHeader(
                    title = "名下信用账户提醒配置",
                    trailing = "${uiState.creditCardSummaries.size} 张",
                )
            }

            item(key = "cards_overview_card") {
                DropletCard {
                    if (uiState.creditCardSummaries.isEmpty()) {
                        Text(
                            text = "暂无活跃信用账户，可在「账户管理」中新建信用卡并设置账单日与还款日。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        uiState.creditCardSummaries.forEachIndexed { index, summary ->
                            ReminderCardSummaryRow(
                                summary = summary,
                                onClick = { onOpenCreditCardDetail(summary.account.id) },
                            )
                            if (index != uiState.creditCardSummaries.lastIndex) {
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
}

@Composable
private fun ReminderCardSummaryRow(
    summary: CreditCardStatementSummary,
    onClick: () -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val account = summary.account
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DropletIcons.CreditCard,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = if (summary.isBillingScheduleConfigured) {
                "账单${account.billingDay}日 · 还款${account.dueDay}日 · 提前${summary.effectiveRemindDaysBefore}天"
            } else {
                "未配置账单日 / 还款日，点击设置"
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (summary.remainingStatementDue.isPositive) {
                PrivacyAmountText(
                    money = summary.remainingStatementDue,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (summary.status == CreditCardStatementStatus.OVERDUE) {
                        semantic.expense
                    } else {
                        semantic.warning
                    },
                )
            } else {
                Text(
                    text = if (summary.isBillingScheduleConfigured) "已还清" else "待配置",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (summary.isBillingScheduleConfigured) {
                        semantic.income
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
