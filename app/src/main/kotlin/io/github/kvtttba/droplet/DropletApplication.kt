package io.github.kvtttba.droplet

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.MarketSyncPreferencesRepository
import io.github.kvtttba.droplet.di.ApplicationScope
import io.github.kvtttba.droplet.notification.CreditCardReminderNotifier
import io.github.kvtttba.droplet.security.AppLockCoordinator
import io.github.kvtttba.droplet.worker.CreditCardReminderScheduler
import io.github.kvtttba.droplet.worker.MarketSyncScheduler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class DropletApplication : Application() {

    @Inject
    lateinit var ledgerRepository: LedgerRepository

    @Inject
    lateinit var appLockCoordinator: AppLockCoordinator

    @Inject
    lateinit var marketSyncPreferencesRepository: MarketSyncPreferencesRepository

    @Inject
    lateinit var marketSyncScheduler: MarketSyncScheduler

    @Inject
    lateinit var creditCardReminderPreferencesRepository: CreditCardReminderPreferencesRepository

    @Inject
    lateinit var creditCardReminderNotifier: CreditCardReminderNotifier

    @Inject
    lateinit var creditCardReminderScheduler: CreditCardReminderScheduler

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appLockCoordinator.onAppForegrounded()
        }

        override fun onStop(owner: LifecycleOwner) {
            appLockCoordinator.onAppBackgrounded()
        }
    }

    override fun onCreate() {
        super.onCreate()
        appLockCoordinator.start()
        creditCardReminderNotifier.ensureNotificationChannel()
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
        applicationScope.launch {
            ledgerRepository.ensureSeeded()
        }
        applicationScope.launch {
            marketSyncPreferencesRepository.preferences.collect { prefs ->
                marketSyncScheduler.syncSchedule(prefs)
            }
        }
        applicationScope.launch {
            creditCardReminderPreferencesRepository.preferences
                .map { it.remindersEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    creditCardReminderScheduler.syncSchedule(remindersEnabled = enabled)
                }
        }
    }
}
