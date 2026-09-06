# Personal profile import

Run `bun scripts/import-personal-profile.ts` on the owner's Mac. The output is `worker/src/generated/personal-profile.json`. This file contains private instructions and skill contents. Keep the actual export out of Git and public reports. The repository sample is empty.

The importer reads global `~/.codex/AGENTS.md` without rewriting it. It imports skills from `~/.agents/skills`, `~/.codex/skills`, `~/.claude/skills`, and the newest cached package for each enabled Codex plugin. It deduplicates identical skill contents, avoids symlink cycles, and gives different skills with the same name a hash suffix. Each skill preserves its source locations and content hash.

Skill scripts, references and assets are indexed by source path. Their contents are not embedded. `profile_read` reads the imported instructions and SKILL.md directly, and delegates text support files to the configured runner callback. It rejects paths absent from the profile and directs binary assets to the runner. The runner must validate resolved paths too, since files and symlinks on the Mac can change after import.

MCP entries record their names, origin, command name, argument flag names and environment variable names. They omit argument values, environment values, headers, credentials and URL paths/parameters. Entries from different host configs stay separate. Enabling a plugin or listing an MCP does not establish an authenticated connection.

CLI discovery checks known tools, executable names in `~/.local/bin` and `~/.bun/bin`, and configured MCP commands against PATH. The inventory does not claim to include every installed program. It does not copy binaries or logins. Xcode, devices and Mac browser access need a Mac runner.

Install the SDK plugin with `personalProfilePlugin(profile, { readFile })` in `OpenCodeWorkerd.create({ plugins: [...] })`. The optional `readFile` callback receives an exact indexed source path and returns text. The plugin registers skills, appends global instructions to the `build` agent, and registers `profile_read`. `profileSummary(profile)` reports available inventory and portability gaps without copying instruction or skill bodies into a status response.

Host-specific skill instructions can refer to Codex tools that OpenCode does not implement. Such skills remain visible with a portability warning. Importing instructions is not proof those tools work. Project AGENTS.md files must be read from the active checkout when the runner works in a project.

Validation commands:

- `bun test scripts/import-personal-profile.test.ts`
- `bun run --cwd worker check`
- `bun run --cwd worker test -- test/personal-profile.test.ts`
