package f.cking.software.ui.devicedetails

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import f.cking.software.R
import f.cking.software.data.btides.BTIDESRepository
import f.cking.software.data.helpers.BleScannerHelper
import f.cking.software.data.helpers.CluesRepository
import f.cking.software.data.helpers.LocationProvider
import f.cking.software.data.helpers.PermissionHelper
import f.cking.software.data.helpers.PowerModeHelper
import f.cking.software.data.helpers.SdpEnumerationHelper
import f.cking.software.data.helpers.SdpServiceClassNames
import f.cking.software.domain.model.Transport
import f.cking.software.data.repo.DevicesRepository
import f.cking.software.data.repo.LocationRepository
import f.cking.software.domain.interactor.GetBleAdTypeName
import f.cking.software.domain.interactor.GetBleRecordFramesFromRawInteractor
import f.cking.software.domain.interactor.GetCharacteristicNameFromUUID
import f.cking.software.domain.interactor.GetServiceNameFromBluetoothService
import f.cking.software.domain.interactor.ParseBleAdRecord
import f.cking.software.domain.model.DeviceData
import f.cking.software.domain.model.LocationModel
import f.cking.software.domain.toDomain
import f.cking.software.extract16BitUuid
import f.cking.software.fromBase64
import f.cking.software.service.BgScanService
import f.cking.software.toBase64
import f.cking.software.toHexString
import f.cking.software.utils.navigation.BackCommand
import f.cking.software.utils.navigation.Router
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import timber.log.Timber
import java.util.UUID

