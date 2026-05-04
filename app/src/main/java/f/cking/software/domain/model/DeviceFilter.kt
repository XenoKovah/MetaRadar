package f.cking.software.domain.model

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

    @Serializable
    @SerialName("is_favorite")
    data class IsFavorite(val favorite: Boolean) : DeviceFilter()

    @Serializable
    @SerialName("is_paired")
    data class IsPaired(val isPaired: Boolean) : DeviceFilter()

    @Serializable
    @SerialName("min_lost_time")
    data class MinLostTime(val minLostTime: Long) : DeviceFilter()

    @Serializable
    @SerialName("tag")
    data class ByTag(val tag: String) : DeviceFilter()

    @Serializable
    @SerialName("airdrop_contact")
    data class AppleAirdropContact(
        val contactStr: String,
        val airdropShaFormat: Int,
        val minLostTime: Long? = null,
    ) : DeviceFilter(checkDifficulty = 20)

    @Serializable
    @SerialName("is_following")
    data class IsFollowing(
        val followingDurationMs: Long,
        val followingDetectionIntervalMs: Long,
    ) : DeviceFilter(checkDifficulty = 50)

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
