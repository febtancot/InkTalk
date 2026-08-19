package com.inktalk.ime.asr

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 火山引擎 SAUC WebSocket 二进制协议编解码。
 * 文档：https://docs.volcengine.com/docs/6561/2630027
 *
 * 帧结构：4 字节 header（可扩展）+ [sequence] + payload size + payload，整数一律大端。
 */
object SaucProtocol {

    // Message type
    const val MSG_FULL_CLIENT_REQUEST = 0x1
    const val MSG_AUDIO_ONLY_REQUEST = 0x2
    const val MSG_FULL_SERVER_RESPONSE = 0x9
    const val MSG_ERROR = 0xF

    // Message type specific flags
    const val FLAG_NONE = 0x0
    const val FLAG_POS_SEQUENCE = 0x1
    const val FLAG_NEG_SEQUENCE = 0x2
    const val FLAG_NEG_WITH_SEQUENCE = 0x3
    const val FLAG_EVENT = 0x4

    // Serialization
    const val SER_RAW = 0x0
    const val SER_JSON = 0x1

    // Compression
    const val COMP_NONE = 0x0
    const val COMP_GZIP = 0x1

    private const val VERSION_AND_HEADER: Byte = 0x11 // version=1, header size=1 (4 bytes)

    /** 服务端消息。 */
    sealed interface ServerMessage {
        /** 识别结果响应。 */
        data class Response(
            val sequence: Int?,
            val event: Int?,
            val isLast: Boolean,
            val json: JSONObject,
            val rawJson: String,
        ) : ServerMessage

        /** 服务端错误帧。 */
        data class Error(val code: Long, val message: String) : ServerMessage
    }

    /** 编码 full client request（首帧，JSON 参数）。 */
    fun encodeFullClientRequest(
        json: String,
        sequence: Int,
        compress: Boolean = true,
    ): ByteArray {
        require(sequence > 0) { "full request sequence must be positive" }
        val payload = json.toByteArray(Charsets.UTF_8)
        return encode(
            MSG_FULL_CLIENT_REQUEST,
            FLAG_POS_SEQUENCE,
            SER_JSON,
            compress,
            sequence,
            payload,
        )
    }

    /** 编码 audio only request。尾包使用负序号通知服务端音频结束。 */
    fun encodeAudioRequest(
        pcm: ByteArray,
        sequence: Int,
        isLast: Boolean,
        compress: Boolean = true,
    ): ByteArray {
        require(sequence > 0) { "audio request sequence must be positive" }
        val flags = if (isLast) FLAG_NEG_WITH_SEQUENCE else FLAG_POS_SEQUENCE
        val wireSequence = if (isLast) -sequence else sequence
        return encode(MSG_AUDIO_ONLY_REQUEST, flags, SER_RAW, compress, wireSequence, pcm)
    }

    private fun encode(
        msgType: Int,
        flags: Int,
        serialization: Int,
        compress: Boolean,
        sequence: Int,
        payload: ByteArray,
    ): ByteArray {
        // 官方客户端即使尾包 payload 为空，也会发送合法的 GZIP 数据。
        val actuallyCompressed = compress
        val body = if (actuallyCompressed) gzip(payload) else payload
        val comp = if (actuallyCompressed) COMP_GZIP else COMP_NONE
        val out = ByteBuffer.allocate(4 + 4 + 4 + body.size).order(ByteOrder.BIG_ENDIAN)
        out.put(VERSION_AND_HEADER)
        out.put(((msgType shl 4) or flags).toByte())
        out.put(((serialization shl 4) or comp).toByte())
        out.put(0) // reserved
        out.putInt(sequence)
        out.putInt(body.size)
        out.put(body)
        return out.array()
    }

    /** 解码服务端下发的一帧。 */
    fun decodeServerMessage(data: ByteArray): ServerMessage {
        if (data.size < 4) return ServerMessage.Error(0, "frame too short: " + data.size + " bytes")
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val version = (buf.get(0).toInt() shr 4) and 0x0F
        val headerSize = (buf.get(0).toInt() and 0x0F) * 4
        val msgType = (buf.get(1).toInt() shr 4) and 0x0F
        val flags = buf.get(1).toInt() and 0x0F
        val serialization = (buf.get(2).toInt() shr 4) and 0x0F
        val compression = buf.get(2).toInt() and 0x0F
        if (version != 1) return ServerMessage.Error(0, "unsupported protocol version: $version")
        if (compression != COMP_NONE && compression != COMP_GZIP) {
            return ServerMessage.Error(0, "unsupported compression: $compression")
        }
        if (headerSize < 4 || headerSize > data.size) {
            return ServerMessage.Error(0, "invalid header size: $headerSize")
        }
        var offset = headerSize

        return when (msgType) {
            MSG_FULL_SERVER_RESPONSE -> {
                var sequence: Int? = null
                var event: Int? = null
                if (flags and FLAG_POS_SEQUENCE != 0) {
                    if (!hasBytes(data, offset, 4)) return truncated("sequence")
                    sequence = buf.getInt(offset)
                    offset += 4
                }
                if (flags and FLAG_EVENT != 0) {
                    if (!hasBytes(data, offset, 4)) return truncated("event")
                    event = buf.getInt(offset)
                    offset += 4
                }
                if (!hasBytes(data, offset, 4)) return truncated("payload size")
                val payloadSize = buf.getInt(offset)
                offset += 4
                if (payloadSize < 0 || !hasBytes(data, offset, payloadSize)) {
                    return ServerMessage.Error(0, "invalid payload size: $payloadSize")
                }
                var payload = data.copyOfRange(offset, offset + payloadSize)
                if (compression == COMP_GZIP && payload.isNotEmpty()) payload = gunzip(payload)
                if (payload.isNotEmpty() && serialization != SER_JSON) {
                    return ServerMessage.Error(0, "unsupported serialization: $serialization")
                }
                val raw = String(payload, Charsets.UTF_8)
                ServerMessage.Response(
                    sequence = sequence,
                    event = event,
                    isLast = flags and FLAG_NEG_SEQUENCE != 0,
                    json = if (raw.isEmpty()) JSONObject() else JSONObject(raw),
                    rawJson = raw,
                )
            }
            MSG_ERROR -> {
                if (!hasBytes(data, offset, 8)) return truncated("error response")
                val code = buf.getInt(offset).toLong()
                offset += 4
                val msgSize = buf.getInt(offset)
                offset += 4
                if (msgSize < 0 || !hasBytes(data, offset, msgSize)) {
                    return ServerMessage.Error(code, "invalid error message size: $msgSize")
                }
                var messageBytes = data.copyOfRange(offset, offset + msgSize)
                if (compression == COMP_GZIP && messageBytes.isNotEmpty()) {
                    messageBytes = gunzip(messageBytes)
                }
                val msg = String(messageBytes, Charsets.UTF_8)
                ServerMessage.Error(code, msg)
            }
            else -> ServerMessage.Error(0, "unknown message type: " + msgType)
        }
    }

    private fun hasBytes(data: ByteArray, offset: Int, count: Int): Boolean =
        offset >= 0 && count >= 0 && offset <= data.size - count

    private fun truncated(field: String): ServerMessage.Error =
        ServerMessage.Error(0, "truncated $field")

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(data)).use { return it.readBytes() }
    }
}
