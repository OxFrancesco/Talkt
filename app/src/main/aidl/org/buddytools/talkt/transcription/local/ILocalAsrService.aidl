package org.buddytools.talkt.transcription.local;

import android.os.ParcelFileDescriptor;
import org.buddytools.talkt.transcription.local.ILocalAsrCallback;

interface ILocalAsrService {
    void transcribe(in ParcelFileDescriptor wav, long durationMillis, String languageHint, ILocalAsrCallback callback);
}
