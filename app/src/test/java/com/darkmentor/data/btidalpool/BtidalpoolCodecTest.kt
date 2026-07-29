package com.darkmentor.data.btidalpool

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** JVM regression tests for the v4-only BTPL frame and unified command set. */
class BtidalpoolCodecTest {

    @Test
    fun `v4 response round-trips success and structured error`() {
        val ok = BtidalpoolCodec.decodeV4Response(
            BtidalpoolCodec.encodeV4ResponseForTest(
                BtidalpoolCodec.V4WireResponse(
                    result = "ok",
                    message = "stored 2 records",
                ),
            ),
        )
        assertEquals("ok", ok.result)
        assertEquals("stored 2 records", ok.message)

        val error = BtidalpoolCodec.decodeV4Response(
            BtidalpoolCodec.encodeV4ResponseForTest(
                BtidalpoolCodec.V4WireResponse(
                    result = "err",
                    kind = "duplicate_upload",
                    message = "already have it",
                ),
            ),
        )
        assertEquals("err", error.result)
        assertEquals("duplicate_upload", error.kind)
    }

    @Test
    fun `v4 decoder rejects bad magic short frames and old versions`() {
        for (invalid in listOf(ByteArray(20) { 0x7A }, byteArrayOf(0x42, 0x54))) {
            try {
                BtidalpoolCodec.decodeV4Response(invalid)
                fail("expected malformed BTPL frame to be rejected")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }

        for (oldVersion in 1..3) {
            val frame = BtidalpoolCodec.encodeV4ResponseForTest(
                BtidalpoolCodec.V4WireResponse(result = "ok"),
            )
            frame[4] = oldVersion.toByte()
            try {
                BtidalpoolCodec.decodeV4Response(frame)
                fail("expected BTPL version $oldVersion to be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("expected 4"))
            }
        }
    }

    @Test
    fun `v4 frames cover the unified command set with version four`() {
        val frames = listOf(
            "create_session" to BtidalpoolCodec.encodeV4CreateSessionFrame("google"),
            "upload" to BtidalpoolCodec.encodeV4UploadFrame("session", "[]".toByteArray(), true),
            "check_hash" to BtidalpoolCodec.encodeV4CheckHashFrame("session", "a".repeat(40)),
            "legacy_query" to BtidalpoolCodec.encodeV4LegacyQueryFrame(
                "session",
                BtidalpoolCodec.QueryParams(),
                true,
            ),
            "native_query" to BtidalpoolCodec.encodeV4NativeQueryFrame(
                "session",
                BtidalpoolCodec.QueryParams(),
                true,
            ),
            "manifest" to BtidalpoolCodec.encodeV4ManifestFrame(
                "session",
                "a".repeat(64),
                2,
                listOf("b".repeat(64)),
                true,
            ),
            "put_chunk" to BtidalpoolCodec.encodeV4PutChunkFrame(
                "session",
                "upload",
                0,
                byteArrayOf(1, 2),
            ),
            "status" to BtidalpoolCodec.encodeV4StatusFrame("session", "upload"),
            "finalize" to BtidalpoolCodec.encodeV4FinalizeFrame("session", "upload"),
        )

        frames.forEach { (expectedCommand, frame) ->
            assertEquals(4.toByte(), frame[4])
            assertEquals(
                expectedCommand,
                BtidalpoolCodec.decodeV4RequestForTest(frame).payload.cmd,
            )
        }
    }

    @Test
    fun `v4 native response preserves bytes and unsigned range as text`() {
        val response = BtidalpoolCodec.V4WireResponse(
            result = "native_query_result",
            query = BtidalpoolCodec.V4NativeQueryResult(
                devices = listOf(
                    BtidalpoolCodec.V4NativeDevice(
                        bdaddr = "AA:BB:CC:DD:EE:FF",
                        tables = mapOf(
                            "table" to BtidalpoolCodec.V4NativeTable(
                                columns = listOf("bytes", "unsigned"),
                                rows = listOf(
                                    listOf(
                                        BtidalpoolCodec.V4DbValue(
                                            kind = "bytes",
                                            bytes = byteArrayOf(0, 0xFF.toByte()),
                                        ),
                                        BtidalpoolCodec.V4DbValue(
                                            kind = "unsigned",
                                            unsigned = "18446744073709551615",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                totalRows = 1,
                rowLimit = 100,
                truncated = false,
            ),
        )

        val decoded = BtidalpoolCodec.decodeV4Response(
            BtidalpoolCodec.encodeV4ResponseForTest(response),
        )
        val row = decoded.query!!.devices.single().tables.getValue("table").rows.single()
        assertArrayEquals(byteArrayOf(0, 0xFF.toByte()), row[0].bytes)
        assertEquals("18446744073709551615", row[1].unsigned)
    }
}
