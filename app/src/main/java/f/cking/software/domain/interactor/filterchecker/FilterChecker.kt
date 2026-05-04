package f.cking.software.domain.interactor.filterchecker

import android.util.LruCache
import f.cking.software.data.helpers.PowerModeHelper
import f.cking.software.domain.model.DeviceData
import f.cking.software.domain.model.DeviceFilter

abstract class FilterChecker<T : DeviceFilter>(
    private val powerModeHelper: PowerModeHelper,
) {

    // The cache is per-FilterChecker-instance (one per concrete filter type), so bumping the
    // size here gives every filter type its own headroom. At M=200k devices each filter can
    // hold every device's verdict — eviction stops thrashing the verdict for the next batch.
    // Memory cost ~80 KB per filter cache (negligible).
    private val cache: LruCache<String, CacheValue> = LruCache(MAX_CACHE_SIZE)

    suspend fun check(deviceData: DeviceData, filter: T): Boolean {
        if (!useCache()) {
            // Skip the read AND the put for filters that opt out of caching (e.g. interval
            // filters whose verdict changes with wall-clock time). The previous version still
            // ran cache.put on every call which churned the LRU for nothing.
            return checkInternal(deviceData, filter)
        }
        val key = "${deviceData.address}_${filter.hashCode()}_${filter::class.simpleName}"
        val cacheValue = cache[key]
        if (cacheValue != null
            && System.currentTimeMillis() - cacheValue.time < powerModeHelper.powerMode(useCached = true).filterCacheExpirationTime
        ) {
            return cacheValue.value
        }
        val result = checkInternal(deviceData, filter)
        cache.put(key, CacheValue(System.currentTimeMillis(), result))
        return result
    }

    protected abstract suspend fun checkInternal(deviceData: DeviceData, filter: T): Boolean

    protected open fun useCache(): Boolean = true

    open fun clearCache() {
        cache.evictAll()
    }

    private data class CacheValue(
        val time: Long,
        val value: Boolean,
    )

    companion object {
        // Sized for mall-scale (N>2000 visible, M>200k historical). Per-filter, so a UI showing
        // multiple chips doesn't make caches evict each other — they're independent.
        private const val MAX_CACHE_SIZE = 50_000
    }
}
