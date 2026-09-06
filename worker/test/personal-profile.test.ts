import { describe, expect, it } from "vitest"
import { profileSummary, readProfileFile } from "../src/personal-profile"
import type { PersonalProfile } from "../src/profile-types"

const profile: PersonalProfile = {
  version: 1, generatedAt: null,
  instructions: { sourcePath: "/owner/AGENTS.md", content: "Use Bun", sha256: "instructions-hash" },
  skills: [{
    id: "personal-test", name: "test", description: "Test skill", content: "Use scripts/check.py", sha256: "skill-hash", sourcePaths: ["/owner/skill/SKILL.md"], autoinvoke: true, portability: "runner-required",
    supportFiles: [
      { sourcePath: "/owner/skill/scripts/check.py", relativePath: "scripts/check.py", bytes: 20, text: true },
      { sourcePath: "/owner/skill/model.bin", relativePath: "model.bin", bytes: 100, text: false },
    ],
  }],
  mcps: [], plugins: [], clis: [], gaps: [],
}

describe("personal profile access", () => {
  it("returns imported instructions without a runner and excludes their contents from the manifest", async () => {
    expect(await readProfileFile(profile, "/owner/AGENTS.md")).toBe("Use Bun")
    expect(await readProfileFile(profile, "/owner/skill/SKILL.md")).toBe("Use scripts/check.py")
    expect(JSON.stringify(profileSummary(profile))).not.toContain("Use Bun")
    expect(JSON.stringify(profileSummary(profile))).not.toContain("Use scripts/check.py")
  })

  it("only delegates exact text support paths to the runner", async () => {
    const reads: string[] = []
    const read = async (path: string) => { reads.push(path); return "print('ok')" }
    expect(await readProfileFile(profile, "/owner/skill/scripts/check.py", read)).toBe("print('ok')")
    await expect(readProfileFile(profile, "/owner/skill/../.env", read)).rejects.toThrow("not in the imported")
    await expect(readProfileFile(profile, "/owner/skill/model.bin", read)).rejects.toThrow("binary")
    expect(reads).toEqual(["/owner/skill/scripts/check.py"])
  })

  it("reports unavailable runner instead of implying support files were imported", async () => {
    await expect(readProfileFile(profile, "/owner/skill/scripts/check.py")).rejects.toThrow("not connected")
  })
})
