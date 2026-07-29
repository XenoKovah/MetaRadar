package com.darkmentor.data.btidalpool

import android.content.SharedPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BtidalpoolAuthRepositoryRefreshTest {

    @Test
    fun `transient refresh failure preserves cached credentials`() = runBlocking {
        val prefs = storedPrefs()
        val client = mockk<BtidalpoolClient>()
        coEvery { client.refreshToken("refresh-token") } returns
            BtidalpoolClient.TokenRefreshResult.TransientFailure(
                httpCode = 503,
                message = "helper unavailable",
                retryAfterMillis = 30_000,
            )
        val repository = BtidalpoolAuthRepository(
            sharedPreferences = prefs,
            client = client,
            legacyPrefs = mockk(relaxed = true),
        )

        val result = repository.refresh()

        assertTrue(result is BtidalpoolAuthRepository.RefreshOutcome.TransientFailure)
        assertEquals("access-token", repository.current()?.token)
        // No credential-removal edit should occur for a timeout/429/5xx.
        verify(exactly = 0) { prefs.edit() }
    }

    private fun storedPrefs(): SharedPreferences = mockk<SharedPreferences>(relaxed = true).also { prefs ->
        every { prefs.contains("btidalpool_token") } returns true
        every { prefs.getString("btidalpool_token", null) } returns "access-token"
        every { prefs.getString("btidalpool_refresh_token", null) } returns "refresh-token"
        every { prefs.getString("btidalpool_email", null) } returns "user@example.com"
    }
}
