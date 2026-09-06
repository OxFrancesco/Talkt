# FRA-527 adversarial review

Scope reviewed: `worker/src/conversation-runs.ts`, `worker/src/runner-jobs.ts`, `worker/src/runner-plugin.ts`, `worker/src/telegram.ts`, `worker/src/telegram-channel.ts`, `worker/src/agent.ts`, `worker/src/index.ts`, `runner/**`, `shared/runner-protocol.ts`, and `docs/research/fra-527-decisions.tsv`.

Verification performed: source inspection, installed SDK type/runtime shape inspection for `@opencode-ai/plugin` / `opencode-ai` `0.0.0-dev-18729`, `cd worker && bun x tsc --noEmit --pretty false`, and `cd runner && bun x tsc --noEmit --pretty false`. Both typechecks passed after the latest root integration. No runtime tests or device/browser end-to-end tests were run in this review.

Note: I accidentally ran `bun install` in `runner` to restore missing local type dependencies before the runner typecheck. That created ignored `runner/node_modules/` only. I did not edit tracked files.

## Findings

### P0. Runner permission prompts are not concrete enough for arbitrary Mac execution

Evidence:

- `worker/src/runner-plugin.ts:13-15` registers `personal_exec` with only `permission: "personal_exec"`.
- `worker/src/runner-plugin.ts:32-34` registers `personal_mcp_call` with only `permission: "personal_mcp_call"`.
- `worker/src/runner-plugin.ts:37-39` asks for approval based on permission action, but does not attach the command, cwd, MCP server, MCP tool, or argument hash to the permission request.
- `worker/src/telegram-channel.ts:48-50` sends `permission.message` if present, else falls back to `permission.action` and `permission.resources`.
- `shared/runner-protocol.ts:5-9` allows generic `exec`, `read_file`, `mcp_list`, and `mcp_call` requests.
- `runner/src/executor.ts:28-33` only checks that `cwd` is inside configured roots. That constrains the starting directory, not the process filesystem.
- `runner/README.md:51` correctly notes that root checks do not sandbox spawned CLI or MCP processes.

Why this is a bug:

The runner can execute arbitrary argv on the user’s Mac. The current permission path appears to approve an action label rather than the exact operation. If the prompt shown in Telegram is generic, the user cannot distinguish `git status` from `bash -lc ...` or an MCP side-effect call. The installed OpenCode SDK types show tool options support a permission action string, but not a tool-input-to-resource callback on `ctx.tool(...)`, so the current implementation cannot rely on the tool declaration alone to make approval concrete.

Fix:

- Add runner-side policy before execution. Allow only specific commands and MCP server/tool pairs needed for FRA-527. Deny shells and interpreters by default unless a separate explicit approval covers that exact invocation.
- Make approval resources include the normalized cwd plus argv digest for `personal_exec`, and server/tool plus serialized argument digest for `personal_mcp_call`.
- Do not allow saved `always` approvals for broad runner actions unless the saved record is keyed to the exact normalized operation or a deliberately narrow policy rule.
- Add tests that prove a different argv, cwd, MCP tool, or argument body does not reuse a prior approval.

### P1. Leased runner jobs do not recover cleanly after runner crash or restart

Evidence:

- `worker/src/runner-jobs.ts:20-29` creates a job with a single 180 second deadline, then waits until that deadline and returns `runner_timeout`.
- `worker/src/runner-jobs.ts:36-43` returns an existing leased job only to the same runner, and otherwise only leases queued jobs.
- There is no `leaseExpires`, lease heartbeat, or requeue path for a job leased to a runner that disappears.

Why this is a bug:

If the runner leases a job and then crashes, no other runner can pick that job until the full request deadline expires. After the deadline expires, the stored job remains non-completed and expired. A retry with the same job id immediately times out again because the stale job is reused. This breaks restart/retry behavior and leaves manual storage cleanup as the practical recovery path.

Fix:

- Split job deadline from lease deadline.
- Store `leasedAt` and `leaseExpires`.
- Requeue expired leases, or let a new runner claim them with a new lease token.
- Mark request timeout as an explicit terminal state, or delete/reopen expired jobs in a controlled way for idempotent retries.
- Decide and test late completion behavior. Either reject completions after deadline or accept them and make future retries return the completed result.

