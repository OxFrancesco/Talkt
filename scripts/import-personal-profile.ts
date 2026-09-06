import { createHash } from "node:crypto"
import { readdir, readFile, realpath, stat, mkdir, writeFile, access } from "node:fs/promises"
import { constants } from "node:fs"
import { basename, dirname, extname, join, resolve } from "node:path"
import { homedir } from "node:os"
import type { PersonalProfile, PersonalSkill, ProfileFile, ProfileMcp } from "../worker/src/profile-types"

const hash = (content: string) => createHash("sha256").update(content).digest("hex")
const record = (value: unknown): Record<string, unknown> => typeof value === "object" && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : {}
const strings = (value: unknown): string[] => Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : []
const exists = async (path: string) => access(path).then(() => true, () => false)
const textExtensions = new Set([".md", ".txt", ".json", ".jsonc", ".yaml", ".yml", ".toml", ".ts", ".tsx", ".js", ".mjs", ".cjs", ".py", ".sh", ".bash", ".zsh", ".html", ".css", ".sql", ".svg", ".csv", ".xml"])
const excluded = /^(?:\.git|node_modules|\.env(?:\..*)?|auth\.json|credentials?(?:\..*)?|secrets?(?:\..*)?|.*\.(?:pem|key|p12|pfx|sqlite|db))$/i

function assertNoSecrets(content: string, source: string) {
  if (/-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,}|sk-(?:proj-|or-v1-)?[A-Za-z0-9_-]{24,}|xox[baprs]-[A-Za-z0-9-]{20,}|[0-9]{8,12}:[A-Za-z0-9_-]{30,})\b/.test(content)) {
    throw new Error(`Refusing to import likely credentials from ${source}. Remove the credential before importing.`)
  }
}

