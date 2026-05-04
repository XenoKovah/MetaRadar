package f.cking.software.domain

import android.location.Location
import f.cking.software.data.database.entity.AppleContactEntity
import f.cking.software.data.database.entity.DeviceEntity
import f.cking.software.data.database.entity.JournalEntryEntity
import f.cking.software.data.database.entity.LocationEntity
import f.cking.software.domain.model.AppleAirDrop
import f.cking.software.domain.model.DeviceData
import f.cking.software.domain.model.JournalEntry
import f.cking.software.domain.model.LocationModel
import f.cking.software.domain.model.ManufacturerInfo
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
        favorite = favorite,
        manufacturerInfo = manufacturerId?.let { id ->
            manufacturerName?.let { name -> ManufacturerInfo(id, name, appleAirDrop) }
        },
        lastFollowingDetectionTimeMs = lastFollowingDetectionMs,
        tags = tags.toSet(),
        rssi = lastSeenRssi,
        systemAddressType = systemAddressType,
        deviceClass = deviceClass,
        isPaired = isPaired,
        servicesUuids = serviceUuids,
        rowDataEncoded = rowDataEncoded,
        isConnectable = isConnectable,
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
        favorite = favorite,
        manufacturerId = manufacturerInfo?.id,
        manufacturerName = manufacturerInfo?.name,
        lastFollowingDetectionMs = lastFollowingDetectionTimeMs,
        tags = tags.toList(),
        lastSeenRssi = rssi,
        systemAddressType = systemAddressType,
        deviceClass = deviceClass,
        isPaired = isPaired,
        serviceUuids = servicesUuids,
        rowDataEncoded = rowDataEncoded,
        metadata = null,
        isConnectable = isConnectable,
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