class DeviceDetailsViewModel(
    private val address: String,
    private val router: Router,
    private val devicesRepository: DevicesRepository,
    private val locationRepository: LocationRepository,
    private val locationProvider: LocationProvider,
    private val permissionHelper: PermissionHelper,
    private val bleScannerHelper: BleScannerHelper,
    private val getBleRecordFramesFromRawInteractor: GetBleRecordFramesFromRawInteractor,
    private val cluesRepository: CluesRepository,
    private val btidesRepository: BTIDESRepository,
    private val sdpEnumerationHelper: SdpEnumerationHelper,
) : ViewModel() {

    var deviceState: DeviceData? by mutableStateOf(null)
    var pointsState: List<LocationModel> by mutableStateOf(emptyList())
    /**
     * Per-detection RSSI keyed by [LocationModel.time]. Sidecar to [pointsState] so the
     * existing markers/path rendering code stays untouched, while the heatmap renderer can
     * colour each point by signal strength. Keys are missing (not null) for older detections
     * recorded before migration 21→22.
     */
    var rssiByTime: Map<Long, Int?> by mutableStateOf(emptyMap())
    /**
     * Weighted-centroid "best-fit" position computed from the RSSI samples in
     * [pointsState]/[rssiByTime]. Null when there's not enough data (no samples with RSSI, or
     * all samples within a few metres of each other). Rendered as a black marker on the map.
     */
    var bestFitLocation: LocationModel? by mutableStateOf(null)
    var cameraState: MapCameraState by mutableStateOf(DEFAULT_MAP_CAMERA_STATE)
    var historyPeriod by mutableStateOf(DEFAULT_HISTORY_PERIOD)
    var markersInLoadingState by mutableStateOf(false)
    var loadingHeatmap by mutableStateOf(false)
    var onlineStatusData: OnlineStatus? by mutableStateOf(null)
    var pointsStyle: PointsStyle by mutableStateOf(DEFAULT_POINTS_STYLE)
    var rawData: List<AdRecord> by mutableStateOf(listOf())
    var services: Set<ServiceData> by mutableStateOf(emptySet())
    /**
     * Mirrors `device.sdpUuids` resolved through CLUES for display. Empty when there are no
     * cached SDP results AND the auto-fetch hasn't completed yet — the UI distinguishes by
     * checking [sdpFetchInProgress].
     */
    var sdpServices: List<SdpServiceData> by mutableStateOf(emptyList())
    var sdpFetchInProgress: Boolean by mutableStateOf(false)
    var sdpLastFetchTimeMs: Long? by mutableStateOf(null)
    private var sdpFetchJob: Job? = null
    var connectionStatus: ConnectionStatus by mutableStateOf(ConnectionStatus.DISCONNECTED)
    var recentReadFailures: Set<String> by mutableStateOf(emptySet())
    private val readFailureJobs: MutableMap<String, Job> = mutableMapOf()
    private var connectionJob: Job? = null
    // True between when the user taps Connect and when they tap Disconnect (or leave the screen).
    // Lets us tell apart a peer-initiated drop ("Sargon hung up after 30s") from a user-initiated
    // teardown — only the former should trigger an auto-reconnect.
    private var userWantsConnected: Boolean = false
    // Bounded auto-reconnect counter to avoid hammering an unreachable peer. Reset on every
    // user-initiated Connect / Disconnect.
    private var autoReconnectAttempts: Int = 0

    var mapExpanded: Boolean by mutableStateOf(false)

    // Default off — the heatmap renders an opaque GroundOverlay over OSM tiles which makes it
    // hard to see the actual point markers / device path on a fresh load. The user can opt in
    // per-device-screen via the toggle in the map header. Auto-disabled via a different code
    // path further down when the point count exceeds MAX_POINTS_FOR_HEATMAP.
    var useHeatmap: Boolean by mutableStateOf(false)

    sealed class ConnectionStatus(@StringRes val statusRes: Int) {
        data class CONNECTED(val gatt: BluetoothGatt) : ConnectionStatus(R.string.device_details_status_connected)
        data object CONNECTING : ConnectionStatus(R.string.device_details_status_connecting)
        data object DISCONNECTED : ConnectionStatus(R.string.device_details_status_disconnected)
        data object DISCONNECTING : ConnectionStatus(R.string.device_details_status_disconnecting)
    }

    data class ServiceData(
        val name: String?,
        val uuid: String,
        val characteristics: List<CharacteristicData>,
        val clues: CluesInfo? = null,
    )

    /** One SDP service-class UUID resolved through CLUES for display. */
    data class SdpServiceData(
        val uuid: String,
        val name: String?,
        val clues: CluesInfo? = null,
    )

    /**
     * [gatt] is null when this entry was reconstructed from a cached BTIDES enumeration (no live
     * connection). Read/Re-read controls only render when [gatt] is non-null.
     * [properties] mirrors [BluetoothGattCharacteristic.PROPERTY_*] flags so the readability
     * check works without a live characteristic handle.
     */
    data class CharacteristicData(
        val name: String?,
        val uuid: String,
        val value: String?,
        val valueHex: String?,
        val encodedValue: String?,
        val gatt: BluetoothGattCharacteristic?,
        val properties: Int,
        val clues: CluesInfo? = null,
    )

    /**
     * Subset of a CLUES entry used by the device-details UI: company + human-readable name
     * surface as always-visible subtitle lines, while purpose lives behind the expansion arrow.
     */
    data class CluesInfo(
        val company: String?,
        val name: String?,
        val purpose: String?,
    )

    data class AdRecord(
        val typeHex: String,
        val typeName: String?,
        val dataHex: String,
        val fields: List<ParseBleAdRecord.Field>,
    )

    private var currentLocation: LocationModel? = null

    init {
        viewModelScope.launch {
            observeLocation()
            loadDevice(address)
            observeOnlineStatus()
            refreshLocationHistory(address, autotunePeriod = true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Stop auto-reconnect when the screen goes away — otherwise we'd keep dialing the peer
        // even after the user has navigated back to the device list.
        userWantsConnected = false
        connectionJob?.cancel()
    }

    fun establishConnection() {
        userWantsConnected = true
        autoReconnectAttempts = 0
        beginConnectionAttempt()
    }

    private fun beginConnectionAttempt() {
        connectionJob?.cancel()
        // Pick the transport that matches what the user is investigating: BR/EDR-only or
        // dual-mode peers connect over Classic so we exercise GATT-over-BR/EDR (and any
        // associated pairing prompt) — Apple iPhones/iPads expose vendor-specific GATT
        // services on this transport that aren't visible on their LE side. Pure-LE devices
        // keep the legacy LE transport.
        val transport = when (deviceState?.transport) {
            Transport.BREDR, Transport.DUAL -> android.bluetooth.BluetoothDevice.TRANSPORT_BREDR
            else -> android.bluetooth.BluetoothDevice.TRANSPORT_LE
        }
        connectionJob = viewModelScope.launch {
            bleScannerHelper.connectToDevice(address, transport = transport)
                .onStart { connectionStatus = ConnectionStatus.CONNECTING }
                .catch { e ->
                    Timber.e(e)
                    connectionStatus = ConnectionStatus.DISCONNECTED
                }
                .collect { result ->
                    handleBleConnectResult(result)
                }
        }
    }

    /**
     * Schedule a reconnect attempt after a peer-initiated drop. Only fires when the user still
     * wants to be connected (hasn't tapped Disconnect / left the screen) and we haven't burned
     * through [MAX_AUTO_RECONNECT_ATTEMPTS]. Apple peers (iPhone, MacBook) routinely close
     * GATT links once the central has read everything they care about; the user expectation is
     * "stay connected until I say otherwise," so we re-initiate the link.
     */
    private fun maybeScheduleAutoReconnect() {
        if (!userWantsConnected) return
        if (autoReconnectAttempts >= MAX_AUTO_RECONNECT_ATTEMPTS) {
            Timber.tag(TAG).w("Auto-reconnect cap (%d) reached for %s; giving up", MAX_AUTO_RECONNECT_ATTEMPTS, address)
            return
        }
        autoReconnectAttempts++
        Timber.tag(TAG).i("Auto-reconnecting to %s (attempt %d/%d)", address, autoReconnectAttempts, MAX_AUTO_RECONNECT_ATTEMPTS)
        viewModelScope.launch {
            // Brief backoff so we don't hammer the peer's just-closed connection slot. Apple
            // devices in particular reject reconnects fired too quickly with status=133.
            kotlinx.coroutines.delay(AUTO_RECONNECT_DELAY_MS)
            if (userWantsConnected) beginConnectionAttempt()
        }
    }

    private fun handleBleConnectResult(result: BleScannerHelper.DeviceConnectResult) {
        when (result) {
            is BleScannerHelper.DeviceConnectResult.Connected -> {
                connectionStatus = ConnectionStatus.CONNECTED(result.gatt)
                // Reset the auto-reconnect counter once we're back in: subsequent peer drops
                // get a fresh budget of retries. Without this, three quick drops would
                // exhaust the cap and leave the user stuck.
                autoReconnectAttempts = 0
                discoverServices(result.gatt)
            }

            is BleScannerHelper.DeviceConnectResult.Connecting -> {
                connectionStatus = ConnectionStatus.CONNECTING
            }

            is BleScannerHelper.DeviceConnectResult.Disconnected -> {
                connectionStatus = ConnectionStatus.DISCONNECTED
                resetGattQueue()
                connectionJob?.cancel()
                maybeScheduleAutoReconnect()
            }

            is BleScannerHelper.DeviceConnectResult.Disconnecting -> {
                connectionStatus = ConnectionStatus.DISCONNECTING
            }

            is BleScannerHelper.DeviceConnectResult.DisconnectedWithError -> {
                Timber.e(RuntimeException("Error while connecting to device, error code ${result.errorCode}"))
                connectionStatus = ConnectionStatus.DISCONNECTED
                resetGattQueue()
                connectionJob?.cancel()
                maybeScheduleAutoReconnect()
            }

            // services update
            is BleScannerHelper.DeviceConnectResult.AvailableServices -> {
                addServices(result.services.map { mapService(it) }.toSet())
                enqueueAutoReads(result.gatt, result.services)
            }

            is BleScannerHelper.DeviceConnectResult.CharacteristicRead -> {
                val updatedServices = services.map { service ->
                    val updatedCharacteristics = service.characteristics.map { characteristic ->
                        if (characteristic.uuid == result.characteristic.uuid.toString()) {
                            mapCharacteristic(result.characteristic, result.valueEncoded64.fromBase64())
                        } else {
                            characteristic
                        }
                    }
                    service.copy(characteristics = updatedCharacteristics)
                }
                addServices(updatedServices.toSet())
                onGattOpCompleted()
            }

            is BleScannerHelper.DeviceConnectResult.FailedReadCharacteristic -> {
                val op = inFlightGattOp
                val wasManual = (op as? PendingGattOp.ReadCharacteristic)?.isManual == true
                onGattOpCompleted()
                if (wasManual) {
                    markManualReadFailed(result.characteristic.uuid.toString())
                }
            }

            is BleScannerHelper.DeviceConnectResult.DescriptorRead -> {
                // Match the characteristic that *owns* this descriptor — not every characteristic
                // that happens to have a descriptor with the same UUID. Multiple characteristics
                // commonly share the 0x2901 user-description UUID; using UUID equality leaked
                // one descriptor's value into every sibling.
                val ownerChar = result.descriptor.characteristic
                val updatedServices = services.map { service ->
                    val updatedCharacteristics = service.characteristics.map { characteristic ->
                        if (characteristic.gatt != null && characteristic.gatt === ownerChar) {
                            mapCharacteristic(characteristic.gatt, characteristic.encodedValue?.fromBase64(), result.valueEncoded64.fromBase64())
                        } else {
                            characteristic
                        }
                    }
                    service.copy(characteristics = updatedCharacteristics)
                }
                addServices(updatedServices.toSet())
                onGattOpCompleted()
            }

            is BleScannerHelper.DeviceConnectResult.FailedReadDescriptor -> {
                onGattOpCompleted()
            }
        }
    }

    private sealed interface PendingGattOp {
        val gatt: BluetoothGatt

        data class ReadCharacteristic(
            override val gatt: BluetoothGatt,
            val characteristic: BluetoothGattCharacteristic,
            val isManual: Boolean,
        ) : PendingGattOp

        data class ReadDescriptor(
            override val gatt: BluetoothGatt,
            val characteristic: BluetoothGattCharacteristic,
            val descriptorUuid: UUID,
        ) : PendingGattOp
    }

    private val pendingGattOps: ArrayDeque<PendingGattOp> = ArrayDeque()
    private var inFlightGattOp: PendingGattOp? = null

    private fun enqueueAutoReads(gatt: BluetoothGatt, services: List<BluetoothGattService>) {
        val descriptorUuid = UUID.fromString(DESCRIPTOR_CHARACTERISTIC_USER_DESCRIPTION)
        services.forEach { service ->
            service.characteristics.forEach { characteristic ->
                if (characteristic.descriptors.any { it.uuid == descriptorUuid }) {
                    pendingGattOps.add(PendingGattOp.ReadDescriptor(gatt, characteristic, descriptorUuid))
                }
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    pendingGattOps.add(PendingGattOp.ReadCharacteristic(gatt, characteristic, isManual = false))
                }
            }
        }
        pumpGattQueue()
    }

    private fun pumpGattQueue() {
        if (inFlightGattOp != null) return
        val next = pendingGattOps.removeFirstOrNull() ?: return
        inFlightGattOp = next
        viewModelScope.launch {
            try {
                when (next) {
                    is PendingGattOp.ReadCharacteristic -> bleScannerHelper.readCharacteristic(next.gatt, next.characteristic)
                    is PendingGattOp.ReadDescriptor -> bleScannerHelper.readDescriptor(next.gatt, next.characteristic, next.descriptorUuid)
                }
            } catch (e: Exception) {
                Timber.e(e)
                onGattOpCompleted()
            }
        }
    }

    private fun onGattOpCompleted() {
        inFlightGattOp = null
        pumpGattQueue()
    }

    private fun resetGattQueue() {
        pendingGattOps.clear()
        inFlightGattOp = null
    }

    private fun mapCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray? = null,
        description: ByteArray? = null
    ): CharacteristicData {
        val valueStr = value?.decodeToString()
        val valueHex = value?.toHexString()?.uppercase()?.let { "0x$it" }
        return CharacteristicData(
            name = description?.decodeToString() ?: getCharacteristicNameIfKnown(characteristic),
            uuid = characteristic.uuid.toString(),
            value = valueStr,
            valueHex = valueHex,
            encodedValue = value?.toBase64(),
            gatt = characteristic,
            properties = characteristic.properties,
            clues = lookupClues(characteristic.uuid.toString()),
        )
    }

    private fun mapService(service: BluetoothGattService): ServiceData {
        return ServiceData(
            name = getServiceNameIfKnown(service),
            uuid = service.uuid.toString(),
            characteristics = service.characteristics.map { mapCharacteristic(it) },
            clues = lookupClues(service.uuid.toString()),
        )
    }

    private fun lookupClues(uuid: String): CluesInfo? {
        // CLUES keys SIG UUIDs in short form ("180a"), Android exposes them in expanded base
        // form ("00001800-0000-1000-8000-00805f9b34fb") — try both.
        val entry = cluesRepository.lookup(uuid)
            ?: extract16BitUuid(uuid)?.let { cluesRepository.lookup(it) }
            ?: return null
        if (entry.company.isNullOrBlank() && entry.name.isNullOrBlank() && entry.purpose.isNullOrBlank()) return null
        return CluesInfo(
            company = entry.company?.takeIf { it.isNotBlank() },
            name = entry.name?.takeIf { it.isNotBlank() },
            purpose = entry.purpose?.takeIf { it.isNotBlank() },
        )
    }

    private fun getServiceNameIfKnown(service: BluetoothGattService): String? {
        return GetServiceNameFromBluetoothService.execute(service.uuid.toString())
    }

    private fun getCharacteristicNameIfKnown(characteristic: BluetoothGattCharacteristic): String? {
        return GetCharacteristicNameFromUUID.execute(characteristic.uuid.toString())
    }

    fun readCharacteristic(gattService: BluetoothGattCharacteristic) {
        val gatt = (connectionStatus as? ConnectionStatus.CONNECTED)?.gatt ?: return
        // Manual reads jump to the front of the queue — user explicitly tapped, they shouldn't
        // wait behind dozens of auto-reads (especially when some take 30s to time out).
        pendingGattOps.addFirst(PendingGattOp.ReadCharacteristic(gatt, gattService, isManual = true))
        pumpGattQueue()
    }

    /**
     * Manual-read entry point used by the UI when only the UUID string is known (e.g. on a
     * cached entry whose `gatt` handle was wiped on disconnect). Looks up the matching
     * BluetoothGattCharacteristic in the currently-connected gatt's service tree, returning
     * silently if the device is no longer connected.
     */
    fun readCharacteristicByUuid(serviceUuid: String, characteristicUuid: String) {
        val gatt = (connectionStatus as? ConnectionStatus.CONNECTED)?.gatt ?: return
        val svcUuid = runCatching { UUID.fromString(serviceUuid.normaliseToFullSigUuid()) }.getOrNull() ?: return
        val charUuid = runCatching { UUID.fromString(characteristicUuid.normaliseToFullSigUuid()) }.getOrNull() ?: return
        val char = gatt.services
            .firstOrNull { it.uuid == svcUuid }
            ?.getCharacteristic(charUuid)
            ?: return
        readCharacteristic(char)
    }

    /**
     * BTIDES stores SIG short UUIDs as 4-char hex; UUID.fromString needs the full SIG base form.
     * Pass through any non-4-char input unchanged (custom 128-bit UUIDs already match).
     */
    private fun String.normaliseToFullSigUuid(): String {
        return if (length == 4 && all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            "0000${lowercase()}-0000-1000-8000-00805f9b34fb"
        } else this
    }

    private fun markManualReadFailed(uuid: String) {
        readFailureJobs[uuid]?.cancel()
        recentReadFailures = recentReadFailures + uuid
        readFailureJobs[uuid] = viewModelScope.launch {
            delay(READ_FAILED_DISPLAY_MS)
            recentReadFailures = recentReadFailures - uuid
            readFailureJobs.remove(uuid)
        }
    }

    fun discoverServices(gatt: BluetoothGatt) {
        viewModelScope.launch {
            try {
                bleScannerHelper.discoverServices(gatt)
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    fun disconnect(gatt: BluetoothGatt) {
        // Mark this as user-initiated so the Disconnected callback doesn't trigger an
        // auto-reconnect — otherwise we'd immediately reconnect to the device the user just
        // told us to drop.
        userWantsConnected = false
        autoReconnectAttempts = 0
        viewModelScope.launch {
            try {
                bleScannerHelper.disconnect(gatt)
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    private fun loadRawData(raw: ByteArray) {
        val frames = getBleRecordFramesFromRawInteractor.execute(raw)
        rawData = frames.map {
            AdRecord(
                typeHex = it.type.toHexString().uppercase(),
                typeName = GetBleAdTypeName.execute(it.type),
                dataHex = it.data.toHexString().uppercase(),
                fields = ParseBleAdRecord.execute(it.type, it.data),
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeOnlineStatus() {
        viewModelScope.launch {
            BgScanService.observeIsActive()
                .flatMapLatest { isActive ->
                    if (isActive) {
                        devicesRepository.observeLastBatch()
                    } else {
                        flowOf(emptyList())
                    }
                }
                .map { devices ->
                    val currentDevice = devices.firstOrNull { it.address == address }
                    val rssi = currentDevice?.rssi
                    val distance = currentDevice?.distance()
                    if (rssi != null && distance != null) {
                        deviceState = currentDevice
                        OnlineStatus(rssi, distance)
                    } else if (connectionStatus !is ConnectionStatus.DISCONNECTED) {
                        OnlineStatus(null, null)
                    } else {
                        null
                    }
                }
                .collect { onlineStatus ->
                    onlineStatusData = onlineStatus
                }
        }
    }

    private suspend fun loadDevice(address: String) {
        val device = devicesRepository.getDeviceByAddress(address)
        if (device == null) {
            back()
        } else {
            device.rowDataEncoded?.fromBase64()?.let { loadRawData(it) }
            // Resolve the SIG / CLUES name on the advertised entries too — addServices merges
            // by canonical UUID key so the cached version (with characteristics) usually wins,
            // but if a peer advertised a UUID and never got enumerated the user still sees its
            // name instead of "Unknown".
            addServices(device.servicesUuids.map { uuid ->
                ServiceData(
                    name = GetServiceNameFromBluetoothService.execute(uuid),
                    uuid = uuid,
                    characteristics = emptyList(),
                    clues = lookupClues(uuid),
                )
            }.toSet())
            deviceState = device
            // Render whatever SDP UUIDs we already have for this device. Auto-fire a fresh fetch
            // for CLASSIC/DUAL devices that don't have any yet — the user expects to see
            // services within a few seconds of opening the screen.
            sdpServices = device.sdpUuids.map { resolveSdpService(it) }
            if (device.sdpUuids.isEmpty() && device.transport.isBrEdrOrDual()) {
                fetchSdpServices()
            }
            // Layer the previously-captured GATT data (services + characteristics + values) on
            // top of the advertised UUIDs. addServices merges by canonical UUID with the new
            // entry winning, so any cached service supersedes its bare advertised counterpart
            // and exposes the full characteristic tree even before the user reconnects.
            loadCachedGatt(address)
        }
    }

    private fun resolveSdpService(uuid: String): SdpServiceData {
        val canonical = uuid.lowercase()
        // Prefer the SDP-specific Bluetooth SIG service-class table for naming, since BR/EDR
        // service classes (Audio Source/Sink, HID, OBEX, etc.) are NOT in the GATT service
        // assigned-numbers list that GetServiceNameFromBluetoothService consults. Fall back to
        // the GATT table only when the SDP table doesn't know the UUID.
        val name = SdpServiceClassNames.lookup(canonical)
            ?: GetServiceNameFromBluetoothService.execute(canonical)
        // CLUES community data takes precedence for the Purpose field when present. When CLUES
        // doesn't carry a purpose (or doesn't know the UUID at all), fall back to the SIG-spec
        // profile summary so SDP rows for standard service classes (HID, A2DP, HFP, …) still
        // get an expandable Purpose section. The UI's expand arrow keys off `clues.purpose`
        // being non-null, so populating it from the spec table makes those rows expandable.
        val cluesEntry = lookupClues(canonical)
        val mergedClues = when {
            cluesEntry?.purpose != null -> cluesEntry
            else -> {
                val specPurpose = SdpServiceClassNames.lookupPurpose(canonical)
                if (specPurpose == null) cluesEntry
                else CluesInfo(
                    company = cluesEntry?.company,
                    name = cluesEntry?.name,
                    purpose = specPurpose,
                )
            }
        }
        return SdpServiceData(
            uuid = canonical,
            name = name,
            clues = mergedClues,
        )
    }

    private fun Transport.isBrEdrOrDual(): Boolean = this == Transport.BREDR || this == Transport.DUAL

    /**
     * Run an SDP enumeration against the device's address. Persists results to the DB, fires a
     * BTIDES SDPArray record (synthesized 0x07_SDP_SERVICE_SEARCH_ATTR_RSP), and refreshes the
     * UI list. Idempotent: a second call while one is in flight cancels the in-flight job and
     * re-runs.
     */
    fun fetchSdpServices() {
        sdpFetchJob?.cancel()
        sdpFetchJob = viewModelScope.launch {
            sdpFetchInProgress = true
            try {
                val timestampMs = System.currentTimeMillis()
                val uuids = sdpEnumerationHelper.enumerate(address)
                val canonical = uuids.map { it.toString().lowercase() }
                sdpServices = canonical.map { resolveSdpService(it) }
                sdpLastFetchTimeMs = timestampMs
                devicesRepository.updateSdpUuids(address, canonical)
                if (uuids.isNotEmpty()) {
                    btidesRepository.appendSDPDiscovery(address, uuids, timestampMs)
                }
            } catch (e: BleScannerHelper.BluetoothIsNotInitialized) {
                Timber.w(e, "Bluetooth disabled; cannot enumerate SDP for $address")
            } catch (e: Throwable) {
                Timber.e(e, "SDP enumeration failed for $address")
            } finally {
                sdpFetchInProgress = false
            }
        }
    }

    private suspend fun loadCachedGatt(address: String) {
        val cached = btidesRepository.cachedGattForDevice(address) ?: return
        val parsed = parseCachedServices(cached) ?: return
        if (parsed.isNotEmpty()) addServices(parsed)
    }

    /**
     * Convert a BTIDES device JsonObject (as produced by [BTIDESRepository.cachedGattForDevice])
     * into [ServiceData] entries with characteristics populated. Each characteristic's `gatt`
     * field is null because no live BluetoothGattCharacteristic backs it; the UI suppresses the
     * Read/Re-read controls in that case.
     */
    private fun parseCachedServices(deviceObj: kotlinx.serialization.json.JsonObject): Set<ServiceData>? {
        val gattArray = deviceObj["GATTArray"]?.let { it as? kotlinx.serialization.json.JsonArray } ?: return null
        val out = LinkedHashSet<ServiceData>()
        for (svcEl in gattArray) {
            val svc = svcEl as? kotlinx.serialization.json.JsonObject ?: continue
            val uuid = svc["UUID"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.contentOrNull ?: continue
            val chars = mutableListOf<CharacteristicData>()
            val charsArr = svc["characteristics"]?.let { it as? kotlinx.serialization.json.JsonArray }
            charsArr?.forEach { cEl ->
                val c = cEl as? kotlinx.serialization.json.JsonObject ?: return@forEach
                val charUuid = c["value_uuid"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.contentOrNull
                    ?: return@forEach
                val properties = c["properties"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.intOrNull ?: 0
                val ioArray = c["char_value"]?.let { it as? kotlinx.serialization.json.JsonObject }
                    ?.get("io_array")
                    ?.let { it as? kotlinx.serialization.json.JsonArray }
                // Pick the most recent ATT_READ_RSP value, if any, as the displayed characteristic value.
                val latestHex = ioArray
                    ?.lastOrNull { (it as? kotlinx.serialization.json.JsonObject)?.get("io_type_str")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.contentOrNull?.contains("READ", ignoreCase = true) == true }
                    ?.let { it as? kotlinx.serialization.json.JsonObject }
                    ?.get("value_hex_str")
                    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.contentOrNull
                val valueBytes = latestHex?.takeIf { it.isNotEmpty() }?.let { hexToBytes(it) }
                chars += CharacteristicData(
                    name = GetCharacteristicNameFromUUID.execute(charUuid),
                    uuid = charUuid,
                    value = valueBytes?.decodeToString(),
                    valueHex = valueBytes?.toHexString()?.uppercase()?.let { "0x$it" },
                    encodedValue = valueBytes?.toBase64(),
                    gatt = null,
                    properties = properties,
                    clues = lookupClues(charUuid),
                )
            }
            out += ServiceData(
                name = GetServiceNameFromBluetoothService.execute(uuid),
                uuid = uuid,
                characteristics = chars,
                clues = lookupClues(uuid),
            )
        }
        return out
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X")
        val n = clean.length / 2
        val out = ByteArray(n)
        for (i in 0 until n) {
            val s = clean.substring(i * 2, i * 2 + 2)
            out[i] = s.toInt(16).toByte()
        }
        return out
    }

    private fun addServices(servicesUuids: Set<ServiceData>) {
        // Normalise the merge key so an advertised SIG UUID like "0000180a-0000-1000-8000-..."
        // collapses to the same bucket as the cached BTIDES form "180A". Without this both
        // entries survived the merge and the user saw two rows ("Unknown" advertised, plus the
        // properly-named cached one). Live entries (added later, e.g. via AvailableServices)
        // win the merge by being the last one keyed for a given canonical UUID.
        services = (services + servicesUuids).associateBy { canonicalUuidKey(it.uuid) }.values.toSet()
    }

    private fun canonicalUuidKey(uuid: String): String {
        val short = extract16BitUuid(uuid)
            ?: uuid.takeIf { it.length == 4 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
        return short?.uppercase() ?: uuid.lowercase()
    }

    private fun observeLocation() {
        permissionHelper.checkOrRequestPermission {
            viewModelScope.launch {
                locationProvider.fetchOnce()
                locationProvider.observeLocation()
                    .take(2)
                    .collect { location ->
                        currentLocation = location?.location?.toDomain(System.currentTimeMillis())
                        updateCameraPosition(pointsState, currentLocation)
                    }
            }
        }
    }

    private suspend fun refreshLocationHistory(address: String, autotunePeriod: Boolean) {
        val fromTime = System.currentTimeMillis() - historyPeriod.periodMills
        // Use the RSSI-aware query so the heatmap and best-fit marker have signal-strength
        // data. Markers/path code only needs lat/lng/time, so we project the rows down to
        // LocationModel for [pointsState] and keep RSSI in a sidecar map keyed by time.
        val fetchedRows = locationRepository.getRssiLocationsByAddress(address, fromTime = fromTime)
        val nextStep = historyPeriod.next()

        val shouldStepNext = autotunePeriod && fetchedRows.isEmpty() && nextStep != null

        if (shouldStepNext) {
            selectHistoryPeriodSelected(nextStep, address, autotunePeriod)
        }

        if (fetchedRows.size > MAX_POINTS_FOR_MARKERS) {
            pointsStyle = PointsStyle.PATH
        }

        if (fetchedRows.size > MAX_POINTS_FOR_HEATMAP) {
            useHeatmap = false
        }

        pointsState = fetchedRows.map { LocationModel(lat = it.lat, lng = it.lng, time = it.time) }
        rssiByTime = fetchedRows.associate { it.time to it.rssi }
        bestFitLocation = computeBestFitLocation(fetchedRows)
        updateCameraPosition(pointsState, currentLocation)
    }

    /**
     * Weighted centroid of [rows], weighting by linear power (10^(rssi/10)) so a -45 dBm
     * sample carries ~5600× the influence of a -82 dBm one. This is the standard simple
     * approach for RSSI-based positioning when transmit power is unknown — it doesn't
     * estimate distances, just biases the centroid toward strong-signal samples.
     *
     * Returns null if no rows have RSSI (older data only) or if the result would be
     * indistinguishable from the input cloud (≤ 1 m apart from every input point — no
     * meaningful "best fit" to extract). The threshold rules out rendering a redundant
     * marker on top of a single visited spot.
     */
    private fun computeBestFitLocation(rows: List<f.cking.software.data.database.dao.RssiLocationRow>): LocationModel? {
        val withRssi = rows.filter { it.rssi != null }
        if (withRssi.isEmpty()) return null

        var sumW = 0.0
        var sumLat = 0.0
        var sumLng = 0.0
        for (r in withRssi) {
            // Power weight: 10^(rssi/10). Strong signal → big weight. Mathematically the
            // linear-scale equivalent of the RSSI dBm reading.
            val w = Math.pow(10.0, (r.rssi ?: continue) / 10.0)
            sumW += w
            sumLat += w * r.lat
            sumLng += w * r.lng
        }
        if (sumW <= 0.0) return null
        val estLat = sumLat / sumW
        val estLng = sumLng / sumW
        val tMs = withRssi.maxOf { it.time }

        // Reject when the cloud is essentially a single point — the centroid would land on
        // top of every sample and clutter the map.
        val minDistanceMetersFromAny = withRssi.minOf {
            LocationModel(lat = it.lat, lng = it.lng, time = it.time)
                .distanceTo(LocationModel(lat = estLat, lng = estLng, time = tMs))
                .toDouble()
        }
        if (withRssi.size < 2 && minDistanceMetersFromAny < 1.0) return null

        return LocationModel(lat = estLat, lng = estLng, time = tMs)
    }

    private fun updateCameraPosition(points: List<LocationModel>, currentLocation: LocationModel?) {
        val previousState: MapCameraState = cameraState
        val withAnimation = previousState != DEFAULT_MAP_CAMERA_STATE
        val newState = if (points.isNotEmpty()) {
            MapCameraState.MultiplePoints(points, withAnimation = withAnimation)
        } else if (currentLocation != null) {
            MapCameraState.SinglePoint(location = currentLocation, zoom = MapConfig.DEFAULT_MAP_ZOOM, withAnimation = withAnimation)
        } else {
            DEFAULT_MAP_CAMERA_STATE.copy(withAnimation = withAnimation)
        }
        if (newState != previousState) {
            cameraState = newState
        }
    }

    fun selectHistoryPeriodSelected(
        newHistoryPeriod: HistoryPeriod,
        address: String,
        autotunePeriod: Boolean
    ) {
        viewModelScope.launch {
            historyPeriod = newHistoryPeriod
            refreshLocationHistory(address, autotunePeriod = autotunePeriod)
        }
    }

    fun back() {
        router.navigate(BackCommand)
    }

    enum class HistoryPeriod(
        val periodMills: Long,
        @StringRes val displayNameRes: Int,
    ) {

        DAY(HISTORY_PERIOD_DAY, displayNameRes = R.string.device_details_day),
        WEEK(HISTORY_PERIOD_WEEK, displayNameRes = R.string.device_details_week),
        MONTH(HISTORY_PERIOD_MONTH, displayNameRes = R.string.device_details_month),
        ALL(HISTORY_PERIOD_LONG, displayNameRes = R.string.device_details_all_time);

        fun next(): HistoryPeriod? {
            return HistoryPeriod.values().getOrNull(ordinal + 1)
        }

        fun previous(): HistoryPeriod? {
            return HistoryPeriod.values().getOrNull(ordinal - 1)
        }
    }

    enum class PointsStyle(@StringRes val displayNameRes: Int) {
        MARKERS(R.string.device_history_pint_style_markers),
        PATH(R.string.device_history_pint_style_path),
        HIDE_MARKERS(R.string.device_history_pint_style_hide_markers),
    }

    sealed interface MapCameraState {
        data class SinglePoint(
            val location: LocationModel,
            val zoom: Double,
            val withAnimation: Boolean,
        ) : MapCameraState

        data class MultiplePoints(
            val points: List<LocationModel>,
            val withAnimation: Boolean,
        ) : MapCameraState
    }

    data class OnlineStatus(
        val signalStrength: Int?,
        val distance: Float?,
    )

    companion object {
        private const val TAG = "DeviceDetailsVM"
        private const val DESCRIPTOR_CHARACTERISTIC_USER_DESCRIPTION = "00002901-0000-1000-8000-00805f9b34fb"
        private const val READ_FAILED_DISPLAY_MS = 2_000L
        // How many times to auto-reconnect after a peer-initiated drop before giving up. Apple
        // peers typically allow 1-2 quick reconnects before the system rate-limits.
        private const val MAX_AUTO_RECONNECT_ATTEMPTS = 3
        // Pause between disconnect and the next connectGatt attempt. Some Apple peers reject
        // immediate reconnects with status=133 (GATT_ERROR), but a sub-second backoff is enough
        // for the host's GATT client to release the resource.
        private const val AUTO_RECONNECT_DELAY_MS = 750L
        private const val MAX_POINTS_FOR_MARKERS = 5_000
        private const val MAX_POINTS_FOR_HEATMAP = 30_000
        private const val HISTORY_PERIOD_DAY = 24 * 60 * 60 * 1000L // 24 hours
        private const val HISTORY_PERIOD_WEEK = 7 * 24 * 60 * 60 * 1000L // 1 week
        private const val HISTORY_PERIOD_MONTH = 31 * 24 * 60 * 60 * 1000L // 1 month
        private const val HISTORY_PERIOD_LONG = Long.MAX_VALUE
        private val DEFAULT_HISTORY_PERIOD = HistoryPeriod.DAY
        private val ONLINE_THRESHOLD_MS =
            PowerModeHelper.PowerMode.POWER_SAVING.scanDuration + PowerModeHelper.PowerMode.POWER_SAVING.scanDuration + 3000L
        private val DEFAULT_POINTS_STYLE = PointsStyle.MARKERS

        private val DEFAULT_MAP_CAMERA_STATE = MapCameraState.SinglePoint(
            location = LocationModel(0.0, 0.0, 0),
            zoom = MapConfig.MIN_MAP_ZOOM,
            withAnimation = false
        )
    }
}