import type { Plugin } from "@opencode-ai/plugin"
import type { Mcp } from "@opencode-ai/schema/mcp"
import { z } from "zod"

type Secrets = Readonly<Record<string, string | undefined>>

const remoteServerSchema = z.object({
  id: z.string().regex(/^[a-z][a-z0-9_-]{0,47}$/),
  url: z.string().url().refine((value) => {
    const url = new URL(value)
    return url.protocol === "https:" && !url.username && !url.password && !url.search && !url.hash
      && !url.port && /^[a-z0-9-]+(?:\.[a-z0-9-]+)+$/.test(url.hostname)
      && !/\.(localhost|local|internal|test)$/.test(url.hostname)
      && !/^\d+(?:\.\d+){3}$/.test(url.hostname)
  }, "MCP requires an HTTPS public hostname without credentials, query, fragment or custom port"),
  tokenSecret: z.string().regex(/^NATIVE_[A-Z0-9_]+_TOKEN$/),
}).strict()

export type NativeRemoteServer = z.infer<typeof remoteServerSchema>
export const nativeRemoteServersSchema = z.array(remoteServerSchema).max(32).superRefine((servers, ctx) => {
  const ids = new Set<string>()
  for (const server of servers) {
    if (ids.has(server.id)) ctx.addIssue({ code: "custom", message: "Duplicate native MCP id" })
    ids.add(server.id)
  }
})

const builtInServers: NativeRemoteServer[] = [
  { id: "linear", url: "https://mcp.linear.app/mcp", tokenSecret: "NATIVE_LINEAR_TOKEN" },
  { id: "github", url: "https://api.githubcopilot.com/mcp/", tokenSecret: "NATIVE_GITHUB_TOKEN" },
  { id: "cloudflare", url: "https://mcp.cloudflare.com/mcp", tokenSecret: "NATIVE_CLOUDFLARE_TOKEN" },
]

export const nativeConnectorCatalog = [
  { id: "linear", transport: "remote_mcp", authorization: "provider_token", url: "https://mcp.linear.app/mcp" },
  { id: "github", transport: "remote_mcp", authorization: "provider_token", url: "https://api.githubcopilot.com/mcp/" },
  { id: "cloudflare", transport: "remote_mcp", authorization: "personal_account_token", url: "https://mcp.cloudflare.com/mcp" },
  { id: "notion", transport: "native_rest", authorization: "integration_token", url: "https://api.notion.com/v1/" },
  { id: "google", transport: "mac_runner", authorization: "local_gws_or_gog_login" },
  { id: "figma", transport: "remote_mcp", authorization: "provider_client_approval_and_oauth", url: "https://mcp.figma.com/mcp" },
  { id: "sentry", transport: "remote_mcp", authorization: "provider_oauth", url: "https://mcp.sentry.dev/mcp" },
  { id: "granola", transport: "remote_mcp", authorization: "provider_oauth", url: "https://mcp.granola.ai/mcp" },
  { id: "expo", transport: "remote_mcp", authorization: "provider_oauth_and_optional_local_runner", url: "https://mcp.expo.dev/mcp" },
  { id: "convex", transport: "mac_runner", authorization: "local_convex_login_and_project" },
]

export const googleRunnerRequestSchema = z.object({
  command: z.enum(["gws", "gog"]),
  args: z.array(z.string().max(16_384).refine((value) => !value.includes("\0"))).min(1).max(128),
}).strict()
export type GoogleRunnerRequest = z.infer<typeof googleRunnerRequestSchema>
export type GoogleRunnerContext = { readonly sessionID: string; readonly id: string }

export type NativeConnectorOptions = {
  readonly secrets: Secrets
  readonly durableWrite?: (id: string, action: string, input: unknown, execute: () => Promise<string>) => Promise<string>
  readonly remoteServers?: readonly NativeRemoteServer[]
  readonly googleRunner?: (request: GoogleRunnerRequest, context: GoogleRunnerContext) => Promise<string>
}

