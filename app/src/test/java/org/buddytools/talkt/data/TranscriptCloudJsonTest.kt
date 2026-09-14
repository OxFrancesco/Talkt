package org.buddytools.talkt.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptCloudJsonTest {
    @Test fun preservesUnicodeMultilineAndNullReplies() {
        for (reply in listOf(null, "Line one\nCaffè ☕")) {
            val entry = TranscriptEntry("0f89e6c2-076a-455e-b5a9-924c7e5d69f1", 1789299000000, "Hello\n世界", "transcript", "keyboard", reply)
            assertEquals(entry, TranscriptCloudJson.decode(TranscriptCloudJson.encode(entry)))
        }
    }
}
