package f.cking.software.domain.interactor

import android.net.Uri
import f.cking.software.data.btides.BTIDESRepository
import f.cking.software.data.btides.StrongestRssiLocation
import f.cking.software.data.repo.LocationRepository
import java.io.File

class ExportBTIDESInteractor(
    private val btidesRepository: BTIDESRepository,
    private val locationRepository: LocationRepository,
) {

    /** Per-device "strongest sample" lookup, passed into the export flow. */
    private val strongestRssiLookup: suspend (String) -> StrongestRssiLocation? = { address ->
        locationRepository.getStrongestRssiLocation(address)?.let {
            StrongestRssiLocation(lat = it.lat, lng = it.lng, rssi = it.rssi, timeMs = it.time)
        }
    }

    /**
     * Write the merged BTIDES JSON array to the user-selected SAF Uri.
     * Returns the number of unique BDADDRs included.
     */
    suspend fun execute(
        uri: Uri,
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): Int = btidesRepository.exportTo(uri, strongestRssiLookup, onProgress)

    /**
     * Write the merged BTIDES JSON array to the app's external files dir for ADB pull.
     */
    suspend fun execute(
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): ExternalExport = btidesRepository.exportToExternalFilesDir(strongestRssiLookup, onProgress)
        .let { (file, count) -> ExternalExport(file, count) }

    data class ExternalExport(val file: File, val deviceCount: Int)
}
