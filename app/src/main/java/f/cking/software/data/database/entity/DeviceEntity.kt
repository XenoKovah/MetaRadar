package f.cking.software.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Index on `last_detect_time_ms` accelerates `getByLastDetectTime` (called on every batch tick
// via DevicesRepository.notifyLastBatchListener). Without it the query is a full table scan
// over M=200k+ rows; with it the per-batch cost is bounded by the recently-seen window.
@Entity(
    tableName = "device",
    indices = [Index(name = "index_device_last_detect_time_ms", value = ["last_detect_time_ms"])],
)
data class DeviceEntity(
    @PrimaryKey @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "last_detect_time_ms") val lastDetectTimeMs: Long,
    @ColumnInfo(name = "first_detect_time_ms") val firstDetectTimeMs: Long,
    @ColumnInfo(name = "detect_count") val detectCount: Int,
    @ColumnInfo(name = "custom_name") val customName: String? = null,
    // Orphaned: the favorites feature was removed in this branch but the column stays so we
    // don't need a Room migration. Always written as false; never read by domain code.
    @ColumnInfo(name = "favorite") val favorite: Boolean = false,
    @ColumnInfo(name = "manufacturer_id") val manufacturerId: Int? = null,
    @ColumnInfo(name = "manufacturer_name") val manufacturerName: String? = null,
    @ColumnInfo(name = "last_seen_rssi") val lastSeenRssi: Int? = null,
    @ColumnInfo(name = "system_address_type") val systemAddressType: Int? = null,
    @ColumnInfo(name = "device_class") val deviceClass: Int? = null,
    @ColumnInfo(name = "is_paired") val isPaired: Boolean = false,
    @ColumnInfo(name = "service_uuids", defaultValue = "") val serviceUuids: List<String>,
    @ColumnInfo(name = "row_data_encoded") val rowDataEncoded: String? = null,
    @ColumnInfo(name = "metadata") val metadata: String? = null,
    @ColumnInfo(name = "is_connectable") val isConnectable: Boolean = false,
    // Stores Transport.ordinal — 0=LE, 1=BREDR, 2=DUAL. Default 0 (LE) is the historical
    // fallback for newly-inserted rows whose transport hasn't yet been classified by a real
    // scan observation.
    @ColumnInfo(name = "transport", defaultValue = "0") val transport: Int = 0,
    // SDP service-class UUIDs returned by BluetoothDevice.fetchUuidsWithSdp(); same
    // serialization shape as `service_uuids`.
    @ColumnInfo(name = "sdp_uuids", defaultValue = "") val sdpUuids: List<String> = emptyList(),
    // Captured from GATT 0x2A29 (Manufacturer Name String) during Connect All / device-details
    // GATT enumeration. Display fallback under "Manufacturer" when no MSD-derived name is
    // available — see DeviceData.resolvedManufacturerName.
    @ColumnInfo(name = "gatt_manufacturer_name") val gattManufacturerName: String? = null,
)