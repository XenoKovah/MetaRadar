package com.darkmentor.domain.interactor

import com.darkmentor.domain.model.DeviceData
import com.darkmentor.fromBase64
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
     * Bounded LRU keyed by device address → (rowDataEncoded, fingerprint). Hit when the
     * same address is fingerprinted with the same AD bytes — typical hot path during
     * Connect All's continuous-mode pool refresh, where the same ~100 candidates are
     * considered every refresh and their AD bytes are stable across re-detections. Miss
     * when either the address is new or the AD bytes changed (peer started broadcasting a
     * different payload). Cap at [MAX_CACHE_ENTRIES] so a long session with thousands of
     * distinct addresses doesn't grow the cache without bound; LinkedHashMap's
     * accessOrder=true gives free LRU eviction on overflow.
     *
     * Synchronized: production calls this from multiple coroutines (worker pool refresher
     * + per-worker registration). The Map operations are not internally thread-safe.
     */
    private data class Entry(val rowDataEncoded: String, val fingerprint: String)
    private val cache: java.util.LinkedHashMap<String, Entry> =
        object : java.util.LinkedHashMap<String, Entry>(MAX_CACHE_ENTRIES, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    private val cacheLock = Any()

    /**
     * Compute the fingerprint for [device]. Returns null when [DeviceData.rowDataEncoded] is
     * null/empty (no AD bytes available), so callers can fall back to address-based dedup.
     * Cached per-address; same AD bytes for the same address skips the SHA-1 entirely.
     */
    fun fingerprint(device: DeviceData): String? {
        val encoded = device.rowDataEncoded?.takeIf { it.isNotBlank() } ?: return null
        val key = device.address
        synchronized(cacheLock) {
            cache[key]?.let { if (it.rowDataEncoded == encoded) return it.fingerprint }
        }
        val fp = runCatching {
            val raw = encoded.fromBase64()
            if (raw.isEmpty()) return null
            val md = MessageDigest.getInstance("SHA-1")
            md.update(raw)
            md.digest().toHexString()
        }.getOrNull() ?: return null
        synchronized(cacheLock) {
            cache[key] = Entry(encoded, fp)
        }
        return fp
    }

    /** Test hook: drop the in-memory cache. Production never calls this. */
    internal fun clearCacheForTest() {
        synchronized(cacheLock) { cache.clear() }
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { "%02x".format(it) }

    /**
     * Cache cap. ~5000 entries × ~120 bytes/entry (key + base64 + hex digest) ≈ 600 KB
     * worst case. Far below the heap budget; chosen high enough that the working set of
     * a long Connect All session typically fits without thrashing.
     */
    private const val MAX_CACHE_ENTRIES = 5000
}
