package f.cking.software.domain.interactor

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import f.cking.software.data.btides.BTIDESRepository
import f.cking.software.data.helpers.BleScannerHelper
import f.cking.software.data.repo.DevicesRepository
import f.cking.software.data.repo.SettingsRepository
import f.cking.software.domain.model.DeviceData
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Walks every currently-visible connectable device in RSSI order (strongest first) and runs a
 * GATT enumeration for BTIDES capture purposes. Devices identified as Apple or Samsung are
 * skipped according to the user-configured filter; the same filter is also enforced
 * mid-enumeration: if a discovered service UUID matches a vendor we are configured to skip,
 * the connection is dropped and the buffered GATT records are discarded so nothing for that
 * device ends up in the BTIDES log.
 */
class BulkEnumerateGattInteractor(
    private val devicesRepository: DevicesRepository,
    private val bleScannerHelper: BleScannerHelper,
    private val btidesRepository: BTIDESRepository,
    private val settingsRepository: SettingsRepository,
    private val vendorIdentifier: VendorIdentifier,
) {

    sealed interface Progress {
        data class Started(val total: Int, val skippedAdvFilter: Int) : Progress
        data class DeviceStarted(val index: Int, val total: Int, val device: DeviceData) : Progress
        data class DeviceFinished(
            val index: Int,
            val total: Int,
            val device: DeviceData,
            val outcome: Outcome,
            val errorMessage: String? = null,
        ) : Progress
        data class Done(val total: Int, val succeeded: Int, val skippedVendor: Int, val errors: Int, val advSkipped: Int) : Progress
    }

    enum class Outcome { SUCCESS, SKIPPED_VENDOR, ERROR, TIMEOUT }

    private data class EnumResult(val outcome: Outcome, val errorMessage: String? = null)

    /**
     * Drives one pass.
     *
     * Re-snapshots the last batch *between every attempt* and re-sorts by RSSI, so a device
     * that got closer (better RSSI) since the previous attempt jumps ahead of one that got
     * further. Matches the user-walking case: by the time you've processed device #3, the
     * RSSI ranking of devices #4..N is no longer the ranking from when the pass started.
     *
     * @param skipAddresses devices already successfully enumerated in this session — never
     * re-attempted (across passes; managed by the VM).
     * @param attemptCounts session-wide attempt counter (address.uppercase() → count). Mutated
     * in place: incremented on every connect attempt. Devices whose count reaches
     * [maxAttemptsPerDevice] get filtered out of subsequent picks for the rest of the session.
     * The map is owned by the VM so the cap survives across passes under "Retry forever".
     */
    fun execute(
        skipAddresses: Set<String> = emptySet(),
        attemptCounts: MutableMap<String, Int> = mutableMapOf(),
        maxAttemptsPerDevice: Int = MAX_ATTEMPTS_PER_DEVICE,
    ): Flow<Progress> = channelFlow {
        val skipApple = settingsRepository.getBulkSkipApple()
        val skipSamsung = settingsRepository.getBulkSkipSamsung()
        val normalizedSkip = skipAddresses.map { it.uppercase() }.toSet()

        // Initial snapshot: count vendor-pre-filtered devices for the Started progress event so
        // the user sees "(N pre-skipped)" up front. The same vendor filter is re-applied on each
        // re-snapshot below.
        val initialSnapshot = devicesRepository.observeLastBatch().first().toList()
        val initialConnectable = initialSnapshot.filter { it.isConnectable }
        val initialAdvSkippedCount = initialConnectable.count { d ->
            d.address.uppercase() !in normalizedSkip &&
                vendorIdentifier.shouldSkip(d, skipApple, skipSamsung)
        }
        val initialCandidateCount = initialConnectable.count { d ->
            d.address.uppercase() !in normalizedSkip &&
                !vendorIdentifier.shouldSkip(d, skipApple, skipSamsung)
        }
        send(Progress.Started(total = initialCandidateCount, skippedAdvFilter = initialAdvSkippedCount))

        var succeeded = 0
        var skippedVendor = 0
        var errors = 0
        var attemptIndex = 0
        // Addresses we've completed an attempt on this pass — even if not yet hitting the
        // session-wide cap, we don't loop on the same device twice in one pass.
        val attemptedThisPass = mutableSetOf<String>()

        while (true) {
            val snapshot = devicesRepository.observeLastBatch().first().toList()
            val candidates = snapshot
                .asSequence()
                .filter { it.isConnectable }
                .filter { it.address.uppercase() !in normalizedSkip }
                .filter { it.address.uppercase() !in attemptedThisPass }
                .filter { (attemptCounts[it.address.uppercase()] ?: 0) < maxAttemptsPerDevice }
                .filterNot { vendorIdentifier.shouldSkip(it, skipApple, skipSamsung) }
                .toList()

            if (candidates.isEmpty()) break

            // Pick the highest-RSSI device that hasn't been attempted yet this pass — this is
            // the "re-sort after every attempt" behaviour. Null RSSI sorts last.
            val nextDevice = candidates.maxByOrNull { it.rssi ?: Int.MIN_VALUE } ?: break
            val key = nextDevice.address.uppercase()

            // Display total: max(initial, attempts_so_far + remaining_at_this_iter). Lets the
            // UI keep counting up if extra devices appeared mid-pass.
            val displayTotal = maxOf(initialCandidateCount, attemptIndex + candidates.size)
            send(Progress.DeviceStarted(index = attemptIndex, total = displayTotal, device = nextDevice))

            attemptCounts[key] = (attemptCounts[key] ?: 0) + 1
            attemptedThisPass += key

            val result = enumerateOne(nextDevice, skipApple, skipSamsung)
            when (result.outcome) {
                Outcome.SUCCESS -> succeeded++
                Outcome.SKIPPED_VENDOR -> skippedVendor++
                Outcome.ERROR, Outcome.TIMEOUT -> errors++
            }
            send(Progress.DeviceFinished(attemptIndex, displayTotal, nextDevice, result.outcome, result.errorMessage))
            attemptIndex++
        }

        send(
            Progress.Done(
                total = attemptIndex,
                succeeded = succeeded,
                skippedVendor = skippedVendor,
                errors = errors,
                advSkipped = initialAdvSkippedCount,
            )
        )
    }

    private suspend fun enumerateOne(device: DeviceData, skipApple: Boolean, skipSamsung: Boolean): EnumResult {
        btidesRepository.beginGattSession(device.address)
        var vendorMatched = false
        var pendingChars: List<BluetoothGattCharacteristic> = emptyList()
        var allCharsRead = false
        var gattRef: BluetoothGatt? = null
        var connected = false

        return try {
            withTimeoutOrFallback(PER_DEVICE_TIMEOUT) {
                bleScannerHelper.connectToDevice(device.address)
                    .collectUntil { event ->
                        when (event) {
                            is BleScannerHelper.DeviceConnectResult.Connected -> {
                                connected = true
                                gattRef = event.gatt
                                bleScannerHelper.discoverServices(event.gatt)
                                false
                            }
                            is BleScannerHelper.DeviceConnectResult.AvailableServices -> {
                                val uuids = event.services.map { it.uuid.toString() }
                                if (vendorIdentifier.shouldSkipByServiceUuids(uuids, skipApple, skipSamsung)) {
                                    vendorMatched = true
                                    Timber.tag(TAG).i("Vendor service UUID seen on ${device.address}; aborting")
                                    btidesRepository.markGattSessionForDiscard(device.address)
                                    bleScannerHelper.disconnect(event.gatt)
                                    false
                                } else {
                                    pendingChars = pickReadableCharacteristics(event.services)
                                    if (pendingChars.isEmpty()) {
                                        bleScannerHelper.disconnect(event.gatt)
                                    } else {
                                        bleScannerHelper.readCharacteristic(event.gatt, pendingChars.first())
                                    }
                                    false
                                }
                            }
                            is BleScannerHelper.DeviceConnectResult.CharacteristicRead,
                            is BleScannerHelper.DeviceConnectResult.FailedReadCharacteristic,
                            -> {
                                pendingChars = pendingChars.drop(1)
                                if (pendingChars.isEmpty()) {
                                    allCharsRead = true
                                    gattRef?.let { bleScannerHelper.disconnect(it) }
                                } else {
                                    gattRef?.let { bleScannerHelper.readCharacteristic(it, pendingChars.first()) }
                                }
                                false
                            }
                            is BleScannerHelper.DeviceConnectResult.Disconnected -> true
                            is BleScannerHelper.DeviceConnectResult.DisconnectedWithError -> true
                            else -> false
                        }
                    }
            }

            if (vendorMatched) {
                btidesRepository.closeGattSession(device.address, commit = false)
                EnumResult(Outcome.SKIPPED_VENDOR)
            } else if (!connected) {
                btidesRepository.closeGattSession(device.address, commit = false)
                EnumResult(Outcome.ERROR, "Could not establish GATT connection")
            } else {
                val written = btidesRepository.closeGattSession(device.address, commit = true)
                Timber.tag(TAG).i("Committed $written GATT records for ${device.address} (allCharsRead=$allCharsRead)")
                EnumResult(Outcome.SUCCESS)
            }
        } catch (e: TimeoutFallback) {
            // Partial enumeration is still useful — keep whatever made it into the buffer
            // unless we already detected a vendor match.
            gattRef?.let { runCatching { bleScannerHelper.disconnect(it) } }
            val written = btidesRepository.closeGattSession(device.address, commit = !vendorMatched)
            Timber.tag(TAG).i("Per-device timeout for ${device.address}; committed=$written, vendorMatched=$vendorMatched")
            if (vendorMatched) EnumResult(Outcome.SKIPPED_VENDOR)
            else EnumResult(Outcome.TIMEOUT, "Timed out after ${PER_DEVICE_TIMEOUT.inWholeSeconds}s")
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Bulk enum failed for ${device.address}")
            gattRef?.let { runCatching { bleScannerHelper.disconnect(it) } }
            val written = btidesRepository.closeGattSession(device.address, commit = !vendorMatched)
            Timber.tag(TAG).i("Failure for ${device.address}; committed=$written, vendorMatched=$vendorMatched")
            if (vendorMatched) EnumResult(Outcome.SKIPPED_VENDOR)
            else EnumResult(Outcome.ERROR, e.message ?: e::class.java.simpleName)
        }
    }

    private fun pickReadableCharacteristics(services: List<BluetoothGattService>): List<BluetoothGattCharacteristic> {
        val result = mutableListOf<BluetoothGattCharacteristic>()
        for (s in services) {
            for (c in s.characteristics.orEmpty()) {
                if ((c.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                    result += c
                    if (result.size >= MAX_CHARS_PER_DEVICE) return result
                }
            }
        }
        return result
    }

    private suspend fun <T> Flow<T>.collectUntil(predicate: suspend (T) -> Boolean) {
        var stop = false
        var collected = 0
        try {
            this.first { value ->
                collected++
                stop = predicate(value)
                stop
            }
        } catch (_: NoSuchElementException) {
            // upstream completed without satisfying predicate; that's fine
        }
        if (!stop && collected == 0) {
            // upstream produced nothing
        }
    }

    private suspend fun <T> withTimeoutOrFallback(timeout: Duration, block: suspend () -> T): T = coroutineScope {
        channelFlow<Result<T>> {
            var timer: Job? = null
            val main = launch {
                runCatching { block.invoke() }.also { send(it); timer?.cancel() }
            }
            timer = launch {
                delay(timeout)
                main.cancel()
                send(Result.failure(TimeoutFallback))
            }
        }.first().getOrThrow()
    }

    private object TimeoutFallback : RuntimeException("BulkEnumerateGatt per-device timeout")

    companion object {
        private const val TAG = "BulkEnumerateGatt"
        private const val MAX_CHARS_PER_DEVICE = 12
        // Per-Connect-All-session retry cap — once a device has failed this many times the
        // user has to Stop and re-press "Connect to all" to give it another chance.
        const val MAX_ATTEMPTS_PER_DEVICE = 5
        private val PER_DEVICE_TIMEOUT = 20.seconds
    }
}