async function walk(root: string, visited = new Set<string>()): Promise<string[]> {
  if (!await exists(root)) return []
  const canonical = await realpath(root)
  if (visited.has(canonical)) return []
  visited.add(canonical)
  const files: string[] = []
  for (const entry of (await readdir(root, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
    if (excluded.test(entry.name)) continue
    const path = join(root, entry.name)
    const info = await stat(path).catch(() => null)
    if (info?.isDirectory()) files.push(...await walk(path, visited))
    else if (info?.isFile()) files.push(path)
  }
  return files
}

function metadata(content: string, key: string): string | undefined {
  const front = /^---\r?\n([\s\S]*?)\r?\n---/.exec(content)?.[1]
  const value = front?.split(/\r?\n/).find((line) => line.startsWith(`${key}:`))?.slice(key.length + 1).trim()
  return value?.replace(/^(["'])(.*)\1$/, "$2")
}

export async function importSkills(roots: readonly string[]): Promise<PersonalSkill[]> {
  const byHash = new Map<string, PersonalSkill>()
  const seenPaths = new Set<string>()
  for (const root of roots) {
    for (const path of (await walk(root)).filter((file) => basename(file) === "SKILL.md")) {
      const canonical = await realpath(path)
      if (seenPaths.has(canonical)) continue
      seenPaths.add(canonical)
      const content = await readFile(path, "utf8")
      assertNoSecrets(content, path)
      const sha256 = hash(content)
      const supportFiles: ProfileFile[] = []
      for (const file of await walk(dirname(path))) {
        if (file === path) continue
        const info = await stat(file)
        supportFiles.push({ sourcePath: file, relativePath: file.slice(dirname(path).length + 1), bytes: info.size, text: textExtensions.has(extname(file)) })
      }
      const prior = byHash.get(sha256)
      if (prior) {
        byHash.set(sha256, { ...prior, sourcePaths: [...prior.sourcePaths, path], supportFiles: [...prior.supportFiles, ...supportFiles] })
        continue
      }
      const hostSpecific = /(?:\bmcp__\w+|\bfunctions\.|\bcollaboration\.|\bnodeRepl\b|\bcua\.|skill:\/\/|\borchestrator\b|Codex add-in)/.test(content)
      byHash.set(sha256, {
        id: `personal-${sha256.slice(0, 16)}`,
        name: (metadata(content, "name") || basename(dirname(path))).replace(/[^a-zA-Z0-9_-]/g, "-"),
        description: metadata(content, "description") || `Imported skill from ${basename(dirname(path))}`,
        content, sha256, sourcePaths: [path], supportFiles,
        autoinvoke: metadata(content, "disable-model-invocation") !== "true",
        portability: hostSpecific ? "host-adapter-required" : supportFiles.length || /\b(?:CLI|bash|bun |python|xcodebuild|adb )/.test(content) ? "runner-required" : "instructions",
      })
    }
  }
  const skills = [...byHash.values()]
  const counts = new Map<string, number>()
  for (const skill of skills) counts.set(skill.name, (counts.get(skill.name) ?? 0) + 1)
  return skills.map((skill) => ({ ...skill, name: (counts.get(skill.name) ?? 0) > 1 ? `${skill.name}-${skill.sha256.slice(0, 8)}` : skill.name }))
}

export function sanitizeMcp(name: string, value: unknown, source: string): ProfileMcp {
  const config = record(value)
  const command = typeof config.command === "string" ? config.command : strings(config.command)[0]
  const args = Array.isArray(config.command) ? strings(config.command).slice(1) : strings(config.args)
  let endpointOrigin: string | null = null
  if (typeof config.url === "string") {
    try { endpointOrigin = new URL(config.url).origin } catch { /* Runtime variable reference, not a concrete URL. */ }
  }
  const transport = command ? "stdio" : endpointOrigin ? "remote" : "host-managed"
  return {
    name, source, enabled: config.enabled !== false && config.disabled !== true, transport,
    command: command ? basename(command) : null,
    argumentFlags: args.filter((arg) => /^--?[a-zA-Z][a-zA-Z0-9-]*$/.test(arg)),
    endpointOrigin,
    environmentKeys: [...new Set([...Object.keys(record(config.env)), ...strings(config.env_vars), ...Object.keys(record(config.environment)), ...Object.keys(record(config.env_http_headers)), ...(typeof config.bearer_token_env_var === "string" ? [config.bearer_token_env_var] : [])])].sort(),
    portability: transport === "stdio" ? "Requires a tool runner and its local MCP configuration." : transport === "remote" ? "Requires native MCP registration and separate authentication. Credentials are not imported." : "Requires an equivalent adapter. Host-managed tools and sessions do not transfer.",
  }
}

async function configFile(path: string): Promise<Record<string, unknown>> {
  if (!await exists(path)) return {}
  const content = await readFile(path, "utf8")
  if (path.endsWith(".toml")) return record(Bun.TOML.parse(content))
  return record(JSON.parse(content))
}

const commonClis = ["bun", "bunx", "node", "git", "gh", "wrangler", "buddytg", "codeview", "opencode", "codex", "claude", "agent-browser", "cap", "adb", "fastboot", "xcodebuild", "xcrun", "swift", "asc", "eas", "expo", "convex", "docker", "python3", "uv", "uvx", "pipx", "go", "cargo", "rustc", "ffmpeg", "ffprobe", "magick", "pdftoppm", "pdftotext", "qpdf", "tailscale", "openclaw", "portless", "atuin", "xurl", "gog", "gcloud", "gws", "linear", "notion", "jq", "rg", "curl", "wget", "ssh", "rsync", "brew"]

export async function importPersonalProfile(options: { readonly home?: string; readonly path?: string } = {}): Promise<PersonalProfile> {
  const home = options.home ?? homedir()
  const codexPath = join(home, ".codex/config.toml")
  const codex = await configFile(codexPath)
  const roots = [".agents/skills", ".codex/skills", ".claude/skills"].map((path) => join(home, path))
  const gaps: string[] = []
  const mcps: ProfileMcp[] = []
  const plugins: { name: string; sourcePath: string | null }[] = []
  for (const [name, value] of Object.entries(record(codex.plugins))) {
    if (record(value).enabled !== true) continue
    const [pluginName, marketplace] = name.split("@")
    let sourcePath: string | null = null
    const marketplaces = [...new Set([marketplace, marketplace === "openai-curated" ? "openai-curated-remote" : marketplace])].filter((item): item is string => Boolean(item))
    for (const market of marketplaces) {
      const root = join(home, ".codex/plugins/cache", market, pluginName)
      if (!await exists(root)) continue
      const versions = (await readdir(root)).sort((a, b) => b.localeCompare(a, undefined, { numeric: true }))
      const version = versions[0]
      if (version) { sourcePath = join(root, version); break }
    }
    plugins.push({ name, sourcePath })
    if (!sourcePath) { gaps.push(`Enabled plugin ${name} has no local cached package.`); continue }
    roots.push(join(sourcePath, "skills"))
    const mcpPaths = [".mcp.json", "mcp.json", ".codex-plugin/mcp.json"].map((path) => join(sourcePath, path))
    for (const mcpPath of mcpPaths) {
      const config = await configFile(mcpPath)
      for (const [mcpName, configValue] of Object.entries(record(config.mcpServers))) mcps.push(sanitizeMcp(`${name}/${mcpName}`, configValue, mcpPath))
    }
  }
  for (const [name, value] of Object.entries(record(codex.mcp_servers))) mcps.push(sanitizeMcp(name, value, codexPath))
  for (const relative of [".claude.json", ".mcp.json", ".claude/settings.json", ".config/opencode/opencode.json"]) {
    const source = join(home, relative)
    const config = await configFile(source)
    for (const [name, value] of Object.entries(record(config.mcpServers ?? config.mcp))) mcps.push(sanitizeMcp(name, value, source))
  }
  const instructionsPath = join(home, ".codex/AGENTS.md")
  const instructionsContent = await exists(instructionsPath) ? await readFile(instructionsPath, "utf8") : null
  if (instructionsContent) assertNoSecrets(instructionsContent, instructionsPath)
  const skills = await importSkills(roots)
  const cliNames = new Set(commonClis)
  for (const localBin of [".local/bin", ".bun/bin"]) {
    const directory = join(home, localBin)
    if (await exists(directory)) for (const name of await readdir(directory)) cliNames.add(name)
  }
  for (const mcp of mcps) if (mcp.command) cliNames.add(mcp.command)
  const paths = (options.path ?? process.env.PATH ?? "").split(":").filter(Boolean)
  const clis: { name: string; sourcePath: string; portability: string }[] = []
  for (const name of [...cliNames].sort()) {
    for (const path of paths) {
      const file = join(path, name)
      if (await access(file, constants.X_OK).then(() => true, () => false) && (await stat(file)).isFile()) {
        clis.push({ name, sourcePath: file, portability: /^(xcodebuild|xcrun|swift|asc|adb|fastboot|cap|brew)$/.test(name) ? "Mac or device runner required." : "Tool runner required. Installation and authentication are not copied." })
        break
      }
    }
  }
  gaps.push("CLI inventory covers known tools and executables in ~/.local/bin and ~/.bun/bin, plus configured MCP commands. It is not an inventory of every program installed on the Mac.", "Account credentials, raw MCP arguments, headers, config files, browser sessions and tokens are excluded. Connections require native adapters or the owner's runner.", "Enabled plugin packages are selected from the newest cached version. Host-injected plugin capabilities may not have portable MCP configuration.", "Only global ~/.codex/AGENTS.md is embedded. Project instructions must be read from the active checkout by the tool runner.", "Skill support files are indexed by path and read through the owner runner. Binaries are not copied. Mac-only commands need the Mac online.")
  if (!instructionsContent) gaps.push("Global ~/.codex/AGENTS.md was not found or is empty.")
  return {
    version: 1, generatedAt: new Date().toISOString(),
    instructions: instructionsContent === null ? null : { sourcePath: instructionsPath, content: instructionsContent, sha256: hash(instructionsContent) },
    skills, mcps, plugins, clis, gaps,
  }
}

if (import.meta.main && process.argv.includes("--help")) {
  process.stdout.write("Usage: bun scripts/import-personal-profile.ts [output.json]\n")
} else if (import.meta.main) {
  const output = resolve(process.argv[2] ?? "worker/src/generated/personal-profile.json")
  const profile = await importPersonalProfile()
  await mkdir(dirname(output), { recursive: true })
  await writeFile(output, JSON.stringify(profile, null, 2) + "\n", { mode: 0o600 })
  process.stdout.write(JSON.stringify({ output, skills: profile.skills.length, mcps: profile.mcps.length, plugins: profile.plugins.length, clis: profile.clis.length, gaps: profile.gaps.length }) + "\n")
}
