import { mkdir, readFile, stat, writeFile } from "node:fs/promises"
import { homedir } from "node:os"
import { join, resolve } from "node:path"
import { z } from "zod"

const root = resolve(import.meta.dir, "../..")
const directory = join(homedir(), ".config/talkt")
const keyPath = join(directory, "chatgpt-relay.pk8")
await mkdir(directory, { recursive: true, mode: 0o700 })
let publicKey: CryptoKey
if (await Bun.file(keyPath).exists()) {
  if ((await stat(keyPath)).mode & 0o077) throw new Error("The ChatGPT relay key must have mode 600")
  const privateKey = await crypto.subtle.importKey("pkcs8", Uint8Array.from(await readFile(keyPath)), { name: "RSA-OAEP", hash: "SHA-256" }, true, ["decrypt"])
  const jwk = await crypto.subtle.exportKey("jwk", privateKey)
  publicKey = await crypto.subtle.importKey("jwk", { kty: "RSA", n: jwk.n, e: jwk.e, alg: "RSA-OAEP-256", ext: true, key_ops: ["encrypt"] }, { name: "RSA-OAEP", hash: "SHA-256" }, true, ["encrypt"])
} else {
  const keys = await crypto.subtle.generateKey({ name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["encrypt", "decrypt"])
  await writeFile(keyPath, Buffer.from(await crypto.subtle.exportKey("pkcs8", keys.privateKey)), { flag: "wx", mode: 0o600 })
  publicKey = keys.publicKey
}
await writeFile(join(root, "worker/src/chatgpt-relay-public.json"), JSON.stringify({ publicKey: Buffer.from(await crypto.subtle.exportKey("spki", publicKey)).toString("base64") }) + "\n")
const configPath = join(directory, "mcp.json")
const config = z.object({ mcp: z.record(z.string(), z.unknown()).default({}) }).passthrough().parse(await Bun.file(configPath).exists() ? JSON.parse(await readFile(configPath, "utf8")) : {})
config.mcp.talkt_chatgpt_relay = { type: "local", command: [process.execPath, join(root, "runner/src/chatgpt-relay-mcp.ts")], environment: { TALKT_CHATGPT_RELAY_KEY_FILE: keyPath } }
await writeFile(configPath, JSON.stringify(config, null, 2) + "\n", { mode: 0o600 })
process.stdout.write("ChatGPT relay configured. Restart the Mac runner and deploy the Worker to load the public key.\n")
