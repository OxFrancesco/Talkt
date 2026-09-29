package org.buddytools.talkt.capture

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingStoreTest {
    private val root: File = Files.createTempDirectory("talkt-recordings").toFile()
    private val store = RecordingStore(root)

    @Test fun pendingRecordingsStayWithTheirAccount() {
        val root = kotlin.io.path.createTempDirectory("talkt-recording-accounts").toFile()
        try {
            val alice = RecordingStore(root, "user_alice")
            val bob = RecordingStore(root, "user_bob")
            val part = alice.newRecordingFile()
            part.writeBytes(WavFormat.header(0) + ByteArray(320))
            assertTrue(bob.adoptOrphans().isEmpty())
            assertTrue(bob.pending().isEmpty())
            alice.adoptOrphans()
            assertEquals(1, alice.pending().size)
            assertTrue(RecordingStore(root).pending().isEmpty())
            assertTrue(bob.pending().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `a recording killed mid-way is adopted with a repaired header`() {
        // Simulate the recorder: zero-size header, then PCM, then the process dies.
        val part = store.newRecordingFile(now = 1_000L)
        part.writeBytes(WavFormat.header(0) + ByteArray(32_000))
        assertTrue(store.pending().isEmpty(), "an unfinished .part must not be offered for transcription")

        val adopted = store.adoptOrphans()

        assertEquals(1, adopted.size)
        val wav = adopted.single()
        assertTrue(wav.name.endsWith(".wav"))
        assertFalse(part.exists())
        assertEquals(32_000, dataSizeOf(wav))
        assertEquals(32_000 + 36, riffSizeOf(wav))
        val pending = store.pending().single()
        assertEquals(1_000L, pending.recordedAt)
        assertEquals(1_000L, pending.durationMillis)
    }

    @Test
    fun `empty orphans are discarded instead of adopted`() {
        val part = store.newRecordingFile()
        part.writeBytes(WavFormat.header(0))

        assertTrue(store.adoptOrphans().isEmpty())
        assertFalse(part.exists())
    }

    @Test
    fun `finalize patches sizes and renames`() {
        val part = store.newRecordingFile(now = 5_000L)
        part.writeBytes(WavFormat.header(0) + ByteArray(6_400))

        val wav = store.finalize(part)

        assertEquals(6_400, dataSizeOf(wav))
        assertEquals(200L, WavFormat.durationMillis(wav))
        assertEquals(listOf(wav), store.pending().map { it.file })
    }

    @Test
    fun `pending lists oldest first and ignores header-only files`() {
        store.finalize(store.newRecordingFile(now = 3_000L).apply { writeBytes(WavFormat.header(0) + ByteArray(320)) })
        store.finalize(store.newRecordingFile(now = 2_000L).apply { writeBytes(WavFormat.header(0) + ByteArray(320)) })
        File(store.directory, "9000-empty.wav").writeBytes(WavFormat.header(0))

        assertEquals(listOf(2_000L, 3_000L), store.pending().map { it.recordedAt })
    }

    private fun dataSizeOf(file: File): Int = header(file).getInt(40)

    private fun riffSizeOf(file: File): Int = header(file).getInt(4)

    private fun header(file: File): ByteBuffer =
        ByteBuffer.wrap(file.readBytes().copyOfRange(0, WavFormat.HEADER_BYTES)).order(ByteOrder.LITTLE_ENDIAN)
}
