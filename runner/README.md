> Retired: the Talkt API no longer exposes runner or agent endpoints. This directory is retained to preserve pending local work; do not start it for transcription.

# Personal Mac runner

This Bun process lets the Cloudflare OpenCode v2 agent use local CLI tools, files and configured MCP servers. It polls the Worker over authenticated HTTPS. It does not open a listening port or require a tunnel.

The Mac must be awake and this process must be running. The CLI does not install a launch service itself. The installed owner service is documented below.

## Run

From the repository root:

```sh
bun install --cwd runner
bun run --cwd runner check
bun run --cwd runner test
bun run --cwd runner selftest
```

Provision a separate runner token in the Worker and store the same token in a private local file. Do not reuse the app API token or Telegram bot token. Set the token file to mode `600`.

```sh
export TALKT_API_URL="https://your-talkt-worker.example"
export TALKT_RUNNER_TOKEN_FILE="$HOME/.config/talkt/runner-token"
export TALKT_RUNNER_ROOTS='["/Volumes/T6-7/Coding/Personal/Talkt","/Users/francescooddo/.agents/skills","/Users/francescooddo/.codex/skills"]'
bun run --cwd runner start
```

`TALKT_RUNNER_TOKEN` is also supported when an existing process supervisor injects secrets through the environment. The token file takes precedence. Credentials are never supplied as command arguments.

The default roots are this repository and the user's `.agents/skills`, `.codex/skills` and `.claude/skills` directories. `TALKT_RUNNER_ROOTS` replaces them with a JSON array of absolute paths. Add other repositories explicitly when the agent needs them.

Optional settings:

| Variable | Default | Purpose |
| --- | --- | --- |
| `TALKT_RUNNER_ID` | Mac hostname plus API-origin hash | Stable runner identity |
| `TALKT_RUNNER_STATE_DIR` | `~/.local/state/talkt-runner/<API-origin-hash>` | Private execution journal and process lock |

Run `bun run --cwd runner src/main.ts --inventory` to list configured MCP names and status without connecting to them or printing commands, arguments, headers or credential values. Changes to local MCP configuration take effect when the runner restarts.

## Supported jobs

The protocol is in the `pigent/runner` package export.

- `exec` launches a binary with a literal `argv` array and an approved working directory. It inherits the Mac process environment except `TALKT_*` variables. Commands must finish without interactive terminal input. The default timeout is 60 seconds and the maximum is 120 seconds. A timeout kills the process group. Output reports the exit code, signal, timeout and truncation flags.
- `read_file` reads an approved regular file. Both operations resolve symlinks before checking roots and require absolute paths.
- `mcp_list` with no server lists configured servers. With a server name it initializes that server and returns its tool schemas. Large catalogs return names and descriptions. Pass both `server` and `tool` to retrieve one complete input schema.
- `mcp_call` initializes the named server and calls one tool using its native MCP interface.

`exec` and `read_file` retain up to 24 KiB of raw output. The complete serialized result is limited to 64 KiB of UTF-8 JSON. Larger results return `output_limit`; narrow the command or provider query. MCP tool calls use a 90-second request deadline and a 110-second overall deadline.

The runner has the local user's CLI permissions. Root checks constrain the working directory and direct file reads. They do not sandbox what a CLI or trusted MCP process can access. This runner belongs to the authenticated personal agent and must not accept jobs from untrusted chat users.

## Native MCP configuration

The runner reads these files in ascending precedence. A later server with the same name replaces the earlier definition, including its disabled state.

1. `~/.codex/config.toml`, `mcp_servers`
2. `~/.claude.json`, global `mcpServers`
3. `~/.config/opencode/opencode.json`, `mcp`
4. `~/.config/talkt/mcp.json`, `mcp`

Talkt-specific OAuth connections use the pinned `mcp-remote` dependency as a stdio bridge. Complete the provider's browser authorization before running unattended jobs. Store its OAuth files in `~/.config/talkt/mcp-auth` with directory mode `700` and the configuration file with mode `600`. These files stay outside the repository. Restart the runner with `SIGTERM` after changing configuration so it finishes its current job first.

The installed entries are `talkt_linear`, `talkt_notion`, `talkt_sentry`, `talkt_granola`, `talkt_expo` and `talkt_cloudflare`. GitHub and Google use `gh` and `gog`; Convex uses the existing local MCP configuration. Cloudflare authorization is limited to the personal account. Figma is not connected while its desktop login handoff is failing.

The Worker's native connector status describes direct Worker adapters. Discover these Mac connections with `personal_mcp_list`; a configured name alone is not proof that its authorization still works.

