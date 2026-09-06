import type { OpenCode } from "@opencode-ai/sdk"
import { AbsolutePath } from "@opencode-ai/schema/schema"
import { Skill } from "@opencode-ai/schema/skill"
import { z } from "zod"
import type { PersonalProfile } from "./profile-types"

type Plugin = Parameters<OpenCode.Interface["plugin"]>[0]

export function profileSummary(profile: PersonalProfile) {
  return {
    generatedAt: profile.generatedAt,
    instructions: profile.instructions ? { sourcePath: profile.instructions.sourcePath, sha256: profile.instructions.sha256 } : null,
    skills: profile.skills.map(({ id, name, description, portability, sourcePaths, supportFiles }) => ({ id, name, description, portability, sourcePaths, supportFileCount: supportFiles.length })),
    mcps: profile.mcps,
    plugins: profile.plugins,
    clis: profile.clis,
    gaps: profile.gaps,
  }
}

export async function readProfileFile(profile: PersonalProfile, sourcePath: string, readFile?: (sourcePath: string) => Promise<string>): Promise<string> {
  if (profile.instructions?.sourcePath === sourcePath) return profile.instructions.content
  for (const skill of profile.skills) {
    if (skill.sourcePaths.includes(sourcePath)) return skill.content
    const file = skill.supportFiles.find((file) => file.sourcePath === sourcePath)
    if (!file) continue
    if (!file.text) throw new Error("This support file is binary. Use the tool runner for this asset.")
    if (!readFile) throw new Error("This support file needs the owner's tool runner, which is not connected.")
    return readFile(file.sourcePath)
  }
  throw new Error("File is not in the imported personal profile.")
}

export function personalProfilePlugin(profile: PersonalProfile, options: { readonly readFile?: (sourcePath: string) => Promise<string> } = {}): Plugin {
  return {
    id: "talkt-personal-profile",
    async setup(ctx) {
      const skills = await ctx.skill.transform((editor) => {
        for (const skill of profile.skills) {
          const location = skill.sourcePaths[0]
          if (!location) throw new Error(`Imported skill ${skill.name} has no source path`)
          editor.add({
            id: Skill.ID.make(skill.id),
            name: Skill.Name.make(skill.name),
            location: AbsolutePath.make(location),
            content: `${skill.content}\n\nTalkt file access: relative paths resolve against ${location.slice(0, location.lastIndexOf("/"))}. Use profile_read with kind skill and skillID ${skill.id} to list its support files, then kind file with an exact sourcePath to read them. This skill's portability status is ${skill.portability}. Host-specific tools named in these instructions require an installed adapter; do not claim they are available without checking.`,
            description: skill.description,
            autoinvoke: skill.autoinvoke,
            slash: true,
          })
        }
      })
      const agents = await ctx.agent.transform((editor) => {
        editor.update("build", (agent) => {
          const instructions = profile.instructions?.content
          agent.system = [agent.system, instructions, "Use profile_read to inspect the personal tool, MCP, CLI and skill manifest. The manifest is an inventory, not proof that an account or tool is connected. Do not treat imported host-specific skill instructions as callable APIs unless an adapter provides those tools."].filter(Boolean).join("\n\n")
        })
      })
      const tool = await ctx.tool.transform((editor) => {
        editor.add({
          name: "profile_read",
          description: "Read the personal profile manifest, list one skill's support files, or read an exact indexed file. Support files require the owner's connected tool runner.",
          input: z.discriminatedUnion("kind", [
            z.object({ kind: z.literal("manifest") }),
            z.object({ kind: z.literal("skill"), skillID: z.string() }),
            z.object({ kind: z.literal("file"), sourcePath: z.string() }),
          ]),
          async execute(input) {
            if (input.kind === "file") return { content: await readProfileFile(profile, input.sourcePath, options.readFile) }
            if (input.kind === "skill") {
              const skill = profile.skills.find((skill) => skill.id === input.skillID)
              if (!skill) throw new Error("Skill is not in the imported personal profile.")
              return { content: JSON.stringify({ id: skill.id, name: skill.name, sourcePaths: skill.sourcePaths, supportFiles: skill.supportFiles, portability: skill.portability }) }
            }
            return { content: JSON.stringify(profileSummary(profile)) }
          },
        })
      })
      return async () => { await tool.dispose(); await agents.dispose(); await skills.dispose() }
    },
  }
}
