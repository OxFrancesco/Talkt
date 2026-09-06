import { verifyToken } from "@clerk/backend"

export type AuthConfig = {
  CLERK_JWT_KEY: string
  CLERK_ISSUER: string
  CLERK_AUTHORIZED_PARTIES: string
}

export async function authenticate(request: Request, config: AuthConfig): Promise<string | null> {
  const header = request.headers.get("authorization")
  if (!header?.startsWith("Bearer ")) return null
  const parties = config.CLERK_AUTHORIZED_PARTIES?.split(",").map(value => value.trim()).filter(Boolean)
  if (!config.CLERK_JWT_KEY || !config.CLERK_ISSUER || !parties?.length) {
    throw new Error("Clerk authentication is not configured")
  }
  try {
    const claims = await verifyToken(header.slice(7), {
      jwtKey: config.CLERK_JWT_KEY,
    })
    // Native Clerk sessions omit azp. Check it after signature verification when present.
    if (claims.azp !== undefined && !parties.includes(claims.azp)) return null
    if (claims.iss !== config.CLERK_ISSUER || !claims.sub?.startsWith("user_") ||
        !claims.sid?.startsWith("sess_") || claims.sts === "pending") return null
    return JSON.stringify([claims.iss, claims.sub])
  } catch {
    return null
  }
}
