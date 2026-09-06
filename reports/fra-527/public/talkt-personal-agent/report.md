# Talkt personal agent

September 5, 2026. Implementation is deployed. Connector authorization remains in progress.

The dedicated bot is [@TalktFrancescoBot](https://t.me/TalktFrancescoBot). Telegram and the signed-in Android app use the same personal OpenCode v2 agent on Cloudflare. Other Talkt accounts remain isolated.

## Verified

- A real Telegram message received a model response from OpenCode.
- A Telegram-approved GitHub CLI call returned the owner's account login through the Mac runner.
- The signed-in app on the Fold read the same conversation and Telegram test reply.
- A native Google Drive read returned one file after renewing the existing gog login. Only the file count was repeated in Telegram.
- The profile loads 208 skill contents, 25 MCP configuration records, 28 enabled plugin entries and 105 CLI names, plus global AGENTS.md. Support files and project instructions are read through the Mac runner.
- Worker tests cover Clerk isolation, exact action approvals, durable operations, runner expiry and replay, native connector boundaries and real OpenCode startup. Runner tests exercise real subprocesses and a real stdio MCP server.

## Use it

Send a message to the bot to continue the current conversation. Use `/new`, `/sessions`, `/resume`, `/stop` and `/status`. The bot shows exact action arguments and offers `/approve` and `/deny`. `/result` retrieves an app run that is still working after the app's initial request returns.

The Mac runner starts at login. The Mac must be awake and the SSD mounted for local CLIs, local MCPs, Google and Apple or device tools. Cloud-only conversation continues without it.

## Still needs connection

Linear's direct MCP and Notion's native API are implemented but have no dedicated provider credentials yet. Other remote MCPs require their own provider authorization. Codex-managed connectors and host-injected tools are not portable credentials. The imported inventory records those gaps; it does not claim that every entry is authenticated or executable.

No Composio service is used. Telegram is the first additional chat client. Other chat platforms can reuse the conversation and run API when selected.

[Track the remaining work in FRA-527](https://linear.app/francesco-oddo/issue/FRA-527/opencodev2-agent-on-cloudflare-spawnable-from-telegram-and-other-chat).
