import {
  createContext,
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import { config } from '@/config'

export interface AuthContextValue {
  user: User | null
  isAuthenticated: boolean
  isLoading: boolean
  userRole: string | null
  login: () => Promise<void>
  logout: () => Promise<void>
  getAccessToken: () => Promise<string | null>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

const userManager = new UserManager({
  authority: config.oidcAuthority,
  client_id: config.oidcClientId,
  redirect_uri: `${window.location.origin}/auth/callback`,
  post_logout_redirect_uri: window.location.origin,
  scope: 'openid profile email',
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  automaticSilentRenew: true,
})

interface AuthProviderProps {
  children: ReactNode
}

export function AuthProvider({ children }: AuthProviderProps) {
  const [user, setUser] = useState<User | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    async function loadUser() {
      try {
        const existingUser = await userManager.getUser()
        if (existingUser && !existingUser.expired) {
          setUser(existingUser)
        }
      } catch {
        // User not found or session invalid
      } finally {
        setIsLoading(false)
      }
    }

    void loadUser()

    // Listen for user loaded/unloaded events
    const handleUserLoaded = (loadedUser: User) => setUser(loadedUser)
    const handleUserUnloaded = () => setUser(null)

    userManager.events.addUserLoaded(handleUserLoaded)
    userManager.events.addUserUnloaded(handleUserUnloaded)

    return () => {
      userManager.events.removeUserLoaded(handleUserLoaded)
      userManager.events.removeUserUnloaded(handleUserUnloaded)
    }
  }, [])

  const login = useCallback(async () => {
    await userManager.signinRedirect()
  }, [])

  const logout = useCallback(async () => {
    await userManager.signoutRedirect()
  }, [])

  const getAccessToken = useCallback(async (): Promise<string | null> => {
    const currentUser = await userManager.getUser()
    if (!currentUser || currentUser.expired) {
      try {
        const renewed = await userManager.signinSilent()
        return renewed?.access_token ?? null
      } catch {
        return null
      }
    }
    return currentUser.access_token
  }, [])

  const userRole = useMemo(() => {
    if (!user?.profile) return null
    // Check ID token claims first
    const profile = user.profile as Record<string, unknown>
    const tokenRole =
      (profile['alexandria_role'] as string) ??
      (profile['role'] as string) ??
      null
    return tokenRole
  }, [user])

  // Fetch role from /api/me as fallback when token doesn't include it
  const [apiRole, setApiRole] = useState<string | null>(null)
  const [roleLoading, setRoleLoading] = useState(false)

  useEffect(() => {
    if (!user || user.expired) return
    if (userRole) return // Already have role from token

    setRoleLoading(true)

    async function fetchRole() {
      try {
        const token = user!.access_token
        const response = await fetch(`${config.apiBaseUrl}/api/me`, {
          headers: { Authorization: `Bearer ${token}` },
        })
        if (response.ok) {
          const data = await response.json()
          setApiRole(data.role ?? null)
        }
      } catch {
        // Ignore — role will be null, user gets /catalog
      } finally {
        setRoleLoading(false)
      }
    }

    void fetchRole()
  }, [user, userRole])

  const effectiveRole = userRole ?? apiRole

  const value: AuthContextValue = useMemo(
    () => ({
      user,
      isAuthenticated: !!user && !user.expired,
      isLoading: isLoading || roleLoading,
      userRole: effectiveRole,
      login,
      logout,
      getAccessToken,
    }),
    [user, isLoading, roleLoading, effectiveRole, login, logout, getAccessToken],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export { userManager }