export function nativeMcpConfigs(options: NativeConnectorOptions): ReadonlyMap<string, Mcp.ServerConfig> {
  const servers = nativeRemoteServersSchema.parse([...builtInServers, ...options.remoteServers ?? []])
  const configs = new Map<string, Mcp.ServerConfig>()
  for (const server of servers) {
    const token = options.secrets[server.tokenSecret]?.trim()
    if (!token) continue
    if (/[\r\n]/.test(token)) throw new Error("Invalid native connector token")
    configs.set(`native_${server.id}`, {
      type: "remote",
      url: server.url,
      headers: { Authorization: `Bearer ${token}` },
      oauth: false,
      disabled: false,
      timeout: { startup: 15_000, catalog: 15_000, execution: 30_000 },
    })
  }
  return configs
}

export function nativeConnectorStatus(options: NativeConnectorOptions) {
  const configured = nativeMcpConfigs(options)
  const known = new Set(nativeConnectorCatalog.map((connector) => connector.id))
  return [
    ...nativeConnectorCatalog.map((connector) => ({
      ...connector,
      status: configured.has(`native_${connector.id}`)
        || connector.id === "notion" && Boolean(options.secrets.NATIVE_NOTION_TOKEN?.trim())
        || connector.id === "google" && Boolean(options.googleRunner)
        ? "configured_unverified" : "authorization_or_runner_required",
    })),
    ...(options.remoteServers ?? []).filter((server) => !known.has(server.id)).map((server) => ({
      id: server.id,
      transport: "remote_mcp",
      status: configured.has(`native_${server.id}`) ? "configured_unverified" : "authorization_required",
    })),
  ]
}

const notionRequestSchema = z.object({
  path: z.string().min(1).max(1024).regex(/^\/(?:[a-z0-9_-]+\/)*[a-z0-9_-]+$/i),
  query: z.record(z.string().regex(/^[a-z_]+$/), z.string().max(1024)).optional(),
  body: z.record(z.string(), z.unknown()).optional(),
}).strict()

function validNotionRoute(method: string, path: string, write: boolean): boolean {
  const id = "[a-zA-Z0-9_-]+"
  const reads = new RegExp(`^/(?:users(?:/${id})?|pages/${id}(?:/properties/${id}|/markdown)?|blocks/${id}(?:/children)?|databases/${id}|data_sources/${id}|comments|file_uploads(?:/${id})?)$`)
  if (!write) return method === "GET" && reads.test(path)
    || method === "POST" && new RegExp(`^/(?:search|data_sources/${id}/query)$`).test(path)
  if (method === "POST") return /^\/(pages|databases|data_sources|comments|file_uploads)$/.test(path)
  if (method === "PATCH") return new RegExp(`^/(?:pages/${id}(?:/markdown)?|databases/${id}|data_sources/${id}|blocks/${id}(?:/children)?)$`).test(path)
  return method === "DELETE" && new RegExp(`^/blocks/${id}$`).test(path)
}

export const notionReadSchema = notionRequestSchema.extend({ method: z.enum(["GET", "POST"]) })
  .refine((input) => validNotionRoute(input.method, input.path, false), "Not a supported Notion read endpoint")
  .refine((input) => input.method !== "GET" || input.body === undefined, "GET requests cannot include a body")
export const notionWriteSchema = notionRequestSchema.extend({ method: z.enum(["POST", "PATCH", "DELETE"]) })
  .refine((input) => validNotionRoute(input.method, input.path, true), "Not a supported Notion write endpoint")

function redact(text: string, secrets: Secrets): string {
  let result = text
  for (const secret of Object.values(secrets)) {
    if (secret?.trim()) result = result.replaceAll(secret.trim(), "[redacted]")
  }
  return result
}

export async function requestGoogle(options: {
  readonly runner: NonNullable<NativeConnectorOptions["googleRunner"]>
  readonly input: unknown
  readonly context: GoogleRunnerContext
  readonly secrets: Secrets
}): Promise<string> {
  const input = googleRunnerRequestSchema.parse(options.input)
  try {
    return redact((await options.runner(input, options.context)).slice(0, 256_000), options.secrets)
  } catch {
    throw new Error("Google runner request failed; check runner availability and local authorization")
  }
}

async function boundedResponse(response: Response): Promise<string> {
  const reader = response.body?.getReader()
  if (!reader) return ""
  const chunks: Uint8Array[] = []
  let bytes = 0
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      bytes += value.byteLength
      if (bytes > 256_000) {
        await reader.cancel()
        throw new Error("Notion response exceeded 256000 bytes; request a smaller page")
      }
      chunks.push(value)
    }
  } finally {
    reader.releaseLock()
  }
  const data = new Uint8Array(bytes)
  let offset = 0
  for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.byteLength }
  return new TextDecoder().decode(data)
}

