# Talkt for Wear OS

The watch screen contains only a Thinking Orb. Tap once to record and animate the orb, then tap again to stop. It records up to 60 seconds of 16 kHz mono PCM WAV audio locally and transfers automatically over the Wear Data Layer. The phone stores it in the signed-in account's recording queue, persists a network-constrained background job, then acknowledges receipt. The job uses Talkt's existing cloud transcription and archive path and retries failures automatically. Results appear in History and the phone's clipboard, with the usual notification.

Install both APKs with the same application ID and signing certificate. The debug builds already match. Pair the watch with the Android phone through its companion app, open Talkt on the phone, and sign in. Open Talkt on the watch once to receive the account binding. No separate watch login or provider key is needed.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug :wear:assembleDebug
adb -s PHONE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s WATCH_SERIAL install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Do not clear phone app data or use connected instrumentation on the daily phone. Re-enable the phone accessibility service after installing, preserving other enabled services.

## Offline behavior

- Audio streams to app-private `.part` files. Completed recordings become `.wav`; after process death, the next launch repairs partial WAV headers.
- The watch keeps audio until the phone confirms a durable save. Persistent DataItems handle reconnection, and WorkManager retries failed local submissions. The confirmation means saved on the phone, not successfully transcribed.
- If recording before the first phone connection, audio stays unassigned on the watch. The first signed-in phone connection assigns them automatically. Later account changes never reassign recordings.
- A recording already assigned to an account never moves to a different account. Sign back into its original account on the same phone to process it. Re-pairing or reinstalling either app changes the device relationship and may need manual recovery of pending audio.
- The phone checks the destination node, account token, recording ID, WAV format, and 60-second size limit before accepting audio. A persistent receipt prevents retransmissions from creating another transcript.
- WorkManager performs watch transcription even when Android refuses a foreground-service start. Network failures leave audio in History and retry with backoff. No Send button or phone interaction is needed after initial sign-in.
- Watch recordings do not insert text into whichever phone editor happens to be focused.

## Shortcuts

- Add **Talkt** to an image complication slot on the watch face. It displays one continuous sine wave, without text. Tapping starts a recording immediately in the orb screen; tapping it again while recording stops the recording. The watch face controls the slot size and tint.
- Add the **Talkt** widget from the Android phone home-screen widget picker. Its single sine wave opens the phone's existing recording flow. A signed-out phone opens sign-in first.
- The orb honors Android's animator setting, stops rendering animation when the activity is not visible, and keeps microphone capture in a foreground service while the screen is off. Start and stop provide haptic feedback. Errors use transient system messages rather than persistent controls.

## Device verification

1. Record and stop on the watch. Verify the local WAV and its duration, then check the transcript in phone History.
2. Disconnect the phone. Record, close and reopen the watch app, and confirm the recording remains. Reconnect and verify one transcript, then check watch audio and DataItem cleanup.
3. Close the watch app during recording and lower the wrist. Verify the microphone foreground service continues and stops at one minute.
4. Kill the watch process during a recording. Reopen and verify recovered audio remains available for transfer.
5. Deny the microphone permission, retry, and grant it. Verify the watch can record afterwards.
6. Sign out or switch the phone account before transferring. Verify the original audio is never uploaded under the new account. Switch back and verify delivery.
7. Interrupt the phone after saving audio but before acknowledgment. Retry transfer and verify no duplicate transcript.

The paired-device transfer and cloud transcription checks require a paired watch and signed-in phone. Emulator recording checks alone do not prove that path.

References: [official Data Layer sample](https://github.com/android/wear-os-samples/tree/main/DataLayer), [Data Layer events](https://developer.android.com/training/wearables/data/events), [Asset file descriptors](https://developers.google.com/android/reference/com/google/android/gms/wearable/Asset).
