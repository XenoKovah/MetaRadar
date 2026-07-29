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

    /**
     * One strongest RSSI row per address in a single query. The explicit tie breakers make the
     * result deterministic when multiple detections share the same RSSI.
     */
    @Query("""
        SELECT
            dtl.device_address AS device_address,
            location.time AS time,
            location.lat AS lat,
            location.lng AS lng,
            dtl.rssi AS rssi
        FROM device_to_location AS dtl
        INNER JOIN location ON location.time = dtl.location_time
        WHERE dtl.rssi IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM device_to_location AS better
              WHERE better.device_address = dtl.device_address
                AND better.rssi IS NOT NULL
                AND (
                    better.rssi > dtl.rssi
                    OR (better.rssi = dtl.rssi AND better.location_time > dtl.location_time)
                    OR (
                        better.rssi = dtl.rssi
                        AND better.location_time = dtl.location_time
                        AND better.id > dtl.id
                    )
                )
          )
    """)
    fun getAllStrongestRssiLocations(): List<AddressedRssiLocationRow>

    /** Every address/location join in one query for upload exclusion-zone evaluation. */
    @Query("""
        SELECT
            dtl.device_address AS device_address,
            location.time AS time,
            location.lat AS lat,
            location.lng AS lng,
            dtl.rssi AS rssi
        FROM device_to_location AS dtl
        INNER JOIN location ON location.time = dtl.location_time
        ORDER BY dtl.device_address, dtl.location_time
    """)
    fun getAllAddressedRssiLocations(): List<AddressedRssiLocationRow>

    @Query("SELECT * FROM location")
    fun observeAllLocations(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM location ORDER BY time DESC LIMIT 1")
    fun getLatestLocation(): LocationEntity?

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
