import { Database } from "bun:sqlite";
import { chmod, mkdir } from "node:fs/promises";
import { dirname } from "node:path";
import { validateRunnerResult, type RunnerJob, type RunnerResult } from "../../shared/runner-protocol";

export class Journal {
  private constructor(private readonly db: Database) {}

  static async open(path: string) {
    await mkdir(dirname(path), { recursive: true, mode: 0o700 });
    const db = new Database(path, { create: true });
    await chmod(path, 0o600);
    db.exec("PRAGMA journal_mode = WAL; PRAGMA synchronous = FULL; CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, request TEXT NOT NULL, result TEXT, started_at TEXT NOT NULL)");
    return new Journal(db);
  }

  async run(job: RunnerJob, execute: () => Promise<RunnerResult>): Promise<RunnerResult> {
    const request = JSON.stringify(job.request);
    const existing = this.db.query<{ request: string; result: string | null }, [string]>("SELECT request, result FROM jobs WHERE id = ?").get(job.id);
    if (existing) {
      if (existing.request !== request) return { kind: "error", code: "job_conflict", message: "The job ID was reused with a different request" };
      if (existing.result !== null) return validateRunnerResult(JSON.parse(existing.result));
      const result: RunnerResult = { kind: "error", code: "outcome_unknown", message: "The runner stopped after starting this job. Its effects may already have happened. Inspect the target before requesting a new job." };
      this.complete(job.id, result);
      return result;
    }
    this.db.query("INSERT INTO jobs (id, request, started_at) VALUES (?, ?, ?)").run(job.id, request, new Date().toISOString());
    let result: RunnerResult;
    try {
      result = validateRunnerResult(await execute());
    } catch {
      result = { kind: "error", code: "execution_failed", message: "Runner execution failed. Check the local runner and target state before retrying." };
    }
    this.complete(job.id, result);
    return result;
  }

  private complete(id: string, result: RunnerResult) {
    this.db.query("UPDATE jobs SET result = ? WHERE id = ?").run(JSON.stringify(result), id);
  }

  close() { this.db.close(); }
}
