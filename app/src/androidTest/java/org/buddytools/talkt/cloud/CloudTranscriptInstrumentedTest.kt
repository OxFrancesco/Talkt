package org.buddytools.talkt.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.buddytools.talkt.auth.TalktAuth
import org.buddytools.talkt.data.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CloudTranscriptInstrumentedTest {
    @Test fun authenticatedStorageSearchAndDeletion() = runBlocking {
        assumeTrue("Run explicitly against a signed-in device", InstrumentationRegistry.getArguments().getString("verifyCloud") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = TalktAuth.requireUserId()
        val api = TalktApi(TalktSettings(context))
        val marker = "cloudcheck" + UUID.randomUUID().toString().replace("-", "")
        val entry = TranscriptEntry(UUID.randomUUID().toString(), System.currentTimeMillis(),
            "$marker Caffè at the museum", "transcript", "verification", "Tickets for tomorrow")
        try {
            assertTrue("R2 upload acknowledged", api.saveTranscript(owner, entry))
            assertTrue("Retry is idempotent", api.saveTranscript(owner, entry))
            assertEquals("R2 read preserves transcript", entry, api.cloudTranscript(owner, entry.id))
            assertEquals(listOf(entry), api.searchTranscripts(owner, "$marker caffe").entries)
            assertEquals(listOf(entry), api.searchTranscripts(owner, "$marker tickets").entries)
            api.deleteCloudTranscript(owner, entry.id)
            assertTrue(api.searchTranscripts(owner, marker).entries.isEmpty())
            try {
                api.cloudTranscript(owner, entry.id)
                fail("Deleted transcript remained readable")
            } catch (error: TalktApiException) { assertEquals(404, error.statusCode) }
            try {
                api.saveTranscript(owner, entry)
                fail("Retry resurrected deleted transcript")
            } catch (error: TalktApiException) { assertEquals(410, error.statusCode) }
        } finally { api.deleteCloudTranscript(owner, entry.id) }
    }
}
