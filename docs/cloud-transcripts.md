# Cloud transcripts

Talkt keeps transcripts on the phone and optionally backs them up to the signed-in account. Enable Cloud backup on the home screen. Existing notes upload only when the user checks Include existing transcripts. Cabinet's Cloud button opens server search. Disabling backup retains cloud copies; deleting a local note also retains its cloud copy. Delete cloud copies in Cloud transcripts.

## Storage and search

The `talkt-api` Worker verifies the Clerk session signature and issuer. The verified issuer and subject select a dedicated `TranscriptArchive` Durable Object. Clients cannot choose another account's archive.

The private `talkt-transcripts` R2 bucket stores JSON files under `<durable-object-id>/<transcript-uuid>.json`. Files contain the transcript, optional reply, timestamp, kind and source. The bucket has no public URL. Authenticated Worker routes provide access.

Each account's SQLite database stores metadata and text, with an FTS5 index covering transcripts and replies. Search supports Unicode words, accent-insensitive matching and word prefixes. All entered terms must match. Results are ranked, then ordered by creation time. Empty searches list newest entries. Results paginate in groups of 50. This is keyword search, not semantic similarity or AI-generated answers.

## Reliability

WorkManager retries background backups when connected. Local preferences track completed IDs per account and API endpoint. The server enforces immutable UUIDs, so a lost acknowledgement cannot create duplicates. A reused UUID with different content returns 409.

SQLite stores pending R2 operations before network I/O. An alarm is scheduled before accepting pending work. R2 failure returns 202 with `saved: false`; the client retries and the Durable Object alarm processes pending operations. Mutations and R2 reads run through a per-object queue. SQL and FTS changes share a transaction. Search can find durably accepted transcripts while their R2 copy is pending, but backup is acknowledged complete only after the R2 write.

Cloud deletion removes the search entry immediately and queues R2 deletion. A UUID tombstone prevents a later backup retry from recreating a deleted note. Tombstones retain only IDs and timestamps, not transcript text. Provider recovery retention still applies to historical SQLite snapshots.

Google Drive authorization, API clients, backup UI and dependencies were removed. On upgrade, old Drive backup work is cancelled and its account preferences are removed. Existing files in Google Drive, if any, are not touched.

## API

All routes require a Clerk bearer token.

- `PUT /v1/transcripts/:uuid`: immutable JSON transcript. Returns 200 when R2 is saved, 202 while pending, 409 for changed content, 410 for a deleted UUID.
- `GET /v1/transcripts?q=...&offset=0`: search or list. Returns entries and nextOffset.
- `GET /v1/transcripts/:uuid`: read the R2 file. Returns 404 when missing or deleted.
- `DELETE /v1/transcripts/:uuid`: remove from search and R2. Returns 202 when R2 deletion is pending.

Bodies are bounded to 1.6 MB. Text and reply each allow 128,000 characters. Queries allow 500 characters and up to 20 word terms. SQL uses parameter binding; FTS receives quoted word literals, never raw search syntax.

## Validation

Run `bun run --cwd worker check` and `bun run --cwd worker test`. Android needs the Android Studio JBR:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The opt-in `CloudTranscriptInstrumentedTest` requires a signed-in physical device. It uploads a synthetic note, retries, reads R2, searches text and reply, deletes the note and checks that retry cannot recreate it. It deletes its synthetic cloud note in a finally block and leaves local history untouched. Pass instrumentation argument `verifyCloud=true` when running this test.

Reference documentation: [R2 Worker bindings](https://developers.cloudflare.com/r2/api/workers/workers-api-usage/) and [Durable Object SQLite with FTS5](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/).
