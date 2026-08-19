package com.inktalk.ime.asr

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** 火山引擎流式语音识别 WebSocket 客户端。 */
class VolcAsrClient(private val listener: Listener) {

    interface Listener {
        /** 握手成功；[logId] 是 X-Tt-Logid，排错线索。 */
        fun onOpen(logId: String?)
        fun onMessage(msg: SaucProtocol.ServerMessage)
        fun onFailure(t: Throwable)
        fun onClosed()
    }

    /**
     * [apiKey] 非空时使用新版控制台鉴权（只发 X-Api-Key）；
     * 否则使用旧版双凭据（X-Api-App-Key + X-Api-Access-Key）。
     */
    data class Config(
        val endpoint: String,
        val resourceId: String,
        val apiKey: String = "",
        val appKey: String = "",
        val accessKey: String = "",
        val requestId: String = UUID.randomUUID().toString(),
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接
        .build()
    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    private var ws: WebSocket? = null
    private var connectionGeneration = 0L
    private var nextSequence = 1

    @Volatile
    var isOpen: Boolean = false
        private set

    fun connect(config: Config) {
        val generation = ++connectionGeneration
        nextSequence = 1
        openSocket(config, generation, attempt = 0)
    }

    internal fun buildRequest(config: Config): Request {
        val builder = Request.Builder()
            .url(config.endpoint)
            .header("X-Api-Resource-Id", config.resourceId)
            .header("X-Api-Request-Id", config.requestId)
            .header("X-Api-Sequence", "-1")
        if (config.apiKey.isNotBlank()) {
            builder.header("X-Api-Key", config.apiKey)
        } else {
            builder.header("X-Api-App-Key", config.appKey)
            builder.header("X-Api-Access-Key", config.accessKey)
        }
        return builder.build()
    }

    private fun openSocket(config: Config, generation: Long, attempt: Int) {
        val request = buildRequest(config)
        var handshakeCompleted = false

        httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != connectionGeneration) {
                    webSocket.close(1000, "stale")
                    return
                }
                handshakeCompleted = true
                isOpen = true
                listener.onOpen(response.header("X-Tt-Logid"))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (generation != connectionGeneration) return
                try {
                    listener.onMessage(SaucProtocol.decodeServerMessage(bytes.toByteArray()))
                } catch (t: Throwable) {
                    listener.onFailure(
                        RuntimeException(failureDescription(t, null, config.requestId), t)
                    )
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != connectionGeneration) return
                // 服务端正常只发二进制帧；文本帧视为异常信息
                listener.onMessage(
                    SaucProtocol.ServerMessage.Error(0, "unexpected text frame: " + text)
                )
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != connectionGeneration) return
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != connectionGeneration) return
                isOpen = false
                listener.onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != connectionGeneration) return
                isOpen = false
                Log.e(
                    TAG,
                    "ASR WebSocket failed: attempt=$attempt, requestId=${config.requestId}, " +
                        "http=${response?.code ?: "none"}",
                    t,
                )
                if (!handshakeCompleted && response == null &&
                    attempt < MAX_CONNECT_RETRIES && isRetryable(t)
                ) {
                    main.postDelayed({
                        if (generation == connectionGeneration) {
                            openSocket(
                                config.copy(requestId = UUID.randomUUID().toString()),
                                generation,
                                attempt + 1,
                            )
                        }
                    }, RETRY_DELAY_MS)
                    return
                }
                listener.onFailure(
                    RuntimeException(failureDescription(t, response, config.requestId), t)
                )
            }
        }).also { ws = it }
    }

    @Synchronized
    fun sendFullRequest(json: String): Boolean {
        val socket = ws ?: return false
        if (socket.queueSize() > MAX_QUEUED_BYTES) return false
        val sequence = nextSequence++
        return socket.send(
            ByteString.of(*SaucProtocol.encodeFullClientRequest(json, sequence))
        )
    }

    /** [last] 为 true 时发送尾包，通知服务端音频结束。 */
    @Synchronized
    fun sendAudio(pcm: ByteArray, last: Boolean = false): Boolean {
        val socket = ws ?: return false
        if (socket.queueSize() > MAX_QUEUED_BYTES) return false
        val sequence = nextSequence++
        return socket.send(
            ByteString.of(*SaucProtocol.encodeAudioRequest(pcm, sequence, last))
        )
    }

    fun close() {
        connectionGeneration += 1
        ws?.close(1000, "bye")
        ws = null
        isOpen = false
    }

    fun destroy() {
        close()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    internal fun failureDescription(
        throwable: Throwable,
        response: Response?,
        requestId: String,
    ): String {
        val shortId = requestId.take(8)
        if (response != null) {
            val statusCode = response.header("X-Api-Status-Code")
            val statusMessage = response.header("X-Api-Message")
                ?: response.header("X-Api-Status-Message")
            val logId = response.header("X-Tt-Logid")
            val bodyText = try {
                response.body?.string()?.trim()?.take(200)
            } catch (_: Throwable) {
                null
            }
            return buildString {
                append("服务端拒绝连接（HTTP ").append(response.code).append('）')
                if (!statusCode.isNullOrBlank()) append("，状态码 ").append(statusCode)
                if (!statusMessage.isNullOrBlank()) append("：").append(statusMessage)
                if (!bodyText.isNullOrBlank()) append("；").append(bodyText)
                if (!logId.isNullOrBlank()) append("；logid=").append(logId)
                append("；请求 ID=").append(shortId)
            }
        }

        val root = rootCause(throwable)
        val explanation = when (root) {
            is UnknownHostException -> "无法解析识别服务地址，请检查网络或 DNS"
            is SocketTimeoutException -> "连接识别服务超时，请稍后重试"
            is SSLException -> "TLS 握手失败，请检查系统时间和网络证书"
            is ConnectException -> "无法连接识别服务，请检查当前网络"
            is EOFException -> "连接在握手完成前被关闭，请切换网络后重试"
            is SocketException -> root.message?.takeIf { it.isNotBlank() }
                ?.let { "网络连接被中断：$it" } ?: "网络连接被中断，请重试"
            else -> root.message?.takeIf { it.isNotBlank() }
                ?: "${root.javaClass.simpleName}（没有返回详细原因）"
        }
        return "$explanation；请求 ID=$shortId"
    }

    internal fun isRetryable(throwable: Throwable): Boolean =
        rootCause(throwable) is IOException

    private fun rootCause(throwable: Throwable): Throwable {
        var current = throwable
        val seen = HashSet<Throwable>()
        while (current.cause != null && seen.add(current)) {
            current = current.cause!!
        }
        return current
    }

    private companion object {
        const val TAG = "InkTalkASR"
        const val MAX_CONNECT_RETRIES = 1
        const val RETRY_DELAY_MS = 450L
        const val MAX_QUEUED_BYTES = 512L * 1024L
    }
}
