package com.darkmentor.domain.model

/**
 * Intermediate carrier from a single radio observation (BLE advertisement OR BR/EDR inquiry
 * result) before it's promoted to a [DeviceData] domain record by `BuildDeviceFromScanDataInteractor`.
 *
 * Despite the name, this is now transport-agnostic — BR/EDR results land here too with
 * `addressType = null` and `scanRecordRaw = null`. [deviceType] carries the raw Android
 * `BluetoothDevice.DEVICE_TYPE_*` constant so the interactor can derive the canonical
 * [Transport] without importing Android types into the domain layer.
 */
class BleScanDevice(
    val address: String,
    val name: String?,
    val scanTimeMs: Long,
    val scanRecordRaw: ByteArray?,
    val rssi: Int?,
    val addressType: Int?,
    val deviceClass: Int?,
    val isPaired: Boolean,
    val serviceUuids: List<String>,
    val isConnectable: Boolean,
    val deviceType: Int? = null,
)