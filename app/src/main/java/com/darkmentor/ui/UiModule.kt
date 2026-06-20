package com.darkmentor.ui

import com.darkmentor.ui.backgroundlocationrequest.BackgroundLocationRequestViewModel
import com.darkmentor.ui.connectall.ConnectAllSession
import com.darkmentor.ui.connectall.ConnectAllViewModel
import com.darkmentor.ui.devicedetails.DeviceDetailsViewModel
import com.darkmentor.ui.devicelist.DeviceListViewModel
import com.darkmentor.ui.exclusionzones.ExclusionZonesViewModel
import com.darkmentor.ui.journal.JournalViewModel
import com.darkmentor.ui.main.MainViewModel
import com.darkmentor.ui.map.MapViewModel
import com.darkmentor.ui.selectdevice.SelectDeviceViewModel
import com.darkmentor.ui.selectmanufacturer.SelectManufacturerViewModel
import com.darkmentor.ui.settings.SettingsViewModel
import com.darkmentor.utils.navigation.Router
import com.darkmentor.utils.navigation.RouterImpl
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

object UiModule {
    val module = module {
        single { RouterImpl() }
        single<Router> { get<RouterImpl>() }
        // App-singleton holder for the Connect All session so the loop can be driven by either
        // the UI or the boot receiver. Lives in the application coroutine scope (DataModule).
        single { ConnectAllSession(get(), get()) }
        viewModel { MainViewModel(get(), get(), get(), get(), get(), get(), get()) }
        viewModel { DeviceListViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
        viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        viewModel { SelectManufacturerViewModel(get()) }
        viewModel { SelectDeviceViewModel(get(), get()) }
        viewModel { DeviceDetailsViewModel(address = it[0], get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        viewModel { JournalViewModel(get(), get(), get()) }
        viewModel { ConnectAllViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
        viewModel { MapViewModel(get(), get(), get()) }
        viewModel { BackgroundLocationRequestViewModel(get(), get()) }
        viewModel { ExclusionZonesViewModel(get(), get(), get()) }
    }
}