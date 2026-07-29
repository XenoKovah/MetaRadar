package com.darkmentor.domain.model

import android.content.Context
import com.darkmentor.dateTimeStringFormatLocalized
import com.darkmentor.domain.interactor.BuildDeviceClassFromSystemInfo
import com.darkmentor.domain.interactor.BuildExtendedAddressInfoInteractor
import com.darkmentor.getTimePeriodStr
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
    // GATT 0x2A29 (Manufacturer Name String) — peer-self-reported via Generic Access service.
    // Used as a 2nd-tier fallback for [resolvedManufacturerName] when the device has no MSD-
    // derived manufacturer info; preferred over IEEE OUI lookup because it's an explicit
    // peer claim rather than an inference from address bytes.
    val gattManufacturerName: String? = null,
) {

    val resolvedDeviceClass: DeviceClass by lazy {
        BuildDeviceClassFromSystemInfo.execute(this)
    }

    // Direct property — formerly `by lazy { name }`, which wrapped a one-line getter in a
    // synchronized Lazy holder. At ~1000 fresh DeviceData per snapshot replacement, the lazy
    // delegate's allocation + sync overhead added up; the property is just an alias for
    // [name] so a plain getter is strictly cheaper.
    val resolvedName: String? get() = name

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
            ?: gattManufacturerName?.takeIf { it.isNotBlank() }
            ?: run {
                if (cachedExtendedAddressInfo.type != ExtendedAddressInfo.BleAddressType.PUBLIC) return@run null
                // Use the process-wide cached OuiRepository reference instead of re-resolving
                // the Koin graph per-device. Each fresh snapshot can produce ~1000 DeviceData
                // and many of them hit this fallback (random addresses with no MSD); the
                // GlobalContext.get() + reflection-y get<T>() lookup adds up at that scale.
                ouiRepoLazy.lookupByAddress(address)
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

    private fun pickLongerName(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        a.length >= b.length -> a
        else -> b
    }

    fun mergeWithNewDetected(new: DeviceData): DeviceData {
        return this.copy(
            detectCount = detectCount + 1,
            lastDetectTimeMs = new.lastDetectTimeMs,
            // Pick the longer of the two names. Peers often advertise a truncated Local Name
            // (the AD payload is capped at 31 bytes) and expose the full long name on GATT
            // 0x2A00 — e.g. "HP" advertised vs "HP OfficeJet Pro 8020 series" on the
            // characteristic. We want display to always show the most informative variant,
            // so a fresh scan with a SHORTER local name doesn't silently overwrite the
            // longer GATT-sourced name (or vice versa). When the new scan doesn't carry a
            // name at all, keep the existing one.
            name = pickLongerName(new.name, this.name),
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
            // GATT-captured manufacturer name only ever arrives via [DevicesRepository.
            // setGattManufacturerNameIfMissing], not through scan-based detections. A fresh
            // detection has it null; preserve whatever we already captured.
            gattManufacturerName = new.gattManufacturerName ?: gattManufacturerName,
        )
    }

    companion object {
        // Process-wide single resolution of OuiRepository — Koin's GlobalContext.get() and
        // the subsequent reflection-y get<T>() lookup are NOT free at the per-device-snapshot
        // scale (1000 fresh DeviceData × every refresh, each computing
        // resolvedManufacturerName the first time it's read). Caching the ref in a top-level
        // lazy means the lookup happens exactly once per process; the OuiRepository itself
        // is a singleton so semantics are unchanged.
        private val ouiRepoLazy: com.darkmentor.data.helpers.OuiRepository by lazy {
            org.koin.core.context.GlobalContext.get()
                .get<com.darkmentor.data.helpers.OuiRepository>()
        }
    }
}
