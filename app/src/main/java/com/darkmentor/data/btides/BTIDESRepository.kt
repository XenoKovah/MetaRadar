package com.darkmentor.data.btides

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.net.Uri
import com.darkmentor.domain.model.ExclusionZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * The strongest-RSSI sample that placed a device at a known lat/lng. Returned by the export
 * lookup function to enrich the per-device export record with "where this device's signal was
 * strongest" metadata. The data lives in Room (device_to_location.rssi joined to location);
 * BTIDESRepository receives it via a lookup function so it doesn't have to depend on the
 * location tables directly.
 */
data class StrongestRssiLocation(
    val lat: Double,
    val lng: Double,
    val rssi: Int?,
    val timeMs: Long,
)

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

    private val indexFile: File
        get() = File(context.filesDir, INDEX_FILE_NAME).also { it.parentFile?.mkdirs() }

    private val btidesDir: File
        get() = File(context.filesDir, BTIDES_DIR).also { it.mkdirs() }

    /**
     * Sidecar marker holding the epoch-millis timestamp of the first append into the current
     * active log. Written when we hit the empty-log → first-byte transition; deleted on
     * rotate / clear. Drives both archive naming (so archive filenames reflect the time the
     * data was *captured*, not the time of rotation) and the per-day auto-rotation check.
     */
    private val firstCaptureFile: File
        get() = File(btidesDir, FIRST_CAPTURE_FILE_NAME)

    /** In-memory cache of the on-disk first-capture marker. Avoids stat/read on every record. */
    @Volatile private var firstCaptureMs: Long? = null

    /**
     * Channel-based write pipeline. Every record (advertisements, GATT enumerations, char/desc
     * reads, and flushed gatt-session buffers) flows through this single channel into a
     * dedicated consumer coroutine. Two consequences:
     *  - Back-pressure: callers suspend if the channel is full (capacity 4096), satisfying the
     *    "every ad must persist" constraint without dropping under load.
     *  - One open FileOutputStream + BufferedOutputStream for app lifetime — replaces the per-
     *    event `FileWriter().use { append }` pattern that allocated ~20k FileWriters/sec at
     *    mall-scale scan rates. The previous global `writeLock` is gone; the channel itself
     *    is the synchronisation point.
     *
     * The consumer also writes a sidecar index entry (`<addr> <byteOffset> <byteLen>\n`) per
     * record so [cachedGattForDevice] can do O(records-for-this-device) lookups via
     * RandomAccessFile.seek instead of streaming the entire JSONL.
     */
    private val writeChannel = Channel<JsonObject>(
        capacity = WRITE_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writerStateLock = Mutex()

    private var logStream: BufferedOutputStream? = null
    private var indexStream: BufferedOutputStream? = null
    private var bytesInLog: Long = 0L

    init {
        // Seed bytesInLog from the on-disk log size so byte offsets in the sidecar index
        // remain accurate across app restarts.
        bytesInLog = if (logFile.exists()) logFile.length() else 0L
        // Hydrate the first-capture cache from the on-disk marker (or null if absent — the
        // next append will write it).
        firstCaptureMs = if (firstCaptureFile.exists()) {
            firstCaptureFile.readText().trim().toLongOrNull()
        } else null
        // Start the writer consumer. Lives for app lifetime (BTIDESRepository is a Koin single).
        ioScope.launch { runWriterLoop() }
        // If the index is missing or stale (e.g. first run after upgrade, or it was hand-deleted),
        // rebuild it by walking the existing JSONL once. Runs in the background so app start
        // isn't blocked on a 200 MB scan.
        ioScope.launch { rebuildIndexIfMissing() }
    }

    private suspend fun runWriterLoop() {
        var pending = 0
        var lastFlush = System.currentTimeMillis()
        for (record in writeChannel) {
            try {
                writeRecordToStreams(record)
                pending++
                val now = System.currentTimeMillis()
                if (pending >= WRITER_FLUSH_RECORDS || now - lastFlush > WRITER_FLUSH_INTERVAL_MS) {
                    writerStateLock.withLock {
                        logStream?.flush()
                        indexStream?.flush()
                    }
                    pending = 0
                    lastFlush = now
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Writer loop failed for one record; continuing")
            }
        }
    }

    private suspend fun writeRecordToStreams(record: JsonObject) {
        val serialized = json.encodeToString(JsonObject.serializer(), record)
        val bytes = serialized.toByteArray(Charsets.UTF_8)
        // bdaddr is the first key written by every record producer in this file. Pull it out
        // once so we can write the sidecar index entry alongside the main log line. Type flag:
        // 'G' = record carries a GATTArray, 'A' = only an AdvChanArray. Letting the reader
        // filter by type avoids parsing thousands of advertisement records when looking up
        // a device's cached GATT.
        val address = record["bdaddr"]?.jsonPrimitive?.contentOrNull?.uppercase()
        val typeFlag = when {
            record["GATTArray"] != null -> RECORD_TYPE_GATT
            record["AdvChanArray"] != null -> RECORD_TYPE_ADV
            record["SDPArray"] != null -> RECORD_TYPE_SDP
            record["EIRArray"] != null -> RECORD_TYPE_EIR
            record["HCIArray"] != null -> RECORD_TYPE_HCI
            else -> RECORD_TYPE_OTHER
        }
        writerStateLock.withLock {
            // Auto-rotation triggers (in priority order):
            //   1. Per-day rollover — first capture into the current active log was on an
            //      earlier local-time day than now.
            //   2. Per-size rollover — the current active log has grown past
            //      [MAX_ACTIVE_LOG_BYTES] (sized to keep merged exports under the BTIDALPOOL
            //      server's 10 MB upload cap with headroom). Without this, a long-running
            //      capture session produces a single 100+ MB log that the server rejects with
            //      "File size too big" — the user then has to manually rotate / clear before
            //      they can upload anything.
            // The rotation itself is the only expensive part; the size check is a cheap
            // long compare against the in-memory bytesInLog counter on every write.
            val firstMs = firstCaptureMs
            val now = System.currentTimeMillis()
            val rolloverByDay = firstMs != null && shouldRolloverToNewDay(firstMs, now)
            val rolloverBySize = bytesInLog >= MAX_ACTIVE_LOG_BYTES
            if (rolloverByDay || rolloverBySize) {
                rotateActiveLocked()
            }
            ensureStreamsOpenLocked()
            stampFirstCaptureMarkerIfMissingLocked()
            val byteStart = bytesInLog
            logStream!!.write(bytes)
            logStream!!.write('\n'.code)
            bytesInLog += bytes.size + 1
            if (address != null) {
                val indexLine = "$address $typeFlag $byteStart ${bytes.size}\n".toByteArray(Charsets.UTF_8)
                indexStream!!.write(indexLine)
            }
        }
    }

    private fun ensureStreamsOpenLocked() {
        if (logStream == null) {
            logFile.parentFile?.mkdirs()
            logStream = BufferedOutputStream(FileOutputStream(logFile, /* append = */ true))
        }
        if (indexStream == null) {
            indexFile.parentFile?.mkdirs()
            indexStream = BufferedOutputStream(FileOutputStream(indexFile, /* append = */ true))
        }
    }

    private fun stampFirstCaptureMarkerIfMissingLocked() {
        if (firstCaptureMs != null) return
        val now = System.currentTimeMillis()
        firstCaptureMs = now
        runCatching {
            btidesDir.mkdirs()
            firstCaptureFile.writeText(now.toString())
        }
    }

    /**
     * Whether [firstMs] and [nowMs] fall on different *local* calendar days. Local time matches
     * the user's intuition for "yesterday's data" — UTC day boundaries surprise users in
     * timezones west of UTC. We compute via TimeZone.getOffset rather than a Calendar instance
     * because this runs on the writer hot path.
     */
    private fun shouldRolloverToNewDay(firstMs: Long, nowMs: Long): Boolean {
        val tz = java.util.TimeZone.getDefault()
        val firstDay = (firstMs + tz.getOffset(firstMs)) / MS_PER_DAY
        val nowDay = (nowMs + tz.getOffset(nowMs)) / MS_PER_DAY
        return firstDay != nowDay
    }

    /**
     * Rename the current active log + sidecar index to date-stamped archive files based on the
     * first-capture timestamp marker. Caller must hold [writerStateLock]. No-op when the active
     * log is empty (nothing to rotate). Returns the archive log file, or null if no rotation
     * happened.
     */
    private fun rotateActiveLocked(): File? {
        runCatching { logStream?.close() }
        runCatching { indexStream?.close() }
        logStream = null
        indexStream = null
        if (!logFile.exists() || logFile.length() == 0L) {
            // Nothing captured — just clear the marker so the next append re-stamps it.
            runCatching { firstCaptureFile.delete() }
            firstCaptureMs = null
            bytesInLog = 0L
            return null
        }
        val firstMs = firstCaptureMs ?: System.currentTimeMillis()
        val baseName = "btides_log_${formatArchiveTimestamp(firstMs)}"
        val archiveLog = uniqueArchiveFile(baseName, ARCHIVE_LOG_EXT)
        val archiveIdx = File(btidesDir, archiveLog.nameWithoutExtension + "." + ARCHIVE_IDX_EXT)
        runCatching { logFile.renameTo(archiveLog) }
        runCatching { if (indexFile.exists()) indexFile.renameTo(archiveIdx) }
        runCatching { firstCaptureFile.delete() }
        firstCaptureMs = null
        bytesInLog = 0L
        return archiveLog
    }

    private fun uniqueArchiveFile(baseName: String, ext: String): File {
        var candidate = File(btidesDir, "$baseName.$ext")
        var i = 1
        while (candidate.exists()) {
            candidate = File(btidesDir, "${baseName}_$i.$ext")
            i++
        }
        return candidate
    }

    private fun formatArchiveTimestamp(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
        return sdf.format(java.util.Date(ms))
    }

    /**
     * One log: either the live active log or a rotated archive. Exposes a stable label suitable
     * for use as an export file basename (so on-disk filenames stay parallel between
     * `<filesDir>/btides/<label>.jsonl` and `<external>/<label>.btides`).
     */
    data class LogFile(val file: File, val isActive: Boolean) {
        /** Stable basename without extension; used to derive matching export filenames. */
        val baseName: String get() = file.nameWithoutExtension
    }

    /**
     * List the active log (if non-empty) followed by all rotated archives in chronological
     * order. Archives are named `btides_log_YYYY-MM-DD_HH-mm-ss[_N].jsonl` so a lexical sort
     * matches capture-time order. Index sidecar files are filtered out.
     */
    suspend fun listLogs(): List<LogFile> = withContext(Dispatchers.IO) {
        writerStateLock.withLock { runCatching { logStream?.flush() } }
        val out = mutableListOf<LogFile>()
        if (logFile.exists() && logFile.length() > 0L) out.add(LogFile(logFile, isActive = true))
        btidesDir.listFiles()
            ?.filter {
                // Match `btides_log_<stamp>.jsonl` but explicitly exclude the parallel
                // `btides_log_<stamp>.idx.jsonl` sidecar — both end with ".jsonl" so the naive
                // suffix check would treat each archive as two logs.
                it.isFile &&
                    it.name.startsWith("btides_log_") &&
                    it.name.endsWith("." + ARCHIVE_LOG_EXT) &&
                    !it.name.endsWith("." + ARCHIVE_IDX_EXT)
            }
            ?.sortedBy { it.name }
            ?.forEach { out.add(LogFile(it, isActive = false)) }
        out
    }

    /**
     * Rotate the active log into a date-stamped archive. Returns the archive file, or null
     * if the active log was empty.
     */
    suspend fun rotateActive(): File? = withContext(Dispatchers.IO) {
        writerStateLock.withLock { rotateActiveLocked() }
    }

    /**
     * Delete a specific archive file (and its sidecar index, if any). No-op for the active log.
     */
    suspend fun deleteArchive(archive: File) = withContext(Dispatchers.IO) {
        if (archive == logFile) return@withContext
        runCatching { archive.delete() }
        val idx = File(btidesDir, archive.nameWithoutExtension + "." + ARCHIVE_IDX_EXT)
        runCatching { idx.delete() }
    }

    /** Total bytes across the active log + every rotated archive. */
    suspend fun totalLogSizeBytes(): Long = withContext(Dispatchers.IO) {
        listLogs().sumOf { it.file.length() }
    }

    /**
     * Walk the JSONL and rebuild the sidecar index. Called from `init` when the index file is
     * missing (first launch after upgrade, or user deleted it manually). Costs one full
     * sequential pass over the log — but runs in the background and only the first time.
     */
    private suspend fun rebuildIndexIfMissing() {
        if (indexFile.exists() && indexFile.length() > 0L) return
        if (!logFile.exists() || logFile.length() == 0L) return
        Timber.tag(TAG).i("Rebuilding BTIDES sidecar index from %d bytes of JSONL", logFile.length())
        writerStateLock.withLock {
            // Close the index stream so we can rewrite the file from scratch.
            runCatching { indexStream?.close() }
            indexStream = null
            FileOutputStream(indexFile, /* append = */ false).use { rawOut ->
                BufferedOutputStream(rawOut).use { out ->
                    var offset = 0L
                    logFile.bufferedReader().useLines { lines ->
                        for (rawLine in lines) {
                            val bytes = rawLine.toByteArray(Charsets.UTF_8)
                            val m = KEY_REGEX.find(rawLine)
                            if (m != null) {
                                val addr = m.groupValues[1].uppercase()
                                // Substring sniff is enough for the type tag — no need to parse
                                // the whole JSON during rebuild. Lines always contain one of
                                // these tokens (or neither, in which case mark as OTHER).
                                val typeFlag = when {
                                    rawLine.contains("\"GATTArray\":") -> RECORD_TYPE_GATT
                                    rawLine.contains("\"AdvChanArray\":") -> RECORD_TYPE_ADV
                                    rawLine.contains("\"SDPArray\":") -> RECORD_TYPE_SDP
                                    rawLine.contains("\"EIRArray\":") -> RECORD_TYPE_EIR
                                    rawLine.contains("\"HCIArray\":") -> RECORD_TYPE_HCI
                                    else -> RECORD_TYPE_OTHER
                                }
                                val indexLine = "$addr $typeFlag $offset ${bytes.size}\n".toByteArray(Charsets.UTF_8)
                                out.write(indexLine)
                            }
                            offset += bytes.size + 1
                        }
                    }
                }
            }
            // The next writer iteration will reopen the index stream in append mode.
        }
        Timber.tag(TAG).i("Sidecar index rebuild complete (%d bytes)", indexFile.length())
    }

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

    /**
     * Append a synthesized SDP enumeration result for [bdaddr]. Android only exposes the parsed
     * service-class UUID list — never raw SDP/L2CAP bytes — so we synthesize a schema-valid
     * `0x07_SDP_SERVICE_SEARCH_ATTR_RSP` PDU via [BTIDESSdpBuilder] and tag it with
     * `std_optional_fields.src_file = "android-fetchUuidsWithSdp"` so wire-captured records
     * remain distinguishable downstream.
     *
     * BR/EDR addresses are always public per BT Core Spec — `bdaddr_rand` is hardcoded to 0.
     */
    suspend fun appendSDPDiscovery(
        bdaddr: String,
        uuids: List<java.util.UUID>,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        val sdpRecord = BTIDESSdpBuilder.synthesizeSearchAttrRspRecord(uuids, timestampMs)
        appendRecord {
            put("bdaddr", bdaddr.uppercase())
            put("bdaddr_rand", 0)
            put("SDPArray", buildJsonArray { add(sdpRecord) })
        }
    }

    /**
     * Append an EIR ClassOfDevice record (BTIDES_EIR.json type=2). Carries the Android-derived
     * 24-bit Class-of-Device value as a 6-char lower-hex string, matching the schema's
     * `CoD_hex_str` shape. BR/EDR-only — `bdaddr_rand` is 0.
     */
    suspend fun appendEirClassOfDevice(
        bdaddr: String,
        cod: Int,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        val codHex = "%06x".format(cod and 0xFFFFFF)
        val eirEntry = buildJsonObject {
            putJsonObject("std_optional_fields") {
                putJsonObject("time") {
                    put("unix_time_milli", timestampMs)
                    put("unix_time", timestampMs / 1000L)
                }
            }
            put("type", 2)
            put("CoD_hex_str", codHex)
        }
        appendRecord {
            put("bdaddr", bdaddr.uppercase())
            put("bdaddr_rand", 0)
            put("EIRArray", buildJsonArray { add(eirEntry) })
        }
    }

    /**
     * Append an HCI Remote Name Request Complete record (BTIDES_HCI.json event_code=7). Used for
     * BR/EDR-inquiry-resolved device names — the schema's own comment notes this event carries
     * the *reassembled* remote name (post-fragmentation), which matches what Android hands us
     * via `BluetoothDevice.EXTRA_NAME`. There is no native EIRArray variant for the local name,
     * and the AdvData CompleteLocalName variants are LE-flavoured, so HCIArray is the cleanest
     * home for this data.
     *
     * `name` may contain non-printable bytes when round-tripping through Android's String type;
     * `remote_name_hex_str` preserves the original UTF-8 bytes verbatim so downstream tools can
     * reconstruct the exact wire form, while `utf8_name` is filtered to printable code points
     * to satisfy the schema's "printable UTF-8" contract.
     */
    suspend fun appendHciRemoteName(
        bdaddr: String,
        name: String,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val nameHex = nameBytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
        val printable = name.filter { c ->
            // Strip ASCII C0 controls and DEL; everything else (including non-ASCII printable
            // glyphs and emoji) passes through. Matches the spec's "printable UTF-8" intent.
            c.code !in 0x00..0x1F && c.code != 0x7F
        }
        val hciEntry = buildJsonObject {
            putJsonObject("std_optional_fields") {
                putJsonObject("time") {
                    put("unix_time_milli", timestampMs)
                    put("unix_time", timestampMs / 1000L)
                }
            }
            put("event_code", 7)
            put("event_code_str", "HCI_Remote_Name_Request_Complete")
            put("status", 0)
            put("remote_name_hex_str", nameHex)
            if (printable.isNotEmpty()) put("utf8_name", printable)
        }
        appendRecord {
            put("bdaddr", bdaddr.uppercase())
            put("bdaddr_rand", 0)
            put("HCIArray", buildJsonArray { add(hciEntry) })
        }
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
        // Funnel buffered records through the same channel as live writes so byte offsets
        // stay sequential and every record gets a sidecar-index entry.
        for (record in session.buffer) writeChannel.send(record)
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
                if (!session.discarded) {
                    if (session.buffer.size >= MAX_GATT_SESSION_BUFFER) {
                        // Defensive cap. In practice each device's GATT enumeration produces
                        // <100 records (Connect All caps reads at 12 chars/device). Hitting
                        // 1024 means something pathological — log + drop the new record so the
                        // session can't grow without bound. Affected: GATT records only;
                        // advertisements take a different path (`appendScan` → write directly).
                        Timber.tag(TAG).w("GATT session buffer full for %s (size=%d), dropping record", key, session.buffer.size)
                    } else {
                        session.buffer += record
                    }
                }
                true
            } else false
        }
        if (routedToSession) return
        writeChannel.send(record)
        // A new GATT row will land in the index file shortly; force the next filter check to
        // re-read the index instead of returning a stale "no GATT" result for this address.
        invalidateGattAddressCache()
    }

    private suspend fun appendRecord(record: JsonObject) {
        // SUSPEND on full channel — back-pressure the caller rather than dropping the record.
        // The single-consumer writer runs on Dispatchers.IO and persists for app lifetime.
        writeChannel.send(record)
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
        sourceFile: File,
        tempDir: File,
        sourceSize: Long,
        onBytesProcessed: suspend (Long) -> Unit,
    ): LinkedHashMap<String, File> {
        val devFiles = LinkedHashMap<String, File>()
        // LRU-bounded writer pool. Previously this was an unbounded HashMap that kept one
        // open BufferedWriter per distinct device for the full source scan. On the Motorola
        // observed in the wild (≥867 distinct devices in a 60 MB archive) that pushed the
        // app into OOM via two paths: heap pressure from 800+ × 8 KB buffers + UTF-8
        // encoder state, and file-descriptor pressure (per-process fd cap is ~1024 on
        // Android). The LRU caps concurrent open writers at MAX_OPEN_DEVICE_WRITERS; the
        // eldest writer is flushed + closed on overflow. Reopens use append=true so prior
        // writes survive the cycle.
        val writerLru = object : LinkedHashMap<String, BufferedWriter>(MAX_OPEN_DEVICE_WRITERS, 0.75f, /* accessOrder= */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BufferedWriter>): Boolean {
                if (size <= MAX_OPEN_DEVICE_WRITERS) return false
                runCatching { eldest.value.close() }
                return true
            }
        }
        var bytes = 0L
        try {
            sourceFile.bufferedReader().useLines { lines ->
                for (rawLine in lines) {
                    // Stop at the snapshot boundary so we don't include records appended by
                    // the live writer after the export started — keeps the export's view of
                    // the log internally consistent with the snapshotted size.
                    if (bytes >= sourceSize) break
                    bytes += rawLine.length.toLong() + 1L // +1 for newline
                    val line = rawLine.trim()
                    if (line.isEmpty()) continue
                    val m = KEY_REGEX.find(line) ?: continue
                    val key = "${m.groupValues[1]}|${m.groupValues[2]}"
                    val file = devFiles.getOrPut(key) {
                        File(tempDir, "dev_${devFiles.size}.jsonl")
                    }
                    val writer = writerLru.getOrPut(key) {
                        // append=true so the FIRST open creates the file empty (it doesn't
                        // exist yet) and subsequent reopens after LRU eviction extend instead
                        // of truncating prior writes.
                        java.io.BufferedWriter(java.io.FileWriter(file, /* append= */ true))
                    }
                    writer.write(line)
                    writer.newLine()
                    currentCoroutineContext().ensureActive()
                    onBytesProcessed(bytes)
                }
            }
        } finally {
            writerLru.values.forEach { runCatching { it.close() } }
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
                obj["SDPArray"]?.jsonArray?.let { acc.mergeSdp(it) }
                obj["EIRArray"]?.jsonArray?.let { acc.mergeEir(it) }
                obj["HCIArray"]?.jsonArray?.let { acc.mergeHci(it) }
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
        strongestRssiLookup: suspend (String) -> StrongestRssiLocation? = { null },
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
        sourceFile: File? = null,
        // User GPS exclusion zones (BTIDALPOOL upload path only). Devices whose strongest-RSSI GPS
        // is inside any zone are dropped. Default empty ⇒ every other caller is unaffected.
        exclusionZones: List<ExclusionZone> = emptyList(),
    ): Int = withContext(Dispatchers.IO) {
        // Snapshot the log size at export start. Live capture continues writing past this
        // boundary; we just don't see those new records in the export. The previous design
        // held the global writeLock for the whole multi-second export, which froze every
        // appendScan/append… call — at mall-scale that meant tens of thousands of suspended
        // coroutines piling up by the time the export finished.
        val src = sourceFile ?: logFile
        // Only the active log is being written to live — flushing under the lock matters for
        // it. Archive files are write-once snapshots; we can stat them without contention.
        val sourceSize = if (src == logFile) {
            writerStateLock.withLock {
                logStream?.flush()
                indexStream?.flush()
                if (src.exists()) src.length() else 0L
            }
        } else {
            if (src.exists()) src.length() else 0L
        }
        if (sourceSize == 0L) {
            out.bufferedWriter().use { it.write("[]\n") }
            onProgress?.invoke(1L, 1L)
            return@withContext 0
        }
        val totalBytes = sourceSize * 2L
        val tempDir = File(context.cacheDir, "btides_export_tmp_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            onProgress?.invoke(0L, totalBytes)
            val devFiles = routeLogToPerDeviceTempFiles(src, tempDir, sourceSize) { bytes ->
                onProgress?.invoke(bytes.coerceAtMost(sourceSize), totalBytes)
            }
            // Hoisted to the outer try so the written-device count is in scope for the return below.
            var idx = 0
            val writer = out.bufferedWriter()
            try {
                writer.write("[")
                var pass2Bytes = 0L
                for ((key, devFile) in devFiles) {
                    val acc = mergeOneDeviceFromFile(devFile, key) { lineBytes ->
                        pass2Bytes += lineBytes
                        onProgress?.invoke(sourceSize + pass2Bytes.coerceAtMost(sourceSize), totalBytes)
                    }
                    // Look up the strongest-RSSI location for this device. Fail-soft: any lookup
                    // failure or missing data is silently ignored (older detections pre-migration
                    // 21→22 have no RSSI; offline-mode users have no locations at all).
                    val strongest = runCatching { strongestRssiLookup(acc.bdaddr) }.getOrNull()
                    // GPS upload-exclusion (privacy): if this device's strongest-RSSI sample — the
                    // only coordinate we'd emit for it — falls inside any user exclusion zone, drop
                    // the WHOLE device record. A device with no location can't be in a zone, so it's
                    // kept. Empty zone list (every caller except the upload path) makes this a no-op.
                    if (strongest != null && exclusionZones.any { it.contains(strongest.lat, strongest.lng) }) {
                        continue
                    }
                    if (idx > 0) writer.write(",")
                    writer.write("\n  ")
                    writer.writeJsonObjectStreaming(acc.toJsonObject(strongest), INDENT_UNIT, INDENT_UNIT)
                    idx++
                }
                if (idx > 0) writer.write("\n")
                writer.write("]\n")
                writer.flush()
                onProgress?.invoke(totalBytes, totalBytes)
            } finally {
                writer.close()
            }
            // Return the count actually WRITTEN (idx), not devFiles.size — so when every device is
            // excluded, uploadOneLog sees 0 and its EmptyLog short-circuit fires correctly.
            idx
        } finally {
            runCatching { tempDir.deleteRecursively() }
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
        strongestRssiLookup: suspend (String) -> StrongestRssiLocation? = { null },
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        resolver.openOutputStream(uri, "wt")?.use { exportTo(it, strongestRssiLookup, onProgress) }
            ?: throw RuntimeException("Cannot open output stream for $uri")
    }

    /**
     * Export every log (active + archives) to the app's external files dir, one file per log
     * named `<log-base-name>.btides`. Returns the list of (target, deviceCount) pairs that
     * were actually written. Empty input list when no logs exist.
     *
     * Each [onProgress] callback is rebased per-log: the caller sees the fraction *for the
     * currently-exporting log*. We can't easily compute a global fraction without first sizing
     * every log up front, and the per-log signal is what the inline progress bar consumes.
     *
     * If the caller's coroutine is cancelled mid-export, every partially-written file is
     * deleted so the user is never left with a half-written `.btides` masquerading as
     * complete.
     */
    suspend fun exportAllToExternalFilesDir(
        strongestRssiLookup: suspend (String) -> StrongestRssiLocation? = { null },
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): List<ExternalExportResult> = withContext(Dispatchers.IO) {
        val externalDir = context.getExternalFilesDir(null)
            ?: throw RuntimeException("External files dir is unavailable")
        val logs = listLogs()
        val results = mutableListOf<ExternalExportResult>()
        val written = mutableListOf<File>()
        try {
            for (log in logs) {
                val target = File(externalDir, log.baseName + "." + EXPORT_EXT)
                target.parentFile?.mkdirs()
                val count = try {
                    target.outputStream().use { exportTo(it, strongestRssiLookup, onProgress, sourceFile = log.file) }
                } catch (t: Throwable) {
                    runCatching { target.delete() }
                    throw t
                }
                written.add(target)
                results.add(ExternalExportResult(target, count, log.isActive))
            }
        } catch (t: Throwable) {
            // Cancellation / failure path: anything partially written stays on disk only for
            // the file we were mid-flush on (already-completed earlier files in the loop are
            // valid). Be conservative and remove only the in-flight one — preserved files give
            // the user *some* output even if the loop got interrupted mid-way.
            throw t
        }
        results
    }

    data class ExternalExportResult(val file: File, val deviceCount: Int, val isActive: Boolean)

    /**
     * Clear only the active (in-progress) log. Rotated archives remain on disk untouched.
     * Acquire the writer-state lock so no in-flight write is mid-flush. Close + delete the
     * main log + sidecar index + first-capture marker, reset the byte counter — the next
     * record through the channel reopens both streams in append mode against the fresh files.
     */
    suspend fun clearActive() = withContext(Dispatchers.IO) {
        writerStateLock.withLock {
            runCatching { logStream?.close() }
            runCatching { indexStream?.close() }
            logStream = null
            indexStream = null
            if (logFile.exists()) logFile.delete()
            if (indexFile.exists()) indexFile.delete()
            runCatching { firstCaptureFile.delete() }
            firstCaptureMs = null
            bytesInLog = 0L
        }
    }

    /** Clear the active log AND every rotated archive (data + sidecar indexes). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        clearActive()
        btidesDir.listFiles()
            ?.filter {
                it.isFile && it.name.startsWith("btides_log_") &&
                    (it.name.endsWith("." + ARCHIVE_LOG_EXT) || it.name.endsWith("." + ARCHIVE_IDX_EXT))
            }
            ?.forEach { runCatching { it.delete() } }
    }

    suspend fun logFileSizeBytes(): Long = withContext(Dispatchers.IO) {
        // Flush so the on-disk size reflects everything queued up to this point. The settings
        // screen polls this on every entry — flushing here is the cheapest way to make the
        // displayed size match user expectation without polling the writer's internal counter.
        writerStateLock.withLock {
            runCatching { logStream?.flush() }
        }
        val f = logFile
        if (f.exists()) f.length() else 0L
    }

    /**
     * Look up the merged GATT records for [address] using the sidecar index for O(records-for-
     * this-device) cost instead of scanning the entire JSONL log.
     *
     * The index file pairs every JSONL record with `<address> <byteOffset> <byteLen>`. We read
     * the index (small — ~30 bytes/record), find matching offsets for the target address, and
     * `RandomAccessFile.seek` directly to read just those lines from the main log. At a 200 MB
     * log with 1 M records and 50 records for the target device, this is ~10 ms vs. multi-second
     * for a full scan.
     *
     * If the index is missing (first launch on a pre-T2 install before the background rebuild
     * finishes), fall back to the previous full-log scan.
     */
    /**
     * Cached set of addresses that have at least one GATT (type 'G') record in the sidecar
     * index, plus any in-flight [GattSession] buffers. Source of truth for the GATT-quick-
     * filter on the Devices list. Cheap rebuild (single pass over the index file, no JSON
     * parse) but cached for [GATT_ADDRESSES_TTL_MS] so a 200k-row scan doesn't run on every
     * recomposition. Cache is invalidated by [appendGattRecord] (it knows it added a G row).
     */
    @Volatile private var gattAddressesCache: Set<String>? = null
    @Volatile private var gattAddressesCacheAtMs: Long = 0L

    suspend fun addressesWithGatt(): Set<String> = withContext(Dispatchers.IO) {
        val cached = gattAddressesCache
        val now = System.currentTimeMillis()
        if (cached != null && now - gattAddressesCacheAtMs < GATT_ADDRESSES_TTL_MS) {
            return@withContext cached
        }
        val out = HashSet<String>()
        // In-flight Connect-All sessions show up in gattSessions before the JSONL is flushed.
        sessionsLock.withLock {
            for ((addr, session) in gattSessions) {
                if (session.buffer.any { it["GATTArray"] != null }) out.add(addr.uppercase())
            }
        }
        writerStateLock.withLock {
            runCatching { indexStream?.flush() }
        }
        if (indexFile.exists() && indexFile.length() > 0L) {
            indexFile.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val parts = line.split(' ', limit = 4)
                    if (parts.size != 4) continue
                    if (parts[1] != RECORD_TYPE_GATT) continue
                    out.add(parts[0].uppercase())
                }
            }
        }
        gattAddressesCache = out
        gattAddressesCacheAtMs = now
        out
    }

    /** Drop the cached GATT-address set so the next [addressesWithGatt] re-reads the index. */
    fun invalidateGattAddressCache() {
        gattAddressesCache = null
    }

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
        // Flush so the index sidecar reflects everything queued up to this point.
        writerStateLock.withLock {
            runCatching { logStream?.flush() }
            runCatching { indexStream?.flush() }
        }
        if (indexFile.exists() && indexFile.length() > 0L) {
            // Fast path: scan the (small) index for matching offsets, then RandomAccessFile.seek
            // to read just those bytes from the main log. The 4-column format
            // `<addr> <type> <off> <len>` lets us skip every advertisement record (~99% of the
            // index for chatty devices) without paying the JSON parse cost on each one.
            val matches = mutableListOf<Pair<Long, Int>>()
            indexFile.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith(target)) continue
                    val parts = line.split(' ', limit = 4)
                    if (parts.size != 4) continue
                    if (parts[0] != target) continue
                    if (parts[1] != RECORD_TYPE_GATT) continue
                    val start = parts[2].toLongOrNull() ?: continue
                    val len = parts[3].toIntOrNull() ?: continue
                    matches += start to len
                }
            }
            if (matches.isNotEmpty()) {
                RandomAccessFile(src, "r").use { raf ->
                    val maxLen = matches.maxOf { it.second }
                    val buf = ByteArray(maxLen)
                    for ((start, len) in matches) {
                        raf.seek(start)
                        raf.readFully(buf, 0, len)
                        val line = String(buf, 0, len, Charsets.UTF_8)
                        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                        obj["GATTArray"]?.jsonArray?.let { acc.mergeGatt(it); hadAnyMatch = true }
                    }
                }
            }
        } else {
            // Fallback (rare — happens only on the first run after an upgrade, before the
            // background rebuildIndexIfMissing finishes). Same two-stage filter (cheap
            // substring → KEY_REGEX validator → JSON parse) as before.
            src.bufferedReader().useLines { lines ->
                for (rawLine in lines) {
                    val line = rawLine.trim()
                    if (line.isEmpty()) continue
                    if (!line.contains(target)) continue
                    val m = KEY_REGEX.find(line) ?: continue
                    if (!m.groupValues[1].equals(target, ignoreCase = true)) continue
                    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    obj["GATTArray"]?.jsonArray?.let { acc.mergeGatt(it); hadAnyMatch = true }
                }
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
        // SDP enumeration is one synthesized 0x07 PDU per fetch; multiple fetches for the same
        // device are last-write-wins (the most recent enumeration is the truth). Storing them
        // additively would just produce a stack of duplicates downstream tools have to dedup.
        var sdpRecord: JsonObject? = null
        // EIR entries (ClassOfDevice, PageScanRepetitionMode) are typed; collapse duplicates by
        // type so a device that's been inquired 100 times doesn't emit 100 identical CoD rows.
        val eirByType: LinkedHashMap<Int, JsonObject> = linkedMapOf()
        // HCI events are keyed by event_code so re-inquiries don't accumulate duplicate
        // Remote_Name_Request_Complete records.
        val hciByCode: LinkedHashMap<Int, JsonObject> = linkedMapOf()

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

        fun mergeSdp(sdpArray: JsonArray) {
            for (entry in sdpArray) {
                val rec = entry as? JsonObject ?: continue
                // We only synthesize 0x07 SERVICE_SEARCH_ATTR_RSP records, one at a time.
                // Multiple fetches → last one wins.
                sdpRecord = rec
            }
        }

        fun mergeEir(eirArray: JsonArray) {
            for (entry in eirArray) {
                val rec = entry as? JsonObject ?: continue
                val type = rec["type"]?.jsonPrimitive?.intOrNull ?: continue
                eirByType[type] = rec
            }
        }

        fun mergeHci(hciArray: JsonArray) {
            for (entry in hciArray) {
                val rec = entry as? JsonObject ?: continue
                val code = rec["event_code"]?.jsonPrimitive?.intOrNull ?: continue
                hciByCode[code] = rec
            }
        }

        fun toJsonObject(strongest: StrongestRssiLocation? = null): JsonObject = buildJsonObject {
            put("bdaddr", bdaddr)
            put("bdaddr_rand", rand)
            if (advChan.isNotEmpty()) put("AdvChanArray", JsonArray(advChan))
            if (services.isNotEmpty()) {
                put("GATTArray", buildJsonArray { services.values.forEach { add(it.toJsonObject()) } })
            }
            sdpRecord?.let { rec ->
                put("SDPArray", buildJsonArray { add(rec) })
            }
            if (eirByType.isNotEmpty()) {
                put("EIRArray", buildJsonArray { eirByType.values.forEach { add(it) } })
            }
            if (hciByCode.isNotEmpty()) {
                put("HCIArray", buildJsonArray { hciByCode.values.forEach { add(it) } })
            }
            // The strongest-RSSI sample's lat/lng/time/rssi for this device, emitted as a
            // standard BTIDES `GPSArray` record (BTIDES_GPS.json). This is the same coordinate
            // that surfaces as the black "strongest sample" marker on the device-details map.
            // Schema-required fields: time, lat, lon. RSSI is optional but always populated
            // here (the picker is RSSI-driven). Replaces the old `XMR_strongest_rssi_location`
            // vendor extension so downstream BTIDES tooling consumes it without special-casing.
            if (strongest != null) {
                put("GPSArray", buildJsonArray {
                    add(buildJsonObject {
                        putJsonObject("time") {
                            put("unix_time_milli", strongest.timeMs)
                            put("unix_time", strongest.timeMs / 1000L)
                        }
                        put("lat", strongest.lat)
                        put("lon", strongest.lng)
                        if (strongest.rssi != null) put("rssi", strongest.rssi)
                    })
                })
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
        // Sidecar index — one line per JSONL record: `<address> <byteOffset> <byteLen>\n`.
        // Used by [cachedGattForDevice] to seek directly into the main log instead of streaming.
        private const val INDEX_FILE_NAME = "btides/index.jsonl"
        // Soft cap on per-address in-memory GATT session buffer. Sized for the Connect-All
        // worst case (services + read responses + descriptor reads, well under 100 records).
        private const val MAX_GATT_SESSION_BUFFER = 1024
        // Channel back-pressure threshold. At 4096 records and a typical scan rate of
        // ~10/sec/device, the writer has ~7 minutes of headroom for a 1000-device environment
        // before suspending callers — long enough for any disk hiccup to recover.
        private const val WRITE_CHANNEL_CAPACITY = 4096
        // Flush thresholds — whichever fires first. 1024 records is one full channel drain;
        // 250 ms keeps the on-disk size visibly fresh in the Settings BTIDES log size display.
        private const val WRITER_FLUSH_RECORDS = 1024
        private const val WRITER_FLUSH_INTERVAL_MS = 250L
        // Sidecar index record-type tags. Reader filters to GATT-only when looking up cached
        // GATT — typical chatty devices have 99%+ ADV records that we'd otherwise pay the JSON
        // parse cost on for nothing.
        private const val RECORD_TYPE_GATT = "G"
        private const val RECORD_TYPE_ADV = "A"
        private const val RECORD_TYPE_SDP = "S"
        private const val RECORD_TYPE_EIR = "E"
        private const val RECORD_TYPE_HCI = "H"
        private const val RECORD_TYPE_OTHER = "O"
        const val EXPORT_FILE_NAME = "btides_log.btides"
        private const val BTIDES_DIR = "btides"
        private const val FIRST_CAPTURE_FILE_NAME = "btides_log.first_capture_ms"
        private const val ARCHIVE_LOG_EXT = "jsonl"
        private const val ARCHIVE_IDX_EXT = "idx.jsonl"
        private const val EXPORT_EXT = "btides"
        private const val MS_PER_DAY = 86_400_000L
        // Auto-rotate the active BTIDES log once it crosses this byte threshold. Sized to
        // keep merged exports comfortably under the BTIDALPOOL server's per-upload cap
        // (currently 50 MB) — the merged JSON output is roughly the same size as the source
        // jsonl for typical captures, so 40 MB of jsonl source ≈ 40-45 MB exported. With
        // logs bounded this small the multi-archive "Upload all" path produces lots of small
        // requests instead of one giant one — the server's `json.loads()` heap blowup
        // (~4-5x parsed-tree expansion) stays modest, and a transient network drop only
        // costs us one chunk worth of upload time.
        private const val MAX_ACTIVE_LOG_BYTES = 40L * 1024 * 1024
        // Short TTL: filter chip evaluation runs per scan-batch, but a fresh GATT enumeration
        // arrives much less often than scan batches. 5s makes the cache hot during
        // recompositions while still picking up new GATT records soon after they land.
        private const val GATT_ADDRESSES_TTL_MS = 5_000L
        private const val INDENT_UNIT = "  "
        // Cap on concurrent open per-device writers during the per-device temp-file routing
        // pass. With 64 active writers and an 8 KB BufferedWriter buffer each, peak buffer
        // memory is bounded at ~512 KB regardless of how many distinct devices the source
        // contains. Anecdotally a Motorola moto g play with ~867 distinct devices in a
        // 60 MB archive OOM'd at the unbounded prior implementation; this cap fixes it
        // without sacrificing throughput (most archives have temporally clustered records
        // per device, so the LRU re-open rate stays low).
        private const val MAX_OPEN_DEVICE_WRITERS = 64
        // Match the bdaddr + bdaddr_rand top-level fields without parsing the whole record. The
        // appendScan/appendGattRecord writers always emit them as the first two object keys, in
        // that order, on a single line — this regex is a fast first-pass router.
        private val KEY_REGEX = Regex("""\"bdaddr\"\s*:\s*\"([0-9A-Fa-f:]+)\"[^}]*?\"bdaddr_rand\"\s*:\s*(-?\d+)""")
    }
}
