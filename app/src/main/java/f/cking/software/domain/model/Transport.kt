package f.cking.software.domain.model

/**
 * Categorizes how a device was observed at the radio layer. Persisted by ordinal in the
 * `device.transport` column — ordinals are stable across releases, so don't reorder.
 *
 * - [LE]    : seen only via BLE advertisement scans (Android `DEVICE_TYPE_LE`).
 * - [BREDR] : seen only via BR/EDR inquiry (Android `DEVICE_TYPE_CLASSIC`).
 * - [DUAL]  : Android reports `DEVICE_TYPE_DUAL`, OR we've observed the same address on both
 *             transports across different scan/inquiry cycles.
 *
 * There is no `UNKNOWN` member — every device we surface has been observed on at least one
 * radio, so the transport is always determinable. Stale rows from older schema versions get
 * remapped to LE in migration 24→25 (the historical fallback).
 */
enum class Transport {
    LE, BREDR, DUAL;

    fun shortLabel(): String = when (this) {
        LE -> "LE"
        BREDR -> "BR"
        DUAL -> "Dual"
    }

    fun supportsGattOverLe(): Boolean = this == LE || this == DUAL
    fun isBrEdrOnly(): Boolean = this == BREDR

    companion object {
        /**
         * Convert from the raw Android `BluetoothDevice.DEVICE_TYPE_*` constant. Avoids importing
         * the Android constant into the domain model — callers pass the int through unchanged.
         * Falls back to [LE] for `DEVICE_TYPE_UNKNOWN` (0) and null because the only callers
         * that hit this path are LE scan results (BR/EDR inquiry sets the type explicitly), and
         * raw advertisement bytes are always LE-only.
         */
        fun fromAndroidDeviceType(deviceType: Int?): Transport = when (deviceType) {
            // BluetoothDevice.DEVICE_TYPE_CLASSIC == 1
            1 -> BREDR
            // BluetoothDevice.DEVICE_TYPE_DUAL == 3
            3 -> DUAL
            // 2 (DEVICE_TYPE_LE) and everything else (0 / null) treated as LE.
            else -> LE
        }

        /**
         * Combine two observed transports — used when we re-detect a device that was previously
         * seen on a different transport. LE + BREDR (in either order) → DUAL.
         */
        fun merge(prior: Transport, current: Transport): Transport = when {
            prior == current -> prior
            prior == DUAL || current == DUAL -> DUAL
            // One is LE and the other is BREDR.
            else -> DUAL
        }
    }
}
