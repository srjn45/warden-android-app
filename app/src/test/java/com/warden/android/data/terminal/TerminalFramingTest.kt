package com.warden.android.data.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalFramingTest {

    @Test
    fun `resize json is emitted for positive dims`() {
        assertEquals("{\"cols\":80,\"rows\":24}", TerminalFraming.resizeJson(80, 24))
    }

    @Test
    fun `resize json is suppressed for non-positive dims`() {
        // The server silently ignores zero-dim resizes; never send them.
        assertNull(TerminalFraming.resizeJson(0, 24))
        assertNull(TerminalFraming.resizeJson(80, 0))
        assertNull(TerminalFraming.resizeJson(-1, -1))
    }

    @Test
    fun `small input is a single frame`() {
        val data = ByteArray(1000) { it.toByte() }
        val chunks = TerminalFraming.chunkInput(data)
        assertEquals(1, chunks.size)
        assertArrayEquals(data, chunks[0])
    }

    @Test
    fun `input at the boundary stays one frame`() {
        val data = ByteArray(10) { it.toByte() }
        val chunks = TerminalFraming.chunkInput(data, max = 10)
        assertEquals(1, chunks.size)
    }

    @Test
    fun `large input splits into ordered frames under the limit`() {
        val data = ByteArray(25) { it.toByte() }
        val chunks = TerminalFraming.chunkInput(data, max = 10)

        assertEquals(3, chunks.size)
        assertEquals(10, chunks[0].size)
        assertEquals(10, chunks[1].size)
        assertEquals(5, chunks[2].size)
        // Reassembly must be lossless and order-preserving.
        assertArrayEquals(data, chunks.reduce { a, b -> a + b })
    }

    @Test
    fun `default max frame stays under the 1 MiB server limit`() {
        // The server closes the socket on a client binary frame > 1 MiB.
        assert(TerminalFraming.MAX_INPUT_FRAME < 1024 * 1024)
    }
}
