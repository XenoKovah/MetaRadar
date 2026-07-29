package com.darkmentor.domain

import android.location.Location
import com.darkmentor.data.database.entity.AppleContactEntity
import com.darkmentor.data.database.entity.DeviceEntity
import com.darkmentor.data.database.entity.JournalEntryEntity
import com.darkmentor.data.database.entity.LocationEntity
import com.darkmentor.domain.model.AppleAirDrop
import com.darkmentor.domain.model.DeviceData
import com.darkmentor.domain.model.JournalEntry
import com.darkmentor.domain.model.LocationModel
import com.darkmentor.domain.model.ManufacturerInfo
import com.darkmentor.domain.model.Transport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

fun Location.toDomain(time: Long): LocationModel {
    return LocationModel(
        lat = this.latitude,
        lng = this.longitude,
        time = time,
    )
}

fun LocationModel.toData(): LocationEntity {
    return LocationEntity(
        time = time,
        lat = lat,
        lng = lng,
    )
}

fun LocationEntity.toDomain(): LocationModel {
    return LocationModel(lat, lng, time)
}

fun DeviceEntity.toDomain(appleAirDrop: AppleAirDrop? = null): DeviceData {
    return DeviceData(
        address = address,
        name = name,
        lastDetectTimeMs = lastDetectTimeMs,
        firstDetectTimeMs = firstDetectTimeMs,
        detectCount = detectCount,
        customName = customName,
        manufacturerInfo = manufacturerId?.let { id ->
            manufacturerName?.let { name -> ManufacturerInfo(id, name, appleAirDrop) }
        },
        rssi = lastSeenRssi,
        systemAddressType = systemAddressType,
        deviceClass = deviceClass,
        isPaired = isPaired,
        servicesUuids = serviceUuids,
        rowDataEncoded = rowDataEncoded,
        isConnectable = isConnectable,
        // Stored ordinals come from migration 24→25: 0=LE, 1=BREDR, 2=DUAL. Anything out of
        // range falls back to LE (the same default new rows get).
        transport = Transport.entries.getOrElse(transport) { Transport.LE },
        sdpUuids = sdpUuids,
        gattManufacturerName = gattManufacturerName,
    )
}

fun DeviceData.toData(): DeviceEntity {
    return DeviceEntity(
        address = address,
        name = name,
        lastDetectTimeMs = lastDetectTimeMs,
        firstDetectTimeMs = firstDetectTimeMs,
        detectCount = detectCount,
        customName = customName,
        manufacturerId = manufacturerInfo?.id,
        manufacturerName = manufacturerInfo?.name,
        lastSeenRssi = rssi,
        systemAddressType = systemAddressType,
        deviceClass = deviceClass,
        isPaired = isPaired,
        serviceUuids = servicesUuids,
        rowDataEncoded = rowDataEncoded,
        isConnectable = isConnectable,
        transport = transport.ordinal,
        sdpUuids = sdpUuids,
        gattManufacturerName = gattManufacturerName,
    )
}

fun AppleAirDrop.AppleContact.toData(associatedAddress: String): AppleContactEntity {
    return AppleContactEntity(
        sha256,
        associatedAddress,
        lastDetectTimeMs = lastDetectionTimeMs,
        firstDetectTimeMs = firstDetectionTimeMs
    )
}

fun AppleContactEntity.toDomain(): AppleAirDrop.AppleContact {
    return AppleAirDrop.AppleContact(
        sha256,
        lastDetectionTimeMs = lastDetectTimeMs,
        firstDetectionTimeMs = firstDetectTimeMs
    )
}

fun JournalEntryEntity.toDomain(): JournalEntry {
    return JournalEntry(
        id = id,
        timestamp = timestamp,
        report = json.decodeFromString(report),
    )
}

fun JournalEntry.toData(): JournalEntryEntity {
    return JournalEntryEntity(
        id = id,
        timestamp = timestamp,
        report = json.encodeToString(report),
    )
}

private val json = Json { ignoreUnknownKeys = true }
private inline fun <reified T> Json.decodeFromStringOrNull(str: String, ignoreUnknown: Boolean = true): T? {
    return try {
        decodeFromString<T>(str)
    } catch (e: Exception) {
        Timber.e(e)
        null
    }
}
