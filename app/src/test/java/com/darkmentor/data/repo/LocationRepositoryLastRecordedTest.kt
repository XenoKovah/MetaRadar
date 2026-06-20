package com.darkmentor.data.repo

import com.darkmentor.data.database.AppDatabase
import com.darkmentor.data.database.dao.LocationDao
import com.darkmentor.data.database.entity.LocationEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LocationRepository.getLastRecordedLocation] — the query the exclusion-zone map uses to seed its
 * camera. Verifies it returns the DAO's newest row mapped to a domain model, and null when the
 * `location` table is empty.
 */
class LocationRepositoryLastRecordedTest {

    private fun repoWith(dao: LocationDao): LocationRepository {
        val db = mockk<AppDatabase>()
        every { db.locationDao() } returns dao
        return LocationRepository(db)
    }

    @Test
    fun `maps the latest entity to a domain model`() = runBlocking {
        val dao = mockk<LocationDao>()
        every { dao.getLatestLocation() } returns LocationEntity(time = 1_700_000_000_000L, lat = 41.5, lng = -71.3)

        val result = repoWith(dao).getLastRecordedLocation()!!

        assertEquals(41.5, result.lat, 1e-9)
        assertEquals(-71.3, result.lng, 1e-9)
        assertEquals(1_700_000_000_000L, result.time)
    }

    @Test
    fun `returns null when nothing has been recorded`() = runBlocking {
        val dao = mockk<LocationDao>()
        every { dao.getLatestLocation() } returns null

        assertNull(repoWith(dao).getLastRecordedLocation())
    }
}
