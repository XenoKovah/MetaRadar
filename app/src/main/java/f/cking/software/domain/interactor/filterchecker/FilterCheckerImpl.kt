package f.cking.software.domain.interactor.filterchecker

import f.cking.software.checkRegexSafe
import f.cking.software.data.btides.BTIDESRepository
import f.cking.software.data.helpers.PowerModeHelper
import f.cking.software.domain.interactor.CheckDeviceLocationHistoryInteractor
import f.cking.software.domain.interactor.CheckUserLocationHistoryInteractor
import f.cking.software.domain.interactor.VendorIdentifier
import f.cking.software.domain.model.DeviceData
import f.cking.software.domain.model.DeviceFilter
import f.cking.software.domain.model.ManufacturerInfo
import f.cking.software.domain.model.Transport

class FilterCheckerImpl(
    private val powerModeHelper: PowerModeHelper,
    private val checkDeviceLocationHistoryInteractor: CheckDeviceLocationHistoryInteractor,
    private val checkUserLocationHistoryInteractor: CheckUserLocationHistoryInteractor,
    private val vendorIdentifier: VendorIdentifier,
    private val btidesRepository: BTIDESRepository,
) : FilterChecker<DeviceFilter>(powerModeHelper) {

    private val internalFilters: MutableList<FilterChecker<*>> = mutableListOf()

    private val lastDetectionInterval = filterChecker<DeviceFilter.LastDetectionInterval> { device, filter ->
        device.lastDetectTimeMs in filter.from..filter.to
    }
    private val firstDetectionInterval = filterChecker<DeviceFilter.FirstDetectionInterval>(useCache = true) { device, filter ->
        device.firstDetectTimeMs in filter.from..filter.to
    }
    private val name = filterChecker<DeviceFilter.Name>(useCache = true) { device, filter ->
        val regexMatch = device.resolvedName?.checkRegexSafe(filter.name) ?: false
        val noCaseSubstringMatch = device.resolvedName?.contains(filter.name, filter.ignoreCase) ?: false
        regexMatch || noCaseSubstringMatch
    }
    private val address = filterChecker<DeviceFilter.Address>(useCache = true) { device, filter ->
        device.address == filter.address
    }
    private val manufacturer = filterChecker<DeviceFilter.Manufacturer>(useCache = true) { device, filter ->
        // Apple's company id (0x004C) is also used by every iBeacon transmitter, including
        // non-Apple vendors like Tesla and Estimote. Samsung gets a similar broadened check
        // (OUI + advertised UUIDs) via VendorIdentifier so the "Not Samsung" quick-filter
        // matches the same set Connect All's "Skip Samsung" toggle excludes. Other vendors
        // use the cheap stored-id comparison.
        when (filter.manufacturerId) {
            ManufacturerInfo.APPLE_ID -> vendorIdentifier.isApple(device)
            ManufacturerInfo.SAMSUNG_ID -> vendorIdentifier.isSamsung(device)
            else -> device.manufacturerInfo?.id?.let { it == filter.manufacturerId } ?: false
        }
    }
    private val isPaired = filterChecker<DeviceFilter.IsPaired> { device, filter ->
        device.isPaired == filter.isPaired
    }
    private val addressType = filterChecker<DeviceFilter.AddressType>(useCache = true) { device, filter ->
        // The cached `extendedAddressInfo()` does the heavy classification work once per
        // DeviceData instance; filter rows by enum-name match against the user's selection.
        val deviceTypeName = device.extendedAddressInfo().type.name
        deviceTypeName in filter.typeNames
    }
    private val transportFilter = filterChecker<DeviceFilter.TransportFilter>(useCache = true) { device, filter ->
        // Delegate to [Transport.matchingOrdinalsForFilter] so this path and the SQL pushdown
        // in [DeviceFilterSqlBuilder] agree by construction on the BTC-includes-DUAL contract.
        device.transport.ordinal in Transport.matchingOrdinalsForFilter(filter.transportOrdinal, filter.includeDual)
    }
    private val hasGatt = filterChecker<DeviceFilter.HasGatt>(useCache = true) { device, filter ->
        // 5s-cached set lookup; under typical load this hits the cache for an entire scan
        // batch's worth of filter evaluations.
        val gattAddrs = btidesRepository.addressesWithGatt()
        val present = device.address.uppercase() in gattAddrs
        present == filter.hasGatt
    }
    private val any = filterChecker<DeviceFilter.Any> { device, filter ->
        filter.filters
            .any { check(device, it) }
    }
    private val all = filterChecker<DeviceFilter.All> { device, filter ->
        filter.filters
            .all { check(device, it) }
    }
    private val not = filterChecker<DeviceFilter.Not> { device, filter ->
        !check(device, filter.filter)
    }
    private val deviceLocation = filterChecker<DeviceFilter.DeviceLocation>(useCache = true) { device, filter ->
        checkDeviceLocationHistoryInteractor.execute(filter.location, filter.radiusMeters, device, filter.fromTimeMs, filter.toTimeMs)
    }
    private val userLocation = filterChecker<DeviceFilter.UserLocation> { device, filter ->
        checkUserLocationHistoryInteractor.execute(filter.location, filter.radiusMeters, filter.noLocationDefaultValue)
    }

    override suspend fun checkInternal(deviceData: DeviceData, filter: DeviceFilter): Boolean {
        return when (filter) {
            is DeviceFilter.LastDetectionInterval -> lastDetectionInterval.check(deviceData, filter)
            is DeviceFilter.FirstDetectionInterval -> firstDetectionInterval.check(deviceData, filter)
            is DeviceFilter.Name -> name.check(deviceData, filter)
            is DeviceFilter.Address -> address.check(deviceData, filter)
            is DeviceFilter.Manufacturer -> manufacturer.check(deviceData, filter)
            is DeviceFilter.IsPaired -> isPaired.check(deviceData, filter)
            is DeviceFilter.AddressType -> addressType.check(deviceData, filter)
            is DeviceFilter.TransportFilter -> transportFilter.check(deviceData, filter)
            is DeviceFilter.HasGatt -> hasGatt.check(deviceData, filter)
            is DeviceFilter.Any -> any.check(deviceData, filter)
            is DeviceFilter.All -> all.check(deviceData, filter)
            is DeviceFilter.Not -> not.check(deviceData, filter)
            is DeviceFilter.DeviceLocation -> deviceLocation.check(deviceData, filter)
            is DeviceFilter.UserLocation -> userLocation.check(deviceData, filter)
        }
    }

    override fun clearCache() {
        internalFilters.forEach { it.clearCache() }
    }

    override fun useCache(): Boolean {
        return false
    }

    private fun <T : DeviceFilter> filterChecker(
        useCache: Boolean = false,
        check: suspend (deviceData: DeviceData, filter: T) -> Boolean,
    ): FilterChecker<T> {

        val filter = object : FilterChecker<T>(powerModeHelper) {
            override suspend fun checkInternal(deviceData: DeviceData, filter: T): Boolean {
                return check.invoke(deviceData, filter)
            }

            override fun useCache(): Boolean {
                return useCache
            }
        }

        internalFilters.add(filter)

        return filter
    }
}
