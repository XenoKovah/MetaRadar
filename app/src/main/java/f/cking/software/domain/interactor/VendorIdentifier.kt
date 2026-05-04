package f.cking.software.domain.interactor

import f.cking.software.data.helpers.CluesRepository
import f.cking.software.domain.model.BleRecordFrame
import f.cking.software.domain.model.DeviceData
import f.cking.software.fromBase64
import java.util.UUID

/**
 * Decides whether a device belongs to a particular vendor based on multiple sources:
 *  1. The Manufacturer-Specific Data company id in any AD type 0xFF segment
 *     (per the BT SIG company identifiers list).
 *  2. The IEEE-allocated OUI (top 24 bits of a public BDADDR). Random BLE
 *     addresses don't embed a real OUI, so OUI matching only fires when
 *     `systemAddressType == ADDRESS_TYPE_PUBLIC`.
 *  3. The 16-bit member UUIDs that Bluetooth SIG has allocated to that vendor
 *     (assigned-numbers/uuids/member_uuids.yaml), seen either as advertised
 *     service UUIDs or as discovered GATT service UUIDs.
 *  4. CLUES-attributed UUIDs (typically 128-bit) from the CLUES_data.json
 *     community database — same usage as #3.
 *
 * The data sets behind #1-#4 are baked in to [VendorRegistry] (auto-generated
 * from upstream sources) and the CLUES asset.
 */
