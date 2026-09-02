package org.buddytools.talkt.cabinet

import kotlin.test.Test
import kotlin.test.assertEquals
import org.buddytools.talkt.data.TranscriptEntry

class CabinetSearchTest {
    private val entries = listOf(
        entry("new", "Buy groceries after the meeting", "Remember oat milk"),
        entry("old", "Prenotazione per il caffè domani", null),
        entry("other", "Ship the Android release", null),
    )

    @Test
    fun blankQueryPreservesNewestFirstOrder() {
        assertEquals(listOf("new", "old", "other"), fuzzySearchEntries(entries, "  ").map { it.id })
    }

    @Test
    fun typoStillFindsTheIntendedNote() {
        assertEquals(listOf("new"), fuzzySearchEntries(entries, "meting").map { it.id })
    }

    @Test
    fun ignoresCaseAndAccents() {
        assertEquals(listOf("old"), fuzzySearchEntries(entries, "CAFFE").map { it.id })
    }

    @Test
    fun everyQueryTokenMustMatch() {
        assertEquals(listOf("new"), fuzzySearchEntries(entries, "buy milk").map { it.id })
        assertEquals(emptyList(), fuzzySearchEntries(entries, "buy release"))
    }

    private fun entry(id: String, text: String, reply: String?) = TranscriptEntry(
        id = id,
        timeMillis = 0,
        text = text,
        kind = "copy",
        source = "On device",
        reply = reply,
    )
}
