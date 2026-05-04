package f.cking.software.domain.model

import android.content.Context
import f.cking.software.dateTimeStringFormatLocalized
import f.cking.software.domain.interactor.BuildDeviceClassFromSystemInfo
import f.cking.software.domain.interactor.BuildExtendedAddressInfoInteractor
import f.cking.software.getTimePeriodStr
import java.time.format.FormatStyle

data class DeviceData(
    val address: String,
    val name: String?,
    val lastDetectTimeMs: Long,
    val firstDetectTimeMs: Long,
    val manufacturerInfo: ManufacturerInfo?,
    val detectCount: Int,
    val customName: String?,
    val favorite: Boolean,
    val tags: Set<String>,
    val lastFollowingDetectionTimeMs: Long?,
    val rssi: Int?,
    val systemAddressType: Int?,
    val deviceClass: Int?,
    val isPaired: Boolean,
    val servicesUuids: List<String>,
    val rowDataEncoded: String?,
    val isConnectable: Boolean,
) {

    val resolvedDeviceClass: DeviceClass by lazy {
        BuildDeviceClassFromSystemInfo.execute(this)
    }

    val resolvedName: String? by lazy { name }

    val resolvedManufacturerName by lazy { manufacturerInfo?.name }

    fun knownLifetime(): Long {
        return lastDetectTimeMs - firstDetectTimeMs
    }

    fun buildDisplayName(): String {
        return customName?.takeIf { it.isNotBlank() }
            ?: name
            ?: address
    }

    fun firstDetectionPeriod(context: Context): String {
        return (System.currentTimeMillis() - firstDetectTimeMs).getTimePeriodStr(context)
    }

    fun firstDetectionExactTime(context: Context, formatStyle: FormatStyle = FormatStyle.SHORT): String {
        return firstDetectTimeMs.dateTimeStringFormatLocalized(formatStyle)
    }

    fun lastDetectionPeriod(context: Context): String {
        return (System.currentTimeMillis() - lastDetectTimeMs).getTimePeriodStr(context)
    }

    fun lastDetectionExactTime(context: Context, formatStyle: FormatStyle = FormatStyle.SHORT): String {
        return lastDetectTimeMs.dateTimeStringFormatLocalized(formatStyle)
    }

    fun hasBeenSeenTimeAgo(): Long {
        return System.currentTimeMillis() - lastDetectTimeMs
    }

    fun extendedAddressInfo(): ExtendedAddressInfo {
        return BuildExtendedAddressInfoInteractor.execute(this)
    }

    fun distance(): Float? {
        return if (rssi != null) {
            val txPower = -59 //hard coded power value. Usually ranges between -59 to -65
            val ratio = rssi * 1.0 / txPower
            val distance = if (ratio < 1.0) {
                Math.pow(ratio, 10.0)
            } else {
                (0.89976) * Math.pow(ratio, 7.7095) + 0.111
            }
            distance.toFloat()
        } else {
            null
        }
    }

    fun mergeWithNewDetected(new: DeviceData): DeviceData {
        return this.copy(
            detectCount = detectCount + 1,
            lastDetectTimeMs = new.lastDetectTimeMs,
            name = new.name,
            manufacturerInfo = new.manufacturerInfo,
            rssi = new.rssi,
            systemAddressType = new.systemAddressType,
            isPaired = new.isPaired,
            deviceClass = new.deviceClass,
            servicesUuids = new.servicesUuids,
            rowDataEncoded = new.rowDataEncoded,
            isConnectable = new.isConnectable,
        )
    }
}