### P1. Legacy `/v1/agent/prompt` can hang through permissions and long runner jobs

Evidence:

- `worker/src/agent.ts:102-110` sends a prompt and repeatedly waits on `client.sessions.wait({ sessionID })` until completion.
- `worker/src/index.ts:99-103` exposes that blocking flow through `/v1/agent/prompt`.
- The new async flow exists separately through `/v1/runs`, `/v1/runs/:id`, and `/v1/permissions` in `worker/src/index.ts:52-84`.

Why this is a bug:

A prompt that reaches a runner permission or long local job can hold the Worker request open. The caller gets no concrete permission-needed response from the legacy route, and the request may hit platform/client timeout while the actual OpenCode session waits for approval elsewhere. Telegram uses the async run flow, but Android or older callers using `/v1/agent/prompt` can still hit this path.

Fix:

- Move the app/client path to `/v1/runs` plus polling and permission endpoints.
- Or change `/v1/agent/prompt` to return a run id / 202-style response when the session is still active.
- Add a regression test for a permission-pending tool call through the HTTP route.

### P2. Telegram retry scheduling can stall unrelated updates

Evidence:

- `worker/src/telegram-channel.ts:35` processes up to 100 inbox entries per alarm.
- `worker/src/telegram-channel.ts:67-79` sets `retryAt = Math.max(retryAt, Date.now() + delay)` after a failed send.

Why this is a bug:

One failed send with a long retry delay can push the single alarm later than other ready work. That can delay completed replies, follow-up polls, and approval notices that are unrelated to the failed item. For Telegram global 429s this may be intentional, but generic delivery failures and per-message issues should not stall the whole inbox.

Fix:

- Store per-item `nextAttemptAt` and schedule the alarm for the earliest due item.
- Treat Telegram global `retry_after` as a channel-level backoff only when the API explicitly returns it.
- Keep the existing short poll cadence for active runs independent of a failed send for another inbox item.

### P2. Native connector status likely underreports Google runner availability

Evidence:

- `worker/src/agent.ts:27-30` configures a Google runner connector that executes through runner jobs.
- `worker/src/agent.ts:69-77` calls `nativeConnectorStatus({ secrets: this.nativeSecrets() })` without passing runner availability.
- `worker/src/native-connectors.ts:85-88` reports Google as `configured` only if Google secrets exist or a `googleRunner` status input is supplied.

Why this is a bug:

A deployment with `TALKT_RUNNER_TOKEN` and the local Google runner installed can still report Google as `authorization_or_runner_required` through capabilities/status. That makes the app and operator diagnostics inaccurate even when direct Google operations are available through the Mac runner.

Fix:

Pass a `googleRunner` status into `nativeConnectorStatus`, or expose runner-backed connector availability separately from OAuth-secret availability.

### P2. Decision log cites a circular artifact for user approval

Evidence:

- `docs/research/fra-527-decisions.tsv:2` says the user approved direct provider connectors and excluded Composio, citing `docs/research/fra-527-implementation-plan.md`.
- `docs/research/fra-527-implementation-plan.md:18` says the design is already approved, but does not include or link the actual user approval source.
- `docs/research/fra-527-implementation-plan.md:6` claims baseline Worker typecheck, 11 tests, and personal Cloudflare account verification. This review only re-verified Worker and runner typechecks. I did not find runtime-test or account-verification evidence in the decision TSV.

Why this is a bug:

The decision log cannot prove the approval it records. It points to another internal artifact that repeats the claim. That is enough for implementation context, but not enough for an auditable approval trail.

Fix:

Update the TSV to cite the actual approval source, such as the user message, Linear comment, or session note that contains the approval. Add separate rows for baseline verification if the project needs that claim to be auditable.

## Non-findings

