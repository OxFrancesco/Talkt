# Pigent extraction

Talkt consumes the private GitHub repository `OxFrancesco/Pigent` through a Git dependency named `pigent`, pinned to a full commit SHA.

`worker/src/agent.ts` supplies the owner check, private profile, provider secrets, model configuration, legacy session identity, and Android prompt response. Pigent owns the runtime, conversations, approvals, native tools, runner jobs, and profile importer.

The Mac runner consumes `pigent/runner`. Telegram and Android keep their current API and conversation IDs. The Cloudflare Worker name, Durable Object bindings, class export, migrations, and storage keys are unchanged.

## Install

Git credentials must have read access to both Talkt and Pigent. Keep the `@` before `github.com` in the dependency URL so Bun uses the Git credential helper. A sibling Pigent checkout is not required. Run `bun install --frozen-lockfile` in Talkt and `bun install --cwd runner --frozen-lockfile` for the Mac runner.

Talkt still requires its private profile at `worker/src/generated/personal-profile.json`. Generate it with `bun scripts/import-personal-profile.ts`. The script calls Pigent's importer and writes only to Talkt's ignored local file. Generate Worker environment types with `bun run --cwd worker types`.

Run `bun run check`, `bun run test`, `bun run --cwd runner check`, and `bun run --cwd runner test` before deployment. The Worker bundle can be checked with `bun run --cwd worker deploy --dry-run`.

Pigent has a separate `test:concurrency` probe that records an unresolved concurrent cold-start limitation in Workerd. Passing the standard tests does not prove that case or a production deployment.
