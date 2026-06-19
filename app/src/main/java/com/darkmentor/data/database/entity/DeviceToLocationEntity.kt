package com.darkmentor.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "device_to_location", indices = [Index(value = ["device_address", "location_time"])])
data class DeviceToLocationEntity(
    @ColumnInfo(name = "id") @PrimaryKey(autoGenerate = true) val id: Long? = null,
    @ColumnInfo(name = "device_address") val deviceAddress: String,
    @ColumnInfo(name = "location_time") val locationTime: Long,
    /**
     * RSSI of the strongest scan response that placed this device at this location, in dBm
     * (typically -100..0). Null on rows written before migration 21→22, or on rows where the
     * BLE scan that produced the join didn't carry an RSSI (rare).
     */
    @ColumnInfo(name = "rssi") val rssi: Int? = null,
)