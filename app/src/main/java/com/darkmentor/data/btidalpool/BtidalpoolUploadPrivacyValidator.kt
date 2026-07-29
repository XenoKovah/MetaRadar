package com.darkmentor.data.btidalpool

import com.darkmentor.data.repo.LocationRepository
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.domain.model.ExclusionZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * Fail-closed check immediately before a durable payload enters the network client.
 *
 * Export-time filtering remains the efficient primary path. This validator is defense in depth
 * for payloads that wait in the outbox while zones or location history change.
 */
class BtidalpoolUploadPrivacyValidator(
    private val settingsRepository: SettingsRepository,
    private val locationRepository: LocationRepository,
) {
    sealed interface Validation {
        data object Safe : Validation
        data class Blocked(val reason: String) : Validation
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun currentPolicyFingerprint(): String =
        BtidalpoolGpsExclusionPolicy.fingerprint(settingsRepository.getExclusionZones())

    suspend fun validate(
        payload: File,
        queuedPolicyFingerprint: String?,
    ): Validation = withContext(Dispatchers.IO) {
        try {
            val zones = settingsRepository.getExclusionZones()
            val currentFingerprint = BtidalpoolGpsExclusionPolicy.fingerprint(zones)
            if (queuedPolicyFingerprint != currentFingerprint) {
                return@withContext Validation.Blocked(
                    "GPS exclusion zones changed after this payload was queued",
                )
            }
            if (zones.isEmpty()) return@withContext Validation.Safe
            if (!payload.isFile) {
                return@withContext Validation.Blocked("Queued BTIDALPOOL payload is missing")
            }

            val devices = json.parseToJsonElement(payload.readText()).jsonArray
            val addresses = linkedSetOf<String>()
            var embeddedCoordinateLeak = false
            devices.forEach { element ->
                val device = element.jsonObject
                device["bdaddr"]?.jsonPrimitive?.content?.let { addresses += it.uppercase() }
                device["GPSArray"]?.jsonArray.orEmpty().forEach { gps ->
                    val coordinate = gps.jsonObject
                    val lat = coordinate["lat"]?.jsonPrimitive?.doubleOrNull
                    val lng = coordinate["lon"]?.jsonPrimitive?.doubleOrNull
                    if (lat != null && lng != null && zones.any { it.contains(lat, lng) }) {
                        embeddedCoordinateLeak = true
                    }
                }
            }
            if (embeddedCoordinateLeak) {
                return@withContext Validation.Blocked(
                    "Queued payload contains a GPS coordinate inside an exclusion zone",
                )
            }

            val history = locationRepository.getAllRssiLocationsByAddress()
            val historicalLeak = addresses.any { address ->
                history[address].orEmpty().any { row ->
                    zones.any { zone -> zone.contains(row.lat, row.lng) }
                }
            }
            if (historicalLeak) {
                return@withContext Validation.Blocked(
                    "A queued device now has location history inside an exclusion zone",
                )
            }

            val finalFingerprint = currentPolicyFingerprint()
            if (finalFingerprint != currentFingerprint) {
                return@withContext Validation.Blocked(
                    "GPS exclusion zones changed while validating this payload",
                )
            }
            Validation.Safe
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Validation.Blocked(
                "Could not verify GPS exclusions; upload blocked: " +
                    (t.message ?: t::class.java.simpleName),
            )
        }
    }
}

internal object BtidalpoolGpsExclusionPolicy {
    fun fingerprint(zones: List<ExclusionZone>): String {
        val canonical = zones.map { zone ->
            when (zone) {
                is ExclusionZone.Circle -> listOf(
                    "circle",
                    zone.centerLat.toRawBits().toString(16),
                    zone.centerLng.toRawBits().toString(16),
                    zone.radiusMeters.toRawBits().toString(16),
                ).joinToString(":")
                is ExclusionZone.Square -> listOf(
                    "square",
                    zone.centerLat.toRawBits().toString(16),
                    zone.centerLng.toRawBits().toString(16),
                    zone.halfSizeMeters.toRawBits().toString(16),
                ).joinToString(":")
            }
        }.sorted().joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