- The installed `0.0.0-dev-18729` OpenCode SDK supports the current broad shape used here: `Plugin.define`, `ctx.tool.transform`, `ctx.tool(...)`, `ctx.permission.hook("evaluate", ...)`, `client.sessions.*`, `client.permissions.*`, and `client.session.children(...)` style APIs are present in installed types/runtime.
- `worker/src/index.ts` now wires Telegram webhook, runner poll/complete routes, runs routes, and permission routes. Earlier missing-route concerns are not present in the latest uncommitted state I reviewed.
- `conversation-runs.ts` checks the current per-session run before starting a new run and reuses an existing run id idempotently when the text/session match. I did not find a concrete duplicate-run bug there from static inspection.

## Addendum: pinned SDK permission and OpenRouter checks

### P0 confirmed. Custom tool `options.permission` is not an execution approval request

Evidence from the installed SDK/runtime:

- `@opencode-ai/schema/tool` exposes `options.permission?: string` on tool registration only. See `node_modules/.bun/@opencode-ai+schema@0.0.0-dev-18729/node_modules/@opencode-ai/schema/dist/tool.d.ts:17-27` and `:67-73`.
- The installed tool runtime snapshots permissions by using `tool.options?.permission ?? name` only to filter wholly denied tools from the catalog. See `node_modules/.bun/@opencode-ai+core@0.0.0-dev-18729+7589a49b74945963/node_modules/@opencode-ai/core/dist/chunks/persistent-pty-yx3eycjs.js:174-185`.
- The same runtime executes the tool directly after `execute.before`; it does not create a permission request from `options.permission`. See `node_modules/.bun/@opencode-ai+core@0.0.0-dev-18729+7589a49b74945963/node_modules/@opencode-ai/core/dist/chunks/persistent-pty-yx3eycjs.js:193-211` and `:90-115`.
- Core built-in tools request exact approval by calling `PermissionV2.assert(...)` inside the tool executor. Bash uses `resources: [input.command]` and `save: [input.command]`. See `resources/opencode/packages/core/src/tool/bash.ts:122-149`.
- The promise plugin permission domain exposes only `list`, `get`, `reply`, and `hook`. It does not expose `ask` or `assert`. See `node_modules/.bun/@opencode-ai+plugin@0.0.0-dev-18729+bb420c1578056a96/node_modules/@opencode-ai/plugin/dist/promise/permission.d.ts:6-21` and `node_modules/.bun/@opencode-ai+plugin@0.0.0-dev-18729+bb420c1578056a96/node_modules/@opencode-ai/plugin/dist/promise/plugin.d.ts:24-50`.
- The only exact operation data available to a promise plugin before execution is the `tool.hook("execute.before")` event containing `tool`, `sessionID`, `agent`, `messageID`, `id`, and `input`. See `node_modules/.bun/@opencode-ai+plugin@0.0.0-dev-18729+bb420c1578056a96/node_modules/@opencode-ai/plugin/dist/promise/tool.d.ts:27-35`.

Conclusion:

The earlier P0 is real. `worker/src/runner-plugin.ts:14` and `worker/src/runner-plugin.ts:33` do not request approval. They only give the tool a permission action for catalog filtering. `worker/src/runner-plugin.ts:37-39` can decorate evaluation requests created elsewhere, but no request is created for these custom tools by the installed runtime.

Practical fix:

Use `ctx.tool.hook("execute.before", ...)` to enforce the policy before the runner job is created. Since the promise plugin API has no `permission.assert`, this project needs its own durable approval record keyed to the exact tool call id and an exact input digest, or the plugin must switch to an internal/effect-side implementation that can call `PermissionV2.assert` with exact resources. Do not rely on `options.permission` for approval.

### P1. OpenRouter key connection stores unverified credentials, and local500 is not key verification

Evidence:

