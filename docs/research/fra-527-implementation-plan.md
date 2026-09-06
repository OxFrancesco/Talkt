# FRA-527 implementation

- [x] Read the Principles section of poteto-mode.
- [x] Frame: preserve OpenCodeWorkerd, use native connectors, keep the owner's app and Telegram conversations consistent.
- [x] Design the workflow: separate writable worktrees for profile, connectors, and runner; parent owns agent and channels.
- [x] Baseline: Worker typecheck and 11 tests pass. Personal Cloudflare account verified.
- [x] Profile: import instructions and skills; index support files for runner reads; preserve configuration and explicit dependency gaps.
- [x] Native connectors: provider MCP/API configuration, connection status, no managed connector service.
- [x] Runner: outbound authenticated transport; local CLI and MCP execution; durable jobs and bounded output.
- [x] Conversations: create/list/resume/stop with the existing app default preserved.
- [x] Telegram: authenticated webhook, deduplication, durable runs and replies, command routing.
- [x] Aggregate: inspect every worker artifact and integrate only owned files.
- [x] Verify: real Workerd host loads profile and executes tools; local transport tests; deployment smoke checks; app and Telegram continuity.
- [x] Report: update FRA-527 and record exact verified results and pending account authorization.
- [ ] Authorize Linear, Notion and remaining remote MCPs, then verify each account through the agent.
- [ ] Complete live restart, duplicate-webhook and all conversation-command checks. Existing automated coverage does not replace these checks.
- [ ] Replace host-owned tool dependencies where a standalone implementation is available. Inventory import alone does not provide these tools.

Done means the configured capabilities execute through the real agent and an owner can start and resume the same conversation from the app and Telegram. Inventory entries requiring unavailable host tools or account authorization must be reported individually. Compilation is insufficient.

The approved implementation is deployed as Worker version `8d19b3dd-fd30-45e3-b434-8370cbbd521d`, using SDK `0.0.0-dev-19146`. The verified Clerk account for oddofrancesco000@gmail.com is bound to the existing personal agent. @TalktFrancescoBot is live, with its token stored privately. The Mac runner starts at login.

Validation passed: 72 Worker tests, 6 runner tests, typechecks, real Telegram model replies, approved GitHub and Google Drive calls, and a signed-in Fold test confirming the shared conversation. The Android test lives in the separate Clerk worktree at `app/src/androidTest/java/org/buddytools/talkt/auth/PersonalAgentInstrumentedTest.kt`; the installed app and unrelated Android work were preserved. Detailed evidence is in `evidence/fra-527-runtime.json`.

Report: https://reports.buddytools.org/talkt-personal-agent/. FRA-527 remains In Progress for the remaining authorizations and verification. Changes are uncommitted.

Decision trail: `docs/research/fra-527-decisions.tsv`.
