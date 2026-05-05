package f.cking.software.data.repo

import f.cking.software.data.database.AppDatabase
import f.cking.software.data.database.DatabaseUtils
import f.cking.software.data.database.dao.RssiLocationRow
import f.cking.software.data.database.entity.DeviceToLocationEntity
import f.cking.software.domain.model.LocationModel
import f.cking.software.domain.toData
import f.cking.software.domain.toDomain
import f.cking.software.splitToBatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocationRepository(
    appDatabase: AppDatabase,
) {

    val locationDao = appDatabase.locationDao()

    suspend fun saveLocation(location: LocationModel, detectedAddresses: List<String>) {
        saveLocation(location, detectedAddresses.associateWith { null })
    }

    /**
     * Per-detection variant: each entry's value is the RSSI seen for that address in the same
     * batch as [location]. RSSI is what enables the highest-RSSI-lat-lng enrichment in BTIDES
     * export and the trilateration weighted-centroid best-fit marker. Null is allowed (row
     * stays joined but has no RSSI).
     */
    suspend fun saveLocation(location: LocationModel, detectionsByAddress: Map<String, Int?>) {
        withContext(Dispatchers.IO) {
            locationDao.saveLocation(location.toData())
            val rows = detectionsByAddress.map { (address, rssi) ->
                DeviceToLocationEntity(
                    deviceAddress = address,
                    locationTime = location.time,
                    rssi = rssi,
                )
            }
            locationDao.saveLocationToDevice(rows)
        }
    }

    suspend fun saveLocation(location: LocationModel) {
        withContext(Dispatchers.IO) {
            locationDao.saveLocation(location.toData())
        }
    }

    suspend fun getAllLocationsByAddress(
        deviceAddress: String,
        fromTime: Long = 0,
        toTime: Long = Long.MAX_VALUE,
    ): List<LocationModel> {
        return withContext(Dispatchers.IO) {
            locationDao.getAllLocationsByDeviceAddress(deviceAddress, fromTime, toTime)
                .map { it.toDomain() }
        }
    }

    /**
     * Returns lat/lng/RSSI for every detection of [deviceAddress] in [fromTime..toTime]. Rows
     * with NULL RSSI (older data) are still returned. Used by the trilateration weighted
     * centroid (best-fit marker on the device-details map).
     */
    suspend fun getRssiLocationsByAddress(
        deviceAddress: String,
        fromTime: Long = 0,
        toTime: Long = Long.MAX_VALUE,
    ): List<RssiLocationRow> {
        return withContext(Dispatchers.IO) {
            locationDao.getRssiLocationsByAddress(deviceAddress, fromTime, toTime)
        }
    }

    /**
     * Single highest-RSSI sample for [deviceAddress], or null. Used by the BTIDES export to
     * embed "where this device's signal was strongest" alongside the device's records.
     */
    suspend fun getStrongestRssiLocation(deviceAddress: String): RssiLocationRow? {
        return withContext(Dispatchers.IO) {
            locationDao.getStrongestRssiLocation(deviceAddress)
        }
    }

    suspend fun removeAllLocations() {
        withContext(Dispatchers.IO) {
            locationDao.removeAllLocations()
            locationDao.removeAllDeviceToLocation()
        }
    }

    suspend fun removeDeviceLocationsByAddresses(addresses: List<String>) {
        withContext(Dispatchers.IO) {
            addresses.splitToBatches(DatabaseUtils.getMaxSQLVariablesNumber()).forEach { addressesBatch ->
                locationDao.removeDeviceLocationsByAddresses(addresses)
            }
        }
    }
}