# Native connectors for the personal agent

The agent keeps `OpenCodeWorkerd` in its Durable Object. The `talkt-native-connectors` plugin adds provider MCP servers and native tools through the exact OpenCode v2 SDK, with no connector intermediary.

## Implemented

| Provider | Native route | Required authorization |
| --- | --- | --- |
| Linear | `https://mcp.linear.app/mcp` | Worker secret `NATIVE_LINEAR_TOKEN`, containing a Linear API key or provider bearer token |
| GitHub | `https://api.githubcopilot.com/mcp/` | `NATIVE_GITHUB_TOKEN`, containing a scoped GitHub PAT |
| Cloudflare | `https://mcp.cloudflare.com/mcp` | `NATIVE_CLOUDFLARE_TOKEN`, scoped to the personal account. Never use the Mentasuave account for Talkt |
| Notion | REST requests directly to `api.notion.com/v1` | `NATIVE_NOTION_TOKEN`, containing a Notion integration token. Share the required pages with that integration |
| Google | `gws` or `gog` through the private Mac runner | Local Google CLI login and an authenticated runner connection |

Configured MCP servers register only when their named secret exists. `connector_status` reports configuration availability, never successful authentication merely because a token exists. Verify authorization with a real provider read after setup.

Notion separates read operations from writes. Reads include search, pages, blocks, databases, data sources, comments, users and upload metadata. Queries use `/data_sources/{id}/query`. Writes cover page and database creation, data-source creation and updates, block changes, comments and upload creation. Binary upload transfer is not implemented. The API version is pinned to `2026-03-11`.

Notion paths are confined to named API routes. Requests cannot supply a hostname, headers or an OAuth endpoint. Redirects fail. Calls have a 20-second deadline and 256000-byte request and response limits. Upstream error bodies are excluded from errors. Known configured tokens are redacted from returned text.

`native_notion_write` and generic `native_google` calls request SDK permission before execution. Existing deny decisions remain denied. Google command arguments are passed as an argv array to the runner. The runner must enforce its own command policy and redact its locally held credentials. Remote MCP calls use the host's MCP permission policy. The host must present pending approvals to the app or Telegram and resume after a reply.

## Provider-specific setup still required

| Provider | Verified official route | Remaining requirement |
| --- | --- | --- |
| Notion MCP | `https://mcp.notion.com/mcp` | Provider OAuth if choosing MCP instead of the implemented REST integration |
| Figma | `https://mcp.figma.com/mcp` | Figma currently restricts access to catalog clients. A custom Talkt client needs provider approval and OAuth, or uses the existing supported client on the Mac |
| Sentry | `https://mcp.sentry.dev/mcp` | Provider OAuth |
| Granola | `https://mcp.granola.ai/mcp` | Per-user browser OAuth. The MCP service does not offer API keys or service accounts |
| Expo | `https://mcp.expo.dev/mcp` | Provider OAuth. Simulator and local development tools also need the Mac runner |
| Convex | Official `convex mcp start` CLI | Project-local CLI execution and Convex login through the runner |

These entries describe available native routes. They are not registered as working tools without authorization. This plugin does not silently copy another application's OAuth cache, refresh expired OAuth tokens, or implement a complete provider authorization UI.

## OpenCode v2 integration

Install the same pinned versions of `@opencode-ai/sdk`, `@opencode-ai/plugin` and `@opencode-ai/schema`. Add `nativeConnectorsPlugin(options)` to the host's `plugins` array.

The plugin calls `ctx.mcp.transform` and `servers.set`. Each remote config uses v2 fields `type: "remote"`, `url`, `headers`, `oauth: false`, `disabled: false` and a structured `timeout` with startup, catalog and execution durations. This is not the desktop `mcpServers` format or a v1 numeric timeout.

Additional provider endpoints can be supplied in the deployment-owned `remoteServers` array. Entries contain `id`, `url` and `tokenSecret`. The secret reference must match `NATIVE_*_TOKEN`. Parse deployment JSON with `nativeRemoteServersSchema`. Do not accept this configuration as an agent tool argument or public request body. Only configure provider endpoints whose ownership and token authentication you have verified. The URL parser rejects inline credentials, local hostnames, IP literals, query strings and custom ports. It is configuration validation, not a DNS firewall.

`googleRunner` accepts `{ command: "gws" | "gog", args: string[] }` and a second context argument `{ sessionID: string, id: string }`, then returns text. The context comes from the SDK's session and tool-call identifiers. Derive the runner job key from those stable identifiers so crash recovery cannot submit the same Google write as a new job. Credentials stay on the Mac. When the callback is absent, no Google execution tool is registered.

The pinned SDK has `Tool.Options.permission` and `ctx.permission.hook("evaluate", ...)`. It has no `ctx.permission.ask` method. Write tools use their dedicated permission action and the hook changes an allowed evaluation to `ask`.

## Sources checked

- [OpenCode Workerd host and bundled plugins](https://opencode.ai/v2/docs/build/sdk/cloudflare/)
- [Linear MCP transport and token authentication](https://linear.app/docs/mcp)
- [GitHub MCP authentication](https://docs.github.com/en/copilot/how-tos/provide-context/use-mcp-in-your-ide/use-the-github-mcp-server)
- [Cloudflare MCP servers and API tokens](https://developers.cloudflare.com/agents/model-context-protocol/cloudflare/servers-for-cloudflare/)
- [Notion API versioning](https://developers.notion.com/reference/versioning) and [Notion MCP OAuth](https://developers.notion.com/guides/mcp/get-started-with-mcp)
- [Google Workspace CLI](https://github.com/googleworkspace/cli)
- [Figma remote MCP restrictions](https://developers.figma.com/docs/figma-mcp-server/remote-server-installation/)
- [Granola MCP authorization](https://docs.granola.ai/help-center/sharing/integrations/mcp)
- [Sentry MCP](https://mcp.sentry.dev/)
- [Expo MCP](https://docs.expo.dev/mcp/)
- [Convex MCP CLI](https://docs.convex.dev/ai/convex-mcp-server)
