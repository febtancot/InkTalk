package com.inktalk.ime.asr

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException

class VolcAsrClientTest {
    private val client = VolcAsrClient(object : VolcAsrClient.Listener {
        override fun onOpen(logId: String?) = Unit
        override fun onMessage(msg: SaucProtocol.ServerMessage) = Unit
        override fun onFailure(t: Throwable) = Unit
        override fun onClosed() = Unit
    })

    @Test
    fun newConsoleRequestUsesOnlyWebSocketAuthenticationAndConnectionHeaders() {
        val request = client.buildRequest(
            VolcAsrClient.Config(
                endpoint = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
                resourceId = "volc.seedasr.sauc.duration",
                apiKey = "api-key",
                requestId = "request-id",
            )
        )

        assertEquals("api-key", request.header("X-Api-Key"))
        assertEquals("request-id", request.header("X-Api-Request-Id"))
        assertEquals("-1", request.header("X-Api-Sequence"))
        assertEquals("volc.seedasr.sauc.duration", request.header("X-Api-Resource-Id"))
        assertFalse(request.headers.names().contains("X-Api-Connect-Id"))
        assertFalse(request.headers.names().contains("X-Api-App-Key"))
    }

    @Test
    fun legacyRequestUsesBothLegacyCredentials() {
        val request = client.buildRequest(
            VolcAsrClient.Config(
                endpoint = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
                resourceId = "volc.bigasr.sauc.duration",
                appKey = "app-id",
                accessKey = "access-token",
            )
        )

        assertEquals("app-id", request.header("X-Api-App-Key"))
        assertEquals("access-token", request.header("X-Api-Access-Key"))
        assertFalse(request.headers.names().contains("X-Api-Key"))
    }

    @Test
    fun emptyExceptionMessageNeverBecomesNullCopy() {
        val description = client.failureDescription(
            EOFException(),
            response = null,
            requestId = "12345678-abcd",
        )

        assertTrue(description.contains("握手完成前被关闭"))
        assertTrue(description.contains("请求 ID=12345678"))
        assertFalse(description.contains("null"))
        assertTrue(client.isRetryable(EOFException()))
    }

    @Test
    fun rejectedHandshakeIncludesHttpStatusAndLogId() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://openspeech.bytedance.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(403)
            .message("Forbidden")
            .header("X-Tt-Logid", "server-log-id")
            .body("resource not enabled".toResponseBody())
            .build()

        val description = client.failureDescription(
            IllegalStateException(),
            response,
            requestId = "abcdefgh-1234",
        )

        assertTrue(description.contains("HTTP 403"))
        assertTrue(description.contains("resource not enabled"))
        assertTrue(description.contains("logid=server-log-id"))
    }
}
