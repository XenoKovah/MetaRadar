package com.darkmentor.data.database.dao

import androidx.room.ColumnInfo

/**
 * Projection row used by [LocationDao.getRssiLocationsByAddress] and
 * [LocationDao.getStrongestRssiLocation]. Carries one device-to-location join augmented with
 * the captured RSSI in dBm (typically -100..0). [rssi] can be null for rows written before
 * migration 21→22 — current callers either weight those at zero or filter them out.
 */
data class RssiLocationRow(
    @ColumnInfo(name = "time") val time: Long,
    @ColumnInfo(name = "lat") val lat: Double,
    @ColumnInfo(name = "lng") val lng: Double,
    @ColumnInfo(name = "rssi") val rssi: Int?,
)
