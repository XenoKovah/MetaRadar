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
    val rssi: Int?,
    val systemAddressType: Int?,
    val deviceClass: Int?,
    val isPaired: Boolean,
    val servicesUuids: List<String>,
    val rowDataEncoded: String?,
    val isConnectable: Boolean,
    val transport: Transport = Transport.LE,
    val sdpUuids: List<String> = emptyList(),
) {

    val resolvedDeviceClass: DeviceClass by lazy {
        BuildDeviceClassFromSystemInfo.execute(this)
    }

    val resolvedName: String? by lazy { name }

    /**
     * Manufacturer name with an IEEE OUI fallback. Precedence:
     *   1. MSD-derived manufacturer (the SIG company id from a 0xFF advertisement frame).
     *   2. IEEE OUI of the BD_ADDR — but only when the address is classified as PUBLIC.
     *      A random address that happens to coincide with an assigned OUI shouldn't be
     *      mis-attributed to that manufacturer.
     * The OUI repository is fetched lazily via [BuildExtendedAddressInfoInteractor]'s shared
     * GlobalContext access — same one-call-per-instance overhead as `cachedExtendedAddressInfo`.
     */
    val resolvedManufacturerName: String? by lazy {
        manufacturerInfo?.name?.takeIf { it.isNotBlank() }
            ?: run {
                if (cachedExtendedAddressInfo.type != ExtendedAddressInfo.BleAddressType.PUBLIC) return@run null
                val koin = org.koin.core.context.GlobalContext.get()
                val ouiRepo = koin.get<f.cking.software.data.helpers.OuiRepository>()
                ouiRepo.lookupByAddress(address)
            }
    }

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

    /**
     * Cached so the device list / details paths don't re-parse the address on every Compose
     * recomposition. The result depends only on `address` + `systemAddressType` + `isPaired`,
     * all of which are val-fields on this immutable data class — caching is safe for the
     * lifetime of the instance. (A fresh detection produces a fresh [DeviceData] via copy(),
     * which gets its own new lazy cache.)
     */
    private val cachedExtendedAddressInfo: ExtendedAddressInfo by lazy {
        BuildExtendedAddressInfoInteractor.execute(this)
    }

    fun extendedAddressInfo(): ExtendedAddressInfo = cachedExtendedAddressInfo

    /**
     * Cached so DeviceListItem (active-scan stream) doesn't recompute Math.pow on every
     * recomposition. RSSI is val, so the cached value is valid for the instance's lifetime —
     * a re-detection produces a new copy() with a fresh lazy.
     */
    private val cachedDistance: Float? by lazy {
        if (rssi == null) {
            null
        } else {
            val txPower = -59 // hard-coded; the BLE/BR-EDR txPower for most consumer peers
            // sits in the -59..-65 dBm range and we don't have a per-device override.
            val ratio = rssi * 1.0 / txPower
            val raw = if (ratio < 1.0) {
                Math.pow(ratio, 10.0)
            } else {
                (0.89976) * Math.pow(ratio, 7.7095) + 0.111
            }
            raw.toFloat()
        }
    }

    fun distance(): Float? = cachedDistance

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
            // A re-detection can promote LE→DUAL or BREDR→DUAL when we observe the same
            // address on a different transport across cycles. Preserve sdpUuids — fresh scan
            // observations don't include SDP results, only the SDP enumeration path does.
            transport = Transport.merge(transport, new.transport),
            sdpUuids = if (new.sdpUuids.isNotEmpty()) new.sdpUuids else sdpUuids,
        )
    }
}