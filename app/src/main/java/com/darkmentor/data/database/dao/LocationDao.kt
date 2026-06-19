package com.darkmentor.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.darkmentor.data.database.entity.DeviceToLocationEntity
import com.darkmentor.data.database.entity.LocationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {

    @Query("""
        SELECT location.time, location.lat, location.lng
        FROM location
        INNER JOIN (
            SELECT location_time FROM device_to_location
            WHERE device_address = :address
              AND location_time BETWEEN :fromTime AND :toTime
        ) AS dtl
        ON dtl.location_time = location.time;
    """)
    fun getAllLocationsByDeviceAddress(address: String, fromTime: Long = 0, toTime: Long = Long.MAX_VALUE): List<LocationEntity>

    /**
     * Per-detection rows joined to their location (lat/lng/time) plus the captured RSSI. Used
     * by the trilateration weighted-centroid (best-fit marker) in DeviceDetailsViewModel.
     * Rows with NULL RSSI (older data, pre-migration-22) are still returned — the caller
     * decides how to weight them.
     */
    @Query("""
        SELECT location.time AS time, location.lat AS lat, location.lng AS lng, dtl.rssi AS rssi
        FROM device_to_location AS dtl
        INNER JOIN location ON location.time = dtl.location_time
        WHERE dtl.device_address = :address
          AND dtl.location_time BETWEEN :fromTime AND :toTime
    """)
    fun getRssiLocationsByAddress(address: String, fromTime: Long = 0, toTime: Long = Long.MAX_VALUE): List<RssiLocationRow>

    /**
     * Returns the (lat, lng, rssi) of the strongest sample we ever recorded for [address], or
     * null if we never associated this address with a location (offline or no GPS fix when it
     * was seen). Rows with NULL rssi are tie-broken to the back so they never beat a sample
     * with an actual RSSI.
     */
    @Query("""
        SELECT location.time AS time, location.lat AS lat, location.lng AS lng, dtl.rssi AS rssi
        FROM device_to_location AS dtl
        INNER JOIN location ON location.time = dtl.location_time
        WHERE dtl.device_address = :address
          AND dtl.rssi IS NOT NULL
        ORDER BY dtl.rssi DESC
        LIMIT 1
    """)
    fun getStrongestRssiLocation(address: String): RssiLocationRow?

    @Query("SELECT * FROM location")
    fun observeAllLocations(): Flow<List<LocationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveLocation(locationEntity: LocationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveLocationToDevice(deviceToLocationEntity: List<DeviceToLocationEntity>)

    @Query("DELETE FROM location")
    fun removeAllLocations()

    @Query("DELETE FROM device_to_location")
    fun removeAllDeviceToLocation()

    @Query("DELETE FROM device_to_location WHERE device_address IN (:addresses)")
    fun removeDeviceLocationsByAddresses(addresses: List<String>)
}