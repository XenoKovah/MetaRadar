package f.cking.software.domain.interactor

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothDevice
import f.cking.software.data.btides.BTIDESRepository
import f.cking.software.data.helpers.BleScannerHelper
import f.cking.software.data.helpers.SdpEnumerationHelper
import f.cking.software.data.repo.DevicesRepository
import f.cking.software.data.repo.SettingsRepository
import f.cking.software.domain.model.DeviceData
import f.cking.software.domain.model.Transport
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
    private val sdpEnumerationHelper: SdpEnumerationHelper,
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

    /**
     * - [SUCCESS]        : full GATT enumeration captured (LE / dual-mode peer).
     * - [SDP_SUCCESS]    : BR/EDR-only peer; SDP UUID list was captured (with or without a
     *                      follow-up GATT-over-BR/EDR attempt). Distinct from SUCCESS so the UI
     *                      summary line can split BLE-style "connected" from BR/EDR-style
     *                      "service-classes only".
     * - [SDP_TIMEOUT]    : BR/EDR SDP fetch produced no UUIDs within the helper's timeout —
     *                      typically an unbonded peer that's not in pairing mode.
     * - [SKIPPED_VENDOR] : Apple/Samsung filter matched the device.
     * - [ERROR]/[TIMEOUT]: the existing LE-side outcomes.
     */
    enum class Outcome { SUCCESS, SDP_SUCCESS, SDP_TIMEOUT, SKIPPED_VENDOR, ERROR, TIMEOUT }

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
                Outcome.SUCCESS, Outcome.SDP_SUCCESS -> succeeded++
                Outcome.SKIPPED_VENDOR -> skippedVendor++
                Outcome.SDP_TIMEOUT, Outcome.ERROR, Outcome.TIMEOUT -> errors++
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
        // BR/EDR-only peer: the LE GATT path won't connect, so run SDP enumeration instead.
        // DUAL devices keep the LE path — Android typically exposes GATT only over LE on those,
        // so the LE branch is the right fit.
        if (device.transport == Transport.BREDR) {
            return enumerateBrEdrOne(device, skipApple, skipSamsung)
        }
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

    /**
     * BR/EDR-only branch of the bulk pipeline. Runs SDP enumeration via [SdpEnumerationHelper]
     * (Semaphore(4) bounded internally), persists discovered UUIDs into the DB and BTIDES log,
     * and — when the device's SDP UUID list contains Generic Access (0x1800) or Generic
     * Attribute (0x1801) — attempts a GATT-over-BR/EDR connection with `TRANSPORT_BREDR`.
     * Most BR/EDR-only peers reject that connection; the failure is logged at INFO and does
     * NOT roll back the SDP capture.
     */
    private suspend fun enumerateBrEdrOne(device: DeviceData, skipApple: Boolean, skipSamsung: Boolean): EnumResult {
        return try {
            val timestampMs = System.currentTimeMillis()
            val uuids = withTimeoutOrFallback(PER_DEVICE_TIMEOUT) {
                sdpEnumerationHelper.enumerate(device.address)
            }
            val canonical = uuids.map { it.toString().lowercase() }
            // Run the same vendor filter that LE devices get — Apple/Samsung peers showing up
            // via SDP shouldn't sneak past the user's bulk-skip toggle.
            if (vendorIdentifier.shouldSkipByServiceUuids(canonical, skipApple, skipSamsung)) {
                return EnumResult(Outcome.SKIPPED_VENDOR)
            }
            if (canonical.isEmpty()) {
                return EnumResult(Outcome.SDP_TIMEOUT, "No SDP UUIDs returned within ${PER_DEVICE_TIMEOUT.inWholeSeconds}s")
            }
            devicesRepository.updateSdpUuids(device.address, canonical)
            btidesRepository.appendSDPDiscovery(device.address, uuids, timestampMs)
            // Always attempt GATT-over-BR/EDR for any responsive Classic peer. Apple devices
            // (iPhone, iPad) expose vendor-specific GATT data over BR/EDR but don't necessarily
            // advertise Generic Access (0x1800) / Generic Attribute (0x1801) in their SDP
            // ServiceClassIDList — the previous "only attempt when SDP says GATT" gate was too
            // strict and skipped exactly the devices the user cares about. Peers that don't
            // support GATT-over-BR/EDR (e.g. Beats headphones) fail-fast within the 8s
            // BR_EDR_GATT_TIMEOUT, which is a small price for catching the Apple case.
            attemptGattOverBrEdr(device)
            EnumResult(Outcome.SDP_SUCCESS)
        } catch (e: TimeoutFallback) {
            EnumResult(Outcome.SDP_TIMEOUT, "SDP fetch hit ${PER_DEVICE_TIMEOUT.inWholeSeconds}s timeout")
        } catch (e: BleScannerHelper.BluetoothIsNotInitialized) {
            EnumResult(Outcome.ERROR, "Bluetooth disabled")
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "BR/EDR SDP enum failed for ${device.address}")
            EnumResult(Outcome.ERROR, e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * Best-effort GATT-over-BR/EDR connection. Most BR/EDR-only devices don't expose ATT over
     * the BR/EDR transport even when SDP claims Generic Attribute — Android's connectGatt
     * returns GATT_FAILURE / CONNECTION_FAILED_TO_ESTABLISH and we move on. SDP results are
     * already committed; this is purely additive.
     */
    private suspend fun attemptGattOverBrEdr(device: DeviceData) {
        Timber.tag(TAG).i("Attempting GATT-over-BR/EDR on ${device.address} (SDP indicated ATT support)")
        btidesRepository.beginGattSession(device.address)
        var connected = false
        var gattRef: BluetoothGatt? = null
        try {
            withTimeoutOrFallback(BR_EDR_GATT_TIMEOUT) {
                bleScannerHelper.connectToDevice(device.address, transport = BluetoothDevice.TRANSPORT_BREDR)
                    .collectUntil { event ->
                        when (event) {
                            is BleScannerHelper.DeviceConnectResult.Connected -> {
                                connected = true
                                gattRef = event.gatt
                                bleScannerHelper.discoverServices(event.gatt)
                                false
                            }
                            is BleScannerHelper.DeviceConnectResult.AvailableServices -> {
                                bleScannerHelper.disconnect(event.gatt)
                                false
                            }
                            is BleScannerHelper.DeviceConnectResult.Disconnected -> true
                            is BleScannerHelper.DeviceConnectResult.DisconnectedWithError -> true
                            else -> false
                        }
                    }
            }
        } catch (e: TimeoutFallback) {
            gattRef?.let { runCatching { bleScannerHelper.disconnect(it) } }
        } catch (e: Throwable) {
            Timber.tag(TAG).i(e, "GATT-over-BR/EDR not supported by ${device.address}")
        }
        // Commit only if we actually got services back; otherwise discard so a failed
        // connection attempt doesn't leave a phantom GATTArray entry in BTIDES.
        btidesRepository.closeGattSession(device.address, commit = connected)
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
        // Most BR/EDR-only peers reject the ATT-over-BR/EDR connection; fail fast so the bulk
        // pass keeps moving.
        private val BR_EDR_GATT_TIMEOUT = 8.seconds
    }
}
