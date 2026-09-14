# Google Drive transcript backup

Talkt saves transcripts locally and can back them up directly from Android to the user's Google Drive. The Worker and Mac runner do not handle Drive credentials or backup uploads.

## Behavior

- Sign in to Talkt, then select **Connect Google Drive** on the home screen.
- Existing transcripts stay local unless **Include existing transcripts** is selected.
- New transcripts and agent replies become UTF-8 Markdown files in `Talkt/Transcripts`.
- WorkManager uploads when a network is available. A periodic job recovers work missed during a process interruption. Android can delay background work; force-stopped apps resume after reopening.
- Each Talkt account has separate settings and upload records. Records are also separated by Google's stable Drive user ID. Work checks the active Talkt account, connection generation, and Google account before uploading.
- Google access tokens remain in memory in Talkt. Google Play services manages token refresh. No API secret or refresh token is bundled or stored in Talkt preferences.
- Disconnect stops this device's future uploads. It leaves existing Drive files and Google's grant intact. The user can revoke the grant in their Google account's third-party connections.
- Deleting a transcript in Talkt does not remove its backup. Backup is one-way; this change does not implement restore or cross-device synchronization.

## Retry guarantees

The upload journal commits Google's generated file IDs to disk before creating either folders or transcripts. A lost HTTP response reuses the persisted ID on retry. HTTP 409 triggers a metadata readback; a trashed file is not considered successful. Acknowledgements are written only after the upload returns and the account checks still pass.

These guarantees cover retries and process restarts while app data remains intact. Clearing app data or reinstalling loses the journal and can create another Talkt folder. Deleting the remote backup folder requires restoring it from Drive trash before retrying. Permanently deleted folders require a fresh destination or resetting this device's backup state.

## Google setup

The prepared Google Cloud project name is `Talkt`, with ID `buddytools-talkt`. Automatic approval review rejected project creation, so the project and OAuth client have not been created.

1. Create or choose a dedicated Talkt Google Cloud project.
2. Enable the Google Drive API.
3. Configure Google Auth Platform branding, support contact, audience, and the `https://www.googleapis.com/auth/drive.file` scope. Use testing mode and explicitly listed test users until release setup is complete.
4. Create an Android OAuth client for package `org.buddytools.talkt` and the signing certificate used by the installed APK.
5. Register the production signing certificate separately before distributing a release. Complete Google's required branding and publishing checks before offering authorization to all users.

Current local debug SHA-1, read from Gradle's signing report:

```text
25:34:F7:AC:0E:59:AE:52:F7:28:5F:AC:05:25:F0:9F:80:15:13:2C
```

The Android authorization flow identifies the app using its package and signing certificate. This implementation does not need a web client secret, a server token exchange, or a `google-services.json` file.

Sources: [Android authorization](https://developer.android.com/identity/authorization), [Drive permissions](https://developers.google.com/workspace/drive/api/guides/api-specific-auth), [pre-generated file IDs](https://developers.google.com/workspace/drive/api/guides/create-file). Official reference examples are in `resources/google-workspace-java/drive/snippets/drive_v3`.

## Validation

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest :app:assembleDebug -q
```

The suite contains 56 tests, including 12 Drive tests. Coverage includes lost upload and folder responses, journal persistence failure, excluded history, disconnection during upload, destination isolation, UTF-8 multipart requests, conflict readback, trashed folders, quota errors, and blocking requests after an account change.

The final debug APK built and installed successfully on Fold R3GL708X7KJ. On-device verification caught and fixed the Drive section inheriting black text on the dark background. The signed-out state correctly disables backup. Google sign-in to Talkt succeeded, and the backup confirmation leaves existing transcripts excluded by default.

The Drive account picker opens, but authorization returns Google Play services status 8 after account selection. The result handler now parses error-bearing activity results instead of labelling every unsuccessful result as cancellation. Backup remains disconnected, and no transcripts were uploaded. A screenshot and a CAP recording document the device UI. A real Drive upload, offline recovery, and connected-account isolation checks remain pending OAuth setup and successful authorization.

After configuration, verify with a synthetic transcript, inspect the Markdown in Drive, retry without a duplicate, test offline recovery, then test sign-out, Talkt account switching, and disconnect. Do not upload existing personal history as a test without choosing that option explicitly.
