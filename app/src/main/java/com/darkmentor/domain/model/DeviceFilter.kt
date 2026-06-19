package com.darkmentor.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
sealed class DeviceFilter(@Transient protected val checkDifficulty: Int = 0) {

    open fun getDifficulty(): Int = checkDifficulty

    @Serializable
    @SerialName("last_detection_interval")
    data class LastDetectionInterval(val from: Long, val to: Long) : DeviceFilter()

    @Serializable
    @SerialName("first_detection_interval")
    data class FirstDetectionInterval(val from: Long, val to: Long) : DeviceFilter()

    @Serializable
    @SerialName("name")
    data class Name(val name: String, val ignoreCase: Boolean) : DeviceFilter()

    @Serializable
    @SerialName("address")
    data class Address(val address: String) : DeviceFilter()

    @Serializable
    @SerialName("manufacturer")
    data class Manufacturer(val manufacturerId: Int) : DeviceFilter()

    /**
     * Match devices observed on a particular radio transport. Carries [Transport.ordinal] so
     * the filter is fully serialisable and resolves identically on the SQL and in-memory
     * paths. Use [Transport.BREDR] for the "BTC" filter chip — DUAL devices match it because
     * a dual-mode peer responded to BR/EDR inquiry at some point. Pass [includeDual] = false
     * to exclude DUAL.
     */
    @Serializable
    @SerialName("transport")
    data class TransportFilter(
        val transportOrdinal: Int,
        val includeDual: Boolean = true,
    ) : DeviceFilter()

    @Serializable
    @SerialName("is_paired")
    data class IsPaired(val isPaired: Boolean) : DeviceFilter()

    /**
     * Match devices whose latest scan observation marked them as connectable (LE
     * connectable-advertisement bit set, or BR/EDR inquiry-respondent which we always treat
     * as connectable). Stored on `device.is_connectable` so it pushes into SQL — the SQL
     * fast-path returns only the matching subset of the M=200k+ row table directly to the
     * Devices tab list.
     */
    @Serializable
    @SerialName("is_connectable")
    data class IsConnectable(val isConnectable: Boolean) : DeviceFilter()

    /**
     * Match devices whose resolved [ExtendedAddressInfo.BleAddressType] is in [types]. Carries
     * an enum-name list so the filter is fully serialisable. The address-type classification
     * is computed on the fly by [BuildExtendedAddressInfoInteractor] (using the address bytes
     * + observed lifetime + MSD vendor signal) so this filter can't be pushed to SQL — the
     * Kotlin filter chain runs against the cached `extendedAddressInfo()` value.
     */
    @Serializable
    @SerialName("address_type")
    data class AddressType(val typeNames: List<String>) : DeviceFilter()

    /**
     * Match devices that have at least one GATT (services + characteristics) record captured
     * in the BTIDES log. Used by the "GATT" quick-filter on the Devices tab to surface only
     * devices Connect All has actually enumerated. Cannot be SQL-pushed — GATT data lives in
     * the BTIDES sidecar index, not the Room schema — so the Kotlin filter chain handles it.
     */
    @Serializable
    @SerialName("has_gatt")
    data class HasGatt(val hasGatt: Boolean) : DeviceFilter(checkDifficulty = 1)

    @Serializable
    @SerialName("any")
    data class Any(val filters: List<DeviceFilter>) : DeviceFilter(checkDifficulty = 1) {
        override fun getDifficulty(): Int {
            return filters.sumOf { it.getDifficulty() } + checkDifficulty
        }
    }

    @Serializable
    @SerialName("all")
    data class All(val filters: List<DeviceFilter>) : DeviceFilter(checkDifficulty = 1) {
        override fun getDifficulty(): Int {
            return filters.sumOf { it.getDifficulty() } + checkDifficulty
        }
    }

    @Serializable
    @SerialName("not")
    data class Not(val filter: DeviceFilter) : DeviceFilter(checkDifficulty = 1) {
        override fun getDifficulty(): Int {
            return filter.getDifficulty() + checkDifficulty
        }
    }

    @Serializable
    @SerialName("device_location")
    data class DeviceLocation(
        val location: LocationModel,
        val radiusMeters: Float,
        val fromTimeMs: Long,
        val toTimeMs: Long,
    ) : DeviceFilter(checkDifficulty = 100)

    @Serializable
    @SerialName("user_location")
    data class UserLocation(
        val location: LocationModel,
        val radiusMeters: Float,
        val noLocationDefaultValue: Boolean,
    ) : DeviceFilter(checkDifficulty = 10)
}
