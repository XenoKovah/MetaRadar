package com.darkmentor

object TheAppConfig {
    const val DEFAULT_KNOWN_DEVICE_PERIOD_MS = 1000L * 60L * 60L // 1 hour
    const val DEVICE_GARBAGING_TIME = 1000L * 60L * 60L * 12L // 12 hours
    const val DEFAULT_LOCATION_FILTER_RADIUS = 100F // 100 meters
    const val USE_GPS_LOCATION_ONLY = false
}