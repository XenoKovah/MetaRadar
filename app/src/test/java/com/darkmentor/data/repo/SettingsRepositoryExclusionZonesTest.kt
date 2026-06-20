package com.darkmentor.data.repo

import android.content.SharedPreferences
import com.darkmentor.domain.model.ExclusionZone
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence round-trip for [SettingsRepository.getExclusionZones]/[setExclusionZones] over an
 * in-memory SharedPreferences fake (string put/get/remove). Verifies polymorphic JSON encoding,
 * the max-3 cap, and that an empty list clears the key so the upload path stays a no-op.
 */
class SettingsRepositoryExclusionZonesTest {

    private fun repoWithBacking(): Pair<SettingsRepository, MutableMap<String, Any?>> {
        val backing = mutableMapOf<String, Any?>()
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.getString(any(), any()) } answers { backing[firstArg()] as? String ?: secondArg() }
        every { prefs.getBoolean(any(), any()) } answers { backing[firstArg()] as? Boolean ?: secondArg() }
        every { prefs.getLong(any(), any()) } answers { backing[firstArg()] as? Long ?: secondArg() }
        every { prefs.getInt(any(), any()) } answers { backing[firstArg()] as? Int ?: secondArg() }
        every { prefs.edit() } returns editor
        val sKey = slot<String>()
        val sVal = slot<String>()
        every { editor.putString(capture(sKey), capture(sVal)) } answers { backing[sKey.captured] = sVal.captured; editor }
        val rKey = slot<String>()
        every { editor.remove(capture(rKey)) } answers { backing.remove(rKey.captured); editor }
        every { editor.apply() } answers {}
        every { editor.commit() } returns true
        return SettingsRepository(prefs) to backing
    }

    @Test
    fun `default is empty list`() {
        val (repo, _) = repoWithBacking()
        assertTrue(repo.getExclusionZones().isEmpty())
    }

    @Test
    fun `round-trips a circle and a square with polymorphic type tags`() {
        val (repo, backing) = repoWithBacking()
        val zones = listOf(
            ExclusionZone.Circle(centerLat = 40.0, centerLng = -75.0, radiusMeters = 100.0),
            ExclusionZone.Square(centerLat = 10.0, centerLng = 20.0, halfSizeMeters = 50.0),
        )
        repo.setExclusionZones(zones)
        val raw = backing["key_exclusion_zones"] as String
        assertTrue("circle discriminator present", raw.contains("circle"))
        assertTrue("square discriminator present", raw.contains("square"))
        assertEquals(zones, repo.getExclusionZones())
    }

    @Test
    fun `caps at three zones`() {
        val (repo, _) = repoWithBacking()
        val four = (1..4).map { ExclusionZone.Circle(it.toDouble(), it.toDouble(), 10.0) }
        repo.setExclusionZones(four)
        assertEquals(3, repo.getExclusionZones().size)
    }

    @Test
    fun `empty list removes the key`() {
        val (repo, backing) = repoWithBacking()
        repo.setExclusionZones(listOf(ExclusionZone.Circle(1.0, 2.0, 10.0)))
        assertTrue(backing.containsKey("key_exclusion_zones"))
        repo.setExclusionZones(emptyList())
        assertFalse(backing.containsKey("key_exclusion_zones"))
        assertNull(backing["key_exclusion_zones"])
    }
}
