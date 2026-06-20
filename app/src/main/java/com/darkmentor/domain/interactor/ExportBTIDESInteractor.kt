package com.darkmentor.domain.interactor

import android.net.Uri
import com.darkmentor.data.btides.BTIDESRepository
import com.darkmentor.data.btides.StrongestRssiLocation
import com.darkmentor.data.repo.LocationRepository
import com.darkmentor.data.repo.SettingsRepository
import java.io.File

class ExportBTIDESInteractor(
    private val btidesRepository: BTIDESRepository,
    private val locationRepository: LocationRepository,
    private val settingsRepository: SettingsRepository,
) {

    /** Per-device "strongest sample" lookup, passed into the export flow. */
    private val strongestRssiLookup: suspend (String) -> StrongestRssiLocation? = { address ->
        locationRepository.getStrongestRssiLocation(address)?.let {
            StrongestRssiLocation(lat = it.lat, lng = it.lng, rssi = it.rssi, timeMs = it.time)
        }
    }

    /**
     * Write the merged BTIDES JSON array (active log only) to the user-selected SAF Uri.
     * Returns the number of unique BDADDRs included.
     */
    suspend fun execute(
        uri: Uri,
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): Int = btidesRepository.exportTo(uri, strongestRssiLookup, onProgress)

    /**
     * Write each BTIDES log (active + every rotated archive) to its own file under the app's
     * external files dir for ADB pull. Each file is named after the underlying log's
     * timestamped basename, with the `.btides` extension. Returns one entry per file written.
     */
    suspend fun execute(
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): List<ExternalExport> = btidesRepository.exportAllToExternalFilesDir(strongestRssiLookup, onProgress)
        .map { ExternalExport(it.file, it.deviceCount, it.isActive) }

    /**
     * Export a single specific log file to the given OutputStream-backed target. Used by the
     * BTIDALPOOL upload pipeline so it can produce a merged BTIDES file from one log at a
     * time (active OR a specific archive).
     */
    suspend fun executeForLog(
        logFile: File,
        target: File,
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): Int {
        target.parentFile?.mkdirs()
        // Upload path only: honor the user's GPS exclusion zones. The other export paths
        // (manual SAF export, external-dir dump) intentionally stay complete.
        val exclusionZones = settingsRepository.getExclusionZones()
        return target.outputStream().use {
            btidesRepository.exportTo(
                it,
                strongestRssiLookup,
                onProgress,
                sourceFile = logFile,
                exclusionZones = exclusionZones,
            )
        }
    }

    data class ExternalExport(val file: File, val deviceCount: Int, val isActive: Boolean)
}
