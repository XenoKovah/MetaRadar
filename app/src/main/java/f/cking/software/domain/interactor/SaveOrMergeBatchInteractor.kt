package f.cking.software.domain.interactor

import f.cking.software.data.helpers.LocationProvider
import f.cking.software.data.repo.DevicesRepository
import f.cking.software.data.repo.LocationRepository
import f.cking.software.domain.model.AppleAirDrop
import f.cking.software.domain.model.BleScanDevice
import f.cking.software.domain.model.ManufacturerInfo
import f.cking.software.domain.model.SavedDeviceHandle
import f.cking.software.domain.toDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SaveOrMergeBatchInteractor(
    private val devicesRepository: DevicesRepository,
    private val locationRepository: LocationRepository,
    private val buildDeviceFromScanDataInteractor: BuildDeviceFromScanDataInteractor,
    private val locationProvider: LocationProvider,
    private val isKnownDeviceInteractor: IsKnownDeviceInteractor,
) {

    suspend fun execute(batch: List<BleScanDevice>): Result {
        return withContext(Dispatchers.Default) {
            val discoveredDevices = batch.map { buildDeviceFromScanDataInteractor.execute(it) }
            val existingDevices = devicesRepository.getAllByAddresses(discoveredDevices.map { it.address }).associateBy { it.address }
            val airdropContactToPreviouslySeenAtTime = mutableMapOf<Int, Long>()

            // Collect every airdrop SHA in the batch and resolve them in a single SQL query
            // (the DAO's getAllBySHA already takes a list and is chunked by splitToBatches).
            // Previously every device with airdrop info issued its own SELECT; at N=500 that
            // was 500 round-trips per scan tick — multi-second on a 200k-row contacts table.
            val allShasInBatch = discoveredDevices.flatMap { device ->
                device.manufacturerInfo?.airdrop?.contacts?.map { it.sha256 } ?: emptyList()
            }.distinct()
            val existingContactsBySha = if (allShasInBatch.isEmpty()) {
                emptyMap()
            } else {
                devicesRepository.getAllBySHA(allShasInBatch).associateBy { it.sha256 }
            }

            val mergedDevices = discoveredDevices.map { newDiscovered ->
                val existing = existingDevices[newDiscovered.address]
                val mergedDeviceData = existing?.mergeWithNewDetected(newDiscovered) ?: newDiscovered
                val airdropMergeResult = mergeAirdropContactsWithExisting(mergedDeviceData.manufacturerInfo, existingContactsBySha)
                airdropContactToPreviouslySeenAtTime.putAll(airdropMergeResult.airdropContactToPreviouslySeenAtTime)

                mergedDeviceData.copy(manufacturerInfo = airdropMergeResult.updatedManufacturerInfo)
            }

            devicesRepository.saveScanBatch(mergedDevices)

            val savedBatch = mergedDevices.map { mergedDevice ->
                SavedDeviceHandle(
                    previouslySeenAtTime = existingDevices[mergedDevice.address]?.lastDetectTimeMs ?: mergedDevice.lastDetectTimeMs,
                    device = mergedDevice,
                    airdrop = airdropContactToPreviouslySeenAtTime
                        .takeIf { it.isNotEmpty() }
                        ?.let { SavedDeviceHandle.AirdropHandle(it) }
                )
            }

            val location = locationProvider.getFreshLocation()

            val detectTime = batch.firstOrNull()?.scanTimeMs
            if (location != null && detectTime != null) {
                // Per-detection RSSI: if the same address appears multiple times in the same
                // batch (rare but possible), keep the strongest sample — that's what the
                // trilateration weighted-centroid (best-fit marker) wants.
                val rssiByAddress: Map<String, Int?> = batch
                    .groupBy { it.address }
                    .mapValues { (_, samples) -> samples.mapNotNull { it.rssi }.maxOrNull() }
                locationRepository.saveLocation(location.toDomain(detectTime), rssiByAddress)
            }

            val knownDevicesCount = mergedDevices.count(isKnownDeviceInteractor::execute)
            Result(
                knownDevicesCount = knownDevicesCount,
                savedBatch = savedBatch
            )
        }
    }

    private fun mergeAirdropContactsWithExisting(
        found: ManufacturerInfo?,
        existingContactsBySha: Map<Int, AppleAirDrop.AppleContact>,
    ): AirdropContactsMergeResult {
        val airdrop = found?.airdrop ?: return AirdropContactsMergeResult(found, emptyMap())

        val airdropContactToPreviouslySeenAtTime = mutableMapOf<Int, Long>()
        val mergedContacts = airdrop.contacts.map { contact ->
            val existing = existingContactsBySha[contact.sha256]
            if (existing != null) {
                airdropContactToPreviouslySeenAtTime[existing.sha256] = existing.lastDetectionTimeMs
            }
            existing?.mergeWithNewContact(contact) ?: contact
        }
        return AirdropContactsMergeResult(
            found.copy(airdrop = AppleAirDrop(mergedContacts)),
            airdropContactToPreviouslySeenAtTime,
        )
    }

    private data class AirdropContactsMergeResult(
        val updatedManufacturerInfo: ManufacturerInfo?,
        val airdropContactToPreviouslySeenAtTime: Map<Int, Long>,
    )

    data class Result(
        val knownDevicesCount: Int,
        val savedBatch: List<SavedDeviceHandle>,
    )

    companion object {
        private const val TAG = "SaveOrMergeBatchInteractor"
    }
}