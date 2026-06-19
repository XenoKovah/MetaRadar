package com.darkmentor.domain.interactor

import com.darkmentor.data.helpers.CluesRepository
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

/**
 * Regression coverage for the boundary-condition off-by-one fixed in
 * `VendorIdentifier.parseAdvFrames`. Each AD frame is `[length] [type] [data...]`; the
 * frame at offset `i` spans `[i, i + length]` inclusive — `length + 1` bytes. The pre-fix
 * `copyOfRange(i + 2, i + 1 + length + 1)` reached one byte past that, so a 62-byte
 * advertisement whose final frame ended exactly at the buffer boundary tripped
 * `IndexOutOfBoundsException` on the StateFlow collector that runs candidate filtering.
 *
 * `parseAdvFrames` is `internal @VisibleForTesting` so the regression can be exercised
 * directly without rigging up android.util.Base64 stubs in JVM unit tests.
 */
class VendorIdentifierParseAdvFramesTest {

    // parseAdvFrames doesn't touch CluesRepository — relaxed mock keeps construction simple.
    private val clues: CluesRepository = mockk<CluesRepository>(relaxed = true)
    private val identifier = VendorIdentifier(clues)

    @Test
    fun `frame ending exactly at buffer boundary does not throw`() {
        // Construct a 62-byte advertisement whose final frame's data ends at index 61
        // (the last valid byte). Pre-fix this triggered IndexOutOfBoundsException; post-fix
        // it parses cleanly and returns both frames.
        // Frame 1: 3 bytes — [02 (length=2), 01 (Flags type), 06 (LE General Discoverable)].
        // Frame 2: 59 bytes — [3A (length=58 covering type+data), FF (MSD type),
        //   <2-byte company id> + 55 payload bytes].
        // Total: 3 + 59 = 62. Last byte at index 61.
        val frame1 = byteArrayOf(0x02, 0x01, 0x06)
        val frame2 = byteArrayOf(0x3A.toByte(), 0xFF.toByte()) +
            ByteArray(57) { (it and 0xFF).toByte() } // 2-byte company id + 55 data = 57 bytes
        val raw = frame1 + frame2
        check(raw.size == 62) { "test setup wrong: raw.size=${raw.size}" }

        val frames = identifier.parseAdvFrames(raw)
        assertEquals(2, frames.size)
        // Frame 1 was a Flags AD (type=0x01) with 1 data byte (length-1).
        assertEquals(0x01.toByte(), frames[0].type)
        assertEquals(1, frames[0].data.size)
        // Frame 2 was MSD (type=0xFF). length=58 → 57 bytes of data.
        assertEquals(0xFF.toByte(), frames[1].type)
        assertEquals(57, frames[1].data.size)
    }

    @Test
    fun `length byte extending beyond buffer breaks safely`() {
        // length=50 claims 50 bytes after the length byte, but only 5 bytes follow.
        val raw = byteArrayOf(50.toByte(), 0xFF.toByte(), 0x4C.toByte(), 0x00, 0x02, 0x15)
        val frames = identifier.parseAdvFrames(raw)
        // Truncated frame must be dropped, not throw.
        assertTrue("expected zero frames from truncated buffer, got ${frames.size}", frames.isEmpty())
    }

    @Test
    fun `zero length byte terminates parsing`() {
        // Per BT spec, length=0 marks the end of meaningful AD records (often padding); the
        // parser must stop there without trying to read a type byte.
        val raw = byteArrayOf(0x02, 0x01, 0x06, 0x00, 0x00, 0x00, 0x00)
        val frames = identifier.parseAdvFrames(raw)
        assertEquals(1, frames.size)
        assertEquals(0x01.toByte(), frames[0].type)
    }

    @Test
    fun `well formed multi-frame advertisement parses without throwing`() {
        // Two complete frames, all bytes within bounds. Happy path — no boundary games.
        // Frame A: 02 01 06 (Flags = LE General Discoverable)
        // Frame B: 03 02 0F 18 (Incomplete List of 16-bit Service UUIDs = 0x180F Battery)
        val raw = byteArrayOf(0x02, 0x01, 0x06, 0x03, 0x02, 0x0F, 0x18)
        val frames = identifier.parseAdvFrames(raw)
        assertEquals(2, frames.size)
        // Frame B has 2 bytes of UUID data (0F 18 little-endian = 0x180F).
        assertEquals(0x02.toByte(), frames[1].type)
        assertEquals(2, frames[1].data.size)
        assertEquals(0x0F.toByte(), frames[1].data[0])
        assertEquals(0x18.toByte(), frames[1].data[1])
    }

    @Test
    fun `empty advertisement returns no frames`() {
        val frames = identifier.parseAdvFrames(byteArrayOf())
        assertTrue(frames.isEmpty())
    }
}
