package f.cking.software.domain.interactor

import android.bluetooth.BluetoothDevice
import f.cking.software.data.helpers.BluetoothSIG
import f.cking.software.data.helpers.OuiRepository
import f.cking.software.domain.model.DeviceData
import f.cking.software.domain.model.ExtendedAddressInfo
import f.cking.software.domain.model.ExtendedAddressInfo.BleAddressType
import f.cking.software.domain.model.ManufacturerInfo
import org.koin.core.context.GlobalContext
import kotlin.time.DurationUnit
import kotlin.time.toDuration

object BuildExtendedAddressInfoInteractor {

    /**
     * Lazy reference to the IEEE OUI lookup asset. Pulled via Koin's GlobalContext rather than
     * constructor injection because [DeviceData.extendedAddressInfo()] is called from many
     * places (UI rows, filter checker, BTIDES export) — threading the repo through every call
     * site would touch dozens of files. The lookup is cached on [DeviceData] anyway via the
     * `by lazy` wrapper, so the GlobalContext access happens at most once per device instance.
     */
    private val ouiRepository: OuiRepository by lazy {
        GlobalContext.get().get()
    }

    fun execute(device: DeviceData): ExtendedAddressInfo {
        val type = when (device.systemAddressType) {
            BluetoothDevice.ADDRESS_TYPE_PUBLIC -> BleAddressType.PUBLIC
            BluetoothDevice.ADDRESS_TYPE_ANONYMOUS -> BleAddressType.NON_RESOLVABLE_PRIVATE
            else -> getBleAddressType(device.address, device.knownLifetime(), device.manufacturerInfo)
        }
        return ExtendedAddressInfo(device.address, type)
    }

    private fun getBleAddressType(address: String, lifetime: Long, manufacturerInfo: ManufacturerInfo?): BleAddressType {
        val bytes = address.split(":").mapNotNull { it.toIntOrNull(16) }
        if (bytes.size != 6) return BleAddressType.INVALID

        val msb = bytes[0] // BLE addresses are big-endian, so MSB is the first byte

        // BT Core Spec Vol 6 Pt B §1.3.2 defines three random-address top-2-bit encodings:
        //   11 -> Static
        //   01 -> RPA
        //   00 -> NRPA
        // (10 is reserved.) Public addresses don't follow this scheme — they're real IEEE
        // OUIs and the top two bits can be anything. So when we see top-bits=10, the address
        // CANNOT be random by spec → must be public. For the other three patterns the bits
        // are ambiguous: a public OUI that happens to start with 0xC0..0xFF coincides with
        // Static, 0x00..0x3F with NRPA, 0x40..0x7F with RPA. The lifetime / manufacturer
        // heuristic disambiguates the 00 and 11 cases (RPAs and NRPAs cycle every ~15 min,
        // so anything we've watched for >12 h must be either Static or Public). Without the
        // platform's `BluetoothDevice.getAddressType()` (public API in Android 15 only) this
        // is the best we can do at scan time.
        // Cheap upfront check: if the upper 24 bits are an IEEE-assigned OUI, the address is
        // almost certainly Public. The random-address space (46 effective bits across NRPA /
        // RPA / Static) doesn't relate to IEEE assignments, so the chance of a random address
        // happening to match an assigned 24-bit OUI is ~39k / 16M ≈ 0.24%. Even on the
        // ambiguous bit patterns (0b00, 0b11) this is a much stronger signal than the
        // lifetime/byte heuristic alone.
        val ouiAssigned = ouiRepository.isAssigned(address)

        return when ((msb shr 6) and 0b11) {
            0b10 -> BleAddressType.PUBLIC
            0b01 -> {
                // RPAs use this prefix exclusively per spec — but if the IEEE OUI also matches
                // we trust the OUI signal (some hardware reuses MAC-style addresses for BLE
                // and the bit pattern coincides with RPA).
                if (ouiAssigned) BleAddressType.PUBLIC else BleAddressType.RESOLVABLE_PRIVATE
            }
            0b00 -> {
                // NRPA shares this prefix with public OUIs in 0x00..0x3F. OUI lookup is now
                // the primary signal; the lifetime / byte heuristic remains as fallback.
                if (ouiAssigned || isPublicAddress(msb, lifetime, manufacturerInfo)) BleAddressType.PUBLIC
                else BleAddressType.NON_RESOLVABLE_PRIVATE
            }
            0b11 -> {
                // Static-Random shares this prefix with public OUIs in 0xC0..0xFF. OUI lookup
                // catches the public case on first detection (otherwise would only be flagged
                // after 12 h of observation).
                if (ouiAssigned || lifetime > LONG_LIFETIME_MS) BleAddressType.PUBLIC
                else BleAddressType.STATIC_RANDOM
            }
            else -> BleAddressType.INVALID
        }
    }

    private fun isPublicAddress(msb: Int, lifetime: Long, manufacturerInfo: ManufacturerInfo?): Boolean {
        return lifetime > LONG_LIFETIME_MS
                || (!MANUFACTURERS_WITH_PRIVATE_ADDRESSES.contains(manufacturerInfo?.id) && (msb and 0b110000) == 0)
    }

    private val LONG_LIFETIME_MS =
        HOURS_TO_BE_CONSIDERED_STATIC.toDuration(DurationUnit.HOURS).inWholeMilliseconds

    private const val HOURS_TO_BE_CONSIDERED_STATIC = 12
    private val MANUFACTURERS_WITH_PRIVATE_ADDRESSES = BluetoothSIG.bluetoothSIG.entries.filter {
        it.value.contains("apple", ignoreCase = true)
                || it.value.contains("microsoft", ignoreCase = true)
    }
        .map { it.key }
}