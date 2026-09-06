package org.buddytools.talkt.transcription.local

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParakeetModelTest {
    @Test
    fun declinesColdLoadWhenTheModelHasNoMemoryHeadroom() {
        assertFalse(ParakeetModel.canLoad(ParakeetModel.MIN_AVAILABLE_BYTES - 1, false))
        assertTrue(ParakeetModel.canLoad(ParakeetModel.MIN_AVAILABLE_BYTES, false))
    }

    @Test
    fun androidMemoryPressureOverridesTheAvailableByteCount() {
        assertFalse(ParakeetModel.canLoad(8L * 1024 * 1024 * 1024, true))
    }
}
