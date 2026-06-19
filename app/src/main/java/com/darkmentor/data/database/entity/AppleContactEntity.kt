package com.darkmentor.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Index on `associated_address` accelerates `getByAddress` and `getByAddresses` — both called
// on every batch tick via DevicesRepository.toDomainWithAirDrop. Without it each lookup was a
// full table scan over M_contacts rows.
@Entity(
    tableName = "apple_contacts",
    indices = [Index(name = "index_apple_contacts_associated_address", value = ["associated_address"])],
)
data class AppleContactEntity(
    @PrimaryKey @ColumnInfo(name = "sha_256") val sha256: Int,
    @ColumnInfo(name = "associated_address") val associatedAddress: String,
    @ColumnInfo(name = "first_detect_time_ms") val firstDetectTimeMs: Long,
    @ColumnInfo(name = "last_detect_time_ms") val lastDetectTimeMs: Long,
)