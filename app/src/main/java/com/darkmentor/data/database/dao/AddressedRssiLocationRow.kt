package com.darkmentor.data.database.dao

import androidx.room.ColumnInfo

/**
 * One location join carrying its device address. Bulk export queries return these rows once,
 * then build in-memory maps instead of issuing one or two Room queries for every device.
 */
data class AddressedRssiLocationRow(
    @ColumnInfo(name = "device_address") val deviceAddress: String,
    @ColumnInfo(name = "time") val time: Long,
    @ColumnInfo(name = "lat") val lat: Double,
    @ColumnInfo(name = "lng") val lng: Double,
    @ColumnInfo(name = "rssi") val rssi: Int?,
) {
    fun withoutAddress() = RssiLocationRow(time = time, lat = lat, lng = lng, rssi = rssi)
}
