package com.darkmentor.data.helpers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.darkmentor.data.btides.BTIDESRepository
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.domain.interactor.VendorIdentifier
import com.darkmentor.domain.model.BleScanDevice
import com.darkmentor.toBase64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BleScannerHelper(
    private val bleFiltersProvider: BleFiltersProvider,
    private val appContext: Context,
    private val powerModeHelper: PowerModeHelper,
    private val settingsRepository: SettingsRepository,
    private val btidesRepository: BTIDESRepository,
    private val vendorIdentifier: VendorIdentifier,
) {

    private val btidesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun inferBdaddrRand(addressType: Int?, address: String): Int {
        // Android BluetoothDevice address types: PUBLIC = 0, RANDOM = 1, UNKNOWN = 0xFFFF.
        // Default to random when unknown, since the BLE scanner predominantly surfaces
        // random-addressed peripherals.
        if (addressType != null && addressType in 0..1) return addressType
        // Heuristic from the top 2 bits of the most-significant address byte: any of the three
        // BLE random sub-types (static random, RPA, NRPA) leaves at least one of those bits set.
        val msbHex = address.substringBefore(':', "00")
        val msb = msbHex.toIntOrNull(16) ?: return 1
        return if ((msb and 0xC0) != 0) 1 else 0
    }

    @SuppressLint("MissingPermission")
    private fun deviceAddressType(device: BluetoothDevice): Int? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) device.addressType else null
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothScanner: BluetoothLeScanner? = null
    private val handler: Handler = Handler(Looper.getMainLooper())
    // ConcurrentHashMap because `onScanResult` (BLE binder thread) writes here while
    // `cancelScanning` (caller thread) reads/clears. A plain HashMap would lose writes or
    // throw ConcurrentModificationException under sustained scan rates.
    private val batch: MutableMap<String, BleScanDevice> = ConcurrentHashMap()
    private var currentScanTimeMs: Long = System.currentTimeMillis()
    private val connections: MutableMap<String, BluetoothGatt> = ConcurrentHashMap()

    var inProgress = MutableStateFlow(false)

    private var scanListener: ScanListener? = null

    /**
     * Snapshot of the system's bonded-device addresses, used by the BLE scan callback to
     * determine `isPaired` *without* a synchronous IPC into the Bluetooth process. We've seen
     * 8s+ ANRs on Android 14 + busy BT stacks where `BluetoothDevice.bondState` blocks the
     * BLE scan thread (which is the main looper) waiting for the bluetooth-server to reply.
     *
     * Refreshed at scan start and on every `ACTION_BOND_STATE_CHANGED` broadcast. Bond state
     * is a user-driven action (pair/unpair via OS UI) so the cache is naturally fresh enough.
     */
    @Volatile private var bondedAddresses: Set<String> = emptySet()
    private val bondStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            refreshBondedAddresses()
        }
    }
    private var bondReceiverRegistered: Boolean = false

    init {
        tryToInitBluetoothScanner()
    }

    @SuppressLint("MissingPermission")
    private fun refreshBondedAddresses() {
        bondedAddresses = runCatching {
            bluetoothAdapter?.bondedDevices?.mapNotNull { it.address?.uppercase() }?.toSet().orEmpty()
        }.getOrElse { emptySet() }
    }

    /**
     * Minimum-cost copy of a [ScanResult] for handoff to the consumer coroutine. Holding the
     * raw [ScanResult] across thread boundaries isn't safe (the system reuses the object after
     * `onScanResult` returns), so we materialise the bytes-and-primitives we actually need.
     */
    private data class RawScanResult(
        val address: String,
        val name: String?,
        val scanRecordRaw: ByteArray?,
        val rssi: Int,
        val addressType: Int?,
        val isPaired: Boolean,
        val deviceClass: Int?,
        val serviceUuids: List<String>,
        val isConnectable: Boolean,
        val isLegacy: Boolean,
        val scanTimeMs: Long,
        val callbackTimeMs: Long,
    )

    /**
     * Off-thread ingestion queue. The BLE binder callback only does field copies + a non-
     * blocking `trySend`; everything else (BleScanDevice allocation, BTIDES JsonObject
     * building, batch HashMap update) runs in the consumer coroutine on Dispatchers.IO.
     *
     * Capacity 4096 + DROP_OLDEST: at sustained overload we lose the oldest unprocessed
     * scan rather than blocking the BLE thread (which would cause the system to drop scans
     * upstream of us, with no signal to log). Each drop is logged so the user can see
     * if/when they're hitting it.
     */
    private val rawScanChannel = Channel<RawScanResult>(
        capacity = RAW_SCAN_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val rawScanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val droppedScanCount: AtomicInteger = AtomicInteger(0)

    init {
        rawScanScope.launch { consumeRawScans() }
    }

    private suspend fun consumeRawScans() {
        for (raw in rawScanChannel) {
            try {
                val device = BleScanDevice(
                    address = raw.address,
                    name = raw.name,
                    scanTimeMs = raw.scanTimeMs,
                    scanRecordRaw = raw.scanRecordRaw,
                    rssi = raw.rssi,
                    addressType = raw.addressType,
                    deviceClass = raw.deviceClass,
                    isPaired = raw.isPaired,
                    serviceUuids = raw.serviceUuids,
                    isConnectable = raw.isConnectable,
                )
                batch[device.address] = device
                recordToBTIDESOffThread(raw, device)
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Off-thread scan handler failed for %s", raw.address)
            }
        }
    }

    private suspend fun recordToBTIDESOffThread(raw: RawScanResult, device: BleScanDevice) {
        // When scanning was started by the Connect All pane (mode == CONNECT_ALL_AUTO) and the
        // user has the corresponding vendor-skip toggle on, we omit the advertisement from the
        // BTIDES log entirely. This matches the GATT-side behaviour (Connect All never tries
        // to enumerate Apple/Samsung when the toggles are on, and discards the buffered GATT
        // records on a mid-enumeration vendor match).
        //
        // Other scan modes (USER_EXPLICIT from the Devices-tab FAB, or NONE / background) keep
        // the toggles ignored — they exist to gate Connect All, not to filter scans the user
        // started for general-purpose capture.
        val skipMode = settingsRepository.getScanStartMode() == SettingsRepository.ScanStartMode.CONNECT_ALL_AUTO
        if (skipMode) {
            val skipApple = settingsRepository.getBulkSkipApple()
            val skipSamsung = settingsRepository.getBulkSkipSamsung()
            if (vendorIdentifier.shouldSkipByScanRecord(raw.scanRecordRaw, raw.address, raw.addressType, skipApple, skipSamsung)) {
                return
            }
        }

        val (advType, advTypeStr) = inferAdvType(raw.isLegacy, raw.isConnectable)
        val bdaddrRand = inferBdaddrRand(raw.addressType, raw.address)
        try {
            btidesRepository.appendScan(
                bdaddr = raw.address,
                bdaddrRand = bdaddrRand,
                advType = advType,
                advTypeStr = advTypeStr,
                scanTimeMs = raw.callbackTimeMs,
                rssi = raw.rssi,
                rawScanRecord = raw.scanRecordRaw,
            )
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Failed to append BTIDES record for %s", raw.address)
        }
    }

    private fun inferAdvType(isLegacy: Boolean, isConnectable: Boolean): Pair<Int, String> {
        return when {
            !isLegacy -> 10 to "AUX_ADV_IND"
            isConnectable -> 0 to "ADV_IND"
            else -> 2 to "ADV_NONCONN_IND"
        }
    }

    private val callback = object : ScanCallback() {

        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            ingestScanResult(result)
        }

        // Active when [SCAN_REPORT_DELAY_MS] > 0 — Android delivers a window's worth of
        // results in one batch instead of one callback per advertisement. In a dense
        // (250+ device) environment this collapses ~2500 callbacks/sec into ~2/sec, which
        // cuts ~100 MB/s of large-object allocation pressure (system Parcel deserialisation
        // + ScanRecord parsing + our per-result allocation chain). Validated as the fix
        // for the recurring OOM-while-scanning-with-Connect-All crash (heap pegged at
        // 192 MB cap → fixedPeriodTicker incidental allocator dies).
        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results ?: return
            for (r in results) ingestScanResult(r)
        }

        @SuppressLint("MissingPermission")
        private fun ingestScanResult(result: ScanResult?) {
            if (result == null || result.device == null) {
                Timber.e(IllegalArgumentException("Scan result is null"))
                return
            }

            // Field extraction MUST happen on this thread — the underlying ScanResult / device
            // objects can be reused by the system after the callback returns. Everything past
            // this point happens in the consumer coroutine.
            val scanRecord = result.scanRecord
            val device = result.device
            val raw = RawScanResult(
                address = device.address,
                name = device.name ?: scanRecord?.deviceName,
                scanRecordRaw = scanRecord?.bytes,
                rssi = result.rssi,
                addressType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) device.addressType else null,
                // Cached lookup — see [bondedAddresses]. Calling device.bondState here would
                // ANR the BLE scan thread under load (8s+ blocking IPC into bluetooth-server).
                isPaired = bondedAddresses.contains(device.address?.uppercase()),
                deviceClass = device.bluetoothClass?.deviceClass,
                serviceUuids = scanRecord?.serviceUuids?.map { it.uuid.toString() }.orEmpty(),
                isConnectable = result.isConnectable,
                isLegacy = result.isLegacy,
                scanTimeMs = currentScanTimeMs,
                callbackTimeMs = System.currentTimeMillis(),
            )
            // Cheap side effect: update the previously-noticed-UUIDs cache. Tracked here
            // because it gates the next BLE filter rebuild — needs to be in sync with what
            // the system actually saw.
            for (uuid in raw.serviceUuids) bleFiltersProvider.previouslyNoticedServicesUUIDs.add(uuid)

            // trySend is non-blocking: if the channel has filled up (consumer is behind), the
            // oldest queued scan is dropped to make room. We log a one-line summary every
            // 1000 drops so saturation is visible without flooding logcat.
            val sent = rawScanChannel.trySend(raw).isSuccess
            if (!sent) {
                val n = droppedScanCount.incrementAndGet()
                if (n % 1000 == 0) Timber.tag(TAG).w("Raw-scan channel saturated: %d drops total", n)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Timber.tag(TAG).e("BLE Scan failed with error: $errorCode")
            cancelScanning(ScanResultInternal.Failure(errorCode))
        }
    }

    /**
     * Connect to a peer at [address] over the chosen transport. Default is LE — every existing
     * caller stays bit-for-bit compatible. Pass [BluetoothDevice.TRANSPORT_BREDR] to force a
     * BR/EDR ATT connection (used for the experimental GATT-over-BR/EDR path on CLASSIC-only
     * devices that advertise Generic Access / Generic Attribute via SDP). In practice fewer
     * than ~5% of BR/EDR-only devices support that path; expect graceful failure on most.
     *
     * Defensively cancels any pending BR/EDR inquiry before connecting — Android documents this
     * as a precondition for `connectGatt`, and it's cheap when no inquiry is running.
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(
        address: String,
        transport: Int = BluetoothDevice.TRANSPORT_LE,
    ): Flow<DeviceConnectResult> {
        return callbackFlow {
            val services = mutableSetOf<BluetoothGattService>()
            val device = requireAdapter().getRemoteDevice(address)
            var gatt: BluetoothGatt? = null

            val bdaddrRand = inferBdaddrRand(deviceAddressType(device), address)

            fun captureGattEnumeration(allServices: List<BluetoothGattService>) {
                btidesScope.launch {
                    try {
                        btidesRepository.appendGATTEnumeration(address, bdaddrRand, allServices)
                    } catch (e: Throwable) {
                        Timber.tag(TAG_CONNECT).w(e, "Failed to append BTIDES GATT enumeration for $address")
                    }
                }
            }

            fun captureCharacteristicRead(characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                btidesScope.launch {
                    try {
                        btidesRepository.appendCharacteristicRead(address, bdaddrRand, characteristic, value, status)
                    } catch (e: Throwable) {
                        Timber.tag(TAG_CONNECT).w(e, "Failed to append BTIDES characteristic read for $address")
                    }
                }
            }

            fun captureDescriptorRead(descriptor: BluetoothGattDescriptor, value: ByteArray, status: Int) {
                btidesScope.launch {
                    try {
                        btidesRepository.appendDescriptorRead(address, bdaddrRand, descriptor, value, status)
                    } catch (e: Throwable) {
                        Timber.tag(TAG_CONNECT).w(e, "Failed to append BTIDES descriptor read for $address")
                    }
                }
            }

            val callback = object : BluetoothGattCallback() {
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    super.onServicesDiscovered(gatt, status)
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Timber.tag(TAG_CONNECT).d("Services discovered. ${gatt.services.size} services for device $address")
                        services.addAll(gatt.services.orEmpty())
                        captureGattEnumeration(services.toList())
                        trySend(DeviceConnectResult.AvailableServices(gatt, services.toList()))
                    } else {
                        Timber.tag(TAG_CONNECT).e("Error while discovering services for device $address. Gatt is null")
                    }
                }

                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                    super.onCharacteristicRead(gatt, characteristic, value, status)
                    captureCharacteristicRead(characteristic, value, status)
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Timber.tag(TAG_CONNECT).d("Characteristic read. ${characteristic.uuid}, value: ${value.decodeToString()}")
                        // Pass the raw bytes through instead of round-tripping value.toBase64()
                        // → consumer.fromBase64(). Every char read used to allocate two
                        // throwaway byte-array-and-String pairs (encoder + decoder buffers)
                        // for no semantic benefit; both consumers (DeviceDetailsViewModel +
                        // BulkEnumerateGattInteractor) immediately re-decoded back to bytes.
                        trySend(DeviceConnectResult.CharacteristicRead(gatt, characteristic, value))
                    } else {
                        Timber.tag(TAG_CONNECT).e("Error while reading characteristic ${characteristic.uuid}. Error code: $status")
                        trySend(DeviceConnectResult.FailedReadCharacteristic(gatt, characteristic, status))
                    }
                }

                override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int, value: ByteArray) {
                    super.onDescriptorRead(gatt, descriptor, status, value)
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Timber.tag(TAG_CONNECT).d("Descriptor read. ${descriptor.uuid}, value: ${value.decodeToString()}")
                        captureDescriptorRead(descriptor, value, status)
                        // Same raw-bytes pass-through as CharacteristicRead — drop the
                        // base64 round-trip that was wasted allocation per descriptor read.
                        trySend(DeviceConnectResult.DescriptorRead(gatt, descriptor, value))
                    } else {
                        Timber.tag(TAG_CONNECT).e("Error while reading descriptor ${descriptor.uuid}. Error code: $status")
                        trySend(DeviceConnectResult.FailedReadDescriptor(gatt, descriptor))
                    }
                }

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    super.onConnectionStateChange(gatt, status, newState)
                    checkStatus(newState, gatt, status)
                }

                private fun checkStatus(newState: Int, gatt: BluetoothGatt, status: Int) {
                    connections[address] = gatt
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTING -> {
                            Timber.tag(TAG_CONNECT).d("Connecting to device $address")
                            trySend(DeviceConnectResult.Connecting)
                        }

                        BluetoothProfile.STATE_CONNECTED -> {
                            Timber.tag(TAG_CONNECT).d("Connected to device $address")
                            trySend(DeviceConnectResult.Connected(gatt))
                        }

                        BluetoothProfile.STATE_DISCONNECTING -> {
                            Timber.tag(TAG_CONNECT).d("Disconnecting from device $address")
                            trySend(DeviceConnectResult.Disconnecting)
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Timber.tag(TAG_CONNECT).d("Disconnected from device $address")
                            handleDisconnect(status, gatt)
                            close(gatt)
                        }

                        else -> {
                            Timber.tag(TAG_CONNECT).e("Error while connecting to device $address. Error code: $status")
                            trySend(DeviceConnectResult.DisconnectedWithError.UnspecifiedConnectionError(gatt, status))
                        }
                    }
                }

                private fun handleDisconnect(status: Int, gatt: BluetoothGatt) {
                    when (status) {
                        BluetoothGatt.GATT_SUCCESS -> {
                            trySend(DeviceConnectResult.Disconnected)
                        }

                        CONNECTION_FAILED_TO_ESTABLISH -> {
                            Timber.tag(TAG_CONNECT).e("Error while connecting to device $address. Error code: $status")
                            trySend(DeviceConnectResult.DisconnectedWithError.ConnectionFailedToEstablish(gatt, status))
                        }

                        CONNECTION_FAILED_BEFORE_INITIALIZING -> {
                            Timber.tag(TAG_CONNECT).e("Error while connecting to device $address. Error code: $status")
                            trySend(DeviceConnectResult.DisconnectedWithError.ConnectionFailedBeforeInitializing(gatt, status))
                        }

                        CONNECTION_TERMINATED -> {
                            Timber.tag(TAG_CONNECT).e("Error while connecting to device $address. Error code: $status")
                            trySend(DeviceConnectResult.DisconnectedWithError.ConnectionTerminated(gatt, status))
                        }

                        BluetoothGatt.GATT_CONNECTION_TIMEOUT -> {
                            Timber.tag(TAG_CONNECT).e("Error while connecting to device $address. Error code: $status")
                            trySend(DeviceConnectResult.DisconnectedWithError.ConnectionTimeout(gatt, status))
                        }

                        BluetoothGatt.GATT_FAILURE -> {
                            Timber.tag(TAG_CONNECT).e("Error while connecting to device $address. Error code: $status")
                            trySend(DeviceConnectResult.DisconnectedWithError.ConnectionFailedTooManyClients(gatt, status))
                        }

                        else -> {
                            Timber.tag(TAG_CONNECT).e("Error while connecting to device $address. Error code: $status")
                            trySend(DeviceConnectResult.DisconnectedWithError.UnspecifiedConnectionError(gatt, status))
                        }
                    }
                }
            }

            // Cancel any in-flight BR/EDR inquiry — Android requires this before connectGatt.
            // Safe no-op when nothing is running.
            runCatching { requireAdapter().cancelDiscovery() }
            val transportLabel = when (transport) {
                BluetoothDevice.TRANSPORT_BREDR -> "BR/EDR"
                BluetoothDevice.TRANSPORT_LE -> "LE"
                else -> "AUTO"
            }
            Timber.tag(TAG_CONNECT).d("Connecting to device $address over $transportLabel")
            gatt = device.connectGatt(appContext, false, callback, transport)

            awaitClose {
                Timber.tag(TAG_CONNECT).d("Closing connection to device $address")
                if (isDeviceConnected(device)) {
                    gatt.disconnect()
                } else {
                    close(gatt)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverServices(gatt: BluetoothGatt) {
        Timber.tag(TAG_CONNECT).d("Discovering services for device ${gatt.device.address}")
        gatt.discoverServices()
    }

    @SuppressLint("MissingPermission")
    fun disconnect(gatt: BluetoothGatt) {
        Timber.tag(TAG_CONNECT).d("Disconnecting from device ${gatt.device.address}")
        gatt.disconnect()
    }

    @SuppressLint("MissingPermission")
    fun close(gatt: BluetoothGatt, tag: String = TAG_CONNECT) {
        Timber.tag(tag).i("Closing connection to device ${gatt.device.address}")
        if (isDeviceConnected(gatt.device)) {
            Timber.tag(tag).e("Trying to close connection for device ${gatt.device.address} while it is still connected.")
        }
        gatt.close()
        connections.remove(gatt.device.address)
    }

    fun closeDeviceConnection(address: String) {
        connections[address]?.let(::close)
    }

    @SuppressLint("MissingPermission")
    fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return requireBluetoothManager().getConnectionState(device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
    }

    @SuppressLint("MissingPermission")
    fun isDeviceDisconnected(device: BluetoothDevice): Boolean {
        return requireBluetoothManager().getConnectionState(device, BluetoothProfile.GATT) == BluetoothProfile.STATE_DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    suspend fun hardDisconnectDevice(device: BluetoothDevice, tag: String = TAG_CONNECT) {
        return callbackFlow<Unit> {
            Timber.tag(tag).i("Trying to close connection to device ${device.address}")
            val gatt = device.connectGatt(appContext, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    super.onConnectionStateChange(gatt, status, newState)
                    Timber.tag(tag).i("Connection state change for device ${gatt.device.address}. Status: $status, newState: $newState")
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Timber.tag(tag).i("Try disconnect from ${gatt.device.address}")
                            gatt.disconnect()
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Timber.tag(tag).i("Disconnected. Closing connection ${gatt.device.address}")
                            gatt.close()
                            trySend(Unit)
                            this@callbackFlow.close()
                        }
                    }
                }
            })

            awaitClose {
                if (isDeviceConnected(device)) {
                    Timber.tag(tag).e("Device ${gatt.device.address} is still connected")
                }
            }
        }.first()
    }

    @SuppressLint("MissingPermission")
    suspend fun hardCloseAllConnections(tag: String = TAG_CONNECT) {
        connections.values.forEach { close(it, tag) }
        connections.clear()
        val otherConnections = requireBluetoothManager().getConnectedDevices(BluetoothProfile.GATT)
        Timber.tag(tag).i("Found ${otherConnections.size} other connections")
        otherConnections.forEach { device ->
            hardDisconnectDevice(device, tag)
        }
        System.gc()
        val stillConnected = requireBluetoothManager().getConnectedDevices(BluetoothProfile.GATT)
        Timber.tag(tag).i("Hard close all connections done. ${stillConnected.size} connections left")
    }

    /**
     * Initiate a GATT char read. Returns `true` if the read was queued (the result will arrive
     * via the [BluetoothGattCallback.onCharacteristicRead] path → `CharacteristicRead` /
     * `FailedReadCharacteristic` flow events). Returns `false` when the read could NOT be
     * initiated — either because [BluetoothGatt.readCharacteristic] returned false (busy /
     * unsupported / wrong state) or because the system threw a [SecurityException] (a few
     * GATT chars are gated behind `BLUETOOTH_PRIVILEGED`, e.g. 0x2B3A "Server Supported
     * Features"; observed in dense Connect-All passes).
     *
     * The Boolean lets the caller advance to the next characteristic instead of waiting for
     * a callback that will never arrive — without this signal, the bulk enumerator's
     * collectUntil hangs until the 20s per-device timeout. The earlier `void` signature also
     * let SecurityException escape into the connect flow, which the bulk pipeline's outer
     * `catch (Throwable)` swept up as a fatal device-level ERROR.
     */
    @SuppressLint("MissingPermission")
    fun readCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        Timber.tag(TAG_CONNECT).d("Reading characteristic ${characteristic.uuid}")
        return try {
            val initiated = gatt.readCharacteristic(characteristic)
            if (!initiated) {
                Timber.tag(TAG_CONNECT).w("readCharacteristic refused for ${characteristic.uuid} (busy / unsupported)")
            }
            initiated
        } catch (e: SecurityException) {
            Timber.tag(TAG_CONNECT).w("BLUETOOTH_PRIVILEGED denied for ${characteristic.uuid}; skipping")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun readDescriptor(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, descriptorUuid: UUID) {
        Timber.tag(TAG_CONNECT).d("Reading descriptor $descriptorUuid for characteristic ${characteristic.uuid}")
        val descriptor = characteristic.getDescriptor(descriptorUuid)
        gatt.readDescriptor(descriptor)
    }

    sealed interface DeviceConnectResult {
        data class AvailableServices(val gatt: BluetoothGatt, val services: List<BluetoothGattService>) : DeviceConnectResult
        data class CharacteristicRead(val gatt: BluetoothGatt, val characteristic: BluetoothGattCharacteristic, val value: ByteArray) :
            DeviceConnectResult

        data class FailedReadCharacteristic(
            val gatt: BluetoothGatt,
            val characteristic: BluetoothGattCharacteristic,
            /** GATT error status (BluetoothGatt.GATT_*). 5=auth, 8=authz, 15=encryption. */
            val status: Int,
        ) : DeviceConnectResult
        data class DescriptorRead(val gatt: BluetoothGatt, val descriptor: BluetoothGattDescriptor, val value: ByteArray) : DeviceConnectResult
        data class FailedReadDescriptor(val gatt: BluetoothGatt, val descriptor: BluetoothGattDescriptor) : DeviceConnectResult
        data object Connecting : DeviceConnectResult
        data class Connected(val gatt: BluetoothGatt) : DeviceConnectResult
        data object Disconnecting : DeviceConnectResult
        data object Disconnected : DeviceConnectResult
        sealed interface DisconnectedWithError : DeviceConnectResult {
            val errorCode: Int
            val gatt: BluetoothGatt

            class UnspecifiedConnectionError(override val gatt: BluetoothGatt, override val errorCode: Int) : DisconnectedWithError
            class ConnectionTimeout(override val gatt: BluetoothGatt, override val errorCode: Int) : DisconnectedWithError
            class ConnectionTerminated(override val gatt: BluetoothGatt, override val errorCode: Int) : DisconnectedWithError
            class ConnectionFailedToEstablish(override val gatt: BluetoothGatt, override val errorCode: Int) : DisconnectedWithError
            class ConnectionFailedBeforeInitializing(override val gatt: BluetoothGatt, override val errorCode: Int) : DisconnectedWithError
            class ConnectionFailedTooManyClients(override val gatt: BluetoothGatt, override val errorCode: Int) : DisconnectedWithError
        }
    }

    fun isBluetoothEnabled(): Boolean {
        tryToInitBluetoothScanner()
        return bluetoothAdapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    suspend fun scan(
        scanListener: ScanListener,
    ) {
        Timber.tag(TAG).d("Start BLE Scan. Restricted mode: ${powerModeHelper.powerMode().useRestrictedBleConfig}")

        if (!isBluetoothEnabled()) {
            throw BluetoothIsNotInitialized()
        }

        if (inProgress.value) {
            // Not actually a failure — happens normally when the Connect All candidate-poll
            // ticks while an earlier scan window is still in flight. Demoted to debug so we
            // don't spam logcat at red-error level for an expected race.
            Timber.tag(TAG).d("Scan request ignored: previous scan is still in flight")
        } else {
            this@BleScannerHelper.scanListener = scanListener
            batch.clear()

            inProgress.tryEmit(true)
            currentScanTimeMs = System.currentTimeMillis()

            val powerMode = powerModeHelper.powerMode()
            val keepScreenOn = powerMode.tryToTurnOnScreen && settingsRepository.getWakeUpScreenWhileScanning()
            val scanFilters = if (powerMode.useRestrictedBleConfig && !keepScreenOn) {
                bleFiltersProvider.getBackgroundFilters()
            } else {
                listOf(ScanFilter.Builder().build())
            }

            if (powerMode.tryToTurnOnScreen && settingsRepository.getWakeUpScreenWhileScanning()) {
                Timber.tag(TAG).d("Will try to turn on screen for ${powerMode.scanDuration} ms")
                powerModeHelper.wakeScreenTemporarily(powerMode.scanDuration)
            }

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                // Batch results into ~[SCAN_REPORT_DELAY_MS] windows. The system buffers
                // advertisements and delivers them via [ScanCallback.onBatchScanResults] as
                // a List<ScanResult> instead of one [onScanResult] per packet. In a dense
                // (250+ device) environment this drops callback rate from ~2500/sec to
                // ~2/sec — and with it ~100 MB/s of LOS allocation pressure. Trade-off: a
                // newly-arrived device's first detection lands up to [SCAN_REPORT_DELAY_MS]
                // later than otherwise; acceptable for this app's use case (the user sees
                // the device populate within ~1 sec). Some devices don't support hardware
                // batching; the framework falls back to software batching, which is
                // functionally identical for our purposes.
                .setReportDelay(SCAN_REPORT_DELAY_MS)
                .build()

            // Refresh the bonded-addresses cache before each scan window starts. The
            // [onScanResult] callback then does an O(1) Set lookup instead of an IPC into the
            // Bluetooth process, avoiding a recurring 8s+ ANR on Android 14 when the BT stack
            // is busy. We also subscribe to ACTION_BOND_STATE_CHANGED once so the cache stays
            // current if the user pairs/unpairs while scanning.
            refreshBondedAddresses()
            ensureBondReceiverRegistered()

            withContext(Dispatchers.IO) {
                requireScanner().startScan(scanFilters, scanSettings, callback)
                handler.postDelayed({ cancelScanning(ScanResultInternal.Success) }, powerModeHelper.powerMode().scanDuration)
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun ensureBondReceiverRegistered() {
        if (bondReceiverRegistered) return
        val filter = android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(bondStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(bondStateReceiver, filter)
        }
        bondReceiverRegistered = true
    }

    fun stopScanning() {
        cancelScanning(ScanResultInternal.Canceled)
    }

    /**
     * Best-effort poke of the system BLE scanner state to clear any LE scan registrations
     * tied to this UID that the OS didn't reclaim (e.g. after a force-kill or OOM of a
     * prior process instance). On Qualcomm-based phones (moto g family observed) those
     * leaked registrations silently block subsequent [BluetoothAdapter.startDiscovery]
     * BR/EDR inquiry calls — startDiscovery returns true, but no ACTION_DISCOVERY_STARTED
     * broadcast ever follows.
     *
     * Calling [BluetoothLeScanner.stopScan] against the singleton callback is a no-op when
     * no scan is registered, but Android's BluetoothManagerService uses these calls as a
     * trigger to walk its scanner-client list and prune entries whose IBinder is dead.
     * In practice this restores BR/EDR inquiry on the very next cycle for the moto g case.
     */
    @SuppressLint("MissingPermission")
    fun flushLeakedScans() {
        if (bluetoothAdapter?.state != BluetoothAdapter.STATE_ON) return
        try {
            bluetoothScanner?.flushPendingScanResults(callback)
        } catch (e: Throwable) {
            Timber.tag(TAG).d(e, "flushPendingScanResults threw (no-op when not registered)")
        }
        try {
            bluetoothScanner?.stopScan(callback)
        } catch (e: Throwable) {
            Timber.tag(TAG).d(e, "stopScan threw during flushLeakedScans (no-op when not registered)")
        }
        Timber.tag(TAG).d("flushLeakedScans: nudged the system scanner state machine")
    }

    /** True if at least one GATT connection is currently held. Used by BgScanService to defer
     *  BR/EDR inquiry windows during in-flight enumeration. */
    fun hasOpenGattConnections(): Boolean = connections.isNotEmpty()

    @SuppressLint("MissingPermission")
    private fun cancelScanning(scanResult: ScanResultInternal) {
        inProgress.tryEmit(false)

        // With [SCAN_REPORT_DELAY_MS] > 0 the system buffers results in batch mode and
        // delivers them via [onBatchScanResults] only at flush time. Per Android docs the
        // requested delay is clamped to ≥5000 ms internally, so the typical scan window
        // ends BEFORE the system has spontaneously flushed. We have to:
        //   1. Ask the controller to flush (async).
        //   2. Wait long enough for the flush callback to land — while the scanner is
        //      still registered, otherwise the records are dropped on an unregistered
        //      callback ID.
        //   3. THEN stopScan + snapshot + deliver onSuccess.
        // Earlier attempt (flush + stopScan synchronously, then postDelayed snapshot)
        // silently dropped every batch because stopScan unregistered the callback
        // before the system flush had a chance to invoke it.
        if (bluetoothAdapter?.state == BluetoothAdapter.STATE_ON) {
            try {
                bluetoothScanner?.flushPendingScanResults(callback)
            } catch (e: Throwable) {
                Timber.tag(TAG).d(e, "flushPendingScanResults threw during cancelScanning")
            }
            // Don't call requireAdapter().cancelDiscovery() here. The BLE scan teardown runs
            // every ~10s; before BR/EDR support landed, this line was a no-op (no inquiry
            // was ever in flight). With BrEdrDiscoveryHelper running on its own cadence,
            // calling cancelDiscovery here cuts each ~12s inquiry short ~3s in, killing
            // the ACTION_DISCOVERY_FINISHED broadcast and starving handleBrEdrInquiryResult
            // of any data. The BR/EDR helper does its own defensive cancelDiscovery before
            // each startDiscovery, which is the only place that needs it.
        }

        // Capture state locally so concurrent state mutation by a re-entered scan() can't
        // surprise the deferred lambda. scanListener stays referenced through the closure
        // even after we null it on the field.
        val capturedListener = scanListener
        scanListener = null
        handler.postDelayed({
            // Only NOW do we stopScan — by this point the system flush from above has had
            // [BATCH_FLUSH_GRACE_MS] to deliver via onBatchScanResults, and the consumer
            // coroutine has had time to drain the channel into [batch].
            if (bluetoothAdapter?.state == BluetoothAdapter.STATE_ON) {
                try {
                    bluetoothScanner?.stopScan(callback)
                } catch (e: Throwable) {
                    Timber.tag(TAG).d(e, "stopScan threw during cancelScanning postDelayed")
                }
            }
            when (scanResult) {
                is ScanResultInternal.Success -> {
                    Timber.tag(TAG).d("BLE Scan finished ${batch.count()} devices found")
                    capturedListener?.onSuccess(batch.values.toList())
                }
                is ScanResultInternal.Failure -> {
                    capturedListener?.onFailure(BLEScanFailure(scanResult.errorCode, BleScanErrorMapper.map(scanResult.errorCode)))
                }
                is ScanResultInternal.Canceled -> {
                    // do nothing
                }
            }
        }, BATCH_FLUSH_GRACE_MS)
    }

    private fun tryToInitBluetoothScanner() {
        bluetoothAdapter = requireBluetoothManager().adapter
        bluetoothScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    private fun requireBluetoothManager(): BluetoothManager {
        return appContext.getSystemService(BluetoothManager::class.java)
    }

    private fun requireScanner(): BluetoothLeScanner {
        if (bluetoothScanner == null) {
            tryToInitBluetoothScanner()
        }
        return bluetoothScanner ?: throw BluetoothIsNotInitialized()
    }

    private fun requireAdapter(): BluetoothAdapter {
        if (bluetoothAdapter == null) {
            tryToInitBluetoothScanner()
        }
        return bluetoothAdapter ?: throw BluetoothIsNotInitialized()
    }

    interface ScanListener {
        fun onSuccess(batch: List<BleScanDevice>)
        fun onFailure(exception: Exception)
        /**
         * Optional incremental callback fired the first time a device is seen during the current
         * scan/inquiry window. Lets BR/EDR consumers surface devices in the UI as soon as
         * ACTION_FOUND arrives rather than waiting up to ~13s for ACTION_DISCOVERY_FINISHED.
         * Default no-op preserves LE-side behaviour, which already emits frequently via
         * onSuccess batches.
         */
        fun onIncrementalDevice(device: BleScanDevice) {}
    }

    private sealed interface ScanResultInternal {

        object Success : ScanResultInternal

        data class Failure(val errorCode: Int) : ScanResultInternal

        object Canceled : ScanResultInternal
    }

    class BLEScanFailure(errorCode: Int, errorDescription: String) :
        RuntimeException("BLE Scan failed with error code: $errorCode (${errorDescription})")

    class BluetoothIsNotInitialized : RuntimeException("Bluetooth is turned off or not available on this device")

    companion object {
        private const val TAG = "BleScannerHelper"
        private const val TAG_CONNECT = "BleScannerHelperConnect"
        private const val CONNECTION_FAILED_BEFORE_INITIALIZING = 0x85
        private const val CONNECTION_FAILED_TO_ESTABLISH = 0x3E
        private const val CONNECTION_TERMINATED = 0x16
        // 4096 raw scans buffered between the BLE callback and the consumer coroutine. At
        // ~1 KB per RawScanResult that's ~4 MB worst-case retention. Sized for a sustained
        // 5k advertisements/sec environment with the consumer ~1 sec behind.
        private const val RAW_SCAN_CHANNEL_CAPACITY = 4096
        // Hardware/software batch window for [ScanSettings.setReportDelay]. 500 ms is the
        // sweet spot: aggressive enough to coalesce per-device advertisement bursts (most
        // peripherals broadcast at 50–100 Hz, so a 500 ms window catches every device once
        // and dedups ~50 callbacks/device into one), short enough that a newly-arrived
        // device's first paint lands inside ~1 sec of physical detection. Note: Android
        // internally clamps this to ≥5000 ms — the system flushes either at our explicit
        // [BluetoothLeScanner.flushPendingScanResults] call (issued in [cancelScanning])
        // or at scan stop, whichever is sooner.
        private const val SCAN_REPORT_DELAY_MS: Long = 500L
        // Grace window after the explicit flush + stopScan in [cancelScanning], before we
        // snapshot the batch map and deliver onSuccess to the listener. The system
        // delivers the buffered batch via onBatchScanResults from the BLE binder thread
        // shortly after our flush request; the consumer coroutine then drains the channel
        // into the [batch] map. 250 ms is empirically a comfortable upper bound for this
        // round trip on the moto g 5G — we've measured ~50–150 ms in steady state.
        // Without this delay, the snapshot fires synchronously and reports "0 devices
        // found" while the system silently drops a multi-record batch arriving 18 ms later.
        private const val BATCH_FLUSH_GRACE_MS: Long = 250L
    }
}