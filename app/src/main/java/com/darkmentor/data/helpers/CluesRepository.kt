package com.darkmentor.data.helpers

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * In-memory index of the bundled CLUES_Schema/CLUES_data.json asset.
 *
 * Source: https://github.com/darkmentorllc/CLUES_Schema/blob/main/CLUES_data.json
 *
 * The CLUES data set associates well-known UUIDs (services, characteristics,
 * advertisement payloads, etc.) with the company that owns them and a
 * human-readable name/purpose. We use it for two things:
 *  1. Vendor exclusion when filtering devices (any UUID seen for a device that
 *     CLUES attributes to e.g. Apple is treated as evidence of an Apple device).
 *  2. Optional supplemental enrichment of GATT enumeration output for
 *     downstream tooling.
 *
 * UUIDs are stored as lower-cased dashed strings (e.g. "9fa480e0-4967-4542-..."),
 * matching the format Android exposes for 128-bit UUIDs. Short SIG UUIDs in
 * CLUES are kept in their native short form ("180a", "2a29", etc.).
 */
class CluesRepository(
    private val appContext: Context,
) {

    data class Entry(
        val uuid: String,
        val company: String?,
        val name: String?,
        val purpose: String?,
        val usages: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var indexByUuid: Map<String, Entry>? = null

    private fun ensureLoaded(): Map<String, Entry> {
        indexByUuid?.let { return it }
        synchronized(this) {
            indexByUuid?.let { return it }
            val loaded = try {
                appContext.assets.open(ASSET_NAME).use { stream ->
                    val text = stream.bufferedReader().readText()
                    parse(text)
                }
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Failed to load CLUES asset")
                emptyMap()
            }
            indexByUuid = loaded
            return loaded
        }
    }

    /** Look up an entry by UUID (case-insensitive, dashes allowed). */
    fun lookup(uuid: String): Entry? {
        return ensureLoaded()[normalizeUuid(uuid)]
    }

    /** Returns true if any UUID belongs to a company whose name contains [companyContains]. */
    fun anyUuidIsCompany(uuids: Collection<String>, companyContains: String): Boolean {
        val map = ensureLoaded()
        val needle = companyContains.lowercase()
        for (u in uuids) {
            val e = map[normalizeUuid(u)] ?: continue
            if ((e.company ?: "").lowercase().contains(needle)) return true
        }
        return false
    }

    private fun parse(text: String): Map<String, Entry> {
        val arr = json.parseToJsonElement(text).jsonArray
        val out = LinkedHashMap<String, Entry>(arr.size * 2)
        for (el in arr) {
            val obj = el.jsonObject
            val uuid = (obj["UUID"]?.jsonPrimitive?.content ?: continue).let { normalizeUuid(it) }
            val company = obj["company"]?.jsonPrimitive?.content
            val name = obj["UUID_name"]?.jsonPrimitive?.content
            val purpose = obj["UUID_purpose"]?.jsonPrimitive?.content
            val usages = obj["UUID_usage_array"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            out[uuid] = Entry(uuid, company, name, purpose, usages)
        }
        return out
    }

    companion object {
        private const val ASSET_NAME = "CLUES_data.json"
        private const val TAG = "CluesRepository"

        fun normalizeUuid(uuid: String): String {
            val lower = uuid.lowercase().trim()
            // Accept "180A" (short) → "180a"; "180a" stays; long stays as-is.
            return lower
        }
    }
}
