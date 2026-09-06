import { describe, expect, it, vi } from "vitest"
import {
  googleRunnerRequestSchema, nativeConnectorStatus, nativeConnectorsPlugin, nativeMcpConfigs,
  nativeRemoteServersSchema, notionReadSchema, notionWriteSchema, requestGoogle, requestNotion,
} from "../src/native-connectors"

describe("native connector configuration", () => {
  it("registers exact V2 remote configs only when provider tokens exist", () => {
    const configs = nativeMcpConfigs({ secrets: { NATIVE_LINEAR_TOKEN: "linear-test", NATIVE_GITHUB_TOKEN: "github-test" } })
    expect([...configs.keys()]).toEqual(["native_linear", "native_github"])
    expect(configs.get("native_linear")).toEqual({
      type: "remote", url: "https://mcp.linear.app/mcp", headers: { Authorization: "Bearer linear-test" },
      oauth: false, disabled: false, timeout: { startup: 15_000, catalog: 15_000, execution: 30_000 },
    })
    expect(nativeConnectorsPlugin({ secrets: {} }).id).toBe("talkt-native-connectors")
  })

  it("does not misreport tokens as authenticated connections or leak them", () => {
    const status = nativeConnectorStatus({ secrets: { NATIVE_LINEAR_TOKEN: "secret-marker" } })
    expect(status.find((item) => item.id === "linear")?.status).toBe("configured_unverified")
    expect(status.find((item) => item.id === "notion")?.status).toBe("authorization_or_runner_required")
    expect(JSON.stringify(status)).not.toContain("secret-marker")
    expect(nativeMcpConfigs({ secrets: {} }).size).toBe(0)
  })

  it.each(["http://mcp.example.com/mcp", "https://localhost/mcp", "https://127.0.0.1/mcp", "https://[::1]/mcp", "https://mcp.example.local/mcp", "https://user:pass@mcp.example.com/mcp", "https://mcp.example.com/mcp?token=secret"])("rejects unsafe deployment URL %s", (url) => {
    expect(nativeRemoteServersSchema.safeParse([{ id: "extra", url, tokenSecret: "NATIVE_EXTRA_TOKEN" }]).success).toBe(false)
  })

  it("rejects duplicate ids, arbitrary secret names, inline tokens and header injection", () => {
    const server = { id: "extra", url: "https://mcp.example.com/mcp", tokenSecret: "NATIVE_EXTRA_TOKEN" }
    expect(nativeRemoteServersSchema.safeParse([server, server]).success).toBe(false)
    expect(nativeRemoteServersSchema.safeParse([{ ...server, tokenSecret: "OPENROUTER_API_KEY" }]).success).toBe(false)
    expect(nativeRemoteServersSchema.safeParse([{ ...server, token: "inline" }]).success).toBe(false)
    expect(() => nativeMcpConfigs({ secrets: { NATIVE_LINEAR_TOKEN: "x\r\nInjected: y" } })).toThrow("Invalid native connector token")
  })
})

describe("Notion boundaries", () => {
  it.each(["//evil.example/x", "/../oauth/token", "/pages/%2e%2e", "/pages/x?url=https://evil.example", "/pages/x#fragment", "https://evil.example", "/oauth/token"])("rejects unscoped path %s", (path) => {
    expect(notionReadSchema.safeParse({ method: "GET", path }).success).toBe(false)
    expect(notionWriteSchema.safeParse({ method: "POST", path }).success).toBe(false)
  })

  it("keeps POST searches separate from POST writes", () => {
    expect(notionReadSchema.safeParse({ method: "POST", path: "/search", body: { query: "Talkt" } }).success).toBe(true)
    expect(notionReadSchema.safeParse({ method: "POST", path: "/data_sources/abc/query", body: {} }).success).toBe(true)
    expect(notionReadSchema.safeParse({ method: "POST", path: "/pages", body: {} }).success).toBe(false)
    expect(notionWriteSchema.safeParse({ method: "POST", path: "/pages", body: {} }).success).toBe(true)
    expect(notionReadSchema.safeParse({ method: "GET", path: "/users/me", body: {} }).success).toBe(false)
  })

  it("calls only Notion, disables redirects and redacts returned tokens", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response('{"text":"secret-marker"}'))
    const result = await requestNotion({ input: { method: "GET", path: "/pages/page-id", query: { page_size: "5" } }, write: false, secrets: { NATIVE_NOTION_TOKEN: "secret-marker" }, fetcher })
    expect(result).toBe('{"text":"[redacted]"}')
    expect(String(fetcher.mock.calls[0]?.[0])).toBe("https://api.notion.com/v1/pages/page-id?page_size=5")
    expect(fetcher.mock.calls[0]?.[1]?.redirect).toBe("manual")
    expect(fetcher.mock.calls[0]?.[1]?.headers).toEqual({ Authorization: "Bearer secret-marker", "Notion-Version": "2026-03-11", "Content-Type": "application/json" })
  })

  it("does not expose upstream errors or fetch without authorization", async () => {
    const fetcher = vi.fn<typeof fetch>().mockRejectedValue(new Error("credential secret-marker"))
    await expect(requestNotion({ input: { method: "GET", path: "/users/me" }, write: false, secrets: {}, fetcher })).rejects.toThrow("Notion authorization required")
    expect(fetcher).not.toHaveBeenCalled()
    await expect(requestNotion({ input: { method: "GET", path: "/users/me" }, write: false, secrets: { NATIVE_NOTION_TOKEN: "secret-marker" }, fetcher })).rejects.toThrow("Notion request failed or timed out")
  })

  it("bounds returned bodies and rejects unsupported requests before fetching", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response("x".repeat(256_001)))
    await expect(requestNotion({ input: { method: "GET", path: "/users/me" }, write: false, secrets: { NATIVE_NOTION_TOKEN: "secret" }, fetcher })).rejects.toThrow("Notion response exceeded")
    fetcher.mockClear()
    await expect(requestNotion({ input: { method: "POST", path: "/pages", body: {} }, write: false, secrets: { NATIVE_NOTION_TOKEN: "secret" }, fetcher })).rejects.toThrow()
    expect(fetcher).not.toHaveBeenCalled()
  })
})

it("Google accepts argv for gws and gog only", () => {
  expect(googleRunnerRequestSchema.safeParse({ command: "gws", args: ["drive", "files", "list"] }).success).toBe(true)
  expect(googleRunnerRequestSchema.safeParse({ command: "sh", args: ["-c", "echo nope"] }).success).toBe(false)
  expect(googleRunnerRequestSchema.safeParse({ command: "gog", args: ["bad\0argument"] }).success).toBe(false)
})

it("Google forwards stable session and tool-call IDs on replay and redacts output", async () => {
  const runner = vi.fn().mockResolvedValue("result secret-marker")
  const options = {
    runner, input: { command: "gws", args: ["gmail", "users", "messages", "send"] },
    context: { sessionID: "session-one", id: "tool-call-one" }, secrets: { NATIVE_TEST_TOKEN: "secret-marker" },
  }
  expect(await requestGoogle(options)).toBe("result [redacted]")
  await requestGoogle(options)
  expect(runner.mock.calls[0]).toEqual([options.input, options.context])
  expect(runner.mock.calls[1]).toEqual(runner.mock.calls[0])
})
