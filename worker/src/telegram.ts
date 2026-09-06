import { z } from "zod"

export const telegramUpdateSchema = z.object({
  update_id: z.number().int().nonnegative(),
  message: z.object({
    message_id: z.number().int(),
    from: z.object({ id: z.number().int(), is_bot: z.boolean().optional() }).optional(),
    chat: z.object({ id: z.number().int(), type: z.string() }),
    message_thread_id: z.number().int().optional(),
    text: z.string().max(32_000).optional(),
  }).optional(),
})
export type TelegramUpdate = z.infer<typeof telegramUpdateSchema>
export type TelegramMessage = NonNullable<TelegramUpdate["message"]>

export function ownerMessage(update: TelegramUpdate, ownerID: string): TelegramMessage | undefined {
  const message = update.message
  if (!message || message.from?.is_bot || String(message.from?.id) !== ownerID || message.chat.type !== "private" || String(message.chat.id) !== ownerID) return
  return message
}

export function messageChunks(text: string): string[] {
  const chunks: string[] = []
  let chunk = ""
  for (const character of text) {
    if (chunk.length + character.length > 3500) { chunks.push(chunk); chunk = "" }
    chunk += character
  }
  if (chunk) chunks.push(chunk)
  return chunks.length ? chunks : ["Done."]
}

export class TelegramDeliveryError extends Error {
  constructor(readonly retryable: boolean, readonly retryAfter: number, status: number) { super(`Telegram delivery failed with HTTP ${status}`) }
}

export async function sendTelegram(token: string, message: TelegramMessage, text: string): Promise<void> {
  const response = await fetch(`https://api.telegram.org/bot${token}/sendMessage`, {
    method: "POST", redirect: "manual", headers: { "content-type": "application/json" },
    body: JSON.stringify({ chat_id: message.chat.id, text, ...(message.message_thread_id ? { message_thread_id: message.message_thread_id } : {}) }),
    signal: AbortSignal.timeout(15_000),
  })
  const body = z.object({ ok: z.boolean(), parameters: z.object({ retry_after: z.number() }).optional() }).safeParse(await response.json())
  if (!response.ok || !body.success || !body.data.ok) {
    throw new TelegramDeliveryError(response.status === 429 || response.status >= 500, body.success ? body.data.parameters?.retry_after ?? 10 : 10, response.status)
  }
}
