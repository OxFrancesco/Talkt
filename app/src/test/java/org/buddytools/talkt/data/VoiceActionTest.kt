package org.buddytools.talkt.data

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceActionTest {
    @Test
    fun `agent disabled always defaults to transcription`() {
        assertEquals(VoiceAction.Transcribe, defaultVoiceAction(agentEnabled = false, hasEditor = false))
        assertEquals(VoiceAction.Transcribe, defaultVoiceAction(agentEnabled = false, hasEditor = true))
    }

    @Test
    fun `agent enabled keeps the existing context-sensitive default`() {
        assertEquals(VoiceAction.AskAgent, defaultVoiceAction(agentEnabled = true, hasEditor = false))
        assertEquals(VoiceAction.Transcribe, defaultVoiceAction(agentEnabled = true, hasEditor = true))
    }

    @Test
    fun `agent disabled overrides a per-capture agent selection`() {
        assertEquals(VoiceAction.Transcribe, VoiceAction.AskAgent.withAgentEnabled(agentEnabled = false))
        assertEquals(VoiceAction.AskAgent, VoiceAction.AskAgent.withAgentEnabled(agentEnabled = true))
    }
}
