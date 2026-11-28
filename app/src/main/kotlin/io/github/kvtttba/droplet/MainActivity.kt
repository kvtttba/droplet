package io.github.kvtttba.droplet

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.kvtttba.droplet.core.database.repository.AiDraftRepository
import io.github.kvtttba.droplet.core.database.repository.BudgetOverview
import io.github.kvtttba.droplet.core.database.repository.BudgetRepository
import io.github.kvtttba.droplet.core.database.repository.RecurringRuleRepository
import io.github.kvtttba.droplet.core.database.repository.TemplateRepository
import io.github.kvtttba.droplet.core.network.NetworkProxyConfig
import io.github.kvtttba.droplet.core.network.NetworkProxyType
import io.github.kvtttba.droplet.core.security.AiConfigPreferencesRepository
import io.github.kvtttba.droplet.core.security.AiServiceConfig
import io.github.kvtttba.droplet.core.security.BiometricAuthResult
import io.github.kvtttba.droplet.core.security.BiometricAuthenticator
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferences
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.LockStatus
import io.github.kvtttba.droplet.core.security.MarketSyncPreferences
import io.github.kvtttba.droplet.core.security.MarketSyncPreferencesRepository
import io.github.kvtttba.droplet.core.security.NetworkProxyPreferencesRepository
import io.github.kvtttba.droplet.feature.aicapture.PendingAiSharePayload
import io.github.kvtttba.droplet.notification.CreditCardReminderNotificationPublisher
import io.github.kvtttba.droplet.notification.DebtReminderNotificationPublisher
import io.github.kvtttba.droplet.notification.LoanReminderNotificationPublisher
import io.github.kvtttba.droplet.notification.PlanningAlertNotificationPublisher
import io.github.kvtttba.droplet.security.AppLockCoordinator
import io.github.kvtttba.droplet.share.ShareIntentParser
import io.github.kvtttba.droplet.ui.DropletApp
import io.github.kvtttba.droplet.ui.toAppThemePreference
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var appLockCoordinator: AppLockCoordinator

    @Inject
    lateinit var biometricAuthenticator: BiometricAuthenticator

    @Inject
    lateinit var marketSyncPreferencesRepository: MarketSyncPreferencesRepository

    @Inject
    lateinit var networkProxyPreferencesRepository: NetworkProxyPreferencesRepository

    @Inject
    lateinit var creditCardReminderPreferencesRepository: CreditCardReminderPreferencesRepository

    @Inject
    lateinit var aiConfigPreferencesRepository: AiConfigPreferencesRepository

    @Inject
    lateinit var aiDraftRepository: AiDraftRepository

    @Inject
    lateinit var budgetRepository: BudgetRepository

    @Inject
    lateinit var recurringRuleRepository: RecurringRuleRepository

    @Inject
    lateinit var templateRepository: TemplateRepository

    private val pendingCreditCardAccountIdFlow = MutableStateFlow<String?>(null)
    private val pendingLoanPlanIdFlow = MutableStateFlow<String?>(null)
    private val pendingDebtProfileIdFlow = MutableStateFlow<String?>(null)
    private val pendingAiSharePayloadFlow = MutableStateFlow<PendingAiSharePayload?>(null)
    private val pendingPlanningDestinationFlow = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appLockCoordinator.start()
        captureDeepLinks(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appLockCoordinator.preferences
                    .map { it.flagSecureEnabled }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        applyFlagSecure(enabled)
                    }
            }
        }

        setContent {
            val preferences by appLockCoordinator.preferences.collectAsStateWithLifecycle()
            val isHydrated by appLockCoordinator.isHydrated.collectAsStateWithLifecycle()
            val lockState by appLockCoordinator.lockState.collectAsStateWithLifecycle()
            val marketSyncPrefs by marketSyncPreferencesRepository.preferences
                .collectAsStateWithLifecycle(initialValue = MarketSyncPreferences())
            val proxyConfig by networkProxyPreferencesRepository.proxyConfig
                .collectAsStateWithLifecycle(initialValue = NetworkProxyConfig.NONE)
            val reminderPrefs by creditCardReminderPreferencesRepository.preferences
                .collectAsStateWithLifecycle(initialValue = CreditCardReminderPreferences())
            val aiServiceConfig by aiConfigPreferencesRepository.config
                .collectAsStateWithLifecycle(initialValue = AiServiceConfig())
            val pendingAiDraftCount by aiDraftRepository.observeActiveDraftCount()
                .collectAsStateWithLifecycle(initialValue = 0)
            val budgetOverview by budgetRepository.observeBudgetOverview(includeDisabled = true)
                .collectAsStateWithLifecycle(initialValue = BudgetOverview(targetMonth = YearMonth.now()))
            val recurringRules by recurringRuleRepository.observeRulesWithStatus()
                .collectAsStateWithLifecycle(initialValue = emptyList())
            val templates by templateRepository.observeTemplates()
                .collectAsStateWithLifecycle(initialValue = emptyList())
            val pendingCreditCardAccountId by pendingCreditCardAccountIdFlow.collectAsStateWithLifecycle()
            val pendingLoanPlanId by pendingLoanPlanIdFlow.collectAsStateWithLifecycle()
            val pendingDebtProfileId by pendingDebtProfileIdFlow.collectAsStateWithLifecycle()
            val pendingAiSharePayload by pendingAiSharePayloadFlow.collectAsStateWithLifecycle()
            val pendingPlanningDestination by pendingPlanningDestinationFlow.collectAsStateWithLifecycle()

            val marketSyncSummary = buildString {
                append(marketSyncPrefs.refreshInterval.label)
                if (marketSyncPrefs.wifiOnly && marketSyncPrefs.refreshInterval.isPeriodicEnabled) {
                    append(" · 仅 Wi-Fi")
                }
                append(" · ")
                append(
                    when {
                        !proxyConfig.isEnabled -> "直连"
                        proxyConfig.type == NetworkProxyType.HTTP -> "HTTP 代理"
                        else -> "SOCKS 代理"
                    },
                )
            }
            val creditCardReminderSummary = if (reminderPrefs.remindersEnabled) {
                if (reminderPrefs.defaultRemindDaysBefore == 0) {
                    "已开启 · 还款日当天提醒"
                } else {
                    "已开启 · 默认提前 ${reminderPrefs.defaultRemindDaysBefore} 天提醒"
                }
            } else {
                "已关闭 · 点击开启本地提醒"
            }
            val budgetSummary = when {
                budgetOverview.progresses.isEmpty() -> "设置月/年总预算与分类消耗预警"
                budgetOverview.monthlyOverall != null -> {
                    val m = budgetOverview.monthlyOverall!!
                    "已配 ${budgetOverview.enabledCount} 项 · 本月已用 ${m.usedPercentageInt}%"
                }
                else -> "已配 ${budgetOverview.enabledCount} 项启用预算"
            }
            val pendingRecurringCount = recurringRules.count { it.isPendingConfirmation }
            val activeRecurringCount = recurringRules.count { !it.rule.isPaused && !it.isTerminated }
            val recurringSummary = when {
                recurringRules.isEmpty() -> "房租、工资与订阅自动入账或到期提醒"
                pendingRecurringCount > 0 -> "$activeRecurringCount 条启用 · $pendingRecurringCount 期待确认"
                else -> "$activeRecurringCount 条启用 · 共 ${recurringRules.size} 条规则"
            }
            val templateSummary = if (templates.isEmpty()) {
                "高频消费一键入账或预填表单"
            } else {
                "已配 ${templates.size} 个模板 · 一键入账"
            }

            LaunchedEffect(isHydrated, lockState.isOverlayVisible, lockState.status, lockState.lastErrorMessage) {
                if (
                    isHydrated &&
                    lockState.isOverlayVisible &&
                    lockState.status == LockStatus.LOCKED &&
                    lockState.lastErrorMessage == null
                ) {
                    triggerUnlockPrompt()
                }
            }

            DropletApp(
                preferences = preferences,
                isPreferencesHydrated = isHydrated,
                lockState = lockState,
                marketSyncSummary = marketSyncSummary,
                creditCardReminderSummary = creditCardReminderSummary,
                aiConfigSummary = aiServiceConfig.formatSummary(),
                budgetSummary = budgetSummary,
                recurringSummary = recurringSummary,
                templateSummary = templateSummary,
                monthlyBudgetProgress = budgetOverview.monthlyOverall,
                pendingAiDraftCount = pendingAiDraftCount,
                pendingCreditCardAccountId = pendingCreditCardAccountId,
                onConsumePendingCreditCardAccountId = { pendingCreditCardAccountIdFlow.value = null },
                pendingLoanPlanId = pendingLoanPlanId,
                onConsumePendingLoanPlanId = { pendingLoanPlanIdFlow.value = null },
                pendingDebtProfileId = pendingDebtProfileId,
                onConsumePendingDebtProfileId = { pendingDebtProfileIdFlow.value = null },
                pendingAiSharePayload = pendingAiSharePayload,
                onConsumePendingAiSharePayload = { pendingAiSharePayloadFlow.value = null },
                pendingPlanningDestination = pendingPlanningDestination,
                onConsumePendingPlanningDestination = { pendingPlanningDestinationFlow.value = null },
                onThemeModeChange = { mode ->
                    appLockCoordinator.setThemeMode(mode.toAppThemePreference())
                },
                onDynamicColorChange = appLockCoordinator::setDynamicColor,
                onToggleAmountsHidden = appLockCoordinator::toggleAmountsHidden,
                onRequestUnlock = ::triggerUnlockPrompt,
                onVerifyIdentityForEnable = ::verifyIdentityForLockEnable,
                onLockOverlayBackPressed = { moveTaskToBack(true) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureDeepLinks(intent)
    }

    private fun captureDeepLinks(intent: Intent?) {
        val accountId = intent?.getStringExtra(
            CreditCardReminderNotificationPublisher.EXTRA_CREDIT_CARD_ACCOUNT_ID,
        )?.takeIf { it.isNotBlank() }
        if (accountId != null) {
            pendingCreditCardAccountIdFlow.value = accountId
        }
        val loanPlanId = intent?.getStringExtra(
            LoanReminderNotificationPublisher.EXTRA_LOAN_PLAN_ID,
        )?.takeIf { it.isNotBlank() }
        if (loanPlanId != null) {
            pendingLoanPlanIdFlow.value = loanPlanId
        }
        val debtProfileId = intent?.getStringExtra(
            DebtReminderNotificationPublisher.EXTRA_DEBT_PROFILE_ID,
        )?.takeIf { it.isNotBlank() }
        if (debtProfileId != null) {
            pendingDebtProfileIdFlow.value = debtProfileId
        }
        val sharePayload = ShareIntentParser.parse(intent)
        if (sharePayload != null) {
            pendingAiSharePayloadFlow.value = sharePayload
        }
        val planningDest = intent?.getStringExtra(
            PlanningAlertNotificationPublisher.EXTRA_PLANNING_DESTINATION,
        )?.takeIf { it.isNotBlank() }
        if (planningDest != null) {
            pendingPlanningDestinationFlow.value = planningDest
        }
    }

    private fun applyFlagSecure(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun triggerUnlockPrompt() {
        if (!appLockCoordinator.onAuthenticationStarted()) return
        biometricAuthenticator.authenticate(
            activity = this,
            title = "解锁 Droplet 记账",
            subtitle = "验证指纹、面容或设备锁屏密码以继续",
            onSoftFailure = appLockCoordinator::onAuthenticationSoftFailure,
            onResult = appLockCoordinator::onAuthenticationResult,
        )
    }

    private fun verifyIdentityForLockEnable(onComplete: (Boolean) -> Unit) {
        biometricAuthenticator.authenticate(
            activity = this,
            title = "开启应用隐私锁",
            subtitle = "请先验证本机指纹、面容或锁屏密码以确认可正常解锁",
            onResult = { result ->
                onComplete(result is BiometricAuthResult.Succeeded)
            },
        )
    }
}
