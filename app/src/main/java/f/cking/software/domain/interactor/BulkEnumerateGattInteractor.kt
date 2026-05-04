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
     * @param skipAddresses devices already successfully enumerated in this session that should
     * be skipped silently — used by the Connect All "Retry forever" loop so we don't re-attempt
     * connections to peers that already gave us their full GATT.
     */
    fun execute(skipAddresses: Set<String> = emptySet()): Flow<Progress> = channelFlow {
        val skipApple = settingsRepository.getBulkSkipApple()
        val skipSamsung = settingsRepository.getBulkSkipSamsung()

        val snapshot = devicesRepository.observeLastBatch().first().toList()
        val connectable = snapshot.filter { it.isConnectable }
        val normalizedSkip = skipAddresses.map { it.uppercase() }.toSet()

        // Pre-filter on advertisement-time signals (MSD/OUI/UUIDs) and on the already-enumerated
        // skip set.
        val advSkipped = mutableListOf<DeviceData>()
        val candidates = mutableListOf<DeviceData>()
        for (d in connectable) {
            if (d.address.uppercase() in normalizedSkip) continue
            if (vendorIdentifier.shouldSkip(d, skipApple, skipSamsung)) advSkipped += d else candidates += d
        }

        val ordered = candidates.sortedByDescending { it.rssi ?: Int.MIN_VALUE }
        send(Progress.Started(total = ordered.size, skippedAdvFilter = advSkipped.size))

        var succeeded = 0
        var skippedVendor = 0
        var errors = 0

        ordered.forEachIndexed { index, device ->
            send(Progress.DeviceStarted(index = index, total = ordered.size, device = device))
            val result = enumerateOne(device, skipApple, skipSamsung)
            when (result.outcome) {
                Outcome.SUCCESS -> succeeded++
                Outcome.SKIPPED_VENDOR -> skippedVendor++
                Outcome.ERROR, Outcome.TIMEOUT -> errors++
            }
            send(Progress.DeviceFinished(index, ordered.size, device, result.outcome, result.errorMessage))
        }

        send(
            Progress.Done(
                total = ordered.size,
                succeeded = succeeded,
                skippedVendor = skippedVendor,
                errors = errors,
                advSkipped = advSkipped.size,
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
        private val PER_DEVICE_TIMEOUT = 20.seconds
    }
}
