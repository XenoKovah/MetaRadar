package com.darkmentor.domain.interactor

import android.net.Uri
import com.darkmentor.data.btidalpool.BtidalpoolGpsExclusionPolicy
import com.darkmentor.data.btides.BTIDESRepository
import com.darkmentor.data.btides.StrongestRssiLocation
import com.darkmentor.data.repo.LocationRepository
import com.darkmentor.data.repo.SettingsRepository
import java.io.File
import java.security.MessageDigest

class ExportBTIDESInteractor(
    private val btidesRepository: BTIDESRepository,
    private val locationRepository: LocationRepository,
    private val settingsRepository: SettingsRepository,
) {

    private data class EnrichmentLookups(
        val strongest: suspend (String) -> StrongestRssiLocation?,
        val exclusionCoordinates: suspend (String) -> List<Pair<Double, Double>>,
    )

    /**
     * Load all enrichment rows up front. With exclusion zones disabled this is one Room query;
     * with zones enabled it is still one query because the full location rows also determine the
     * strongest sample. The export loop itself performs only map lookups.
     */
    private suspend fun enrichmentLookups(includeAllCoordinates: Boolean): EnrichmentLookups {
        if (includeAllCoordinates) {
            val allRows = locationRepository.getAllRssiLocationsByAddress()
            val strongest = allRows.mapValues { (_, rows) ->
                rows.asSequence()
                    .filter { it.rssi != null }
                    .maxWithOrNull(compareBy<com.darkmentor.data.database.dao.RssiLocationRow> {
                        it.rssi
                    }.thenBy { it.time })
                    ?.toExportLocation()
            }
            return EnrichmentLookups(
                strongest = { address -> strongest[address.uppercase()] },
                exclusionCoordinates = { address ->
                    allRows[address.uppercase()].orEmpty().map { it.lat to it.lng }
                },
            )
        }
        val strongest = locationRepository.getAllStrongestRssiLocations()
            .mapValues { (_, row) -> row.toExportLocation() }
        return EnrichmentLookups(
            strongest = { address -> strongest[address.uppercase()] },
            exclusionCoordinates = { emptyList() },
        )
    }

    private fun com.darkmentor.data.database.dao.RssiLocationRow.toExportLocation() =
        StrongestRssiLocation(lat = lat, lng = lng, rssi = rssi, timeMs = time)

    /**
     * Write the merged BTIDES JSON array (active log only) to the user-selected SAF Uri.
     * Returns the number of unique BDADDRs included.
     */
    suspend fun execute(
        uri: Uri,
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): Int {
        val lookups = enrichmentLookups(includeAllCoordinates = false)
        return btidesRepository.exportTo(uri, lookups.strongest, onProgress)
    }

    /**
     * Write each BTIDES log (active + every rotated archive) to its own file under the app's
     * external files dir for ADB pull. Each file is named after the underlying log's
     * timestamped basename, with the `.btides` extension. Returns one entry per file written.
     */
    suspend fun execute(
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): List<ExternalExport> {
        val lookups = enrichmentLookups(includeAllCoordinates = false)
        return btidesRepository.exportAllToExternalFilesDir(lookups.strongest, onProgress)
            .map { ExternalExport(it.file, it.deviceCount, it.isActive) }
    }

    /**
     * Durable-upload export. Produces compact standalone BTIDES arrays bounded by the server's
     * raw JSON limit, then hashes the exact bytes that will be sent for outbox identity/dedup.
     */
    suspend fun executeUploadChunks(
        logFile: File,
        outputDir: File,
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): List<UploadChunk> {
        val exclusionZones = settingsRepository.getExclusionZones()
        val privacyPolicyFingerprint =
            BtidalpoolGpsExclusionPolicy.fingerprint(exclusionZones)
        val lookups = enrichmentLookups(includeAllCoordinates = exclusionZones.isNotEmpty())
        return btidesRepository.exportUploadChunks(
            outputDir = outputDir,
            strongestRssiLookup = lookups.strongest,
            onProgress = onProgress,
            sourceFile = logFile,
            exclusionZones = exclusionZones,
            exclusionCoordsLookup = lookups.exclusionCoordinates,
        ).map { chunk ->
            UploadChunk(
                file = chunk.file,
                index = chunk.index,
                deviceCount = chunk.deviceCount,
                sha256 = sha256(chunk.file),
                privacyPolicyFingerprint = privacyPolicyFingerprint,
            )
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    data class UploadChunk(
        val file: File,
        val index: Int,
        val deviceCount: Int,
        val sha256: String,
        val privacyPolicyFingerprint: String,
    )

    data class ExternalExport(val file: File, val deviceCount: Int, val isActive: Boolean)
}
