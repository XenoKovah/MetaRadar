package f.cking.software.domain.interactor

import f.cking.software.domain.model.DeviceData
import f.cking.software.fromBase64
import java.security.MessageDigest

/**
 * Stable hash of a peer's broadcast advertisement payload. Used by the Connect All candidate
 * selector to dedup "same physical device, different BDADDR" — i.e. when an RPA-rotating
 * peripheral re-appears under a fresh address but with byte-identical AD bytes.
 *
 * Strategy: SHA-1 of the raw AD bytes (rowDataEncoded base64-decoded). Strict byte-for-byte
 * match means:
 *
 *  - Two RPA rotations of a non-Apple peripheral (TV, headphones, fitness band, IoT sensor)
 *    that don't include any rolling counters in their AD will hash identically — the
 *    fingerprint deduplicates them. This is the win the user asked for.
 *  - Apple devices include a 2-byte rolling counter in their MSD that increments per
 *    advertising interval, so two Apple advertisements (even from the same physical device)
 *    will hash differently — the fingerprint never matches across rotations. This is
 *    deliberate: the user already has Skip Apple as a separate vendor filter, and
 *    pretending two Apple ADs match would be false even between two scans of the same
 *    static address.
 *  - BR/EDR-only peers, manual entries, and detections from before the rowDataEncoded
 *    column existed have no AD bytes — fingerprint() returns null and they fall through
 *    to address-based dedup.
 *
 * The dedup is gated by allCharsRead at capture time (see [BulkEnumerateGattInteractor]):
 * we only register a fingerprint as "fully captured" when the prior attempt actually read
 * every readable characteristic. Partial captures stay eligible for retry under a fresh
 * address — matches the user's "if and only if it successfully read all readable
 * Characteristics" requirement.
 */
object AdvertisementFingerprint {

    /**
     * Compute the fingerprint for [device]. Returns null when [DeviceData.rowDataEncoded] is
     * null/empty (no AD bytes available), so callers can fall back to address-based dedup.
     */
    fun fingerprint(device: DeviceData): String? {
        val encoded = device.rowDataEncoded?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val raw = encoded.fromBase64()
            if (raw.isEmpty()) return null
            val md = MessageDigest.getInstance("SHA-1")
            md.update(raw)
            md.digest().toHexString()
        }.getOrNull()
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { "%02x".format(it) }
}
