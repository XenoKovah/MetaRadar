package com.darkmentor.data.btidalpool

import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.random.Random

/**
 * Retry policy for BTIDALPOOL's explicit overload responses.
 *
 * HTTP status is authoritative: 429 is the caller quota and 503 is global capacity. A
 * Retry-After value is always honored as a floor, with positive jitter added so a room full of
 * phones does not wake at once. Missing or invalid headers use the bounded fallback schedule.
 */
object BtidalpoolOverloadRetry {
    const val MAX_ATTEMPTS = 7
    const val MAX_ELAPSED_MILLIS = 2L * 60 * 1_000
    private val FALLBACK_MILLIS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)

    data class Decision(
        val delayMillis: Long,
        val retryNumber: Int,
    )

    fun isOverload(httpStatus: Int): Boolean = httpStatus == 429 || httpStatus == 503

    /**
     * @param completedAttempts number of HTTP attempts already completed, starting at one.
     * @param jitterUnit deterministic value in [0, 1] for tests; production supplies random input.
     */
    fun decision(
        completedAttempts: Int,
        elapsedMillis: Long,
        retryAfterMillis: Long?,
        jitterUnit: Double,
    ): Decision? {
        if (completedAttempts >= MAX_ATTEMPTS || elapsedMillis >= MAX_ELAPSED_MILLIS) return null
        val base = retryAfterMillis?.takeIf { it > 0 }
            ?: FALLBACK_MILLIS[(completedAttempts - 1).coerceIn(0, FALLBACK_MILLIS.lastIndex)]
        val jitterCap = min(1_000L, (base / 4).coerceAtLeast(1L))
        // Deliberately positive even when the random source returns zero.
        val jitter = 1L + (jitterUnit.coerceIn(0.0, 1.0) * (jitterCap - 1L)).toLong()
        val wait = base + jitter
        if (elapsedMillis + wait > MAX_ELAPSED_MILLIS) return null
        return Decision(wait, completedAttempts)
    }

    fun parseRetryAfterMillis(raw: String?, nowMillis: Long): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { seconds ->
            if (seconds <= 0 || seconds > Long.MAX_VALUE / 1_000L) return null
            return seconds * 1_000L
        }
        return runCatching {
            val at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            (at.toInstant().toEpochMilli() - nowMillis).takeIf { it > 0 }
        }.getOrNull()
    }
}

internal interface BtidalpoolRetryRuntime {
    fun wallClockMillis(): Long
    fun monotonicMillis(): Long
    fun jitterUnit(): Double
    suspend fun sleep(delayMillis: Long)
}

internal object SystemBtidalpoolRetryRuntime : BtidalpoolRetryRuntime {
    override fun wallClockMillis(): Long = System.currentTimeMillis()
    override fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
    override fun jitterUnit(): Double = Random.nextDouble()
    override suspend fun sleep(delayMillis: Long) = delay(delayMillis)
}
