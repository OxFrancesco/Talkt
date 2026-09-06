import { afterEach, expect, test } from "bun:test"
import { mkdtemp, mkdir, writeFile, symlink, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { importPersonalProfile, importSkills, sanitizeMcp } from "./import-personal-profile"

const temporary: string[] = []
async function fixture() { const root = await mkdtemp(join(tmpdir(), "talkt-profile-")); temporary.push(root); return root }
afterEach(async () => { await Promise.all(temporary.splice(0).map((path) => rm(path, { recursive: true, force: true }))) })

async function skill(root: string, folder: string, body: string) {
  const path = join(root, folder)
  await mkdir(path, { recursive: true })
  await writeFile(join(path, "SKILL.md"), `---\nname: example\ndescription: Useful instructions\n---\n${body}`)
  return path
}

test("deduplicates symlinks and identical content while preserving distinct name collisions", async () => {
  const root = await fixture()
  const first = await skill(root, "one", "First instructions")
  await skill(root, "copy", "First instructions")
  await skill(root, "two", "Different instructions")
  await symlink(first, join(root, "alias"))
  const skills = await importSkills([root, root])
  expect(skills).toHaveLength(2)
  expect(new Set(skills.map((entry) => entry.name)).size).toBe(2)
  expect(skills.find((entry) => entry.content.includes("First instructions"))?.sourcePaths).toHaveLength(2)
})

test("indexes support files without embedding credentials or binary contents", async () => {
  const root = await fixture()
  const dir = await skill(root, "one", "Run scripts/example.py")
  await mkdir(join(dir, "scripts"))
  await writeFile(join(dir, "scripts/example.py"), "print('hello')")
  await writeFile(join(dir, ".env"), "API_KEY=very-private-value")
  await writeFile(join(dir, "credentials.json"), "very-private-value")
  await writeFile(join(dir, "model.bin"), Buffer.from([0, 255, 13]))
  const [imported] = await importSkills([root])
  expect(imported?.supportFiles.map((file) => file.relativePath).sort()).toEqual(["model.bin", "scripts/example.py"])
  expect(imported?.supportFiles.find((file) => file.relativePath === "model.bin")?.text).toBe(false)
  expect(JSON.stringify(imported)).not.toContain("very-private-value")
  expect(JSON.stringify(imported)).not.toContain("print('hello')")
})

test("refuses to embed likely credentials in instructions", async () => {
  const root = await fixture()
  await skill(root, "unsafe", "ghp_" + "a".repeat(40))
  await expect(importSkills([root])).rejects.toThrow("Refusing to import likely credentials")
})

test("MCP inventory excludes credentials, raw arguments, headers and URL parameters", () => {
  const result = sanitizeMcp("native", { command: "/bin/tool", args: ["--api-key", "private-argument", "--token=private-inline", "--debug"], env: { API_TOKEN: "private-env" }, url: "https://user:private-password@example.com/mcp?token=private-query", headers: { Authorization: "private-header" } }, "/config")
  expect(result.command).toBe("tool")
  expect(result.argumentFlags).toEqual(["--api-key", "--debug"])
  expect(result.environmentKeys).toEqual(["API_TOKEN"])
  expect(result.endpointOrigin).toBe("https://example.com")
  expect(JSON.stringify(result)).not.toContain("private-")
})

test("imports AGENTS.md exactly and only enabled cached plugin skills", async () => {
  const home = await fixture()
  await mkdir(join(home, ".codex"))
  const instructions = "# My instructions\n\nUse Bun.\n"
  await writeFile(join(home, ".codex/AGENTS.md"), instructions)
  await writeFile(join(home, ".codex/config.toml"), '[plugins."active@openai-curated"]\nenabled = true\n[plugins."inactive@openai-curated"]\nenabled = false\n[mcp_servers.test]\ncommand="test-cli"\n[mcp_servers.test.env]\nTOKEN="not-imported"\n')
  await skill(join(home, ".codex/plugins/cache/openai-curated-remote/active/1.0.0/skills"), "use", "Active package")
  await skill(join(home, ".codex/plugins/cache/openai-curated-remote/inactive/1.0.0/skills"), "use", "Inactive package")
  const profile = await importPersonalProfile({ home, path: "" })
  expect(profile.instructions?.content).toBe(instructions)
  expect(profile.skills).toHaveLength(1)
  expect(profile.skills[0]?.content).toContain("Active package")
  expect(profile.plugins.map((plugin) => plugin.name)).toEqual(["active@openai-curated"])
  expect(profile.mcps[0]?.environmentKeys).toEqual(["TOKEN"])
  expect(JSON.stringify(profile)).not.toContain("not-imported")
})