Supported configuration includes command and argument arrays, local environment values, working directories, remote URLs, static headers, `http_headers`, `env_http_headers`, `bearer_token_env_var`, `${ENV_NAME}` and OpenCode `{env:ENV_NAME}` references. Disabled entries remain visible and cannot execute. Missing environment variables and unsupported entries remain visible as unavailable. JSON files must contain JSON, not JSONC.

Remote MCPs use Streamable HTTP by default. An explicit `type: "sse"` uses the older SSE transport. Remote servers must use HTTPS, except local loopback servers. The runner does not downgrade transports or run an OAuth flow automatically.

OAuth sessions inside Codex, Claude or another host do not become runner credentials merely because their server URL is copied. Supply a provider-supported token reference or a standalone authenticated MCP. Runtime-owned tools such as Codex `node_repl`, browser control and app connector sessions need an independent implementation. Installed plugin metadata alone cannot provide their hosted runtime.

Google CLI tools such as `gws` or `gog`, GitHub's `gh`, BuddyTG and other installed binaries run through `exec` using their existing local authentication. The runner does not create accounts or change OAuth grants.

Known credential values from the runner environment and MCP environment/header configuration are redacted from returned text. That is a protection against accidental output, not a complete secret scanner. CLI requests should avoid printing credential files or full environments. Logs contain job IDs, request kinds and status, never raw command arguments or tool outputs.

## Delivery and recovery

The runner posts `{runnerID}` to `/v1/runner/poll`. The Worker returns `{job:null}` or a job containing `id`, `leaseToken` and `request`. The runner posts `{runnerID,id,leaseToken,result}` to `/v1/runner/result`.

The private SQLite journal records a started job before executing it. It records the completed result with full synchronous durability before uploading it. A repeated job ID returns its saved result, even with a new lease. Reusing an ID with different arguments returns `job_conflict`.

After a crash, a started job without a recorded result returns `outcome_unknown`. The runner never repeats that action automatically. Inspect the target before requesting a new job. This avoids replaying a write whose result was lost, but cannot guarantee that an interrupted action did not already happen.

Upload failures retry the saved result. A `409` or `410` lease response stops that upload attempt and keeps the journal for redelivery. Authentication errors stop the runner. `SIGINT` and `SIGTERM` stop polling after the current job finishes. Do not delete the journal to fix a connection problem; it prevents duplicate actions.

## Verification

Bun tests exercise real subprocess argument handling, token environment removal, timeouts, output limits, symlink traversal rejection, restart recovery and duplicate IDs. They also start a real local stdio MCP server and verify tool discovery, calls, disabled states, configuration precedence and redaction. `selftest` executes Bun locally and reads only the MCP inventory. It does not contact the Worker or prove a connected account works.

## Installed on Francesco's Mac

The personal runner is installed as `org.buddytools.talkt.runner` in the user's LaunchAgents directory. It starts after login and restarts on exit. The token is read from `~/.config/talkt/runner-token`, with mode `600`. The private journal persists under `~/.local/state/talkt-runner/`. Logs contain job IDs and outcome kinds, not command arguments or credentials, at `~/Library/Logs/Talkt/runner.log`.

Check or stop this service:

```sh
launchctl print gui/501/org.buddytools.talkt.runner
launchctl bootout gui/501/org.buddytools.talkt.runner
```

Start it again:

```sh
launchctl bootstrap gui/501 ~/Library/LaunchAgents/org.buddytools.talkt.runner.plist
```

The configured executable is `/opt/homebrew/bin/bun`. The SSD containing Talkt must be mounted. The Mac must be awake. A Cloudflare-only agent conversation still works when the runner is offline; local CLI and MCP calls wait for it and eventually return an explicit timeout.
# ChatGPT subscription relay

Telegram Settings connects the ChatGPT subscription through OpenCode V2 device login. Cloudflare currently receives HTTP 403 from the ChatGPT model endpoint, so Talkt retries that specific HTML rejection through the Mac runner. The model and provider remain OpenAI.

Run `bun runner/src/setup-chatgpt-relay.ts` from Talkt to create or reuse the private relay key and register the local MCP. Deploy the Worker after generating its public key, then restart the runner. The private key stays in `~/.config/talkt/chatgpt-relay.pk8` with mode 600. Only the public key belongs in the repository.

The relay accepts only POST requests to the ChatGPT responses endpoint, refuses redirects, and encrypts credentials and response segments before journal storage. Requests time out after 80 seconds. Uncompressed responses are limited to 4 MB and encrypted packets to 1 MB. Segments remain readable for ten minutes and stale files are removed on the next relay startup. The Mac must stay awake and online for subscription requests.
