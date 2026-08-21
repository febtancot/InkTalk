package com.inktalk.ime.history

import org.junit.Assert.assertEquals
import org.junit.Test

class InputSourceTest {

    @Test
    fun everyWireValueRoundTrips() {
        InputSource.entries.forEach { source ->
            assertEquals(source, InputSource.fromWireValue(source.wireValue))
        }
    }

    @Test
    fun unknownLegacyValueFallsBackToVoice() {
        assertEquals(InputSource.VOICE, InputSource.fromWireValue("unknown"))
    }
}