- `worker/src/agent.ts:64-65` calls `integration.connect.key({ integrationID: "openrouter", key, label: "Talkt" })`.
- The generated client sends `POST /api/integration/:integrationID/connect/key` with `successStatus: 204` and declared API errors `[400, 401]`. See `node_modules/.bun/@opencode-ai+client@0.0.0-dev-18729+11eac7cfbf53fc55/node_modules/@opencode-ai/client/dist/promise/generated/client.js:604-624` and output type `void` at `.../types.d.ts:6071-6103`.
- The installed server handler maps the call to `service.connection.key(...)`; it does not run a model/provider request. See `node_modules/.bun/@opencode-ai+server@0.0.0-dev-18729+5d1f4b2d5138e29e/node_modules/@opencode-ai/server/dist/handlers/integration.js:24-32`.
- The installed integration service checks only that a key method exists and optional form fields validate, then creates the credential. See `node_modules/.bun/@opencode-ai+core@0.0.0-dev-18729+7589a49b74945963/node_modules/@opencode-ai/core/dist/chunks/persistent-pty-cs2yang5.js:453-470`.
- `models-dev` registers a generic key method for integrations whose provider metadata has env names, including OpenRouter through the models catalog path. See `resources/opencode/packages/core/src/plugin/models-dev.ts:124-139`.
- Model use later resolves the active integration credential and injects it into runtime model settings. See installed `model-resolver` at `node_modules/.bun/@opencode-ai+core@0.0.0-dev-18729+7589a49b74945963/node_modules/@opencode-ai/core/dist/chunks/persistent-pty-x3tpb9a6.js:209-218`, with key mapping at `:93-130` and `:182-191`.
- The generated client throws `ClientError("UnexpectedStatus")` only for responses whose status is neither the success status nor a declared error status. See `node_modules/.bun/@opencode-ai+client@0.0.0-dev-18729+11eac7cfbf53fc55/node_modules/@opencode-ai/client/dist/promise/generated/client.js:37-44`.

Conclusion:

A `local500 UnexpectedStatus` observed while calling the Talkt `/v1/agent/openrouter/key` route is unlikely to be an OpenRouter key-validity failure. The OpenCode key-connect endpoint does not verify the key against OpenRouter. It stores the credential if the OpenRouter integration and key method are registered. Key validity fails later when a session actually uses an OpenRouter model.

More likely causes for local500 are OpenCode server/runtime errors outside the declared 204/400/401 path, for example the integration registry was not ready, the `openrouter` integration was missing its key method at the moment of the call, credential storage failed, or Talkt's wrapper returned a thrown generated-client error without converting it to a useful JSON error. The exact cause needs local server logs or a direct call to `agent.openrouterStatus()` / `integration.get({ integrationID: "openrouter" })` before and after connect.

Fix:

- Do not treat `/v1/agent/openrouter/key` success as key verification. Return `connected: true` only after `integration.get` shows an active OpenRouter connection, and add a separate lightweight model probe if product UX needs “this key works”.
- Wrap `connectOpenRouter` errors and include the OpenCode client error reason/status/kind in the Talkt JSON response. Do not collapse generated-client errors to `local500 UnexpectedStatus`.
- Before storing a new key, check `integration.get("openrouter")` and return a clear `openrouter_integration_not_ready` or `openrouter_key_method_missing` error if the key method is absent.

### P0. Current local FRA-527 branch still maps every signed-in app caller to the owner DO

Evidence:

- `worker/src/index.ts:49-50` authenticates the request and then unconditionally uses `env.TALKT_AGENT.getByName("owner")` for all non-runner/non-Telegram app routes.
- `worker/src/index.ts:133-151` currently accepts either app bearer token or Clerk JWT, but returns only a boolean. It does not return a Clerk subject or tenant/user identity.
- `worker/src/agent.ts:21-45` installs the personal profile plugin and runner plugin in every `TalktAgent` instance when `TALKT_RUNNER_TOKEN` exists.
- `worker/src/telegram-channel.ts:40` and `:87` correctly use the owner DO for Telegram, which is owner-only by `ownerMessage(...)`. That path should stay owner-scoped.

Why this is a bug:

In the current uncommitted branch, any valid Clerk user who reaches the Worker app API talks to the owner Durable Object and therefore gets the owner personal profile and Mac runner plugin. The production Clerk task deployed from `/Users/francescooddo/.t3/worktrees/Talkt/t3code-e6ef1bf3` changes the auth surface, so merging FRA-527 as-is would regress isolation.

Fix:

