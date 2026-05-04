package f.cking.software.domain.interactor

import f.cking.software.domain.model.BleScanDevice
import f.cking.software.domain.model.DeviceData
import f.cking.software.domain.model.Transport
import f.cking.software.toBase64

class BuildDeviceFromScanDataInteractor(
    private val getManufacturerInfoFromRawBleInteractor: GetManufacturerInfoFromRawBleInteractor,
) {

    fun execute(scanData: BleScanDevice): DeviceData {
        val rawData = scanData.scanRecordRaw

        return DeviceData(
            address = scanData.address,
            name = scanData.name,
            lastDetectTimeMs = scanData.scanTimeMs,
            firstDetectTimeMs = scanData.scanTimeMs,
            detectCount = 1,
            customName = null,
            manufacturerInfo = rawData?.let {
                getManufacturerInfoFromRawBleInteractor.execute(it, scanData.scanTimeMs)
            },
            lastFollowingDetectionTimeMs = null,
            tags = emptySet(),
            rssi = scanData.rssi,
            systemAddressType = scanData.addressType,
            deviceClass = scanData.deviceClass,
            isPaired = scanData.isPaired,
            servicesUuids = scanData.serviceUuids,
            rowDataEncoded = rawData?.toBase64(),
            isConnectable = scanData.isConnectable,
            // BR/EDR inquiry sets `deviceType=DEVICE_TYPE_CLASSIC`; LE scan leaves it null.
            // Treat null as LE (the legacy scan path) so existing devices keep their pre-BTC
            // classification when this commit lands. Once BR/EDR discovery wires in, that path
            // will pass an explicit deviceType.
            transport = Transport.fromAndroidDeviceType(scanData.deviceType)
                .let { if (it == Transport.UNKNOWN && rawData != null) Transport.LE else it },
            sdpUuids = emptyList(),
        )
    }
}