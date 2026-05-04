package f.cking.software.domain.interactor.filterchecker

import f.cking.software.checkRegexSafe
import f.cking.software.data.helpers.PowerModeHelper
import f.cking.software.data.repo.DevicesRepository
import f.cking.software.domain.interactor.CheckDeviceIsFollowingInteractor
import f.cking.software.domain.interactor.CheckDeviceLocationHistoryInteractor
import f.cking.software.domain.interactor.CheckUserLocationHistoryInteractor
import f.cking.software.domain.interactor.VendorIdentifier
import f.cking.software.domain.model.AppleAirDrop
import f.cking.software.domain.model.DeviceData
import f.cking.software.domain.model.DeviceFilter
import f.cking.software.domain.model.ManufacturerInfo

class FilterCheckerImpl(
    private val checkDeviceIsFollowing: CheckDeviceIsFollowingInteractor,
    private val devicesRepository: DevicesRepository,
    private val powerModeHelper: PowerModeHelper,
    private val checkDeviceLocationHistoryInteractor: CheckDeviceLocationHistoryInteractor,
    private val checkUserLocationHistoryInteractor: CheckUserLocationHistoryInteractor,
    private val vendorIdentifier: VendorIdentifier,
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
        // non-Apple vendors like Tesla and Estimote. Defer to VendorIdentifier so the "Not
        // Apple" filter (and any user-built `Manufacturer(Apple)` filter) doesn't fold those
        // beacons in. For every other vendor, the cheap stored-id comparison is fine.
        if (filter.manufacturerId == ManufacturerInfo.APPLE_ID) {
            vendorIdentifier.isApple(device)
        } else {
            device.manufacturerInfo?.id?.let { it == filter.manufacturerId } ?: false
        }
    }
    private val isFavorite = filterChecker<DeviceFilter.IsFavorite> { device, filter ->
        device.favorite == filter.favorite
    }
    private val isPaired = filterChecker<DeviceFilter.IsPaired> { device, filter ->
        device.isPaired == filter.isPaired
    }
    private val minLostTime = filterChecker<DeviceFilter.MinLostTime> { device, filter ->
        System.currentTimeMillis() - device.lastDetectTimeMs >= filter.minLostTime
    }
    private val airdrop = filterChecker<DeviceFilter.AppleAirdropContact> { device, filter ->
        fun checkMinLostTime(contact: AppleAirDrop.AppleContact): Boolean {
            val currentTime = System.currentTimeMillis()
            return filter.minLostTime == null
                    || (contact.firstDetectionTimeMs == contact.lastDetectionTimeMs)
                    || (currentTime - contact.lastDetectionTimeMs >= filter.minLostTime)
        }
        device.manufacturerInfo?.airdrop?.contacts?.any { contact ->
            contact.sha256 == filter.airdropShaFormat && checkMinLostTime(contact)
        } == true
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
    private val isFollowing = filterChecker<DeviceFilter.IsFollowing> { deviceData, filter ->
        val detected = checkDeviceIsFollowing.execute(deviceData, filter.followingDurationMs, filter.followingDetectionIntervalMs)
        if (detected) {
            devicesRepository.saveFollowingDetection(deviceData, System.currentTimeMillis())
        }
        detected
    }
    private val deviceLocation = filterChecker<DeviceFilter.DeviceLocation>(useCache = true) { device, filter ->
        checkDeviceLocationHistoryInteractor.execute(filter.location, filter.radiusMeters, device, filter.fromTimeMs, filter.toTimeMs)
    }
    private val userLocation = filterChecker<DeviceFilter.UserLocation> { device, filter ->
        checkUserLocationHistoryInteractor.execute(filter.location, filter.radiusMeters, filter.noLocationDefaultValue)
    }
    private val tag = filterChecker<DeviceFilter.ByTag> { device, filter ->
        device.tags.contains(filter.tag)
    }

    override suspend fun checkInternal(deviceData: DeviceData, filter: DeviceFilter): Boolean {
        return when (filter) {
            is DeviceFilter.LastDetectionInterval -> lastDetectionInterval.check(deviceData, filter)
            is DeviceFilter.FirstDetectionInterval -> firstDetectionInterval.check(deviceData, filter)
            is DeviceFilter.Name -> name.check(deviceData, filter)
            is DeviceFilter.Address -> address.check(deviceData, filter)
            is DeviceFilter.Manufacturer -> manufacturer.check(deviceData, filter)
            is DeviceFilter.IsFavorite -> isFavorite.check(deviceData, filter)
            is DeviceFilter.IsPaired -> isPaired.check(deviceData, filter)
            is DeviceFilter.MinLostTime -> minLostTime.check(deviceData, filter)
            is DeviceFilter.AppleAirdropContact -> airdrop.check(deviceData, filter)
            is DeviceFilter.Any -> any.check(deviceData, filter)
            is DeviceFilter.All -> all.check(deviceData, filter)
            is DeviceFilter.Not -> not.check(deviceData, filter)
            is DeviceFilter.IsFollowing -> isFollowing.check(deviceData, filter)
            is DeviceFilter.DeviceLocation -> deviceLocation.check(deviceData, filter)
            is DeviceFilter.UserLocation -> userLocation.check(deviceData, filter)
            is DeviceFilter.ByTag -> tag.check(deviceData, filter)
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
