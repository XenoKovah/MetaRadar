package f.cking.software.domain.model

/**
 * Categorizes how a device was observed at the radio layer. Persisted by ordinal in the
 * `device.transport` column — ordinals are stable across releases, so don't reorder.
 *
 * - [LE]    : seen only via BLE advertisement scans (Android `DEVICE_TYPE_LE`).
 * - [BREDR] : seen only via BR/EDR inquiry (Android `DEVICE_TYPE_CLASSIC`).
 * - [DUAL]  : Android reports `DEVICE_TYPE_DUAL`, OR we've observed the same address on both
 *             transports across different scan/inquiry cycles.
 * - [UNKNOWN]: legacy rows from before we tracked this, or Android returned `DEVICE_TYPE_UNKNOWN`
 *             and no observation source has clarified the device's nature yet.
 */
enum class Transport {
    UNKNOWN, LE, BREDR, DUAL;

    fun shortLabel(): String = when (this) {
        LE -> "LE"
        BREDR -> "BR"
        DUAL -> "Dual"
        UNKNOWN -> ""
    }

    fun supportsGattOverLe(): Boolean = this == LE || this == DUAL
    fun isBrEdrOnly(): Boolean = this == BREDR

    companion object {
        /**
         * Convert from the raw Android `BluetoothDevice.DEVICE_TYPE_*` constant. Avoids importing
         * the Android constant into the domain model — callers pass the int through unchanged.
         */
        fun fromAndroidDeviceType(deviceType: Int?): Transport = when (deviceType) {
            // BluetoothDevice.DEVICE_TYPE_CLASSIC == 1
            1 -> BREDR
            // BluetoothDevice.DEVICE_TYPE_LE == 2
            2 -> LE
            // BluetoothDevice.DEVICE_TYPE_DUAL == 3
            3 -> DUAL
            else -> UNKNOWN
        }

        /**
         * Combine two observed transports — used when we re-detect a device that was previously
         * seen on a different transport. LE + BREDR (in either order) → DUAL. UNKNOWN is treated
         * as "no information" and is replaced by anything more specific.
         */
        fun merge(prior: Transport, current: Transport): Transport = when {
            prior == current -> prior
            prior == UNKNOWN -> current
            current == UNKNOWN -> prior
            prior == DUAL || current == DUAL -> DUAL
            // One is LE and the other is BREDR.
            else -> DUAL
        }
    }
}
