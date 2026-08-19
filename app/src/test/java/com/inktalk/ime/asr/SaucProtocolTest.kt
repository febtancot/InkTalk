package com.inktalk.ime.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class SaucProtocolTest {
    @Test
    fun fullClientRequestUsesPositiveSequenceAndJsonGzip() {
        val json = "{\"audio\":{\"format\":\"pcm\"}}"
        val frame = SaucProtocol.encodeFullClientRequest(json, sequence = 1)

        assertEquals(0x11, frame[0].toInt() and 0xFF)
        assertEquals(0x11, frame[1].toInt() and 0xFF)
        assertEquals(0x11, frame[2].toInt() and 0xFF)
        assertEquals(0, frame[3].toInt() and 0xFF)
        assertEquals(1, ByteBuffer.wrap(frame, 4, 4).order(ByteOrder.BIG_ENDIAN).int)
        val payloadSize = ByteBuffer.wrap(frame, 8, 4).order(ByteOrder.BIG_ENDIAN).int
        assertEquals(frame.size - 12, payloadSize)
        val decoded = GZIPInputStream(
            ByteArrayInputStream(frame, 12, payloadSize)
        ).use { it.readBytes() }
        assertArrayEquals(json.toByteArray(), decoded)
    }

    @Test
    fun lastAudioFrameUsesNegativeSequenceAndValidGzipPayload() {
        val frame = SaucProtocol.encodeAudioRequest(
            ByteArray(0),
            sequence = 7,
            isLast = true,
        )

        assertEquals(0x23, frame[1].toInt() and 0xFF)
        assertEquals(0x01, frame[2].toInt() and 0xFF)
        assertEquals(-7, ByteBuffer.wrap(frame, 4, 4).order(ByteOrder.BIG_ENDIAN).int)
        val payloadSize = ByteBuffer.wrap(frame, 8, 4).order(ByteOrder.BIG_ENDIAN).int
        assertEquals(frame.size - 12, payloadSize)
        val decoded = GZIPInputStream(
            ByteArrayInputStream(frame, 12, payloadSize)
        ).use { it.readBytes() }
        assertArrayEquals(ByteArray(0), decoded)
    }

    @Test
    fun regularAudioFrameUsesPositiveSequence() {
        val frame = SaucProtocol.encodeAudioRequest(
            byteArrayOf(1, 2, 3),
            sequence = 2,
            isLast = false,
        )

        assertEquals(0x21, frame[1].toInt() and 0xFF)
        assertEquals(2, ByteBuffer.wrap(frame, 4, 4).order(ByteOrder.BIG_ENDIAN).int)
    }

    @Test
    fun decodesOfficialSequencedFinalResponse() {
        val payload = gzip("{\"result\":{\"text\":\"hello\"}}".toByteArray())
        val frame = ByteBuffer.allocate(12 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(0x11)
            .put(0x93.toByte())
            .put(0x11)
            .put(0)
            .putInt(-9)
            .putInt(payload.size)
            .put(payload)
            .array()

        val response = SaucProtocol.decodeServerMessage(frame) as SaucProtocol.ServerMessage.Response

        assertEquals(-9, response.sequence)
        assertEquals(true, response.isLast)
        assertEquals(null, response.event)
        assertEquals("{\"result\":{\"text\":\"hello\"}}", response.rawJson)
    }

    @Test
    fun decodesSequenceAndEventFlagsTogether() {
        val payload = gzip("{\"result\":{\"text\":\"\"}}".toByteArray())
        val frame = ByteBuffer.allocate(16 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(0x11)
            .put(0x95.toByte())
            .put(0x11)
            .put(0)
            .putInt(2)
            .putInt(450)
            .putInt(payload.size)
            .put(payload)
            .array()

        val response = SaucProtocol.decodeServerMessage(frame) as SaucProtocol.ServerMessage.Response

        assertEquals(2, response.sequence)
        assertEquals(450, response.event)
        assertEquals(false, response.isLast)
    }

    @Test
    fun keepsServerErrorCodeSignedAndDecompressesMessage() {
        val payload = gzip("bad request".toByteArray())
        val frame = ByteBuffer.allocate(12 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(0x11)
            .put(0xF0.toByte())
            .put(0x01)
            .put(0)
            .putInt(-450)
            .putInt(payload.size)
            .put(payload)
            .array()

        val error = SaucProtocol.decodeServerMessage(frame) as SaucProtocol.ServerMessage.Error

        assertEquals(-450L, error.code)
        assertEquals("bad request", error.message)
    }

    @Test
    fun rejectsPayloadSizeBeyondFrameBoundary() {
        val frame = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .put(0x11)
            .put(0x91.toByte())
            .put(0x11)
            .put(0)
            .putInt(1)
            .putInt(99)
            .array()

        val error = SaucProtocol.decodeServerMessage(frame) as SaucProtocol.ServerMessage.Error

        assertEquals(0L, error.code)
        assertEquals("invalid payload size: 99", error.message)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(data) }
        return output.toByteArray()
    }
}
