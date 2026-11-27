package io.github.kvtttba.droplet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.CreditCardRepository
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementSummary
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferences
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreditCardReminderSettingsUiState(
    val remindersEnabled: Boolean = true,
    val defaultRemindDaysBefore: Int = CreditCardReminderPreferences.DEFAULT_REMIND_DAYS_BEFORE,
    val creditCardSummaries: List<CreditCardStatementSummary> = emptyList(),
    val userMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CreditCardReminderSettingsViewModel @Inject constructor(
    private val reminderPreferencesRepository: CreditCardReminderPreferencesRepository,
    private val creditCardRepository: CreditCardRepository,
) : ViewModel() {

    private val userMessageFlow = MutableStateFlow<String?>(null)

    private val summariesFlow = reminderPreferencesRepository.preferences
        .map { it.defaultRemindDaysBefore }
        .distinctUntilChanged()
        .flatMapLatest { defaultDays ->
            creditCardRepository.observeCreditCardSummaries(
                includeArchived = false,
                defaultRemindDaysBefore = defaultDays,
            )
        }

    val uiState: StateFlow<CreditCardReminderSettingsUiState> = combine(
        reminderPreferencesRepository.preferences,
        summariesFlow,
        userMessageFlow,
    ) { prefs, summaries, message ->
        CreditCardReminderSettingsUiState(
            remindersEnabled = prefs.remindersEnabled,
            defaultRemindDaysBefore = prefs.defaultRemindDaysBefore,
            creditCardSummaries = summaries,
            userMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = CreditCardReminderSettingsUiState(),
    )

    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            reminderPreferencesRepository.setRemindersEnabled(enabled)
            userMessageFlow.update {
                if (enabled) "已开启信用卡还款本地提醒（每日离线扫描）" else "已关闭信用卡还款本地提醒"
            }
        }
    }

    fun setDefaultRemindDaysBefore(days: Int) {
        if (days !in 0..CreditCardReminderPreferences.MAX_REMIND_DAYS_BEFORE) return
        viewModelScope.launch {
            reminderPreferencesRepository.setDefaultRemindDaysBefore(days)
            userMessageFlow.update {
                if (days == 0) {
                    "已将默认提醒窗口调整为还款日当天"
                } else {
                    "已将默认提醒窗口调整为提前 $days 天"
                }
            }
        }
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }
}
