package com.darkmentor

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.darkmentor.data.DataModule
import com.darkmentor.domain.interactor.InteractorsModule
import com.darkmentor.ui.UiModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import timber.log.Timber

class TheApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        applyDynamicColors()
        initDi()
        initTimber()
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
    }

    fun restartKoin() {
        stopKoin()
        initDi()
    }

    private fun applyDynamicColors() {
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    private fun initDi() {
        startKoin {
            androidContext(this@TheApp)
            modules(
                DataModule(SHARED_PREF_NAME, BTIDALPOOL_AUTH_PREF_NAME, DATABASE_NAME, applicationScope).module,
                InteractorsModule.module,
                UiModule.module,
                module { single { this@TheApp } }
            )
        }
    }

    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    companion object {
        const val SHARED_PREF_NAME = "app-prefs"
        // BTIDALPOOL OAuth tokens live in their own SharedPreferences file so backup_rules.xml /
        // data_extraction_rules.xml can <exclude> *only* the auth file from cloud-backup and
        // device-transfer without losing user settings on restore. The file name (`.xml` is
        // appended automatically) is what the backup-rules path attribute matches against.
        const val BTIDALPOOL_AUTH_PREF_NAME = "btidalpool-auth"
        const val DATABASE_NAME = "app-database"
    }
}
