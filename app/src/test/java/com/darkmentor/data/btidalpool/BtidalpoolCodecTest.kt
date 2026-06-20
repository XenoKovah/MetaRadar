@file:OptIn(ExperimentalSerializationApi::class)

package com.darkmentor.data.btidalpool

import com.github.luben.zstd.Zstd
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests for [BtidalpoolCodec] — the "BTPL" frame + CBOR envelope + zstd round-trip used to
 * talk to the Rust BTIDALPOOL server (port 3568). These run the *real* zstd via the desktop
 * zstd-jni jar (a `testImplementation` dep; the app ships the @aar with Android-only natives), so a
 * regression in the header math, the byte-string encoding, or the snake_case wire keys is caught
 * off-device.
 */
class BtidalpoolCodecTest {

    private val cbor = Cbor { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `frame then decodeResponse round-trips an ok result`() {
        val bytes = cbor.encodeToByteArray(
            BtidalpoolCodec.WireResponse(result = "ok", message = "stored 2 records"),
        )
        val decoded = BtidalpoolCodec.decodeResponse(BtidalpoolCodec.frame(bytes))
        assertEquals("ok", decoded.result)
        assertEquals("stored 2 records", decoded.message)
    }

    @Test
    fun `frame then decodeResponse round-trips an err result with kind`() {
        val bytes = cbor.encodeToByteArray(
            BtidalpoolCodec.WireResponse(
                result = "err",
                kind = "duplicate_upload",
                message = "already have it",
            ),
        )
        val decoded = BtidalpoolCodec.decodeResponse(BtidalpoolCodec.frame(bytes))
        assertEquals("err", decoded.result)
        assertEquals("duplicate_upload", decoded.kind)
    }

    @Test
    fun `upload frame has BTPL header, exact declared length, and a byte-string payload`() {
        val json = """[{"bdaddr":"AA:BB:CC:DD:EE:FF"}]""".toByteArray(Charsets.UTF_8)
        val frame = BtidalpoolCodec.encodeUploadFrame(
            token = "tok-123",
            refreshToken = "ref-456",
            useTestDb = true,
            btidesJson = json,
        )

        // Header: "BTPL" + 0x01 + big-endian uint32 declared (uncompressed CBOR) length.
        assertEquals('B'.code.toByte(), frame[0])
        assertEquals('T'.code.toByte(), frame[1])
        assertEquals('P'.code.toByte(), frame[2])
        assertEquals('L'.code.toByte(), frame[3])
        assertEquals(1.toByte(), frame[4])
        val declaredLen =
            ((frame[5].toInt() and 0xFF) shl 24) or
                ((frame[6].toInt() and 0xFF) shl 16) or
                ((frame[7].toInt() and 0xFF) shl 8) or
                (frame[8].toInt() and 0xFF)

        // Decompress the payload; the declared length must be exact.
        val cborBytes = Zstd.decompress(frame.copyOfRange(9, frame.size), declaredLen)
        assertEquals(declaredLen, cborBytes.size)

        // The envelope decodes back with the byte string intact and the constant cmd present.
        val envelope = cbor.decodeFromByteArray<BtidalpoolCodec.UploadEnvelope>(cborBytes)
        assertEquals("tok-123", envelope.auth.token)
        assertEquals("ref-456", envelope.auth.refreshToken)
        assertTrue(envelope.auth.useTestDb)
        assertEquals("upload", envelope.payload.cmd)
        assertArrayEquals(json, envelope.payload.btidesJson)

        // Decoding through our own classes would hide a wrong @SerialName, so assert the literal
        // snake_case CBOR text keys the Rust server deserialises by name.
        val asLatin1 = String(cborBytes, Charsets.ISO_8859_1)
        for (key in listOf("auth", "payload", "cmd", "upload", "btides_json", "refresh_token", "use_test_db")) {
            assertTrue("CBOR should contain wire key/value '$key'", asLatin1.contains(key))
        }
    }

    @Test
    fun `decodeResponse rejects a frame with bad magic`() {
        try {
            BtidalpoolCodec.decodeResponse(ByteArray(20) { 0x7A })
            fail("expected IllegalArgumentException for bad BTPL magic")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `decodeResponse rejects a too-short frame`() {
        try {
            BtidalpoolCodec.decodeResponse(byteArrayOf(0x42, 0x54))
            fail("expected IllegalArgumentException for a sub-header-length frame")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
