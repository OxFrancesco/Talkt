package org.buddytools.talkt.data

enum class VoiceAction {
    Transcribe,
    AskAgent,
}

fun defaultVoiceAction(agentEnabled: Boolean, hasEditor: Boolean): VoiceAction =
    if (agentEnabled && !hasEditor) VoiceAction.AskAgent else VoiceAction.Transcribe

fun VoiceAction.withAgentEnabled(agentEnabled: Boolean): VoiceAction =
    if (agentEnabled) this else VoiceAction.Transcribe