export async function requestNotion(options: {
  readonly input: unknown
  readonly write: boolean
  readonly secrets: Secrets
  readonly fetcher?: typeof fetch
}): Promise<string> {
  const input = (options.write ? notionWriteSchema : notionReadSchema).parse(options.input)
  const token = options.secrets.NATIVE_NOTION_TOKEN?.trim()
  if (!token || /[\r\n]/.test(token)) throw new Error("Notion authorization required")
  const url = new URL(`https://api.notion.com/v1${input.path}`)
  for (const [key, value] of Object.entries(input.query ?? {})) url.searchParams.set(key, value)
  const body = input.body === undefined ? undefined : JSON.stringify(input.body)
  if (body && new TextEncoder().encode(body).length > 256_000) throw new Error("Notion request exceeded 256000 bytes")
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 20_000)
  try {
    const response = await (options.fetcher ?? fetch)(url, {
      method: input.method,
      redirect: "manual",
      headers: { Authorization: `Bearer ${token}`, "Notion-Version": "2026-03-11", "Content-Type": "application/json" },
      body,
      signal: controller.signal,
    })
    if (!response.ok) throw new Error(`Notion request failed with HTTP ${response.status}`)
    return redact(await boundedResponse(response), options.secrets)
  } catch (error) {
    if (error instanceof Error && /^Notion (request failed with HTTP \d{3}|response exceeded 256000 bytes; request a smaller page)$/.test(error.message)) throw error
    throw new Error("Notion request failed or timed out")
  } finally {
    clearTimeout(timeout)
  }
}

export function nativeConnectorsPlugin(options: NativeConnectorOptions) {
  const configs = nativeMcpConfigs(options)
  return {
    id: "talkt-native-connectors",
    async setup(ctx) {
      await ctx.mcp.transform((servers) => {
        for (const [id, config] of configs) servers.set(id, config)
      })
      await ctx.permission.hook("evaluate", (request) => {
        if (request.effect === "deny") return
        if (["native_notion_write", "native_google"].includes(request.action)) request.effect = "ask"
        if (["connector_status", "native_notion_read"].includes(request.action)) request.effect = "allow"
      })
      await ctx.tool.transform((tools) => {
        tools.add({
          name: "connector_status",
          description: "Show native connector configuration and missing authorization. Configured does not mean authenticated or connected.",
          input: z.object({}).strict(),
          execute: async () => ({ content: JSON.stringify(nativeConnectorStatus(options)) }),
        })
        if (options.secrets.NATIVE_NOTION_TOKEN?.trim()) {
          tools.add({
            name: "native_notion_read",
            description: "Read Notion pages, blocks, databases, data sources, users and comments; POST /search or /data_sources/{id}/query. Paths start after /v1. Access is limited to content shared with the integration. API version 2026-03-11.",
            input: notionReadSchema,
            execute: async (input) => ({ content: await requestNotion({ input, write: false, secrets: options.secrets }) }),
          })
          tools.add({
            name: "native_notion_write",
            description: "Create or update Notion pages, blocks, databases, data sources and comments, or archive blocks. Requires user approval. Paths start after /v1. API version 2026-03-11.",
            input: notionWriteSchema,
            options: { permission: "native_notion_write" },
            execute: async (input, context) => {
              if (!options.durableWrite) throw new Error("Durable native writes are not configured")
              return { content: await options.durableWrite(`${context.sessionID}:${context.id}`, "native_notion_write", input, () => requestNotion({ input, write: true, secrets: options.secrets })) }
            },
          })
        }
        const runner = options.googleRunner
        if (runner) tools.add({
          name: "native_google",
          description: "Run the native Google Workspace CLI gws or gog through the private Mac runner. Credentials remain on the Mac. Pass argv without shell syntax. Requires user approval; runner must enforce its own allowlist and redact output.",
          input: googleRunnerRequestSchema,
          options: { permission: "native_google" },
          execute: async (input, context) => ({ content: await requestGoogle({
            runner, input, context: { sessionID: context.sessionID, id: context.id }, secrets: options.secrets,
          }) }),
        })
      })
    },
  } satisfies Plugin.Plugin
}
