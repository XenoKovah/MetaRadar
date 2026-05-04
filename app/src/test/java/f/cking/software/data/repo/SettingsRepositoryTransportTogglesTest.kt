package f.cking.software.data.repo

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

/**
 * Verifies the two new transport-discovery toggles default to true (so the app's behaviour
 * matches today's LE-only flow plus opt-in BR/EDR), persist independently, and write back
 * exactly the keys the rest of the codebase reads on subsequent launches.
 */
class SettingsRepositoryTransportTogglesTest {

    /** Minimal in-memory SharedPreferences stand-in. We only need Boolean read/write here. */
    private fun fakePrefs(): Pair<SharedPreferences, MutableMap<String, Any>> {
        val backing = mutableMapOf<String, Any>()
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.getBoolean(any(), any()) } answers {
            val key = firstArg<String>()
            (backing[key] as? Boolean) ?: secondArg<Boolean>()
        }
        every { prefs.getString(any(), any()) } answers { backing[firstArg<String>()] as? String ?: secondArg() }
        every { prefs.getInt(any(), any()) } answers { backing[firstArg<String>()] as? Int ?: secondArg() }
        every { prefs.getLong(any(), any()) } answers { backing[firstArg<String>()] as? Long ?: secondArg() }
        every { prefs.edit() } returns editor
        val keySlot = slot<String>()
        val valueSlot = slot<Boolean>()
        every { editor.putBoolean(capture(keySlot), capture(valueSlot)) } answers {
            backing[keySlot.captured] = valueSlot.captured
            editor
        }
        every { editor.apply() } answers {}
        every { editor.commit() } returns true
        return prefs to backing
    }

    @Test
    fun `default toggles are both enabled out of the box`() {
        val (prefs, _) = fakePrefs()
        val repo = SettingsRepository(prefs)
        assertTrue("LE default should be true", repo.getDiscoverLeEnabled())
        assertTrue("BR/EDR default should be true", repo.getDiscoverBrEdrEnabled())
    }

    @Test
    fun `toggling LE persists across reads`() {
        val (prefs, backing) = fakePrefs()
        val repo = SettingsRepository(prefs)
        repo.setDiscoverLeEnabled(false)
        assertEquals(false, repo.getDiscoverLeEnabled())
        // Sanity check that backing storage actually got written under a stable key.
        assertTrue(
            "expected LE key in backing store, got keys=${backing.keys}",
            backing.keys.any { "le_enabled" in it },
        )
    }

    @Test
    fun `toggling BR_EDR persists across reads`() {
        val (prefs, backing) = fakePrefs()
        val repo = SettingsRepository(prefs)
        repo.setDiscoverBrEdrEnabled(false)
        assertEquals(false, repo.getDiscoverBrEdrEnabled())
        assertTrue(
            "expected BR/EDR key in backing store, got keys=${backing.keys}",
            backing.keys.any { "br_edr_enabled" in it || "br/edr" in it.lowercase() || "brEdr" in it },
        )
    }

    @Test
    fun `toggles are independent`() {
        val (prefs, _) = fakePrefs()
        val repo = SettingsRepository(prefs)
        repo.setDiscoverLeEnabled(false)
        // BR/EDR was never touched; should still be at the default.
        assertEquals(true, repo.getDiscoverBrEdrEnabled())
        repo.setDiscoverBrEdrEnabled(false)
        // LE was set to false earlier; flipping BR/EDR shouldn't have flipped LE back.
        assertEquals(false, repo.getDiscoverLeEnabled())
        assertEquals(false, repo.getDiscoverBrEdrEnabled())
    }

    @Test
    fun `setting one toggle does not consume default for the other`() {
        val (prefs, _) = fakePrefs()
        val repo = SettingsRepository(prefs)
        // Set LE explicitly to true (its default) — should still leave BR/EDR at default.
        repo.setDiscoverLeEnabled(true)
        assertEquals(true, repo.getDiscoverLeEnabled())
        assertEquals(true, repo.getDiscoverBrEdrEnabled())
    }
}
