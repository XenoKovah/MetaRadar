package com.darkmentor.domain.interactor

import com.darkmentor.domain.interactor.filterchecker.FilterCheckerImpl
import org.koin.dsl.module

object InteractorsModule {

    val module = module {
        single { FilterCheckerImpl(get(), get(), get(), get(), get()) }

        factory { ClearGarbageInteractor(get(), get(), get(), get()) }
        factory { ClearAllDevicesInteractor(get()) }
        factory { GetAllDevicesInteractor(get()) }
        factory { IsKnownDeviceInteractor() }
        factory { GetBleRecordFramesFromRawInteractor() }
        factory { GetManufacturerInfoFromRawBleInteractor(get(), get()) }
        factory { BuildDeviceFromScanDataInteractor(get()) }
        factory { GetAirdropInfoFromBleFrame() }
        factory { SaveReportInteractor(get()) }
        factory { BackupDatabaseInteractor(get(), get()) }
        factory { CreateBackupFileInteractor(get(), get()) }
        factory { SelectBackupFileInteractor(get(), get()) }
        factory { RestoreDatabaseInteractor(get(), get()) }
        factory { CreateBTIDESFileInteractor(get(), get()) }
        factory { ExportBTIDESInteractor(get(), get()) }
        factory { ClearBTIDESLogInteractor(get()) }
        factory { UploadToBtidalpoolInteractor(get(), get(), get(), get(), get()) }
        single { VendorIdentifier(get()) }
        factory { BulkEnumerateGattInteractor(get(), get(), get(), get(), get(), get(), get()) }
        factory { CheckDeviceLocationHistoryInteractor(get()) }
        factory { CheckUserLocationHistoryInteractor(get()) }
        factory { GetAppUsageDaysInteractor(get()) }
        factory { SaveFirstAppLaunchTimeInteractor(get()) }
        factory { SaveOrMergeBatchInteractor(get(), get(), get(), get(), get()) }
        factory { GetDatabaseInfoInteractor(get(), get()) }
    }
}