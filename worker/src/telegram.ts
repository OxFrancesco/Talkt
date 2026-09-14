import { z } from "zod"

const telegramMessageSchema = z.object({
    message_id: z.number().int(),
    from: z.object({ id: z.number().int(), is_bot: z.boolean().optional() }).optional(),
    chat: z.object({ id: z.number().int(), type: z.string() }),
    message_thread_id: z.number().int().optional(),
    text: z.string().max(32_000).optional(),
  })
export const telegramUpdateSchema = z.object({
  update_id: z.number().int().nonnegative(),
  message: telegramMessageSchema.optional(),
  callback_query: z.object({
    id: z.string().min(1).max(200),
    from: z.object({ id: z.number().int(), is_bot: z.boolean().optional() }),
    message: telegramMessageSchema.optional(),
    data: z.string().max(64).optional(),
  }).optional(),
})
export type TelegramUpdate = z.infer<typeof telegramUpdateSchema>
export type TelegramMessage = NonNullable<TelegramUpdate["message"]>

export function ownerMessage(update: TelegramUpdate, ownerID: string): TelegramMessage | undefined {
  const message = update.message
  if (!message || message.from?.is_bot || String(message.from?.id) !== ownerID || message.chat.type !== "private" || String(message.chat.id) !== ownerID) return
  return message
}

export function ownerCallback(update: TelegramUpdate, ownerID: string) {
  const callback = update.callback_query
  if (!callback?.message || !callback.data || callback.from.is_bot || String(callback.from.id) !== ownerID
    || callback.message.chat.type !== "private" || String(callback.message.chat.id) !== ownerID) return
  return { ...callback, message: callback.message, data: callback.data }
}

export type TelegramButton = { text: string } & ({ callback_data: string } | { url: string } | { copy_text: { text: string } })
export type TelegramKeyboard = TelegramButton[][]
export type TelegramOptions = { html?: boolean; keyboard?: TelegramKeyboard }

export function escapeHTML(text: string): string {
  return text.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;")
}

export function telegramHTML(text: string): string {
  const tokens = /```(?:[^\n`]*\n)?([\s\S]*?)```|`([^`\n]+)`|\*\*([^*\n]+)\*\*|\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/g
  let result = "", cursor = 0
  for (const match of text.matchAll(tokens)) {
    result += escapeHTML(text.slice(cursor, match.index))
    if (match[1] !== undefined) result += `<pre>${escapeHTML(match[1])}</pre>`
    else if (match[2] !== undefined) result += `<code>${escapeHTML(match[2])}</code>`
    else if (match[3] !== undefined) result += `<b>${escapeHTML(match[3])}</b>`
    else result += `<a href="${escapeHTML(match[5])}">${escapeHTML(match[4])}</a>`
    cursor = match.index + match[0].length
  }
  return result + escapeHTML(text.slice(cursor))
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

export async function telegramRequest(token: string, method: string, input: unknown): Promise<unknown> {
  const response = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
    method: "POST", redirect: "manual", headers: { "content-type": "application/json" },
    body: JSON.stringify(input),
    signal: AbortSignal.timeout(15_000),
  })
  const body = z.object({ ok: z.boolean(), result: z.unknown().optional(), parameters: z.object({ retry_after: z.number() }).optional() }).safeParse(await response.json())
  if (!response.ok || !body.success || !body.data.ok) {
    throw new TelegramDeliveryError(response.status === 429 || response.status >= 500, body.success ? body.data.parameters?.retry_after ?? 10 : 10, response.status)
  }
  return body.data.result
}

export async function sendTelegram(token: string, message: TelegramMessage, text: string, options: TelegramOptions = {}): Promise<number> {
  const result = await telegramRequest(token, "sendMessage", {
    chat_id: message.chat.id, text, ...(options.html ? { parse_mode: "HTML" } : {}),
    link_preview_options: { is_disabled: true },
    ...(options.keyboard ? { reply_markup: { inline_keyboard: options.keyboard } } : {}),
    ...(message.message_thread_id ? { message_thread_id: message.message_thread_id } : {}),
  })
  return z.object({ message_id: z.number().int() }).parse(result).message_id
}

export async function answerCallback(token: string, id: string, text?: string) {
  await telegramRequest(token, "answerCallbackQuery", { callback_query_id: id, ...(text ? { text } : {}) })
}

export async function clearKeyboard(token: string, chatID: number, messageID: number) {
  await telegramRequest(token, "editMessageReplyMarkup", { chat_id: chatID, message_id: messageID, reply_markup: { inline_keyboard: [] } })
}
