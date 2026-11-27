package io.github.kvtttba.droplet

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.di.ApplicationScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class DropletApplication : Application() {

    @Inject
    lateinit var ledgerRepository: LedgerRepository

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            ledgerRepository.ensureSeeded()
        }
    }
}
