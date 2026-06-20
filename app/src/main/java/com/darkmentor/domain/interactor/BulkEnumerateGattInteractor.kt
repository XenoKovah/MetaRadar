package com.darkmentor.domain.interactor

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothDevice
import com.darkmentor.data.btides.BTIDESRepository
import com.darkmentor.data.helpers.BleScannerHelper
import com.darkmentor.data.helpers.SdpEnumerationHelper
import com.darkmentor.data.repo.DevicesRepository
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.domain.model.DeviceData
import com.darkmentor.domain.model.Transport
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    private val capturedAdvertFingerprintRepository: com.darkmentor.data.repo.CapturedAdvertFingerprintRepository,
) {

    sealed interface Progress {
        data class Started(val total: Int, val skippedAdvFilter: Int) : Progress
        /**
         * A specific worker slot ([slotId]) just acquired [device]. With concurrent workers
         * (4 LE + 1 BR/EDR) the consumer needs the slot id to maintain a per-slot in-flight
         * map for the multi-line UI; without it, two near-simultaneous DeviceStarted events
         * would collapse into a single visible row.
         */
        data class DeviceStarted(val slotId: Int, val index: Int, val total: Int, val device: DeviceData) : Progress
        data class DeviceFinished(
            val slotId: Int,
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

    /**
     * [allCharsRead] gates the AD-fingerprint dedup write — we only mark a fingerprint as
     * "fully captured" when the prior attempt actually finished every readable
     * characteristic. A partial capture leaves the device eligible for retry under a fresh
     * BDADDR (matches the user's "if and only if it successfully read all readable
     * Characteristics" requirement).
     */
    private data class EnumResult(
        val outcome: Outcome,
        val errorMessage: String? = null,
        val allCharsRead: Boolean = false,
    )

    /**
     * Drives a Connect All run.
     *
     * Two modes, controlled by [continuous]:
     *
     * - [continuous] = false (default): one pass. The candidate pool is built once from the
     *   current scan batch, workers drain it, and the flow emits [Progress.Done] when the pool
     *   is empty and all workers have exited. Used by the "retry forever OFF" branch and by
     *   tests that want a single deterministic round.
     *
     * - [continuous] = true: workers never exit. A background refresher continuously merges
     *   freshly-scanned connectable devices into the pool, applying the same skip/cap/vendor
     *   filters used at pass start. Workers loop forever until the channelFlow is cancelled
     *   (i.e., the session is stopped). No [Progress.Done] is emitted — the session keeps
     *   running totals from the per-device events. This is the "retry forever ON" path; it
     *   keeps all 4 LE workers + 1 BR/EDR worker continuously busy whenever there are enough
     *   candidates, instead of stalling between rounds while a single straggler finishes.
     *
     * Re-sorting on RSSI happens at every refresher tick (continuous) or once at pass start
     * (one-shot). Either way the strongest-signal candidate at the time of pick gets attempted
     * first.
     *
     * @param skipAddresses devices already successfully enumerated in this session — never
     * re-attempted (managed by the session).
     * @param attemptCounts session-wide attempt counter (address.uppercase() → count). Mutated
     * in place: incremented on every connect attempt. Devices whose count reaches
     * [maxAttemptsPerDevice] get filtered out of subsequent picks for the rest of the session.
     * The map is owned by the session so the cap survives across passes / continuous runs.
     */
    fun execute(
        skipAddresses: Set<String> = emptySet(),
        attemptCounts: MutableMap<String, Int> = mutableMapOf(),
        maxAttemptsPerDevice: Int = MAX_ATTEMPTS_PER_DEVICE,
        continuous: Boolean = false,
    ): Flow<Progress> = channelFlow {
        val skipApple = settingsRepository.getBulkSkipApple()
        val skipSamsung = settingsRepository.getBulkSkipSamsung()
        val normalizedSkip = skipAddresses.map { it.uppercase() }.toSet()

        // Initial snapshot: count vendor-pre-filtered devices for the Started progress event so
        // the user sees "(N pre-skipped)" up front. The same vendor filter is re-applied on each
        // re-snapshot below. Selection logic lives in [BulkEnumerateCandidateSelection] so it
        // can be JVM-unit-tested independently of this interactor's full dependency tree.
        val initialSnapshot = devicesRepository.observeLastBatch().first().toList()
        val initialConnectable = initialSnapshot.filter { it.isConnectable }
        val shouldSkipVendor: (DeviceData) -> Boolean = { vendorIdentifier.shouldSkip(it, skipApple, skipSamsung) }
        val initialAdvSkippedCount = BulkEnumerateCandidateSelection.countAdvSkipped(
            connectable = initialConnectable,
            normalizedSkipAddresses = normalizedSkip,
            shouldSkipVendor = shouldSkipVendor,
        )
        val initialCandidateCount = BulkEnumerateCandidateSelection.countCandidatesBeforeAttemptCap(
            connectable = initialConnectable,
            normalizedSkipAddresses = normalizedSkip,
            shouldSkipVendor = shouldSkipVendor,
        )

        // Snapshot the AD-fingerprint dedup set ONCE at pass start. New entries written via
        // [register] inside this pass land in the DB but are also reflected locally so the
        // refresher's filter sees them on its next rebuild — see [capturedFingerprints]
        // mutation below the worker loop. Persistent across app restarts (table is
        // [captured_advert_fingerprint]); cleared by Settings → Clear database.
        val capturedFingerprints: MutableSet<String> = capturedAdvertFingerprintRepository
            .allFingerprints()
            .toMutableSet()
        // Note: Started.total is sent below after [frozenCandidates] is built, so it reflects
        // the actual attempt count (initialCandidateCount minus the per-pass max-retries cap).

        // Concurrent workers: BLE_PARALLELISM LE slots + BREDR_PARALLELISM BR/EDR slot. Each
        // worker loops "pop next eligible device for my radio → enumerate → repeat". The pool
        // is serialised under [pickerLock] so two workers never grab the same device, but the
        // actual enumeration runs in parallel — up to 5 simultaneous in-flight connections
        // show up in the multi-line status display.
        //
        // Pool semantics depend on [continuous]:
        //   - one-shot: pool is built once from the initial scan batch, workers exit when
        //     it's empty, [Progress.Done] is emitted.
        //   - continuous: a background refresher rebuilds the pool from every fresh
        //     [observeLastBatch] emit, applying the same skip/cap/vendor filters and
        //     excluding addresses currently in flight. Workers never exit; pickNext blocks
        //     (with a small poll delay) when the pool is empty, then returns whatever the
        //     refresher pushed in. This eliminates the "single straggler at end of round"
        //     stall under retry-forever — workers refill the moment a new device appears,
        //     instead of waiting for the surviving worker to finish + the next pass to
        //     start before parallelism recovers.
        val succeeded = java.util.concurrent.atomic.AtomicInteger(0)
        val skippedVendor = java.util.concurrent.atomic.AtomicInteger(0)
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val attemptIndex = java.util.concurrent.atomic.AtomicInteger(0)
        val pickerLock = kotlinx.coroutines.sync.Mutex()

        // Pool = address.uppercase() → DeviceData, ordered by RSSI desc on insert. Workers
        // pop from the head; refresher rebuilds in continuous mode.
        val pool: java.util.LinkedHashMap<String, DeviceData> = java.util.LinkedHashMap()
        // Addresses currently being enumerated. The refresher excludes these so a device that
        // re-appears in the scan batch mid-attempt isn't double-popped by another worker.
        val inFlight: MutableSet<String> = mutableSetOf()

        suspend fun rebuildPool(snapshot: List<DeviceData>) = pickerLock.withLock {
            val candidates = BulkEnumerateCandidateSelection.selectFrozenCandidates(
                connectable = snapshot.filter { it.isConnectable },
                normalizedSkipAddresses = normalizedSkip,
                attemptCounts = attemptCounts,
                maxAttemptsPerDevice = maxAttemptsPerDevice,
                shouldSkipVendor = shouldSkipVendor,
                capturedFingerprints = capturedFingerprints,
                fingerprintFn = AdvertisementFingerprint::fingerprint,
            )
            pool.clear()
            for (d in candidates) {
                val key = d.address.uppercase()
                if (key !in inFlight) pool[key] = d
            }
        }

        rebuildPool(initialConnectable)
        // Send Started with the initial pool size. In continuous mode `total` is just the
        // initial snapshot — the actual attempt count climbs via attemptIndex as devices
        // arrive over time.
        send(Progress.Started(total = pool.size, skippedAdvFilter = initialAdvSkippedCount))

        suspend fun popMatching(forBrEdr: Boolean): DeviceData? = pickerLock.withLock {
            val it = pool.entries.iterator()
            while (it.hasNext()) {
                val d = it.next().value
                val matches = if (forBrEdr) d.transport == Transport.BREDR
                              else d.transport != Transport.BREDR
                if (matches) {
                    it.remove()
                    val key = d.address.uppercase()
                    inFlight.add(key)
                    attemptCounts[key] = (attemptCounts[key] ?: 0) + 1
                    return@withLock d
                }
            }
            null
        }

        suspend fun pickNext(forBrEdr: Boolean): DeviceData? {
            while (true) {
                val popped = popMatching(forBrEdr)
                if (popped != null) return popped
                if (!continuous) return null
                // Continuous: pool empty for this radio. Sleep briefly and let the refresher
                // pump in fresh candidates (or, for BR/EDR, wait for the slow inquiry cadence).
                delay(POOL_POLL_INTERVAL_MS)
            }
        }

        suspend fun releaseInFlight(address: String) = pickerLock.withLock {
            inFlight.remove(address.uppercase())
        }

        suspend fun runWorker(slotId: Int, forBrEdr: Boolean) {
            while (true) {
                val device = pickNext(forBrEdr) ?: return
                val idx = attemptIndex.getAndIncrement()
                // Display total tracks "max we've ever seen" so the count keeps climbing even
                // when devices appear mid-pass. Using attemptIndex.get() + 1 is a reasonable
                // lower-bound proxy with parallelism.
                val displayTotal = maxOf(initialCandidateCount, idx + 1)
                send(Progress.DeviceStarted(slotId = slotId, index = idx, total = displayTotal, device = device))
                val result = try {
                    enumerateOne(device, skipApple, skipSamsung)
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "Worker slot %d crashed on %s", slotId, device.address)
                    EnumResult(Outcome.ERROR, t.message ?: t::class.java.simpleName)
                }
                releaseInFlight(device.address)
                when (result.outcome) {
                    Outcome.SUCCESS, Outcome.SDP_SUCCESS -> succeeded.incrementAndGet()
                    Outcome.SKIPPED_VENDOR -> skippedVendor.incrementAndGet()
                    Outcome.SDP_TIMEOUT, Outcome.ERROR, Outcome.TIMEOUT -> errors.incrementAndGet()
                }
                // AD-fingerprint dedup: when an attempt completes with allCharsRead=true the
                // user explicitly does NOT want us to re-attempt the same AD bytes under a
                // rotated BDADDR. Register the fingerprint (idempotent on the table's PK) and
                // mirror into the in-memory set so the refresher's selectFrozenCandidates
                // sees it on the next rebuild without a DB round-trip per pop. Fingerprint
                // returns null for peers without raw AD bytes (BR/EDR-only inquiries) — those
                // fall through to address-only dedup, which is fine since BR/EDR addresses
                // don't rotate.
                if (result.outcome == Outcome.SUCCESS && result.allCharsRead) {
                    AdvertisementFingerprint.fingerprint(device)?.let { fp ->
                        pickerLock.withLock { capturedFingerprints.add(fp) }
                        runCatching {
                            capturedAdvertFingerprintRepository.register(
                                fingerprint = fp,
                                address = device.address,
                                capturedTimeMs = System.currentTimeMillis(),
                            )
                        }.onFailure { Timber.tag(TAG).w(it, "Failed to persist fingerprint for ${device.address}") }
                    }
                }
                // Re-fetch the device row on a successful enumeration. enumerateOne may have
                // promoted a freshly-read GATT 0x2A00 ("Device Name") and 0x2A29
                // ("Manufacturer Name String") into the row via setNameIfMissing /
                // setGattManufacturerNameIfMissing — but `device` here is the snapshot taken at
                // pop time, before either column was written. Without this refresh the
                // ConnectAllSession's `connected` list shows the stale "N/A" / address-only
                // display name even after the user can see the real name on the Device Details
                // screen (which queries the live row). Failure path: keep the original
                // snapshot; the row hasn't been mutated by enumerateOne for those outcomes.
                val finishedDevice = if (result.outcome == Outcome.SUCCESS || result.outcome == Outcome.SDP_SUCCESS) {
                    devicesRepository.getDeviceByAddress(device.address) ?: device
                } else {
                    device
                }
                send(Progress.DeviceFinished(slotId, idx, displayTotal, finishedDevice, result.outcome, result.errorMessage))
            }
        }

        coroutineScope {
            val refresherJob = if (continuous) {
                launch {
                    devicesRepository.observeLastBatch().collect { batch ->
                        rebuildPool(batch)
                    }
                }
            } else null

            val workerJobs = (0 until BLE_PARALLELISM).map { slot ->
                launch { runWorker(slotId = slot, forBrEdr = false) }
            } + (0 until BREDR_PARALLELISM).map { slot ->
                launch { runWorker(slotId = BLE_PARALLELISM + slot, forBrEdr = true) }
            }
            // In continuous mode, workers never exit on their own — this join blocks until
            // the channelFlow is cancelled (session.stop()), which propagates cancellation
            // down the worker tree and breaks them out of pickNext's delay loop.
            workerJobs.forEach { it.join() }
            refresherJob?.cancel()
        }

        // Only meaningful in one-shot mode. In continuous mode the workerJobs.join() above
        // only returns under cancellation, which throws a CancellationException before we
        // get here, so this Done event is naturally suppressed.
        send(
            Progress.Done(
                total = attemptIndex.get(),
                succeeded = succeeded.get(),
                skippedVendor = skippedVendor.get(),
                errors = errors.get(),
                advSkipped = initialAdvSkippedCount,
            )
        )
    }

    private suspend fun enumerateOne(device: DeviceData, skipApple: Boolean, skipSamsung: Boolean): EnumResult {
        // BR/EDR-only peer: the LE GATT path won't connect, so run SDP enumeration instead.
        if (device.transport == Transport.BREDR) {
            return enumerateBrEdrOne(device, skipApple, skipSamsung)
        }
        // DUAL peer: capture both transports' service data. SDP first (cheap, ~1s on a paired
        // bonded peer; bounded by the inner timeout) so the BR/EDR-side ServiceClassIDList
        // makes it into the BTIDES log + sdp_uuids column even if the LE GATT side
        // subsequently times out or auth-cancels. We swallow SDP errors here — the LE GATT
        // attempt is the source of truth for SUCCESS / SDP_TIMEOUT / ERROR outcomes.
        if (device.transport == Transport.DUAL) {
            runCatching { tryFetchSdpFor(device) }
                .onFailure { Timber.tag(TAG).w(it, "Dual-mode SDP attempt failed for ${device.address} (continuing to LE GATT)") }
        }
        btidesRepository.beginGattSession(device.address)
        var vendorMatched = false
        var pendingChars: List<BluetoothGattCharacteristic> = emptyList()
        var allCharsRead = false
        var gattRef: BluetoothGatt? = null
        var connected = false

        // Drive forward through `pendingChars` until either a read is successfully initiated
        // (the GATT callback will advance us via CharacteristicRead/FailedReadCharacteristic)
        // or the list is exhausted (we disconnect). A char that can't be initiated — busy /
        // unsupported / BLUETOOTH_PRIVILEGED-gated like 0x2B3A — is skipped silently, NOT
        // waited on. Without this loop, an un-initiable read produced no callback and the
        // bulk worker stalled until PER_DEVICE_TIMEOUT.
        fun startNextReadOrDisconnect(gatt: BluetoothGatt) {
            while (pendingChars.isNotEmpty()) {
                if (bleScannerHelper.readCharacteristic(gatt, pendingChars.first())) return
                pendingChars = pendingChars.drop(1)
            }
            allCharsRead = true
            bleScannerHelper.disconnect(gatt)
        }

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
                                    startNextReadOrDisconnect(event.gatt)
                                    false
                                }
                            }
                            is BleScannerHelper.DeviceConnectResult.CharacteristicRead -> {
                                var abortForVendor = false
                                // GATT 0x2A00 ("Device Name", part of Generic Access service): when a peer
                                // doesn't advertise a Local Name but exposes the GAP Device Name
                                // characteristic, decode it and promote it into the device row's `name`
                                // column. This becomes the display name everywhere — see
                                // DeviceData.buildDisplayName(), which falls back to `address` when
                                // both customName and name are null. setNameIfMissing() refuses to
                                // overwrite an existing name, so a later genuine advertisement still
                                // wins.
                                if (event.characteristic.uuid == GAP_DEVICE_NAME_UUID) {
                                    runCatching {
                                        // event.value is the raw bytes — base64 round-trip removed
                                        // (was a wasted alloc per char read on the bulk-enum hot path).
                                        val bytes = event.value
                                        // Trim NULs that some peers append, then decode as UTF-8.
                                        val name = String(bytes, Charsets.UTF_8).trimEnd(' ').trim()
                                        if (name.isNotEmpty()) {
                                            devicesRepository.setNameIfBetter(device.address, name)
                                            // A Galaxy / iPhone device that didn't advertise its name is
                                            // only recognisable once its GAP Device Name is read.
                                            // pickReadableCharacteristics reads 0x2A00 FIRST, so honour
                                            // Skip Samsung / Skip Apple here to abort before reading the
                                            // rest of its characteristics (otherwise it would enumerate
                                            // to completion and show up connected despite the toggle).
                                            if (vendorIdentifier.shouldSkipByName(name, skipApple, skipSamsung)) {
                                                abortForVendor = true
                                            }
                                        }
                                    }.onFailure {
                                        Timber.tag(TAG).w(it, "Failed to decode 0x2A00 value for ${device.address}")
                                    }
                                }
                                // GATT 0x2A29 ("Manufacturer Name String", Device Information service):
                                // peer-self-reported vendor name. Surfaces under "Manufacturer" on
                                // Device Details when no MSD-derived vendor is available; preferred
                                // over IEEE OUI inference because the peer explicitly claims it.
                                // setGattManufacturerNameIfMissing refuses to overwrite a prior
                                // capture, so a single bad read can't corrupt the row.
                                if (event.characteristic.uuid == DIS_MANUFACTURER_NAME_UUID) {
                                    runCatching {
                                        // event.value is raw bytes; same base64-removal as above.
                                        val bytes = event.value
                                        val mfg = String(bytes, Charsets.UTF_8).trimEnd(' ').trim()
                                        if (mfg.isNotEmpty()) {
                                            devicesRepository.setGattManufacturerNameIfMissing(device.address, mfg)
                                        }
                                    }.onFailure {
                                        Timber.tag(TAG).w(it, "Failed to decode 0x2A29 value for ${device.address}")
                                    }
                                }
                                if (abortForVendor) {
                                    // Same teardown as the service-UUID vendor match above: flag it,
                                    // discard the buffered GATT records, and disconnect (the Disconnected
                                    // event ends collectUntil, giving the SKIPPED_VENDOR outcome below).
                                    vendorMatched = true
                                    Timber.tag(TAG).i("Vendor name seen on ${device.address}; aborting")
                                    btidesRepository.markGattSessionForDiscard(device.address)
                                    gattRef?.let { bleScannerHelper.disconnect(it) }
                                } else {
                                    pendingChars = pendingChars.drop(1)
                                    gattRef?.let { startNextReadOrDisconnect(it) }
                                }
                                false
                            }
                            is BleScannerHelper.DeviceConnectResult.FailedReadCharacteristic -> {
                                // Auth/authz/encryption errors mean the peer requires bonding to
                                // read this characteristic. Android responds by triggering a
                                // user-visible pairing prompt for *every* such char read in a
                                // row — 12 characteristics × auth-required = 12 dialogs. Abort
                                // the remaining reads as soon as we see the first auth-shaped
                                // failure so the user only ever sees one prompt per device per
                                // attempt. Plain transient failures (133, 8 = busy etc.) keep
                                // advancing as before.
                                val isAuthFailure = event.status in AUTH_FAILURE_STATUSES
                                if (isAuthFailure) {
                                    Timber.tag(TAG).i(
                                        "Auth required (status=%d) on %s; aborting remaining %d char reads",
                                        event.status, device.address, pendingChars.size,
                                    )
                                    pendingChars = emptyList()
                                    allCharsRead = false
                                    gattRef?.let { bleScannerHelper.disconnect(it) }
                                } else {
                                    pendingChars = pendingChars.drop(1)
                                    gattRef?.let { startNextReadOrDisconnect(it) }
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
                EnumResult(Outcome.SUCCESS, allCharsRead = allCharsRead)
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
    /**
     * Fetch SDP UUIDs and persist them into the device row + BTIDES log. Used by both the
     * dedicated BR/EDR path (where the result drives the [Outcome]) and the new Dual-device
     * pre-pass (where any error is logged-and-swallowed because LE GATT is still going to
     * run). Returns the canonical UUID list on success, empty on no-data, throws on hard
     * errors so the caller can decide whether to convert to an EnumResult or log+continue.
     */
    private suspend fun tryFetchSdpFor(device: DeviceData): List<String> {
        val timestampMs = System.currentTimeMillis()
        val uuids = withTimeoutOrFallback(PER_DEVICE_TIMEOUT) {
            sdpEnumerationHelper.enumerate(device.address)
        }
        val canonical = uuids.map { it.toString().lowercase() }
        if (canonical.isEmpty()) {
            Timber.tag(TAG).i("SDP returned no UUIDs for ${device.address}")
            return canonical
        }
        devicesRepository.updateSdpUuids(device.address, canonical)
        btidesRepository.appendSDPDiscovery(device.address, uuids, timestampMs)
        Timber.tag(TAG).i("SDP captured ${canonical.size} UUIDs for ${device.address}: $canonical")
        return canonical
    }

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
        val readable = services
            .flatMap { it.characteristics.orEmpty() }
            .filter { (it.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0 }
        // Read the GAP Device Name (0x2A00) FIRST so a "Galaxy"/"iPhone"-style name lets us skip the
        // device (when Skip Samsung / Skip Apple is on) before spending time reading the rest of its
        // characteristics. Stable sort keeps every other char in discovery order, and collecting all
        // readable chars before the cap means 0x2A00 is included even if it'd fall past the cap.
        return readable
            .sortedByDescending { it.uuid == GAP_DEVICE_NAME_UUID }
            .take(MAX_CHARS_PER_DEVICE)
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
        // How often a worker re-checks the pool when it found nothing on the prior pop. Short
        // enough to keep parallelism filling fast as new scan batches arrive (LE scan emits
        // every ~10s; BR/EDR every ~60s) without busy-spinning while we wait on the radio.
        private const val POOL_POLL_INTERVAL_MS: Long = 500L
        // Most BR/EDR-only peers reject the ATT-over-BR/EDR connection; fail fast so the bulk
        // pass keeps moving.
        private val BR_EDR_GATT_TIMEOUT = 8.seconds
        // Concurrent worker counts. Android exposes 4 simultaneous BluetoothGatt connections
        // by default (see BluetoothGatt's binder pool); going beyond starves earlier connects.
        // BR/EDR runs a single SDP fetch at a time — there's only one inquiry-radio resource,
        // and SdpEnumerationHelper itself caps at 4 parallel fetches but Connect All only
        // generates one BR/EDR enumeration in flight from this orchestrator.
        const val BLE_PARALLELISM = 4
        const val BREDR_PARALLELISM = 1
        const val TOTAL_PARALLELISM = BLE_PARALLELISM + BREDR_PARALLELISM
        // BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION (5) /
        // GATT_INSUFFICIENT_AUTHORIZATION (8) / GATT_INSUFFICIENT_ENCRYPTION (15). Any of
        // these on a char read means the peer wants a bond to release the value; if the user
        // cancels the prompt, retrying further reads on the same connection produces a fresh
        // prompt per char.
        private val AUTH_FAILURE_STATUSES = setOf(5, 8, 15)
        // GAP "Device Name" characteristic — used as the display-name fallback when no
        // advertised Local Name is captured. UUID per BT SIG: 16-bit 0x2A00 expanded onto
        // the SIG base UUID 0000xxxx-0000-1000-8000-00805f9b34fb.
        private val GAP_DEVICE_NAME_UUID: java.util.UUID =
            java.util.UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
        // Device Information service (0x180A) → "Manufacturer Name String" characteristic.
        // 16-bit GATT UUID 0x2A29 expanded onto the SIG base UUID. Used as the vendor-name
        // fallback when no MSD-derived ManufacturerInfo is available.
        private val DIS_MANUFACTURER_NAME_UUID: java.util.UUID =
            java.util.UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    }
}