- Change auth to return an identity object, not boolean, for example `{ kind: "owner" } | { kind: "clerk", sub: string } | { kind: "appToken" }`.
- Map the explicit owner Clerk `sub` and Telegram/runner routes to `getByName("owner")`.
- Map every other Clerk subject to a separate DO name derived from the subject, for example `clerk:${sha256(sub)}`.
- Only install `personalProfilePlugin` and `runnerPlugin` in the owner DO. Non-owner Clerk DOs should keep ordinary OpenRouter/chat behavior without local Mac authority.
- Add a test that a non-owner Clerk subject cannot see `personal_exec`, `personal_mcp_call`, or personal profile tool definitions and cannot enqueue runner jobs.

## Final narrow pass addendum 2026-09-05

### P1 before enabling Notion write: direct native writes can replay after an ambiguous send

Evidence:

- `worker/src/tool-approvals.ts:26-31` returns from `require()` immediately after a stored `decision === "once"`; it does not consume the approval or bind it to a persisted execution outcome.
- `worker/src/approval-plugin.ts:8-10` gates `native_notion_write` in `execute.before` using that approval store.
- `worker/src/native-connectors.ts:227-232` executes `native_notion_write` by calling `requestNotion()` directly from the tool callback.
- `worker/src/native-connectors.ts:183-191` sends the Notion HTTP request and returns the body, but no durable operation record is written before send or after response.
- By contrast, runner-backed writes go through a durable job plus local journal. `worker/src/runner-plugin.ts:15,34` uses stable `sessionID:callID` job IDs, `worker/src/runner-jobs.ts:25-47` deduplicates Worker jobs, and `runner/src/journal.ts:17-35` records started/completed execution locally.

Impact:

If Notion write credentials are configured, a retry of the same OpenCode tool call after a Worker restart, network timeout, or SDK retry can reuse the exact approval and send the same non-idempotent Notion write again. POST `/pages`, `/comments`, `/file_uploads`, and block append/update paths are affected by the current allowlist. The new exact approval removes arbitrary argument drift, but it does not make direct external writes exactly-once.

Fix:

Gate direct native writes through a persisted operation wrapper keyed by `sessionID:callID:tool`. Store `{ action, inputHash, state: started|completed, result }` before sending the HTTP request. On repeated call with the same key and completed result, return the saved result. On repeated call with started/no result, fail closed with an `outcome_unknown` style error that tells the user to inspect Notion before retrying. Keep `native_notion_read` direct.

### No new P0 found in the final files inspected

- Approval hook naming matches the actual tool names. `worker/src/approval-plugin.ts:8-10` checks `native_google`, and the tool is registered as `native_google` in `worker/src/native-connectors.ts:236-243`.
- Installed OpenCode plugin typings support the selected hook. `worker/node_modules/@opencode-ai/plugin/dist/promise/tool.d.ts:28-36` defines `execute.before` with `tool`, `sessionID`, `id`, and `input`; `worker/node_modules/@opencode-ai/plugin/dist/promise/permission.d.ts:19-20` exposes only permission `list/get/reply` plus hooks.
- SDK pins are upgraded consistently to `0.0.0-dev-19146` in `worker/package.json:14-16` and `bun.lock:12-14`.
- Personal plugin isolation is now subject-based. `worker/src/index.ts:50-54` maps only `PERSONAL_OWNER_SUB` to `owner`; other signed-in Clerk users get `clerk:${identity}`. `worker/src/agent.ts:24-52` installs personal profile/native/runner plugins only for the owner DO. Regression evidence is in `worker/test/auth.test.ts:111-119`.
- Telegram chat ownership is strict private-owner only. `worker/src/telegram.ts:16-19` requires sender id, chat type private, and chat id to match `TELEGRAM_OWNER_ID`; `/status`, `/result`, `/approve`, and `/deny` are implemented in `worker/src/telegram-channel.ts:124-146`.

### Decision log proof gaps still present

`docs/research/fra-527-decisions.tsv:3-4` claims BotFather/getMe confirmation and Wrangler/Clerk/dashboard verification. I did not find those artifacts under `docs/research`, `worker/test`, or `runner/tests` in this pass. The claims may be true from operator history, but the repository-visible evidence is absent. `docs/research/fra-527-decisions.tsv:5` cites `/tmp/talkt-fra527-review.md`; that file exists on this machine but is outside the repository, so the decision log is not self-contained for future reviewers.
