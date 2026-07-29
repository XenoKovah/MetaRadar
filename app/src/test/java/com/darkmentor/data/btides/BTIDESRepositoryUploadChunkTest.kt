package com.darkmentor.data.btides

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BTIDESRepositoryUploadChunkTest {
    private fun context(tempDir: File): Context = mockk<Context>().also {
        every { it.cacheDir } returns tempDir
        every { it.filesDir } returns tempDir
    }

    @Test
    fun `export splits on actual serialized bytes and every chunk remains valid JSON`() = runBlocking {
        val dir = Files.createTempDirectory("btides_chunks").toFile()
        try {
            val source = File(dir, "source.jsonl").apply {
                writeText(
                    (1..6).joinToString("\n", postfix = "\n") { n ->
                        val suffix = n.toString().padStart(2, '0')
                        """{"bdaddr":"AA:AA:AA:AA:AA:$suffix","bdaddr_rand":1,"AdvChanArray":[{"type":0,"data":"${"x".repeat(40)}"}]}"""
                    },
                )
            }
            val output = File(dir, "out")
            val chunks = BTIDESRepository(context(dir)).exportUploadChunks(
                outputDir = output,
                sourceFile = source,
                targetChunkBytes = 260,
                hardMaxChunkBytes = 1_024,
            )

            assertTrue("tiny target should force more than one chunk", chunks.size > 1)
            assertEquals(6, chunks.sumOf { it.deviceCount })
            chunks.forEach { chunk ->
                assertTrue("${chunk.file.name} exceeds hard cap", chunk.file.length() <= 1_024)
                val array = Json.parseToJsonElement(chunk.file.readText()).jsonArray
                assertEquals(chunk.deviceCount, array.size)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `one device larger than the hard server cap fails instead of creating an invalid request`() =
        runBlocking {
            val dir = Files.createTempDirectory("btides_oversize_device").toFile()
            try {
                val source = File(dir, "source.jsonl").apply {
                    writeText(
                        """{"bdaddr":"AA:AA:AA:AA:AA:AA","bdaddr_rand":1,"AdvChanArray":[{"type":0,"data":"${"x".repeat(1_000)}"}]}""" +
                            "\n",
                    )
                }
                val failure = runCatching {
                    BTIDESRepository(context(dir)).exportUploadChunks(
                        outputDir = File(dir, "out"),
                        sourceFile = source,
                        targetChunkBytes = 200,
                        hardMaxChunkBytes = 400,
                    )
                }.exceptionOrNull()

                assertTrue(failure is IllegalArgumentException)
                assertTrue(failure?.message.orEmpty().contains("server accepts at most"))
            } finally {
                dir.deleteRecursively()
            }
        }
}
