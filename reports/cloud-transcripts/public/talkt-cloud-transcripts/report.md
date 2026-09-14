# Talkt cloud transcripts

Google Drive backup is replaced by private Cloudflare R2 storage and per-account SQLite full-text search. Local transcripts remain available offline. Cloud backup is opt-in; users choose whether to include existing notes.

The app can search transcript text and agent replies, load more results, copy notes and delete cloud copies. Search supports word prefixes and accent-insensitive matching. It does not provide semantic search or AI-generated answers.

The Clerk-verified account selects its own Durable Object and R2 prefix. Upload retries do not duplicate files. Pending R2 writes and deletes retry through durable alarms. Cloud deletion removes search results and prevents retries from restoring the deleted UUID.

## Verified

- 45 Worker tests passed, including account isolation, R2 outage recovery, duplicate retries, deletion and pagination.
- 45 Android unit tests passed. Debug APK and device-test APK compiled.
- Private R2 bucket `talkt-transcripts` created in the personal Cloudflare account. Public development URL disabled; no custom domains.
- Worker deployed at `https://talkt-api.oddofrancesco000.workers.dev`, version `9c1eb410-b5cf-4769-946d-88823d88490f`.
- Live health returned 200. Unauthenticated transcript access returned 401.

## Device check

Pending. The Fold was reconnected through wireless debugging, but APK installation and screenshot capture have stalled. The compiled opt-in device test checks authenticated upload, R2 readback, text and reply search, deletion and retry protection using a synthetic note. A successful build does not prove that flow on the phone.

[Cloudflare R2 bindings](https://developers.cloudflare.com/r2/api/workers/workers-api-usage/) · [SQLite full-text search](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/)
