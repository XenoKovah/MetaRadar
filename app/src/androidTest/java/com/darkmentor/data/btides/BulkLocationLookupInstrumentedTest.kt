package com.darkmentor.data.btides

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darkmentor.data.repo.LocationRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent

/**
 * Read-only real-database check for the upload enrichment bulk queries. It compares a sample of
 * addresses against the legacy per-address queries without changing locations or BTIDES data.
 */
@RunWith(AndroidJUnit4::class)
class BulkLocationLookupInstrumentedTest {

    @Test
    fun bulk_location_maps_match_per_address_queries() = runBlocking {
        val repository = KoinJavaComponent.getKoin().get<LocationRepository>()
        val allByAddress = repository.getAllRssiLocationsByAddress()
        val strongestByAddress = repository.getAllStrongestRssiLocations()

        allByAddress.keys.take(50).forEach { address ->
            val legacyLocations = repository.getAllLocationsByAddress(address)
            val bulkLocations = allByAddress[address].orEmpty()
            assertEquals(
                "location count differs for $address",
                legacyLocations.size,
                bulkLocations.size,
            )

            val legacyStrongest = repository.getStrongestRssiLocation(address)
            val bulkStrongest = strongestByAddress[address]
            assertEquals(
                "strongest RSSI differs for $address",
                legacyStrongest?.rssi,
                bulkStrongest?.rssi,
            )
        }

        assertTrue(
            "strongest map cannot contain an address absent from the full location map",
            strongestByAddress.keys.all { it in allByAddress },
        )
    }
}
