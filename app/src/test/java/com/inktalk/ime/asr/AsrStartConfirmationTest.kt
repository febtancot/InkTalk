package com.inktalk.ime.asr

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrStartConfirmationTest {
    @Test
    fun acceptsNonFinalResponseRegardlessOfSequence() {
        listOf<Int?>(1, 0, -1, null).forEach { sequence ->
            assertTrue(AsrStartConfirmation.accepts(response(sequence, isLast = false)))
        }
    }

    @Test
    fun rejectsResponseThatAlreadyEndsSession() {
        assertFalse(AsrStartConfirmation.accepts(response(-1, isLast = true)))
    }

    private fun response(
        sequence: Int?,
        isLast: Boolean,
    ): SaucProtocol.ServerMessage.Response = SaucProtocol.ServerMessage.Response(
        sequence = sequence,
        event = null,
        isLast = isLast,
        json = JSONObject(),
        rawJson = "",
    )
}
