# Talkt connector verification

September 6, 2026

Nine providers passed authenticated read requests. Figma remains blocked by desktop login. Six new OAuth grants are stored privately on the Mac and loaded by the restarted Talkt runner.

| Provider | Verified read |
| --- | --- |
| Linear | Current user and personal workspace |
| Notion | Current user |
| Sentry | Personal organization |
| Granola | Account and workspace |
| Expo | Existing Android build |
| Cloudflare | Personal account Workers list |
| Convex | Project deployment status and production table schema |
| GitHub | Authenticated user through gh |
| Google | Drive listing through gog |
| Figma | Not connected. Remote client registration returned HTTP 403. Desktop browser approval did not complete login after restart and a first-run reset. |

## Implementation

The runner reads a private Talkt MCP configuration after existing Codex, Claude and OpenCode configurations. OAuth uses mcp-remote 0.8.3. Large MCP catalogs now return summaries, and an exact tool lookup returns the full input schema. This fixes Linear discovery exceeding the 64 KiB result limit.

Both TypeScript checks passed. Seven runner tests and 31 focused Worker tests passed. The Worker deployment is d39b968c-59ad-4ed2-b8ca-6e8b28768c5c. The Mac runner restarted without configuration warnings.

A fresh Telegram conversation completed tool-schema discovery, explicit approval, a Linear current-user call through the cloud agent and Mac runner, and a visible reply with the verified account name. CAP recorded this verification. The previous conversation was busy, so the test used /new.

Provider reads verify authentication and read access. Write actions were not exercised. Mac connections require the Mac to remain awake. The Worker's direct-adapter status does not include these runner connections; use personal_mcp_list to discover them.

Figma's supported desktop setup requires a signed-in desktop app. See the [official setup instructions](https://developers.figma.com/docs/figma-mcp-server/local-server-installation/). Completing that login and enabling the desktop MCP server remains the next step.
