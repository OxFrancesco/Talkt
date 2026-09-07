import { mkdir, writeFile } from "node:fs/promises"
import { dirname, resolve } from "node:path"
import { importPersonalProfile } from "pigent/profile-importer"

if (process.argv.includes("--help")) {
  process.stdout.write("Usage: bun scripts/import-personal-profile.ts [output.json]\n")
} else {
  const output = resolve(process.argv[2] ?? "worker/src/generated/personal-profile.json")
  const profile = await importPersonalProfile()
  await mkdir(dirname(output), { recursive: true })
  await writeFile(output, JSON.stringify(profile, null, 2) + "\n", { mode: 0o600 })
  process.stdout.write(JSON.stringify({ output, skills: profile.skills.length, mcps: profile.mcps.length, plugins: profile.plugins.length, clis: profile.clis.length, gaps: profile.gaps.length }) + "\n")
}
