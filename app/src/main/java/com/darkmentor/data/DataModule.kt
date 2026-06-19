package com.darkmentor.data

import android.content.Context
import android.content.Context.MODE_PRIVATE
import com.darkmentor.data.btidalpool.BtidalpoolAuthRepository
import com.darkmentor.data.btidalpool.BtidalpoolClient
import com.darkmentor.data.btides.BTIDESRepository
import com.darkmentor.data.database.AppDatabase
import com.darkmentor.data.helpers.ActivityProvider
import com.darkmentor.data.helpers.BleFiltersProvider
import com.darkmentor.data.helpers.BleScannerHelper
import com.darkmentor.data.helpers.BrEdrDiscoveryHelper
import com.darkmentor.data.helpers.CluesRepository
import com.darkmentor.data.helpers.OuiRepository
import com.darkmentor.data.helpers.SdpEnumerationHelper
import com.darkmentor.data.helpers.IntentHelper
import com.darkmentor.data.helpers.LocationProvider
import com.darkmentor.data.helpers.NotificationsHelper
import com.darkmentor.data.helpers.PermissionHelper
import com.darkmentor.data.helpers.PowerModeHelper
import com.darkmentor.data.repo.CapturedAdvertFingerprintRepository
import com.darkmentor.data.repo.DevicesRepository
import com.darkmentor.data.repo.JournalRepository
import com.darkmentor.data.repo.LocationRepository
import com.darkmentor.data.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import org.koin.core.qualifier.named
import org.koin.dsl.module

class DataModule(
    private val sharedPreferencesName: String,
    private val btidalpoolAuthPreferencesName: String,
    private val appDatabaseName: String,
    private val globalScope: CoroutineScope,
) {
    val module = module {
        single { globalScope }
        single { BTIDESRepository(get()) }
        single { BtidalpoolClient(get()) }
        // Qualified inject: pull the BTIDALPOOL-only SharedPreferences (excluded from backup) for
        // token storage; pull the default app prefs as `legacyPrefs` so a one-shot migration
        // can move tokens out of the backed-up file on first run after upgrade.
        single {
            BtidalpoolAuthRepository(
                sharedPreferences = get(named(BTIDALPOOL_AUTH_PREF_QUALIFIER)),
                client = get(),
                legacyPrefs = get(),
            )
        }
        single { CluesRepository(get()) }
        single { OuiRepository(get()) }
        single { BleScannerHelper(get(), get(), get(), get(), get(), get()) }
        single { BrEdrDiscoveryHelper(get()) }
        single { SdpEnumerationHelper(get()) }
        single { BleFiltersProvider(get()) }
        single { get<Context>().getSharedPreferences(sharedPreferencesName, MODE_PRIVATE) }
        single(named(BTIDALPOOL_AUTH_PREF_QUALIFIER)) {
            get<Context>().getSharedPreferences(btidalpoolAuthPreferencesName, MODE_PRIVATE)
        }
        single { SettingsRepository(get()) }
        single { AppDatabase.build(get(), appDatabaseName) }
        single { DevicesRepository(get()) }
        single { CapturedAdvertFingerprintRepository(get()) }
        single { PermissionHelper(get(), get(), get()) }
        single { ActivityProvider() }
        single { IntentHelper(get(), get(), get()) }
        single { LocationProvider(get(), get(), get(), get()) }
        single { LocationRepository(get()) }
        single { JournalRepository(get()) }
        single { NotificationsHelper(get(), get(), get()) }
        single { PowerModeHelper(get(), get(), get()) }
    }

    companion object {
        const val BTIDALPOOL_AUTH_PREF_QUALIFIER = "btidalpool-auth-prefs"
    }
}