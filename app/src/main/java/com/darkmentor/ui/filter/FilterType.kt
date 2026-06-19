package com.darkmentor.ui.filter

import androidx.annotation.StringRes
import com.darkmentor.R

enum class FilterType(@StringRes val displayNameRes: Int, @StringRes val displayDescription: Int) {
    BY_LOGIC_ANY(R.string.filter_any_of, R.string.filter_any_of_description),
    BY_LOGIC_ALL(R.string.filter_all_of, R.string.filter_all_of_description),
    BY_LOGIC_NOT(R.string.filter_not, R.string.filter_not_description),
    NAME(R.string.filter_by_name, R.string.filter_by_name_description),
    BY_MANUFACTURER(R.string.filter_by_manufacturer, R.string.filter_by_manufacturer_description),
    ADDRESS(R.string.filter_by_address, R.string.filter_by_address_description),
    // (`Min Lost Period` removed — wasn't useful as a primary filter, the `Last Detection`
    //  range filter expresses the same intent more flexibly.)
    BY_FIRST_DETECTION(R.string.filter_by_first_detection_period, R.string.filter_by_first_detection_period_description),
    BY_LAST_DETECTION(R.string.filter_by_last_detection_period, R.string.filter_by_last_detection_period_description),
    BY_IS_PAIRED(R.string.filter_by_is_paired, R.string.filter_by_is_paired_description),
    BY_ADDRESS_TYPE(R.string.filter_by_address_type, R.string.filter_by_address_type_description),
    BY_DEVICE_LOCATION(R.string.filter_device_location, R.string.filter_device_location_description),
    BY_USER_LOCATION(R.string.filter_user_location, R.string.filter_user_location_description),
}