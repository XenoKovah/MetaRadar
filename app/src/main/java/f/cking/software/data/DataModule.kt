package f.cking.software.data

import android.content.Context
import android.content.Context.MODE_PRIVATE
import f.cking.software.data.btidalpool.BtidalpoolAuthRepository
import f.cking.software.data.btidalpool.BtidalpoolClient
import f.cking.software.data.btides.BTIDESRepository
import f.cking.software.data.database.AppDatabase
import f.cking.software.data.helpers.ActivityProvider
import f.cking.software.data.helpers.BleFiltersProvider
import f.cking.software.data.helpers.BleScannerHelper
import f.cking.software.data.helpers.BrEdrDiscoveryHelper
import f.cking.software.data.helpers.CluesRepository
import f.cking.software.data.helpers.OuiRepository
import f.cking.software.data.helpers.SdpEnumerationHelper
import f.cking.software.data.helpers.IntentHelper
import f.cking.software.data.helpers.LocationProvider
import f.cking.software.data.helpers.NotificationsHelper
import f.cking.software.data.helpers.PermissionHelper
import f.cking.software.data.helpers.PowerModeHelper
import f.cking.software.data.repo.DevicesRepository
import f.cking.software.data.repo.JournalRepository
import f.cking.software.data.repo.LocationRepository
import f.cking.software.data.repo.SettingsRepository
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