package org.buddytools.talkt.transcription.local;

interface ILocalAsrCallback {
    void onSuccess(String text, String language);
    void onNoSpeech();
    void onFailure(int code, String detail);
}
