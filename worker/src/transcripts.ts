import { DurableObject } from "cloudflare:workers"
import { z } from "zod"

export const transcriptID = z.string().uuid()
export const transcriptSchema = z.object({
  id: transcriptID,
  timeMillis: z.number().int().min(0).max(8_640_000_000_000_000),
  text: z.string().min(1).max(128_000),
  kind: z.string().max(100),
  source: z.string().max(200),
  reply: z.string().max(128_000).nullable(),
}).strict()
export type Transcript = z.infer<typeof transcriptSchema>
type Row = { id: string; payload: string; deleted: number; pending: number }

export class TranscriptArchive extends DurableObject<Env> {
  private serial: Promise<unknown> = Promise.resolve()

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env)
    ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS transcripts (
        id TEXT PRIMARY KEY, time INTEGER NOT NULL, payload TEXT NOT NULL,
        deleted INTEGER NOT NULL DEFAULT 0, pending INTEGER NOT NULL DEFAULT 1
      );
      CREATE INDEX IF NOT EXISTS transcripts_time ON transcripts(deleted, time DESC, id);
      CREATE INDEX IF NOT EXISTS transcripts_pending ON transcripts(pending);
      CREATE VIRTUAL TABLE IF NOT EXISTS transcript_search USING fts5(id UNINDEXED, text, reply, tokenize='unicode61 remove_diacritics 2');
    `)
  }

  private queue<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.serial.then(operation)
    this.serial = result.catch(() => {})
    return result
  }

  private row(id: string): Row | undefined {
    return this.ctx.storage.sql.exec<Row>("SELECT id, payload, deleted, pending FROM transcripts WHERE id = ?", id).toArray()[0]
  }

  private key(id: string): string { return `${this.ctx.id.toString()}/${id}.json` }

  async put(input: Transcript): Promise<{ status: number; saved: boolean }> {
    const entry = transcriptSchema.parse(input)
    return this.queue(async () => {
      const payload = JSON.stringify(entry)
      const existing = this.row(entry.id)
      if (existing?.deleted) return { status: 410, saved: false }
      if (existing && existing.payload !== payload) return { status: 409, saved: false }
      if (!existing) {
        // Schedule recovery before creating pending work, so a crash cannot strand a write.
        await this.ctx.storage.setAlarm(Date.now() + 30_000)
        this.ctx.storage.transactionSync(() => {
          this.ctx.storage.sql.exec("INSERT INTO transcripts(id, time, payload) VALUES (?, ?, ?)", entry.id, entry.timeMillis, payload)
          this.ctx.storage.sql.exec("INSERT INTO transcript_search(id, text, reply) VALUES (?, ?, ?)", entry.id, entry.text, entry.reply ?? "")
        })
      }
      const saved = !this.row(entry.id)?.pending || await this.flushOne(entry.id)
      return { status: saved ? 200 : 202, saved }
    })
  }

  async remove(id: string): Promise<{ pending: boolean }> {
    transcriptID.parse(id)
    return this.queue(async () => {
      await this.ctx.storage.setAlarm(Date.now() + 30_000)
      this.ctx.storage.transactionSync(() => {
        this.ctx.storage.sql.exec(`INSERT INTO transcripts(id, time, payload, deleted, pending) VALUES (?, 0, '', 1, 1)
          ON CONFLICT(id) DO UPDATE SET payload = '', deleted = 1, pending = 1`, id)
        this.ctx.storage.sql.exec("DELETE FROM transcript_search WHERE id = ?", id)
      })
      return { pending: !(await this.flushOne(id)) }
    })
  }

  async get(id: string): Promise<Transcript | null> {
    transcriptID.parse(id)
    return this.queue(async () => {
      const row = this.row(id)
      if (!row || row.deleted) return null
      const object = await this.env.TRANSCRIPTS.get(this.key(id))
      return object ? transcriptSchema.parse(await object.json()) : null
    })
  }

  async search(query: string, offset = 0): Promise<{ entries: Transcript[]; nextOffset: number | null }> {
    z.string().max(500).parse(query)
    z.number().int().min(0).max(100_000).parse(offset)
    const terms = query.match(/[\p{L}\p{N}]+/gu)?.slice(0, 20) ?? []
    if (query.trim() && !terms.length) return { entries: [], nextOffset: null }
    const rows = terms.length
      ? this.ctx.storage.sql.exec<{ payload: string }>(`SELECT t.payload FROM transcript_search
          JOIN transcripts t ON t.id = transcript_search.id
          WHERE transcript_search MATCH ? AND t.deleted = 0
          ORDER BY rank, t.time DESC, t.id LIMIT 51 OFFSET ?`, terms.map(term => `"${term}"*`).join(" AND "), offset).toArray()
      : this.ctx.storage.sql.exec<{ payload: string }>("SELECT payload FROM transcripts WHERE deleted = 0 ORDER BY time DESC, id LIMIT 51 OFFSET ?", offset).toArray()
    return { entries: rows.slice(0, 50).map(row => JSON.parse(row.payload) as Transcript), nextOffset: rows.length > 50 ? offset + 50 : null }
  }

  private async flushOne(id: string): Promise<boolean> {
    const row = this.row(id)
    if (!row?.pending) return true
    try {
      if (row.deleted) await this.env.TRANSCRIPTS.delete(this.key(id))
      else await this.env.TRANSCRIPTS.put(this.key(id), row.payload, { httpMetadata: { contentType: "application/json; charset=utf-8" } })
      this.ctx.storage.sql.exec("UPDATE transcripts SET pending = 0 WHERE id = ?", id)
      return true
    } catch {
      console.warn("transcript_r2_retry")
      await this.ctx.storage.setAlarm(Date.now() + 30_000)
      return false
    }
  }

  async alarm(): Promise<void> {
    await this.queue(async () => {
      await this.ctx.storage.setAlarm(Date.now() + 30_000)
      const rows = this.ctx.storage.sql.exec<{ id: string }>("SELECT id FROM transcripts WHERE pending = 1 LIMIT 5").toArray()
      for (const row of rows) await this.flushOne(row.id)
      if (!this.ctx.storage.sql.exec("SELECT id FROM transcripts WHERE pending = 1 LIMIT 1").toArray().length) await this.ctx.storage.deleteAlarm()
    })
  }
}
