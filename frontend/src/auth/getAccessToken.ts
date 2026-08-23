import { UserManager, WebStorageStateStore } from 'oidc-client-ts'
import { config } from '@/config'

let userManager: UserManager | null = null

function getUserManager(): UserManager {
  if (!userManager) {
    userManager = new UserManager({
      authority: config.oidcAuthority,
      client_id: config.oidcClientId,
      redirect_uri: `${window.location.origin}/auth/callback`,
      post_logout_redirect_uri: window.location.origin,
      scope: 'openid profile email',
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      automaticSilentRenew: true,
    })
  }
  return userManager
}

/**
 * Retrieves the current access token from the OIDC user session.
 * Attempts a silent renew if the token is expired.
 * Returns null if no valid session exists.
 */
export async function getAccessToken(): Promise<string | null> {
  const manager = getUserManager()
  const user = await manager.getUser()

  if (!user || user.expired) {
    try {
      const renewed = await manager.signinSilent()
      return renewed?.access_token ?? null
    } catch {
      return null
    }
  }

  return user.access_token
}
