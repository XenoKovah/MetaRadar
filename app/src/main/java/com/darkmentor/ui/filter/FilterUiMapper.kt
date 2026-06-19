package com.darkmentor.ui.filter

import com.darkmentor.data.helpers.BluetoothSIG
import com.darkmentor.domain.model.DeviceFilter
import com.darkmentor.domain.model.ManufacturerInfo
import com.darkmentor.timeFromDateTime
import com.darkmentor.toLocalDate
import com.darkmentor.toLocalTime
import java.time.LocalDate
import java.time.LocalTime

object FilterUiMapper {


    fun mapToDomain(from: FilterUiState): DeviceFilter {
        return when (from) {
            is FilterUiState.Name -> DeviceFilter.Name(from.name, from.ignoreCase)
            is FilterUiState.Address -> DeviceFilter.Address(from.address)
            is FilterUiState.IsPaired -> DeviceFilter.IsPaired(from.isPaired)
            is FilterUiState.AddressType -> DeviceFilter.AddressType(from.selectedTypeNames.toList())
            is FilterUiState.Manufacturer -> DeviceFilter.Manufacturer(from.manufacturer!!.id)
            is FilterUiState.LastDetectionInterval -> DeviceFilter.LastDetectionInterval(
                from = mapTimeToUi(from.fromDate, from.fromTime, Long.MIN_VALUE),
                to = mapTimeToUi(from.toDate, from.toTime, Long.MAX_VALUE),
            )
            is FilterUiState.FirstDetectionInterval -> DeviceFilter.FirstDetectionInterval(
                from = mapTimeToUi(from.fromDate, from.fromTime, Long.MIN_VALUE),
                to = mapTimeToUi(from.toDate, from.toTime, Long.MAX_VALUE),
            )
            is FilterUiState.DeviceLocation -> DeviceFilter.DeviceLocation(
                location = from.targetLocation!!,
                radiusMeters = from.radius,
                fromTimeMs = mapTimeToUi(from.fromDate, from.fromTime, Long.MIN_VALUE),
                toTimeMs = mapTimeToUi(from.toDate, from.toTime, Long.MAX_VALUE),
            )
            is FilterUiState.UserLocation -> DeviceFilter.UserLocation(
                location = from.targetLocation!!,
                radiusMeters = from.radius,
                noLocationDefaultValue = from.defaultValueIfNoLocation,
            )
            is FilterUiState.Any -> DeviceFilter.Any(from.filters.map { mapToDomain(it) }.sortedBy { it.getDifficulty() })
            is FilterUiState.All -> DeviceFilter.All(from.filters.map { mapToDomain(it) }.sortedBy { it.getDifficulty() })
            is FilterUiState.Not -> DeviceFilter.Not(mapToDomain(from.filter!!))
            is FilterUiState.Unknown, is FilterUiState.Interval -> throw IllegalArgumentException("Unsupported type: ${from::class.java}")
        }
    }

    fun mapToUi(from: DeviceFilter): FilterUiState {
        return when (from) {
            is DeviceFilter.Name -> FilterUiState.Name().apply {
                this.name = from.name
                this.ignoreCase = from.ignoreCase
            }
            is DeviceFilter.Address -> FilterUiState.Address().apply {
                this.address = from.address
            }
            is DeviceFilter.Manufacturer -> FilterUiState.Manufacturer().apply {
                this.manufacturer = BluetoothSIG.bluetoothSIG[from.manufacturerId]?.let {
                    ManufacturerInfo(from.manufacturerId, it, null,)
                }
            }
            is DeviceFilter.IsPaired -> FilterUiState.IsPaired().apply {
                this.isPaired = from.isPaired
            }
            is DeviceFilter.AddressType -> FilterUiState.AddressType().apply {
                this.selectedTypeNames = from.typeNames.toSet()
            }
            is DeviceFilter.FirstDetectionInterval -> FilterUiState.FirstDetectionInterval().apply {
                this.fromDate = from.from.takeIf { it != Long.MIN_VALUE }?.toLocalDate()
                this.fromTime = from.from.takeIf { it != Long.MIN_VALUE }?.toLocalTime()
                this.toDate = from.to.takeIf { it != Long.MAX_VALUE }?.toLocalDate()
                this.toTime = from.to.takeIf { it != Long.MAX_VALUE }?.toLocalTime()
            }
            is DeviceFilter.LastDetectionInterval -> FilterUiState.LastDetectionInterval().apply {
                this.fromDate = from.from.takeIf { it != Long.MIN_VALUE }?.toLocalDate()
                this.fromTime = from.from.takeIf { it != Long.MIN_VALUE }?.toLocalTime()
                this.toDate = from.to.takeIf { it != Long.MAX_VALUE }?.toLocalDate()
                this.toTime = from.to.takeIf { it != Long.MAX_VALUE }?.toLocalTime()
            }
            is DeviceFilter.All -> FilterUiState.All().apply {
                this.filters = from.filters.map { mapToUi(it) }
            }
            is DeviceFilter.Any -> FilterUiState.Any().apply {
                this.filters = from.filters.map { mapToUi(it) }
            }
            is DeviceFilter.Not -> FilterUiState.Not().apply {
                this.filter = mapToUi(from.filter)
            }
            is DeviceFilter.DeviceLocation -> FilterUiState.DeviceLocation().apply {
                this.targetLocation = from.location
                this.radius = from.radiusMeters
                this.fromDate = from.fromTimeMs.takeIf { it != Long.MIN_VALUE }?.toLocalDate()
                this.fromTime = from.fromTimeMs.takeIf { it != Long.MIN_VALUE }?.toLocalTime()
                this.toDate = from.toTimeMs.takeIf { it != Long.MAX_VALUE }?.toLocalDate()
                this.toTime = from.toTimeMs.takeIf { it != Long.MAX_VALUE }?.toLocalTime()
            }
            is DeviceFilter.UserLocation -> FilterUiState.UserLocation().apply {
                this.targetLocation = from.location
                this.radius = from.radiusMeters
                this.defaultValueIfNoLocation = from.noLocationDefaultValue
            }
            // TransportFilter (BTC chip), HasGatt (GATT chip), and IsConnectable (Connectable
            // chip) are quick-filters with no editable parameters, never opened in the
            // FilterUiState builder. Surface a placeholder Unknown state so the editor doesn't
            // crash if a saved filter somehow contains one.
            is DeviceFilter.TransportFilter -> FilterUiState.Unknown()
            is DeviceFilter.HasGatt -> FilterUiState.Unknown()
            is DeviceFilter.IsConnectable -> FilterUiState.Unknown()
        }
    }

    private fun mapTimeToUi(date: LocalDate?, time: LocalTime?, defaultValue: Long): Long {
        return if (date != null && time != null) {
            timeFromDateTime(date, time)
        } else {
            defaultValue
        }
    }
}
