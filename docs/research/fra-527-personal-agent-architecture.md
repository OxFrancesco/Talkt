# Personal agent capabilities and chat access

Reviewed on 2026-09-05 for [FRA-527](https://linear.app/francesco-oddo/issue/FRA-527/opencodev2-agent-on-cloudflare-spawnable-from-telegram-and-other-chat).

## Recommendation

Keep the existing OpenCode v2 agent inside its Cloudflare Durable Object. Add a versioned personal profile and an authenticated tool runner. Add Telegram separately as another client of that agent.

The approved design uses direct provider MCPs and APIs. Google uses the existing gog or gws CLI through an authenticated Mac runner. GitHub can use gh through that runner or its direct remote MCP. Linear uses its provider MCP and Notion uses its API with an integration token. No Composio integration is included. The Mac runner reuses installed CLIs and local MCP configuration. A Cloudflare Sandbox remains an optional later addition for portable tools.

Implementation is in progress. See fra-527-implementation-plan.md for current evidence and unverified account connections.

## Baseline inspected before implementation

- `worker/package.json` pins `@opencode-ai/sdk` to `0.0.0-dev-18729`.
- `worker/src/agent.ts` creates `OpenCodeWorkerd` using Durable Object storage. It configures OpenRouter and does not pass personal plugins.
- `worker/src/index.ts` routes authenticated app calls to `TALKT_AGENT.getByName("owner")`.
- Sessions are keyed by primary/fallback role and model. They are not yet independent user-selected conversations. Session metadata assumes Android and voice.
- `worker/wrangler.jsonc` declares `talkt-api`, a SQLite Durable Object, and no Sandbox binding.
- Production `/health` returned success. The authenticated `/v1/agent/status` probe returned HTTP 403. The deployed SDK version and provider connection therefore remain unverified.

The installed `@opencode-ai/server/dist/workerd.js` disables process spawning, local filesystem reading/listing/search, PTYs, and filesystem plugin discovery. The [Cloudflare SDK documentation](https://opencode.ai/v2/docs/build/sdk/cloudflare/) describes this embedded runtime and bundled plugin entry point. A Worker cannot acquire local CLI execution merely by copying configuration.

## Workstream A: the full personal setup

### Profile and instructions

Create a private, versioned profile containing the global AGENTS.md, skill directories with supporting scripts and references, translated MCP definitions, configuration preferences, and a tool inventory. Load project AGENTS.md only when operating in that project. Keep the source wording and record any runtime-specific adaptation separately.

Use a bundled OpenCode plugin to register skill content and inject the global instructions. The installed plugin package supports `ctx.skill.transform` with `add`, and its agent schema has a `system` field. Do not assume the Workerd runtime discovers files from `~/.agents` or `/Users/francescooddo`. Supporting files need an explicit read mechanism through the runner or a private artifact store. Skills should load on demand rather than placing every skill body in every prompt. See [OpenCode plugins](https://opencode.ai/v2/docs/build/plugins/).

The local inventory found 199 skill entries with 110 distinct SKILL.md contents across `.agents/skills`, `.codex/skills`, `.claude/skills`, and Codex system skills. This includes the design, animation, review, TypeScript, research, verification, Apple release, Cloudflare, BuddyTG, and writing workflows. Deduplicate shared files while preserving names, supporting files, and precedence.

The import must also enumerate enabled plugin skills and project-specific configuration. Those are not included in the 110 count. Codex-specific tools referenced by a skill need an OpenCode equivalent or an explicit unavailable state. Copying a skill does not create its tool dependencies.

### Account connectors

Connect provider MCP servers individually and use the installed gog or gws through the runner for Google. Keep account credentials on the Mac where possible. Cloud provider tokens use Worker secrets and are never embedded in the profile. Both chat channels call the same personal Durable Object.

[Linear](https://linear.app/docs/mcp) and [GitHub](https://github.com/github/github-mcp-server/blob/main/docs/host-integration.md) accept token-based remote MCP authentication. [Hosted Notion MCP](https://developers.notion.com/guides/mcp/get-started-with-mcp) requires OAuth and does not accept an ordinary Notion integration token. Its older token-based MCP server is unmaintained. The implementation uses the native Notion API until a dedicated OAuth callback is configured. [gws](https://github.com/googleworkspace/cli/blob/main/README.md) supports headless credentials, but explicitly is not an officially supported Google product. Existing CLI credentials should stay on the Mac unless a separate cloud authorization or transfer is chosen.

OpenCode v2 configuration uses `mcp.servers` and `disabled`, unlike the local legacy `mcp.<name>` and `enabled` configuration. Its documented OAuth flow uses a local callback listener. Do not assume that flow works unchanged inside a Durable Object. Use hosted account authorization, an explicit cloud callback implementation, or a runner-managed OAuth flow. See [OpenCode MCP configuration](https://opencode.ai/v2/docs/mcp-servers).

The enabled local Codex plugins include Gmail, Google Calendar, Google Drive, Linear, Notion, GitHub, Cloudflare, Convex, Figma, Sentry, Granola, Expo, and artifact tools. Treat this as a capability inventory, not portable authenticated MCP configuration. Their existing host-managed access must be replaced with supported external connectors or implementations where needed. Report gaps individually rather than claiming complete parity.

Composio does not host arbitrary local custom tools through its session MCP endpoint. Its client-side tool modifiers also do not run through that endpoint. Keep custom tools in OpenCode or the runner, and enforce requested action permissions where execution actually occurs. See [MCP trade-offs](https://docs.composio.dev/docs/sessions-via-mcp#trade-offs).

### MCPs and CLI tools

The inspected configurations contain Convex, shadcn, node_repl, disabled computer-use, Playwright, Vercel, Exa, chrome-devtools, Mobbin, and open-computer-use. Preserve disabled entries as disabled. Remote HTTP servers can connect directly where their authentication works; stdio servers need the runner. Desktop integration processes need their original host or a verified equivalent.

The checked CLI set includes Bun/Bunx, OpenCode/OpenCode2, Codex, Claude, GitHub CLI, Git, Codeview, BuddyTG, gog, gws, xurl, asc, EAS, ADB, CAP, FFmpeg, ripgrep, jq, Python, uv, Docker, Tailscale, OpenClaw, Atuin, and mcporter. The inventory also records executables in `.local/bin` and `.bun/bin`, including personal tools such as Bee and herdr. Installed does not prove frequently used or authenticated. Wrangler is available as a project dependency rather than a standalone executable on PATH; keep using Bunx for it.

Use one authenticated runner protocol for command execution, files, job status/cancellation, and stdio MCP access. Run commands in explicit project directories. An outbound connection from the Mac avoids requiring an unauthenticated public shell endpoint. Scope runner credentials to the owner and track jobs by OpenCode session and tool-call identity.

The Mac runner is the shortest route to existing Keychain-backed authentication, Helium, Xcode, local repositories, and physical devices. The Mac must be reachable for those tools. A Linux Cloudflare Sandbox can run portable tools but cannot reproduce macOS desktop and Apple build capabilities. The OpenCode agent remains in the Durable Object in either case. See [Cloudflare command execution](https://developers.cloudflare.com/sandbox/guides/execute-commands/).

## Workstream B: Telegram and other chat apps

Add a Telegram Bot API webhook to the Worker. Validate its webhook secret and Francesco's sender identity. Normalize incoming messages into the same application-level conversation API used by Talkt.

Persist the mapping from owner, channel, chat, and thread to OpenCode session ID. Support `/new`, `/sessions`, `/resume`, and `/stop`. Opening Telegram should not implicitly create a second agent identity or discard app history. A user can select the same conversation from either client, while separate conversations remain separate.

Store incoming update IDs before acknowledging webhook delivery, and process each event once. Persist outgoing replies with retry state. Long runs need durable dispatch and recovery, not a webhook request held open until completion. Forward permission requests to the active channel and bind responses to the exact owner, session, and action. Telegram documents webhook secrets, retries, update identifiers, and replies in its [Bot API](https://core.telegram.org/bots/api).

Other chat apps implement the same message and reply adapter. They do not install a second connector catalog or their own copy of the skills. Add a chat SDK only if its adapters reduce the work for the actual next channels.

## Suggested delivery sequence

1. Import and inspect the profile, with a named status for every skill, connector, MCP, and CLI. Preserve original instructions and explicitly map host-specific dependencies.
2. Prove one account read, one skill with a supporting file, one CLI job, and one local MCP call through the existing OpenCodeWorkerd host. Then migrate the remaining inventory.
3. Add explicit conversation IDs to the app API. Keep model fallback within the intended conversation history.
4. Add Telegram and verify start, resume, stop, owner checks, duplicate updates, reply retries, and recovery after interruption.
5. Verify an app conversation resumed through Telegram sees the same prior context and account connections. Verify Linux and Mac tool routing separately.

Implementation is complete only when the inventory reports working entries or named limitations and the real app-to-agent and Telegram-to-agent flows pass. A configuration import, successful deployment, or health response alone is insufficient.

## Inventory artifact

[fra-527-personal-agent-inventory.json](fra-527-personal-agent-inventory.json) records source paths, instruction hashes, skill hashes, configured MCP names, enabled plugin names, executable names, and PATH checks. It contains no credential values. The inventory is a starting snapshot, not a completed migration.

## Integration update after deployment

The separate Clerk task deployed SDK 0.0.0-dev-19146 and account isolation. This implementation preserves that authentication. The verified Clerk subject for oddofrancesco000@gmail.com now selects the existing personal owner object. Other users receive isolated agents without personal plugins, credentials, instructions or runner access. The initial 18729 SDK and 403 observations above describe the research baseline, not current production.

The dedicated Telegram bot is @TalktFrancescoBot, owned by Francesco and restricted to Telegram user 904041730 in private chat. Its token is private. Live webhook delivery, model replies and approved GitHub and Google Drive calls passed. The signed-in Fold sees the same conversation. The runner starts at login and requires the Mac awake with the SSD mounted.

The current private profile contains 208 skill contents, 25 MCP configuration entries, 28 enabled plugins and 105 CLI names. These are inventory counts, not claims that every connector is authenticated or every host-specific skill can run on Cloudflare. CLI and MCP results are journaled by the Mac runner to avoid re-executing interrupted writes. Exact custom-tool approvals are stored separately because SDK options.permission only filters the tool catalog.
