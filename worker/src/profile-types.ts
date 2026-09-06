import { z } from "zod"

const profileFileSchema = z.object({
  sourcePath: z.string().startsWith("/"),
  relativePath: z.string(),
  bytes: z.number().int().nonnegative(),
  text: z.boolean(),
})

const personalSkillSchema = z.object({
  id: z.string().min(1),
  name: z.string().regex(/^[a-zA-Z0-9_-]+$/),
  description: z.string(),
  content: z.string(),
  sha256: z.string(),
  sourcePaths: z.array(z.string().startsWith("/")).min(1),
  supportFiles: z.array(profileFileSchema),
  autoinvoke: z.boolean(),
  portability: z.enum(["instructions", "runner-required", "host-adapter-required"]),
})

const profileMcpSchema = z.object({
  name: z.string(),
  source: z.string(),
  enabled: z.boolean(),
  transport: z.enum(["remote", "stdio", "host-managed"]),
  command: z.string().nullable(),
  argumentFlags: z.array(z.string()),
  endpointOrigin: z.string().nullable(),
  environmentKeys: z.array(z.string()),
  portability: z.string(),
})

export const personalProfileSchema = z.object({
  version: z.literal(1),
  generatedAt: z.string().nullable(),
  instructions: z.object({ sourcePath: z.string().startsWith("/"), content: z.string(), sha256: z.string() }).nullable(),
  skills: z.array(personalSkillSchema),
  mcps: z.array(profileMcpSchema),
  plugins: z.array(z.object({ name: z.string(), sourcePath: z.string().nullable() })),
  clis: z.array(z.object({ name: z.string(), sourcePath: z.string(), portability: z.string() })),
  gaps: z.array(z.string()),
}).superRefine((profile, ctx) => {
  for (const key of ["id", "name"] as const) {
    const values = profile.skills.map((skill) => skill[key])
    if (new Set(values).size !== values.length) ctx.addIssue({ code: "custom", message: `Skill ${key} values must be unique`, path: ["skills"] })
  }
})

export type ProfileFile = z.infer<typeof profileFileSchema>
export type PersonalSkill = z.infer<typeof personalSkillSchema>
export type ProfileMcp = z.infer<typeof profileMcpSchema>
export type PersonalProfile = z.infer<typeof personalProfileSchema>
export const parsePersonalProfile = (value: unknown): PersonalProfile => personalProfileSchema.parse(value)