class VendorIdentifier(
    private val cluesRepository: CluesRepository,
) {

    enum class Vendor { APPLE, SAMSUNG }

    /** Returns the matching vendor, or null. Combines all data sources. */
    fun identifyVendor(device: DeviceData): Vendor? {
        val advUuids = collectAdvertisementUuids(device)
        // iBeacon (Apple subtype 0x02 / length 0x15) carries Apple's company id (0x004C) but is a
        // multi-vendor format — Tesla, Estimote, etc. broadcast iBeacons too. Treat the device's
        // resolved manufacturerInfo.id as Apple only when at least one MSD frame is genuinely
        // Apple-proprietary (not iBeacon-shaped).
        val msdAppleNonIBeacon = collectAppleNonIBeaconMsdCount(device) > 0
        val effectiveCompanyId = when {
            device.manufacturerInfo?.id == APPLE_COMPANY_ID && !msdAppleNonIBeacon -> null
            else -> device.manufacturerInfo?.id
        }
        return identify(
            companyId = effectiveCompanyId,
            oui = if (device.systemAddressType == ADDRESS_TYPE_PUBLIC) parseOui(device.address) else null,
            uuids = advUuids,
            msdCompanyIds = collectMsdCompanyIds(device),
        )
    }

    /** Same idea, but starting from a list of GATT service UUIDs (e.g. mid-enumeration). */
    fun identifyByServiceUuids(uuids: Collection<String>): Vendor? {
        return identify(
            companyId = null,
            oui = null,
            uuids = uuids,
            msdCompanyIds = emptyList(),
        )
    }

    fun isApple(device: DeviceData): Boolean = identifyVendor(device) == Vendor.APPLE
    fun isSamsung(device: DeviceData): Boolean = identifyVendor(device) == Vendor.SAMSUNG

    fun shouldSkip(device: DeviceData, skipApple: Boolean, skipSamsung: Boolean): Boolean {
        val vendor = identifyVendor(device) ?: return false
        return (skipApple && vendor == Vendor.APPLE) || (skipSamsung && vendor == Vendor.SAMSUNG)
    }

    fun shouldSkipByServiceUuids(
        uuids: Collection<String>,
        skipApple: Boolean,
        skipSamsung: Boolean,
    ): Boolean {
        val vendor = identifyByServiceUuids(uuids) ?: return false
        return (skipApple && vendor == Vendor.APPLE) || (skipSamsung && vendor == Vendor.SAMSUNG)
    }

    private fun identify(
        companyId: Int?,
        oui: Int?,
        uuids: Collection<String>,
        msdCompanyIds: List<Int>,
    ): Vendor? {
        // 1. Top-level manufacturer info (resolved by upstream interactor)
        when (companyId) {
            APPLE_COMPANY_ID -> return Vendor.APPLE
            SAMSUNG_COMPANY_ID -> return Vendor.SAMSUNG
        }
        // 2. Any MSD frame with a vendor company id
        for (id in msdCompanyIds) {
            if (id == APPLE_COMPANY_ID) return Vendor.APPLE
            if (id == SAMSUNG_COMPANY_ID) return Vendor.SAMSUNG
        }
        // 3. Public-address OUI
        if (oui != null) {
            if (oui in VendorRegistry.APPLE_OUIS) return Vendor.APPLE
            if (oui in VendorRegistry.SAMSUNG_OUIS) return Vendor.SAMSUNG
        }
        // 4. UUIDs (advertised or discovered)
        for (raw in uuids) {
            val short = shortUuid(raw)
            if (short != null) {
                if (short in VendorRegistry.APPLE_SIG_UUID16S) return Vendor.APPLE
                if (short in VendorRegistry.SAMSUNG_SIG_UUID16S) return Vendor.SAMSUNG
            }
            val long = raw.lowercase()
            if (long in VendorRegistry.APPLE_CLUES_UUIDS) return Vendor.APPLE
            if (long in VendorRegistry.SAMSUNG_CLUES_UUIDS) return Vendor.SAMSUNG
            // Fallback: ask CLUES by company name
            val entry = cluesRepository.lookup(raw)
            val companyName = (entry?.company ?: "").lowercase()
            if (companyName.startsWith("apple")) return Vendor.APPLE
            if ("samsung" in companyName) return Vendor.SAMSUNG
        }
        return null
    }

    private fun collectAdvertisementUuids(device: DeviceData): List<String> {
        val out = mutableListOf<String>()
        out += device.servicesUuids

        // Pull UUID16/32/128 lists out of raw advertisement bytes too.
        val raw = device.rowDataEncoded?.takeIf { it.isNotBlank() }?.let {
            runCatching { it.fromBase64() }.getOrNull()
        } ?: return out
        for (frame in parseAdvFrames(raw)) {
            val type = frame.type.toInt() and 0xFF
            when (type) {
                0x02, 0x03, 0x14 -> readUuids(frame.data, 2).forEach { out += le16ToHex(it) }
                0x04, 0x05, 0x1F -> readUuids(frame.data, 4).forEach { out += le32ToHex(it) }
                0x06, 0x07, 0x15 -> readUuid128s(frame.data).forEach { out += it }
                0x16 -> if (frame.data.size >= 2) out += le16ToHex(le16(frame.data, 0))
                0x20 -> if (frame.data.size >= 4) out += le32ToHex(le32(frame.data, 0))
                0x21 -> if (frame.data.size >= 16) out += bytesToUuid128(frame.data, 0)
            }
        }
        return out
    }

    private fun collectMsdCompanyIds(device: DeviceData): List<Int> {
        val raw = device.rowDataEncoded?.takeIf { it.isNotBlank() }?.let {
            runCatching { it.fromBase64() }.getOrNull()
        } ?: return emptyList()
        // Drop iBeacon frames so a Tesla / Estimote beacon broadcasting an iBeacon under Apple's
        // company id doesn't get counted as Apple here either.
        return parseAdvFrames(raw)
            .filter { it.type == TYPE_MSD && it.data.size >= 2 }
            .filterNot { isIBeaconMsd(it.data) }
            .map { (it.data[0].toInt() and 0xFF) or ((it.data[1].toInt() and 0xFF) shl 8) }
    }

    /**
     * Counts MSD frames carrying Apple's company id that are NOT iBeacon-shaped — i.e. genuine
     * Apple-proprietary advertisements (Continuity, AirDrop, Handoff, Find My, etc.). A device
     * with zero such frames either isn't Apple at all (Tesla iBeacon) or is an iPhone configured
     * solely as an iBeacon transmitter — in both cases the user-facing "skip Apple" / "Not Apple"
     * filters should treat it as non-Apple per the Apple Beacon spec being multi-vendor.
     */
    fun collectAppleNonIBeaconMsdCount(device: DeviceData): Int {
        val raw = device.rowDataEncoded?.takeIf { it.isNotBlank() }?.let {
            runCatching { it.fromBase64() }.getOrNull()
        } ?: return 0
        return parseAdvFrames(raw)
            .filter { it.type == TYPE_MSD && it.data.size >= 2 }
            .count { frame ->
                val companyId = (frame.data[0].toInt() and 0xFF) or ((frame.data[1].toInt() and 0xFF) shl 8)
                companyId == APPLE_COMPANY_ID && !isIBeaconMsd(frame.data)
            }
    }

    /**
     * iBeacon spec: Apple company id (0x004C, little-endian → 4C 00) followed by subtype 0x02
     * and length 0x15 (21 bytes of UUID + major + minor + tx-power). Min total inner length is
     * therefore 4 bytes; full payload is 25.
     * https://developer.apple.com/ibeacon/
     */
    private fun isIBeaconMsd(msdData: ByteArray): Boolean {
        return msdData.size >= 4 &&
            (msdData[0].toInt() and 0xFF) == 0x4C &&
            (msdData[1].toInt() and 0xFF) == 0x00 &&
            (msdData[2].toInt() and 0xFF) == 0x02 &&
            (msdData[3].toInt() and 0xFF) == 0x15
    }

    /**
     * Parses BLE Advertising / EIR records: each frame is `[length] [type] [data...]` where
     * `length` covers `type + data` (so data is `length - 1` bytes). The frame at offset `i`
     * therefore spans `[i, i + length]` inclusive — a total of `length + 1` bytes — and we
     * need `i + length + 1 <= raw.size` for the frame to be valid.
     *
     * Stops parsing on any malformed frame instead of throwing — this runs on the Main thread
     * under a StateFlow collector, so a throw here would crash the UI. (Past regression: a 62-
     * byte advertisement whose final frame ended exactly at the buffer boundary tripped an
     * off-by-one in the data-slice indices and brought down the app on every Connect-All scan.)
     */
    @androidx.annotation.VisibleForTesting
    internal fun parseAdvFrames(raw: ByteArray): List<BleRecordFrame> {
        val out = ArrayList<BleRecordFrame>()
        var i = 0
        while (i < raw.size) {
            val length = raw[i].toInt() and 0xFF
            // Need length+1 bytes from offset i (the length byte itself + length bytes of type+data).
            if (length == 0 || i + 1 + length > raw.size) break
            val type = raw[i + 1]
            // Data spans indices [i+2, i+length] inclusive → copyOfRange toIndex = i + length + 1.
            val data = raw.copyOfRange(i + 2, i + length + 1)
            out.add(BleRecordFrame(type, data))
            i += 1 + length
        }
        return out
    }

    private fun readUuids(data: ByteArray, size: Int): List<Long> {
        val out = mutableListOf<Long>()
        var p = 0
        while (p + size <= data.size) {
            var v = 0L
            for (i in 0 until size) v = v or ((data[p + i].toLong() and 0xFFL) shl (i * 8))
            out += v
            p += size
        }
        return out
    }

    private fun readUuid128s(data: ByteArray): List<String> {
        val out = mutableListOf<String>()
        var p = 0
        while (p + 16 <= data.size) {
            out += bytesToUuid128(data, p)
            p += 16
        }
        return out
    }

    private fun bytesToUuid128(data: ByteArray, offset: Int): String {
        // BLE wire is little-endian; reverse to big-endian then format as canonical UUID.
        val be = ByteArray(16) { data[offset + 15 - it] }
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (be[i].toLong() and 0xFF)
        for (i in 8 until 16) lsb = (lsb shl 8) or (be[i].toLong() and 0xFF)
        return UUID(msb, lsb).toString().lowercase()
    }

    private fun le16(data: ByteArray, off: Int) =
        (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or
            ((data[off + 1].toInt() and 0xFF) shl 8) or
            ((data[off + 2].toInt() and 0xFF) shl 16) or
            ((data[off + 3].toInt() and 0xFF) shl 24)

    private fun le16ToHex(v: Long): String = "%04x".format(v.toInt() and 0xFFFF)
    private fun le32ToHex(v: Long): String = "%08x".format(v.toInt())
    private fun le16ToHex(v: Int): String = "%04x".format(v and 0xFFFF)
    private fun le32ToHex(v: Int): String = "%08x".format(v)

    /**
     * Returns the 16-bit form of a SIG short UUID (e.g. for "0000fe8a-..." → 0xFE8A,
     * or for "fe8a" → 0xFE8A), else null.
     */
    private fun shortUuid(uuid: String): Int? {
        val s = uuid.lowercase()
        if (s.length == 4) return s.toIntOrNull(16)
        // Standard 36-char form; only if matching the SIG base
        if (s.length == 36 && s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
            return s.substring(4, 8).toIntOrNull(16)
        }
        return null
    }

    /** Parses "AA:BB:CC:DD:EE:FF" into 0xAABBCC, returning -1 on failure. */
    private fun parseOui(address: String): Int {
        val parts = address.split(':')
        if (parts.size < 3) return -1
        val a = parts[0].toIntOrNull(16) ?: return -1
        val b = parts[1].toIntOrNull(16) ?: return -1
        val c = parts[2].toIntOrNull(16) ?: return -1
        return (a shl 16) or (b shl 8) or c
    }

    companion object {
        private const val APPLE_COMPANY_ID = 0x004C
        private const val SAMSUNG_COMPANY_ID = 0x0075
        private const val ADDRESS_TYPE_PUBLIC = 0
        private const val TYPE_MSD: Byte = 0xFF.toByte()
    }
}
