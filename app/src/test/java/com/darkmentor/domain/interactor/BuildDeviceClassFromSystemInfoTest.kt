package com.darkmentor.domain.interactor

import android.bluetooth.BluetoothClass
import com.darkmentor.domain.model.DeviceClass
import com.darkmentor.domain.model.DeviceData
import org.junit.Assert.assertEquals
import org.junit.Test

class BuildDeviceClassFromSystemInfoTest {

    @Test
    fun `Samsung tracker service maps to SmartTag`() {
        assertEquals(
            DeviceClass.Beacon.SmartTag,
            BuildDeviceClassFromSystemInfo.execute(
                device(serviceUuids = listOf("0000fd50-0000-1000-8000-00805f9b34fb")),
            ),
        )
    }

    @Test
    fun `Samsung tracker name maps to SmartTag`() {
        assertEquals(
            DeviceClass.Beacon.SmartTag,
            BuildDeviceClassFromSystemInfo.execute(device(name = "My Smart Tag")),
        )
    }

    @Test
    fun `health data display class maps to its specific type`() {
        assertEquals(
            DeviceClass.Health.HealthDataDisplay,
            BuildDeviceClassFromSystemInfo.execute(
                device(deviceClass = BluetoothClass.Device.HEALTH_DATA_DISPLAY),
            ),
        )
    }

    @Test
    fun `service UUID matching is case insensitive`() {
        assertEquals(
            DeviceClass.Health.Uncategorised,
            BuildDeviceClassFromSystemInfo.execute(
                device(serviceUuids = listOf("0000183E-0000-1000-8000-00805f9b34fb")),
            ),
        )
    }

    private fun device(
        name: String? = null,
        serviceUuids: List<String> = emptyList(),
        deviceClass: Int? = null,
    ) = DeviceData(
        address = "12:34:56:78:9A:BC",
        name = name,
        lastDetectTimeMs = 2,
        firstDetectTimeMs = 1,
        manufacturerInfo = null,
        detectCount = 1,
        customName = null,
        rssi = null,
        systemAddressType = null,
        deviceClass = deviceClass,
        isPaired = false,
        servicesUuids = serviceUuids,
        rowDataEncoded = null,
        isConnectable = true,
    )
}
