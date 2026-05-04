package f.cking.software.data.btides

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStream

/**
 * Persists BLE advertisement scans and GATT observations to disk in BTIDES-compatible form.
 *
 * Live capture appends one JSONL record per advertisement / GATT event to a single log file.
 * Each record is a SingleBDADDR object containing exactly one event (an AdvChanArray entry, or
 * a partial GATTArray entry). The append-style file is cheap to write and bounded only by disk
 * space.
 *
 * Export reads the JSONL log, groups entries by (bdaddr, bdaddr_rand), merges their
 * AdvChanArray and GATTArray contents, and emits a single BTIDES JSON array conforming to
 * BTIDES_base.json.
 */
class BTIDESRepository(
    private val context: Context,
) {

    private val writeLock = Mutex()
    private val json = Json { encodeDefaults = false }

    /**
     * Per-address GATT capture sessions. While a session exists for an address, GATT records
     * for that address are buffered in memory instead of being appended to the JSONL file.
     * The bulk-enumeration flow uses this to avoid writing GATT data for devices that turn
     * out to belong to a filtered vendor.
     *
     * A session that's been marked `discarded` keeps absorbing in-flight writes (so that fire-
     * and-forget auto-captures from BleScannerHelper that race with the disconnect can't sneak
     * past the buffer and land on disk), but their records are dropped on close.
     */
    private data class GattSession(val buffer: MutableList<JsonObject> = mutableListOf(), var discarded: Boolean = false)

    private val gattSessions: MutableMap<String, GattSession> = mutableMapOf()
    private val sessionsLock = Mutex()

    private val logFile: File
        get() = File(context.filesDir, LOG_FILE_NAME).also { it.parentFile?.mkdirs() }

    /**
     * Path that ADB can pull without root: /sdcard/Android/data/<pkg>/files/btides_log.btides
     */
    fun externalExportFile(): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        return File(dir, EXPORT_FILE_NAME)
    }

    /**
     * Append a single AdvChanData entry for a scan.
     */
    suspend fun appendScan(
        bdaddr: String,
        bdaddrRand: Int,
        advType: Int,
        advTypeStr: String,
        scanTimeMs: Long,
        rssi: Int?,
        rawScanRecord: ByteArray?,
    ) {
        val advDataArray = BTIDESParser.parseAdvDataArray(rawScanRecord)

        val advChan = buildJsonObject {
            putJsonObject("std_optional_fields") {
                putJsonObject("time") {
                    put("unix_time_milli", scanTimeMs)
                    put("unix_time", scanTimeMs / 1000L)
                }
                if (rssi != null && rssi in -128..0) put("RSSI", rssi)
            }
            put("type", advType)
            put("type_str", advTypeStr)
            if (rawScanRecord != null && rawScanRecord.isNotEmpty()) {
                put("full_pkt_hex_str", rawScanRecord.toLowerHex())
            }
            put("AdvDataArray", advDataArray)
        }

        appendRecord {
            put("bdaddr", bdaddr.uppercase())
            put("bdaddr_rand", bdaddrRand)
            put("AdvChanArray", buildJsonArray { add(advChan) })
        }
    }

    /**
     * Append a structural GATT enumeration: services + characteristics + (parameterless) descriptors.
     */
    suspend fun appendGATTEnumeration(
        bdaddr: String,
        bdaddrRand: Int,
        services: List<BluetoothGattService>,
    ) {
        if (services.isEmpty()) return
        val gattArray = BTIDESGattBuilder.buildServicesArray(services)
        if (gattArray.isEmpty()) return
        appendGattRecord(bdaddr, bdaddrRand, gattArray)
    }

    /**
     * Append a Characteristic read result (success or error).
     */
    suspend fun appendCharacteristicRead(
        bdaddr: String,
        bdaddrRand: Int,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        val gattArray = BTIDESGattBuilder.buildCharacteristicReadArray(characteristic, value, status)
        if (gattArray.isEmpty()) return
        appendGattRecord(bdaddr, bdaddrRand, gattArray)
    }

    /**
     * Append a Descriptor read result.
     */
    suspend fun appendDescriptorRead(
        bdaddr: String,
        bdaddrRand: Int,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
        status: Int,
    ) {
        val gattArray = BTIDESGattBuilder.buildDescriptorReadArray(descriptor, value, status)
        if (gattArray.isEmpty()) return
        appendGattRecord(bdaddr, bdaddrRand, gattArray)
    }

    /** Begin a buffered GATT capture session for an address (idempotent). */
    suspend fun beginGattSession(bdaddr: String) {
        sessionsLock.withLock { gattSessions.getOrPut(bdaddr.uppercase()) { GattSession() } }
    }

    /**
     * Mark an in-progress session for discard. The session entry stays so that fire-and-forget
     * auto-captures racing against the disconnect still get absorbed into the (now-doomed)
     * buffer rather than landing on disk. Call [closeGattSession] once the connection has
     * fully torn down to actually free the session.
     */
    suspend fun markGattSessionForDiscard(bdaddr: String) {
        sessionsLock.withLock {
            gattSessions[bdaddr.uppercase()]?.let { it.discarded = true; it.buffer.clear() }
        }
    }

    /**
     * Close a session. If [commit] is true and the session was not previously marked for
     * discard, its buffered records are flushed to disk. Returns the number of records written.
     */
    suspend fun closeGattSession(bdaddr: String, commit: Boolean): Int {
        val key = bdaddr.uppercase()
        val session = sessionsLock.withLock { gattSessions.remove(key) } ?: return 0
        if (!commit || session.discarded || session.buffer.isEmpty()) return 0
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                FileWriter(logFile, /* append = */ true).use { writer ->
                    for (record in session.buffer) writer.appendLine(json.encodeToString(JsonObject.serializer(), record))
                }
            }
        }
        return session.buffer.size
    }

    /** True when the address has an active in-memory GATT session. */
    suspend fun hasGattSession(bdaddr: String): Boolean = sessionsLock.withLock {
        gattSessions.containsKey(bdaddr.uppercase())
    }

    private suspend fun appendGattRecord(bdaddr: String, bdaddrRand: Int, gattArray: JsonArray) {
        val record = buildJsonObject {
            put("bdaddr", bdaddr.uppercase())
            put("bdaddr_rand", bdaddrRand)
            put("GATTArray", gattArray)
        }
        val key = bdaddr.uppercase()
        val routedToSession = sessionsLock.withLock {
            val session = gattSessions[key]
            if (session != null) {
                if (!session.discarded) session.buffer += record
                true
            } else false
        }
        if (routedToSession) return
        writeRecordLine(record)
    }

    private suspend fun appendRecord(record: JsonObject) {
        writeRecordLine(record)
    }

    private suspend fun writeRecordLine(record: JsonObject) {
        val line = json.encodeToString(JsonObject.serializer(), record)
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                FileWriter(logFile, /* append = */ true).use { it.appendLine(line) }
            }
        }
    }

    private suspend inline fun appendRecord(crossinline build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        appendRecord(buildJsonObject(build))
    }

    /**
     * Pass 1: stream the JSONL log and route each record to a per-device temp file in cache.
     * Bounded memory: one [BufferedWriter] per unique device, plus a small map of file handles.
     *
     * Cooperatively checks coroutine cancellation between lines and reports source-bytes-consumed
     * via [onBytesProcessed] so the UI can render a proportional progress bar.
     */
    private suspend fun routeLogToPerDeviceTempFiles(
        tempDir: File,
        onBytesProcessed: suspend (Long) -> Unit,
    ): LinkedHashMap<String, File> {
        val devFiles = LinkedHashMap<String, File>()
        val writers = HashMap<String, BufferedWriter>()
        var bytes = 0L
        try {
            logFile.bufferedReader().useLines { lines ->
                for (rawLine in lines) {
                    bytes += rawLine.length.toLong() + 1L // +1 for newline
                    val line = rawLine.trim()
                    if (line.isEmpty()) continue
                    val m = KEY_REGEX.find(line) ?: continue
                    val key = "${m.groupValues[1]}|${m.groupValues[2]}"
                    val writer = writers.getOrPut(key) {
                        val f = File(tempDir, "dev_${devFiles.size}.jsonl")
                        devFiles[key] = f
                        f.bufferedWriter()
                    }
                    writer.write(line)
                    writer.newLine()
                    currentCoroutineContext().ensureActive()
                    onBytesProcessed(bytes)
                }
            }
        } finally {
            writers.values.forEach { runCatching { it.close() } }
        }
        return devFiles
    }

    /**
     * Pass 2 helper: merge a single device's records from its per-device temp file. Memory peak
     * is bounded by this device's data only — one accumulator at a time.
     *
     * [onLineConsumed] is called once per non-empty line with that line's byte count so the
     * caller can advance a progress counter. Cancellation is checked between lines.
     */
    private suspend fun mergeOneDeviceFromFile(
        file: File,
        key: String,
        onLineConsumed: suspend (Long) -> Unit,
    ): DeviceAccumulator {
        val sep = key.indexOf('|')
        val bdaddr = if (sep >= 0) key.substring(0, sep) else key
        val rand = if (sep >= 0) key.substring(sep + 1).toIntOrNull() ?: 1 else 1
        val acc = DeviceAccumulator(bdaddr, rand)
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val byteLen = line.length.toLong() + 1L
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val obj = try {
                    json.parseToJsonElement(trimmed).jsonObject
                } catch (e: Throwable) {
                    Timber.tag(TAG).w(e, "Skipping malformed BTIDES log line")
                    continue
                }
                obj["AdvChanArray"]?.jsonArray?.let { acc.advChan.addAll(it) }
                obj["GATTArray"]?.jsonArray?.let { acc.mergeGatt(it) }
                currentCoroutineContext().ensureActive()
                onLineConsumed(byteLen)
            }
        }
        return acc
    }

    /**
     * Stream the merged BTIDES array to an OutputStream as pretty-printed JSON.
     *
     * Two-pass design: pass 1 routes the JSONL log to per-device temp files; pass 2 builds and
     * writes one [DeviceAccumulator] at a time. Peak heap is bounded by the largest single
     * device's data, not the whole log — historically the in-memory merge OOM'd at ~45 MB of
     * JSONL because parsed `JsonElement` trees were 4-5x the wire size.
     *
     * Within pass 2 we recursively walk every nested object/array and write it piece-by-piece
     * to the output Writer, so only individual `JsonPrimitive` leaves ever go through
     * `encodeToString` — bounding the per-allocation size as well.
     *
     * [onProgress] is invoked with `(bytesProcessed, totalBytes)` where `totalBytes = 2 *
     * jsonl_file_size` (pass 1 reads the source once, pass 2 reads the routed temp files which
     * together hold the same data). The UI can divide to get a 0..1 fraction. Cancellation of
     * the surrounding coroutine is honored between lines in both passes.
     */
    suspend fun exportTo(
        out: OutputStream,
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val src = logFile
            if (!src.exists() || src.length() == 0L) {
                out.bufferedWriter().use { it.write("[]\n") }
                onProgress?.invoke(1L, 1L)
                return@withLock 0
            }
            val sourceSize = src.length()
            val totalBytes = sourceSize * 2L
            val tempDir = File(context.cacheDir, "btides_export_tmp_${System.currentTimeMillis()}")
            tempDir.mkdirs()
            try {
                onProgress?.invoke(0L, totalBytes)
                val devFiles = routeLogToPerDeviceTempFiles(tempDir) { bytes ->
                    onProgress?.invoke(bytes.coerceAtMost(sourceSize), totalBytes)
                }
                val writer = out.bufferedWriter()
                try {
                    writer.write("[")
                    var idx = 0
                    var pass2Bytes = 0L
                    for ((key, devFile) in devFiles) {
                        val acc = mergeOneDeviceFromFile(devFile, key) { lineBytes ->
                            pass2Bytes += lineBytes
                            onProgress?.invoke(sourceSize + pass2Bytes.coerceAtMost(sourceSize), totalBytes)
                        }
                        if (idx > 0) writer.write(",")
                        writer.write("\n  ")
                        writer.writeJsonObjectStreaming(acc.toJsonObject(), INDENT_UNIT, INDENT_UNIT)
                        idx++
                    }
                    if (devFiles.isNotEmpty()) writer.write("\n")
                    writer.write("]\n")
                    writer.flush()
                    onProgress?.invoke(totalBytes, totalBytes)
                } finally {
                    writer.close()
                }
                devFiles.size
            } finally {
                runCatching { tempDir.deleteRecursively() }
            }
        }
    }

    private fun BufferedWriter.writeJsonElementStreaming(value: JsonElement, indent: String, baseIndent: String) {
        when (value) {
            is JsonObject -> writeJsonObjectStreaming(value, indent, baseIndent)
            is JsonArray -> writeJsonArrayStreaming(value, indent, baseIndent)
            is JsonNull -> write("null")
            is JsonPrimitive -> write(json.encodeToString(JsonElement.serializer(), value))
        }
    }

    private fun BufferedWriter.writeJsonObjectStreaming(obj: JsonObject, indent: String, baseIndent: String) {
        if (obj.isEmpty()) { write("{}"); return }
        write("{")
        val childIndent = baseIndent + indent
        var i = 0
        for ((key, value) in obj) {
            if (i > 0) write(",")
            write("\n")
            write(childIndent)
            write(json.encodeToString(JsonElement.serializer(), JsonPrimitive(key)))
            write(": ")
            writeJsonElementStreaming(value, indent, childIndent)
            i++
        }
        write("\n")
        write(baseIndent)
        write("}")
    }

    private fun BufferedWriter.writeJsonArrayStreaming(arr: JsonArray, indent: String, baseIndent: String) {
        if (arr.isEmpty()) { write("[]"); return }
        write("[")
        val childIndent = baseIndent + indent
        arr.forEachIndexed { i, element ->
            if (i > 0) write(",")
            write("\n")
            write(childIndent)
            writeJsonElementStreaming(element, indent, childIndent)
        }
        write("\n")
        write(baseIndent)
        write("]")
    }

    suspend fun exportTo(
        uri: Uri,
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        resolver.openOutputStream(uri, "wt")?.use { exportTo(it, onProgress) }
            ?: throw RuntimeException("Cannot open output stream for $uri")
    }

    /**
     * If the caller's coroutine is cancelled mid-export, the partial output file is deleted so
     * the user is never left with a half-written `.btides` file masquerading as a complete one.
     */
    suspend fun exportToExternalFilesDir(
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): Pair<File, Int> = withContext(Dispatchers.IO) {
        val target = externalExportFile() ?: throw RuntimeException("External files dir is unavailable")
        target.parentFile?.mkdirs()
        val count = try {
            target.outputStream().use { exportTo(it, onProgress) }
        } catch (t: Throwable) {
            runCatching { target.delete() }
            throw t
        }
        target to count
    }

    suspend fun clearLog() = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val f = logFile
            if (f.exists()) f.delete()
        }
    }

    suspend fun logFileSizeBytes(): Long = withContext(Dispatchers.IO) {
        val f = logFile
        if (f.exists()) f.length() else 0L
    }

    /**
     * Scan the JSONL log for every record matching [address] (case-insensitive) and merge them
     * into a single BTIDES device JsonObject. Returns null when the device has no GATT records
     * captured yet.
     *
     * Used by the device-details screen to display previously-enumerated services + characteristic
     * values without requiring a fresh GATT connection. A full scan of the log is acceptable for
     * a one-shot user-triggered load — the BleScannerHelper writes a per-device session into
     * `gattSessions` for in-memory data, but those records get flushed to disk on session close,
     * so the JSONL is the only durable source.
     */
    suspend fun cachedGattForDevice(address: String): JsonObject? = withContext(Dispatchers.IO) {
        val src = logFile
        if (!src.exists() || src.length() == 0L) return@withContext null
        val target = address.uppercase()
        val acc = DeviceAccumulator(target, /* rand placeholder */ 1)
        var hadAnyMatch = false
        // Live captures may also be buffering for this address (Connect All session). Pull those
        // first so the cache reflects them before the JSONL flush.
        sessionsLock.withLock {
            gattSessions[target]?.buffer?.forEach { record ->
                record["GATTArray"]?.jsonArray?.let { acc.mergeGatt(it); hadAnyMatch = true }
            }
        }
        // Scan the JSONL log line-by-line. The KEY_REGEX is a fast first-pass filter so we don't
        // pay the JSON parse cost on every line.
        src.bufferedReader().useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val m = KEY_REGEX.find(line) ?: continue
                if (!m.groupValues[1].equals(target, ignoreCase = true)) continue
                val obj = try {
                    json.parseToJsonElement(line).jsonObject
                } catch (_: Throwable) {
                    continue
                }
                obj["GATTArray"]?.jsonArray?.let { acc.mergeGatt(it); hadAnyMatch = true }
            }
        }
        if (!hadAnyMatch) null else acc.toJsonObject()
    }

    private fun ByteArray.toLowerHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }

    /**
     * Per-device merge state: AdvChan list + a service hierarchy keyed by service-handle range.
     */
    private inner class DeviceAccumulator(val bdaddr: String, val rand: Int) {
        val advChan: MutableList<JsonElement> = mutableListOf()
        // service key = "begin_handle|UUID" — handle disambiguates duplicate UUIDs.
        val services: LinkedHashMap<String, ServiceAccumulator> = linkedMapOf()

        fun mergeGatt(gattArray: JsonArray) {
            for (entry in gattArray) {
                val svc = entry as? JsonObject ?: continue
                val begin = svc["begin_handle"]?.jsonPrimitive?.intOrNull ?: continue
                val uuid = svc["UUID"]?.jsonPrimitive?.contentOrNull ?: continue
                val key = "$begin|$uuid"
                val sAcc = services.getOrPut(key) { ServiceAccumulator(svc) }
                sAcc.merge(svc)
            }
        }

        fun toJsonObject(): JsonObject = buildJsonObject {
            put("bdaddr", bdaddr)
            put("bdaddr_rand", rand)
            if (advChan.isNotEmpty()) put("AdvChanArray", JsonArray(advChan))
            if (services.isNotEmpty()) {
                put("GATTArray", buildJsonArray { services.values.forEach { add(it.toJsonObject()) } })
            }
        }
    }

    private inner class ServiceAccumulator(initial: JsonObject) {
        private var typeStr: String? = initial["type_str"]?.jsonPrimitive?.contentOrNull
        private var utype: String? = initial["utype"]?.jsonPrimitive?.contentOrNull
        private var uuid: String? = initial["UUID"]?.jsonPrimitive?.contentOrNull
        private var beginHandle: Int = initial["begin_handle"]?.jsonPrimitive?.intOrNull ?: 1
        private var endHandle: Int = initial["end_handle"]?.jsonPrimitive?.intOrNull ?: beginHandle
        // characteristic key = decl handle
        private val characteristics: LinkedHashMap<Int, CharacteristicAccumulator> = linkedMapOf()

        init {
            initial["characteristics"]?.jsonArray?.forEach { c ->
                val obj = c as? JsonObject ?: return@forEach
                val handle = obj["handle"]?.jsonPrimitive?.intOrNull ?: return@forEach
                characteristics.getOrPut(handle) { CharacteristicAccumulator(obj) }.merge(obj)
            }
        }

        fun merge(other: JsonObject) {
            other["end_handle"]?.jsonPrimitive?.intOrNull?.let { if (it > endHandle) endHandle = it }
            if (typeStr == null) typeStr = other["type_str"]?.jsonPrimitive?.contentOrNull
            if (utype == null) utype = other["utype"]?.jsonPrimitive?.contentOrNull
            if (uuid == null) uuid = other["UUID"]?.jsonPrimitive?.contentOrNull
            other["characteristics"]?.jsonArray?.forEach { c ->
                val obj = c as? JsonObject ?: return@forEach
                val handle = obj["handle"]?.jsonPrimitive?.intOrNull ?: return@forEach
                characteristics.getOrPut(handle) { CharacteristicAccumulator(obj) }.merge(obj)
            }
        }

        fun toJsonObject(): JsonObject = buildJsonObject {
            typeStr?.let { put("type_str", it) }
            utype?.let { put("utype", it) }
            uuid?.let { put("UUID", it) }
            put("begin_handle", beginHandle)
            put("end_handle", endHandle)
            if (characteristics.isNotEmpty()) {
                put("characteristics", buildJsonArray { characteristics.values.forEach { add(it.toJsonObject()) } })
            }
        }
    }

    private inner class CharacteristicAccumulator(initial: JsonObject) {
        private var typeStr: String? = initial["type_str"]?.jsonPrimitive?.contentOrNull
        private var utype: String? = initial["utype"]?.jsonPrimitive?.contentOrNull
        private var handle: Int = initial["handle"]?.jsonPrimitive?.intOrNull ?: 1
        private var properties: Int = initial["properties"]?.jsonPrimitive?.intOrNull ?: 0
        private var valueHandle: Int = initial["value_handle"]?.jsonPrimitive?.intOrNull ?: handle
        private var valueUuid: String = initial["value_uuid"]?.jsonPrimitive?.contentOrNull.orEmpty()
        private var charValueIo: MutableList<JsonElement> = mutableListOf()
        // Descriptor key = handle. Last write wins for the parsed fields (e.g. config_bits),
        // but the schema's descriptors are not multi-valued so this is correct.
        private val descriptors: LinkedHashMap<Int, JsonObject> = linkedMapOf()

        init {
            initial["char_value"]?.jsonObject?.get("io_array")?.jsonArray?.let { charValueIo.addAll(it) }
            initial["descriptors"]?.jsonArray?.forEach { d ->
                val obj = d as? JsonObject ?: return@forEach
                val h = obj["handle"]?.jsonPrimitive?.intOrNull ?: return@forEach
                descriptors[h] = obj
            }
        }

        fun merge(other: JsonObject) {
            other["properties"]?.jsonPrimitive?.intOrNull?.takeIf { it != 0 }?.let { properties = it }
            other["value_handle"]?.jsonPrimitive?.intOrNull?.let { valueHandle = it }
            other["value_uuid"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) valueUuid = it }
            other["char_value"]?.jsonObject?.get("io_array")?.jsonArray?.let { charValueIo.addAll(it) }
            other["descriptors"]?.jsonArray?.forEach { d ->
                val obj = d as? JsonObject ?: return@forEach
                val h = obj["handle"]?.jsonPrimitive?.intOrNull ?: return@forEach
                descriptors[h] = obj
            }
        }

        fun toJsonObject(): JsonObject = buildJsonObject {
            typeStr?.let { put("type_str", it) }
            utype?.let { put("utype", it) }
            put("handle", handle)
            put("properties", properties)
            put("value_handle", valueHandle)
            put("value_uuid", valueUuid)
            if (charValueIo.isNotEmpty()) {
                put("char_value", buildJsonObject {
                    put("handle", valueHandle)
                    put("value_uuid", valueUuid)
                    put("io_array", JsonArray(charValueIo))
                })
            }
            if (descriptors.isNotEmpty()) {
                put("descriptors", buildJsonArray { descriptors.values.forEach { add(it) } })
            }
        }
    }

    companion object {
        private const val TAG = "BTIDESRepository"
        private const val LOG_FILE_NAME = "btides/btides_log.jsonl"
        const val EXPORT_FILE_NAME = "btides_log.btides"
        private const val INDENT_UNIT = "  "
        // Match the bdaddr + bdaddr_rand top-level fields without parsing the whole record. The
        // appendScan/appendGattRecord writers always emit them as the first two object keys, in
        // that order, on a single line — this regex is a fast first-pass router.
        private val KEY_REGEX = Regex("""\"bdaddr\"\s*:\s*\"([0-9A-Fa-f:]+)\"[^}]*?\"bdaddr_rand\"\s*:\s*(-?\d+)""")
    }
}
