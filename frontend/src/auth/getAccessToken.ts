import { userManager } from './AuthProvider'

/**
 * Retrieves the current access token from the OIDC user session.
 * Attempts a silent renew if the token is expired.
 * Returns null if no valid session exists.
 *
 * Uses the same UserManager instance as AuthProvider to share session state.
 */
export async function getAccessToken(): Promise<string | null> {
  const user = await userManager.getUser()

  if (!user || user.expired) {
    try {
      const renewed = await userManager.signinSilent()
      return renewed?.access_token ?? null
    } catch {
      return null
    }
  }

  return user.access_token
}
